package com.tranthor.example

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessaging
import com.tranthor.push.PushConfiguration
import com.tranthor.push.PushContent
import com.tranthor.push.PushInteraction
import com.tranthor.push.PushNotification
import com.tranthor.push.PushPermission
import com.tranthor.push.RichImageConfiguration
import com.tranthor.push.TranthorNotifications
import com.tranthor.push.TranthorPush
import com.tranthor.sdk.TranthorSDK
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One per process. The FCM token is never logged or displayed. TranthorPush is bound to the current
 * TranthorSDK, which registers the Installation and reports Push Received/Opened/Dismissed itself
 * through its durable queue; no registration callback is needed.
 */
class ExamplePush(context: Context, private val scope: CoroutineScope, private val uiTesting: Boolean) {
    private val app = context.applicationContext
    var permission by mutableStateOf("Not requested")
        private set
    var registration by mutableStateOf("No Installation registered yet")
        private set
    var tokenStatus by mutableStateOf("No FCM token received")
        private set
    var observations by mutableIntStateOf(0)
        private set
    var lastObservation by mutableStateOf("None")
        private set
    var previewDeliveryId by mutableStateOf("None")
        private set
    var firebaseStatus by mutableStateOf("Not checked")
        private set
    var imageStatus by mutableStateOf(imageConfiguration.second)
        private set
    /** Debug builds only: the FCM token can be copied to the clipboard for scripts/send-fcm-push.mjs. */
    var tokenAvailable by mutableStateOf(false)
        private set
    private var lastToken: String? = null
    private var enabled = false
    private var client: TranthorPush? = null
    private var boundSdk: TranthorSDK? = null
    private val recorded = HashSet<String>()
    private val pendingLaunchObservations = ArrayDeque<Pair<PushNotification, PushInteraction>>()
    private var onLog: ((String) -> Unit)? = null
    private val notificationIds = AtomicInteger(1000)
    private val main = Handler(Looper.getMainLooper())

    fun attach(onLog: (String) -> Unit) {
        this.onLog = onLog
    }

    private fun log(text: String) { main.post { onLog?.invoke(text) } }

    /**
     * Called after persisted identity/consent loads (configure/refresh). Identity changes, reset and
     * consent are handled by the SDK itself; a new SDK instance gets a new binding.
     */
    suspend fun synchronize(sdk: TranthorSDK) {
        enabled = !sdk.isOptedOut
        if (boundSdk !== sdk) {
            boundSdk = sdk
            client = TranthorPush.bind(app, PushConfiguration(app.packageName), sdk)
            lastToken?.let { register(client!!, it) }
        }
        val pending = pendingLaunchObservations.toList()
        pendingLaunchObservations.clear()
        // Drain after refresh returns (the caller holds the model gate); the SDK drops them while opted out.
        if (enabled) scope.launch { for ((payload, interaction) in pending) receive(payload, interaction) }
    }

    /** The SDK is being shut down or replaced: drop this binding (it never removes the Installation). */
    fun disable() {
        enabled = false
        client = null; boundSdk = null
    }

    private suspend fun register(push: TranthorPush, token: String) {
        try {
            push.registerDeviceToken(token)
            withContext(Dispatchers.Main) { registration = "Token forwarded; it is registered once a customer is identified (token hidden)" }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            withContext(Dispatchers.Main) { registration = "Installation could not be queued (${error.javaClass.simpleName})" }
        }
    }

    /** Optional FCM: initializes Firebase only from local, uncommitted options; otherwise degrades. */
    fun startFirebase() {
        val app = FirebaseApp.getApps(this.app).firstOrNull() ?: run {
            if (BuildConfig.FIREBASE_APPLICATION_ID.isEmpty() || BuildConfig.FIREBASE_PROJECT_ID.isEmpty()) {
                firebaseStatus = "Firebase not configured (no google-services/local options): FCM disabled, local previews work."
                return
            }
            FirebaseApp.initializeApp(this.app, FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build())
        }
        firebaseStatus = "Firebase configured (${app.name}); fetching FCM token…"
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful && task.result != null) receiveToken(task.result) else {
                tokenStatus = "FCM token request failed (check Firebase configuration). No token was logged."
            }
        }
    }

    fun receiveToken(token: String) {
        main.post {
            tokenStatus = "FCM token received (hidden)"; lastToken = token; tokenAvailable = true
            // Registered when the SDK is configured (synchronize) if it is not yet.
            client?.let { push -> scope.launch { register(push, token) } }
        }
    }

    /** Debug only. Copies the token to the clipboard; it is never logged or displayed. */
    fun copyToken(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val token = lastToken ?: return false
        val clip = android.content.ClipData.newPlainText("FCM token", token)
        // Keep the token out of the clipboard preview/overlay (API 33+ honours the flag).
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(if (android.os.Build.VERSION.SDK_INT >= 33) android.content.ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
        app.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip) ?: return false
        log("FCM token copied to the clipboard (not logged).")
        return true
    }

    fun refreshPermission() {
        permission = when {
            PushPermission.canPostNotifications(app) -> "Granted"
            PushPermission.shouldRequest(app) -> "Not granted (tap request)"
            else -> "Disabled in system settings"
        }
        // A changed permission re-registers the Installation (also re-checked on every foreground).
        client?.let { push -> scope.launch { runCatching { push.refreshPermission() } } }
    }

    /**
     * Posts a real local notification after a short delay (6 s in UI-testing mode to allow Home
     * navigation). It never contacts FCM. When it appears while the app is foreground, that is
     * reported as a foreground observation.
     */
    fun preview(withImage: Boolean) {
        refreshPermission()
        if (!PushPermission.canPostNotifications(app)) { log("Notification permission is required for previews."); return }
        val deliveryId = "preview-${UUID.randomUUID()}"
        previewDeliveryId = deliveryId
        val delayMs = if (uiTesting) 6000L else 2000L
        log("Local push preview scheduled. It does not contact FCM.")
        main.postDelayed({
            scope.launch {
                val image = if (withImage) withContext(Dispatchers.Default) { bundledImage() } else null
                if (withImage && image == null) log("Could not attach preview image; showing text.")
                val marker = PushNotification.marker(deliveryId)
                post(PushNotification.fromMarker(marker)!!, "Tranthor push preview ${deliveryId.takeLast(8)}",
                    "Local notification: tap to test the opened event.", image,
                    observe = PushInteraction.Foreground.takeIf { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) })
            }
        }, delayMs)
    }

    private fun bundledImage() = try {
        val bytes = app.resources.openRawResource(R.raw.preview_image).use { it.readBytes() }
        TranthorNotifications.decode(bytes)
    } catch (_: Exception) { null }

    /**
     * The FCM path (ExampleMessagingService) and the debug simulated-push receiver both land here. The
     * server sends data-only pushes: title and body come from `data.title` / `data.body`. Every
     * arrival, foreground or background, is reported as Push Received.
     */
    fun onRemoteMessage(data: Map<String, String?>) {
        val content = PushContent.fromData(data) ?: return
        val notification = content.notification
        scope.launch {
            // Remote images need an explicit HTTPS CDN allowlist; the example's is empty (text only).
            val image = withContext(Dispatchers.IO) { TranthorNotifications.loadImage(notification, imageConfiguration.first) }
            post(notification, content.title ?: "Tranthor push", content.body ?: "Simulated or FCM notification", image,
                observe = PushInteraction.Received)
        }
    }

    private suspend fun post(notification: PushNotification, title: String, body: String, image: android.graphics.Bitmap?,
                             observe: PushInteraction?) {
        TranthorNotifications.ensureChannel(app, CHANNEL, app.getString(R.string.push_channel_name))
        val id = notificationIds.incrementAndGet()
        val launch = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val built = TranthorNotifications.build(app, CHANNEL, R.drawable.ic_notification, title, body,
            TranthorNotifications.openIntent(app, launch, notification, id),
            TranthorNotifications.dismissIntent(app, NotificationDismissReceiver::class.java, notification, id), image)
        try {
            app.getSystemService(NotificationManager::class.java).notify(id, built)
        } catch (_: SecurityException) { log("Notification permission was revoked."); return }
        observe?.let { receive(notification, it) }
    }

    /** Invoked after the host handled the system callback. Presentation remains host-owned. */
    suspend fun receive(payload: PushNotification?, interaction: PushInteraction) {
        payload ?: return
        val push = client ?: run {
            pendingLaunchObservations.addLast(payload to interaction)
            while (pendingLaunchObservations.size > 32) pendingLaunchObservations.removeFirst()
            return
        }
        if (!enabled) return
        try { if (push.handle(payload, interaction)) record(payload, interaction) } catch (error: Exception) {
            if (error is CancellationException) throw error
            log("Push observation could not be admitted to the events SDK.")
        }
    }

    /** Runs after the SDK handled the observation; only updates the screen. A repeat of the same one is not counted. */
    private suspend fun record(payload: PushNotification, interaction: PushInteraction) {
        // A foreground observation is the same receipt as a received one, as the SDK reports it.
        val observed = if (interaction == PushInteraction.Foreground) PushInteraction.Received else interaction
        if (!enabled || !recorded.add("${payload.deliveryId}|${observed}")) return
        withContext(Dispatchers.Main) {
            observations++
            lastObservation = when (observed) {
                PushInteraction.Foreground, PushInteraction.Received -> "Notification received"
                is PushInteraction.Opened -> "Notification opened"
                PushInteraction.Dismissed -> "Notification dismissed"
            }
            onLog?.invoke("$lastObservation; ${eventName(observed)} queued by the SDK.")
        }
    }

    private fun eventName(interaction: PushInteraction) = when (interaction) {
        PushInteraction.Foreground, PushInteraction.Received -> "Push Received"
        is PushInteraction.Opened -> "Push Opened"
        PushInteraction.Dismissed -> "Push Dismissed"
    }

    companion object {
        const val CHANNEL = "tranthor_previews"
        /**
         * Exact publisher-controlled CDN hosts from local.properties `tranthor.example.imageHosts`
         * (validated by RichImageConfiguration). Empty: remote images are rejected (text only).
         */
        val imageConfiguration: Pair<RichImageConfiguration?, String> by lazy {
            val hosts = BuildConfig.IMAGE_HOSTS.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            when {
                hosts.isEmpty() -> null to "Remote images disabled (no tranthor.example.imageHosts)."
                else -> try {
                    RichImageConfiguration(hosts) to "Remote images allowed from: ${hosts.joinToString()}"
                } catch (_: Exception) { null to "Invalid tranthor.example.imageHosts; remote images disabled." }
            }
        }
    }
}
