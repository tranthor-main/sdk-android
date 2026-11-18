package com.tranthor.sdk

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Drives ProcessLifecycleHook with a real LifecycleRegistry on the main thread. */
@RunWith(AndroidJUnit4::class)
class LifecycleHookTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** Fails the first [failures] attempts with 503, then succeeds. */
    private class CountingTransport(private val failures: Int) : SdkTransport {
        val attempts = AtomicInteger()
        override fun send(batch: List<QueueItem>, abort: AbortHandle) {
            if (attempts.incrementAndGet() <= failures) throw DeliveryFailure(503, "fixture")
        }
    }

    private suspend fun waitFor(timeoutMs: Long = 3000, condition: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) { if (System.currentTimeMillis() > deadline) return false; delay(5) }
        return true
    }

    private fun root() = File(context.cacheDir, "lifecycle-${UUID.randomUUID()}")

    /** Repeats of the back-to-back event tests; pass `-e raceIterations 1000` for a longer soak. */
    private fun iterations(): Int = InstrumentationRegistry.getArguments().getString("raceIterations")?.toIntOrNull() ?: 20

    private suspend fun make(root: File, transport: SdkTransport, baseDelay: Int,
                             initial: Lifecycle.State = Lifecycle.State.RESUMED): Pair<TranthorSDK, Pair<Owner, ProcessLifecycleHook>> {
        val sdk = TranthorSDK.create(Configuration("fixture", batchSize = 100, flushIntervalMs = 60000, baseDelay = baseDelay,
            automaticContext = false, storageDirectory = root), AndroidPlatform(context), transport)
        val owner = Owner(); val hook = ProcessLifecycleHook(sdk)
        instrumentation.runOnMainSync { owner.registry.currentState = initial; owner.registry.addObserver(hook) }
        return sdk to (owner to hook)
    }

    @Test fun pauseFlushRetainsOrdinaryRetryDelivery() = runBlocking {
        val root = root()
        try {
            val transport = CountingTransport(failures = 1)
            val (sdk, hooks) = make(root, transport, baseDelay = 1)
            sdk.track("inactive")
            instrumentation.runOnMainSync { hooks.first.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
            assertTrue("ON_PAUSE must retry without a background/foreground cycle", waitFor { transport.attempts.get() >= 2 })
            assertEquals(2, transport.attempts.get())
            assertFalse(sdk.deliveryPaused())
            hooks.second.dispose(); sdk.shutdown()
        } finally { root.deleteRecursively() }
    }

    @Test fun stopPromotesRunningPauseFlushToOneAttemptBackgroundMode() = runBlocking {
        val root = root()
        try {
            val transport = CountingTransport(failures = Int.MAX_VALUE)
            val (sdk, hooks) = make(root, transport, baseDelay = 1000)
            sdk.track("home")
            instrumentation.runOnMainSync { hooks.first.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
            assertTrue(waitFor { transport.attempts.get() >= 1 })
            delay(150)
            assertEquals(1, transport.attempts.get()); assertFalse(sdk.deliveryPaused())
            instrumentation.runOnMainSync { hooks.first.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
            assertTrue("Failed background delivery must pause with the batch retained", waitFor(1500) { sdk.deliveryPaused() })
            assertEquals("ON_STOP must cancel the backoff and make one immediate attempt", 2, transport.attempts.get())
            hooks.second.dispose(); sdk.shutdown()
            val queue = File(JsonStore(root, "default", AtomicFileIo).directory, "queue")
            val kept = queue.list()!!.filter { it.endsWith(".json") }.sorted().map { JsonValue.parse(File(queue, it).readText())["event"]?.stringValue }
            assertEquals(listOf("home"), kept)
        } finally { root.deleteRecursively() }
    }

    /** ProcessLifecycleOwner dispatches ON_PAUSE and ON_STOP in one runnable: no gap between them. */
    @Test fun backToBackPauseStopInOneRunnablePromotesToBackgroundMode() = runBlocking {
        repeat(iterations()) {
            val root = root()
            try {
                val transport = CountingTransport(failures = Int.MAX_VALUE)
                val (sdk, hooks) = make(root, transport, baseDelay = 5000)
                sdk.track("home")
                instrumentation.runOnMainSync {
                    hooks.first.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                    hooks.first.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                }
                assertTrue("Back-to-back ON_STOP must promote to one attempt + pause", waitFor(2000) { sdk.deliveryPaused() })
                assertTrue(transport.attempts.get() in 1..2)
                hooks.second.dispose(); sdk.shutdown()
            } finally { root.deleteRecursively() }
        }
    }

    /** ON_STOP immediately followed by ON_START: delivery must continue in the foreground. */
    @Test fun backToBackStopStartKeepsForegroundDelivery() = runBlocking {
        repeat(iterations()) {
            val root = root()
            try {
                val transport = CountingTransport(failures = 1)
                val (sdk, hooks) = make(root, transport, baseDelay = 10, initial = Lifecycle.State.STARTED)
                sdk.track("x")
                instrumentation.runOnMainSync {
                    hooks.first.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                    hooks.first.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                }
                // Either the retry delivered it, or the background attempt paused and ON_START cleared the pause
                // and re-armed the interval. Never left paused in the foreground.
                assertTrue("Foreground must not stay paused", waitFor(3000) {
                    !sdk.deliveryPaused() && (transport.attempts.get() >= 2 || sdk.pendingTimer() != null)
                })
                hooks.second.dispose(); sdk.shutdown()
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun startClearsPauseAndResumesDeliveryTimer() = runBlocking {
        val root = root()
        try {
            val (sdk, hooks) = make(root, CountingTransport(failures = Int.MAX_VALUE), baseDelay = 1, initial = Lifecycle.State.STARTED)
            sdk.track("waiting")
            instrumentation.runOnMainSync { hooks.first.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
            assertTrue("A failed background attempt must pause delivery", waitFor { sdk.deliveryPaused() })
            instrumentation.runOnMainSync { hooks.first.registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
            assertTrue("Returning to the foreground must resume delivery", waitFor { !sdk.deliveryPaused() && sdk.pendingTimer() != null })
            assertNotNull(sdk.pendingTimer())
            hooks.second.dispose(); sdk.shutdown()
        } finally { root.deleteRecursively() }
    }

    @Test fun atomicFileStoreRoundTripsAndRecoversLegacyBackup() {
        val root = root()
        try {
            val store = JsonStore(root, "atomic", AtomicFileIo)
            store.load()
            store.save(StoredState(userId = "u1")); store.saveConsent(false)
            assertEquals("u1", JsonStore(root, "atomic", AtomicFileIo).load().userId)
            // Simulate a pre-API-30 crash mid-write: partial base plus a complete .bak.
            val good = store.stateFile.readBytes()
            File(store.stateFile.path + ".bak").writeBytes(good)
            store.stateFile.writeBytes(good.copyOf(good.size / 2))
            val restored = JsonStore(root, "atomic", AtomicFileIo).load()
            assertEquals("AtomicFile must restore the backup instead of quarantining", "u1", restored.userId)
            assertFalse(restored.optedOut)
        } finally { root.deleteRecursively() }
    }

    /** AndroidPlatform, except that configure() installs the real hook on an already RESUMED registry (the launch replay). */
    private inner class LaunchReplayPlatform(private val delegate: AndroidPlatform) : Platform by delegate {
        override suspend fun installLifecycle(sdk: TranthorSDK): (suspend () -> Unit) {
            val owner = Owner(); val hook = ProcessLifecycleHook(sdk)
            // Production adds its observer to ProcessLifecycleOwner inside configure(); a started app replays ON_START and ON_RESUME.
            instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.RESUMED; owner.registry.addObserver(hook) }
            return { hook.dispose() }
        }
    }

    @Test fun aLaunchReplayCoalescesWithTheAppStartsRemovalCycle() = runBlocking {
        val root = root()
        try {
            val transport = CountingTransport(failures = Int.MAX_VALUE)
            fun configuration() = Configuration("fixture", batchSize = 100, flushIntervalMs = 60000, retries = 0,
                automaticContext = false, storageDirectory = root)
            val first = TranthorSDK.create(configuration(), AndroidPlatform(context), transport)
            first.identify("user-1") // Only a signed-in customer's phone is registered.
            first.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
            first.optOut()
            assertTrue(waitFor { transport.attempts.get() >= 1 })
            delay(200)
            first.shutdown()
            val restarted = TranthorSDK.create(configuration(), LaunchReplayPlatform(AndroidPlatform(context)), transport)
            assertTrue(waitFor { transport.attempts.get() >= 2 })
            delay(500)
            assertEquals("App start plus its launch replay make one attempt cycle", 2, transport.attempts.get())
            restarted.shutdown()
        } finally { root.deleteRecursively() }
    }

    /** shutdown() posts the observer removal to the main looper instead of awaiting it, so blocking main on it cannot deadlock. */
    @Test fun shutdownFromRunBlockingOnMainDoesNotDeadlock() {
        val root = root()
        var completed: Boolean? = null
        instrumentation.runOnMainSync {
            completed = runBlocking {
                val sdk = TranthorSDK.configure(context, Configuration("fixture", automaticContext = false, storageDirectory = root))
                kotlinx.coroutines.withTimeoutOrNull(5000) { sdk.shutdown(); true } ?: false
            }
        }
        root.deleteRecursively()
        assertEquals("shutdown() deadlocks when awaited with runBlocking on the main thread", true, completed)
    }
}
