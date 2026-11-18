package com.tranthor.sdk

import com.tranthor.testing.MiniHttpServer
import com.tranthor.push.PushConfiguration
import com.tranthor.push.PushException
import com.tranthor.push.PushInteraction
import com.tranthor.push.PushNotification
import com.tranthor.push.TranthorPush
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** End to end: tranthor-push's public API, this core and its queue, against a local HTTP server. */
class PushIntegrationTest {
    private val requests = CopyOnWriteArrayList<MiniHttpServer.Request>()
    private val server = MiniHttpServer { request -> requests.add(request); MiniHttpServer.Response(204) }
    private val root: File = tempRoot("tranthor-push-integration")

    @After fun stop() { server.close(); root.deleteRecursively() }

    private suspend fun sdk(storeWrite: ((ByteArray, File) -> Unit)? = null) =
        configureJvm(Configuration("Basic push-fixture==", host = server.url, batchSize = 100,
            flushIntervalMs = 60000, storageNamespace = "push", storageDirectory = root), storeWrite = storeWrite)

    /** Every event that reached the server, in order. */
    private fun sent(): List<Map<String, JsonValue>> = requests.flatMap { request ->
        JsonValue.parse(request.body.toString(Charsets.UTF_8)).objectValue!!["batch"]!!.arrayValue!!.map { it.objectValue!! }
    }.filter { it["type"]?.stringValue == "track" } // Not the sign-in's identify.
    private fun Map<String, JsonValue>.event() = this["event"]?.stringValue
    private fun Map<String, JsonValue>.property(name: String) = this["properties"]?.objectValue?.get(name)?.stringValue

    @Test fun forwardedTokenReachesTheServerAsInstallationRegisteredWithTheWriteKey() = runBlocking {
        val sdk = sdk()
        val push = TranthorPush(PushConfiguration("com.example.shop"), sdk, permission = { InstallationPermission.GRANTED })
        sdk.identify("user-1")
        push.registerDeviceToken("fcm-token-1")
        sdk.flush()
        sdk.shutdown()
        assertEquals("Basic push-fixture==", requests.single().headers["authorization"])
        val event = sent().single()
        assertEquals("Installation Registered", event.event())
        assertEquals("com.example.shop", event.property("appId"))
        assertEquals("fcm-token-1", event.property("token"))
        assertEquals("granted", event.property("permissionState"))
        assertEquals("user-1", event["userId"]?.stringValue)
    }

    @Test fun receivedOpenedAndDismissedReachTheServerWithDeliveryIdAndStableMessageIds() = runBlocking {
        val sdk = sdk()
        sdk.identify("user-1")
        val push = TranthorPush(PushConfiguration("com.example.shop"), sdk)
        val notification = PushNotification.fromData(mapOf("tranthor" to """{"version":1,"deliveryId":"delivery-123"}""", "title" to "Hi"))!!
        push.handle(notification, PushInteraction.Received)
        // A foreground observation of the same message is the same receipt, not a second event.
        push.handle(notification, PushInteraction.Foreground)
        push.handle(notification, PushInteraction.Opened())
        push.handle(notification, PushInteraction.Dismissed)
        sdk.flush()
        sdk.shutdown()
        val events = sent().filter { it["type"]?.stringValue == "track" }
        assertEquals(listOf("Push Received", "Push Opened", "Push Dismissed"), events.map { it.event() })
        val ids = events.map { it["messageId"]?.stringValue }
        assertTrue(Regex("^[0-9A-F]{8}-[0-9A-F]{4}-8[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$").matches(ids[0]!!))
        // Reference values of the stable message ID algorithm (tranthor-push's message-id-vectors.tsv).
        assertEquals(listOf("1AB91B92-E51A-870E-85ED-98EA62BDF64E", "B18F63EE-5DB4-8A75-8190-F27FEFA3F795"), ids.drop(1))
        for (event in events) {
            assertEquals(mapOf("deliveryId" to "delivery-123"), event["properties"]?.objectValue?.mapValues { it.value.stringValue })
            assertEquals("user-1", event["userId"]?.stringValue)
        }
    }

    private fun notification(id: String) = PushNotification.fromMarker(PushNotification.marker(id))!!

    @Test fun observationsAreIgnoredWhileOptedOutAndResumeAfterOptIn() = runBlocking {
        val sdk = sdk()
        sdk.identify("user-1")
        val push = TranthorPush(PushConfiguration("com.example.shop"), sdk)
        sdk.optOut()
        assertTrue("A recognized notification is still reported as handled", push.handle(notification("d-1"), PushInteraction.Foreground))
        sdk.optIn()
        push.handle(notification("d-1"), PushInteraction.Foreground)
        sdk.flush()
        sdk.shutdown()
        assertEquals(listOf("Push Received"), sent().map { it.event() })
    }

    @Test fun anUnrecognizedPayloadIsNotHandled() = runBlocking {
        val sdk = sdk()
        val push = TranthorPush(PushConfiguration("com.example.shop"), sdk)
        assertFalse(push.handle(null, PushInteraction.Foreground))
        sdk.flush()
        sdk.shutdown()
        assertEquals(emptyList<String?>(), sent().map { it.event() })
    }

    @Test fun concurrentDuplicateObservationsAreReportedOnce() = runBlocking {
        val sdk = sdk()
        sdk.identify("user-1")
        val push = TranthorPush(PushConfiguration("com.example.shop"), sdk)
        val payload = notification("d-1")
        (1..26).map { async(Dispatchers.Default) { push.handle(payload, PushInteraction.Foreground) } }.awaitAll()
        push.handle(payload, PushInteraction.Foreground)
        push.handle(payload, PushInteraction.Opened())
        sdk.flush()
        sdk.shutdown()
        assertEquals("Received and opened are separate observations", listOf("Push Received", "Push Opened"), sent().map { it.event() })
    }

    @Test fun duplicateMemoryIsBounded() = runBlocking {
        val sdk = sdk()
        sdk.identify("user-1")
        val push = TranthorPush(PushConfiguration("com.example.shop", recentEventLimit = 2), sdk)
        for (id in listOf("a", "b", "c", "a")) push.handle(notification(id), PushInteraction.Foreground)
        sdk.flush()
        sdk.shutdown()
        assertEquals("The oldest observation is forgotten once the limit is exceeded",
            listOf("a", "b", "c", "a"), sent().map { it.property("deliveryId") })
    }

    @Test fun invalidApplicationIdsAreRejected() = runBlocking {
        val sdk = sdk()
        for (id in listOf("", ".bad", "bad..name", "bad/name", "single", "a.", "com.1bad", "com.bad-name")) {
            assertThrows(id, PushException.InvalidApplicationId::class.java) { TranthorPush(PushConfiguration(id), sdk) }
        }
        sdk.shutdown()
    }

    @Test fun anEmptyDeviceTokenIsRejectedAndNothingIsRegistered() = runBlocking {
        val sdk = sdk()
        sdk.identify("user-1")
        val push = TranthorPush(PushConfiguration("com.example.shop"), sdk)
        try { push.registerDeviceToken(""); fail("An empty token must be rejected") } catch (_: PushException.EmptyDeviceToken) {}
        assertFalse(push.hasDeviceToken)
        sdk.flush()
        sdk.shutdown()
        assertEquals(emptyList<String?>(), sent().map { it.event() })
    }

    @Test fun refreshingPermissionBeforeAnyTokenSendsNothing() = runBlocking {
        val sdk = sdk()
        sdk.identify("user-1")
        val push = TranthorPush(PushConfiguration("com.example.shop"), sdk, permission = { InstallationPermission.GRANTED })
        push.refreshPermission()
        sdk.flush()
        sdk.shutdown()
        assertEquals(emptyList<String?>(), sent().map { it.event() })
    }

    @Test fun foregroundAndReceivedForTheSameDeliveryAreOneReceiptWithOneMessageId() = runBlocking {
        val sdk = sdk()
        sdk.identify("user-1")
        val push = TranthorPush(PushConfiguration("com.example.shop"), sdk)
        push.handle(notification("d-1"), PushInteraction.Foreground)
        push.handle(notification("d-1"), PushInteraction.Received)
        sdk.flush()
        sdk.shutdown()
        val receipts = sent().filter { it.event() == "Push Received" }
        assertEquals("The second observation is deduped", 1, receipts.size)
        // The same id the Received interaction alone produces, so a server drops a repeat of it.
        val alone = sdk()
        alone.identify("user-2")
        TranthorPush(PushConfiguration("com.example.shop"), alone).handle(notification("d-1"), PushInteraction.Received)
        alone.flush()
        alone.shutdown()
        val ids = sent().filter { it.event() == "Push Received" }.map { it["messageId"]?.stringValue }
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
    }

    @Test fun anObservationWhoseTrackFailedIsNotRememberedAndARetryIsSent() = runBlocking {
        var failWrites = false
        val sdk = sdk(storeWrite = { bytes, file ->
            if (failWrites && file.parentFile.name == "queue") throw java.io.IOException("disk full")
            TempRenameFileIo.write(file, bytes)
        })
        sdk.identify("user-1")
        val push = TranthorPush(PushConfiguration("com.example.shop"), sdk)
        failWrites = true
        try { push.handle(notification("d-1"), PushInteraction.Received); fail("The failed track must reach the caller") } catch (_: TranthorException) {}
        failWrites = false
        assertTrue(push.handle(notification("d-1"), PushInteraction.Received))
        sdk.flush()
        sdk.shutdown()
        assertEquals(listOf("Push Received"), sent().map { it.event() })
    }

    @Test fun aChangedOsPermissionReregistersOnlyWhenItChanges() = runBlocking {
        val sdk = sdk()
        var permission = InstallationPermission.GRANTED
        val push = TranthorPush(PushConfiguration("com.example.shop"), sdk, permission = { permission })
        push.refreshPermission() // No token yet: nothing to register.
        sdk.identify("user-1")
        push.registerDeviceToken("fcm-token-1")
        push.refreshPermission()
        permission = InstallationPermission.DENIED
        push.refreshPermission()
        push.refreshPermission()
        sdk.flush()
        sdk.shutdown()
        assertEquals(listOf("granted", "denied"), sent().map { it.property("permissionState") })
    }

    @Test fun aTokenForwardedWhileAnEarlierOneIsStillRegisteringEndsAsTheRegisteredToken() = runBlocking {
        val sdk = sdk()
        sdk.identify("user-1")
        // The launch-time token's registration is held up (here, while it reads the OS permission)
        // when onNewToken delivers a newer token.
        val firstIsRegistering = CountDownLatch(1)
        val letFirstFinish = CountDownLatch(1)
        var reads = 0
        val push = TranthorPush(PushConfiguration("com.example.shop"), sdk, permission = {
            val first = synchronized(this@PushIntegrationTest) { reads++ == 0 }
            if (first) { firstIsRegistering.countDown(); letFirstFinish.await(10, TimeUnit.SECONDS) }
            InstallationPermission.GRANTED
        })
        val launchToken = launch(Dispatchers.Default) { push.registerDeviceToken("launch-token") }
        assertEquals(true, firstIsRegistering.await(10, TimeUnit.SECONDS))
        val newToken = launch(Dispatchers.Default) { push.registerDeviceToken("new-token") }
        withTimeoutOrNull(1000) { newToken.join() }
        letFirstFinish.countDown()
        launchToken.join()
        newToken.join()
        sdk.flush()
        sdk.shutdown()
        val installations = sent().map { it.event() to it.property("token") }
        // The newest token is the one left registered, and it is never removed.
        assertEquals("Installation Registered" to "new-token", installations.last())
        assertEquals(emptyList<Pair<String?, String?>>(), installations.filter { it == ("Installation Removed" to "new-token") })
    }
}
