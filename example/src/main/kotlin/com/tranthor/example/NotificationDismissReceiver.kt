package com.tranthor.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tranthor.push.TranthorNotifications
import kotlinx.coroutines.launch

/** Non-exported delete-intent receiver: reports a dismissal (never counted as opened). */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (notification, interaction) = TranthorNotifications.interactionFrom(context, intent) ?: return
        val application = context.applicationContext as ExampleApplication
        val pending = goAsync()
        application.scope.launch {
            try { application.modelOrDefault().push.receive(notification, interaction) } finally { pending.finish() }
        }
    }
}
