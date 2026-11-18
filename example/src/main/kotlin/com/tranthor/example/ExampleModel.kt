package com.tranthor.example

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.core.content.edit
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tranthor.sdk.Configuration
import com.tranthor.sdk.JsonValue
import com.tranthor.sdk.TranthorSDK
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Launch-only overrides (intent extras); never persisted. MainActivity is exported (every launcher
 * activity is), so any installed app can send these extras. They are therefore honoured only in
 * debuggable builds, only for a loopback mock host (127.0.0.1, localhost, 10.0.2.2), and only as a
 * pair: a launch host is never combined with the Keystore-saved key. Use non-secret fixtures only.
 */
class LaunchOptions(
    val host: String?,
    val writeKey: String?,
    val flushIntervalMs: Int?,
    val uiTesting: Boolean,
    val ignoredReason: String? = null,
) {
    companion object {
        const val HOST = "TRANTHOR_HOST"
        const val WRITE_KEY = "TRANTHOR_WRITE_KEY"
        const val FLUSH_INTERVAL = "TRANTHOR_FLUSH_INTERVAL_MS"
        const val UI_TESTING = "TRANTHOR_UI_TESTING"
        private val loopbackHosts = setOf("127.0.0.1", "localhost", "10.0.2.2")

        fun isLoopbackHost(value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return uri.scheme?.lowercase() in setOf("http", "https") && uri.host?.lowercase() in loopbackHosts &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        }

        fun from(intent: Intent?, debuggable: Boolean = BuildConfig.DEBUG): LaunchOptions {
            val host = intent?.getStringExtra(HOST)
            val key = intent?.getStringExtra(WRITE_KEY)
            val interval = intent?.getStringExtra(FLUSH_INTERVAL)?.toIntOrNull()?.takeIf { it > 0 }
            val uiTesting = intent?.getBooleanExtra(UI_TESTING, false) == true
            val reason = when {
                host == null && key == null -> null
                !debuggable -> "Launch host/key overrides are ignored in release builds."
                host == null || key == null -> "Launch overrides need both a loopback host and a launch key; ignored (the saved key is never sent to a launch host)."
                !isLoopbackHost(host) -> "Launch host is not a loopback mock (127.0.0.1, localhost, 10.0.2.2); ignored."
                else -> null
            }
            val accepted = reason == null && host != null && key != null
            return LaunchOptions(if (accepted) host else null, if (accepted) key else null,
                if (debuggable) interval else null, debuggable && uiTesting, reason)
        }
    }
}

enum class ExampleEvent { TRACK, PAGE, SCREEN }

/**
 * Example state. Mutations hold one FIFO gate so reconfiguration cannot shut down an SDK captured
 * by an in-progress action; flush deliberately does not hold it so opt-out can interrupt delivery.
 */
class ExampleModel(context: Context, launch: LaunchOptions, private val scope: CoroutineScope, val push: ExamplePush) {
    private val app = context.applicationContext
    private val secrets = SecretStore(app)
    private val preferences = app.getSharedPreferences("example", Context.MODE_PRIVATE)
    private val gate = Mutex()

    var writeKey by mutableStateOf("")
        private set
    var host by mutableStateOf(Configuration.DEFAULT_HOST)
        private set
    var enteredUserId by mutableStateOf("")
    var burstCount by mutableIntStateOf(25)
    var slowFlush by mutableStateOf(false)
        private set
    private var launchFlushInterval: Int? = launch.flushIntervalMs
    val flushIntervalMs: Int get() = launchFlushInterval ?: if (slowFlush) 60_000 else 500
    var configured by mutableStateOf(false)
        private set
    var configuring by mutableStateOf(false)
        private set
    var flushing by mutableStateOf(false)
        private set
    var anonymousId by mutableStateOf("Not configured")
        private set
    var userId by mutableStateOf("Anonymous")
        private set
    var optedOut by mutableStateOf(false)
        private set
    var lastFlush by mutableStateOf("Not requested")
        private set
    var acceptedCalls by mutableIntStateOf(0)
        private set
    val errors = mutableStateListOf<String>()
    val lines = mutableStateListOf<String>()
    val runId: String = UUID.randomUUID().toString()
    var keyFromLaunch by mutableStateOf(false)
        private set
    private var hostFromLaunch = false
    private var sdk: TranthorSDK? = null
    private var generation = 0
    // A launch-override (debug, loopback, fixture key) session never opens the user's namespace, so it
    // cannot see or deliver the user's queued events or identity.
    private val namespace = when {
        launch.uiTesting -> "ui-test-${UUID.randomUUID()}"
        launch.host != null -> "launch-override"
        else -> "android-example"
    }

    init {
        slowFlush = preferences.getBoolean("slowFlush", false)
        launch.flushIntervalMs?.let { slowFlush = it > 500 }
        if (launch.host != null) { host = launch.host; hostFromLaunch = true }
        else preferences.getString("host", null)?.let { host = it }
        if (launch.writeKey != null) { writeKey = launch.writeKey; keyFromLaunch = true }
        else try { writeKey = secrets.read() ?: "" } catch (_: Exception) { log("Could not read the Keystore-encrypted key; enter the key again.") }
        launch.ignoredReason?.let { log(it) }
        log("Reconnect auto-flush is disabled; SDK retries run on their own schedule.")
        log("Launch runId: $runId")
        push.attach(onLog = ::log)
    }

    val properties: Map<String, JsonValue>
        get() = mapOf("liveTest" to JsonValue.BooleanValue(true), "runId" to JsonValue.StringValue(runId),
            "source" to JsonValue.StringValue("android-example"))

    fun log(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.add("[$time] $text")
        while (lines.size > 200) lines.removeAt(0)
    }

    fun editKey(value: String) { writeKey = value; keyFromLaunch = false }
    fun editHost(value: String) { host = value; hostFromLaunch = false }

    fun launch(block: suspend () -> Unit) { scope.launch { block() } }

    suspend fun start() { if (writeKey.isNotEmpty() && !configured) configure() }

    suspend fun setSlowFlush(enabled: Boolean) {
        slowFlush = enabled; launchFlushInterval = null
        preferences.edit { putBoolean("slowFlush", enabled) }
        log(if (enabled) "Slow flush enabled (60 s); press Home to exercise lifecycle delivery." else "Normal flush interval restored (500 ms).")
        if (configured) configure()
    }

    suspend fun forgetKey() = gate.withLock {
        push.disable()
        sdk?.shutdown()
        generation++
        sdk = null; configured = false; writeKey = ""; keyFromLaunch = false
        anonymousId = "Not configured"; userId = "Anonymous"; optedOut = false
        lastFlush = "Not requested"; errors.clear(); flushing = false
        try { secrets.delete(); log("Saved write key forgotten; configure with a new key to resume.") }
        catch (_: Exception) { log("Keystore deletion failed; the field is cleared, but try Forget key again.") }
    }

    suspend fun configure() {
        if (configuring) return
        // Capture values AND their origins before suspending: later field edits must not leak in.
        val key = writeKey; val selectedHost = host
        val saveKey = !keyFromLaunch; val saveHost = !hostFromLaunch
        if (key.isEmpty()) { log("Enter a write key before configuring."); return }
        val uri = runCatching { URI(selectedHost) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("https", "http") || uri.host == null || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null) {
            log("Host must be an HTTP(S) origin without credentials, query or fragment."); return
        }
        configuring = true
        try {
            gate.withLock {
                generation++
                val current = generation
                push.disable()
                sdk?.shutdown()
                configured = false
                lastFlush = "Not requested"
                try {
                    sdk = TranthorSDK.configure(app, Configuration(key, host = selectedHost, flushIntervalMs = flushIntervalMs,
                        storageNamespace = namespace, onError = { failure ->
                            val status = failure.error.status?.toString() ?: "transport"
                            val entry = "${failure.events.size} event(s), ${failure.attempts} attempt(s), error: $status / ${failure.error.message}"
                            scope.launch { if (generation == current) recordFailure(entry) }
                        }))
                    configured = true
                    if (saveKey) runCatching { secrets.write(key) }.onFailure { log("Configured, but saving the key failed. Re-enter it next launch.") }
                    if (saveHost) preferences.edit { putString("host", selectedHost) }
                    refresh()
                    log("Flush interval: $flushIntervalMs ms. Track does not explicitly flush.")
                    log("Configured. Credentials are hidden; queued state restored from this app's durable namespace.")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    sdk = null
                    log("Configuration failed (${error.javaClass.simpleName}). Check the host and local storage.")
                }
            }
        } finally { configuring = false }
    }

    private fun recordFailure(entry: String) {
        errors.add(entry)
        while (errors.size > 200) errors.removeAt(0)
        log("Terminal batch error: $entry")
    }

    private suspend fun refresh() {
        val client = sdk ?: return
        anonymousId = client.anonymousId
        userId = client.userId ?: "Anonymous"
        optedOut = client.isOptedOut
        push.synchronize(client)
    }

    suspend fun identify() = gate.withLock {
        val client = sdk?.takeIf { configured && !configuring } ?: return@withLock
        val selected = enteredUserId
        if (selected.isEmpty()) { log("Enter a user ID before Identify."); return@withLock }
        try {
            // The v1 identify API accepts traits/context, not properties.
            client.identify(userId = selected, traits = properties, context = properties)
            refresh()
            log(if (optedOut) "Identify discarded: opted out." else "Identify accepted with live-test tags in traits/context.")
        } catch (error: Exception) { if (error is CancellationException) throw error; log("Identify could not be persisted.") }
    }

    suspend fun send(event: ExampleEvent, count: Int = 1) = gate.withLock {
        val client = sdk?.takeIf { configured && !configuring && count > 0 } ?: return@withLock
        val tags = properties
        try {
            repeat(count) {
                when (event) {
                    ExampleEvent.TRACK -> client.track("Android Example Tagged Event", properties = tags)
                    ExampleEvent.PAGE -> client.page("Android Example", properties = tags)
                    ExampleEvent.SCREEN -> client.screen("Example Home", properties = tags)
                }
                if (!optedOut) acceptedCalls++
            }
            log(if (optedOut) "Call discarded: opted out." else "$count $event call(s) accepted. This is not a delivery acknowledgement.")
        } catch (error: Exception) { if (error is CancellationException) throw error; log("Event could not be persisted.") }
    }

    suspend fun flush() {
        val client = sdk?.takeIf { configured && !flushing && !configuring } ?: return
        val current = generation
        flushing = true
        lastFlush = "Waiting for delivery…"
        log("Flush started.")
        try {
            client.flush()
            if (current != generation) return
            lastFlush = if (optedOut) "Succeeded (opted out; no request)" else "Succeeded"
            log("Flush: $lastFlush.")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (current != generation) return
            lastFlush = "Failed or cancelled; see terminal errors"
            log("Flush failed or was cancelled. Terminal errors below describe delivery failures.")
        } finally { flushing = false }
    }

    suspend fun changeConsent() = gate.withLock {
        val client = sdk?.takeIf { configured && !configuring } ?: return@withLock
        try {
            if (optedOut) client.optIn() else client.optOut()
            refresh()
            log(if (optedOut) "Opted out: queued events discarded and retries cancelled." else "Opted in: new events will be accepted.")
        } catch (error: Exception) { if (error is CancellationException) throw error; refresh(); log("Consent change could not be saved.") }
    }

    suspend fun reset() = gate.withLock {
        val client = sdk?.takeIf { configured && !configuring } ?: return@withLock
        try { client.reset(); refresh(); enteredUserId = ""; log("Identity reset; a new anonymous ID is active.") }
        catch (error: Exception) { if (error is CancellationException) throw error; log("Identity reset could not be saved.") }
    }
}
