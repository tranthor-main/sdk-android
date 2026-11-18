package com.tranthor.example

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.messaging.RemoteMessage
import com.tranthor.push.PushConfiguration
import com.tranthor.push.PushContent
import com.tranthor.push.TranthorMessagingService
import com.tranthor.push.TranthorNotificationStyle
import com.tranthor.push.TranthorNotifications
import com.tranthor.push.TranthorPush
import com.tranthor.sdk.Configuration
import com.tranthor.sdk.JsonValue
import com.tranthor.sdk.TranthorSDK
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SDK-owned display path of [TranthorMessagingService] with [TranthorMessagingService.tranthorPush] and
 * [TranthorMessagingService.notificationStyle] overridden, driven through its public onMessageReceived with real
 * RemoteMessages. Notifications are read from the NotificationManager, events from the local mock's
 * /control/requests. Run with scripts/test-emulator.sh --example (it supplies -e tranthorHost).
 */
@RunWith(AndroidJUnit4::class)
class TranthorMessagingServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val notifications = target.getSystemService(NotificationManager::class.java)
    private lateinit var host: String
    private lateinit var root: File
    private var sdk: TranthorSDK? = null

    /** A host's subclass, constructed directly (never started by the OS) and attached to the app's context. */
    private class HostService(
        private val push: suspend () -> TranthorPush?,
        private val style: () -> TranthorNotificationStyle?,
    ) : TranthorMessagingService() {
        val others = ArrayList<RemoteMessage>()
        fun attach(context: Context) = attachBaseContext(context)
        override suspend fun tranthorPush(): TranthorPush? = push()
        override fun notificationStyle(): TranthorNotificationStyle? = style()
        override fun onOtherMessage(message: RemoteMessage) { others.add(message) }
    }

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

    private fun events(): List<JsonValue> = (JsonValue.parse(http("/control/requests"))["requests"]?.arrayValue ?: emptyList())
        .flatMap { it["body"]?.get("batch")?.arrayValue ?: emptyList() }

    @Before fun setUp() {
        host = InstrumentationRegistry.getArguments().getString("tranthorHost")
            ?: error("Missing tranthorHost; run scripts/test-emulator.sh --example")
        assertTrue("Tests use the local mock only", host.startsWith("http://127.0.0.1:") || host.startsWith("http://10.0.2.2:"))
        instrumentation.uiAutomation.grantRuntimePermission(target.packageName, Manifest.permission.POST_NOTIFICATIONS)
        http("/control/reset", "{}")
        notifications.cancelAll()
        root = File(target.cacheDir, "messaging-service-${UUID.randomUUID()}")
    }

    @After fun tearDown() {
        runBlocking { sdk?.shutdown() }
        notifications.cancelAll()
        root.deleteRecursively()
    }

    private var push: TranthorPush? = null

    /** Configures the SDK on the mock once and binds TranthorPush to it, as a host's tranthorPush() would. */
    private suspend fun configurePush(): TranthorPush = push ?: run {
        val configured = TranthorSDK.configure(target, Configuration("conformance-fixture", host = host, flushIntervalMs = 60000,
            storageNamespace = "messaging-service", storageDirectory = root))
        sdk = configured
        TranthorPush.bind(target, PushConfiguration(target.packageName), configured).also { push = it }
    }

    private fun style() = TranthorNotificationStyle(R.drawable.ic_notification, "tranthor-test-default", "Tranthor test",
        Intent(target, MainActivity::class.java))

    private fun tranthorMessage(deliveryId: String, extra: Map<String, String> = emptyMap()): RemoteMessage =
        RemoteMessage.Builder("test@fcm.googleapis.com").setMessageId(UUID.randomUUID().toString())
            .setData(mapOf("tranthor" to """{"version":1,"deliveryId":"$deliveryId"}""", "title" to "Spring sale",
                "body" to "Twenty percent off today") + extra).build()

    private fun shown(): List<StatusBarNotification> = notifications.activeNotifications.filter { it.packageName == target.packageName }

    private fun awaitShown(count: Int, timeoutMs: Long = 5000): List<StatusBarNotification> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (shown().size < count && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return shown()
    }

    @Test fun aDataOnlyTranthorPushIsShownFromDataTitleAndBody() {
        val service = HostService(push = { configurePush() }, style = { style() }).apply { attach(target) }
        service.onMessageReceived(tranthorMessage("delivery-shown"))
        val notification = awaitShown(1).single().notification
        assertEquals("Spring sale", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Twenty percent off today", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test fun aStyleThatIsReadyOnlyAfterConfigurationStillShowsThePushOnAColdStart() {
        // A host whose style depends on the configured SDK (for example on a remote setting) has none before tranthorPush().
        var ready: TranthorNotificationStyle? = null
        val service = HostService(push = { configurePush().also { ready = style() } }, style = { ready }).apply { attach(target) }
        service.onMessageReceived(tranthorMessage("delivery-cold-start"))
        assertEquals(listOf("Spring sale"), awaitShown(1).map { it.notification.extras.getString(Notification.EXTRA_TITLE) })
    }

    @Test fun aFailingTranthorPushStillShowsThePush() {
        val service = HostService(push = { throw IllegalStateException("configuration failed") }, style = { style() })
            .apply { attach(target) }
        service.onMessageReceived(tranthorMessage("delivery-failed-configuration"))
        assertEquals(listOf("Spring sale"), awaitShown(1).map { it.notification.extras.getString(Notification.EXTRA_TITLE) })
    }

    @Test fun aChannelTheAppNeverCreatedFallsBackToTheDefaultChannel() {
        val service = HostService(push = { configurePush() }, style = { style() }).apply { attach(target) }
        service.onMessageReceived(tranthorMessage("delivery-missing-channel", mapOf("channelId" to "channel-the-app-never-created")))
        assertEquals(listOf("tranthor-test-default"), awaitShown(1).map { it.notification.channelId })
    }

    @Test fun theSameMessageArrivingTwiceShowsOneNotificationAndSendsPushReceivedOnce() {
        val service = HostService(push = { configurePush() }, style = { style() }).apply { attach(target) }
        val message = tranthorMessage("delivery-twice")
        service.onMessageReceived(message)
        service.onMessageReceived(message)
        Thread.sleep(500)
        assertEquals(listOf("Spring sale"), awaitShown(1).map { it.notification.extras.getString(Notification.EXTRA_TITLE) })
        runBlocking { sdk!!.flush() }
        // Filtered by this test's delivery: the example app's own model shares the process and the mock.
        val received = events().filter { it["event"]?.stringValue == "Push Received" &&
            it["properties"]?.get("deliveryId")?.stringValue == "delivery-twice" }
        assertEquals(listOf(mapOf("deliveryId" to "delivery-twice")),
            received.map { event -> event["properties"]?.objectValue?.mapValues { it.value.stringValue } })
    }

    @Test fun aMessageWithoutTheTranthorMarkerIsLeftToTheHost() {
        val service = HostService(push = { configurePush() }, style = { style() }).apply { attach(target) }
        val other = RemoteMessage.Builder("test@fcm.googleapis.com").setMessageId(UUID.randomUUID().toString())
            .setData(mapOf("title" to "Another SDK", "body" to "Not ours")).build()
        service.onMessageReceived(other)
        Thread.sleep(500)
        assertEquals(emptyList<StatusBarNotification>(), shown())
        assertEquals(listOf("Another SDK"), service.others.map { it.data["title"] })
        // The example app's own model shares this process and the mock, so assert only on what this service could send.
        assertEquals("tranthorPush() is not awaited for another SDK's message", null, sdk)
        assertEquals(emptyList<JsonValue>(), events().filter { it["event"]?.stringValue == "Push Received" })
    }

    /** The OS drops a notification without an error when POST_NOTIFICATIONS is denied, so show() must check it itself. */
    @Test fun showReturnsFalseAndPostsNothingWhenNotificationsCannotBePosted() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val denied = object : ContextWrapper(target) {
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        }
        val content = PushContent.fromData(mapOf("tranthor" to """{"version":1,"deliveryId":"delivery-denied"}""", "title" to "Spring sale"))!!
        assertFalse(TranthorNotifications.show(denied, content, style()))
        Thread.sleep(300)
        assertEquals(emptyList<StatusBarNotification>(), shown())
    }
}
