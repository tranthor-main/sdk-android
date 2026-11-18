package com.tranthor.sdk

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle events reach the SDK from threads that can reorder them, so delivery must end in the right
 * state whatever order they are admitted in: never paused in the foreground, and a background stop
 * always makes one attempt and then pauses with the batch retained.
 */
class LifecycleOrderingTest {
    private class ScriptedTransport(private val failFirst: Int, private val status: Int = 503) : SdkTransport {
        val attempts = AtomicInteger()
        override fun send(batch: List<QueueItem>, abort: AbortHandle) {
            if (attempts.incrementAndGet() <= failFirst) throw DeliveryFailure(status, "fixture")
        }
    }

    private fun test(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    /** A foreground event admitted before the stale stop that preceded it must not leave delivery paused. */
    @Test fun foregroundAdmittedBeforeBackgroundFlushDoesNotStallDelivery() = test {
        val root = tempRoot("lifecycle-order")
        val transport = ScriptedTransport(failFirst = 1)
        val sdk = configureJvm(Configuration("k", batchSize = 100, flushIntervalMs = 50, baseDelay = 10,
            automaticContext = false, storageDirectory = root), transport)
        try {
            sdk.track("first")
            sdk.handleLifecycle(LifecycleEvent.FOREGROUND, 2)
            sdk.handleLifecycle(LifecycleEvent.STOP, 1)
            sdk.track("second")
            delay(1500) // About 30 flush intervals in the foreground.
            assertFalse("Foreground delivery is paused after an inverted STOP/START admission", sdk.deliveryPaused())
            assertTrue("Queued events must be delivered while in the foreground", transport.attempts.get() >= 2)
        } finally { sdk.shutdown(); root.deleteRecursively() }
    }

    /** A stop admitted before the pause that preceded it still makes one attempt, pauses and retains the batch. */
    @Test fun stopBeforeStalePauseStillMakesOneAttemptThenPauses() = test {
        val root = tempRoot("lifecycle-order")
        val transport = ScriptedTransport(failFirst = Int.MAX_VALUE)
        val log = FailureLog()
        val sdk = configureJvm(Configuration("k", batchSize = 100, flushIntervalMs = 60_000, retries = 3, baseDelay = 50,
            automaticContext = false, storageDirectory = root, onError = { log.append(it) }), transport)
        try {
            sdk.track("home")
            sdk.handleLifecycle(LifecycleEvent.STOP, 2)
            sdk.handleLifecycle(LifecycleEvent.PAUSE, 1)
            assertEquals("Background must make one attempt then pause", 1, transport.attempts.get())
            assertTrue(sdk.deliveryPaused())
            assertTrue(log.snapshot().isEmpty())
            assertEquals(listOf("home"), savedEventNames(root))
        } finally { sdk.shutdown(); root.deleteRecursively() }
    }

    /** A pause immediately followed by a stop promotes the running inactivity flush to background mode. */
    @Test fun backToBackPauseStopPromotesRunningFlush() = test {
        val root = tempRoot("lifecycle-order")
        val transport = ScriptedTransport(failFirst = Int.MAX_VALUE)
        val log = FailureLog()
        val sdk = configureJvm(Configuration("k", batchSize = 100, flushIntervalMs = 60_000, retries = 3, baseDelay = 200,
            automaticContext = false, storageDirectory = root, onError = { log.append(it) }), transport)
        try {
            sdk.track("home")
            val pause = async(start = CoroutineStart.UNDISPATCHED) { sdk.handleLifecycle(LifecycleEvent.PAUSE, 1) }
            val stop = async(start = CoroutineStart.UNDISPATCHED) { sdk.handleLifecycle(LifecycleEvent.STOP, 2) }
            pause.await(); stop.await()
            waitUntil { sdk.deliveryPaused() }
            assertTrue("At most the inactivity attempt plus one background attempt", transport.attempts.get() in 1..2)
            assertTrue("No exhaustion or drop in the background", log.snapshot().isEmpty())
            assertEquals(listOf("home"), savedEventNames(root))
        } finally { sdk.shutdown(); root.deleteRecursively() }
    }
}
