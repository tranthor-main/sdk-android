package com.tranthor.sdk

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Java facade runs every call through one FIFO consumer, which must stay responsive and alive. */
class TranthorClientTest {
    private class FailingTransport(private val blockMs: Long) : SdkTransport {
        val attempts = AtomicInteger()
        override fun send(batch: List<QueueItem>, abort: AbortHandle) {
            Thread.sleep(blockMs)
            attempts.incrementAndGet()
            throw DeliveryFailure(503, "fixture")
        }
    }

    private fun test(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private fun clientFor(sdk: TranthorSDK): TranthorClient =
        TranthorClient::class.java.getDeclaredConstructor(TranthorSDK::class.java).apply { isAccessible = true }.newInstance(sdk)

    /** A flush in progress must not delay later calls: they are admitted at once, not after delivery settles. */
    @Test fun optOutAndTrackDoNotWaitForFlushRetries() = test {
        val root = tempRoot("client")
        val transport = FailingTransport(blockMs = 300)
        val sdk = configureJvm(Configuration("k", batchSize = 100, flushIntervalMs = 60_000, retries = 4, baseDelay = 200,
            automaticContext = false, storageDirectory = root), transport)
        val client = clientFor(sdk)
        try {
            val start = System.nanoTime()
            val trackDone = AtomicLong(-1); val optOutDone = AtomicLong(-1)
            client.track("a")
            client.flush { _, _ -> }
            client.track("b", null) { _, _ -> trackDone.set((System.nanoTime() - start) / 1_000_000) }
            client.optOut { _, _ -> optOutDone.set((System.nanoTime() - start) / 1_000_000) }
            waitUntil(20_000) { optOutDone.get() >= 0 }
            assertTrue("track after flush admitted only at ${trackDone.get()} ms", trackDone.get() in 0..250)
            assertTrue("optOut after flush completed only at ${optOutDone.get()} ms", optOutDone.get() in 0..1000)
        } finally { runCatching { sdk.shutdown() }; root.deleteRecursively() }
    }

    /** A host callback that throws must not kill the consumer and silently drop every later call. */
    @Test fun survivesThrowingCallback() = test {
        val root = tempRoot("client")
        val sdk = configureJvm(Configuration("k", automaticContext = false, storageDirectory = root), NoopTransport)
        val client = clientFor(sdk)
        try {
            val later = AtomicBoolean(false)
            client.track("a", null) { _, _ -> throw RuntimeException("host callback bug") }
            client.track("b", null) { _, _ -> later.set(true) }
            delay(1000)
            assertTrue("Calls after a throwing callback were never executed", later.get())
        } finally { sdk.shutdown(); root.deleteRecursively() }
    }
}
