package com.tranthor.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** identify(userId) links the stored anonymousId and rotates it when a different user identifies. */
class IdentityLinkTest {
    private fun config(root: java.io.File) = Configuration("fixture", host = "http://127.0.0.1:1", batchSize = 100,
        flushIntervalMs = 60000, storageNamespace = "identity-link", storageDirectory = root)

    private fun queued(root: java.io.File) = savedEvents(root, "identity-link")

    @Test fun firstIdentifyAfterAnonymousUseCarriesTheStoredAnonymousId() = runBlocking {
        val root = tempRoot("tranthor-link")
        try {
            val sdk = configureJvm(config(root), NoopTransport)
            val anon = sdk.anonymousId
            sdk.track("anonymous")
            sdk.identify("user-1")
            sdk.identify("user-1")
            assertEquals(anon, sdk.anonymousId)
            sdk.shutdown()
            val q = queued(root)
            assertEquals(anon, q[0]["anonymousId"]?.stringValue)
            for (index in 1..2) {
                assertEquals("user-1", q[index]["userId"]?.stringValue)
                assertEquals(anon, q[index]["anonymousId"]?.stringValue)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun identifyWithADifferentUserRotatesBeforeSending() = runBlocking {
        val root = tempRoot("tranthor-link")
        try {
            val sdk = configureJvm(config(root), NoopTransport)
            val anon = sdk.anonymousId
            sdk.identify("user-1")
            sdk.identify("user-2")
            val rotated = sdk.anonymousId
            assertNotEquals(anon, rotated)
            assertTrue(isUuid(rotated))
            sdk.shutdown()
            val q = queued(root)
            assertEquals(anon, q[0]["anonymousId"]?.stringValue)
            assertEquals("user-2", q[1]["userId"]?.stringValue)
            assertEquals(rotated, q[1]["anonymousId"]?.stringValue)
        } finally { root.deleteRecursively() }
    }

    @Test fun explicitAnonymousIdWinsAndIsNotStored() = runBlocking {
        val root = tempRoot("tranthor-link")
        try {
            val sdk = configureJvm(config(root), NoopTransport)
            val anon = sdk.anonymousId
            sdk.identify("user-1", anonymousId = "caller-1")
            assertEquals(anon, sdk.anonymousId)
            sdk.identify("user-2", anonymousId = "caller-2")
            val rotated = sdk.anonymousId
            assertNotEquals(anon, rotated)
            sdk.identify("user-2")
            sdk.shutdown()
            val q = queued(root)
            assertEquals("caller-1", q[0]["anonymousId"]?.stringValue)
            assertEquals("caller-2", q[1]["anonymousId"]?.stringValue)
            assertEquals(rotated, q[2]["anonymousId"]?.stringValue)
        } finally { root.deleteRecursively() }
    }

    @Test fun rotatedIdentityPersistsAcrossRestart() = runBlocking {
        val root = tempRoot("tranthor-link")
        try {
            val first = configureJvm(config(root), NoopTransport)
            val anon = first.anonymousId
            first.identify("user-1")
            first.identify("user-2")
            val rotated = first.anonymousId
            first.shutdown()
            assertNotEquals(anon, rotated)
            val second = configureJvm(config(root), NoopTransport)
            assertEquals(rotated, second.anonymousId)
            assertEquals("user-2", second.userId)
            second.identify("user-2")
            assertEquals(rotated, second.anonymousId)
            second.identify("user-1")
            val third = second.anonymousId
            second.shutdown()
            assertNotEquals(rotated, third)
            val q = queued(root)
            assertEquals(rotated, q[2]["anonymousId"]?.stringValue)
            assertEquals(third, q[3]["anonymousId"]?.stringValue)
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyUserIdIsNotAUserAndDoesNotDefeatRotation() = runBlocking {
        val root = tempRoot("tranthor-link")
        try {
            val sdk = configureJvm(config(root), NoopTransport)
            sdk.identify("user-a")
            val linkedToA = sdk.anonymousId
            sdk.identify("", traits = jsonObjectOf("guest" to true))
            assertEquals("user-a", sdk.userId)
            assertEquals(linkedToA, sdk.anonymousId)
            sdk.identify("user-b")
            val rotated = sdk.anonymousId
            assertNotEquals(linkedToA, rotated)
            assertEquals("user-b", sdk.userId)
            sdk.shutdown()
            val q = queued(root)
            // "" is not a user: the traits event falls back to the stored identity like identify(null).
            assertEquals("user-a", q[1]["userId"]?.stringValue)
            assertNull(q[1]["anonymousId"])
            assertEquals(rotated, q[2]["anonymousId"]?.stringValue)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectedIdentifyRestoresBothIds() = runBlocking {
        val root = tempRoot("tranthor-link")
        try {
            val sdk = configureJvm(config(root), NoopTransport)
            sdk.identify("user-1")
            val anon = sdk.anonymousId
            try {
                sdk.identify("user-2", traits = mapOf("bad" to JsonValue.NumberValue(Double.NaN)))
                fail("Expected serialization failure")
            } catch (_: TranthorException.InvalidPayload) {}
            assertEquals("user-1", sdk.userId)
            assertEquals(anon, sdk.anonymousId)
            sdk.shutdown()
            val restored = configureJvm(config(root), NoopTransport)
            assertEquals("user-1", restored.userId)
            assertEquals(anon, restored.anonymousId)
            restored.shutdown()
        } finally { root.deleteRecursively() }
    }

    @Test fun eventsAfterIdentifyCarryOnlyTheStoredUserId() = runBlocking {
        val root = tempRoot("tranthor-link")
        try {
            val sdk = configureJvm(config(root), NoopTransport)
            sdk.identify("user-1")
            sdk.track("after")
            sdk.screen("Home")
            sdk.shutdown()
            for (event in queued(root).drop(1)) {
                assertEquals("user-1", event["userId"]?.stringValue)
                assertNull(event["anonymousId"])
            }
        } finally { root.deleteRecursively() }
    }
}
