package com.tranthor.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A redelivered observation must carry the same `messageId`, so the server can drop the duplicate. */
class MessageIdTest {
    @Test fun interactionsGetDistinctWellFormedIds() {
        val opened = stableMessageId("unique-device-delivery", PushInteraction.Opened().key)
        val received = stableMessageId("unique-device-delivery", PushInteraction.Received.key)
        assertNotEquals(opened, received)
        assertEquals(opened, stableMessageId("unique-device-delivery", PushInteraction.Opened().key))
        assertTrue(Regex("^[0-9A-F]{8}-[0-9A-F]{4}-8[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$").matches(opened))
    }

    /** The vectors are fixed reference values of the algorithm; a change to the algorithm must change them on purpose. */
    @Test fun matchesReferenceVectorsByteForByte() {
        val vectors = javaClass.classLoader!!.getResourceAsStream("message-id-vectors.tsv")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(6, vectors.size)
        for (line in vectors) {
            val (id, action, expected) = line.split('\t')
            assertEquals(line, expected, stableMessageId(id, action))
        }
        assertEquals("2F7E720D-05D9-89F3-8AB0-00D9FAF783DD", stableMessageId("delivery-123", PushInteraction.Foreground.key))
        assertEquals("1AB91B92-E51A-870E-85ED-98EA62BDF64E", stableMessageId("delivery-123", PushInteraction.Opened().key))
        assertEquals("B18F63EE-5DB4-8A75-8190-F27FEFA3F795", stableMessageId("delivery-123", PushInteraction.Dismissed.key))
    }
}
