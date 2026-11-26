package com.tranthor.sdk

import java.io.File

/**
 * Immutable SDK settings. Numeric inputs accept any [Number]; missing, non-finite, fractional (where
 * an integer is required) or out-of-range values use the defaults below.
 *
 * @property writeKey complete Authorization header value, sent verbatim.
 * @property host origin, default `https://api.tranthor.com`; trailing slashes are removed and an
 * invalid HTTP(S) URL makes [TranthorSDK.configure] throw.
 * @property identityTokenProvider supplies and renews identity tokens, see [TranthorSDK.identify]. It receives a
 * user id and returns that user's token, or null when there is none. It is called off the SDK's own executor and
 * the SDK waits at most 20 seconds for it, so a slow or failing provider delays a send by at most 20 seconds and never keeps events back.
 * @property onNotice receives diagnostics that are not delivery failures, see [Notice]. It runs on the SDK's serial
 * executor: return promptly.
 * @property appId sent with every request. Null (the default) sends the application's
 * package name. A value that cannot be an HTTP header value (empty, spaces at either end, a newline or any character
 * outside printable ASCII) makes [TranthorSDK.configure] throw [TranthorException.InvalidAppId]; the SDK never sends
 * an id you did not choose.
 */
public class Configuration private constructor(
    public val writeKey: String,
    public val host: String,
    public val batchSize: Int,
    public val flushIntervalMs: Int,
    public val retries: Int,
    public val baseDelay: Double,
    public val automaticContext: Boolean,
    public val storageNamespace: String,
    public val storageDirectory: File?,
    public val maxQueueSize: Int,
    public val onError: ((BatchFailure) -> Unit)?,
    public val identityTokenProvider: (suspend (String) -> String?)?,
    public val appId: String?,
    public val onNotice: ((Notice) -> Unit)?,
) {
    @JvmOverloads
    public constructor(
        writeKey: String,
        host: String = DEFAULT_HOST,
        batchSize: Number? = null,
        flushIntervalMs: Number? = null,
        retries: Number? = null,
        baseDelay: Number? = null,
        automaticContext: Boolean = true,
        storageNamespace: String = "default",
        storageDirectory: File? = null,
        maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
        onError: ((BatchFailure) -> Unit)? = null,
        identityTokenProvider: (suspend (String) -> String?)? = null,
        appId: String? = null,
        onNotice: ((Notice) -> Unit)? = null,
    ) : this(
        writeKey = writeKey,
        host = host,
        batchSize = positiveInteger(batchSize, 5),
        flushIntervalMs = positiveInteger(flushIntervalMs, 500),
        retries = nonnegativeInteger(retries, 5),
        baseDelay = baseDelay?.toDouble()?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 500.0,
        automaticContext = automaticContext,
        storageNamespace = storageNamespace,
        storageDirectory = storageDirectory,
        maxQueueSize = if (maxQueueSize > 0) maxQueueSize else DEFAULT_MAX_QUEUE_SIZE,
        onError = onError,
        identityTokenProvider = identityTokenProvider,
        appId = appId,
        onNotice = onNotice,
    )

    /** Never includes the write key. */
    override fun toString(): String =
        "Configuration(host=$host, batchSize=$batchSize, flushIntervalMs=$flushIntervalMs, retries=$retries, " +
            "baseDelay=$baseDelay, automaticContext=$automaticContext, storageNamespace=$storageNamespace, maxQueueSize=$maxQueueSize, " +
            "appId=$appId, identityTokenProvider=${if (identityTokenProvider == null) "none" else "set"})"

    /** Java-friendly builder; every setter is optional except the write key. */
    public class Builder(private val writeKey: String) {
        private var host = DEFAULT_HOST
        private var batchSize: Number? = null
        private var flushIntervalMs: Number? = null
        private var retries: Number? = null
        private var baseDelay: Number? = null
        private var automaticContext = true
        private var storageNamespace = "default"
        private var storageDirectory: File? = null
        private var maxQueueSize = DEFAULT_MAX_QUEUE_SIZE
        private var onError: ((BatchFailure) -> Unit)? = null
        private var identityTokenProvider: (suspend (String) -> String?)? = null
        private var appId: String? = null
        private var onNotice: ((Notice) -> Unit)? = null
        public fun host(value: String): Builder = apply { host = value }
        public fun batchSize(value: Number?): Builder = apply { batchSize = value }
        public fun flushIntervalMs(value: Number?): Builder = apply { flushIntervalMs = value }
        public fun retries(value: Number?): Builder = apply { retries = value }
        public fun baseDelay(value: Number?): Builder = apply { baseDelay = value }
        public fun automaticContext(value: Boolean): Builder = apply { automaticContext = value }
        public fun storageNamespace(value: String): Builder = apply { storageNamespace = value }
        public fun storageDirectory(value: File?): Builder = apply { storageDirectory = value }
        public fun maxQueueSize(value: Int): Builder = apply { maxQueueSize = value }
        public fun onError(value: BatchFailureListener?): Builder = apply { onError = value?.let { listener -> { listener.onFailure(it) } } }
        /** Kotlin hosts: a suspend function from a user id to that user's identity token, or null. */
        public fun identityTokenProvider(value: (suspend (String) -> String?)?): Builder = apply { identityTokenProvider = value }
        /** Java hosts: a blocking supplier of the same thing. It runs on a background thread. */
        public fun identityTokenSupplier(value: IdentityTokenSupplier?): Builder =
            apply { identityTokenProvider = value?.let { supplier -> { userId -> supplier.tokenFor(userId) } } }
        /** Sent with every request; null sends the application's package name. */
        public fun appId(value: String?): Builder = apply { appId = value }
        /** Receives diagnostics that are not delivery failures (see [Notice]); runs on the SDK's serial executor. */
        public fun onNotice(value: NoticeListener?): Builder = apply { onNotice = value?.let { listener -> { listener.onNotice(it) } } }
        public fun build(): Configuration = Configuration(writeKey, host, batchSize, flushIntervalMs, retries, baseDelay,
            automaticContext, storageNamespace, storageDirectory, maxQueueSize, onError, identityTokenProvider, appId, onNotice)
    }

    public companion object {
        public const val DEFAULT_HOST: String = "https://api.tranthor.com"
        public const val DEFAULT_MAX_QUEUE_SIZE: Int = 10_000

        private fun positiveInteger(value: Number?, fallback: Int): Int {
            val v = value?.toDouble() ?: return fallback
            if (!v.isFinite() || v <= 0 || Math.floor(v) != v || v >= Int.MAX_VALUE.toDouble()) return fallback
            return v.toInt()
        }
        private fun nonnegativeInteger(value: Number?, fallback: Int): Int {
            val v = value?.toDouble() ?: return fallback
            if (!v.isFinite() || v >= Int.MAX_VALUE.toDouble()) return fallback
            return Math.floor(v).coerceAtLeast(0.0).toInt()
        }
    }
}

/** Java-friendly error callback. Runs on the SDK's serial executor; return promptly. */
public fun interface BatchFailureListener {
    public fun onFailure(failure: BatchFailure)
}

/** Java-friendly notice callback. Runs on the SDK's serial executor; return promptly. */
public fun interface NoticeListener {
    public fun onNotice(notice: Notice)
}

/** What a [Notice] is about. */
public enum class NoticeKind {
    /** The server accepted the request but dropped events: anonymous or unattributed ones when identity is required, and oversized ones in every mode. */
    EVENTS_DROPPED,
    /** The server accepted events it could not verify; with identity Required it would have refused them (identity Monitor). */
    EVENTS_UNVERIFIED,
    /** The server refused an identity token the SDK could not fix by renewing, or refused events with no user to renew a token for; delivery is held or retried. */
    IDENTITY_REFUSED,
    /** The server could not read its identity settings; the batch is held, not dropped. */
    IDENTITY_UNAVAILABLE,
}

/**
 * A diagnostic that is not a delivery failure: nothing was dropped by the SDK, but the app should know. It never
 * contains an identity token, a push token or a user id. [count] is set for the two `EVENTS_` kinds, [reason] is the
 * server's reason for an [NoticeKind.IDENTITY_REFUSED] (`missing`, `signature`, `expired`, ...; an open list).
 */
public class Notice internal constructor(
    /** What the notice is about. */
    public val kind: NoticeKind,
    /** A sentence for logs and developers; it names no token, push token or user id. */
    public val message: String,
    public val count: Int? = null,
    public val reason: String? = null,
) {
    override fun toString(): String = "Notice(kind=$kind, count=$count, reason=$reason, message=$message)"
}

/**
 * An id the SDK can send as the `x-tranthor-app-id` header: non-empty, printable ASCII, no space at either end.
 */
internal fun isValidAppId(value: String): Boolean = value.isNotEmpty() && value == value.trim() && isPrintableAscii(value)

internal fun isPrintableAscii(value: String): Boolean = value.all { it in ' '..'~' }

/**
 * Java-friendly identity token provider: blocks on a background thread and returns the user's token, or null.
 * The SDK waits at most 20 seconds and then abandons the call, but it cannot interrupt a blocked thread: no second
 * call starts while an abandoned one is still running. Make the supplier cancellable (give your HTTP call a timeout
 * well under 20 seconds) so it returns promptly.
 */
public fun interface IdentityTokenSupplier {
    @Throws(Exception::class)
    public fun tokenFor(userId: String): String?
}

/**
 * A delivery failure: [status] is null for network failures (timeouts, TLS, dropped connections). [code] is the
 * server's machine-readable error code for certain server error responses (for example when a write key is limited to other apps), or null.
 */
public class DeliveryFailure internal constructor(
    public val status: Int?,
    message: String,
    public val retryAfterMs: Double = 0.0,
    public val code: String? = null,
    /** The users the server named in an `identity_not_verified` answer; at most 50. */
    internal val userIds: List<String> = emptyList(),
    /** The server's reason for refusing an identity token (`missing`, `signature`, `expired`, ...). An open list. */
    internal val reason: String? = null,
    /** Whether the response carried a usable Retry-After, which a hold needs to tell "0" from absent. */
    internal val retryAfterGiven: Boolean = false,
    /** The Retry-After in ms capped at 5 minutes rather than 30 s, for a hold. */
    internal val heldAfterMs: Double = 0.0,
) : Exception(message) {
    public val retryable: Boolean
        get() = status == null || status == 408 || status == 425 || status == 429 || status in 500..599

    override fun equals(other: Any?): Boolean =
        other is DeliveryFailure && other.status == status && other.message == message && other.retryAfterMs == retryAfterMs &&
            other.code == code
    override fun hashCode(): Int = (status ?: -1) * 31 + (message?.hashCode() ?: 0)
    override fun toString(): String = "DeliveryFailure(status=$status, message=$message, retryAfterMs=$retryAfterMs, code=$code)"
}

/** One terminally failed (or dropped, attempts = 0) batch. */
public class BatchFailure internal constructor(
    public val error: DeliveryFailure,
    public val events: List<Map<String, JsonValue>>,
    public val attempts: Int,
) {
    override fun toString(): String = "BatchFailure(error=$error, events=${events.size}, attempts=$attempts)"
}

/** Only ASCII RFC 9110 delay-seconds; huge values saturate at the 30 s policy cap (a hold's cap is 5 minutes). */
internal fun retryAfter(value: String?, capSeconds: Double = 30.0): Double {
    if (value.isNullOrEmpty() || !value.all { it in '0'..'9' }) return 0.0
    var seconds = 0.0
    for (digit in value) seconds = minOf(capSeconds, seconds * 10 + (digit - '0'))
    return seconds * 1000
}

internal fun retryDelay(base: Double, attempt: Int, requested: Double): Double =
    minOf(30_000.0, maxOf(if (base == 0.0) 0.0 else base * Math.pow(2.0, attempt.toDouble()), requested))
