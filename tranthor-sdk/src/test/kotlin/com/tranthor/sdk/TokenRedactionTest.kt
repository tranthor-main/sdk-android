package com.tranthor.sdk

import com.tranthor.testing.MiniHttpServer
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The push token never reaches the host's onError, whichever path reports the failed Installation event. */
class TokenRedactionTest {
    private val requests = CopyOnWriteArrayList<MiniHttpServer.Request>()
    private val server = MiniHttpServer { request -> requests.add(request); MiniHttpServer.Response(400) }
    private val root: File = tempRoot("tranthor-token-redaction")

    @After fun stop() { server.close(); root.deleteRecursively() }

    private fun tokens(failures: List<BatchFailure>) = failures.flatMap { it.events }
        .filter { it["event"]?.stringValue?.startsWith("Installation ") == true }
        .map { it["properties"]?.objectValue?.get("token")?.stringValue }

    @Test fun aRejectedRegistrationBatchIsReportedWithoutThePushToken() = runBlocking {
        val failures = CopyOnWriteArrayList<BatchFailure>()
        val sdk = configureJvm(Configuration("Basic fixture==", host = server.url, batchSize = 100, flushIntervalMs = 60000, retries = 0,
            storageDirectory = root, onError = { failures.add(it) }))
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        try { sdk.flush() } catch (_: DeliveryFailure) {}
        sdk.shutdown()
        assertEquals(listOf("<redacted>"), tokens(failures))
        val registered = failures.single().events.single { it["event"]?.stringValue == "Installation Registered" }
        assertEquals("com.example.shop", registered["properties"]?.objectValue?.get("appId")?.stringValue)
        assertTrue("What went to the server carried the real token", requests.single().body.toString(Charsets.UTF_8).contains("fcm-token-1"))
    }

    @Test fun installationEventsDroppedByQueueOverflowAreReportedWithoutThePushToken() = runBlocking {
        val failures = CopyOnWriteArrayList<BatchFailure>()
        val sdk = configureJvm(Configuration("Basic fixture==", batchSize = 100, flushIntervalMs = 60000, maxQueueSize = 1,
            storageDirectory = root, onError = { failures.add(it) }), NoopTransport)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.registerInstallation("fcm-token-2", "com.example.shop", InstallationPermission.GRANTED)
        delay(700) // Dropped events are reported together after 500 ms.
        sdk.shutdown()
        val reported = tokens(failures)
        assertTrue("Expected dropped Installation events", reported.isNotEmpty())
        assertEquals(List(reported.size) { "<redacted>" }, reported)
    }

    @Test fun anOrdinaryEventWithATokenPropertyIsReportedUnchanged() = runBlocking {
        val failures = CopyOnWriteArrayList<BatchFailure>()
        val sdk = configureJvm(Configuration("Basic fixture==", host = server.url, batchSize = 100, flushIntervalMs = 60000, retries = 0,
            storageDirectory = root, onError = { failures.add(it) }))
        sdk.track("Password Reset", properties = jsonObjectOf("token" to "reset-123"))
        try { sdk.flush() } catch (_: DeliveryFailure) {}
        sdk.shutdown()
        assertEquals("reset-123", failures.single().events.single()["properties"]?.objectValue?.get("token")?.stringValue)
    }
}
