package com.tranthor.sdk

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoreTest {
    @Test fun corruptStoreIsDiscardedAndReplaced() {
        val root = tempRoot("tranthor-unit")
        try {
            val store = JsonStore(root, "../../escape")
            store.stateFile.writeText("{broken")
            val fresh = store.load()
            assertTrue(isUuid(fresh.anonymousId))
            assertTrue(store.legacyEvents.isEmpty())
            store.save(fresh)
            assertEquals(fresh.anonymousId, store.load().anonymousId)
            assertTrue(store.stateFile.canonicalPath.startsWith(root.canonicalPath + "/"))
            store.stateFile.writeText("{\"anonymousId\":\"invalid\"}")
            assertTrue(isUuid(store.load().anonymousId))
        } finally { root.deleteRecursively() }
    }

    @Test fun retryAfterAndDelayEdges() {
        for (value in listOf(null, "", " 1", "1 ", "+1", "-1", "1.0", "1e2", "0x10", "١", "１", "1\n", "Wed, 21 Oct 2015 07:28:00 GMT")) {
            assertEquals(value.toString(), 0.0, retryAfter(value), 0.0)
        }
        assertEquals(0.0, retryAfter("0"), 0.0)
        assertEquals(2000.0, retryAfter("0002"), 0.0)
        assertEquals(30000.0, retryAfter("30"), 0.0)
        assertEquals(30000.0, retryAfter("9".repeat(500)), 0.0)
        assertEquals(500.0, retryDelay(500.0, 0, 0.0), 0.0)
        assertEquals(8000.0, retryDelay(500.0, 4, 0.0), 0.0)
        assertEquals(1200.0, retryDelay(600.0, 1, 1000.0), 0.0)
        assertEquals(30000.0, retryDelay(500.0, 2000, 0.0), 0.0)
        assertEquals(1000.0, retryDelay(0.0, 2000, 1000.0), 0.0)
        // Retry-After raises the delay but never replaces or adds to the backoff.
        assertEquals(2000.0, retryDelay(500.0, 2, 1000.0), 0.0)
        assertEquals(30000.0, retryDelay(500.0, 0, 45000.0), 0.0)
    }

    @Test fun configurationFallbacks() {
        for (value in listOf<Number?>(null, 0, -1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, Int.MAX_VALUE.toDouble(), Long.MAX_VALUE)) {
            val c = Configuration("fixture", batchSize = value, flushIntervalMs = value)
            assertEquals(5, c.batchSize); assertEquals(500, c.flushIntervalMs)
        }
        val defaults = Configuration("fixture", retries = Double.NaN, baseDelay = Double.POSITIVE_INFINITY)
        assertEquals(5, defaults.retries); assertEquals(500.0, defaults.baseDelay, 0.0)
        assertEquals("https://api.tranthor.com", defaults.host)
        assertTrue(defaults.automaticContext)
        assertEquals("default", defaults.storageNamespace)
        assertEquals(10_000, defaults.maxQueueSize)
        assertNull(defaults.storageDirectory)
        val c = Configuration("fixture", batchSize = 2, flushIntervalMs = 20, retries = 2.8, baseDelay = -10)
        assertEquals(2, c.batchSize); assertEquals(20, c.flushIntervalMs)
        assertEquals(2, c.retries); assertEquals(0.0, c.baseDelay, 0.0)
        assertEquals(0, Configuration("fixture", retries = -2).retries)
        assertEquals(0.5, Configuration("fixture", baseDelay = 0.5).baseDelay, 0.0)
        assertEquals(10_000, Configuration("fixture", maxQueueSize = 0).maxQueueSize)
        assertEquals(10_000, Configuration("fixture", maxQueueSize = -1).maxQueueSize)
        assertEquals(23, Configuration("fixture", maxQueueSize = 23).maxQueueSize)
        val built = Configuration.Builder("fixture").batchSize(7).flushIntervalMs(800).retries(3).baseDelay(250).build()
        assertEquals(7, built.batchSize); assertEquals(800, built.flushIntervalMs); assertEquals(3, built.retries); assertEquals(250.0, built.baseDelay, 0.0)
        assertFalse("toString must never expose the write key", Configuration("Basic secret-value").toString().contains("secret-value"))
    }

    @Test fun hostValidationAndTrailingSlashes() {
        assertEquals("https://api.tranthor.com/api/public/apps/batch", endpointFor("https://api.tranthor.com///").toString())
        assertEquals("http://127.0.0.1:43189/api/public/apps/batch", endpointFor("http://127.0.0.1:43189/").toString())
        for (bad in listOf("", "api.tranthor.com", "ftp://example.com", "http://", "https://exa mple.com", "/relative")) {
            assertThrows(bad, TranthorException.InvalidHost::class.java) { endpointFor(bad) }
        }
        runBlocking {
            try { configureJvm(Configuration("fixture", host = "not a url", storageDirectory = tempRoot("host"))); fail("Invalid host must throw") }
            catch (_: TranthorException.InvalidHost) {}
        }
    }

    @Test fun identityContextAndStablePersistence() = runBlocking {
        val root = tempRoot("tranthor-unit")
        try {
            val config = Configuration("fixture", host = "http://127.0.0.1:1", batchSize = 100, flushIntervalMs = 60000,
                storageNamespace = "identity", storageDirectory = root)
            val sdk = configureJvm(config, NoopTransport)
            val anon = sdk.anonymousId
            sdk.track("anonymous")
            sdk.identify("known", traits = jsonObjectOf("a" to 1))
            sdk.track("known")
            sdk.page(anonymousId = "explicit", context = jsonObjectOf("library" to mapOf("name" to "caller"), "locale" to "custom"))
            sdk.screen(userId = "override", messageId = "id", timestamp = "literal")
            sdk.reset()
            val resetAnon = sdk.anonymousId
            assertNotEquals(anon, resetAnon)
            sdk.track("reset")
            sdk.shutdown()
            val q = savedEvents(root, "identity")
            assertEquals(6, q.size)
            assertEquals(anon, q[0]["anonymousId"]?.stringValue); assertNull(q[0]["userId"])
            // The login identify links the stored anonymous ID; later events carry only the user ID.
            assertEquals("known", q[1]["userId"]?.stringValue); assertEquals(anon, q[1]["anonymousId"]?.stringValue)
            assertEquals("known", q[2]["userId"]?.stringValue); assertNull(q[2]["anonymousId"])
            assertEquals("explicit", q[3]["anonymousId"]?.stringValue); assertNull(q[3]["userId"])
            assertEquals("override", q[4]["userId"]?.stringValue); assertNull(q[4]["anonymousId"])
            assertEquals("id", q[4]["messageId"]?.stringValue); assertEquals("literal", q[4]["timestamp"]?.stringValue)
            assertEquals(resetAnon, q[5]["anonymousId"]?.stringValue)
            // Caller top-level keys replace automatic keys wholesale.
            assertEquals(JsonValue.of(mapOf("name" to "caller")), q[3]["context"]?.get("library"))
            assertEquals("custom", q[3]["context"]?.get("locale")?.stringValue)
            assertNotNull(q[3]["context"]?.get("os"))
            for (key in listOf("library", "os", "device", "app", "locale", "timezone")) assertNotNull(key, q[0]["context"]?.get(key))
            assertEquals(TranthorSDKVersion.CURRENT, q[0]["context"]?.get("library")?.get("version")?.stringValue)
            assertEquals(1.0, q[1]["traits"]?.get("a")?.numberValue)
            val restored = configureJvm(config, NoopTransport)
            assertEquals(resetAnon, restored.anonymousId); assertNull(restored.userId)
            restored.shutdown()
            assertEquals(q, savedEvents(root, "identity"))
        } finally { root.deleteRecursively() }
    }

    @Test fun identifyPersistsUserAndConsent() = runBlocking {
        val root = tempRoot("tranthor-unit")
        try {
            val c = Configuration("fixture", host = "http://127.0.0.1:1", batchSize = 100, flushIntervalMs = 60000, storageDirectory = root)
            val first = configureJvm(c, NoopTransport)
            first.identify("saved")
            first.shutdown()
            val second = configureJvm(c, NoopTransport)
            assertEquals("saved", second.userId)
            second.optOut()
            second.identify("ignored")
            second.shutdown()
            val third = configureJvm(c, NoopTransport)
            assertTrue(third.isOptedOut); assertEquals("saved", third.userId)
            third.flush()
            third.optIn()
            assertFalse(third.isOptedOut)
            third.shutdown()
        } finally { root.deleteRecursively() }
    }

    @Test fun timestampFormat() {
        assertEquals("2020-01-02T03:04:05.006Z", timestamp(1_577_934_245_006L))
        assertEquals("1970-01-01T00:00:00.000Z", timestamp(0L))
        assertEquals("2000-02-29T23:59:59.999Z", timestamp(951_868_799_999L))
        assertEquals("1969-12-31T23:59:59.999Z", timestamp(-1L))
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$").matches(timestamp()))
    }

    @Test fun concurrentTracksAreDurableUniqueAndOrderedPerCaller() = runBlocking {
        val root = tempRoot("tranthor-unit")
        try {
            val sdk = configureJvm(Configuration("fixture", host = "http://127.0.0.1:1", batchSize = 1000,
                flushIntervalMs = 60000, automaticContext = false, storageDirectory = root), NoopTransport)
            (0 until 200).map { index -> async(kotlinx.coroutines.Dispatchers.Default) { sdk.track("event-$index") } }.awaitAll()
            sdk.shutdown()
            val events = savedEvents(root)
            assertEquals(200, events.size)
            assertEquals(200, events.mapNotNull { it["messageId"]?.stringValue }.toSet().size)
            assertEquals(200, events.mapNotNull { it["event"]?.stringValue }.toSet().size)
            assertTrue(events.all { it["anonymousId"] != null && it["userId"] == null })
        } finally { root.deleteRecursively() }
    }

    @Test fun nonFinitePayloadRejectedBeforeQueueing() = runBlocking {
        val root = tempRoot("tranthor-unit")
        try {
            val errors = FailureLog()
            val sdk = configureJvm(Configuration("fixture", host = "http://127.0.0.1:1", flushIntervalMs = 60000, storageDirectory = root,
                onError = errors::append), NoopTransport)
            try { sdk.track("bad", properties = mapOf("value" to JsonValue.NumberValue(Double.NaN))); fail("Expected serialization failure") }
            catch (_: TranthorException.InvalidPayload) {}
            try { sdk.identify("invalid", traits = mapOf("value" to JsonValue.NumberValue(Double.POSITIVE_INFINITY))); fail("Expected serialization failure") }
            catch (_: TranthorException.InvalidPayload) {}
            assertNull(sdk.userId)
            val entries = errors.snapshot()
            assertEquals(2, entries.size)
            assertTrue(entries.all { it.attempts == 0 && it.error.status == null && it.events.size == 1 })
            sdk.shutdown()
            assertTrue(savedEvents(root).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun jsonCodecRoundTripsAndRejectsMalformedInput() {
        val text = """{"s":"a\"b\\c\n\u0001é😀","n":-1.5e3,"i":12,"z":0,"neg":-0,"t":true,"f":false,"null":null,"a":[1,[2],{}],"o":{"k":"v"}}"""
        val value = JsonValue.parse(text)
        assertEquals(value, JsonValue.parse(value.toJson()))
        assertEquals("a\"b\\c\n\u0001é😀", value["s"]?.stringValue)
        assertEquals(-1500.0, value["n"]?.numberValue)
        assertEquals("12", JsonCodec.formatNumber(12.0))
        assertEquals("0.5", JsonCodec.formatNumber(0.5))
        assertEquals("""{"x":"\u2028"}""".replace("\u2028", "\\u2028"), JsonValue.of(mapOf("x" to "\u2028")).toJson())
        for (bad in listOf("", "{", "{\"a\":}", "[1,]", "01", "1.", "-", "nul", "\"\u0001\"", "{} x", "[\"\\x\"]", "1e999")) {
            assertThrows(bad, IllegalArgumentException::class.java) { JsonValue.parse(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { JsonValue.NumberValue(Double.NaN).toJson() }
        assertThrows(IllegalArgumentException::class.java) { JsonValue.of(Any()) }
        assertThrows(IllegalArgumentException::class.java) { JsonValue.parse("[".repeat(10_000)) }
        assertEquals(JsonValue.of(listOf(1, "a", null, true)), JsonValue.parse("[1,\"a\",null,true]"))
    }
}
