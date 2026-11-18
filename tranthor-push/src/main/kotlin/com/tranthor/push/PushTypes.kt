package com.tranthor.push

import android.os.Bundle
import com.tranthor.sdk.JsonValue
import java.security.MessageDigest

/** Local validation errors. */
public sealed class PushException(message: String) : Exception(message) {
    public class InvalidApplicationId : PushException("Invalid application ID")
    public class EmptyDeviceToken : PushException("Empty device token")
}

/**
 * A Tranthor push, read from the JSON string under the `tranthor` data key (`version` 1, a `deliveryId`
 * and optionally an `imageUrl`). Any other message belongs to the host.
 */
public class PushNotification private constructor(
    public val deliveryId: String,
    /** Optional rich image URL; validated against the allowlist only when downloading. */
    public val imageUrl: String?,
) {
    override fun equals(other: Any?): Boolean = other is PushNotification && other.deliveryId == deliveryId && other.imageUrl == imageUrl
    override fun hashCode(): Int = deliveryId.hashCode()
    override fun toString(): String = "PushNotification(deliveryId=$deliveryId)"

    public companion object {
        /** The FCM data key (and notification-tap intent extra) carrying the JSON marker string. */
        public const val DATA_KEY: String = "tranthor"
        private const val MAX_MARKER_BYTES = 8192

        /** From FCM `RemoteMessage.data` (or any string map). Returns null for unrelated/malformed payloads. */
        @JvmStatic
        public fun fromData(data: Map<String, String?>): PushNotification? = fromMarker(data[DATA_KEY])

        /** From notification-tap intent extras, where FCM copies data keys as string extras. */
        @JvmStatic
        public fun fromExtras(extras: Bundle?): PushNotification? = fromMarker(extras?.getString(DATA_KEY))

        /** Parses the marker JSON string itself. */
        @JvmStatic
        public fun fromMarker(marker: String?): PushNotification? {
            if (marker == null || marker.length > MAX_MARKER_BYTES) return null
            val root = try { JsonValue.parse(marker).objectValue } catch (_: IllegalArgumentException) { null } ?: return null
            // Booleans and strings are rejected: only the JSON number 1.
            if ((root["version"] as? JsonValue.NumberValue)?.value != 1.0) return null
            val id = (root["deliveryId"] as? JsonValue.StringValue)?.value ?: return null
            if (!isValidDeliveryId(id)) return null
            val image = when (val value = root["imageUrl"]) {
                null, JsonValue.Null -> null
                is JsonValue.StringValue -> value.value
                else -> return null
            }
            return PushNotification(id, image)
        }

        internal fun isValidDeliveryId(id: String): Boolean =
            id.isNotBlank() && id.toByteArray(Charsets.UTF_8).size <= 128 &&
                id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == ':' || it == '-' }

        /** Builds a marker string for local previews and tests. */
        @JvmStatic
        @JvmOverloads
        public fun marker(deliveryId: String, imageUrl: String? = null): String {
            require(isValidDeliveryId(deliveryId)) { "Invalid delivery ID" }
            val map = linkedMapOf<String, Any?>("version" to 1, "deliveryId" to deliveryId)
            if (imageUrl != null) map["imageUrl"] = imageUrl
            return JsonValue.of(map).toJson()
        }
    }
}

/** What happened to a notification. */
public sealed class PushInteraction {
    /** A notification observed while the app's process was in the foreground. Reported as `Push Received`. */
    public object Foreground : PushInteraction() { override fun toString(): String = "foreground" }
    /**
     * A Tranthor message reached the app, in the foreground or background (wherever the messaging
     * service runs). Reported as `Push Received`.
     */
    public object Received : PushInteraction() { override fun toString(): String = "received" }
    /** The user tapped the notification or one of its actions. */
    public data class Opened(val actionIdentifier: String = DEFAULT_ACTION) : PushInteraction()
    /** The user swiped the notification away (delete intent). Never counted as opened. */
    public object Dismissed : PushInteraction() { override fun toString(): String = "dismissed" }

    internal val key: String
        get() = when (this) {
            Foreground -> "foreground"
            Received -> "received"
            is Opened -> "opened:$actionIdentifier"
            Dismissed -> "dismissed"
        }

    public companion object {
        /** The identifier of a tap on the notification body. */
        public const val DEFAULT_ACTION: String = "default"
    }
}

/**
 * A deterministic UUIDv8 (SHA-256 derived, upper case) of a delivery ID and an interaction key, so a
 * redelivered observation carries the same `messageId` and the server drops the duplicate.
 */
internal fun stableMessageId(deliveryId: String, interactionKey: String): String {
    val input = "TranthorPush:v1:${deliveryId.toByteArray(Charsets.UTF_8).size}:$deliveryId:$interactionKey"
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { "%02X".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

/**
 * @property applicationId the app's package/application ID (reverse-DNS), part of each Installation.
 * @property recentEventLimit how many recent observations are remembered to drop duplicates, clamped
 * to 1 to 10,000.
 */
public class PushConfiguration @JvmOverloads constructor(
    public val applicationId: String,
    recentEventLimit: Int = 1024,
) {
    public val recentEventLimit: Int = recentEventLimit.coerceIn(1, 10_000)
}

/**
 * A verified tap or swipe on a Tranthor notification ([TranthorNotifications.tapFrom]): what to report
 * with [TranthorPush.handle], and what the push asked the app to open.
 *
 * @property url the template's "When tapped, open" link, or null. Check it before opening it.
 * @property data the template's data entries.
 */
public class PushTap internal constructor(
    public val notification: PushNotification,
    public val interaction: PushInteraction,
    public val url: String?,
    public val data: Map<String, String>,
) {
    override fun toString(): String = "PushTap(deliveryId=${notification.deliveryId}, interaction=$interaction)"
}

/**
 * What a data-only Tranthor push asks the app to show: the server sends no FCM
 * `notification` block, only `data.tranthor` (the marker), optional `data.title`, `data.body` and
 * `data.channelId`.
 */
public class PushContent private constructor(
    public val notification: PushNotification,
    public val title: String?,
    public val body: String?,
    /** The app's notification channel to use when it exists; otherwise the SDK's default channel. */
    public val channelId: String?,
    /**
     * The template's "When tapped, open" link (`data.url`), a web URL or an app link, or null. It
     * comes from a push: check it against the links your app accepts before opening it. The SDK
     * never opens it.
     */
    public val url: String?,
    /** The template's data entries: every other data key the server and FCM do not reserve. */
    public val data: Map<String, String>,
) {
    override fun toString(): String = "PushContent(deliveryId=${notification.deliveryId})"

    public companion object {
        /** Null for messages without a valid Tranthor marker: those belong to the host app. */
        @JvmStatic
        public fun fromData(data: Map<String, String?>): PushContent? {
            val notification = PushNotification.fromData(data) ?: return null
            val entries = LinkedHashMap<String, String>()
            for ((key, value) in data) if (value != null && !isReserved(key)) entries[key] = value
            return PushContent(notification, data["title"]?.takeIf { it.isNotEmpty() }, data["body"]?.takeIf { it.isNotEmpty() },
                data["channelId"]?.takeIf { it.isNotBlank() }, data["url"]?.takeIf { it.isNotBlank() }, entries)
        }

        /** Keys that carry the message itself or belong to FCM, so they are not template data entries. */
        private val reservedKeys = setOf(PushNotification.DATA_KEY, "title", "body", "channelId", "url", "from", "message_type")
        private fun isReserved(key: String): Boolean = key in reservedKeys || key.startsWith("google.") || key.startsWith("gcm.")
    }
}
