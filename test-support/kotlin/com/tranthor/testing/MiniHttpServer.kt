package com.tranthor.testing

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny HTTP/1.1 test server on 127.0.0.1 (android.jar, which the unit tests compile against, has
 * no com.sun.net.httpserver). One response per connection, then the connection closes.
 */
internal class MiniHttpServer(private val handler: (Request) -> Response) : AutoCloseable {
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    /** [contentLength] null = send the body's length; -1 = omit the header and end by closing. */
    class Response(val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0),
                   val contentLength: Long? = null, val beforeBody: () -> Unit = {})

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val url: String get() = "http://127.0.0.1:$port"

    init {
        thread(isDaemon = true, name = "MiniHttpServer") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    private fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private fun serve(client: Socket) {
        client.use {
            try {
                val input = client.getInputStream()
                val requestLine = readLine(input).split(' ')
                if (requestLine.size < 2) return
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(length)
                var read = 0
                while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
                val response = handler(Request(requestLine[0], requestLine[1], headers, body))
                val out = client.getOutputStream()
                val head = StringBuilder("HTTP/1.1 ${response.status} Status\r\n")
                response.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
                val declared = response.contentLength ?: response.body.size.toLong()
                if (declared >= 0) head.append("Content-Length: ").append(declared).append("\r\n")
                head.append("Connection: close\r\n\r\n")
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                out.flush()
                response.beforeBody()
                out.write(response.body)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    override fun close() { socket.close() }
}
