package com.tranthor.push

import android.content.Context
import com.tranthor.sdk.InstallationPermission
import com.tranthor.sdk.InternalTranthorApi
import com.tranthor.sdk.JsonValue
import com.tranthor.sdk.TranthorSDK
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * FCM integration for a [TranthorSDK]. Forwarded tokens become `Installation Registered` and
 * `Installation Removed` events, and observations become `Push Received`, `Push Opened` and
 * `Push Dismissed`, all through the SDK's durable queue. The SDK's identity and consent apply.
 *
 * Obtain an instance with [bind] and keep it for the application's lifetime. It owns neither the
 * host's `FirebaseMessagingService` nor its activities, and never prompts for permission.
 */
public class TranthorPush @InternalTranthorApi constructor(
    public val configuration: PushConfiguration,
    private val sdk: TranthorSDK,
    private val permission: () -> InstallationPermission = { InstallationPermission.UNKNOWN },
) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    internal val scope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile private var token: String? = null
    private data class EventKey(val id: String, val interaction: String)
    private val pending = HashSet<EventKey>()
    private val recent = HashSet<EventKey>()
    private val recentOrder = ArrayDeque<EventKey>()

    /** Keeps a newer token from being overtaken by an earlier one that is still registering. */
    private val installationOrder = Mutex()

    /** Whether an FCM token has been forwarded to this instance. */
    public val hasDeviceToken: Boolean get() = token != null

    init {
        if (!isValidApplicationId(configuration.applicationId)) throw PushException.InvalidApplicationId()
    }

    /**
     * Forward every FCM token callback (`onNewToken`) and the token fetched at launch. This enqueues
     * `Installation Registered` (and `Installation Removed` for a replaced token) through the SDK's
     * durable queue before returning. The token is held until a customer is identified.
     */
    public suspend fun registerDeviceToken(token: String): Unit = installationOrder.withLock {
        if (token.isEmpty()) throw PushException.EmptyDeviceToken()
        this.token = token
        sdk.registerInstallation(token, configuration.applicationId, permission())
    }

    /**
     * Re-reads the OS notification permission and re-registers the last forwarded token if it
     * changed. Call it after a permission request. [bind] already calls it each time the app returns
     * to the foreground. A no-op before a token arrives.
     */
    public suspend fun refreshPermission() {
        installationOrder.withLock {
            val current = token ?: return
            sdk.registerInstallation(current, configuration.applicationId, permission())
        }
    }

    /**
     * Reports an observation to the SDK. Returns false for a null (unrecognized) notification and true
     * for a recognized one, even if consent discards it or it repeats a recent observation. Dedupe is
     * bounded by [PushConfiguration.recentEventLimit] and process-local.
     */
    public suspend fun handle(notification: PushNotification?, interaction: PushInteraction): Boolean {
        if (notification == null) return false
        // A foreground observation is the same receipt as the messaging service's.
        val observed = if (interaction == PushInteraction.Foreground) PushInteraction.Received else interaction
        val key = EventKey(notification.deliveryId, observed.key)
        val admitted = withContext(dispatcher) {
            if (sdk.isOptedOut) return@withContext false
            if (key in pending || key in recent) return@withContext false
            // Also bound suspended handlers; drop the observation rather than grow without limit.
            if (pending.size >= configuration.recentEventLimit) return@withContext false
            pending.add(key)
            true
        }
        if (!admitted) return true
        try {
            val name = when (observed) {
                PushInteraction.Foreground, PushInteraction.Received -> PUSH_RECEIVED
                is PushInteraction.Opened -> PUSH_OPENED
                PushInteraction.Dismissed -> PUSH_DISMISSED
            }
            sdk.track(name, properties = mapOf("deliveryId" to JsonValue.StringValue(notification.deliveryId)),
                messageId = stableMessageId(notification.deliveryId, observed.key))
            withContext(dispatcher) {
                recent.add(key); recentOrder.addLast(key)
                if (recentOrder.size > configuration.recentEventLimit) recent.remove(recentOrder.removeFirst())
            }
        } finally {
            withContext(NonCancellable) { withContext(dispatcher) { pending.remove(key) } }
        }
        return true
    }

    public companion object {
        /**
         * A [TranthorPush] bound to [sdk] that reports the OS notification permission
         * ([PushPermission.state]) and re-checks it each time the app returns to the foreground, so a
         * permission changed in system settings re-registers the Installation. Keep it for the app's lifetime.
         * The foreground re-check follows the latest call: binding again, for example to a new SDK instance, replaces it.
         */
        @JvmStatic
        public fun bind(context: Context, configuration: PushConfiguration, sdk: TranthorSDK): TranthorPush {
            val app = context.applicationContext ?: context
            val push = TranthorPush(configuration, sdk) { PushPermission.state(app) }
            ForegroundRefresh.process.install { push.scope.launch { runCatching { push.refreshPermission() } } }
            return push
        }

        private const val PUSH_RECEIVED = "Push Received"
        private const val PUSH_OPENED = "Push Opened"
        private const val PUSH_DISMISSED = "Push Dismissed"

        private val applicationIdPattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        internal fun isValidApplicationId(value: String): Boolean = value.length <= 255 && applicationIdPattern.matches(value)
    }
}
