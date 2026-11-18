package com.tranthor.push

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val SDK_WORK_TIMEOUT_MS = 15_000L

internal object TranthorFirebaseMessaging {
    /** True while some activity is visible; data messages also arrive in the background. */
    fun isAppInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

/**
 * Optional ready-made subclass. Declare your subclass in the manifest with the
 * `com.google.firebase.MESSAGING_EVENT` intent filter. Unrelated messages are passed to
 * [onOtherMessage] so another SDK can handle them.
 *
 * Override [tranthorPush] (and [notificationStyle]) and the service does the Tranthor work itself:
 * it forwards every token, displays each data-only Tranthor push and reports `Push Received`
 * through the SDK's durable queue, in the foreground or background.
 */
public abstract class TranthorMessagingService : FirebaseMessagingService() {
    /**
     * The app's [TranthorPush] bound to its [com.tranthor.sdk.TranthorSDK], awaiting configuration
     * when the service runs before the app finished it (a push can start a cold process). Null (the
     * default) leaves tokens and messages to [onTranthorToken] and [onTranthorMessage].
     */
    protected open suspend fun tranthorPush(): TranthorPush? = null

    /** Lets the service display Tranthor pushes itself with [TranthorNotifications.show]. Null (the default) leaves display to [onTranthorMessage]. */
    protected open fun notificationStyle(): TranthorNotificationStyle? = null

    /** Called for every token. Without [tranthorPush], forward to [TranthorPush.registerDeviceToken] yourself. */
    protected open fun onTranthorToken(token: String) {}

    /** A recognized Tranthor message, after the SDK's own handling. [foreground] tells whether the app was visible. */
    protected open fun onTranthorMessage(message: RemoteMessage, notification: PushNotification, foreground: Boolean) {}

    /** Messages without a recognized marker. */
    protected open fun onOtherMessage(message: RemoteMessage) {}

    final override fun onNewToken(token: String) {
        onTranthorToken(token)
        sdkWork { tranthorPush()?.registerDeviceToken(token) }
    }

    final override fun onMessageReceived(message: RemoteMessage) {
        val content = PushContent.fromData(message.data)
        if (content == null) { onOtherMessage(message); return }
        val foreground = TranthorFirebaseMessaging.isAppInForeground()
        sdkWork {
            // Await the SDK first: a push can start a cold process, and a host's style may only be ready once its
            // SDK is configured. A failing tranthorPush() still lets the push be shown.
            val push = try { tranthorPush() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
            notificationStyle()?.let { TranthorNotifications.show(this@TranthorMessagingService, content, it) }
            push?.handle(content.notification, PushInteraction.Received)
        }
        onTranthorMessage(message, content.notification, foreground)
    }

    /**
     * FCM calls the service on a background thread and allows it roughly 10-20 seconds; the SDK
     * work (an image download, then one durable queue write) is bounded well inside that.
     */
    private fun sdkWork(block: suspend () -> Unit) {
        try {
            runBlocking { withTimeoutOrNull(SDK_WORK_TIMEOUT_MS) { block() } }
        } catch (error: Exception) {
            // A full disk or a stopped SDK must not crash the host's messaging service.
        }
    }
}
