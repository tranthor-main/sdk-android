package com.tranthor.sdk

import java.io.File

/**
 * Immutable SDK settings. Numeric inputs accept any [Number]; missing, non-finite, fractional (where
 * an integer is required) or out-of-range values use the defaults below.
 *
 * @property writeKey complete Authorization header value, sent verbatim.
 * @property host origin, default `https://api.tranthor.com`; trailing slashes are removed and an
 * invalid HTTP(S) URL makes [TranthorSDK.configure] throw.
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
    )

    /** Never includes the write key. */
    override fun toString(): String =
        "Configuration(host=$host, batchSize=$batchSize, flushIntervalMs=$flushIntervalMs, retries=$retries, " +
            "baseDelay=$baseDelay, automaticContext=$automaticContext, storageNamespace=$storageNamespace, maxQueueSize=$maxQueueSize)"

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
        public fun build(): Configuration = Configuration(writeKey, host, batchSize, flushIntervalMs, retries, baseDelay,
            automaticContext, storageNamespace, storageDirectory, maxQueueSize, onError)
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

/** A delivery failure: [status] is null for network failures (timeouts, TLS, dropped connections). */
public class DeliveryFailure internal constructor(
    public val status: Int?,
    message: String,
    public val retryAfterMs: Double = 0.0,
) : Exception(message) {
    public val retryable: Boolean
        get() = status == null || status == 408 || status == 425 || status == 429 || status in 500..599

    override fun equals(other: Any?): Boolean =
        other is DeliveryFailure && other.status == status && other.message == message && other.retryAfterMs == retryAfterMs
    override fun hashCode(): Int = (status ?: -1) * 31 + (message?.hashCode() ?: 0)
    override fun toString(): String = "DeliveryFailure(status=$status, message=$message, retryAfterMs=$retryAfterMs)"
}

/** One terminally failed (or dropped, attempts = 0) batch. */
public class BatchFailure internal constructor(
    public val error: DeliveryFailure,
    public val events: List<Map<String, JsonValue>>,
    public val attempts: Int,
) {
    override fun toString(): String = "BatchFailure(error=$error, events=${events.size}, attempts=$attempts)"
}

/** Only ASCII RFC 9110 delay-seconds; huge values saturate at the 30 s policy cap. */
internal fun retryAfter(value: String?): Double {
    if (value.isNullOrEmpty() || !value.all { it in '0'..'9' }) return 0.0
    var seconds = 0.0
    for (digit in value) seconds = minOf(30.0, seconds * 10 + (digit - '0'))
    return seconds * 1000
}

internal fun retryDelay(base: Double, attempt: Int, requested: Double): Double =
    minOf(30_000.0, maxOf(if (base == 0.0) 0.0 else base * Math.pow(2.0, attempt.toDouble()), requested))
