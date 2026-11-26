package com.tranthor.sdk

/** The two timings of the identity-token rules; a hold for a missing token lasts the provider backoff. The defaults are the contract; tests and conformance scale them. */
internal class IdentityTimings(
    /** How long a batch waits for the provider. */
    val providerTimeoutMs: Long = 20_000,
    /** After a provider failure (error, timeout or no token) the provider is not called again for this long. */
    val providerBackoffMs: Long = 60_000,
)

/** A token with less than this left is renewed through the provider before it is sent. */
internal const val RENEW_BELOW_MS = 60_000L

/** The server considers at most this many identity tokens per request, so a batch holds at most this many users. */
internal const val MAX_USERS_PER_REQUEST = 10

/** While a batch is held, the queue keeps at most this many events; the oldest are dropped and reported. */
internal const val HELD_QUEUE_LIMIT = 1000

internal const val IDENTITY_NOT_VERIFIED = "identity_not_verified"
internal val IDENTITY_UNAVAILABLE_CODES = setOf("identity_settings_unavailable", "identity_secrets_unavailable")
internal const val UNAVAILABLE_HOLD_DEFAULT_MS = 30_000L
internal const val UNAVAILABLE_HOLD_MIN_MS = 1_000L
/** A 503 hold honours the server's Retry-After up to this long. */
internal const val UNAVAILABLE_HOLD_MAX_MS = 300_000L
/** The server accepts a token until `exp` plus this long, so a cached token is attached that long past its `exp`. */
internal const val TOKEN_ACCEPTED_AFTER_EXPIRY_MS = 120_000L

/** What one ingestion request carries: the events, and one identity token per user in them that has one. */
internal class BatchRequest(val batch: List<QueueItem>, val identityTokens: List<String> = emptyList())

/** The user id of an event, or null for an anonymous one. An empty id is not a user. */
internal fun userIdOf(item: QueueItem): String? = item.value["userId"]?.stringValue?.takeIf { it.isNotEmpty() }

/** The distinct users of [batch], in the order they first appear. */
internal fun distinctUsers(batch: List<QueueItem>): List<String> {
    val users = LinkedHashSet<String>()
    for (item in batch) userIdOf(item)?.let(users::add)
    return users.toList()
}

/**
 * How many of the queued items the next batch takes: the longest prefix that holds at most [size] items and
 * at most [MAX_USERS_PER_REQUEST] distinct users, and at least one.
 */
internal fun takeCount(queue: List<QueueItem>, size: Int): Int {
    val users = HashSet<String>()
    var count = 0
    for (item in queue) {
        if (count >= size) break
        val user = userIdOf(item)
        if (user != null && user !in users) {
            if (users.size >= MAX_USERS_PER_REQUEST) break
            users.add(user)
        }
        count++
    }
    return maxOf(1, count)
}

// 9999-12-31T23:59:59Z in seconds; anything beyond is not a real expiry.
private const val MAX_EXP_SECONDS = 253_402_300_799.0

/**
 * Reads the `exp` claim (seconds since the epoch) of a JWT WITHOUT verifying the token. Use it to schedule a
 * renewal and for nothing else: it proves nothing about the token. Null unless the token has three segments and
 * a JSON object payload with a finite numeric `exp`. Never throws and never logs the token.
 */
internal fun decodeJwtExp(token: String): Long? {
    val segments = token.split('.')
    if (segments.size != 3 || segments[1].isEmpty()) return null
    val json = base64UrlDecode(segments[1]) ?: return null
    val payload = try { JsonValue.parse(String(json, Charsets.UTF_8)).objectValue } catch (_: Exception) { null } ?: return null
    val exp = payload["exp"]?.numberValue ?: return null
    if (!exp.isFinite() || exp < 0 || exp > MAX_EXP_SECONDS) return null
    return Math.floor(exp).toLong()
}

/** java.util.Base64 is API 26 and minSdk is 23. Unpadded base64url only; null for any other character or length. */
private fun base64UrlDecode(segment: String): ByteArray? {
    if (segment.length % 4 == 1) return null
    val out = java.io.ByteArrayOutputStream(segment.length * 3 / 4)
    var bits = 0
    var value = 0
    for (char in segment) {
        val digit = when (char) {
            in 'A'..'Z' -> char - 'A'
            in 'a'..'z' -> char - 'a' + 26
            in '0'..'9' -> char - '0' + 52
            '-' -> 62
            '_' -> 63
            else -> return null
        }
        value = (value shl 6) or digit
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.write((value shr bits) and 0xff)
            value = value and ((1 shl bits) - 1)
        }
    }
    return out.toByteArray()
}

/**
 * The tokens of the users this install knows, keyed by user id, in the order they were stored. A token is only
 * ever returned for its own user. It is persisted with the queue (see [StoredState]) and never shown: neither
 * [toString] nor any log line includes a token.
 */
internal class TokenCache {
    private class Entry(val token: String, val expiresAtMs: Long?)

    private val entries = LinkedHashMap<String, Entry>()

    val users: List<String> get() = entries.keys.toList()
    fun isEmpty(): Boolean = entries.isEmpty()

    /** Stores [token] for [userId]. An empty token or user is ignored. */
    fun set(userId: String, token: String) {
        if (userId.isEmpty() || token.isEmpty()) return
        // A token whose exp cannot be read is kept and attached until the server refuses it.
        entries[userId] = Entry(token, decodeJwtExp(token)?.let { it * 1000 })
    }

    /**
     * The user's token while it is valid for at least [minValidityMs] more. A token whose exp cannot be read
     * counts as valid: it is attached, and the server's answer decides.
     */
    fun getFresh(userId: String, nowMs: Long, minValidityMs: Long): String? {
        val entry = entries[userId] ?: return null
        val expires = entry.expiresAtMs
        if (expires != null && expires - nowMs < minValidityMs) return null
        return entry.token
    }

    /** The user's token whatever its age, for the save and for restoring after a failed save. */
    fun get(userId: String): String? = entries[userId]?.token

    /** Removes every token whose `exp` is before [cutoffMs]; a token whose exp cannot be read stays. True when any was removed. */
    fun evictExpired(cutoffMs: Long): Boolean =
        entries.values.removeAll { it.expiresAtMs != null && it.expiresAtMs < cutoffMs }

    fun copy(): TokenCache = TokenCache().also { it.entries.putAll(entries) }

    fun delete(userId: String): Boolean = entries.remove(userId) != null

    /** Removes the token of every user that is not in [keep]; true when any was removed. */
    fun retain(keep: Set<String>): Boolean = entries.keys.retainAll(keep)

    override fun toString(): String = "TokenCache(users=${entries.size}, tokens=<redacted>)"
}

/** Removes every token whose user is not [current], [extra], or in an event still waiting; true when any was removed. */
internal fun StoredState.pruneTokens(queue: EventQueue, nowMs: Long, current: String?, vararg extra: String?): Boolean {
    if (tokens.isEmpty()) return false
    // A token the server would refuse anyway (past exp + 2 minutes) is no use to anyone: evict it first.
    val expired = tokens.evictExpired(nowMs - TOKEN_ACCEPTED_AFTER_EXPIRY_MS)
    val keep = HashSet<String>()
    current?.let(keep::add)
    for (user in extra) user?.let(keep::add)
    for (item in queue.queuedItems) userIdOf(item)?.let(keep::add)
    for (item in queue.activeItems) userIdOf(item)?.let(keep::add)
    for (item in pendingRemovals) userIdOf(item)?.let(keep::add)
    return tokens.retain(keep) || expired
}
