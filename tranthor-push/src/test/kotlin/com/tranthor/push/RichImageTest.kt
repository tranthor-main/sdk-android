package com.tranthor.push

import com.tranthor.testing.MiniHttpServer
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RichImageTest {
    private var server: MiniHttpServer? = null
    private val hits = AtomicInteger()
    @After fun stop() { server?.close(); server = null }

    /** Structurally valid images built by hand (android.jar has no ImageIO). */
    private fun image(format: String, width: Int = 4, height: Int = 3): ByteArray = when (format) {
        "png" -> png(width, height)
        "gif" -> gif(width, height)
        "jpg" -> jpeg(width, height)
        else -> error(format)
    }

    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            out.write(java.nio.ByteBuffer.allocate(4).putInt(data.size).array())
            val typed = type.toByteArray(Charsets.US_ASCII) + data
            out.write(typed)
            val crc = CRC32().apply { update(typed) }.value.toInt()
            out.write(java.nio.ByteBuffer.allocate(4).putInt(crc).array())
        }
        chunk("IHDR", java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array())
        val raw = ByteArray(height * (1 + width * 3)) { if (it % (1 + width * 3) == 0) 0 else 0x7F }
        val deflater = Deflater().apply { setInput(raw); finish() }
        val buffer = ByteArray(raw.size + 64)
        val compressed = buffer.copyOf(deflater.deflate(buffer))
        chunk("IDAT", compressed)
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun gif(width: Int, height: Int): ByteArray {
        val bytes = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0, 0x80.toByte(), 0, 0, -1, -1, -1, 0, 0, 0,
            0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0, 0x3B)
        bytes[6] = (width and 0xFF).toByte(); bytes[7] = (width shr 8).toByte()
        bytes[8] = (height and 0xFF).toByte(); bytes[9] = (height shr 8).toByte()
        return bytes
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(-1, 0xD8.toByte()))
        out.write(byteArrayOf(-1, 0xE0.toByte(), 0, 16) + "JFIF".toByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
        out.write(byteArrayOf(-1, 0xC0.toByte(), 0, 11, 8, (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), 1, 1, 0x11, 0))
        out.write(ByteArray(64) { 0x55 })
        out.write(byteArrayOf(-1, 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun serve(status: Int = 200, contentType: String? = "image/png", body: ByteArray = image("png"), contentLength: Long? = null,
                      headers: Map<String, String> = emptyMap(), stall: CountDownLatch? = null): String {
        val http = MiniHttpServer {
            hits.incrementAndGet()
            val all = LinkedHashMap(headers)
            contentType?.let { all["Content-Type"] = it }
            MiniHttpServer.Response(status, all, body, contentLength) { stall?.await(10, TimeUnit.SECONDS) }
        }
        server = http
        return http.url + "/image"
    }

    private fun downloader(config: RichImageConfiguration = RichImageConfiguration(setOf("images.example.com"))) =
        RichImageDownloader(config, { it.openConnection() as HttpURLConnection }, { config.accepts(it) || it.startsWith("http://127.0.0.1:") })

    private fun expect(error: RichImageError, block: () -> Unit) {
        try { block(); fail("Expected $error") } catch (thrown: RichImageException) { assertEquals(error, thrown.error) }
    }

    @Test fun exactHttpsAllowlistAndConfigurationBounds() {
        val config = RichImageConfiguration(setOf("Images.Example.com"))
        assertTrue(config.accepts("https://images.example.com/a.png"))
        assertTrue(config.accepts("https://IMAGES.example.com:443/a.png?signature=abc"))
        for (bad in listOf("http://images.example.com/a.png", "https://evil.example.com/a.png", "https://images.example.com.evil.com/a.png",
            "https://user:pass@images.example.com/a.png", "https://images.example.com:8443/a.png", "https://images.example.com/a.png#frag",
            "ftp://images.example.com/a.png", "not a url", "https://images.example.com/" + "a".repeat(5000))) {
            assertFalse(bad, config.accepts(bad))
        }
        assertFalse("The empty default rejects all remote images", RichImageConfiguration(emptySet()).accepts("https://images.example.com/a.png"))
        for (make in listOf<() -> Unit>(
            { RichImageConfiguration(setOf("a.com"), maximumBytes = 0) },
            { RichImageConfiguration(setOf("a.com"), maximumBytes = 10 * 1024 * 1024 + 1) },
            { RichImageConfiguration(setOf("a.com"), timeoutMs = 999) },
            { RichImageConfiguration(setOf("a.com"), timeoutMs = 20_001) },
            { RichImageConfiguration(setOf("bad host")) }, { RichImageConfiguration(setOf("")) },
        )) {
            try { make(); fail("Invalid configuration accepted") } catch (_: IllegalArgumentException) {}
        }
        RichImageConfiguration(setOf("a.com"), maximumBytes = 10 * 1024 * 1024, timeoutMs = 20_000)
    }

    @Test fun imageValidationAndMimeMismatch() {
        assertEquals(4, ImageValidation.validate(image("png"), "image/png").width)
        assertEquals(3, ImageValidation.validate(image("jpg"), "image/jpeg").height)
        assertEquals(4, ImageValidation.validate(image("gif"), "image/gif").width)
        expect(RichImageError.INVALID_IMAGE) { ImageValidation.validate(image("png"), "image/jpeg") }
        expect(RichImageError.INVALID_IMAGE) { ImageValidation.validate(image("png"), "image/webp") }
        expect(RichImageError.INVALID_IMAGE) { ImageValidation.validate("hello".toByteArray(), "image/png") }
    }

    @Test fun truncatedPngJpegAndGifAreRejectedEvenWhenHeadersParse() {
        for ((format, mime) in listOf("png" to "image/png", "jpg" to "image/jpeg", "gif" to "image/gif")) {
            val bytes = image(format, 64, 64)
            expect(RichImageError.TRUNCATED_IMAGE) { ImageValidation.validate(bytes.copyOf(bytes.size - 1), mime) }
        }
    }

    @Test fun pixelBoundRejectsHugeFirstFrame() {
        // A forged PNG header claiming 5000x5000 (25 MP) with a valid trailer.
        val real = image("png")
        val forged = real.copyOf()
        val dims = byteArrayOf(0, 0, 0x13, 0x88.toByte(), 0, 0, 0x13, 0x88.toByte())
        System.arraycopy(dims, 0, forged, 16, 8)
        expect(RichImageError.INVALID_IMAGE) { ImageValidation.validate(forged, "image/png") }
    }

    @Test fun streamingValidDownload() {
        val bytes = image("png")
        val (downloaded, mime) = downloader().download(serve(body = bytes))
        assertTrue(bytes.contentEquals(downloaded)); assertEquals("image/png", mime)
    }

    @Test fun httpAndMimeErrorsAreRejected() {
        expect(RichImageError.INVALID_RESPONSE) { downloader().download(serve(status = 404)) }; stop()
        expect(RichImageError.INVALID_RESPONSE) { downloader().download(serve(contentType = "text/html")) }; stop()
        expect(RichImageError.INVALID_RESPONSE) { downloader().download(serve(contentType = "image/svg+xml")) }; stop()
        expect(RichImageError.INVALID_IMAGE) { downloader().download(serve(contentType = "image/jpeg")) }; stop()
    }

    @Test fun redirectsAreNeverFollowed() {
        expect(RichImageError.INVALID_RESPONSE) {
            downloader().download(serve(status = 302, contentType = null, body = ByteArray(0), headers = mapOf("Location" to "https://images.example.com/x.png")))
        }
        assertEquals(1, hits.get())
    }

    @Test fun declaredAndStreamedSizeLimits() {
        val bytes = image("png", 200, 200)
        val small = RichImageConfiguration(setOf("images.example.com"), maximumBytes = bytes.size - 1)
        expect(RichImageError.OVERSIZED_IMAGE) { downloader(small).download(serve(body = bytes)) }; stop()
        // Chunked response without Content-Length: the streaming byte cap still applies.
        expect(RichImageError.OVERSIZED_IMAGE) { downloader(small).download(serve(body = bytes, contentLength = -1)) }
    }

    @Test fun disallowedUrlNeverConnects() {
        val strict = RichImageDownloader(RichImageConfiguration(setOf("images.example.com")))
        val url = serve()
        expect(RichImageError.DISALLOWED_URL) { strict.download(url) }
        expect(RichImageError.DISALLOWED_URL) { strict.download("https://evil.example.com/a.png") }
        assertEquals(0, hits.get())
    }

    @Test fun timeoutPreservesTextPath() {
        val stall = CountDownLatch(1)
        val config = RichImageConfiguration(setOf("images.example.com"), timeoutMs = 1000)
        val started = System.nanoTime()
        try {
            downloader(config).download(serve(stall = stall)); fail("Expected timeout")
        } catch (error: RichImageException) {
            assertTrue(error.error == RichImageError.TIMED_OUT || error.error == RichImageError.INVALID_RESPONSE)
        } finally { stall.countDown() }
        assertTrue("Time bound must hold", (System.nanoTime() - started) / 1e6 < 5000)
    }

    @Test fun notificationMarkerHelpersRoundTrip() {
        val marker = PushNotification.marker("delivery-1", "https://images.example.com/a.png")
        assertEquals("""{"version":1,"deliveryId":"delivery-1","imageUrl":"https://images.example.com/a.png"}""", marker)
        assertThrows(IllegalArgumentException::class.java) { PushNotification.marker("bad id") }
    }
}
