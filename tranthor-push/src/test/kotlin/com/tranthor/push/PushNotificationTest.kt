package com.tranthor.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only a well-formed version 1 Tranthor marker is ours; everything else belongs to the host app. */
class PushNotificationTest {
    @Test fun payloadOwnershipRejectsOtherVendorsAndWrongVersions() {
        val invalid = listOf<Map<String, String?>>(
            emptyMap(), mapOf("aps" to "{}"), mapOf("tranthor" to null), mapOf("tranthor" to "not-json"),
            mapOf("tranthor" to """{"version":true,"deliveryId":"x"}"""), mapOf("tranthor" to """{"version":"1","deliveryId":"x"}"""),
            mapOf("tranthor" to """{"version":2,"deliveryId":"x"}"""), mapOf("tranthor" to """{"version":1,"deliveryId":" "}"""),
            mapOf("tranthor" to """{"version":1,"deliveryId":"${"x".repeat(129)}"}"""),
            mapOf("tranthor" to """{"version":1,"deliveryId":"line\nbreak"}"""),
            mapOf("tranthor" to """{"version":1,"deliveryId":"user@example.com"}"""),
            mapOf("tranthor" to """{"version":1,"deliveryId":"é"}"""),
            mapOf("tranthor" to """{"version":1,"deliveryId":1}"""),
            mapOf("tranthor" to """{"version":1,"deliveryId":"x","imageUrl":5}"""),
            mapOf("tranthor" to """["version",1]"""),
        )
        for (payload in invalid) assertNull(payload.toString(), PushNotification.fromData(payload))
        val valid = PushNotification.fromData(mapOf("tranthor" to """{"version":1,"deliveryId":"valid","imageUrl":"https://img.example/a.png"}""", "title" to "Hi"))
        assertEquals("valid", valid?.deliveryId); assertEquals("https://img.example/a.png", valid?.imageUrl)
        assertEquals("x".repeat(128), PushNotification.fromData(mapOf("tranthor" to """{"version":1.0,"deliveryId":"${"x".repeat(128)}"}"""))?.deliveryId)
        assertEquals(PushNotification.fromMarker(PushNotification.marker("a.b_c:d-1", "https://x.example/i.png")),
            PushNotification.fromData(mapOf("tranthor" to """{"deliveryId":"a.b_c:d-1","version":1,"imageUrl":"https://x.example/i.png"}""")))
    }
}
