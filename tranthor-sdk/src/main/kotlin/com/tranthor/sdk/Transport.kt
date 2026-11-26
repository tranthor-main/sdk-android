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
    /**
     * One request with the identity tokens beside its events. A transport that ignores tokens needs only the batch
     * form. Returns what the server said about the events it accepted, or null.
     */
    fun send(request: BatchRequest, abort: AbortHandle): ResponseNotes? { send(request.batch, abort); return null }
    fun shutdown() {}
}

/** What a 2xx answer reports besides success: events the server dropped or could not verify (identity Required or Monitor). */
internal class ResponseNotes(val droppedEvents: Int, val unverifiedEvents: Int) {
    val isEmpty: Boolean get() = droppedEvents <= 0 && unverifiedEvents <= 0
}

/** A non-negative count from a response header; anything else is 0. */
private fun headerCount(value: String?): Int =
    value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 9 && it.all { c -> c in '0'..'9' } }?.toInt() ?: 0

internal fun endpointFor(host: String): URL {
    val trimmed = host.trimEnd('/')
    val uri = try { URI(trimmed) } catch (_: Exception) { throw TranthorException.InvalidHost() }
    val scheme = uri.scheme?.lowercase()
    if ((scheme != "https" && scheme != "http") || uri.host.isNullOrEmpty() || !uri.isAbsolute) throw TranthorException.InvalidHost()
    return try { URL("$trimmed/api/public/apps/batch") } catch (_: Exception) { throw TranthorException.InvalidHost() }
}

internal fun encodeBatch(batch: List<QueueItem>, identityTokens: List<String> = emptyList()): ByteArray {
    val out = StringBuilder(16 + batch.sumOf { it.json.length + 1 })
    out.append("{\"batch\":[")
    batch.forEachIndexed { index, item -> if (index > 0) out.append(','); out.append(item.json) }
    out.append(']')
    // One token per user in the batch that has one, in the order the users first appear; omitted when there is none.
    if (identityTokens.isNotEmpty()) {
        out.append(",\"identityTokens\":[")
        identityTokens.forEachIndexed { index, token -> if (index > 0) out.append(','); JsonCodec.encode(JsonValue.StringValue(token), out) }
        out.append(']')
    }
    out.append('}')
    return out.toString().toByteArray(Charsets.UTF_8)
}

private const val MAX_ERROR_BODY_BYTES = 8 * 1024
private const val MAX_REPORTED_USER_IDS = 50

/** The machine-readable part of a 403 or 503 body, read from at most 8 KiB. Anything unreadable is simply absent. */
internal class ErrorBody(val code: String?, val userIds: List<String>, val reason: String? = null) {
    companion object {
        val EMPTY = ErrorBody(null, emptyList())

        fun parse(bytes: ByteArray): ErrorBody = try {
            val root = JsonValue.parse(String(bytes, Charsets.UTF_8)).objectValue
            val code = root?.get("code")?.stringValue?.takeIf { it.isNotEmpty() && it.length <= 100 }
            val users = root?.get("userIds")?.arrayValue.orEmpty().mapNotNull { it.stringValue?.takeIf(String::isNotEmpty) }.take(MAX_REPORTED_USER_IDS)
            // The reason is an open list ("missing", "signature", "expired", ...): it is only ever shown, never branched on.
            ErrorBody(code, users, root?.get("reason")?.stringValue?.takeIf { it.isNotEmpty() && it.length <= 50 && isPrintableAscii(it) })
        } catch (_: Exception) {
            EMPTY
        }
    }
}

/**
 * HttpURLConnection transport. Redirects are refused (`instanceFollowRedirects = false`, so a 3xx is a
 * terminal HTTP failure); fixed-length streaming prevents the JDK's silent POST resend on a stale
 * keep-alive connection. Connect and read timeouts are 30 s each; the SDK adds a 60 s total watchdog.
 */
internal class HttpTransport(
    private val endpoint: URL,
    private val writeKey: String,
    /** Sent as `x-tranthor-app-id` when not null. */
    private val appId: String? = null,
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 30_000,
) : SdkTransport {
    override fun send(batch: List<QueueItem>, abort: AbortHandle) { send(BatchRequest(batch), abort) }

    override fun send(request: BatchRequest, abort: AbortHandle): ResponseNotes? {
        if (abort.aborted) throw DeliveryFailure(null, "Delivery cancelled")
        val body = encodeBatch(request.batch, request.identityTokens)
        val status: Int
        val retryAfterHeader: String?
        var notes: ResponseNotes? = null
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
            appId?.let { connection.setRequestProperty("x-tranthor-app-id", it) }
            connection.outputStream.use { it.write(body) }
            status = connection.responseCode
            retryAfterHeader = connection.getHeaderField("Retry-After")
            if (status in 200..299) {
                notes = ResponseNotes(headerCount(connection.getHeaderField("x-tranthor-dropped-events")),
                    headerCount(connection.getHeaderField("x-tranthor-unverified-events")))
            }
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
                return notes?.takeUnless { it.isEmpty }
            }
            // The body of a 403 or 503 can say why (an identity check, a write-key restriction); read it bounded and tolerantly.
            val detail = if (status == 403 || status == 503) readErrorBody { connection.errorStream } else ErrorBody.EMPTY
            drain { connection.errorStream }
            if (status < 0) throw DeliveryFailure(null, "No HTTP response")
            throw DeliveryFailure(status, "HTTP delivery failure", retryAfter(retryAfterHeader), detail.code, detail.userIds, detail.reason,
                retryAfterGiven = !retryAfterHeader.isNullOrEmpty() && retryAfterHeader.all { it in '0'..'9' },
                heldAfterMs = retryAfter(retryAfterHeader, capSeconds = 300.0))
        } finally {
            connection.disconnect()
        }
    }

    private inline fun readErrorBody(stream: () -> InputStream?): ErrorBody = try {
        stream()?.let { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(1024)
            while (buffer.size() < MAX_ERROR_BODY_BYTES) {
                val read = input.read(chunk, 0, minOf(chunk.size, MAX_ERROR_BODY_BYTES - buffer.size()))
                if (read < 0) break
                buffer.write(chunk, 0, read)
            }
            ErrorBody.parse(buffer.toByteArray())
        } ?: ErrorBody.EMPTY
    } catch (_: IOException) {
        ErrorBody.EMPTY
    }

    private inline fun drain(stream: () -> InputStream?) {
        try {
            stream()?.use { input -> val buffer = ByteArray(1024); while (input.read(buffer) >= 0) Unit }
        } catch (_: IOException) {
        }
    }
}
