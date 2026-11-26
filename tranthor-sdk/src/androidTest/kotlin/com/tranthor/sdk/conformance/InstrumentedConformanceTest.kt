package com.tranthor.sdk.conformance

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.tranthor.sdk.AndroidPlatform
import com.tranthor.sdk.Configuration
import com.tranthor.sdk.IdentityTimings
import com.tranthor.sdk.TranthorSDK
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Started before lifecycle scenarios so the process is foreground until the real Home press. */
class LifecycleTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Tranthor lifecycle conformance"
    }
}

/**
 * Instrumented adapter on the emulator: the real SDK with AndroidPlatform (AtomicFile persistence,
 * HttpURLConnection, ProcessLifecycleOwner hook). Declares all nine capabilities with
 * lifecycleSource "os-hook": the `background` step presses the real Home button through
 * UiAutomator, and the runner asserts ProcessLifecycleOwner actually reached CREATED (ON_STOP).
 *
 * Host: -Pandroid.testInstrumentationRunnerArguments.conformanceHost=http://127.0.0.1:P after
 * `adb reverse tcp:P tcp:P`. A missing host fails unless conformanceAllowSkip=1.
 */
@RunWith(AndroidJUnit4::class)
class InstrumentedConformanceTest {
    private class DeviceEnvironment : ConformanceEnvironment {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val target = instrumentation.targetContext
        private val results = File(target.getExternalFilesDir(null) ?: target.filesDir, "conformance-results.jsonl").apply { writeText("") }
        private var activity: ActivityScenario<LifecycleTestActivity>? = null
        override val adapterName = "TranthorSDK Android instrumented"
        override val capabilities = listOf("core", "transport", "persistence", "lifecycle", "consent", "identity-link", "durable-queue", "identity-token", "app-id")
        override val lifecycleSource: String = "os-hook"
        override fun readText(path: String): String =
            instrumentation.context.assets.open(path).use { String(it.readBytes(), Charsets.UTF_8) }
        override fun scenarioFiles(): List<String> =
            instrumentation.context.assets.list("scenarios")!!.filter { it.endsWith(".json") }
        override fun temporaryDirectory(): File = target.cacheDir
        // configure() with the two identity timings scaled for real-time scenarios.
        override suspend fun makeSdk(configuration: Configuration, timings: IdentityTimings): TranthorSDK =
            TranthorSDK.create(configuration, AndroidPlatform(target.applicationContext ?: target), identityTimings = timings)

        private fun processState(): Lifecycle.State {
            var state = Lifecycle.State.INITIALIZED
            instrumentation.runOnMainSync { state = ProcessLifecycleOwner.get().lifecycle.currentState }
            return state
        }
        private suspend fun awaitState(predicate: (Lifecycle.State) -> Boolean, description: String) {
            val deadline = System.currentTimeMillis() + 10_000
            while (!predicate(processState())) {
                if (System.currentTimeMillis() > deadline) throw CheckFailure("ProcessLifecycleOwner did not reach $description (now ${processState()})")
                delay(10)
            }
        }

        override suspend fun prepare(scenario: J) {
            val needsLifecycle = scenario["capabilities"]?.arrayValue?.any { it.stringValue == "lifecycle" } == true
            if (!needsLifecycle) return
            activity = ActivityScenario.launch(LifecycleTestActivity::class.java)
            awaitState({ it.isAtLeast(Lifecycle.State.RESUMED) }, "RESUMED")
        }

        override suspend fun background() {
            if (!processState().isAtLeast(Lifecycle.State.STARTED)) throw CheckFailure("Process must be foreground before background")
            UiDevice.getInstance(instrumentation).pressHome()
            // Proves the real OS hook fired: ProcessLifecycleOwner dispatched ON_STOP (after its ~700 ms debounce).
            awaitState({ it == Lifecycle.State.CREATED }, "CREATED after Home")
        }

        override suspend fun finish(scenario: J) {
            activity?.close(); activity = null
        }

        override fun emit(line: String) {
            Log.i(TAG, line)
            results.appendText(line + "\n")
        }
    }

    @Test
    fun conformance() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val environment = buildMap {
            arguments.getString("conformanceHost")?.let { put("CONFORMANCE_HOST", it) }
            arguments.getString("conformanceAllowSkip")?.let { put("CONFORMANCE_ALLOW_SKIP", it) }
        }
        val host = conformanceHost(environment, allowDeveloperSkip = true)
        assumeTrue("Explicit opt-out: conformanceAllowSkip=1", host != null)
        val summary = ConformanceSuite(DeviceEnvironment(), host!!).run(arguments.getString("conformanceScenario"))
        Log.i(TAG, "validated ${summary.validated}, passed ${summary.passed}, failed ${summary.failed}, skipped ${summary.skipped}")
        assertEquals("Conformance scenarios failed", 0, summary.failed)
    }

    private companion object {
        const val TAG = "TranthorConformance"
    }
}
