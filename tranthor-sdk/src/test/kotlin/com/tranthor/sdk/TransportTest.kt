package com.tranthor.sdk

import com.tranthor.testing.MiniHttpServer
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransportTest {
    private class Recorded(val method: String, val path: String, val headers: Map<String, String?>, val body: String)
    private val recorded = CopyOnWriteArrayList<Recorded>()
    private var server: MiniHttpServer? = null

    private fun serve(status: Int, headers: Map<String, String> = emptyMap()): URL {
        val http = MiniHttpServer { request ->
            recorded.add(Recorded(request.method, request.path, request.headers, request.body.toString(Charsets.UTF_8)))
            MiniHttpServer.Response(status, headers)
        }
        server = http
        return endpointFor(http.url + "/")
    }

    @After fun stop() { server?.close(); server = null }

    private fun item(name: String) = QueueItem.of(jsonObjectOf("type" to "track", "event" to name, "messageId" to name))

    @Test fun postsExactEnvelopeHeadersAndPath() {
        val endpoint = serve(204)
        HttpTransport(endpoint, "Basic fixture==").send(listOf(item("a"), item("b")), AbortHandle())
        val request = recorded.single()
        assertEquals("POST", request.method)
        assertEquals("/api/public/apps/batch", request.path)
        assertEquals("Basic fixture==", request.headers["authorization"])
        assertEquals("application/json", request.headers["content-type"])
        assertEquals(JsonValue.parse("""{"batch":[{"type":"track","event":"a","messageId":"a"},{"type":"track","event":"b","messageId":"b"}]}"""),
            JsonValue.parse(request.body))
        assertEquals(setOf("batch"), JsonValue.parse(request.body).objectValue!!.keys)
    }

    @Test fun redirectIsRefusedAsTerminalHttpFailure() {
        val endpoint = serve(302, mapOf("Location" to "http://127.0.0.1:1/elsewhere"))
        try { HttpTransport(endpoint, "k").send(listOf(item("a")), AbortHandle()); fail("3xx must fail") }
        catch (failure: DeliveryFailure) {
            assertEquals(302, failure.status)
            assertFalse("3xx is terminal", failure.retryable)
        }
        assertEquals("Redirect must not be followed", 1, recorded.size)
    }

    @Test fun retryableStatusesAndRetryAfterHeader() {
        for ((status, retryable) in listOf(408 to true, 425 to true, 429 to true, 500 to true, 503 to true, 599 to true, 400 to false, 401 to false, 403 to false, 404 to false)) {
            val endpoint = serve(status, mapOf("Retry-After" to "2"))
            try { HttpTransport(endpoint, "k").send(listOf(item("a")), AbortHandle()); fail("$status must fail") }
            catch (failure: DeliveryFailure) {
                assertEquals(status, failure.status); assertEquals("$status", retryable, failure.retryable)
                assertEquals(2000.0, failure.retryAfterMs, 0.0)
            }
            stop()
        }
    }

    @Test fun anyTwoHundredIsSuccess() {
        for (status in listOf(200, 202, 204, 299)) {
            HttpTransport(serve(status), "k").send(listOf(item("a")), AbortHandle())
            stop()
        }
    }

    /** A dropped connection is a network failure (status null) sent exactly once: no silent JDK POST resend. */
    @Test fun droppedConnectionIsNetworkFailureWithoutSilentResend() {
        val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val connections = AtomicInteger()
        val acceptor = thread {
            try {
                while (true) {
                    val client = socket.accept()
                    connections.incrementAndGet()
                    val input = client.getInputStream()
                    val buffer = ByteArray(65536)
                    var text = ""
                    while (!text.contains("\r\n\r\n")) { val n = input.read(buffer); if (n < 0) break; text += String(buffer, 0, n) }
                    client.close()
                }
            } catch (_: Exception) {}
        }
        try {
            val endpoint = endpointFor("http://127.0.0.1:${socket.localPort}")
            try { HttpTransport(endpoint, "k").send(listOf(item("a")), AbortHandle()); fail("Drop must fail") }
            catch (failure: DeliveryFailure) { assertNull(failure.status); assertTrue(failure.retryable) }
            Thread.sleep(200)
            assertEquals("POST must not be resent on a dropped connection", 1, connections.get())
        } finally { socket.close(); acceptor.join(2000) }
    }

    @Test fun abortedHandleNeverConnects() {
        val endpoint = serve(204)
        val abort = AbortHandle().apply { abort() }
        try { HttpTransport(endpoint, "k").send(listOf(item("a")), abort); fail("Aborted send must fail") }
        catch (failure: DeliveryFailure) { assertNull(failure.status) }
        assertTrue(recorded.isEmpty())
    }

    @Test fun abortUnblocksAHangingRequest() {
        val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val acceptor = thread { try { val client = socket.accept(); Thread.sleep(10_000); client.close() } catch (_: Exception) {} }
        try {
            val abort = AbortHandle()
            val started = System.nanoTime()
            thread { Thread.sleep(300); abort.abort() }
            try { HttpTransport(endpointFor("http://127.0.0.1:${socket.localPort}"), "k").send(listOf(item("a")), abort); fail("Abort must fail") }
            catch (failure: DeliveryFailure) { assertNull(failure.status) }
            assertTrue("Abort must unblock promptly", (System.nanoTime() - started) / 1e6 < 5000)
        } finally { socket.close(); acceptor.interrupt() }
    }

    @Test fun productionTimeoutsAreThirtySecondsWithSixtySecondTotal() {
        val transport = HttpTransport(endpointFor("http://127.0.0.1:1"), "k")
        val fields = HttpTransport::class.java.declaredFields.associateBy { it.name }
        fields.getValue("connectTimeoutMs").isAccessible = true
        fields.getValue("readTimeoutMs").isAccessible = true
        assertEquals(30_000, fields.getValue("connectTimeoutMs").getInt(transport))
        assertEquals(30_000, fields.getValue("readTimeoutMs").getInt(transport))
    }
}
