package com.tranthor.example.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tranthor.example.ExampleApplication
import com.tranthor.push.PushInteraction
import com.tranthor.push.PushNotification
import kotlinx.coroutines.launch

/**
 * DEBUG BUILDS ONLY. Simulates FCM for scripts/send-emulator-push.sh. It bypasses FCM, Google Play
 * services, credentials and any server: it cannot prove real remote delivery. Guarded by the DUMP
 * permission so only the shell (adb) or system can send it.
 *
 * - default (`interaction` absent): an FCM data message arriving. Feeds the same handler as the
 *   app's FirebaseMessagingService (posts the notification, downloading `imageUrl` through the real
 *   allowlisted rich-image path) and reports Push Received.
 * - `interaction=opened|dismissed`: simulates the user tapping/dismissing a Tranthor notification by
 *   reporting the observation directly. Real taps are verified intents (nonce) that adb cannot forge.
 */
class SimulatedPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val marker = intent.getStringExtra(PushNotification.DATA_KEY) ?: return
        val application = context.applicationContext as ExampleApplication
        val push = application.modelOrDefault().push
        when (intent.getStringExtra("interaction")) {
            "opened", "dismissed" -> {
                val notification = PushNotification.fromMarker(marker) ?: return
                val interaction = if (intent.getStringExtra("interaction") == "opened") PushInteraction.Opened() else PushInteraction.Dismissed
                val pending = goAsync()
                application.scope.launch { try { push.receive(notification, interaction) } finally { pending.finish() } }
            }
            else -> {
                val data = mapOf(PushNotification.DATA_KEY to marker, "title" to intent.getStringExtra("title"), "body" to intent.getStringExtra("body"))
                push.onRemoteMessage(data)
            }
        }
    }
}
