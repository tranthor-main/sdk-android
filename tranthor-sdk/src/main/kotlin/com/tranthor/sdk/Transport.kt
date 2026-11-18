package com.tranthor.sdk

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Lets the delivery cycle abort a blocking request from another thread (cancellation, shutdown, watchdog). */
internal class AbortHandle {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile var aborted: Boolean = false
        private set

    fun attach(value: HttpURLConnection) {
        connection = value
        if (aborted) value.disconnect()
    }
    fun abort() {
        aborted = true
        connection?.disconnect()
    }
}

internal interface SdkTransport {
    /** Blocking. Returns on 2xx; throws [DeliveryFailure] otherwise. */
    fun send(batch: List<QueueItem>, abort: AbortHandle)
    fun shutdown() {}
}

internal fun endpointFor(host: String): URL {
    val trimmed = host.trimEnd('/')
    val uri = try { URI(trimmed) } catch (_: Exception) { throw TranthorException.InvalidHost() }
    val scheme = uri.scheme?.lowercase()
    if ((scheme != "https" && scheme != "http") || uri.host.isNullOrEmpty() || !uri.isAbsolute) throw TranthorException.InvalidHost()
    return try { URL("$trimmed/api/public/apps/batch") } catch (_: Exception) { throw TranthorException.InvalidHost() }
}

internal fun encodeBatch(batch: List<QueueItem>): ByteArray {
    val out = StringBuilder(16 + batch.sumOf { it.json.length + 1 })
    out.append("{\"batch\":[")
    batch.forEachIndexed { index, item -> if (index > 0) out.append(','); out.append(item.json) }
    out.append("]}")
    return out.toString().toByteArray(Charsets.UTF_8)
}

/**
 * HttpURLConnection transport. Redirects are refused (`instanceFollowRedirects = false`, so a 3xx is a
 * terminal HTTP failure); fixed-length streaming prevents the JDK's silent POST resend on a stale
 * keep-alive connection. Connect and read timeouts are 30 s each; the SDK adds a 60 s total watchdog.
 */
internal class HttpTransport(
    private val endpoint: URL,
    private val writeKey: String,
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 30_000,
) : SdkTransport {
    override fun send(batch: List<QueueItem>, abort: AbortHandle) {
        if (abort.aborted) throw DeliveryFailure(null, "Delivery cancelled")
        val body = encodeBatch(batch)
        val status: Int
        val retryAfterHeader: String?
        var connection: HttpURLConnection? = null
        try {
            connection = endpoint.openConnection() as HttpURLConnection
            abort.attach(connection)
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", writeKey)
            connection.outputStream.use { it.write(body) }
            status = connection.responseCode
            retryAfterHeader = connection.getHeaderField("Retry-After")
        } catch (error: IOException) {
            connection?.disconnect()
            throw DeliveryFailure(null, "Network delivery failure")
        } catch (error: RuntimeException) {
            connection?.disconnect()
            throw DeliveryFailure(null, "Network delivery failure")
        }
        try {
            // A received 2xx is an acknowledgement even if draining the body later fails.
            if (status in 200..299) {
                drain { connection.inputStream }
                return
            }
            drain { connection.errorStream }
            if (status < 0) throw DeliveryFailure(null, "No HTTP response")
            throw DeliveryFailure(status, "HTTP delivery failure", retryAfter(retryAfterHeader))
        } finally {
            connection.disconnect()
        }
    }

    private inline fun drain(stream: () -> InputStream?) {
        try {
            stream()?.use { input -> val buffer = ByteArray(1024); while (input.read(buffer) >= 0) Unit }
        } catch (_: IOException) {
        }
    }
}
