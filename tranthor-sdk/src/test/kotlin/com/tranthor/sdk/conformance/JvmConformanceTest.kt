package com.tranthor.sdk.conformance

import com.tranthor.sdk.Configuration
import com.tranthor.sdk.JvmPlatform
import com.tranthor.sdk.TranthorSDK
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * JVM host adapter: the real SDK queue, HttpURLConnection transport and persistence (temp+rename
 * atomic files) against the Node mock. Declares core/transport/persistence/consent and honestly
 * omits lifecycle: a plain JVM has no ProcessLifecycleOwner.
 * Run with scripts/test-host.sh (Gradle property tranthor.conformance).
 */
class JvmConformanceTest {
    private class JvmEnvironment(private val results: File?) : ConformanceEnvironment {
        override val adapterName = "TranthorSDK Android JVM host"
        override val capabilities = listOf("core", "transport", "persistence", "consent", "identity-link")
        override val lifecycleSource: String? = null
        private val loader = JvmConformanceTest::class.java.classLoader!!
        override fun readText(path: String): String =
            loader.getResourceAsStream(path)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: throw CheckFailure("Missing fixture $path")
        override fun scenarioFiles(): List<String> {
            val url = loader.getResource("scenarios/anonymous-track.json") ?: throw CheckFailure("Missing scenarios")
            return File(url.toURI()).parentFile!!.list { _, name -> name.endsWith(".json") }!!.toList()
        }
        override fun temporaryDirectory(): File = File(System.getProperty("java.io.tmpdir")!!)
        override suspend fun makeSdk(configuration: Configuration): TranthorSDK = TranthorSDK.create(configuration, JvmPlatform())
        override suspend fun background() = throw CheckFailure("No lifecycle on the JVM host")
        override fun emit(line: String) {
            println(line)
            results?.appendText(line + "\n")
        }
    }

    @Test
    fun conformance() = runBlocking {
        val host = conformanceHost(System.getenv(), allowDeveloperSkip = true)
        assumeTrue("Explicit developer opt-out: CONFORMANCE_ALLOW_SKIP=1", host != null)
        val results = System.getenv("CONFORMANCE_RESULTS")?.let { File(it).apply { parentFile?.mkdirs(); writeText("") } }
        val summary = ConformanceSuite(JvmEnvironment(results), host!!).run(System.getenv("CONFORMANCE_SCENARIO"))
        println("validated ${summary.validated}, passed ${summary.passed}, failed ${summary.failed}, skipped ${summary.skipped}")
        assertEquals("Conformance scenarios failed", 0, summary.failed)
    }
}
