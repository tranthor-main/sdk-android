package com.tranthor.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The data-only FCM message the Tranthor server sends. */
class PushContentTest {
    @Test fun dataOnlyServerMessageIsShownFromTitleBodyChannelAndImage() {
        val content = PushContent.fromData(mapOf(
            "tranthor" to """{"version":1,"deliveryId":"d-1","imageUrl":"https://cdn.example/a.png"}""",
            "title" to "Sale", "body" to "Today only", "channelId" to "promotions"))!!
        assertEquals("d-1", content.notification.deliveryId)
        assertEquals("https://cdn.example/a.png", content.notification.imageUrl)
        assertEquals("Sale", content.title)
        assertEquals("Today only", content.body)
        assertEquals("promotions", content.channelId)
    }

    @Test fun serverOmitsEmptyFieldsAndMayMarkATestSend() {
        val content = PushContent.fromData(mapOf("tranthor" to """{"version":1,"deliveryId":"d-2","test":true}"""))!!
        assertEquals("d-2", content.notification.deliveryId)
        assertNull(content.title); assertNull(content.body); assertNull(content.channelId); assertNull(content.notification.imageUrl)
        assertNull(PushContent.fromData(mapOf("tranthor" to """{"version":1,"deliveryId":"d-3"}""", "channelId" to " "))!!.channelId)
    }

    @Test fun messagesWithoutAValidMarkerAreLeftToTheHost() {
        assertNull(PushContent.fromData(mapOf("title" to "Other SDK", "body" to "x")))
        assertNull(PushContent.fromData(mapOf("tranthor" to """{"version":2,"deliveryId":"d"}""", "title" to "x")))
    }

    @Test fun theTemplatesTapLinkAndDataEntriesAreHandedToTheApp() {
        val content = PushContent.fromData(mapOf(
            "tranthor" to """{"version":1,"deliveryId":"d-4"}""",
            "title" to "Your order shipped", "body" to "It arrives Friday.", "channelId" to "orders",
            "url" to "shop://orders/42", "orderId" to "42", "tier" to "gold",
            // What FCM and the server reserve is never a data entry.
            "from" to "1234567890", "message_type" to "gcm", "google.sent_time" to "1", "gcm.n.e" to "1"))!!
        assertEquals("shop://orders/42", content.url)
        assertEquals(mapOf("orderId" to "42", "tier" to "gold"), content.data)
    }

    @Test fun aPushWithoutALinkOrDataEntriesHasNone() {
        val content = PushContent.fromData(mapOf("tranthor" to """{"version":1,"deliveryId":"d-5"}""", "title" to "Hi", "url" to ""))!!
        assertNull(content.url)
        assertEquals(emptyMap<String, String>(), content.data)
    }
}
