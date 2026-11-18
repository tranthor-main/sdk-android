package com.tranthor.example

import android.os.Handler
import android.os.Looper
import com.google.firebase.messaging.RemoteMessage
import com.tranthor.push.PushNotification
import com.tranthor.push.TranthorMessagingService

/** The host-owned FCM service. Only active when Firebase is configured locally. onNewToken is final in the base class and forwards to onTranthorToken. */
@android.annotation.SuppressLint("MissingFirebaseInstanceTokenRefresh")
class ExampleMessagingService : TranthorMessagingService() {
    private val main = Handler(Looper.getMainLooper())
    private fun model() = (application as ExampleApplication).modelOrDefault()

    override fun onTranthorToken(token: String) {
        main.post { model().push.receiveToken(token) }
    }

    override fun onTranthorMessage(message: RemoteMessage, notification: PushNotification, foreground: Boolean) {
        val data = HashMap(message.data)
        main.post { model().push.onRemoteMessage(data) }
    }
}
