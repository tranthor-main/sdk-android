package com.tranthor.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal class AndroidPlatform(private val context: Context) : Platform {
    /** noBackupFilesDir is excluded from Auto Backup and device transfer. */
    override fun defaultStorageDirectory(): File = File(context.noBackupFilesDir, "TranthorSDK")
    override val fileIo: FileIo = AtomicFileIo
    override fun automaticContext(): Map<String, JsonValue> {
        val packageName = context.packageName
        var appName = ""
        var version = ""
        var build = ""
        try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            version = info.versionName ?: ""
            build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else @Suppress("DEPRECATION") info.versionCode.toString()
            appName = context.applicationInfo.loadLabel(context.packageManager).toString()
        } catch (_: PackageManager.NameNotFoundException) {
        } catch (_: RuntimeException) {
        }
        val metrics = context.resources.displayMetrics
        return jsonObjectOf(
            "library" to mapOf("name" to TranthorSDKVersion.LIBRARY_NAME, "version" to TranthorSDKVersion.CURRENT),
            "os" to mapOf("name" to "Android", "version" to (Build.VERSION.RELEASE ?: ""), "sdkInt" to Build.VERSION.SDK_INT),
            "device" to mapOf("manufacturer" to (Build.MANUFACTURER ?: ""), "model" to (Build.MODEL ?: "")),
            "app" to mapOf("name" to appName, "version" to version, "build" to build, "namespace" to packageName),
            "screen" to mapOf("width" to metrics.widthPixels, "height" to metrics.heightPixels, "density" to metrics.density.toDouble()),
            "locale" to Locale.getDefault().toLanguageTag(),
            "timezone" to TimeZone.getDefault().id,
        )
    }
    override val appVersion: String by lazy {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    }
    override val osVersion: String get() = Build.VERSION.RELEASE ?: ""
    override fun logDebug(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
    }

    override suspend fun installLifecycle(sdk: TranthorSDK): (suspend () -> Unit) {
        val hook = ProcessLifecycleHook(sdk)
        // A shutdown() may be awaited with runBlocking on main, so removal never waits for the main
        // thread: dispose() silences late events and the removal is posted.
        val remove: suspend () -> Unit = {
            hook.dispose()
            if (Looper.myLooper() == Looper.getMainLooper()) ProcessLifecycleOwner.get().lifecycle.removeObserver(hook)
            else Handler(Looper.getMainLooper()).post { ProcessLifecycleOwner.get().lifecycle.removeObserver(hook) }
        }
        try {
            onMain { ProcessLifecycleOwner.get().lifecycle.addObserver(hook) }
        } catch (error: CancellationException) {
            // Cancelled after the observer was added: nobody will hold this SDK to remove it.
            remove()
            throw error
        }
        return remove
    }

    private suspend fun onMain(block: () -> Unit) =
        runOnMain(Looper.myLooper() == Looper.getMainLooper(), { Handler(Looper.getMainLooper()).post(it) }, block)

    companion object {
        const val TAG = "TranthorSDK"
    }
}

/**
 * Runs [block] on the main thread and returns when it has run; it does not run if the caller is cancelled
 * first. An exception from [block] reaches the caller, not the looper, where it would crash the app.
 */
internal suspend fun runOnMain(onMainThread: Boolean, post: (Runnable) -> Unit, block: () -> Unit) {
    if (onMainThread) { block(); return }
    suspendCancellableCoroutine { continuation ->
        post(Runnable {
            if (!continuation.isActive) return@Runnable
            try { block() } catch (error: Throwable) { continuation.resumeWithException(error); return@Runnable }
            continuation.resume(Unit)
        })
    }
}

/**
 * android.util.AtomicFile for every read and write, so a pre-API-30 crash that leaves a partial
 * base file plus `.bak` is recovered by AtomicFile instead of being quarantined.
 */
internal object AtomicFileIo : FileIo {
    override fun read(file: File): ByteArray? {
        // FileNotFoundException covers ENOENT and EACCES alike; check existence explicitly.
        val backup = File(file.path + ".bak")
        if (!file.exists() && !backup.exists()) return null
        return AtomicFile(file).readFully()
    }
    override fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: IOException) {
            atomic.failWrite(stream)
            throw error
        } catch (error: RuntimeException) {
            atomic.failWrite(stream)
            throw error
        }
    }
    override fun move(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("Unable to move ${from.name}")
        File(from.path + ".bak").delete()
        File(from.path + ".new").delete()
    }
    override fun delete(file: File) {
        File(file.path + ".bak").delete()
        File(file.path + ".new").delete()
        if (file.exists() && !file.delete()) throw IOException("Unable to delete ${file.name}")
    }
}

/**
 * ProcessLifecycleOwner dispatches ON_PAUSE and ON_STOP back to back on the main thread. Each event gets a
 * sequence number and starts UNDISPATCHED, so the SDK's serial executor sees them in OS order.
 */
internal class ProcessLifecycleHook(private val sdk: TranthorSDK) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sequence = 0L
    @Volatile private var disposed = false

    override fun onPause(owner: LifecycleOwner) = deliver(LifecycleEvent.PAUSE)
    override fun onStop(owner: LifecycleOwner) = deliver(LifecycleEvent.STOP)
    override fun onStart(owner: LifecycleOwner) = deliver(LifecycleEvent.FOREGROUND)
    override fun onResume(owner: LifecycleOwner) = deliver(LifecycleEvent.FOREGROUND)

    private fun deliver(event: LifecycleEvent) {
        if (disposed) return
        val next = ++sequence
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!disposed) sdk.handleLifecycle(event, next)
        }
    }

    fun dispose() {
        disposed = true
        scope.cancel()
    }
}
