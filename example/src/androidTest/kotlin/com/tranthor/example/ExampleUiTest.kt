package com.tranthor.example

import android.content.ClipboardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.junit.Assume.assumeTrue
import android.Manifest
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tranthor.sdk.JsonValue
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real example against the local mock (127.0.0.1 via adb reverse). Every assertion about
 * delivery reads the mock's /control/requests; an empty successful flush alone proves nothing.
 * Run with scripts/test-emulator.sh --example (it supplies -e tranthorHost).
 */
@RunWith(AndroidJUnit4::class)
class ExampleUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val target = instrumentation.targetContext
    private lateinit var host: String
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun http(path: String, body: String? = null): String {
        val connection = URL(host + path).openConnection() as HttpURLConnection
        try {
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            assertEquals(200, connection.responseCode)
            return connection.inputStream.use { String(it.readBytes()) }
        } finally { connection.disconnect() }
    }

    private fun requests(): List<JsonValue> = JsonValue.parse(http("/control/requests"))["requests"]?.arrayValue ?: emptyList()

    /** Polls the mock until [count] complete requests are recorded (no flush to make it pass). */
    private fun awaitRequests(count: Int, timeoutMs: Long = 15_000): List<JsonValue> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var records = requests()
        while (System.currentTimeMillis() < deadline) {
            records = requests()
            if (records.size >= count && records.all { it["body"] != null && it["body"] != JsonValue.Null }) break
            Thread.sleep(100)
        }
        Thread.sleep(300)
        return requests()
    }

    private fun events(records: List<JsonValue>): List<JsonValue> = records.flatMap { record ->
        assertEquals("Envelope must contain exactly batch", setOf("batch"), record["body"]?.objectValue?.keys)
        record["body"]?.get("batch")?.arrayValue ?: emptyList()
    }

    /** Push interaction events are the SDK's own reserved track events, carrying only the delivery ID. */
    private fun assertPushEvent(event: JsonValue, name: String, deliveryId: String) {
        assertEquals(name, event["event"]?.stringValue)
        assertEquals(mapOf("deliveryId" to deliveryId), event["properties"]?.objectValue?.mapValues { it.value.stringValue })
        assertEquals("tranthor-sdk-android", event["context"]?.get("library")?.get("name")?.stringValue)
    }

    private fun assertTagged(event: JsonValue, name: String) {
        assertEquals(name, event["event"]?.stringValue)
        val properties = event["properties"]!!
        assertEquals(true, properties["liveTest"]?.booleanValue)
        assertEquals("android-example", properties["source"]?.stringValue)
        assertTrue(Regex("^[0-9a-f-]{36}$").matches(properties["runId"]?.stringValue ?: ""))
        assertEquals("tranthor-sdk-android", event["context"]?.get("library")?.get("name")?.stringValue)
        assertEquals("Android", event["context"]?.get("os")?.get("name")?.stringValue)
    }

    @Before fun setUp() {
        host = InstrumentationRegistry.getArguments().getString("tranthorHost")
            ?: error("Missing tranthorHost; run scripts/test-emulator.sh --example")
        assertTrue("UI tests use the local mock only", host.startsWith("http://127.0.0.1:") || host.startsWith("http://10.0.2.2:"))
        instrumentation.uiAutomation.grantRuntimePermission(target.packageName, Manifest.permission.POST_NOTIFICATIONS)
        http("/control/reset", "{}")
        // Isolation: earlier tests' notifications would be grouped and could absorb the tap.
        target.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        // Non-secret mock fixture; the slow interval proves explicit Flush/Home delivery rather than the timer.
        val intent = Intent(target, MainActivity::class.java)
            .putExtra(LaunchOptions.HOST, host)
            .putExtra(LaunchOptions.WRITE_KEY, "conformance-fixture")
            .putExtra(LaunchOptions.FLUSH_INTERVAL, "60000")
            .putExtra(LaunchOptions.UI_TESTING, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(15_000) { text("configurationStatus") == "Configured" }
    }

    @After fun tearDown() { scenario?.close() }

    /** The copied FCM token is flagged sensitive so the clipboard UI does not preview it (API 33+). */
    @Test fun copiedTokenIsMarkedSensitive() {
        assumeTrue(Build.VERSION.SDK_INT >= 33 && BuildConfig.DEBUG)
        var sensitive: Boolean? = null
        scenario!!.onActivity { activity ->
            val push = (activity.application as ExampleApplication).modelOrDefault().push
            push.receiveToken("fixture-fcm-token")
            // receiveToken posts to main; run the queue before copying.
            Handler(Looper.getMainLooper()).post {
                assertTrue(push.copyToken())
                val description = activity.getSystemService(ClipboardManager::class.java).primaryClipDescription
                sensitive = description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false)
            }
        }
        compose.waitUntil(5_000) { sensitive != null }
        assertEquals(true, sensitive)
    }

    private fun text(tag: String): String = runCatching {
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
    }.getOrDefault("")

    private fun observations(): Int = text("pushObservations").substringAfter(": ").toIntOrNull() ?: -1

    /** Observation state is per process and shared by tests: wait for a NEW observation of [kind]. */
    private fun awaitObservation(kind: String, after: Int, timeoutMs: Long = 20_000) {
        compose.waitUntil(timeoutMs) { observations() > after && text("pushLastObservation") == kind }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
    }

    private fun flushAndWait() {
        click("flush")
        compose.waitUntil(15_000) { text("flushResult") == "Last flush: Succeeded" }
    }

    @Test fun trackThenFlushDeliversOneTaggedEvent() {
        click("track")
        compose.waitUntil(10_000) { text("acceptedCalls").isNotEmpty() && !text("acceptedCalls").endsWith(": 0") }
        flushAndWait()
        val records = awaitRequests(1)
        assertEquals(1, records.size)
        val batch = events(records)
        assertEquals(1, batch.size)
        assertTagged(batch.single(), "Android Example Tagged Event")
        assertEquals("/api/public/apps/batch", records.single()["path"]?.stringValue)
        assertEquals("conformance-fixture", records.single()["headers"]?.get("authorization")?.stringValue)
    }

    @Test fun localNotificationPreviewReportsForegroundObservation() {
        val before = observations()
        click("pushPreview")
        compose.waitUntil(5_000) { text("pushPreviewId").startsWith("Preview ID: preview-") }
        val deliveryId = text("pushPreviewId").removePrefix("Preview ID: ")
        awaitObservation("Notification received", before)
        flushAndWait()
        // The flush can also carry the launch's own events (an Installation Registered when Firebase is configured).
        val received = events(awaitRequests(1)).filter { it["event"]?.stringValue == "Push Received" }
        assertPushEvent(received.single(), "Push Received", deliveryId)
        device.openNotification()
        assertNotNull("The real local notification must be posted", device.wait(Until.findObject(By.textContains(deliveryId.takeLast(8))), 10_000))
        device.pressBack()
    }

    @Test fun tappingNotificationFromHomeReportsOpened() {
        val before = observations()
        click("pushPreview")
        compose.waitUntil(5_000) { text("pushPreviewId").startsWith("Preview ID: preview-") }
        val deliveryId = text("pushPreviewId").removePrefix("Preview ID: ")
        device.pressHome()
        device.openNotification()
        val banner = device.wait(Until.findObject(By.text("Tranthor push preview ${deliveryId.takeLast(8)}")), 20_000)
        assertNotNull("Preview notification did not appear", banner)
        // Click the notification row itself (the title text is not clickable; tapping it can hit the header).
        var row = banner
        while (!row.isClickable && row.parent != null) row = row.parent
        row.click()
        device.wait(Until.hasObject(By.pkg(target.packageName).depth(0)), 10_000)
        awaitObservation("Notification opened", before)
        flushAndWait()
        val opened = events(awaitRequests(1)).filter { it["event"]?.stringValue == "Push Opened" }
        assertEquals(1, opened.size)
        assertPushEvent(opened.single(), "Push Opened", deliveryId)
    }

    /**
     * The same intents scripts/send-emulator-push.sh sends (that script quotes through `adb shell`;
     * UiAutomation does not interpret quotes, so these values contain no spaces). Bypasses FCM entirely.
     */
    @Test fun simulatedPushReceiveAndOpenReportTaggedEvents() {
        val before = observations()
        val marker = "{\"version\":1,\"deliveryId\":\"emulator-ui-test-1\"}"
        shell("am broadcast -n ${target.packageName}/.debug.SimulatedPushReceiver --es tranthor $marker --es title Tranthor-emulator-push --es body Simulated")
        awaitObservation("Notification received", before)
        // An exported-activity intent carrying the marker (what any other app could send) is NOT an open.
        shell("am start -n ${target.packageName}/.MainActivity --es tranthor $marker")
        Thread.sleep(1500)
        assertEquals("Forged launcher intent must not report an open", before + 1, observations())
        shell("am broadcast -n ${target.packageName}/.debug.SimulatedPushReceiver --es tranthor $marker --es interaction opened")
        awaitObservation("Notification opened", before + 1)
        flushAndWait()
        val batch = events(awaitRequests(1))
        assertEquals(listOf("Push Received", "Push Opened"), batch.map { it["event"]?.stringValue })
        batch.forEach { assertPushEvent(it, it["event"]!!.stringValue!!, "emulator-ui-test-1") }
    }

    @Test fun homePressFlushesWithSlowIntervalWithinTenSeconds() {
        click("track")
        compose.waitUntil(10_000) { text("acceptedCalls").isNotEmpty() && !text("acceptedCalls").endsWith(": 0") }
        val pressed = System.currentTimeMillis()
        device.pressHome()
        val records = awaitRequests(1, timeoutMs = 10_000)
        assertEquals("ProcessLifecycleOwner ON_STOP must flush; the 60 s timer cannot", 1, records.size)
        assertTrue(System.currentTimeMillis() - pressed < 11_000)
        assertTagged(events(records).single(), "Android Example Tagged Event")
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { String(it.readBytes()) }
    }
}
