package com.tranthor.example

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tranthor.push.TranthorNotifications
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Only intents the SDK created count as opens; Recents relaunches never replay. */
@RunWith(AndroidJUnit4::class)
class NotificationIntentTrustTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker = "{\"version\":1,\"deliveryId\":\"spoofed-by-app\"}"

    @Test fun foreignIntentWithMarkerIsNotAnOpen() {
        val forged = Intent(context, MainActivity::class.java).putExtra("tranthor", marker).putExtra("tranthor_interaction", "opened")
        assertNull(TranthorNotifications.interactionFrom(context, forged))
    }

    @Test fun forgedActionWithoutInstallNonceIsNotAnOpen() {
        val forged = Intent(context, MainActivity::class.java).setAction(TranthorNotifications.ACTION_OPEN)
            .putExtra("tranthor", marker).putExtra(TranthorNotifications.EXTRA_NONCE, "guessed")
        assertNull(TranthorNotifications.interactionFrom(context, forged))
    }

    @Test fun sdkPendingIntentIsAcceptedOnceAndConsumed() {
        val notification = com.tranthor.push.PushNotification.fromMarker("{\"version\":1,\"deliveryId\":\"own-1\"}")!!
        // Build the same intent openIntent() wraps (the PendingIntent itself cannot be unwrapped).
        val launch = Intent(context, MainActivity::class.java)
        TranthorNotifications.openIntent(context, launch, notification, 1)
        val nonce = context.getSharedPreferences("com.tranthor.push", android.content.Context.MODE_PRIVATE).getString("intent_nonce", null)!!
        val own = Intent(launch).setAction(TranthorNotifications.ACTION_OPEN)
            .putExtra("tranthor", com.tranthor.push.PushNotification.marker("own-1")).putExtra(TranthorNotifications.EXTRA_NONCE, nonce)
        val first = TranthorNotifications.interactionFrom(context, own)
        org.junit.Assert.assertEquals("own-1", first?.first?.deliveryId)
        assertNull("Consumed: activity recreation must not report it again", TranthorNotifications.interactionFrom(context, own))
        val replay = Intent(own).putExtra("tranthor", com.tranthor.push.PushNotification.marker("own-1"))
            .putExtra(TranthorNotifications.EXTRA_NONCE, nonce).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(TranthorNotifications.interactionFrom(context, replay))
    }

    @Test fun recentsRelaunchIsNotAnOpen() {
        val replay = Intent(context, MainActivity::class.java).putExtra("tranthor", marker)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(TranthorNotifications.interactionFrom(context, replay))
    }

    private val launch = Intent(context, MainActivity::class.java)
    private fun serverPush(deliveryId: String) = com.tranthor.push.PushContent.fromData(mapOf(
        "tranthor" to "{\"version\":1,\"deliveryId\":\"$deliveryId\"}", "title" to "Your order shipped",
        "url" to "shop://orders/42", "orderId" to "42"))!!

    @Test fun aTapOnAShownPushHandsTheAppTheTemplatesLinkAndDataEntriesOnce() {
        // The intent a tap on the notification the SDK shows delivers to the launch activity.
        val tapped = TranthorNotifications.tapIntent(context, launch, serverPush("own-link-1"))
        val tap = TranthorNotifications.tapFrom(context, tapped)!!
        org.junit.Assert.assertEquals("own-link-1", tap.notification.deliveryId)
        org.junit.Assert.assertEquals(com.tranthor.push.PushInteraction.Opened(), tap.interaction)
        org.junit.Assert.assertEquals("shop://orders/42", tap.url)
        org.junit.Assert.assertEquals(mapOf("orderId" to "42"), tap.data)
        assertNull("Consumed: activity recreation must not hand the link over again", TranthorNotifications.tapFrom(context, tapped))
    }

    @Test fun aLinkOnAnIntentTheSdkDidNotCreateIsNotHandedOver() {
        val real = TranthorNotifications.tapIntent(context, launch, serverPush("own-link-2"))
        // Another app starts the exported launch activity with the same extras but cannot know the install nonce.
        val forged = Intent(real).putExtra(TranthorNotifications.EXTRA_NONCE, "guessed")
        assertNull(TranthorNotifications.tapFrom(context, forged))
    }

    @Test fun reportingTheOpenStillWorksOnTheSameIntent() {
        val tapped = TranthorNotifications.tapIntent(context, launch, serverPush("own-link-3"))
        org.junit.Assert.assertEquals("own-link-3", TranthorNotifications.interactionFrom(context, tapped)?.first?.deliveryId)
    }

    /** The install nonce is durable as soon as the first SDK intent exists (commit, not async apply). */
    @Test fun nonceIsPersistedSynchronously() {
        val prefsFile = java.io.File(context.dataDir, "shared_prefs/com.tranthor.push.xml")
        context.getSharedPreferences("com.tranthor.push", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        prefsFile.delete()
        runCatching {
            val field = TranthorNotifications::class.java.getDeclaredField("cachedNonce").apply { isAccessible = true }
            field.set(TranthorNotifications, null)
        }
        val notification = com.tranthor.push.PushNotification.fromMarker(com.tranthor.push.PushNotification.marker("nonce-1"))!!
        TranthorNotifications.openIntent(context, Intent(context, MainActivity::class.java), notification, 7)
        org.junit.Assert.assertTrue("Nonce file must exist immediately after the first SDK intent", prefsFile.isFile && prefsFile.readText().contains("intent_nonce"))
    }
}
