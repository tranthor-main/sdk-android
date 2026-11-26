package com.tranthor.sdk

import com.tranthor.testing.MiniHttpServer
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opting out removes the push Installation on the server. The one `Installation Removed` is
 * the single exception to "opt-out discards everything". Asserted on what reaches a local HTTP server.
 */
class OptOutRemovalTest {
    private val requests = CopyOnWriteArrayList<MiniHttpServer.Request>()
    @Volatile private var status = 204
    @Volatile private var respond: (MiniHttpServer.Request) -> Int = { status }
    private val server = MiniHttpServer { request -> requests.add(request); MiniHttpServer.Response(respond(request)) }
    private val root: File = tempRoot("tranthor-opt-out-removal")

    @After fun stop() { server.close(); root.deleteRecursively() }

    /**
     * A short [flushIntervalMs] is real time, so on a slow machine the interval could elapse between the test's setup calls
     * and its `optOut()` and send the queued identify and registration first. Unless [gated] is false, an SDK that is not
     * opted out yet therefore never lets the interval elapse until it has been seen opted out (an instance restarted opted
     * out runs freely at once). The interval is still real time after that, which is what the tests about it measure.
     */
    private suspend fun sdk(
        host: String = server.url, flushIntervalMs: Int = 60000, storeWrite: ((ByteArray, File) -> Unit)? = null,
        clock: () -> Long = System::currentTimeMillis, retries: Int = 0, maxQueueSize: Int = 10_000,
        onError: ((BatchFailure) -> Unit)? = null, sleeper: ManualSleeper? = null, gated: Boolean = true,
    ): TranthorSDK {
        val created = java.util.concurrent.atomic.AtomicReference<TranthorSDK?>()
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
        val gate: (suspend (Double) -> Unit)? = if (!gated || sleeper != null) null else { ms ->
            if (ms == flushIntervalMs.toDouble()) {
                while (!opened.get()) { if (created.get()?.isOptedOut == true) opened.set(true) else delay(1) }
            }
            sleepMilliseconds(ms)
        }
        return configureJvm(Configuration("Basic push-fixture==", host = host, batchSize = 100, flushIntervalMs = flushIntervalMs,
            retries = retries, baseDelay = 10, storageNamespace = "opt-out", storageDirectory = root, maxQueueSize = maxQueueSize,
            onError = onError), storeWrite = storeWrite, sleeper = sleeper, sleep = gate, platform = JvmPlatform(clock = clock))
            .also { created.set(it) }
    }

    /** Every event that reached the server with a 2xx or not, in arrival order. */
    private fun sent(): List<Map<String, JsonValue>> = requests.flatMap { request ->
        JsonValue.parse(request.body.toString(Charsets.UTF_8)).objectValue!!["batch"]!!.arrayValue!!.map { it.objectValue!! }
    }
    private fun Map<String, JsonValue>.event() = this["event"]?.stringValue
    private fun Map<String, JsonValue>.time() = java.time.Instant.parse(this["timestamp"]!!.stringValue)
    private fun Map<String, JsonValue>.properties() = this["properties"]?.objectValue?.mapValues { it.value.stringValue }

    @Test fun optOutDeliversExactlyOneInstallationRemovedForTheCurrentTokenAndNothingElse() = runBlocking {
        val sdk = sdk()
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.track("Queued Before Opt-out")
        sdk.optOut()
        sdk.track("Tracked While Opted Out")
        waitUntil { sent().isNotEmpty() }
        delay(300) // Observation window: nothing else may follow.
        sdk.shutdown()
        val removed = sent().single()
        // No automatic context: it is sent after consent was withdrawn.
        assertEquals(setOf("messageId", "timestamp", "userId", "type", "event", "properties"), removed.keys)
        assertEquals("track", removed["type"]?.stringValue)
        assertEquals("Installation Removed", removed.event())
        assertEquals("user-1", removed["userId"]?.stringValue)
        assertEquals(mapOf("platform" to "android", "appId" to "com.example.shop", "token" to "fcm-token-1"), removed.properties())
    }

    @Test fun aRemovalMadeOfflineIsDeliveredAfterRestartWhileStillOptedOut() = runBlocking {
        val offline = sdk(host = "http://127.0.0.1:1")
        offline.identify("user-1")
        offline.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        offline.optOut()
        offline.shutdown()
        val restarted = sdk()
        restarted.track("Tracked While Opted Out")
        waitUntil { sent().isNotEmpty() }
        delay(300)
        restarted.shutdown()
        val removed = sent().single()
        assertEquals("Installation Removed", removed.event())
        assertEquals("user-1", removed["userId"]?.stringValue)
        assertEquals(mapOf("platform" to "android", "appId" to "com.example.shop", "token" to "fcm-token-1"), removed.properties())
        assertEquals(true, restarted.isOptedOut)
    }

    @Test fun aRetryableFailureGetsOneAttemptCycleThenNothingOnAnyInterval() = runBlocking {
        status = 503
        val sdk = sdk(flushIntervalMs = 100, retries = 2)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { requests.size >= 3 } // The first attempt and the two configured retries.
        status = 204 // Back online, but no trigger comes.
        delay(600) // Six flush intervals.
        // No silent loop either: no timer armed and no (empty) cycle running (test hooks).
        assertEquals(null, sdk.pendingTimer())
        assertEquals(false, sdk.deliveryCycleActive())
        sdk.shutdown()
        assertEquals(3, requests.size)
        assertEquals(1, sent().map { it["messageId"] }.toSet().size)
        assertEquals(setOf("Installation Removed"), sent().map { it.event() }.toSet())
    }

    @Test fun aProcessKilledBetweenTheConsentAndStateWritesOfOptOutStillRemovesTheInstallation() = runBlocking {
        // optOut() writes consent.json first; the process dies before state.json follows.
        var optingOut = false
        var killed = false
        val dying = sdk(host = "http://127.0.0.1:1", storeWrite = { bytes, file ->
            if (killed) throw java.io.IOException("process killed")
            TempRenameFileIo.write(file, bytes)
            if (optingOut && file.name == "consent.json") killed = true
        })
        dying.identify("user-1")
        dying.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        dying.track("Queued Before Opt-out")
        optingOut = true
        runCatching { dying.optOut() }
        dying.shutdown()
        val restarted = sdk()
        waitUntil { sent().isNotEmpty() }
        delay(300)
        restarted.shutdown()
        val removed = sent().single()
        assertEquals("Installation Removed", removed.event())
        assertEquals("user-1", removed["userId"]?.stringValue)
        assertEquals(mapOf("platform" to "android", "appId" to "com.example.shop", "token" to "fcm-token-1"), removed.properties())
        assertEquals(true, restarted.isOptedOut)
    }

    @Test fun optingBackInMakesOneNewCycleThatSendsTheRemovalFirstAndRegistersStrictlyLater() = runBlocking {
        // The first request is answered 503 and every later one 204, decided as each request arrives (the hooks of other tests
        // count requests the same way), so the test never races its own change of `status` against the first response.
        respond = { if (requests.size == 1) 503 else 204 }
        // The flush interval never elapses (nothing releases the sleeper), so the queued identify and registration cannot
        // be sent by a timer before optOut() on a slow machine: only the opt-out removal is ever the first request.
        val sleeper = ManualSleeper()
        val sdk = sdk(flushIntervalMs = 100, sleeper = sleeper)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { requests.size >= 1 }
        waitUntil { !sdk.deliveryCycleActive() } // the exhausted cycle has ended
        // An exhausted cycle waits for a trigger: it arms no timer and starts no cycle.
        assertNull(sdk.pendingTimer())
        assertEquals(1, requests.size)
        sdk.optIn()
        waitUntil { sent().any { it.event() == "Installation Registered" } }
        delay(300)
        sdk.shutdown()
        val accepted = sent().drop(1)
        assertEquals(listOf("Installation Removed" to "fcm-token-1", "Installation Registered" to "fcm-token-1"),
            accepted.map { it.event() to it.properties()?.get("token") })
        val (removed, registered) = accepted
        // The server applies a token's changes by event time, so the token ends active in any arrival order.
        assertTrue("Registered ${registered.time()} must be after Removed ${removed.time()}", registered.time() > removed.time())
    }

    @Test fun afterARestartWithTheClockSetBackTheRegistrationIsStillLaterThanThePendingRemoval() = runBlocking {
        val offline = sdk(host = "http://127.0.0.1:1", clock = { 1_800_000_060_000 })
        offline.identify("user-1")
        offline.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        offline.optOut()
        offline.shutdown()
        // The device clock went back a minute (for example a network time correction) before the next start.
        val restarted = sdk(clock = { 1_800_000_000_000 })
        restarted.optIn()
        restarted.flush()
        restarted.shutdown()
        val (removed, registered) = sent()
        assertEquals("Installation Removed", removed.event())
        assertEquals("2027-01-15T08:01:00.001Z", removed["timestamp"]?.stringValue)
        assertEquals("Installation Registered", registered.event())
        assertEquals("2027-01-15T08:01:00.002Z", registered["timestamp"]?.stringValue)
    }

    @Test fun aTokenRefreshedWhileOptedOutIsStoredAndRegisteredOnlyAtOptIn() = runBlocking {
        val sdk = sdk()
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { sent().isNotEmpty() }
        sdk.registerInstallation("fcm-token-2", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut() // A repeated opt-out removes nothing more.
        delay(300)
        assertEquals(listOf("Installation Removed" to "fcm-token-1"), sent().map { it.event() to it.properties()?.get("token") })
        sdk.optIn()
        sdk.flush()
        sdk.shutdown()
        assertEquals(listOf("Installation Removed" to "fcm-token-1", "Installation Registered" to "fcm-token-2"),
            sent().map { it.event() to it.properties()?.get("token") })
    }

    @Test fun offlineOptOutOptInOptOutDeliversOneRemovalPerTokenStampedAfterTheRegistrationBetween() = runBlocking {
        var now = 1_800_000_000_000
        val offline = sdk(host = "http://127.0.0.1:1", clock = { now })
        offline.identify("user-1")
        offline.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        offline.optOut()
        now += 1000
        offline.optIn()
        now += 1000
        offline.registerInstallation("fcm-token-2", "com.example.shop", InstallationPermission.GRANTED)
        now += 1000
        offline.optOut()
        offline.shutdown()
        val restarted = sdk()
        waitUntil { sent().size >= 2 }
        delay(300)
        restarted.shutdown()
        // fcm-token-1: removed after its re-registration at opt-in (and after its refresh removal, discarded
        // with the queue). fcm-token-2: removed by the second opt-out.
        assertEquals(listOf(
            Triple("Installation Removed", "fcm-token-1", "2027-01-15T08:00:03.000Z"),
            Triple("Installation Removed", "fcm-token-2", "2027-01-15T08:00:03.001Z"),
        ), sent().map { Triple(it.event(), it.properties()?.get("token"), it["timestamp"]?.stringValue) })
    }

    @Test fun optingOutAgainBeforeTheFirstRemovalIsDeliveredStillSendsOneRemovalForTheToken() = runBlocking {
        var now = 1_800_000_000_000
        val offline = sdk(host = "http://127.0.0.1:1", clock = { now })
        offline.identify("user-1")
        offline.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        offline.optOut()
        now += 1000
        offline.optIn()
        now += 1000
        offline.optOut()
        offline.shutdown()
        val restarted = sdk()
        waitUntil { sent().isNotEmpty() }
        delay(300)
        restarted.shutdown()
        assertEquals(listOf(Triple("Installation Removed", "fcm-token-1", "2027-01-15T08:00:02.000Z")),
            sent().map { Triple(it.event(), it.properties()?.get("token"), it["timestamp"]?.stringValue) })
    }

    @Test fun aRejectedRemovalIsNotRetriedOrResentAfterRestart() = runBlocking {
        status = 400
        val sdk = sdk(flushIntervalMs = 100)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { requests.size >= 1 }
        delay(400) // Several intervals.
        sdk.handleLifecycle(LifecycleEvent.STOP, 1)
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 2)
        sdk.optOut()
        delay(300)
        sdk.shutdown()
        val restarted = sdk(flushIntervalMs = 100)
        delay(400)
        restarted.shutdown()
        assertEquals(listOf("Installation Removed"), sent().map { it.event() })
    }

    @Test fun optOutWhileNobodyIsSignedInSendsNoRemovalBecauseNoPhoneWasRegistered() = runBlocking {
        val sdk = sdk(flushIntervalMs = 100)
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        delay(400) // Several intervals.
        sdk.shutdown()
        assertEquals(emptyList<String?>(), sent().map { it.event() })
    }

    @Test fun optInWhileNobodyIsSignedInRegistersNothing() = runBlocking {
        val sdk = sdk()
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        sdk.optIn()
        sdk.track("Viewed")
        sdk.flush()
        sdk.shutdown()
        assertEquals(listOf("Viewed"), sent().map { it.event() })
    }

    @Test fun aRejectedRemovalIsReportedToOnErrorOnceWithoutThePushToken() = runBlocking {
        status = 400
        val failures = CopyOnWriteArrayList<BatchFailure>()
        val sdk = sdk(flushIntervalMs = 100, onError = { failures.add(it) })
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { failures.size >= 1 }
        delay(300) // Several intervals: it is reported once.
        sdk.shutdown()
        val failure = failures.single()
        assertEquals(400, failure.error.status)
        assertEquals(1, failure.attempts)
        val event = failure.events.single()
        assertEquals("Installation Removed", event.event())
        assertEquals(mapOf("platform" to "android", "appId" to "com.example.shop", "token" to "<redacted>"), event.properties())
        // What went to the server carried the real token.
        assertEquals("fcm-token-1", sent().single().properties()!!["token"])
    }

    @Test fun afterOptInNothingQueuedIsSentUntilThePendingRemovalIsAccepted() = runBlocking {
        val removalAccepted = java.util.concurrent.atomic.AtomicBoolean(false)
        respond = { request ->
            val removal = request.body.toString(Charsets.UTF_8).contains("\"Installation Removed\"")
            if (removal && !removalAccepted.get()) 503 else 204
        }
        val sdk = sdk(flushIntervalMs = 100)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { requests.size >= 1 }
        sdk.optIn() // One new cycle, which fails too.
        waitUntil { requests.size >= 2 }
        sdk.track("Tracked After Opt-in")
        delay(400) // Several intervals with the Removed still failing.
        assertEquals(listOf("Installation Removed", "Installation Removed"), sent().map { it.event() })
        val failures = requests.size
        removalAccepted.set(true)
        delay(300) // Still nothing on a timer, although the queue is waiting.
        assertEquals(failures, requests.size)
        sdk.handleLifecycle(LifecycleEvent.STOP, 1) // Its background flush is not a trigger.
        assertEquals(failures, requests.size)
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 2) // The hook's ON_START.
        waitUntil { sent().any { it.event() == "Tracked After Opt-in" } }
        sdk.shutdown()
        assertEquals(listOf("Installation Removed", "Installation Registered", "Tracked After Opt-in"),
            sent().drop(failures).map { it.event() })
    }

    @Test fun flushWhileTheRemovalWaitsForATriggerFailsAtOnceWithoutARequest() = runBlocking {
        status = 503
        val sdk = sdk(flushIntervalMs = 100)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { requests.size >= 1 }
        sdk.optIn() // One new cycle, which fails too.
        waitUntil { requests.size >= 2 }
        delay(100)
        sdk.track("Tracked After Opt-in")
        val error = runCatching { kotlinx.coroutines.withTimeout(2000) { sdk.flush() } }.exceptionOrNull()
        sdk.shutdown()
        assertEquals(DeliveryFailure(503, "HTTP delivery failure"), error)
        assertEquals(2, requests.size)
    }

    @Test fun aTriggerDuringARunningCycleStillGetsOneCycleOfItsOwn() = runBlocking {
        val firstResponse = java.util.concurrent.CountDownLatch(1)
        respond = { if (requests.size == 1) firstResponse.await(5, java.util.concurrent.TimeUnit.SECONDS); 503 }
        val sdk = sdk(flushIntervalMs = 100)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { requests.size >= 1 } // The opt-out cycle's request is in flight.
        sdk.optIn() // A trigger while that cycle is still running.
        firstResponse.countDown()
        waitUntil { requests.size >= 2 }
        delay(400)
        sdk.shutdown()
        assertEquals(listOf("Installation Removed", "Installation Removed"), sent().map { it.event() })
    }

    @Test fun eachReturnToTheForegroundMakesOneNewCycle() = runBlocking {
        status = 503
        val sdk = sdk(flushIntervalMs = 100)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { requests.size >= 1 }
        // The process lifecycle hook delivers ON_STOP as STOP, and ON_START and ON_RESUME as FOREGROUND.
        sdk.handleLifecycle(LifecycleEvent.STOP, 1)
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 2)
        waitUntil { requests.size >= 2 }
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 3) // ON_RESUME of the same return: no second cycle.
        sdk.handleLifecycle(LifecycleEvent.PAUSE, 4)
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 5) // Paused but never stopped: not a return.
        delay(400)
        assertEquals(2, requests.size)
        sdk.handleLifecycle(LifecycleEvent.STOP, 6)
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 7)
        waitUntil { requests.size >= 3 }
        delay(400)
        sdk.shutdown()
        assertEquals(List(3) { "Installation Removed" }, sent().map { it.event() })
    }

    @Test fun eachAppStartMakesOneNewCycle() = runBlocking {
        status = 503
        val first = sdk(flushIntervalMs = 100)
        first.identify("user-1")
        first.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        first.optOut()
        waitUntil { requests.size >= 1 }
        delay(300)
        first.shutdown()
        val second = sdk(flushIntervalMs = 100)
        waitUntil { requests.size >= 2 }
        delay(400)
        assertEquals(2, requests.size)
        second.shutdown()
        status = 204
        val third = sdk(flushIntervalMs = 100)
        waitUntil { requests.size >= 3 }
        delay(400)
        third.shutdown()
        assertEquals(List(3) { "Installation Removed" }, sent().map { it.event() })
        assertEquals(true, third.isOptedOut)
    }

    @Test fun eachRepeatedOptOutMakesOneNewCycle() = runBlocking {
        status = 503
        val sdk = sdk(flushIntervalMs = 100)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { requests.size >= 1 }
        delay(300)
        sdk.optOut()
        waitUntil { requests.size >= 2 }
        delay(400)
        sdk.shutdown()
        assertEquals(List(2) { "Installation Removed" }, sent().map { it.event() })
        assertEquals(1, sent().map { it["messageId"] }.toSet().size) // The same removal, not a new one.
    }

    @Test fun anAcceptedRemovalIsNotSentAgainByAnyTrigger() = runBlocking {
        val sdk = sdk(flushIntervalMs = 100)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { requests.size >= 1 }
        sdk.handleLifecycle(LifecycleEvent.STOP, 1)
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 2)
        sdk.optOut()
        delay(300)
        sdk.shutdown()
        val restarted = sdk(flushIntervalMs = 100)
        delay(300)
        restarted.shutdown()
        assertEquals(listOf("Installation Removed"), sent().map { it.event() })
    }

    /** Holds the queue behind a removal the server refuses (503) until [accept] is called. */
    private inner class RefusedRemoval {
        private val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        init {
            respond = { request ->
                val removal = request.body.toString(Charsets.UTF_8).contains("\"Installation Removed\"")
                if (removal && !accepted.get()) 503 else 204
            }
        }
        fun accept() = accepted.set(true)
    }

    private fun droppedNames(log: List<BatchFailure>) =
        log.filter { it.attempts == 0 }.flatMap { failure -> failure.events.map { it["event"]?.stringValue } }

    @Test fun queueOverflowBehindAWaitingRemovalDropsTheOldestOrdinaryEventsNotTheRegistration() = runBlocking {
        val removal = RefusedRemoval()
        val failures = CopyOnWriteArrayList<BatchFailure>()
        val sdk = sdk(maxQueueSize = 3, onError = { failures.add(it) })
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { requests.size >= 1 }
        sdk.optIn() // Queues Installation Registered; its cycle is refused too, so the queue waits.
        waitUntil { requests.size >= 2 }
        for (n in 1..4) sdk.track("Tracked $n")
        delay(700) // Dropped events are reported together after 500 ms.
        assertEquals(listOf("Tracked 1", "Tracked 2"), droppedNames(failures))
        removal.accept()
        sdk.handleLifecycle(LifecycleEvent.STOP, 1)
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 2)
        waitUntil { sent().any { it.event() == "Tracked 4" } }
        sdk.shutdown()
        assertEquals(listOf("Installation Removed", "Installation Registered", "Tracked 3", "Tracked 4"),
            sent().drop(2).map { it.event() })
    }

    @Test fun aRestartWithASmallerQueueLimitAlsoKeepsTheRegistration() = runBlocking {
        val removal = RefusedRemoval()
        val first = sdk()
        first.identify("user-1")
        first.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        first.optOut()
        waitUntil { requests.size >= 1 }
        first.optIn()
        waitUntil { requests.size >= 2 }
        for (n in 1..4) first.track("Tracked $n")
        first.shutdown()
        removal.accept()
        val failures = CopyOnWriteArrayList<BatchFailure>()
        val restarted = sdk(maxQueueSize = 3, onError = { failures.add(it) })
        waitUntil { sent().any { it.event() == "Tracked 4" } }
        delay(700)
        restarted.shutdown()
        assertEquals(listOf("Tracked 1", "Tracked 2"), droppedNames(failures))
        assertEquals(listOf("Installation Removed", "Installation Registered", "Tracked 3", "Tracked 4"),
            sent().drop(2).map { it.event() })
    }

    private suspend fun installationOnlyOverflow(maxQueueSize: Int): Pair<List<String?>, List<Pair<String?, String?>>> {
        val removal = RefusedRemoval()
        val failures = CopyOnWriteArrayList<BatchFailure>()
        val sdk = sdk(maxQueueSize = maxQueueSize, onError = { failures.add(it) })
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { requests.size >= 1 }
        sdk.optIn() // Registered(token 1)
        waitUntil { requests.size >= 2 }
        sdk.registerInstallation("fcm-token-2", "com.example.shop", InstallationPermission.GRANTED) // Removed(1), Registered(2)
        sdk.registerInstallation("fcm-token-2", "com.example.shop", InstallationPermission.DENIED) // Registered(2, denied)
        delay(700)
        removal.accept()
        sdk.handleLifecycle(LifecycleEvent.STOP, 1)
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 2)
        waitUntil { sent().any { it.properties()?.get("permissionState") == "denied" } }
        sdk.shutdown()
        val dropped = failures.filter { it.attempts == 0 }.flatMap { failure ->
            failure.events.map { "${it["event"]?.stringValue}(${it["properties"]?.objectValue?.get("token")?.stringValue})" } }
        // The host never sees the token, so the dropped events are told apart by what the delivered ones keep.
        return dropped to sent().drop(2).map { it.event() to it.properties()?.get("token") }
    }

    @Test fun anOverflowOfOnlyInstallationEventsKeepsTheNewestPerToken() = runBlocking {
        val (dropped, delivered) = installationOnlyOverflow(maxQueueSize = 2)
        assertEquals(listOf("Installation Registered(<redacted>)", "Installation Registered(<redacted>)"), dropped)
        assertEquals(listOf("Installation Removed" to "fcm-token-1", "Installation Removed" to "fcm-token-1",
            "Installation Registered" to "fcm-token-2"), delivered)
    }

    @Test fun anOverflowWithNothingSupersededDropsTheOldestInstallationEvent() = runBlocking {
        val (dropped, delivered) = installationOnlyOverflow(maxQueueSize = 1)
        assertEquals(listOf("Installation Registered(<redacted>)", "Installation Removed(<redacted>)",
            "Installation Registered(<redacted>)"), dropped)
        assertEquals(listOf("Installation Removed" to "fcm-token-1", "Installation Registered" to "fcm-token-2"), delivered)
    }

    /** A process start over a parked removal; [holdStartRequest] keeps the start cycle's request in flight. */
    private suspend fun launch(holdStartRequest: java.util.concurrent.CountDownLatch?): TranthorSDK {
        respond = { if (holdStartRequest != null && requests.size == 2) holdStartRequest.await(5, java.util.concurrent.TimeUnit.SECONDS); 503 }
        val first = sdk(flushIntervalMs = 100)
        first.identify("user-1")
        first.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        first.optOut()
        waitUntil { requests.size >= 1 }
        delay(200)
        first.shutdown()
        val restarted = sdk(flushIntervalMs = 100)
        waitUntil { requests.size >= 2 } // The app start's own cycle.
        return restarted
    }

    @Test fun theFirstForegroundOfALaunchAfterTheStartCycleParkedAddsNoCycle() = runBlocking {
        val sdk = launch(holdStartRequest = null)
        delay(200)
        // Configured in Application.onCreate: the first activity's ON_START and ON_RESUME follow.
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 1)
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 2)
        delay(400)
        sdk.shutdown()
        assertEquals(2, requests.size)
    }

    @Test fun theFirstForegroundOfALaunchDuringTheStartCycleAddsNoCycle() = runBlocking {
        val hold = java.util.concurrent.CountDownLatch(1)
        val sdk = launch(holdStartRequest = hold)
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 1)
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 2)
        hold.countDown()
        delay(400)
        sdk.shutdown()
        assertEquals(2, requests.size)
    }

    @Test fun aFailedSaveOfAnOverflowingTrackLeavesTheQueueAsItWas() = runBlocking {
        val removal = RefusedRemoval()
        val failSave = java.util.concurrent.atomic.AtomicBoolean(false)
        val sdk = sdk(maxQueueSize = 3, storeWrite = { bytes, file ->
            if (failSave.get() && file.parentFile.name == "queue") throw java.io.IOException("disk full")
            TempRenameFileIo.write(file, bytes)
        })
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        waitUntil { requests.size >= 1 }
        sdk.optIn()
        waitUntil { requests.size >= 2 }
        sdk.track("Tracked 1")
        sdk.track("Tracked 2") // The queue is full: Registered, Tracked 1, Tracked 2.
        failSave.set(true)
        val error = runCatching { sdk.track("Tracked 3") }.exceptionOrNull()
        failSave.set(false)
        assertTrue(error.toString(), error is TranthorException.StoreTemporarilyUnavailable)
        removal.accept()
        sdk.handleLifecycle(LifecycleEvent.STOP, 1)
        sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 2)
        waitUntil { sent().any { it.event() == "Tracked 2" } }
        sdk.shutdown()
        assertEquals(listOf("Installation Removed", "Installation Registered", "Tracked 1", "Tracked 2"),
            sent().drop(2).map { it.event() })
    }
}
