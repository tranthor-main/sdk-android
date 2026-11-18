package com.tranthor.push

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import com.tranthor.sdk.InstallationPermission

/**
 * Explicit, host-initiated notification permission helper. Android 13+ (API 33) requires the
 * runtime POST_NOTIFICATIONS permission; earlier versions only honour the app's notification
 * setting. Nothing here prompts automatically.
 */
public object PushPermission {
    public const val PERMISSION: String = "android.permission.POST_NOTIFICATIONS"

    @JvmStatic
    @ChecksSdkIntAtLeast(api = 33)
    public fun requiresRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= 33

    /** True when this app can currently post notifications. */
    @JvmStatic
    public fun canPostNotifications(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val enabled = if (Build.VERSION.SDK_INT >= 24) manager.areNotificationsEnabled() else true
        if (!requiresRuntimePermission()) return enabled
        return enabled && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * The permission reported with `Installation Registered`: granted or denied from the OS
     * (POST_NOTIFICATIONS on API 33+, the app's notification setting below), unknown when the OS
     * cannot tell.
     */
    @JvmStatic
    public fun state(context: Context): InstallationPermission = try {
        val manager = context.getSystemService(NotificationManager::class.java)
        when {
            manager == null -> InstallationPermission.UNKNOWN
            canPostNotifications(context) -> InstallationPermission.GRANTED
            else -> InstallationPermission.DENIED
        }
    } catch (_: RuntimeException) {
        InstallationPermission.UNKNOWN
    }

    /** True when the host should launch its permission request (Activity Result API or [request]). */
    @JvmStatic
    public fun shouldRequest(context: Context): Boolean =
        requiresRuntimePermission() && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    /** Classic request; deliver the result through the activity's onRequestPermissionsResult. */
    @JvmStatic
    public fun request(activity: Activity, requestCode: Int) {
        if (requiresRuntimePermission()) activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
    }
}

/**
 * Builds Tranthor notifications and recovers interactions from their intents. The host still owns
 * channels, presentation policy and its activities; this never opens URLs or routes deep links.
 */
public object TranthorNotifications {
    /** Intent extra naming the interaction: [INTERACTION_OPENED] or [INTERACTION_DISMISSED]. */
    public const val EXTRA_INTERACTION: String = "tranthor_interaction"
    public const val EXTRA_ACTION: String = "tranthor_action"
    public const val INTERACTION_OPENED: String = "opened"
    public const val INTERACTION_DISMISSED: String = "dismissed"

    /** Action set on the SDK's own open/dismiss intents. */
    public const val ACTION_OPEN: String = "com.tranthor.push.action.OPEN"
    public const val ACTION_DISMISS: String = "com.tranthor.push.action.DISMISS"
    /** Per-install secret carried only inside the SDK's own (immutable) PendingIntents. */
    public const val EXTRA_NONCE: String = "tranthor_nonce"
    /** The template's "When tapped, open" link on a [tapIntent]; read it with [tapFrom]. */
    public const val EXTRA_URL: String = "tranthor_url"
    /** The template's data entries on a [tapIntent], a Bundle of strings; read them with [tapFrom]. */
    public const val EXTRA_DATA: String = "tranthor_data"

    private const val PREFERENCES = "com.tranthor.push"
    private const val NONCE_KEY = "intent_nonce"

    @Volatile private var cachedNonce: String? = null

    /**
     * The per-install nonce. Created once and persisted with commit() (synchronous) so it is durable
     * before any PendingIntent carries it. The first call does a small disk write: call [prepare] from
     * a background thread at startup to keep it off the main thread.
     */
    @Synchronized
    private fun installNonce(context: Context): String {
        cachedNonce?.let { return it }
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val nonce = preferences.getString(NONCE_KEY, null) ?: java.util.UUID.randomUUID().toString().also { created ->
            if (!preferences.edit().putString(NONCE_KEY, created).commit()) throw IllegalStateException("Unable to persist the notification nonce")
        }
        cachedNonce = nonce
        return nonce
    }

    /** Loads or creates the install nonce. Call once from a background thread (it may touch disk). */
    @JvmStatic
    public fun prepare(context: Context) {
        installNonce(context)
    }

    /**
     * Recovers a notification interaction only from an intent created by [openIntent] or
     * [dismissIntent]: it must carry the SDK action and this install's secret nonce, which other
     * apps cannot read or forge (it never leaves the app's immutable PendingIntents). A relaunch from
     * Recents (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`) re-delivers the original intent and is ignored.
     * When [consume] is true the SDK extras are removed from [intent] so activity recreation cannot
     * report the same open again. Returns null otherwise, including for FCM tray notification taps
     * (see [unverifiedTrayOpenFrom]).
     */
    @JvmStatic
    @JvmOverloads
    public fun interactionFrom(context: Context, intent: Intent?, consume: Boolean = true): Pair<PushNotification, PushInteraction>? =
        tapFrom(context, intent, consume)?.let { it.notification to it.interaction }

    /**
     * Like [interactionFrom], and also hands over what the push asked the app to open: the template's
     * "When tapped, open" link and its data entries, as [tapIntent] (and so [show]) put them on the
     * intent. They are returned only for an intent the SDK created (the same action and nonce check),
     * so another app that starts the launch activity cannot supply a link. The link still comes from
     * a push: check it against the links your app accepts before opening it.
     */
    @JvmStatic
    @JvmOverloads
    public fun tapFrom(context: Context, intent: Intent?, consume: Boolean = true): PushTap? {
        if (intent == null) return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val interaction = when (intent.action) {
            ACTION_OPEN -> PushInteraction.Opened(safeString(intent, EXTRA_ACTION) ?: PushInteraction.DEFAULT_ACTION)
            ACTION_DISMISS -> PushInteraction.Dismissed
            else -> return null
        }
        val nonce = safeString(intent, EXTRA_NONCE) ?: return null
        val expected = installNonce(context)
        if (!java.security.MessageDigest.isEqual(nonce.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))) return null
        val notification = PushNotification.fromMarker(safeString(intent, PushNotification.DATA_KEY)) ?: return null
        val url = safeString(intent, EXTRA_URL)
        val data = LinkedHashMap<String, String>()
        try {
            intent.getBundleExtra(EXTRA_DATA)?.let { entries -> for (key in entries.keySet()) entries.getString(key)?.let { data[key] = it } }
        } catch (_: RuntimeException) {
            data.clear()
        }
        if (consume) {
            intent.removeExtra(PushNotification.DATA_KEY)
            intent.removeExtra(EXTRA_NONCE)
            intent.removeExtra(EXTRA_INTERACTION)
            intent.removeExtra(EXTRA_ACTION)
            intent.removeExtra(EXTRA_URL)
            intent.removeExtra(EXTRA_DATA)
        }
        return PushTap(notification, interaction, url, data)
    }

    /**
     * FCM *notification* messages tapped from the system tray reach the launcher activity with the
     * `tranthor` data key as a plain extra. The SDK cannot authenticate such an intent: any app can
     * start an exported launcher activity with the same extra, so an "opened" derived from it is
     * spoofable. Prefer data messages (the app builds the notification with [openIntent]). If you
     * opt in, call this only for intents you know came from a tray tap; Recents relaunches are ignored.
     */
    @JvmStatic
    public fun unverifiedTrayOpenFrom(intent: Intent?): Pair<PushNotification, PushInteraction>? {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val notification = PushNotification.fromMarker(safeString(intent, PushNotification.DATA_KEY)) ?: return null
        intent.removeExtra(PushNotification.DATA_KEY)
        return notification to PushInteraction.Opened(PushInteraction.DEFAULT_ACTION)
    }

    /** Reading extras unparcels the whole bundle; a foreign Parcelable in an exported entry point must not crash. */
    private fun safeString(intent: Intent, key: String): String? =
        try { intent.getStringExtra(key) } catch (_: RuntimeException) { null }

    /** An activity PendingIntent that reports an open. [launch] should target the host's activity explicitly. */
    @JvmStatic
    public fun openIntent(context: Context, launch: Intent, notification: PushNotification, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(context, requestCode, verifiedOpen(context, launch, notification),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun verifiedOpen(context: Context, launch: Intent, notification: PushNotification): Intent = Intent(launch)
        .setAction(ACTION_OPEN)
        .putExtra(PushNotification.DATA_KEY, PushNotification.marker(notification.deliveryId, notification.imageUrl))
        .putExtra(EXTRA_INTERACTION, INTERACTION_OPENED)
        .putExtra(EXTRA_NONCE, installNonce(context))

    /**
     * The intent a tap on a notification shown for [content] delivers to the launch activity: [launch]
     * with the SDK's verified-open extras and the template's "When tapped, open" link and data
     * entries, which [tapFrom] hands back. [show] uses it. Wrap it in an immutable activity
     * PendingIntent when you build the notification yourself; never send it to another app.
     */
    @JvmStatic
    public fun tapIntent(context: Context, launch: Intent, content: PushContent): Intent {
        val intent = verifiedOpen(context, launch, content.notification)
        content.url?.let { intent.putExtra(EXTRA_URL, it) }
        if (content.data.isNotEmpty()) {
            intent.putExtra(EXTRA_DATA, android.os.Bundle().apply { for ((key, value) in content.data) putString(key, value) })
        }
        return intent
    }

    /** A broadcast PendingIntent (to the host's own non-exported receiver) that reports a dismissal. */
    @JvmStatic
    public fun dismissIntent(context: Context, receiver: Class<out BroadcastReceiver>, notification: PushNotification, requestCode: Int): PendingIntent {
        val intent = Intent(context, receiver)
            .setAction(ACTION_DISMISS)
            .putExtra(PushNotification.DATA_KEY, PushNotification.marker(notification.deliveryId, notification.imageUrl))
            .putExtra(EXTRA_INTERACTION, INTERACTION_DISMISSED)
            .putExtra(EXTRA_NONCE, installNonce(context))
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Creates the channel on API 26+ (idempotent). */
    @JvmStatic
    public fun ensureChannel(context: Context, channelId: String, name: CharSequence) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    /**
     * Decodes validated image bytes, sampled so the longest side is at most [maxSide] pixels.
     * Returns null when the platform decoder rejects them (text is then preserved).
     */
    @JvmStatic
    @JvmOverloads
    public fun decode(bytes: ByteArray, maxSide: Int = 1024): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * Builds a notification with an optional big picture. Image failures (disallowed URL, HTTP or
     * MIME error, oversize, truncation, timeout, decode) always preserve the text. Blocking: call
     * from a background thread such as FirebaseMessagingService.onMessageReceived.
     */
    @JvmStatic
    @JvmOverloads
    public fun build(
        context: Context,
        channelId: String,
        smallIcon: Int,
        title: CharSequence?,
        body: CharSequence?,
        contentIntent: PendingIntent?,
        deleteIntent: PendingIntent?,
        image: Bitmap? = null,
    ): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channelId) else @Suppress("DEPRECATION") Notification.Builder(context)
        builder.setSmallIcon(smallIcon).setContentTitle(title).setContentText(body).setAutoCancel(true)
        contentIntent?.let(builder::setContentIntent)
        deleteIntent?.let(builder::setDeleteIntent)
        if (image != null) {
            builder.setLargeIcon(image)
            builder.setStyle(Notification.BigPictureStyle().bigPicture(image).setSummaryText(body))
        } else if (body != null) {
            builder.setStyle(Notification.BigTextStyle().bigText(body))
        }
        return builder.build()
    }

    /**
     * Displays a data-only Tranthor push itself: `data.title`/`data.body`, on
     * `data.channelId` when the app created that channel, else [style]'s default channel, with the
     * rich image when `imageUrl` is set and allowlisted. Taps and swipes carry the SDK's verified
     * open/dismiss intents. Blocking (image download): call from a background thread such as
     * FirebaseMessagingService.onMessageReceived. Returns false, and posts nothing, when notifications
     * cannot be posted (permission denied on Android 13+, or notifications turned off for the app).
     */
    @JvmStatic
    public fun show(context: Context, content: PushContent, style: TranthorNotificationStyle): Boolean {
        // The OS drops a notification without an error when posting is not allowed, so ask first.
        if (!PushPermission.canPostNotifications(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = content.channelId?.takeIf { Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(it) != null }
            ?: style.defaultChannelId.also { ensureChannel(context, it, style.defaultChannelName) }
        val notification = content.notification
        val id = style.notificationId(notification)
        val image = loadImage(notification, style.images)
        val built = build(context, channel, style.smallIcon, content.title, content.body,
            PendingIntent.getActivity(context, id, tapIntent(context, style.launchIntent, content),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            style.dismissReceiver?.let { dismissIntent(context, it, notification, id) }, image)
        return try {
            manager.notify(id, built)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Downloads (allowlist + bounds) and decodes the marker's image, or null on any failure. */
    @JvmStatic
    public fun loadImage(notification: PushNotification, configuration: RichImageConfiguration?): Bitmap? {
        val url = notification.imageUrl ?: return null
        if (configuration == null) return null
        return try {
            decode(RichImageDownloader(configuration).download(url).first)
        } catch (_: RichImageException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}

/**
 * How the SDK displays Tranthor pushes itself ([TranthorNotifications.show]).
 *
 * @property launchIntent an explicit intent for the activity a tap opens; forward
 * [TranthorNotifications.interactionFrom] from it to report `Push Opened`.
 * @property dismissReceiver the app's own non-exported receiver for swipes (`Push Dismissed`), or null.
 * @property images the rich-image allowlist; null shows text only.
 */
public class TranthorNotificationStyle @JvmOverloads constructor(
    public val smallIcon: Int,
    public val defaultChannelId: String,
    public val defaultChannelName: CharSequence,
    public val launchIntent: Intent,
    public val dismissReceiver: Class<out BroadcastReceiver>? = null,
    public val images: RichImageConfiguration? = null,
) {
    /** One notification per delivery: a redelivered message replaces, rather than duplicates, it. */
    internal fun notificationId(notification: PushNotification): Int = notification.deliveryId.hashCode()
}
