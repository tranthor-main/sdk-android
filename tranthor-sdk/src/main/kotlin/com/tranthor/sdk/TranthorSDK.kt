package com.tranthor.sdk

import android.content.Context
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Tranthor events client. Every state transition runs on one private serial executor: calls
 * are admitted in order, persisted atomically before they return, and delivered by a single
 * background cycle with at most one request in flight.
 *
 * Event methods suspend only for admission and the durable write, never for network delivery.
 * Keep the instance for the application's lifetime and call [shutdown] when replacing it.
 */
public class TranthorSDK private constructor(
    private val configuration: Configuration,
    private val transport: SdkTransport,
    private val sleep: suspend (Double) -> Unit,
    private val lease: NamespaceLease,
    private val store: JsonStore,
    private var state: StoredState,
    private val context: Map<String, JsonValue>,
    private val platform: Platform,
    private val totalRequestTimeoutMs: Long,
    private val timings: IdentityTimings,
) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher +
        CoroutineExceptionHandler { _, error -> platform.logDebug("Unexpected SDK task failure: ${error.javaClass.simpleName}") })
    private var timer: Job? = null
    private var cycle: Job? = null
    private var stopped = false
    private var shutdownTask: Deferred<Unit>? = null
    private var paused = false
    private var timerGeneration = 0L
    private var backoff: Job? = null
    private var backgroundToken: UUID? = null
    private var lifecycleToken: UUID? = null
    private var lifecycleSequence = Long.MIN_VALUE
    /** The last admitted lifecycle event: only a FOREGROUND after STOP is a return to the foreground. */
    private var lastLifecycleEvent: LifecycleEvent? = null
    private val dropped = ArrayList<Map<String, JsonValue>>()
    private var dropNotification: Job? = null
    private var lifecycleDisposer: (suspend () -> Unit)? = null
    private class Waiter(val deferred: CompletableDeferred<Unit>) { var failure: DeliveryFailure? = null }
    private val waiters = ArrayList<Waiter>()
    private var sentInstallation: List<String>? = null
    private var installationClockMs = Long.MIN_VALUE
    /**
     * The retryable failure that exhausted the opt-out removals' attempt cycle, or null. While set, nothing
     * is sent and no timer runs; only app start, foreground, optIn() and optOut() start one new cycle.
     */
    private var removalParked: DeliveryFailure? = null
    /** Counts triggers, so one that arrives while a cycle runs still gets a cycle of its own after it. */
    private var removalTriggers = 0L
    private val exhaustionPause = ExhaustionPause(scope, sleep)

    /**
     * The wait after an attempt cycle ended with the head batch kept after a retryable failure: 30 s, doubling
     * with each consecutive such cycle up to 5 min, on the injected sleep. The count and the running pause live
     * together, so ending the pause and resetting the count cannot be done half.
     */
    private class ExhaustionPause(private val scope: CoroutineScope, private val sleep: suspend (Double) -> Unit) {
        private var exhaustedCycles = 0
        private var job: Job? = null
        private var generation = 0L

        val isRunning: Boolean get() = job != null

        /** Starts the pause for the next consecutive exhausted cycle; [onElapsed] runs when it ends by itself. */
        fun start(onElapsed: () -> Unit) {
            val milliseconds = minOf(MAX_PAUSE_MS, BASE_PAUSE_MS shl exhaustedCycles)
            exhaustedCycles = minOf(exhaustedCycles + 1, MAX_DOUBLINGS)
            val mine = ++generation
            job = scope.launch {
                try { sleep(milliseconds.toDouble()) } catch (_: CancellationException) { return@launch }
                if (mine != generation) return@launch
                job = null
                onElapsed()
            }
        }

        /** Ends the running pause, if any, and keeps the count: an attempt that follows may exhaust again. */
        fun end() {
            generation++
            job?.cancel(); job = null
        }

        /** Ends the running pause, if any, and starts counting again from 30 s. */
        fun reset() {
            end()
            exhaustedCycles = 0
        }

        private companion object {
            const val BASE_PAUSE_MS = 30_000
            const val MAX_PAUSE_MS = 300_000
            /** 30 s doubled this many times is past the maximum. */
            const val MAX_DOUBLINGS = 4
        }
    }
    /** True while a batch is held for lack of an identity token. Not persisted: a restart starts without a hold. */
    private var held = false
    private var holdTimer: Job? = null
    private var currentHold: Hold? = null
    /** Retries a parked opt-out removal on the hold timer. */
    private var removalTimer: Job? = null
    /** The provider call that is running, if any: a blocking one cannot be interrupted, so no second call starts meanwhile. */
    private var providerWorker: Job? = null
    /** The first user seen on an event before any identify; the current user is the stored one, else this one. */
    private var notedUser: String? = null
    private var providerBackoffUntilMs = 0L
    /** How many requests each queued event has been in, for the attempts reported when it is dropped. */
    private val sendCounts = java.util.IdentityHashMap<QueueItem, Int>()
    /** Runs the host's identity provider off the serial executor, so a blocking provider cannot stall the SDK. */
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, _ -> platform.logDebug("Identity provider task failed") })

    private class Snapshot(val anonymousId: String, val userId: String?, val optedOut: Boolean)
    @Volatile private var snapshot = Snapshot(state.anonymousId, state.userId, state.optedOut)

    /** The persisted anonymous UUID (regenerated by [reset]). */
    public val anonymousId: String get() = snapshot.anonymousId
    /** The persisted user ID set by [identify], or null. */
    public val userId: String? get() = snapshot.userId
    /** Persisted consent. */
    public val isOptedOut: Boolean get() = snapshot.optedOut

    /** The user whose events the app is producing now: the stored user of the last identify, else the first user seen. */
    private fun currentUser(): String? = state.userId?.takeIf { it.isNotEmpty() } ?: notedUser

    private fun noteUser(userId: String?) {
        if (!userId.isNullOrEmpty() && currentUser() == null) notedUser = userId
    }

    /** A token is kept while its user is current or still has events waiting, and not a moment longer. */
    private fun pruneTokens(vararg extra: String?): Boolean =
        // Opted out, nobody is current: only an undelivered removal keeps its user's token.
        state.pruneTokens(store.queue, platform.currentTimeMs(), currentUser()?.takeIf { !state.optedOut }, *extra)

    /**
     * Identity is in use when a token provider is configured or an identity token has ever been passed to
     * [identify] on this install. It does not depend on the cache being non-empty now: a refusal deletes the token.
     */
    private fun identityInUse(): Boolean = configuration.identityTokenProvider != null || state.identityTokenSupplied

    /** Undoes [supersedeRemovals] after a failed write: the removal is back and still parked, with its retry timer running. */
    private fun restoreParkedRemovals(removals: List<QueueItem>, parked: DeliveryFailure?) {
        state.pendingRemovals = ArrayList(removals)
        if (parked != null && removals.isNotEmpty()) {
            removalParked = parked
            startRemovalRetry(timings.providerBackoffMs)
        }
    }

    /** A registration of this install's token under [user] supersedes a removal of it for the same user that an identity refusal parked. */
    private fun supersedeRemovals(user: String, installation: StoredInstallation): Boolean {
        // Only a removal parked by an identity refusal is superseded: it could otherwise keep the registration behind it
        // for good. Any other undelivered removal still goes first, so the server sees them in timestamp order.
        if (removalParked?.code != IDENTITY_NOT_VERIFIED) return false
        val target = installation.appId to installation.token
        val removed = state.pendingRemovals.removeAll { it.value["userId"]?.stringValue == user && installationTarget(it) == target }
        if (removed && state.pendingRemovals.isEmpty()) { removalParked = null; removalTimer?.cancel(); removalTimer = null }
        return removed
    }

    private fun publish() { snapshot = Snapshot(state.anonymousId, state.userId, state.optedOut) }

    internal suspend fun deliveryCycleActive(): Boolean = withContext(dispatcher) { cycle != null }
    internal suspend fun pendingTimer(): Job? = withContext(dispatcher) { timer }
    internal suspend fun flushWaiterCount(): Int = withContext(dispatcher) { waiters.size }
    internal suspend fun deliveryPaused(): Boolean = withContext(dispatcher) { paused }
    internal suspend fun isStopped(): Boolean = withContext(dispatcher) { stopped }

    /**
     * Records traits and persists an explicit user ID. With a non-empty [userId] the event also carries
     * the stored anonymous ID (linking earlier anonymous events to the user), unless [anonymousId] is
     * given: that value is sent instead and is not stored. When a different non-empty user ID is already
     * stored, the anonymous ID is first replaced by a new UUID, so the next user on the device does not
     * inherit the previous user's anonymous history. Both IDs are persisted together. An empty [userId]
     * is treated like null: it is not stored, sent, linked or used to rotate.
     *
     * [identityToken] is the signed-in user's identity token, signed by the app's own backend (see
     * [Configuration.identityTokenProvider]). It is cached for [userId], persisted with the queue and sent beside
     * the events, never inside one; it is ignored without a non-empty [userId]. A token is never logged.
     */
    public suspend fun identify(
        userId: String? = null,
        anonymousId: String? = null,
        traits: Map<String, JsonValue>? = null,
        messageId: String? = null,
        timestamp: String? = null,
        context: Map<String, JsonValue>? = null,
        identityToken: String? = null,
    ): Unit = withContext(dispatcher) {
        if (state.optedOut || stopped) return@withContext
        val previousUserId = state.userId
        val previousAnonymousId = state.anonymousId
        // "" is not a user: storing it would hide the previous user from the rotation check below.
        val user = userId?.takeIf { it.isNotEmpty() }
        // Saved together with the new user ID (and the event) by the single state write in enqueue().
        if (user != null && !previousUserId.isNullOrEmpty() && previousUserId != user) state.anonymousId = newAnonymousId()
        if (user != null) state.userId = user
        val data = payload(user, anonymousId ?: state.anonymousId.takeIf { user != null }, messageId, timestamp, context)
        traits?.let { data["traits"] = JsonValue.ObjectValue(it) }
        data["type"] = JsonValue.StringValue("identify")
        // A signed-in customer's push Installation follows the identity; the server moves the token to the new
        // owner. An identify that signs nobody in (traits only, or an empty user id) leaves a held token held.
        val installation = state.installation?.takeIf { state.userId != null }
        val fingerprint = installation?.let { installationFingerprint(state, platform, it) }
        val registration = if (installation != null && fingerprint != sentInstallation) {
            installationRegistration(state, this@TranthorSDK.context, platform, nextInstallationTime(), installation)
        } else null
        val tokensBefore = state.tokens.copy()
        val suppliedBefore = state.identityTokenSupplied
        val removalsBefore = state.pendingRemovals.toList()
        val parkedBefore = removalParked
        var tokenSet = false
        if (user != null && !identityToken.isNullOrEmpty()) {
            state.tokens.set(user, identityToken)
            // Identity is in use from now on, and stays so even when a refusal deletes this token.
            state.identityTokenSupplied = true
            tokenSet = true
        }
        // Signing the same user in again registers the token, which supersedes a parked removal of it.
        val superseded = registration != null && user != null && installation != null && supersedeRemovals(user, installation)
        val pruned = pruneTokens()
        // A fresh token, or a different user, ends a hold at once: the batch is tried again with what is new.
        val holdBefore = currentHold
        val endedHold = held && (tokenSet || (user != null && user != previousUserId))
        if (endedHold) endHold()
        try {
            // The state file is written with the identity, the token cache or the parked removals when any changed.
            admit(listOfNotNull(data, registration),
                saveState = state.userId != previousUserId || state.anonymousId != previousAnonymousId || tokenSet || pruned || superseded)
            if (registration != null) sentInstallation = fingerprint
            if (endedHold) beginCycle()
        } catch (error: Throwable) {
            state.userId = previousUserId
            state.anonymousId = previousAnonymousId
            state.tokens = tokensBefore
            state.identityTokenSupplied = suppliedBefore
            restoreParkedRemovals(removalsBefore, parkedBefore)
            // The hold it ended is back: nothing about the batch changed.
            if (endedHold && holdBefore != null) startHold(holdBefore)
            throw error
        } finally {
            publish()
        }
    }

    /** The 0.2.x signature, kept so code compiled against it still links. */
    @Deprecated("Binary compatibility with 0.2.x only; use the overload with identityToken.", level = DeprecationLevel.HIDDEN)
    public suspend fun identify(
        userId: String? = null,
        anonymousId: String? = null,
        traits: Map<String, JsonValue>? = null,
        messageId: String? = null,
        timestamp: String? = null,
        context: Map<String, JsonValue>? = null,
    ): Unit = identify(userId, anonymousId, traits, messageId, timestamp, context, null)

    /** Records an event; [files] are passed through verbatim. */
    public suspend fun track(
        event: String,
        userId: String? = null,
        anonymousId: String? = null,
        properties: Map<String, JsonValue>? = null,
        files: List<JsonValue>? = null,
        messageId: String? = null,
        timestamp: String? = null,
        context: Map<String, JsonValue>? = null,
    ): Unit = withContext(dispatcher) {
        val data = payload(userId, anonymousId, messageId, timestamp, context)
        data["event"] = JsonValue.StringValue(event)
        properties?.let { data["properties"] = JsonValue.ObjectValue(it) }
        files?.let { data["files"] = JsonValue.ArrayValue(it) }
        enqueue("track", data)
    }

    /** Records an explicitly named page view. */
    public suspend fun page(
        name: String? = null,
        userId: String? = null,
        anonymousId: String? = null,
        properties: Map<String, JsonValue>? = null,
        messageId: String? = null,
        timestamp: String? = null,
        context: Map<String, JsonValue>? = null,
    ): Unit = named("page", name, userId, anonymousId, properties, messageId, timestamp, context)

    /** Records an explicitly named screen view (no automatic screen tracking). */
    public suspend fun screen(
        name: String? = null,
        userId: String? = null,
        anonymousId: String? = null,
        properties: Map<String, JsonValue>? = null,
        messageId: String? = null,
        timestamp: String? = null,
        context: Map<String, JsonValue>? = null,
    ): Unit = named("screen", name, userId, anonymousId, properties, messageId, timestamp, context)

    private suspend fun named(
        type: String, name: String?, userId: String?, anonymousId: String?, properties: Map<String, JsonValue>?,
        messageId: String?, time: String?, context: Map<String, JsonValue>?,
    ): Unit = withContext(dispatcher) {
        val data = payload(userId, anonymousId, messageId, time, context)
        name?.let { data["name"] = JsonValue.StringValue(it) }
        properties?.let { data["properties"] = JsonValue.ObjectValue(it) }
        enqueue(type, data)
    }

    private fun payload(
        userId: String?, anonymousId: String?, messageId: String?, time: String?, caller: Map<String, JsonValue>?,
    ): LinkedHashMap<String, JsonValue> {
        val data = LinkedHashMap<String, JsonValue>()
        data["messageId"] = JsonValue.StringValue(messageId ?: UUID.randomUUID().toString())
        data["timestamp"] = JsonValue.StringValue(time ?: timestamp())
        userId?.let { data["userId"] = JsonValue.StringValue(it); noteUser(it) }
        anonymousId?.let { data["anonymousId"] = JsonValue.StringValue(it) }
        if (userId == null && anonymousId == null) {
            val stored = state.userId
            if (stored != null) data["userId"] = JsonValue.StringValue(stored)
            else data["anonymousId"] = JsonValue.StringValue(state.anonymousId)
        }
        // Caller top-level keys replace automatic keys wholesale (including nested objects).
        val merged = LinkedHashMap(context)
        caller?.let { merged.putAll(it) }
        if (caller != null || merged.isNotEmpty()) data["context"] = JsonValue.ObjectValue(merged)
        return data
    }

    private fun enqueue(type: String, payload: LinkedHashMap<String, JsonValue>) {
        payload["type"] = JsonValue.StringValue(type)
        admit(listOf(payload))
    }

    /**
     * Admits typed payloads as event files: all of them, or none. With [saveState] the state file is written
     * after them, so a crash in between loses a stored identity change but never an event the server was told of.
     */
    private fun admit(payloads: List<LinkedHashMap<String, JsonValue>>, saveState: Boolean = false) {
        if (state.optedOut || stopped) return
        val items = payloads.map { payload ->
            // Map.putIfAbsent is API 24; minSdk is 23.
            if ("type" !in payload) payload["type"] = JsonValue.StringValue("track")
            // Reject payloads that could not be re-read after a restart (nesting deeper than the state
            // reader accepts) and non-finite numbers before they can poison the queue.
            if (JsonCodec.exceedsDepth(JsonValue.ObjectValue(payload), JsonCodec.MAX_ADMISSION_DEPTH)) {
                notify(BatchFailure(DeliveryFailure(null, "Invalid JSON payload"), listOf(payload), 0))
                throw TranthorException.InvalidPayload("Invalid JSON payload: nesting deeper than ${JsonCodec.MAX_ADMISSION_DEPTH} levels")
            }
            try {
                QueueItem.of(payload)
            } catch (error: IllegalArgumentException) {
                notify(BatchFailure(DeliveryFailure(null, "Invalid JSON payload"), listOf(payload), 0))
                throw TranthorException.InvalidPayload("Invalid JSON payload: ${error.message}")
            }
        }
        try {
            store.queue.append(items)
        } catch (error: Exception) {
            throw storeError(error)
        }
        if (saveState) {
            try {
                store.save(state)
            } catch (error: Exception) {
                store.queue.rollback(items)
                throw storeError(error)
            }
        }
        // While a batch is held the queue stays small: the oldest events go, and are reported, beyond the limit.
        val removed = store.queue.trim(maxOf(0, configuration.maxQueueSize - store.queue.activeCount).let { if (held) minOf(it, HELD_QUEUE_LIMIT) else it })
        reportDropped(removed.map { it.value })
        if (store.queue.queuedCount >= configuration.batchSize) beginCycle() else armTimer()
    }

    /**
     * Push: binds this app install's FCM [token] for [appId] (the Android application ID)
     * to the current identity by enqueuing `Installation Registered` through the durable queue. The
     * token is never logged.
     */
    @InternalTranthorApi
    public suspend fun registerInstallation(token: String, appId: String, permission: InstallationPermission): Unit =
        withContext(dispatcher) {
            if (stopped) return@withContext
            val previous = state.installation
            if (state.optedOut || state.userId == null) {
                // Nothing is collected while opted out; optIn() registers the latest token. And a phone is
                // registered only to an identified customer: until identify() the token is held, not sent.
                state.installation = StoredInstallation(token, appId, permission)
                sentInstallation = null
                saveOrThrow()
                return@withContext
            }
            val events = ArrayList<LinkedHashMap<String, JsonValue>>(2)
            // A refreshed token: the old one leaves first. The server applies a token's events in event-time order.
            if (previous != null && (previous.token != token || previous.appId != appId)) {
                events.add(installationRemoval(state, context, nextInstallationTime(), previous))
            }
            val registered = StoredInstallation(token, appId, permission)
            val fingerprint = installationFingerprint(state, platform, registered)
            // At most once per app start for the same binding: the server treats a re-registration as "last seen".
            if (events.isEmpty() && fingerprint == sentInstallation) return@withContext
            events.add(installationRegistration(state, context, platform, nextInstallationTime(), registered))
            state.installation = registered
            try {
                admit(events, saveState = true)
            } catch (error: Throwable) {
                state.installation = previous
                throw error
            }
            sentInstallation = fingerprint
        }

    /** Strictly increasing within the process, so a Registered never ties with the Removed before it. */
    private fun nextInstallationTime(): String {
        val now = maxOf(platform.currentTimeMs(), installationClockMs + 1)
        installationClockMs = now
        return timestamp(now)
    }

    /**
     * Clears the stored user ID and generates a new anonymous UUID. Queued items keep their identities.
     * A registered push Installation is removed under the outgoing identity and registered again under
     * the new anonymous ID, in the same durable write as the rotation.
     */
    public suspend fun reset(): Unit = withContext(dispatcher) {
        if (stopped) return@withContext
        // Only a signed-in customer's phone is registered, so only then is there a binding to remove.
        val installation = state.installation?.takeIf { !state.optedOut && state.userId != null }
        // Built before the rotation, so it carries the outgoing identity.
        val removal = installation?.let { installationRemoval(state, context, nextInstallationTime(), it) }
        val previousUserId = state.userId
        val previousAnonymousId = state.anonymousId
        val previousSentInstallation = sentInstallation
        val outgoing = currentUser()
        val previousNotedUser = notedUser
        val tokensBefore = state.tokens.copy()
        state.userId = null
        notedUser = null
        state.anonymousId = newAnonymousId()
        sentInstallation = null
        // The outgoing user's identity token stays until their queued events (the removal among them) have been sent.
        pruneTokens(outgoing.takeIf { removal != null })
        try {
            // The token stays held: the next identify() registers it to that customer. Nothing is registered now.
            if (removal == null) saveOrThrow() else admit(listOf(removal), saveState = true)
        } catch (error: Throwable) {
            // All or nothing: a failed write leaves the identity as it was, in memory and on disk.
            state.userId = previousUserId
            state.anonymousId = previousAnonymousId
            sentInstallation = previousSentInstallation
            notedUser = previousNotedUser
            state.tokens = tokensBefore
            throw error
        }
        publish()
    }

    /**
     * Persists opt-out, cancels delivery and retries, and discards pending items without callbacks.
     * Calls made while opted out are ignored; [flush] succeeds without a request. The one exception:
     * a stored push Installation is removed on the server by one durable `Installation Removed`,
     * delivered even while opted out. It gets one attempt cycle; after a retryable failure it waits,
     * with no timer, for the next app start, return to the foreground, [optIn] or [optOut], each of
     * which makes one new cycle, until the server accepts or rejects it. A `403 identity_not_verified` never rejects it:
     * after one renewal through the identity provider it stays, is retried every 60 seconds, and each failed attempt is
     * reported to `onError` with the push token redacted.
     */
    public suspend fun optOut() {
        val outcome = withContext(dispatcher) {
            if (stopped) return@withContext null
            // The single exception to "opt-out discards everything": the server must stop pushing to this phone.
            if (!state.optedOut) state.pendingRemovals = optOutRemovals(state, state.installation?.takeIf { state.userId != null }, ::nextInstallationTime)
            state.optedOut = true
            sentInstallation = null
            store.queue.dropFromMemory()
            dropped.clear()
            dropNotification?.cancel(); dropNotification = null
            cancelTimer()
            // The discarded batch was the reason for the pause; later events start fresh.
            exhaustionPause.reset()
            endHold()
            sendCounts.clear()
            // Only a user with an undelivered removal (and the current user) keeps a token.
            pruneTokens()
            val pending = cycle
            pending?.cancel(); backoff?.cancel()
            publish()
            // Consent must be durable before any queue/identity state can reopen it.
            // The files go last: a restart that finds consent but surviving files finishes the deletion.
            // A file that cannot be deleted stays and is deleted by the next start or by optIn(), never sent.
            val saved = runCatching { store.saveConsent(true); store.save(state) }
            if (saved.isSuccess) store.queue.deleteAllFiles()
            pending to saved
        } ?: return
        outcome.first?.join()
        withContext(dispatcher) { triggerRemovals() }
        outcome.second.exceptionOrNull()?.let { throw storeError(it) }
    }

    /** Whether the delivery cycle has anything to send: opt-out removals always, the queue only while opted in. */
    private fun hasDeliverableWork(): Boolean =
        if (state.pendingRemovals.isNotEmpty()) removalParked == null else !state.optedOut && store.queue.hasQueued && !held

    /** Persists opt-in and resumes collection. */
    public suspend fun optIn(): Unit = withContext(dispatcher) {
        if (stopped) return@withContext
        // Events that outlived a failed deletion at opt-out must never be sent: they go before collection resumes.
        if (state.optedOut) store.queue.deleteAllFiles()?.let { throw storeError(it) }
        try { store.saveConsent(false) } catch (error: Exception) { throw storeError(error) }
        state.optedOut = false
        publish()
        saveOrThrow()
        // Opt-out discarded the queue (and cleared the fingerprint), so the Installation is registered
        // again; an opt-in while already opted in sends nothing new.
        // Only for a signed-in customer: a held token waits for identify().
        state.installation?.takeIf { state.userId != null }?.let { registration ->
            val fingerprint = installationFingerprint(state, platform, registration)
            if (fingerprint != sentInstallation) {
                // Signing the same user in again registers the token, which supersedes a parked removal of it.
                val removalsBefore = state.pendingRemovals.toList()
                val parkedBefore = removalParked
                val superseded = state.userId?.let { supersedeRemovals(it, registration) } == true
                try {
                    admit(listOf(installationRegistration(state, context, platform, nextInstallationTime(), registration)), saveState = superseded)
                } catch (error: Throwable) {
                    restoreParkedRemovals(removalsBefore, parkedBefore)
                    throw error
                }
                sentInstallation = fingerprint
            }
        }
        // A trigger: one new attempt cycle for an opt-out removal still undelivered (the queue waits behind it).
        if (state.pendingRemovals.isNotEmpty()) triggerRemovals() else armTimer()
    }

    /** One new attempt cycle for undelivered opt-out removals (app start, foreground, optIn, optOut). */
    private fun triggerRemovals() {
        if (state.pendingRemovals.isEmpty()) return
        removalTimer?.cancel(); removalTimer = null
        removalTriggers++
        removalParked = null
        beginCycle()
    }

    /**
     * Awaits the current cycle and queued batches, and attempts at once even while delivery is paused. Throws
     * [DeliveryFailure] for a batch that failed after the call began (a batch whose retries ran out stays queued
     * for a later attempt), [TranthorException.FlushCancelled] when the cycle was cancelled,
     * and [TranthorException.Stopped] after [shutdown].
     */
    public suspend fun flush() {
        beginFlush()?.await()
    }

    /** Stops without flushing, preserving pending items for a new instance on the same namespace. */
    public suspend fun shutdown() {
        val task = withContext(dispatcher) {
            shutdownTask ?: run {
                stopped = true
                CoroutineScope(dispatcher).async(start = CoroutineStart.ATOMIC) { completeShutdown() }.also { shutdownTask = it }
            }
        }
        task.await()
    }

    private suspend fun completeShutdown() {
        cancelTimer()
        endHold()
        removalTimer?.cancel(); removalTimer = null
        val pending = cycle
        pending?.cancel(); backoff?.cancel()
        providerScope.cancel()
        transport.shutdown()
        pending?.join()
        emitDropped()
        persistDeliveryState()
        lifecycleDisposer?.let { dispose -> runCatching { withContext(NonCancellable) { dispose() } } }
        lifecycleDisposer = null
        NamespaceRegistry.release(lease)
        scope.cancel()
    }

    private fun saveOrThrow() {
        try { store.save(state) } catch (error: Exception) { throw storeError(error) }
    }

    private fun storeError(error: Throwable): Throwable = when (error) {
        is TranthorException -> error
        is IOException, is SecurityException -> TranthorException.StoreTemporarilyUnavailable(error)
        else -> error
    }

    /** The single path to the host's `onError`: a push token never leaves the SDK through it. */
    private fun notify(failure: BatchFailure) {
        val handler = configuration.onError ?: return
        val reported = BatchFailure(failure.error, failure.events.map(::withoutToken), failure.attempts)
        // Callback exceptions do not change delivery.
        try { handler(reported) } catch (_: Throwable) { platform.logDebug("onError callback threw") }
    }

    /** Diagnostics for the host's `onNotice`: callback exceptions do not change delivery. */
    private fun notice(kind: NoticeKind, message: String, count: Int? = null, reason: String? = null) {
        val handler = configuration.onNotice ?: return
        try { handler(Notice(kind, message, count, reason)) } catch (_: Throwable) { platform.logDebug("onNotice callback threw") }
    }

    private fun cancelTimer() {
        timerGeneration++
        timer?.cancel(); timer = null
    }

    private fun armTimer() {
        if (timer != null || cycle != null || !hasDeliverableWork() || stopped || paused || exhaustionPause.isRunning) return
        timerGeneration++
        val generation = timerGeneration
        val delayMs = configuration.flushIntervalMs.toDouble()
        timer = scope.launch {
            try { sleep(delayMs) } catch (_: CancellationException) { return@launch }
            timerFired(generation)
        }
    }

    private fun timerFired(generation: Long) {
        if (generation != timerGeneration) return
        timer = null
        beginCycle()
    }

    private fun beginCycle() {
        if (cycle != null || stopped || paused || exhaustionPause.isRunning || !hasDeliverableWork()) return
        cancelTimer()
        // ATOMIC: a cycle cancelled before it starts still runs its cleanup and settles its waiters.
        cycle = scope.launch(start = CoroutineStart.ATOMIC) { runCycle(this) }
    }

    /** An explicit flush or a return from the background attempts at once instead of waiting the pause out. */
    private fun attemptAtOnce() {
        exhaustionPause.end()
        beginCycle()
    }

    private fun persistDeliveryState() {
        try { store.save(state) } catch (_: Exception) { platform.logDebug("Unable to save delivery state") }
    }

    private fun reportDropped(events: List<Map<String, JsonValue>>) {
        if (events.isEmpty() || state.optedOut) return
        dropped.addAll(events)
        if (dropNotification != null) return
        dropNotification = scope.launch {
            try { delay(500) } catch (_: CancellationException) { return@launch }
            emitDropped()
        }
    }

    private fun emitDropped() {
        dropNotification?.cancel(); dropNotification = null
        if (dropped.isEmpty()) return
        val events = ArrayList(dropped); dropped.clear()
        notify(BatchFailure(DeliveryFailure(null, "Queue capacity exceeded; oldest queued events dropped"), events, 0))
    }

    /** The result of one request: a failure, or the notes of a 2xx (never both). */
    private class Sent(val failure: DeliveryFailure?, val notes: ResponseNotes?)

    /** Sends on an I/O thread; never throws CancellationException, so a received 2xx is always acknowledged. */
    private suspend fun guardedSend(cycleScope: CoroutineScope, cycleJob: Job, request: BatchRequest): Sent {
        val abort = AbortHandle()
        val watcher = cycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                delay(totalRequestTimeoutMs)
                abort.abort() // Total request bound.
            } catch (error: CancellationException) {
                if (cycleJob.isCancelled) abort.abort()
                throw error
            }
        }
        if (cycleJob.isCancelled) abort.abort()
        return try {
            // Two layers on purpose: withContext that changes dispatcher discards its result when the
            // caller is cancelled (prompt cancellation). The outer NonCancellable layer does not change
            // dispatcher, so the inner result always comes back, even after cycle cancellation.
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    try {
                        Sent(null, transport.send(request, abort))
                    } catch (failure: DeliveryFailure) {
                        Sent(failure, null)
                    } catch (_: Exception) {
                        Sent(DeliveryFailure(null, "Network delivery failure"), null)
                    }
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    /** Waiting flushes reject with the first terminal failure of the cycle. */
    private fun failWaiters(failure: DeliveryFailure) {
        for (waiter in waiters) if (waiter.failure == null) waiter.failure = failure
    }

    /** A batch kept at the head of the queue for [ms]; [failure] is what an explicit flush waiting on it reports. */
    private class Hold(val ms: Long, val failure: DeliveryFailure)

    /** How one batch's attempts ended: acknowledged, a terminal failure, held, or none of these (interrupted). */
    private class Outcome(val acknowledged: Boolean, val terminal: DeliveryFailure?, val attempts: Int, val hold: Hold? = null)

    private fun endHold() {
        held = false
        currentHold = null
        holdTimer?.cancel(); holdTimer = null
    }

    private fun startHold(hold: Hold) {
        held = true
        currentHold = hold
        holdTimer?.cancel()
        holdTimer = scope.launch {
            try { sleep(hold.ms.toDouble()) } catch (_: CancellationException) { return@launch }
            holdTimer = null
            currentHold = null
            held = false
            beginCycle()
        }
    }

    /** Retries a parked opt-out removal after [ms], on the same timer length as the other holds. */
    private fun startRemovalRetry(ms: Long) {
        removalTimer?.cancel()
        removalTimer = scope.launch {
            try { sleep(ms.toDouble()) } catch (_: CancellationException) { return@launch }
            removalTimer = null
            triggerRemovals()
        }
    }

    /**
     * Asks the host's provider for [userId]'s token and waits at most the provider timeout. The provider runs
     * off this executor; any throw (including a CancellationException from it), a timeout or an empty answer
     * is a failure that keeps the provider unused for the backoff, and a late answer is discarded.
     */
    private suspend fun renew(userId: String): Boolean {
        val provider = configuration.identityTokenProvider ?: return false
        if (platform.currentTimeMs() < providerBackoffUntilMs) return false
        // A blocking provider (see IdentityTokenSupplier) is abandoned at the timeout but its thread runs on: no second
        // call starts until it returns, so abandoned calls cannot pile up one per backoff window.
        if (providerWorker?.isCompleted == false) {
            providerBackoffUntilMs = platform.currentTimeMs() + timings.providerBackoffMs
            return false
        }
        val answer = CompletableDeferred<String?>()
        val worker = providerScope.launch {
            try { answer.complete(provider(userId)) } catch (_: Throwable) { answer.complete(null) }
        }
        providerWorker = worker
        val timer = scope.launch {
            try { sleep(timings.providerTimeoutMs.toDouble()) } catch (_: CancellationException) { return@launch }
            answer.complete(null)
        }
        val token = try { answer.await() } finally { timer.cancel(); worker.cancel() }
        if (!token.isNullOrEmpty()) {
            state.tokens.set(userId, token)
            // The call suspended: a user who is no longer current and has nothing queued does not need the token.
            pruneTokens()
            if (state.tokens.get(userId) == null) return false
            persistDeliveryState()
            return true
        }
        providerBackoffUntilMs = platform.currentTimeMs() + timings.providerBackoffMs
        return false
    }

    /**
     * Before the first attempt: renew the token of the current user in the batch (of every user, for an opt-out
     * removal, whose user may no longer be current) when it has less than 60 s left. Whatever happens the batch
     * is then sent with the tokens we have: the server decides, so a failing provider never keeps data back.
     * Returns the users whose token the provider just supplied.
     */
    private suspend fun renewBeforeSending(batch: List<QueueItem>, removals: Boolean): MutableSet<String> {
        val renewed = HashSet<String>()
        if (configuration.identityTokenProvider == null) return renewed
        val users = distinctUsers(batch)
        val targets = if (removals) users else listOfNotNull(currentUser()?.takeIf { it in users })
        for (user in targets) {
            if (state.tokens.getFresh(user, platform.currentTimeMs(), RENEW_BELOW_MS) == null && renew(user)) renewed.add(user)
        }
        return renewed
    }

    /** One token per user in [batch] that has one, in the order the users first appear. Expired by up to 2 minutes still counts. */
    private fun tokensFor(batch: List<QueueItem>): List<String> {
        val now = platform.currentTimeMs()
        return distinctUsers(batch).mapNotNull { state.tokens.getFresh(it, now, -TOKEN_ACCEPTED_AFTER_EXPIRY_MS) }
    }

    /** The server dropped or could not verify events it accepted: say so once, without any token or user id. */
    private fun reportResponseNotes(notes: ResponseNotes?) {
        if (notes == null || notes.isEmpty) return
        val advice = "With identity verification required, register push and identify the user with an identity token after sign-in."
        if (notes.droppedEvents > 0) {
            // The count also covers events dropped for being oversized, in every mode: the wording stays neutral.
            notice(NoticeKind.EVENTS_DROPPED, "The server dropped ${notes.droppedEvents} event(s). In Required mode this includes anonymous events that could change a customer. $advice", count = notes.droppedEvents)
        }
        if (notes.unverifiedEvents > 0) {
            notice(NoticeKind.EVENTS_UNVERIFIED, "The server accepted ${notes.unverifiedEvents} event(s) it could not verify. $advice", count = notes.unverifiedEvents)
        }
    }

    /**
     * Removes a user's items from the batch in flight (a previous user's, whom no token can be renewed for, or every
     * user's when identity was never used) and reports them once through `onError`; the rest of the batch is sent.
     */
    private fun dropUser(user: String, refusal: DeliveryFailure) {
        // A sign-out `Installation Removed` is never dropped for lack of a token: it stays, parked by the hold.
        val items = store.queue.activeItems.filter { userIdOf(it) == user && !isInstallationRemoval(it) }
        if (items.isEmpty()) return
        val attempts = items.maxOf { sendCounts[it] ?: 0 }
        store.queue.removeFromActive(items)
        for (item in items) sendCounts.remove(item)
        pruneTokens()
        persistDeliveryState()
        notify(BatchFailure(refusalFailure(refusal), items.map { it.value }, attempts))
    }

    private fun refusalFailure(refusal: DeliveryFailure, detail: String? = null): DeliveryFailure =
        DeliveryFailure(403, detail ?: "identity not verified", code = IDENTITY_NOT_VERIFIED, reason = refusal.reason)

    /** Reasons the SDK cannot fix by renewing: the app's own token signing is at fault. */
    private fun unfixableByRenewing(reason: String?): Boolean =
        reason in setOf("lifetime", "malformed", "audience", "workspace", "unknown_key", "signature")

    /**
     * Sends the batch with the configured retries and backoff, handling the identity answers on the way. The batch
     * is [StoredState.active], or the opt-out removals when [removals]; either can shrink while this runs.
     * Opt-out interrupts ordinary batches but not removals, which are the only thing sent while opted out.
     */
    private suspend fun sendWithRetries(cycleScope: CoroutineScope, job: Job, removals: Boolean): Outcome {
        fun batch(): List<QueueItem> = if (removals) state.pendingRemovals.toList() else store.queue.activeItems
        fun interrupted() = job.isCancelled || stopped || (!removals && state.optedOut)
        // The users whose token the provider supplied during this send: a token just obtained and refused is not renewed again.
        val renewedThisSend = renewBeforeSending(batch(), removals)
        var sends = 0
        var retry = 0
        // Set when the current user's items were split off to wait: applied once the rest of the batch is delivered.
        var pendingHold: Hold? = null
        while (!job.isCancelled) {
            val current = batch()
            if (current.isEmpty()) return Outcome(true, null, maxOf(sends, 1), pendingHold)
            sends++
            for (item in current) sendCounts[item] = (sendCounts[item] ?: 0) + 1
            // A 2xx acknowledges the batch even if cancellation raced with the response.
            val sent = guardedSend(cycleScope, job, BatchRequest(current, tokensFor(current)))
            val failure = sent.failure
            if (failure == null) { reportResponseNotes(sent.notes); return Outcome(true, null, sends, pendingHold) }
            if (interrupted()) break
            var retryable = failure.retryable
            if (failure.status == 403 && failure.code == IDENTITY_NOT_VERIFIED) {
                val users = distinctUsers(current)
                val refused = users.filter { it in failure.userIds }.ifEmpty { users }
                val inUse = identityInUse()
                if (refused.isEmpty()) {
                    // Nothing to renew, drop or hold. An app that uses identity retries with the normal backoff and says so;
                    // one that never did keeps the old behaviour: a final 403, reported and dropped.
                    if (inUse) {
                        notice(NoticeKind.IDENTITY_REFUSED, "The server refused identity verification for events that have no user to renew a token for" + reasonSuffix(failure.reason),
                            reason = failure.reason)
                        retryable = true
                    }
                } else if (removals) {
                    // An opt-out removal is never dropped for lack of a token: renew once, and if that does not help park it.
                    for (user in refused) state.tokens.delete(user)
                    var renewed = false
                    for (user in refused) if (user !in renewedThisSend && renew(user)) { renewedThisSend.add(user); renewed = true }
                    if (renewed) continue
                    return Outcome(false, null, sends, Hold(timings.providerBackoffMs, refusalFailure(failure)))
                } else if (!inUse && current.none(::isInstallationRemoval)) {
                    // Identity was never used: the old behaviour for the whole batch.
                    return Outcome(false, failure, sends, pendingHold)
                } else {
                    for (user in refused) state.tokens.delete(user)
                    persistDeliveryState()
                    val currentUser = currentUser()
                    val previous = refused.filter { it != currentUser }
                    // A sign-out removal of a previous user gets one renewal through the provider before it is kept.
                    var renewedAny = false
                    for (user in previous) {
                        if (user !in renewedThisSend && current.any { userIdOf(it) == user && isInstallationRemoval(it) } && renew(user)) {
                            renewedThisSend.add(user); renewedAny = true
                        }
                    }
                    if (renewedAny) continue
                    for (user in previous) dropUser(user, failure)
                    var holdCurrent = false
                    if (currentUser != null && currentUser in refused) {
                        // A token just obtained and refused again is not renewed again in the same send.
                        val refusedAfterRenewal = currentUser in renewedThisSend
                        if (inUse && !refusedAfterRenewal && renew(currentUser)) { renewedThisSend.add(currentUser); continue }
                        if (inUse) {
                            holdCurrent = true
                            notice(NoticeKind.IDENTITY_REFUSED, refusalMessage(failure.reason, refusedAfterRenewal), reason = failure.reason)
                        } else dropUser(currentUser, failure)
                    }
                    // What stays: the current user's items waiting for a token, and every kept sign-out removal.
                    val kept = batch().filter { (holdCurrent && userIdOf(it) == currentUser) || isInstallationRemoval(it) && userIdOf(it) in refused }
                    if (kept.isEmpty()) continue
                    // Each failed attempt for a previous user's removal is reported (its push token redacted), because it stays until
                    // delivered; the current user's is a notice like any other held batch.
                    val keptRemovals = kept.filter { isInstallationRemoval(it) && userIdOf(it) != currentUser }
                    if (keptRemovals.isNotEmpty()) notify(BatchFailure(refusalFailure(failure), keptRemovals.map { it.value }, sends))
                    val hold = Hold(timings.providerBackoffMs, refusalFailure(failure))
                    // The other users' events (and anonymous ones) in this batch go first, in one request; only the kept
                    // items wait. Events queued behind the held ones still wait for them: that limit is deliberate.
                    if (kept.size == batch().size || pendingHold != null) return Outcome(false, null, sends, hold)
                    store.queue.returnToHead(kept)
                    pendingHold = hold
                    continue
                }
            } else if (!removals && failure.status == 503 && failure.code in IDENTITY_UNAVAILABLE_CODES) {
                // The server could not read its identity settings. Nothing is wrong with the batch: keep it for Retry-After.
                val ms = if (failure.retryAfterGiven) failure.heldAfterMs.toLong().coerceIn(UNAVAILABLE_HOLD_MIN_MS, UNAVAILABLE_HOLD_MAX_MS)
                else UNAVAILABLE_HOLD_DEFAULT_MS
                notice(NoticeKind.IDENTITY_UNAVAILABLE, "The server could not read its identity settings; delivery is held for ${ms / 1000} s and nothing is dropped.")
                // A hold set aside for the current user's missing token outlasts the server's delay if it is longer.
                return Outcome(false, null, sends, Hold(maxOf(ms, pendingHold?.ms ?: 0L), failure))
            }
            // A background window makes one attempt and never sleeps through a retry.
            if (backgroundToken != null && retryable) { paused = true; break }
            if (retry >= configuration.retries || !retryable) return Outcome(false, failure, sends, pendingHold)
            val delayMs = retryDelay(configuration.baseDelay, retry, failure.retryAfterMs)
            val pending = scope.launch { sleep(delayMs) }
            backoff = pending
            var wasInterrupted = false
            try { pending.join() } catch (_: CancellationException) { wasInterrupted = true }
            if (pending.isCancelled) wasInterrupted = true
            if (wasInterrupted) {
                if (job.isCancelled) pending.cancel()
                // Cancelling only the sleeper on background entry permits an immediate attempt.
                if (interrupted() || backgroundToken == null) break
            }
            backoff = null
            retry++
        }
        return Outcome(false, null, 0, pendingHold)
    }

    private fun reasonSuffix(reason: String?): String = reason?.let { " (reason: $it)" } ?: ""

    /** Never contains a token or a user id. Names the app's signing code when renewing cannot help. */
    private fun refusalMessage(reason: String?, refusedAfterRenewal: Boolean): String = when {
        refusedAfterRenewal && unfixableByRenewing(reason) ->
            "The server refused the identity token the provider supplied${reasonSuffix(reason)}; renewing cannot fix this, check the app's token signing. The batch is held."
        refusedAfterRenewal -> "The server refused the identity token the provider supplied${reasonSuffix(reason)}. The batch is held."
        else -> "No identity token could be obtained for the current user${reasonSuffix(reason)}. The batch is held."
    }

    /**
     * Opt-out removals go first: the server orders a token's changes by clamped event time, so a
     * Removed must never arrive after the Registered that follows it. Returns true when none remain.
     */
    private suspend fun deliverRemovals(cycleScope: CoroutineScope, job: Job): Boolean {
        while (state.pendingRemovals.isNotEmpty() && !job.isCancelled && !stopped && !paused) {
            val batch = state.pendingRemovals.toList()
            val triggers = removalTriggers
            val outcome = sendWithRetries(cycleScope, job, removals = true)
            val hold = outcome.hold
            if (hold != null) {
                // Refused and one renewal did not fix it: the removal stays (the phone must stop receiving push), each failed
                // attempt is reported, and it is retried on the hold timer and at the next launch.
                notify(BatchFailure(hold.failure, batch.map { it.value }, outcome.attempts))
                removalParked = hold.failure
                failWaiters(hold.failure)
                startRemovalRetry(hold.ms)
                return false
            }
            val terminal = outcome.terminal
            // A retryable failure (offline, 429, 5xx) after the configured retries keeps the removal but parks it
            // until the next trigger. An interrupted cycle (cancelled, background) does not park it.
            if (!outcome.acknowledged && (terminal == null || terminal.retryable)) {
                // A trigger that arrived during this cycle gets a cycle of its own.
                if (terminal != null && removalTriggers != triggers) continue
                if (terminal != null) {
                    removalParked = terminal
                    failWaiters(terminal)
                }
                return false
            }
            // The server refused it for good: it is dropped like any other terminal batch, so the host hears
            // of it once. The removal carries the push token, which stays out of the callback.
            if (terminal != null) notify(BatchFailure(terminal, batch.map { it.value }, outcome.attempts))
            state.pendingRemovals.removeAll { sent -> batch.any { it === sent } }
            pruneTokens()
            persistDeliveryState()
        }
        return state.pendingRemovals.isEmpty()
    }

    private suspend fun runCycle(cycleScope: CoroutineScope) {
        val job = currentCoroutineContext().job
        var exhausted = false
        try {
            if (!deliverRemovals(cycleScope, job)) return
            store.queue.retryPendingDeletions()
            while (store.queue.hasQueued && !job.isCancelled && !stopped && !state.optedOut && !held) {
                // Tokens for at most 10 users go in one request; without identity in use batching is unchanged.
                store.queue.takeBatch(configuration.batchSize, if (identityInUse()) ::takeCount else null)
                val outcome = sendWithRetries(cycleScope, job, removals = false)
                // Items a refusal removed from the batch are already reported; this is what is left.
                val batch = store.queue.activeItems
                val acknowledged = outcome.acknowledged
                val terminal = outcome.terminal
                if (terminal != null) {
                    // A retryable failure keeps the batch: the host hears only of events that are gone for good.
                    if (!terminal.retryable) notify(BatchFailure(terminal, batch.map { it.value }, outcome.attempts))
                    else exhausted = true
                    failWaiters(terminal)
                }
                if (acknowledged) exhaustionPause.reset()
                if (acknowledged || terminal?.retryable == false) {
                    for (item in batch) sendCounts.remove(item)
                    store.queue.completeBatch()
                    pruneTokens()
                    persistDeliveryState()
                }
                val hold = outcome.hold
                if (hold != null && !job.isCancelled && !stopped && !state.optedOut) {
                    // What is left of the batch goes back to the head of the queue, and nothing is sent until the hold ends.
                    startHold(hold)
                    failWaiters(hold.failure)
                    break
                }
                // Never advance to another batch while this one remains unacknowledged.
                if (!acknowledged && terminal == null) break
                if (job.isCancelled || stopped || state.optedOut || paused) break
                if (terminal?.retryable == true) break
            }
        } finally {
            // The sole recovery point for an unacknowledged batch. Cancellation is cycle-local and
            // cannot be undone by a foreground event or a flush await.
            val cancelled = job.isCancelled || stopped || paused || store.queue.hasActive
            if (store.queue.hasActive && !state.optedOut) store.queue.returnBatchToHead()
            backoff = null
            cycle = null
            if (exhausted) exhaustionPause.start { beginCycle() }
            val completions = ArrayList(waiters); waiters.clear()
            for (waiter in completions) {
                val failure = waiter.failure
                when {
                    failure != null -> waiter.deferred.completeExceptionally(failure)
                    cancelled -> waiter.deferred.completeExceptionally(TranthorException.FlushCancelled())
                    else -> waiter.deferred.complete(Unit)
                }
            }
            armTimer()
        }
    }

    /**
     * The lifecycle entry point used by the process lifecycle hook. Events carry a sequence
     * number assigned on the main thread in OS order. The hook launches them UNDISPATCHED, so they
     * already reach this serial executor in order; the sequence check additionally drops any event
     * that is superseded by a later one (for example a STOP admitted after the following START), so
     * delivery can never be left paused in the foreground or lose the ON_STOP promotion.
     */
    internal suspend fun handleLifecycle(event: LifecycleEvent, sequence: Long) {
        val token = withContext(dispatcher) {
            if (sequence <= lifecycleSequence) return@withContext null
            lifecycleSequence = sequence
            // Recorded even while opted out, which is when an opt-out removal may be waiting for this trigger.
            val previous = lastLifecycleEvent
            lastLifecycleEvent = event
            when (event) {
                LifecycleEvent.FOREGROUND -> {
                    lifecycleToken = null
                    backgroundToken = null
                    paused = false
                    // Only a FOREGROUND after STOP is a return: ON_START and ON_RESUME both arrive as FOREGROUND, a
                    // pause without a stop is not a return, and the first one of the process belongs to the app
                    // start, which already made its cycle (configured before the first activity, or replayed inside
                    // configure()).
                    if (previous == LifecycleEvent.STOP) {
                        triggerRemovals()
                        if (exhaustionPause.isRunning) attemptAtOnce()
                    }
                    armTimer()
                    null
                }
                LifecycleEvent.PAUSE, LifecycleEvent.STOP -> {
                    if (stopped || state.optedOut) return@withContext null
                    val background = event == LifecycleEvent.STOP
                    val running = lifecycleToken
                    if (running != null) {
                        // Promote a running inactivity flush: cut the backoff short, one attempt, then pause.
                        if (background) { backgroundToken = running; backoff?.cancel() }
                        null
                    } else {
                        val next = UUID.randomUUID()
                        lifecycleToken = next
                        backgroundToken = if (background) next else null
                        paused = false
                        if (background) backoff?.cancel()
                        next
                    }
                }
            }
        } ?: return
        // A held batch is not retried by a lifecycle flush; only an explicit flush ends the hold.
        try { beginFlush(endsHold = false)?.await() } catch (error: Exception) { if (error is CancellationException) throw error }
        withContext(dispatcher) {
            if (backgroundToken == token) backgroundToken = null
            if (lifecycleToken == token) lifecycleToken = null
        }
    }

    /** Registers a flush waiter in admission order without awaiting delivery (Java facade). */
    internal suspend fun beginFlush(endsHold: Boolean = true): CompletableDeferred<Unit>? = withContext(dispatcher) {
        if (stopped) throw TranthorException.Stopped()
        if (state.optedOut) return@withContext null
        // An explicit flush ends a hold and tries again at once; a flush the SDK makes itself does not.
        if (held) { if (endsHold) endHold() else return@withContext null }
        emitDropped()
        paused = false
        store.queue.retryPendingDeletions()
        if (cycle == null && !store.queue.hasQueued) return@withContext null
        // flush() is not a trigger: the queue waits behind a parked opt-out removal, so report its failure.
        removalParked?.let { failure -> if (cycle == null) return@withContext CompletableDeferred<Unit>().apply { completeExceptionally(failure) } }
        Waiter(CompletableDeferred()).also { waiters.add(it); attemptAtOnce() }.deferred
    }

    internal fun logDebug(message: String) = platform.logDebug(message)

    private suspend fun start(removed: List<Map<String, JsonValue>>) = withContext(dispatcher) {
        reportDropped(removed)
        // An app that never calls identify has no stored user: the first user in the restored queue is the current one.
        if (state.userId.isNullOrEmpty()) notedUser = store.queue.queuedItems.firstNotNullOfOrNull { userIdOf(it) }
        // A later Registered must stay strictly after a stored removal even if the clock went back.
        state.pendingRemovals.mapNotNull { item -> item.value["timestamp"]?.stringValue?.let(::parseTimestamp) }.maxOrNull()
            ?.let { installationClockMs = maxOf(installationClockMs, it) }
        // An opt-out removal left undelivered by the previous process goes out at once.
        if (state.pendingRemovals.isNotEmpty()) beginCycle() else armTimer()
    }

    public companion object {
        /**
         * Asynchronously loads storage on an I/O thread, installs the process lifecycle observer and
         * returns the instance. Throws [TranthorException.InvalidHost],
         * [TranthorException.NamespaceInUse] or [TranthorException.StoreTemporarilyUnavailable].
         */
        public suspend fun configure(context: Context, configuration: Configuration): TranthorSDK =
            create(configuration, AndroidPlatform(context.applicationContext ?: context))

        internal suspend fun create(
            configuration: Configuration,
            platform: Platform,
            transport: SdkTransport? = null,
            sleep: suspend (Double) -> Unit = ::sleepMilliseconds,
            storeWrite: ((ByteArray, java.io.File) -> Unit)? = null,
            storeMove: ((java.io.File, java.io.File) -> Unit)? = null,
            storeRead: ((java.io.File) -> ByteArray?)? = null,
            storeDelete: ((java.io.File) -> Unit)? = null,
            totalRequestTimeoutMs: Long = 60_000,
            identityTimings: IdentityTimings = IdentityTimings(),
        ): TranthorSDK {
            val endpoint = endpointFor(configuration.host)
            // An id the developer chose is sent as it is or configuration fails; only the default is the package name.
            configuration.appId?.let { if (!isValidAppId(it)) throw TranthorException.InvalidAppId() }
            val appId = configuration.appId ?: platform.packageName.takeIf { isValidAppId(it) }
            val sender = transport ?: HttpTransport(endpoint, configuration.writeKey, appId)
            val base = configuration.storageDirectory ?: platform.defaultStorageDirectory()
            val lease = NamespaceRegistry.acquire(base, configuration.storageNamespace)
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val io = platform.fileIo
                    val store = JsonStore(base, configuration.storageNamespace, io,
                        writeBytes = storeWrite ?: { bytes, file -> io.write(file, bytes) },
                        readBytes = storeRead ?: { io.read(it) },
                        moveFile = storeMove ?: { from, to -> io.move(from, to) },
                        deleteFile = storeDelete ?: { io.delete(it) })
                    val restored = store.load()
                    // An opted-out user's files are deleted; any that remain are deleted again by the next start or by optIn().
                    if (restored.optedOut) store.queue.deleteAllFiles() else store.queue.load(store.legacyEvents)
                    // Before the first save below, which would hide that the opt-out was interrupted.
                    if (store.interruptedOptOut) restored.installation?.takeIf { restored.userId != null }?.let {
                        restored.pendingRemovals.add(optOutRemoval(restored, it, timestamp(platform.currentTimeMs())))
                    }
                    // A token whose user has nothing left to send and is not the stored user is stale.
                    restored.pruneTokens(store.queue, platform.currentTimeMs(), restored.userId.takeIf { !restored.optedOut })
                    val removed = ArrayList<Map<String, JsonValue>>()
                    store.queue.trim(configuration.maxQueueSize).mapTo(removed) { it.value }
                    // Persist fail-closed consent recovery independently of routine state writes.
                    store.saveConsent(restored.optedOut)
                    store.save(restored)
                    Triple(store, restored, removed)
                }
            } catch (error: Throwable) {
                NamespaceRegistry.release(lease)
                sender.shutdown()
                when (error) {
                    is CancellationException, is TranthorException.StoreTemporarilyUnavailable -> throw error
                    else -> throw TranthorException.StoreTemporarilyUnavailable(error)
                }
            }
            val sdk = TranthorSDK(configuration, sender, sleep, lease, loaded.first, loaded.second,
                if (configuration.automaticContext) platform.automaticContext() else emptyMap(), platform, totalRequestTimeoutMs, identityTimings)
            sdk.lifecycleDisposer = try { platform.installLifecycle(sdk) } catch (error: Exception) {
                if (error is CancellationException) { sdk.shutdown(); throw error }
                platform.logDebug("Lifecycle hooks unavailable"); null
            }
            sdk.start(loaded.third)
            return sdk
        }
    }
}

internal suspend fun sleepMilliseconds(ms: Double) {
    if (ms > 0) delay((ms * 1_000_000).toLong().nanoseconds)
}

internal enum class LifecycleEvent { PAUSE, STOP, FOREGROUND }
