package com.tranthor.push

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Runs one action each time the app returns to the foreground. Installing again replaces the previous
 * action, so a host that binds for each new SDK instance does not keep every old push alive.
 */
internal class ForegroundRefresh(
    private val add: (DefaultLifecycleObserver) -> Unit,
    private val remove: (DefaultLifecycleObserver) -> Unit,
) {
    private var current: DefaultLifecycleObserver? = null

    @Synchronized
    fun install(onForeground: () -> Unit) {
        current?.let(remove)
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onForeground()
        }
        current = observer
        add(observer)
    }

    companion object {
        /** ProcessLifecycleOwner observers must be changed on the main thread. */
        val process = ForegroundRefresh(
            add = { observer -> Handler(Looper.getMainLooper()).post { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) } },
            remove = { observer -> Handler(Looper.getMainLooper()).post { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) } },
        )
    }
}
