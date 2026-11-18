package com.tranthor.example

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Holds the process-wide example state so the activity, FCM service and receivers share it. */
class ExampleApplication : Application() {
    val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        // Create/load the notification nonce off the main thread (it is committed synchronously).
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.tranthor.push.TranthorNotifications.prepare(this@ExampleApplication) }
    }
    private var current: ExampleModel? = null

    /** Created once per process from the first launch intent's (non-persisted) overrides. */
    fun model(launchIntent: Intent?): ExampleModel = current ?: run {
        val options = LaunchOptions.from(launchIntent)
        val push = ExamplePush(this, scope, options.uiTesting)
        ExampleModel(this, options, scope, push).also { current = it; push.startFirebase() }
    }

    /** For background entry points (FCM service, receivers) when no activity created the model yet. */
    fun modelOrDefault(): ExampleModel = model(null)
}
