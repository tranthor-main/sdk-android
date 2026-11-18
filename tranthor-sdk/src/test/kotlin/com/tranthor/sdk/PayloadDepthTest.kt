package com.tranthor.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An event the SDK admits must survive a restart, so admission enforces a nesting limit strictly
 * below the state reader's limit, and parsing deep input fails cleanly instead of overflowing the stack.
 */
class PayloadDepthTest {
    private fun nested(depth: Int): JsonValue {
        var value: JsonValue = JsonValue.StringValue("leaf")
        repeat(depth) { value = JsonValue.ObjectValue(mapOf("n" to value)) }
        return value
    }

    @Test fun admittedDeepEventDoesNotDestroyQueueOnRestart() = runBlocking {
        for (depth in listOf(30, 62, 63, 120, 125, 126, 127, 200)) {
            val root = tempRoot("depth")
            val config = Configuration("k", batchSize = 100, flushIntervalMs = 600_000, automaticContext = false, storageDirectory = root)
            var admitted = true
            val sdk = configureJvm(config, NoopTransport)
            sdk.track("before")
            try { sdk.track("deep", properties = mapOf("p" to nested(depth))) } catch (_: Exception) { admitted = false }
            sdk.track("after")
            sdk.shutdown()
            val again = configureJvm(config, NoopTransport)
            val queued = events(JsonStore(root, "default").let { it.load() })
            again.shutdown()
            if (admitted) {
                assertFalse(again.isOptedOut)
                assertEquals("depth $depth: admitted events lost on restart", listOf("before", "deep", "after"), queued)
            }
            root.deleteRecursively()
        }
    }

    @Test fun tooDeepEventIsRejectedAtAdmissionWithAttemptsZero() = runBlocking {
        val root = tempRoot("depth")
        val log = FailureLog()
        val sdk = configureJvm(Configuration("k", batchSize = 100, flushIntervalMs = 600_000, automaticContext = false,
            storageDirectory = root, onError = log::append), NoopTransport)
        try {
            // properties -> nested(63) -> event object: 1 + 1 + 63 = 65 levels > 64.
            try { sdk.track("deep", properties = mapOf("p" to nested(63))); org.junit.Assert.fail("Expected rejection") }
            catch (_: TranthorException.InvalidPayload) {}
            sdk.track("ok", properties = mapOf("p" to nested(62)))
            val failures = log.snapshot()
            assertEquals(1, failures.size); assertEquals(0, failures[0].attempts); assertEquals(null, failures[0].error.status)
            sdk.shutdown()
            assertEquals(listOf("ok"), events(JsonStore(root, "default").load()))
        } finally { root.deleteRecursively() }
    }

    @Test fun readLimitIsStrictlyAboveAdmissionLimit() {
        assertTrue(JsonCodec.MAX_DEPTH > JsonCodec.MAX_ADMISSION_DEPTH + 2)
        assertTrue(JsonCodec.exceedsDepth(nested(70), 64))
        assertFalse(JsonCodec.exceedsDepth(nested(63), 64))
    }

    @Test fun deepJsonNestingIsRejectedNotStackOverflow() {
        var outcome: Throwable? = null
        val thread = Thread(null, {
            outcome = runCatching { JsonValue.parse("[".repeat(8000)) }.exceptionOrNull()
        }, "small-stack", 256 * 1024)
        thread.start(); thread.join()
        assertTrue("Expected IllegalArgumentException, got $outcome", outcome is IllegalArgumentException)
    }
}
