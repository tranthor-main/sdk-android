package com.tranthor.sdk

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Queue, retry, cancellation and capacity behaviour of the delivery cycle. Sleeps and responses are released explicitly. */
class DeliveryQueueTest {
    private fun root(): File = tempRoot("delivery-queue")
    private suspend fun sdk(root: File, transport: ManualTransport, sleeper: ManualSleeper, errors: FailureLog? = null,
                            batchSize: Int = 100, flushIntervalMs: Int = 60000, maxQueueSize: Int = 10_000): TranthorSDK =
        configureJvm(Configuration("fixture", batchSize = batchSize, flushIntervalMs = flushIntervalMs, automaticContext = false,
            storageDirectory = root, maxQueueSize = maxQueueSize, onError = errors?.let { it::append }), transport, sleeper)

    private fun test(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private var lifecycleSequence = 0L
    private suspend fun TranthorSDK.lifecycle(event: LifecycleEvent) = handleLifecycle(event, ++lifecycleSequence)
    /** The sequence number is taken now, in call order, as the process lifecycle hook does on the main thread. */
    private fun kotlinx.coroutines.CoroutineScope.lifecycleAsync(sdk: TranthorSDK, event: LifecycleEvent): kotlinx.coroutines.Deferred<Unit> {
        val sequence = ++lifecycleSequence
        return async(Dispatchers.Default) { sdk.handleLifecycle(event, sequence) }
    }

    private suspend fun assertRejects(message: String, block: suspend () -> Unit) {
        try { block(); fail(message) } catch (error: Exception) {
            assertTrue("$message: must be a domain error, got $error", error is TranthorException || error is DeliveryFailure)
        }
    }

    /** A cycle cancelled mid-backoff rejects its flush and returns the unacknowledged batch to the queue for the next instance. */
    @Test fun shutdownDuringRetryBackoffRestoresActiveAndRejectsCancelledFlush() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper()
            val sdk = sdk(root, transport, sleeper)
            sdk.track("a")
            val flush = async(Dispatchers.Default) { runCatching { sdk.flush() } }
            transport.wait(1)
            transport.complete(0, DeliveryFailure(503, "retry"))
            val retry = sleeper.wait(500.0)
            val shutdown = async(Dispatchers.Default) { sdk.shutdown() }
            sleeper.waitCancellation(retry)
            sleeper.release(retry)
            shutdown.await()
            val first = flush.await().exceptionOrNull()
            assertTrue("Cancelled cycle must reject its waiter with FlushCancelled, got $first", first is TranthorException.FlushCancelled)
            assertEquals("The unacknowledged batch must stay in the queue", listOf("a"), savedEventNames(root))
            val next = sdk(root, transport, sleeper)
            next.track("b")
            val recovery = async(Dispatchers.Default) { next.flush() }
            transport.wait(2)
            val requests = transport.snapshot()
            assertEquals(listOf("a", "b"), requests[1].map { it["event"]?.stringValue })
            assertEquals(requests[0][0]["messageId"], requests[1][0]["messageId"])
            assertEquals(requests[0][0]["timestamp"], requests[1][0]["timestamp"])
            transport.complete(1)
            recovery.await()
            next.shutdown(); sleeper.releaseAll()
        } finally { root.deleteRecursively() }
    }

    /** A caller that abandons its flush must not leave the instance unusable. */
    @Test fun anInstanceWhoseFlushWasCancelledStillDeliversLaterEvents() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper()
            val sdk = sdk(root, transport, sleeper)
            sdk.track("a")
            val abandoned = async(Dispatchers.Default) { sdk.flush() }
            transport.wait(1)
            abandoned.cancel()
            transport.autoSucceedFutureRequests()
            transport.complete(0)
            sdk.track("b")
            withTimeout(5000) { sdk.flush() }
            assertEquals(listOf("a", "b"), transport.snapshot().flatten().map { it["event"]?.stringValue })
            assertTrue(savedEvents(root).isEmpty())
            sdk.shutdown(); sleeper.releaseAll()
        } finally { root.deleteRecursively() }
    }

    @Test fun everyWaiterOfACancelledCycleIsRejected() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper()
            val sdk = sdk(root, transport, sleeper)
            sdk.track("a")
            val first = async(Dispatchers.Default) { runCatching { sdk.flush() } }
            transport.wait(1)
            val second = async(Dispatchers.Default) { runCatching { sdk.flush() } }
            waitUntil { sdk.flushWaiterCount() >= 2 }
            // Cancel while the request is in flight: the cycle cannot end until the transport returns.
            val shutdown = async(Dispatchers.Default) { sdk.shutdown() }
            waitUntil { sdk.isStopped() }
            transport.complete(0, DeliveryFailure(503, "retry"))
            shutdown.await()
            assertTrue(first.await().exceptionOrNull() is TranthorException.FlushCancelled)
            assertTrue("Waiter attached to a cancelled cycle must reject", second.await().exceptionOrNull() is TranthorException.FlushCancelled)
            assertEquals(listOf("a"), savedEventNames(root))
            sleeper.releaseAll()
        } finally { root.deleteRecursively() }
    }

    @Test fun trackIsAdmittedWhileARequestIsInFlight() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper()
            val sdk = sdk(root, transport, sleeper)
            sdk.track("first")
            val flush = async(Dispatchers.Default) { sdk.flush() }
            transport.wait(1)
            // The request is blocked in the transport; admission must not wait for it.
            withTimeout(2000) { sdk.track("second") }
            assertEquals("The batch in flight keeps its file until it is acknowledged", listOf("first", "second"), savedEventNames(root))
            // The cycle drains every queued batch; let the second batch succeed too.
            transport.autoSucceedFutureRequests()
            transport.complete(0)
            flush.await()
            sdk.shutdown(); sleeper.releaseAll()
        } finally { root.deleteRecursively() }
    }

    @Test fun acknowledgementWinsCancellationAfterSuccessfulResponse() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper()
            val sdk = sdk(root, transport, sleeper)
            sdk.track("acknowledged")
            val flush = async(Dispatchers.Default) { runCatching { sdk.flush() } }
            transport.wait(1)
            val shutdown = async(Dispatchers.Default) { sdk.shutdown() }
            // The stopped flag guarantees cancellation was issued before the transport's successful return.
            waitUntil { sdk.isStopped() }
            transport.complete(0)
            shutdown.await()
            assertTrue("Cancelled cycle waiter must reject", flush.await().exceptionOrNull() is TranthorException.FlushCancelled)
            assertTrue("A 2xx racing cancellation must still acknowledge the batch", savedEvents(root).isEmpty())
            sleeper.releaseAll()
        } finally { root.deleteRecursively() }
    }

    @Test fun backgroundCutsRetryBackoffAndPersistsAfterOneImmediateAttempt() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper()
            val sdk = sdk(root, transport, sleeper)
            sdk.track("background")
            val flush = async(Dispatchers.Default) { runCatching { sdk.flush() } }
            transport.wait(1)
            transport.complete(0, DeliveryFailure(503, "retry"))
            val retry = sleeper.wait(500.0)
            val background = lifecycleAsync(sdk, LifecycleEvent.STOP)
            sleeper.waitCancellation(retry)
            sleeper.release(retry)
            transport.wait(2)
            transport.complete(1, DeliveryFailure(503, "retry"))
            background.await()
            assertTrue("Background retry pause must reject flush", flush.await().isFailure)
            assertEquals(2, transport.count())
            assertEquals(1, savedEvents(root).size)
            assertTrue(sdk.deliveryPaused())
            sdk.shutdown(); sleeper.releaseAll()
        } finally { root.deleteRecursively() }
    }

    /** A retryable failure during a background flush must not stall delivery once the app is foregrounded. */
    @Test fun foregroundClearsBackgroundPauseAndResumesDelivery() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper()
            val sdk = sdk(root, transport, sleeper, flushIntervalMs = 1000)
            sdk.track("offline")
            val background = lifecycleAsync(sdk, LifecycleEvent.STOP)
            transport.wait(1)
            transport.complete(0, DeliveryFailure(503, "offline"))
            background.await()
            assertTrue(sdk.deliveryPaused())
            // While paused, new events must not schedule delivery.
            sdk.track("while-paused")
            assertEquals(null, sdk.pendingTimer())
            sdk.lifecycle(LifecycleEvent.FOREGROUND)
            assertFalse("Foreground must clear the pause", sdk.deliveryPaused())
            assertNotNull("Foreground must re-arm the interval", sdk.pendingTimer())
            val timer = sleeper.wait(1000.0)
            transport.autoSucceedFutureRequests()
            sleeper.release(timer)
            transport.wait(2)
            waitUntil { !sdk.deliveryCycleActive() }
            assertEquals(listOf("offline", "while-paused"), transport.snapshot()[1].map { it["event"]?.stringValue })
            assertTrue(savedEvents(root).isEmpty())
            sdk.shutdown(); sleeper.releaseAll()
        } finally { root.deleteRecursively() }
    }

    @Test fun foregroundDuringBackgroundSleeperCancellationDoesNotAdvanceUnacknowledgedBatch() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper()
            val sdk = sdk(root, transport, sleeper)
            sdk.track("a")
            val flush = async(Dispatchers.Default) { runCatching { sdk.flush() } }
            transport.wait(1)
            transport.complete(0, DeliveryFailure(503, "retry"))
            val retry = sleeper.wait(500.0)
            sdk.track("b")
            transport.autoSucceedFutureRequests()
            val background = lifecycleAsync(sdk, LifecycleEvent.STOP)
            sleeper.waitCancellation(retry)
            sdk.lifecycle(LifecycleEvent.FOREGROUND)
            sleeper.release(retry)
            background.await()
            assertTrue("Unacknowledged cancelled sleeper must reject flush", flush.await().isFailure)
            assertEquals("An unacknowledged batch cannot advance to the later batch", 1, transport.count())
            assertEquals(listOf("a", "b"), savedEventNames(root))
            sdk.shutdown(); sleeper.releaseAll()
        } finally { root.deleteRecursively() }
    }

    @Test fun staleTimerCannotFlushLaterQueue() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper()
            val sdk = sdk(root, transport, sleeper)
            sdk.track("first")
            val stale = sdk.pendingTimer()
            val firstTimer = sleeper.wait(60000.0)
            val flush = async(Dispatchers.Default) { sdk.flush() }
            transport.wait(1)
            sleeper.waitCancellation(firstTimer)
            transport.complete(0); flush.await()
            sdk.track("later")
            // The cancelled sleep returns normally; the timer generation must still reject it.
            sleeper.release(firstTimer, ignoringCancellation = true)
            stale?.join()
            assertFalse("A stale timer must not create a delivery cycle", sdk.deliveryCycleActive())
            assertEquals("A cancelled timer generation cannot flush a later queue", 1, transport.count())
            assertEquals(listOf("later"), savedEventNames(root))
            val secondTimer = sleeper.wait(60000.0)
            sleeper.release(secondTimer)
            transport.wait(2); transport.complete(1)
            sdk.shutdown(); sleeper.releaseAll()
        } finally { root.deleteRecursively() }
    }

    @Test fun duplicateNamespaceRejectedAndReleasedAtShutdown() = test {
        val root = root()
        try {
            val c = Configuration("fixture", batchSize = 100, flushIntervalMs = 60000, storageDirectory = root)
            val first = configureJvm(c, NoopTransport)
            first.track("a")
            try { configureJvm(c, NoopTransport); fail("Duplicate live namespace must throw") }
            catch (_: TranthorException.NamespaceInUse) {}
            first.shutdown()
            val second = configureJvm(c, NoopTransport)
            second.track("b"); second.shutdown()
            assertEquals(listOf("a", "b"), savedEventNames(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun allStoppedMutatorsCannotOverwriteNewOwnerStore() = test {
        val root = root()
        try {
            val c = Configuration("fixture", batchSize = 100, flushIntervalMs = 60000, storageDirectory = root)
            val old = configureJvm(c, NoopTransport); old.shutdown()
            val new = configureJvm(c, NoopTransport); new.track("new")
            val store = JsonStore(root, "default")
            val before = store.stateFile.readBytes(); val consent = store.consentFile.readBytes()
            old.track("stale"); old.identify("stale"); old.page("stale"); old.screen("stale")
            old.reset(); old.optOut(); old.optIn()
            try { old.flush(); fail("Stopped flush must reject") } catch (_: TranthorException.Stopped) {}
            old.shutdown()
            assertTrue(before.contentEquals(store.stateFile.readBytes()))
            assertTrue(consent.contentEquals(store.consentFile.readBytes()))
            new.shutdown()
        } finally { root.deleteRecursively() }
    }

    @Test fun queueCapacityReservesInFlightBatchAndRemainsBoundedAfterCancellation() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper(); val errors = FailureLog()
            val sdk = sdk(root, transport, sleeper, errors, maxQueueSize = 3)
            sdk.track("a")
            val flush = async(Dispatchers.Default) { runCatching { sdk.flush() } }
            transport.wait(1)
            for (event in listOf("b", "c", "d")) sdk.track(event)
            val shutdown = async(Dispatchers.Default) { sdk.shutdown() }
            waitUntil { sdk.isStopped() }
            transport.complete(0, DeliveryFailure(503, "retry"))
            shutdown.await()
            assertTrue("Cancelled cycle must reject", flush.await().isFailure)
            sleeper.releaseAll()
            assertEquals(listOf("a", "c", "d"), savedEventNames(root))
            val failures = errors.snapshot()
            assertEquals(1, failures.size)
            assertEquals(listOf("b"), failures.first().events.map { it["event"]?.stringValue })
        } finally { root.deleteRecursively() }
    }

    @Test fun queueCapDropsOldestAndReportsOneBurst() = test {
        val root = root()
        try {
            val errors = FailureLog()
            val sdk = configureJvm(Configuration("fixture", batchSize = 100, flushIntervalMs = 60000, automaticContext = false,
                storageDirectory = root, maxQueueSize = 3, onError = errors::append), NoopTransport)
            for (index in 0 until 8) sdk.track("e$index")
            sdk.shutdown()
            val failures = errors.snapshot()
            assertEquals(1, failures.size)
            assertEquals(5, failures.first().events.size)
            assertEquals(0, failures.first().attempts)
            assertEquals(listOf("e5", "e6", "e7"), savedEventNames(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun dropCallbacksCoalesceWithinBurstAndOptOutSuppressesThem() = test {
        val root = root()
        try {
            val errors = FailureLog()
            val sdk = configureJvm(Configuration("fixture", batchSize = 100, flushIntervalMs = 60000, automaticContext = false,
                storageDirectory = root, maxQueueSize = 2, onError = errors::append), NoopTransport)
            for (index in 0 until 4) sdk.track("e$index")
            delay(800) // The 500 ms burst window elapses: one coalesced callback.
            assertEquals(1, errors.snapshot().size)
            assertEquals(2, errors.snapshot().first().events.size)
            sdk.track("e4")
            sdk.optOut() // Pending drop callbacks are suppressed by opt-out.
            delay(800)
            assertEquals(1, errors.snapshot().size)
            sdk.shutdown()
        } finally { root.deleteRecursively() }
    }

    @Test fun persistedActiveBatchRestoresBeforeWaitingEventsWithStampsAndResetAttempts() = test {
        val root = root()
        try {
            val first = ManualTransport(); val firstSleeper = ManualSleeper()
            val before = sdk(root, first, firstSleeper, batchSize = 2)
            for (name in listOf("inflight1", "inflight2", "waiting")) before.track(name, messageId = name, timestamp = "2026-10-13T00:00:00.000Z")
            first.wait(1)
            val shutdown = async(Dispatchers.Default) { before.shutdown() }
            waitUntil { before.isStopped() }
            first.complete(0, DeliveryFailure(503, "retry"))
            shutdown.await(); firstSleeper.releaseAll()
            val transport = ManualTransport(); val sleeper = ManualSleeper()
            transport.autoSucceedFutureRequests()
            val sdk = sdk(root, transport, sleeper)
            sdk.flush()
            val sent = transport.snapshot().flatten()
            assertEquals(listOf("inflight1", "inflight2", "waiting"), sent.map { it["event"]?.stringValue })
            assertEquals(listOf("inflight1", "inflight2", "waiting"), sent.map { it["messageId"]?.stringValue })
            assertTrue(sent.all { it["timestamp"]?.stringValue == "2026-10-13T00:00:00.000Z" })
            sdk.shutdown(); sleeper.releaseAll()
        } finally { root.deleteRecursively() }
    }

    @Test fun concurrentShutdownWaitsForFirstCleanupAndLeaseRelease() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper()
            val c = Configuration("fixture", batchSize = 100, flushIntervalMs = 60000, automaticContext = false, storageDirectory = root)
            val sdk = configureJvm(c, transport, sleeper)
            sdk.track("a")
            val flush = async(Dispatchers.Default) { runCatching { sdk.flush() } }
            transport.wait(1)
            val first = async(Dispatchers.Default) { sdk.shutdown() }
            waitUntil { sdk.isStopped() }
            val second = async(Dispatchers.Default) {
                sdk.shutdown()
                runCatching { configureJvm(c, NoopTransport) }
            }
            // The first cleanup is deliberately held in send; the second must await that cleanup.
            delay(50)
            transport.complete(0)
            val result = second.await()
            first.await(); flush.await(); sleeper.releaseAll()
            result.getOrElse { fail("configure after the second shutdown returned failed: $it"); throw it }.shutdown()
        } finally { root.deleteRecursively() }
    }

    @Test fun inactivityRetryContinuesWithoutForegroundCycleOrDrops() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper(); val errors = FailureLog()
            val sdk = configureJvm(Configuration("fixture", batchSize = 2, flushIntervalMs = 1000, automaticContext = false,
                storageDirectory = root, maxQueueSize = 4, onError = errors::append), transport, sleeper)
            sdk.track("a")
            val resign = lifecycleAsync(sdk, LifecycleEvent.PAUSE)
            transport.wait(1)
            transport.complete(0, DeliveryFailure(503, "blip"))
            val retry = sleeper.wait(500.0)
            assertFalse(sdk.deliveryPaused())
            transport.autoSucceedFutureRequests(); sleeper.release(retry); resign.await()
            listOf(listOf("b", "c"), listOf("d", "e")).forEachIndexed { index, pair ->
                for (event in pair) sdk.track(event)
                transport.wait(index + 3)
                waitUntil { !sdk.deliveryCycleActive() }
            }
            sdk.track("f")
            val timer = sleeper.wait(1000.0); sleeper.release(timer)
            transport.wait(5)
            sdk.flush()
            sdk.shutdown(); sleeper.releaseAll()
            assertEquals(listOf("a", "b", "c", "d", "e", "f"), transport.snapshot().drop(1).flatten().map { it["event"]?.stringValue })
            assertTrue(errors.snapshot().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun cycleCancelledBeforeItStartsStillSettlesWaiters() = repeat(25) { test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper()
            transport.autoSucceedFutureRequests()
            val sdk = sdk(root, transport, sleeper)
            sdk.track("a")
            // optOut cancels a cycle that flush has created but that may not have started running.
            val flush = async(Dispatchers.Default) { runCatching { sdk.flush() } }
            val optOut = async(Dispatchers.Default) { sdk.optOut() }
            optOut.await()
            val outcome = withTimeout(3000) { flush.await() }
            // Either the opt-out admitted first (flush succeeds without a request) or the waiter is rejected; never hangs.
            assertTrue(outcome.isSuccess || outcome.exceptionOrNull() is TranthorException.FlushCancelled)
            sdk.shutdown(); sleeper.releaseAll()
        } finally { root.deleteRecursively() }
    } }

    @Test fun terminalFailureReportsOnceWithRealAttemptsAndLaterBatchesContinue() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper(); val errors = FailureLog()
            val sdk = sdk(root, transport, sleeper, errors, batchSize = 1)
            val flush = async(Dispatchers.Default) {
                sdk.track("x"); sdk.track("y")
                runCatching { sdk.flush() }
            }
            transport.wait(1)
            transport.complete(0, DeliveryFailure(500, "retry"))
            val retry = sleeper.wait(500.0); sleeper.release(retry)
            transport.wait(2)
            transport.complete(1, DeliveryFailure(400, "terminal"))
            transport.wait(3)
            assertEquals(listOf("y"), transport.snapshot()[2].map { it["event"]?.stringValue })
            transport.complete(2)
            val result = flush.await()
            assertEquals(400, (result.exceptionOrNull() as? DeliveryFailure)?.status)
            val failures = errors.snapshot()
            assertEquals(1, failures.size); assertEquals(2, failures[0].attempts); assertEquals(400, failures[0].error.status)
            sdk.shutdown(); sleeper.releaseAll()
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyAndOptedOutFlushSucceedWithoutRequest() = test {
        val root = root()
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper()
            val sdk = sdk(root, transport, sleeper)
            sdk.flush()
            sdk.track("discard")
            sdk.optOut()
            sdk.flush()
            assertEquals(0, transport.count())
            sdk.shutdown(); sleeper.releaseAll()
        } finally { root.deleteRecursively() }
    }
}
