package com.tranthor.sdk

/**
 * Typed SDK errors. None of them is a [kotlinx.coroutines.CancellationException], so a failed or
 * stopped flush is never silently treated as coroutine cancellation.
 */
public sealed class TranthorException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Another live instance in this process owns the same storage directory and namespace. */
    public class NamespaceInUse : TranthorException("The SDK storage namespace is already in use.")

    /** Local store I/O failed; retry configuration later. [cause] preserves the underlying diagnostics. */
    public class StoreTemporarilyUnavailable(cause: Throwable) :
        TranthorException("The SDK store is temporarily unavailable. Retry configuration when storage is available.", cause)

    /** The host is not an absolute HTTP(S) origin. */
    public class InvalidHost : TranthorException("Invalid host")

    /** The payload contained a value that cannot be serialized as JSON (for example NaN). */
    public class InvalidPayload(message: String) : TranthorException(message)

    /** The flush was cancelled: the delivery cycle stopped (opt-out, shutdown, background pause) before settling. */
    public class FlushCancelled : TranthorException("The delivery cycle was cancelled before it settled.")

    /** The instance was shut down. */
    public class Stopped : TranthorException("The SDK instance has been shut down.")
}
