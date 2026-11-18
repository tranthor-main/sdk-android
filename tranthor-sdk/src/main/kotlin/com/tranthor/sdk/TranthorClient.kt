package com.tranthor.sdk

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Java-friendly completion callback. Invoked on a background thread. */
public fun interface TranthorCallback<T> {
    /** Exactly one of [value] or [error] is non-null (a Unit-returning operation passes Unit). */
    public fun onComplete(value: T?, error: Throwable?)
}

/**
 * A thin non-coroutine facade over [TranthorSDK] for Java hosts. Calls are fire-and-forget and are
 * admitted strictly in invocation order through one FIFO channel; failures go to the optional
 * callback (run on a background thread; exceptions it throws are logged and ignored). Only admission
 * is serialized: [flush] waits for delivery off the channel, so later calls are never blocked by it.
 * Use the coroutine API from Kotlin.
 */
public class TranthorClient private constructor(public val sdk: TranthorSDK) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (operation in operations) operation() }
    }

    private fun <T> submit(callback: TranthorCallback<T>?, block: suspend () -> T) {
        val accepted = operations.trySend {
            val result = runCatching { block() }
            deliver(callback, result.getOrNull(), result.exceptionOrNull())
        }
        if (accepted.isFailure) deliver(callback, null, TranthorException.Stopped())
    }

    /** A throwing host callback is logged (no payloads) and never stops the facade or crashes the app. */
    private fun <T> deliver(callback: TranthorCallback<T>?, value: T?, error: Throwable?) {
        if (callback == null) return
        try { callback.onComplete(value, error) } catch (thrown: Throwable) {
            sdk.logDebug("TranthorClient callback threw ${thrown.javaClass.simpleName}; ignored")
        }
    }

    public val anonymousId: String get() = sdk.anonymousId
    public val userId: String? get() = sdk.userId
    public val isOptedOut: Boolean get() = sdk.isOptedOut

    @JvmOverloads
    public fun identify(userId: String?, traits: Map<String, *>? = null, callback: TranthorCallback<Unit>? = null): Unit =
        submit(callback) { sdk.identify(userId = userId, traits = traits?.let(JsonValue::objectOf)) }

    @JvmOverloads
    public fun track(event: String, properties: Map<String, *>? = null, callback: TranthorCallback<Unit>? = null): Unit =
        submit(callback) { sdk.track(event = event, properties = properties?.let(JsonValue::objectOf)) }

    @JvmOverloads
    public fun page(name: String?, properties: Map<String, *>? = null, callback: TranthorCallback<Unit>? = null): Unit =
        submit(callback) { sdk.page(name = name, properties = properties?.let(JsonValue::objectOf)) }

    @JvmOverloads
    public fun screen(name: String?, properties: Map<String, *>? = null, callback: TranthorCallback<Unit>? = null): Unit =
        submit(callback) { sdk.screen(name = name, properties = properties?.let(JsonValue::objectOf)) }

    /**
     * Registers a flush after every earlier call has been admitted, then completes [callback] when
     * delivery settles. Waiting happens off the FIFO channel: later calls (track, optOut, shutdown)
     * are admitted immediately, exactly as with the suspending API.
     */
    @JvmOverloads
    public fun flush(callback: TranthorCallback<Unit>? = null) {
        val accepted = operations.trySend {
            val registration = runCatching { sdk.beginFlush() }
            registration.exceptionOrNull()?.let { deliver(callback, null, it); return@trySend }
            val waiter = registration.getOrNull()
            if (waiter == null) { deliver(callback, Unit, null); return@trySend }
            scope.launch {
                val result = runCatching { waiter.await() }
                deliver(callback, result.getOrNull(), result.exceptionOrNull())
            }
        }
        if (accepted.isFailure) deliver(callback, null, TranthorException.Stopped())
    }

    @JvmOverloads
    public fun reset(callback: TranthorCallback<Unit>? = null): Unit = submit(callback) { sdk.reset() }

    @JvmOverloads
    public fun optOut(callback: TranthorCallback<Unit>? = null): Unit = submit(callback) { sdk.optOut() }

    @JvmOverloads
    public fun optIn(callback: TranthorCallback<Unit>? = null): Unit = submit(callback) { sdk.optIn() }

    /** Shuts the SDK down after earlier calls are admitted; later calls fail with [TranthorException.Stopped]. */
    @JvmOverloads
    public fun shutdown(callback: TranthorCallback<Unit>? = null) {
        submit(callback) { sdk.shutdown() }
        operations.close()
    }

    public companion object {
        /** Configures asynchronously; [callback] receives the client or the configuration error. */
        @JvmStatic
        public fun configure(context: Context, configuration: Configuration, callback: TranthorCallback<TranthorClient>) {
            CoroutineScope(Dispatchers.Default).launch {
                val result = runCatching { TranthorClient(TranthorSDK.configure(context, configuration)) }
                try { callback.onComplete(result.getOrNull(), result.exceptionOrNull()) } catch (thrown: Throwable) {
                    result.getOrNull()?.sdk?.logDebug("TranthorClient configure callback threw ${thrown.javaClass.simpleName}; ignored")
                }
            }
        }
    }
}
