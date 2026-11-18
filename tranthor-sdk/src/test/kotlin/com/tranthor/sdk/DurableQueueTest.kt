package com.tranthor.sdk

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Events are kept when retries run out and delivery pauses with growing delays. Time is driven only through
 * the injected sleep; requests and responses are released explicitly.
 */
class DurableQueueTest {
    /** One SDK on its own storage with a manual transport and sleeper; [rig] shuts it down and removes the storage. */
    private class Rig(
        scope: CoroutineScope, val root: File, val transport: ManualTransport, val sleeper: ManualSleeper, val errors: FailureLog, val sdk: TranthorSDK,
    ) : CoroutineScope by scope {
        private var lifecycleSequence = 0L
        suspend fun lifecycle(event: LifecycleEvent) = sdk.handleLifecycle(event, ++lifecycleSequence)
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun rig(batchSize: Int = 100, flushIntervalMs: Int = 1000, retries: Int = 0, block: suspend Rig.() -> Unit) = test {
        val root = tempRoot("durable-queue")
        try {
            val transport = ManualTransport(); val sleeper = ManualSleeper(); val errors = FailureLog()
            val sdk = sdk(root, transport, sleeper, errors, batchSize, flushIntervalMs, retries)
            try { Rig(this, root, transport, sleeper, errors, sdk).block() } finally { sdk.shutdown(); sleeper.releaseAll() }
        } finally { root.deleteRecursively() }
    }

    private suspend fun sdk(
        root: File, transport: ManualTransport, sleeper: ManualSleeper, errors: FailureLog? = null,
        batchSize: Int = 100, flushIntervalMs: Int = 1000, retries: Int = 0,
    ): TranthorSDK = configureJvm(
        Configuration("fixture", batchSize = batchSize, flushIntervalMs = flushIntervalMs, retries = retries, automaticContext = false,
            storageDirectory = root, onError = errors?.let { it::append }),
        transport, sleeper,
    )

    private fun names(batch: List<Map<String, JsonValue>>) = batch.map { it["event"]?.stringValue }

    /** Runs [body] for every kind and reports all the kinds that failed, each by name. */
    private suspend fun <T> eachKind(kinds: List<T>, body: suspend (T) -> Unit) {
        val failed = kinds.mapNotNull { kind ->
            try { body(kind); null } catch (error: AssertionError) { "$kind: ${error.message}" } catch (error: TestTimeout) { "$kind: ${error.message}" }
        }
        assertTrue("These failure kinds did not behave as expected:\n" + failed.joinToString("\n"), failed.isEmpty())
    }

    @Test fun anExhaustedBatchStaysQueuedWithoutErrorCallbackAndALaterFlushDeliversIt() = test {
        val failures = listOf(DeliveryFailure(null, "Network delivery failure"), DeliveryFailure(408, "f"), DeliveryFailure(425, "f"),
            DeliveryFailure(429, "f"), DeliveryFailure(500, "f"), DeliveryFailure(503, "f"))
        eachKind(failures) { failure ->
            val root = tempRoot("durable-queue")
            try {
                val transport = ManualTransport(); val sleeper = ManualSleeper(); val errors = FailureLog()
                val sdk = sdk(root, transport, sleeper, errors, retries = 1)
                try {
                    sdk.track("a", messageId = "message-a", timestamp = "2026-11-01T00:00:00.000Z")
                    val flush = async(Dispatchers.Default) { runCatching { sdk.flush() } }
                    transport.wait(1); transport.complete(0, failure)
                    sleeper.release(sleeper.wait(500.0))
                    transport.wait(2); transport.complete(1, failure)
                    assertEquals("the flush fails with the failure", failure, flush.await().exceptionOrNull())
                    assertTrue("onError must not hear of a kept batch", errors.snapshot().isEmpty())
                    transport.autoSucceedFutureRequests()
                    sdk.flush()
                    val sent = transport.snapshot()
                    assertEquals("requests", 3, sent.size)
                    assertEquals(listOf("a"), names(sent[2]))
                    assertEquals("message-a", sent[2].single()["messageId"]?.stringValue)
                    assertEquals("2026-11-01T00:00:00.000Z", sent[2].single()["timestamp"]?.stringValue)
                } finally { sdk.shutdown(); sleeper.releaseAll() }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun batchesQueuedBehindAnExhaustedBatchAreNotSentBeforeIt() = rig(batchSize = 1) {
        sdk.track("a"); sdk.track("b")
        val first = async(Dispatchers.Default) { runCatching { sdk.flush() } }
        transport.wait(1)
        waitUntil { sdk.flushWaiterCount() == 1 }
        transport.complete(0, DeliveryFailure(500, "down"))
        assertEquals(500, (first.await().exceptionOrNull() as DeliveryFailure).status)
        assertEquals("Only the exhausted batch was attempted", listOf(listOf("a")), transport.snapshot().map(::names))
        transport.autoSucceedFutureRequests()
        sdk.flush()
        assertEquals(listOf(listOf("a"), listOf("a"), listOf("b")), transport.snapshot().map(::names))
    }

    @Test fun deliveryPausesAfterEachExhaustedCycleForGrowingTimeCappedAtFiveMinutes() = rig(batchSize = 1) {
        sdk.track("a")
        val pauses = listOf(30_000.0, 60_000.0, 120_000.0, 240_000.0, 300_000.0, 300_000.0)
        pauses.forEachIndexed { index, milliseconds ->
            transport.wait(index + 1); transport.complete(index, DeliveryFailure(503, "down"))
            val pause = sleeper.wait(milliseconds)
            delay(20)
            assertEquals("No request may start before the pause ends", index + 1, transport.count())
            sleeper.release(pause)
        }
        transport.wait(7)
        assertEquals(pauses, sleeper.requested())
        transport.autoSucceedFutureRequests(); transport.complete(6)
    }

    @Test fun theIntervalTimerANewEventAndAFullBatchDoNotStartAnAttemptDuringThePause() = rig(batchSize = 3) {
        sdk.track("a")
        val flush = async(Dispatchers.Default) { runCatching { sdk.flush() } }
        transport.wait(1); transport.complete(0, DeliveryFailure(503, "down"))
        flush.await()
        val pause = sleeper.wait(30_000.0)
        sdk.track("b")
        sdk.track("c")
        delay(20)
        assertEquals("A full batch must not start an attempt during the pause", 1, transport.count())
        assertEquals("No interval timer is armed during the pause", listOf(1000.0, 30_000.0), sleeper.requested())
        transport.autoSucceedFutureRequests()
        sleeper.release(pause)
        transport.wait(2)
        assertEquals(listOf("a", "b", "c"), names(transport.snapshot()[1]))
    }

    @Test fun aDeliveredBatchResetsThePauseToThirtySeconds() = rig(batchSize = 1) {
        sdk.track("a")
        transport.wait(1); transport.complete(0, DeliveryFailure(503, "down"))
        sleeper.release(sleeper.wait(30_000.0))
        transport.wait(2); transport.complete(1, DeliveryFailure(503, "down"))
        sleeper.release(sleeper.wait(60_000.0))
        transport.wait(3); transport.complete(2)
        sdk.track("b")
        transport.wait(4); transport.complete(3, DeliveryFailure(503, "down"))
        sleeper.wait(30_000.0)
        assertEquals(listOf(30_000.0, 60_000.0, 30_000.0), sleeper.requested())
    }

    @Test fun aFlushDuringThePauseAttemptsAtOnceAndTheEndedPauseCannotStartAnother() = rig {
        sdk.track("a")
        val first = async(Dispatchers.Default) { runCatching { sdk.flush() } }
        transport.wait(1); transport.complete(0, DeliveryFailure(503, "down"))
        first.await()
        val pause = sleeper.wait(30_000.0)
        val second = async(Dispatchers.Default) { runCatching { sdk.flush() } }
        transport.wait(2)
        sleeper.waitCancellation(pause)
        transport.complete(1, DeliveryFailure(503, "down"))
        assertEquals(503, (second.await().exceptionOrNull() as DeliveryFailure).status)
        sleeper.wait(60_000.0)
        // The first pause was ended by the flush; even if its sleep returns late it must not start an attempt.
        sleeper.release(pause, ignoringCancellation = true)
        delay(20)
        assertEquals(2, transport.count())
        assertEquals(listOf(1000.0, 30_000.0, 60_000.0), sleeper.requested())
    }

    @Test fun aReturnFromTheBackgroundAttemptsAtOnceDuringThePause() = rig(batchSize = 1) {
        lifecycle(LifecycleEvent.STOP)
        sdk.track("a")
        transport.wait(1); transport.complete(0, DeliveryFailure(503, "down"))
        sleeper.wait(30_000.0)
        transport.autoSucceedFutureRequests()
        lifecycle(LifecycleEvent.FOREGROUND)
        transport.wait(2)
        assertEquals(listOf(listOf("a"), listOf("a")), transport.snapshot().map(::names))
    }

    @Test fun aNewInstanceOnTheSameStorageStartsWithoutAPause() = rig {
        sdk.track("a", messageId = "message-a")
        val flush = async(Dispatchers.Default) { runCatching { sdk.flush() } }
        transport.wait(1); transport.complete(0, DeliveryFailure(503, "down"))
        flush.await()
        sleeper.wait(30_000.0)
        sdk.shutdown(); sleeper.releaseAll()
        val restarted = ManualTransport(); val restartedSleeper = ManualSleeper()
        restarted.autoSucceedFutureRequests()
        val next = sdk(root, restarted, restartedSleeper)
        try {
            restartedSleeper.release(restartedSleeper.wait(1000.0))
            restarted.wait(1)
            assertEquals("message-a", restarted.snapshot().single().single()["messageId"]?.stringValue)
            assertEquals(listOf(1000.0), restartedSleeper.requested())
        } finally { next.shutdown(); restartedSleeper.releaseAll() }
    }

    @Test fun optingOutEndsThePauseSoNewEventsAfterOptInAreSentAtOnce() = rig(batchSize = 1) {
        sdk.track("a")
        transport.wait(1); transport.complete(0, DeliveryFailure(503, "down"))
        sleeper.wait(30_000.0)
        sdk.optOut(); sdk.optIn()
        sdk.track("b")
        transport.wait(2)
        assertEquals(listOf(listOf("a"), listOf("b")), transport.snapshot().map(::names))
        transport.complete(1, DeliveryFailure(503, "down"))
        sleeper.wait(30_000.0)
        assertEquals(listOf(30_000.0, 30_000.0), sleeper.requested())
    }

    @Test fun optingOutCancelsAStartedPauseAndItsLateEndMakesNoRequest() = rig(batchSize = 1) {
        sdk.track("a")
        transport.wait(1); transport.complete(0, DeliveryFailure(503, "down"))
        val pause = sleeper.wait(30_000.0)
        sdk.optOut()
        sleeper.waitCancellation(pause)
        sleeper.release(pause, ignoringCancellation = true)
        delay(20)
        assertEquals(1, transport.count())
    }

    @Test fun shuttingDownCancelsAStartedPauseAndItsLateEndMakesNoRequest() = rig(batchSize = 1) {
        sdk.track("a")
        transport.wait(1); transport.complete(0, DeliveryFailure(503, "down"))
        val pause = sleeper.wait(30_000.0)
        sdk.shutdown()
        sleeper.waitCancellation(pause)
        sleeper.release(pause, ignoringCancellation = true)
        delay(20)
        assertEquals(1, transport.count())
    }

    @Test fun theBackgroundWindowMakesOneAttemptAndThenPausesDelivery() = rig(batchSize = 1, retries = 2) {
        sdk.track("a")
        transport.wait(1)
        // The app leaves for the background while the first attempt is in flight.
        val stop = async(Dispatchers.Default) { lifecycle(LifecycleEvent.STOP) }
        waitUntil { sdk.flushWaiterCount() == 1 }
        transport.complete(0, DeliveryFailure(503, "down"))
        stop.await()
        assertEquals("No second attempt in the background window", 1, transport.count())
        assertTrue("Delivery is paused until the app returns", sdk.deliveryPaused())
        assertEquals("No retry delay and no timer were started", emptyList<Double>(), sleeper.requested())
    }
}
