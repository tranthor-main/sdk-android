package com.tranthor.sdk

import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

internal class TestTimeout(message: String) : Exception(message)

internal fun tempRoot(prefix: String): File =
    File(System.getProperty("java.io.tmpdir"), "$prefix-${UUID.randomUUID()}")

internal suspend fun waitUntil(timeoutMs: Long = 5000, condition: suspend () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw TestTimeout("Condition not reached")
        delay(1)
    }
}

/**
 * Sleeps are released explicitly; no wall-clock delays drive races. A
 * cancelled sleeper is only marked cancelled and stays pending until released, so a test can make a
 * cancelled sleep return normally (`ignoringCancellation`).
 */
internal class ManualSleeper {
    private class Entry(val delay: Double) {
        val released = CompletableDeferred<Boolean>()
        @Volatile var done = false
    }
    private val lock = Any()
    private val pending = HashMap<Int, Entry>()
    private val cancelled = HashSet<Int>()
    private val asked = ArrayList<Double>()
    private var next = 0

    /** Every requested duration in call order, including sleeps that were later cancelled or released. */
    fun requested(): List<Double> = synchronized(lock) { ArrayList(asked) }

    suspend fun sleep(delay: Double) {
        val entry = Entry(delay)
        val id = synchronized(lock) { asked.add(delay); next++.also { pending[it] = entry } }
        val watcher = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!entry.done) synchronized(lock) { cancelled.add(id) } }
        }
        val ignoring = withContext(NonCancellable) { entry.released.await() }
        entry.done = true
        watcher.cancel()
        val wasCancelled = synchronized(lock) { cancelled.contains(id) }
        if (wasCancelled && !ignoring) throw CancellationException("sleeper $id cancelled")
    }

    suspend fun wait(delay: Double): Int {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            synchronized(lock) { pending.entries.firstOrNull { it.value.delay == delay && it.key !in cancelled }?.key }?.let { return it }
            delay(1)
        }
        throw TestTimeout("waiting for sleeper $delay")
    }

    suspend fun waitCancellation(id: Int) {
        waitUntil { synchronized(lock) { cancelled.contains(id) } }
    }

    fun release(id: Int, ignoringCancellation: Boolean = false) {
        val entry = synchronized(lock) { pending.remove(id) } ?: return
        entry.released.complete(ignoringCancellation)
    }

    fun releaseAll() {
        val ids = synchronized(lock) { pending.keys.toList() }
        ids.forEach { release(it) }
    }
}

/** Requests block until the test completes them; abort/cancellation never ends a request early. */
internal class ManualTransport : SdkTransport {
    private val lock = Any()
    private val requests = ArrayList<List<Map<String, JsonValue>>>()
    private val responses = HashMap<Int, CompletableFuture<DeliveryFailure?>>()
    @Volatile private var automaticSuccess = false

    fun autoSucceedFutureRequests() { automaticSuccess = true }

    override fun send(batch: List<QueueItem>, abort: AbortHandle) {
        val future = CompletableFuture<DeliveryFailure?>()
        synchronized(lock) {
            responses[requests.size] = future
            requests.add(batch.map { it.value })
        }
        if (automaticSuccess) return
        val failure = future.get(30, TimeUnit.SECONDS)
        if (failure != null) throw failure
    }

    suspend fun wait(count: Int) = waitUntil { synchronized(lock) { requests.size >= count } }

    fun complete(index: Int, error: DeliveryFailure? = null) {
        synchronized(lock) { responses.remove(index) }?.complete(error)
    }

    fun snapshot(): List<List<Map<String, JsonValue>>> = synchronized(lock) { ArrayList(requests) }
    fun count(): Int = synchronized(lock) { requests.size }
}

/** Records every callback in order (callbacks run serially on the SDK executor). */
internal class FailureLog {
    private val entries = ArrayList<BatchFailure>()
    @Synchronized fun append(failure: BatchFailure) { entries.add(failure) }
    @Synchronized fun snapshot(): List<BatchFailure> = ArrayList(entries)
}

internal object NoopTransport : SdkTransport {
    override fun send(batch: List<QueueItem>, abort: AbortHandle) {}
}

/** The events in the namespace's queue folder in file order: exactly what a restarted instance would send. */
internal fun savedEvents(directory: File, namespace: String = "default"): List<Map<String, JsonValue>> {
    val queue = File(JsonStore(directory, namespace).directory, "queue")
    val names = queue.list { _, name -> name.matches(Regex("\\d{20}\\.json")) }?.sorted() ?: return emptyList()
    return names.map { JsonValue.parse(File(queue, it).readText()).objectValue!! }
}

internal fun savedEventNames(directory: File, namespace: String = "default"): List<String> =
    savedEvents(directory, namespace).mapNotNull { it["event"]?.stringValue }

internal fun loadState(directory: File, namespace: String = "default"): StoredState =
    JsonStore(directory, namespace).load()

internal suspend fun configureJvm(
    configuration: Configuration,
    transport: SdkTransport? = null,
    sleeper: ManualSleeper? = null,
    storeWrite: ((ByteArray, File) -> Unit)? = null,
    storeMove: ((File, File) -> Unit)? = null,
    storeRead: ((File) -> ByteArray?)? = null,
    storeDelete: ((File) -> Unit)? = null,
    platform: Platform = JvmPlatform(),
    identityTimings: IdentityTimings = IdentityTimings(),
): TranthorSDK = TranthorSDK.create(
    configuration, platform, transport,
    sleep = if (sleeper != null) { ms -> sleeper.sleep(ms) } else ::sleepMilliseconds,
    storeWrite = storeWrite, storeMove = storeMove, storeRead = storeRead, storeDelete = storeDelete,
    identityTimings = identityTimings,
)
