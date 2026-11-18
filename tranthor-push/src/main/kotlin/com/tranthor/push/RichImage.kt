package com.tranthor.push

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal enum class RichImageError { DISALLOWED_URL, INVALID_RESPONSE, OVERSIZED_IMAGE, INVALID_IMAGE, TRUNCATED_IMAGE, BUSY, TIMED_OUT }

internal class RichImageException(val error: RichImageError) : Exception(error.name)

/**
 * Exact CDN hosts controlled by the app publisher. URLs and query strings are never logged.
 * Default decoded response limit 5 MiB (1 B…10 MiB) and timeout 10 s (1…20 s). Out-of-range values or
 * a malformed host throw [IllegalArgumentException].
 */
public class RichImageConfiguration @JvmOverloads constructor(
    allowedHosts: Set<String>,
    public val maximumBytes: Int = 5 * 1024 * 1024,
    public val timeoutMs: Int = 10_000,
) {
    public val allowedHosts: Set<String>

    init {
        val validHosts = allowedHosts.all { host ->
            host.isNotEmpty() && host.length <= 253 && host.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' }
        }
        if (maximumBytes !in 1..10 * 1024 * 1024 || timeoutMs !in 1_000..20_000 || !validHosts) {
            throw IllegalArgumentException("Invalid rich image configuration")
        }
        this.allowedHosts = allowedHosts.map { it.lowercase() }.toSet()
    }

    /** HTTPS only, exact allowlisted host, port 443, no credentials or fragment. */
    public fun accepts(url: String): Boolean {
        if (url.length > 4096) return false
        val uri = try { URI(url) } catch (_: Exception) { return false }
        return uri.scheme?.lowercase() == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            uri.rawFragment == null && uri.host?.lowercase()?.let(allowedHosts::contains) == true
    }
}

/** Pure-Kotlin header/trailer validation so JVM tests cover it; only bitmap decoding needs a device. */
internal object ImageValidation {
    const val MAX_PIXELS: Long = 16_000_000

    class ImageInfo(val mime: String, val width: Int, val height: Int)

    /** Validates magic, dimensions (first frame ≤ 16 MP) and format trailer against the declared MIME type. */
    fun validate(bytes: ByteArray, mime: String): ImageInfo {
        val trailerOk = when (mime) {
            "image/png" -> bytes.size >= 12 && bytes.copyOfRange(bytes.size - 12, bytes.size)
                .contentEquals(byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68, 174.toByte(), 66, 96, 130.toByte()))
            "image/jpeg" -> bytes.size >= 2 && bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()
            "image/gif" -> bytes.isNotEmpty() && bytes[bytes.size - 1] == 0x3B.toByte()
            else -> throw RichImageException(RichImageError.INVALID_IMAGE)
        }
        val info = dimensions(bytes) ?: throw RichImageException(RichImageError.INVALID_IMAGE)
        if (info.mime != mime) throw RichImageException(RichImageError.INVALID_IMAGE)
        if (!trailerOk) throw RichImageException(RichImageError.TRUNCATED_IMAGE)
        if (info.width <= 0 || info.height <= 0 || info.width.toLong() * info.height > MAX_PIXELS) throw RichImageException(RichImageError.INVALID_IMAGE)
        return info
    }

    private fun u8(bytes: ByteArray, index: Int) = bytes[index].toInt() and 0xFF
    private fun be16(bytes: ByteArray, index: Int) = (u8(bytes, index) shl 8) or u8(bytes, index + 1)
    private fun be32(bytes: ByteArray, index: Int) = (be16(bytes, index).toLong() shl 16) or be16(bytes, index + 2).toLong()

    internal fun dimensions(bytes: ByteArray): ImageInfo? {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(png) &&
            String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR") {
            val width = be32(bytes, 16); val height = be32(bytes, 20)
            if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
            return ImageInfo("image/png", width.toInt(), height.toInt())
        }
        if (bytes.size >= 10 && (String(bytes, 0, 6, Charsets.US_ASCII) == "GIF87a" || String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a")) {
            return ImageInfo("image/gif", u8(bytes, 6) or (u8(bytes, 7) shl 8), u8(bytes, 8) or (u8(bytes, 9) shl 8))
        }
        if (bytes.size >= 4 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8) {
            var index = 2
            while (index + 9 < bytes.size) {
                if (u8(bytes, index) != 0xFF) return null
                val marker = u8(bytes, index + 1)
                if (marker == 0xFF) { index++; continue }
                if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { index += 2; continue }
                val length = be16(bytes, index + 2)
                if (length < 2) return null
                val sof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (sof) return ImageInfo("image/jpeg", be16(bytes, index + 7), be16(bytes, index + 5))
                if (marker == 0xDA || marker == 0xD9) return null
                index += 2 + length
            }
        }
        return null
    }
}

/**
 * Downloads a publisher-allowlisted HTTPS image with byte and time bounds. Independent of analytics,
 * write keys, device tokens and the events queue. At most four downloads run per process.
 */
internal class RichImageDownloader(
    private val configuration: RichImageConfiguration,
    private val open: (URL) -> HttpURLConnection,
    private val accepts: (String) -> Boolean,
) {
    constructor(configuration: RichImageConfiguration) :
        this(configuration, { it.openConnection() as HttpURLConnection }, configuration::accepts)

    /** Returns the validated image bytes and MIME type. Throws [RichImageException]. */
    fun download(url: String): Pair<ByteArray, String> {
        if (!accepts(url)) throw RichImageException(RichImageError.DISALLOWED_URL)
        if (!slots.tryAcquire()) throw RichImageException(RichImageError.BUSY)
        var connection: HttpURLConnection? = null
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(configuration.timeoutMs.toLong())
        val watchdog = watchdogs.schedule({ connection?.disconnect() }, configuration.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        try {
            connection = open(URL(url)).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = configuration.timeoutMs
                readTimeout = configuration.timeoutMs
                setRequestProperty("Accept", "image/png, image/jpeg, image/gif")
                // Identity encoding keeps Content-Length meaningful for truncation checks.
                setRequestProperty("Accept-Encoding", "identity")
            }
            val status = connection.responseCode
            val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (status != 200 || mime !in setOf("image/png", "image/jpeg", "image/gif")) throw RichImageException(RichImageError.INVALID_RESPONSE)
            val declared = connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            if (declared > configuration.maximumBytes) throw RichImageException(RichImageError.OVERSIZED_IMAGE)
            val out = java.io.ByteArrayOutputStream(if (declared in 1..configuration.maximumBytes.toLong()) declared.toInt() else 64 * 1024)
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (System.nanoTime() > deadline) throw RichImageException(RichImageError.TIMED_OUT)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > configuration.maximumBytes) throw RichImageException(RichImageError.OVERSIZED_IMAGE)
                    out.write(buffer, 0, count)
                }
            }
            val bytes = out.toByteArray()
            val encoding = connection.contentEncoding?.lowercase()
            if ((encoding == null || encoding == "identity") && declared >= 0 && bytes.size.toLong() != declared) {
                throw RichImageException(RichImageError.TRUNCATED_IMAGE)
            }
            ImageValidation.validate(bytes, mime!!)
            return bytes to mime
        } catch (error: RichImageException) {
            throw error
        } catch (error: IOException) {
            throw RichImageException(if (System.nanoTime() > deadline) RichImageError.TIMED_OUT else RichImageError.INVALID_RESPONSE)
        } finally {
            watchdog.cancel(false)
            connection?.disconnect()
            slots.release()
        }
    }

    private companion object {
        val slots = Semaphore(4)
        val watchdogs = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "TranthorRichImageWatchdog").apply { isDaemon = true }
        }
    }
}
