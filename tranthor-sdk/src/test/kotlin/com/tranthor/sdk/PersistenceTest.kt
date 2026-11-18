package com.tranthor.sdk

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/** State and consent files: round trips, recovery from damage, quarantine, and fail-closed consent. */
class PersistenceTest {
    private fun root() = tempRoot("tranthor-persistence")
    private fun JsonStore.write(text: String, file: File = stateFile) = file.writeText(text)

    @Test fun missingActiveFieldPreservesIdentityQueueAndConsent() {
        val root = root()
        try {
            val store = JsonStore(root, "schema")
            val anonymous = UUID.randomUUID().toString()
            store.write("""{"anonymousId":"$anonymous","userId":"u1","optedOut":true,"queued":[{"type":"track","event":"kept","messageId":"stable-id","timestamp":"stable-time","userId":"u1"}]}""")
            val restored = store.load()
            assertTrue(restored.optedOut)
            assertEquals(anonymous, restored.anonymousId)
            assertEquals("u1", restored.userId)
            assertEquals(1, store.legacyEvents.size)
            assertEquals("stable-id", store.legacyEvents.first().value["messageId"]?.stringValue)
            assertEquals("stable-time", store.legacyEvents.first().value["timestamp"]?.stringValue)
            assertFalse(store.corruptFile.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun missingFieldsUseDefaultsWithoutRegeneratingValidIdentity() {
        val root = root()
        try {
            val store = JsonStore(root, "defaults")
            val anonymous = UUID.randomUUID().toString()
            store.write("""{"anonymousId":"$anonymous"}""")
            store.write("""{"version":1,"optedOut":false}""", store.consentFile)
            val restored = store.load()
            assertEquals(anonymous, restored.anonymousId)
            assertNull(restored.userId)
            assertTrue(store.legacyEvents.isEmpty())
            assertFalse(restored.optedOut)
            store.save(restored)
            assertEquals(2.0, JsonValue.parse(store.stateFile.readText())["version"]?.numberValue)
        } finally { root.deleteRecursively() }
    }

    @Test fun truncatedStateFailsClosedAndIsQuarantined() {
        val root = root()
        try {
            val store = JsonStore(root, "truncated")
            store.save(StoredState(optedOut = true))
            val original = store.stateFile.readBytes()
            val truncated = original.copyOf(original.size / 2)
            store.stateFile.writeBytes(truncated)
            val restored = store.load()
            assertTrue(restored.optedOut)
            assertTrue(isUuid(restored.anonymousId))
            assertTrue(truncated.contentEquals(store.corruptFile.readBytes()))
            assertFalse(store.stateFile.exists())
            store.save(restored)
            assertTrue(truncated.contentEquals(store.corruptFile.readBytes()))
            assertTrue(store.load().optedOut)
        } finally { root.deleteRecursively() }
    }

    @Test fun malformedQueueSalvagesIndependentIdentityAndHonoursConsent() {
        val root = root()
        try {
            val store = JsonStore(root, "salvage")
            val anonymous = UUID.randomUUID().toString()
            store.write("""{"version":1,"anonymousId":"$anonymous","userId":"salvaged-user","queued":"not-an-array"}""")
            store.write("""{"version":1,"optedOut":false}""", store.consentFile)
            val original = store.stateFile.readBytes()
            val restored = store.load()
            assertFalse(restored.optedOut)
            assertEquals(anonymous, restored.anonymousId)
            assertEquals("salvaged-user", restored.userId)
            assertTrue(store.legacyEvents.isEmpty())
            assertTrue(original.contentEquals(store.corruptFile.readBytes()))
        } finally { root.deleteRecursively() }
    }

    @Test fun existingStateWithoutSeparateConsentFailsClosed() {
        val root = root()
        try {
            val store = JsonStore(root, "missing-consent")
            val state = StoredState()
            store.save(state)
            val restored = store.load()
            assertTrue(restored.optedOut)
            assertEquals(state.anonymousId, restored.anonymousId)
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptConsentFailsClosedAndIsNotOverwrittenByStateSave() {
        val root = root()
        try {
            val store = JsonStore(root, "corrupt-consent")
            store.save(StoredState())
            val damaged = "{\"optedOut\":".toByteArray()
            store.consentFile.writeBytes(damaged)
            assertTrue(store.load().optedOut)
            store.save(StoredState())
            assertTrue(damaged.contentEquals(store.consentCorruptFile.readBytes()))
            assertTrue(store.load().optedOut)
        } finally { root.deleteRecursively() }
    }

    @Test fun stateSaveDoesNotImplicitlyOptIn() {
        val root = root()
        try {
            val store = JsonStore(root, "independent-consent")
            store.write("""{"version":1,"optedOut":true}""", store.consentFile)
            val original = store.consentFile.readBytes()
            store.save(StoredState())
            assertTrue(original.contentEquals(store.consentFile.readBytes()))
            assertTrue(store.load().optedOut)
        } finally { root.deleteRecursively() }
    }

    @Test fun unknownSchemaVersionIsQuarantinedAndFailsClosed() {
        val root = root()
        try {
            val store = JsonStore(root, "future-version")
            val anonymous = UUID.randomUUID().toString()
            store.write("""{"version":999,"anonymousId":"$anonymous","optedOut":false,"queued":[],"active":[]}""")
            store.write("""{"version":1,"optedOut":false}""", store.consentFile)
            val restored = store.load()
            assertTrue("Downgrades must fail closed even with previously valid opt-in consent", restored.optedOut)
            assertEquals(anonymous, restored.anonymousId)
            assertTrue(store.corruptFile.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun onlyBrandNewStoreDefaultsOptedIn() {
        val root = root()
        try { assertFalse(JsonStore(root, "new").load().optedOut) } finally { root.deleteRecursively() }
    }

    @Test fun consentOnlyHonouredAndQuarantineWithoutConsentFailsClosed() {
        val root = root()
        try {
            val store = JsonStore(root, "interrupted-repair")
            store.write("""{"version":1,"optedOut":false}""", store.consentFile)
            assertFalse(store.load().optedOut)
            store.consentFile.delete()
            store.corruptFile.writeText("{damaged")
            assertTrue(store.load().optedOut)
        } finally { root.deleteRecursively() }
    }

    @Test fun wrongShapeStateHonoursPersistedOptIn() {
        val root = root()
        try {
            val store = JsonStore(root, "wrong-shape")
            store.write("""{"version":1,"optedOut":false}""", store.consentFile)
            for (damaged in listOf("not-json", "{}", "[]", "{\"queued\":[]}", "{\"anonymousId\":false}", "{\"anonymousId\":\"invalid\"}")) {
                store.stateFile.writeText(damaged)
                val restored = store.load()
                assertFalse(damaged, restored.optedOut)
                assertTrue(isUuid(restored.anonymousId))
                assertEquals(damaged, store.corruptFile.readText())
                assertFalse(store.stateFile.exists())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun longNamespaceHasStableBoundedDistinctPath() {
        val root = root()
        try {
            val namespace = "a".repeat(1000)
            val first = JsonStore(root, namespace)
            val second = JsonStore(root, namespace)
            val distinct = JsonStore(root, namespace + "b")
            assertEquals(first.stateFile, second.stateFile)
            assertTrue(first.stateFile != distinct.stateFile)
            // Independently known SHA-256 vector for 1,000 ASCII 'a' bytes.
            assertEquals("sha256-41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3", first.directory.name)
            first.save(StoredState())
            assertTrue(first.directory.name.length < 255)
            // Short namespaces use a hex encoding; path traversal is impossible.
            assertEquals("2e2e2f2e2e2f657363617065", JsonStore(root, "../../escape").directory.name)
            assertEquals("empty", JsonStore(root, "").directory.name)
        } finally { root.deleteRecursively() }
    }

    @Test fun explicitOptInRecoversAfterCorruptionWithoutDestroyingEvidence() {
        val root = root()
        try {
            val store = JsonStore(root, "recovery")
            store.stateFile.writeText("{damaged")
            val restored = store.load()
            assertTrue(restored.optedOut)
            store.saveConsent(restored.optedOut); store.save(restored)
            assertTrue(store.load().optedOut)
            store.saveConsent(false)
            restored.optedOut = false
            store.save(restored)
            assertFalse(store.load().optedOut)
            assertEquals("{damaged", store.corruptFile.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun atomicWriteLeavesNoTemporaryFilesAndReplacesContent() {
        val root = root()
        try {
            val store = JsonStore(root, "atomic")
            repeat(20) { store.save(StoredState(userId = "u$it")) }
            assertEquals(listOf("state.json"), store.directory.list()!!.sorted())
            assertEquals("u19", store.load().userId)
        } finally { root.deleteRecursively() }
    }
}

/** A store that cannot be read or written fails configuration closed instead of resetting consent or identity. */
class StoreAvailabilityTest {
    private fun root() = tempRoot("store-availability")
    private fun configuration(root: File) = Configuration("fixture", host = "http://127.0.0.1:1", batchSize = 1_000_000,
        flushIntervalMs = 3_600_000, automaticContext = false, storageDirectory = root)
    private fun assertUnavailable(error: Throwable, causeType: Class<*>? = null) {
        assertTrue("Expected recoverable store error, got $error", error is TranthorException.StoreTemporarilyUnavailable)
        if (causeType != null) assertTrue("cause ${error.cause}", causeType.isInstance(error.cause))
    }
    private fun posixPermissionsEnforced(root: File): Boolean {
        // Root (CI containers) ignores chmod; those assertions cannot be meaningful there.
        root.mkdirs()
        val probe = File(root, "probe").apply { writeText("x"); setReadable(false, false) }
        val enforced = !probe.canRead()
        probe.setReadable(true, false); probe.delete()
        return enforced
    }

    /** An unreadable state file must not become a first run (consent failing open). */
    @Test fun unreadableStateThrowsTwiceWithoutQuarantineOrConsentChangesThenRecovers() = runBlocking {
        val root = root()
        try {
            assumeTrue(posixPermissionsEnforced(root))
            val first = configureJvm(configuration(root), NoopTransport)
            first.identify("u1")
            for (event in listOf("a", "b", "c")) first.track(event)
            val anonymous = first.anonymousId; first.shutdown()
            val store = JsonStore(root, "default")
            val original = store.stateFile.readBytes(); val consent = store.consentFile.readBytes()
            store.stateFile.setReadable(false, false)
            try {
                repeat(2) {
                    try { configureJvm(configuration(root), NoopTransport).shutdown(); fail("Unreadable state must stop configure") }
                    catch (error: TranthorException) { assertUnavailable(error) }
                    assertFalse(store.corruptFile.exists())
                    assertTrue(consent.contentEquals(store.consentFile.readBytes()))
                }
            } finally { store.stateFile.setReadable(true, false) }
            assertTrue(original.contentEquals(store.stateFile.readBytes()))
            val recovered = configureJvm(configuration(root), NoopTransport)
            assertFalse(recovered.isOptedOut); assertEquals(anonymous, recovered.anonymousId); assertEquals("u1", recovered.userId)
            recovered.shutdown()
            assertEquals(listOf("a", "b", "c"), savedEventNames(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun unwritableNamespaceConfigureThrowsRecoverableErrorThenPreservesConsentIdentityAndQueue() = runBlocking {
        val root = root()
        try {
            assumeTrue(posixPermissionsEnforced(root))
            val first = configureJvm(configuration(root), NoopTransport)
            first.identify("u1"); first.track("a")
            val anonymous = first.anonymousId; first.shutdown()
            val store = JsonStore(root, "default")
            val stateBytes = store.stateFile.readBytes(); val consentBytes = store.consentFile.readBytes()
            store.directory.setWritable(false, false)
            try {
                try { configureJvm(configuration(root), NoopTransport).shutdown(); fail("Read-only namespace must fail configure") }
                catch (error: TranthorException) { assertUnavailable(error, IOException::class.java) }
            } finally { store.directory.setWritable(true, false) }
            assertTrue(stateBytes.contentEquals(store.stateFile.readBytes()))
            assertTrue(consentBytes.contentEquals(store.consentFile.readBytes()))
            val recovered = configureJvm(configuration(root), NoopTransport)
            assertFalse(recovered.isOptedOut); assertEquals(anonymous, recovered.anonymousId); assertEquals("u1", recovered.userId)
            recovered.shutdown()
            assertEquals(listOf("a"), savedEventNames(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun unreadableConsentDoesNotQuarantineDamagedStateOrOverwriteConsent() = runBlocking {
        val root = root()
        try {
            assumeTrue(posixPermissionsEnforced(root))
            val store = JsonStore(root, "default")
            store.stateFile.writeText("{broken"); store.saveConsent(true)
            val consent = store.consentFile.readBytes()
            store.consentFile.setReadable(false, false)
            try {
                try { configureJvm(configuration(root), NoopTransport).shutdown(); fail("Unreadable consent must stop configure") }
                catch (error: TranthorException) { assertUnavailable(error) }
            } finally { store.consentFile.setReadable(true, false) }
            assertEquals("{broken", store.stateFile.readText())
            assertFalse(store.corruptFile.exists())
            assertTrue(consent.contentEquals(store.consentFile.readBytes()))
        } finally { root.deleteRecursively() }
    }

    @Test fun decodableConsentWinsOverGarbageStateForBothConsentValues() = runBlocking {
        for (optedOut in listOf(false, true)) {
            val root = root()
            try {
                val store = JsonStore(root, "default")
                store.stateFile.writeText("{broken"); store.saveConsent(optedOut)
                val sdk = configureJvm(configuration(root), NoopTransport)
                assertEquals(optedOut, sdk.isOptedOut)
                sdk.shutdown(); assertEquals("{broken", store.corruptFile.readText())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun consentOnlyCrashResidueHonoursBothConsentValues() = runBlocking {
        for (optedOut in listOf(false, true)) {
            val root = root()
            try {
                val store = JsonStore(root, "default"); store.saveConsent(optedOut)
                val sdk = configureJvm(configuration(root), NoopTransport)
                assertEquals(optedOut, sdk.isOptedOut)
                sdk.shutdown(); assertEquals(optedOut, store.load().optedOut)
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun failedQuarantineMoveThrowsAndCannotOverwriteOriginal() = runBlocking {
        val root = root()
        try {
            val failingMove: (File, File) -> Unit = { _, _ -> throw IOException("EACCES") }
            val store = JsonStore(root, "default", TempRenameFileIo, moveFile = failingMove)
            store.stateFile.writeText("{broken"); store.saveConsent(false)
            val consent = store.consentFile.readBytes()
            try { store.load(); fail("load must throw") } catch (error: Exception) { assertUnavailable(error) }
            try { store.save(StoredState()); fail("save without verified load must throw") } catch (_: TranthorException.StoreTemporarilyUnavailable) {}
            try { configureJvm(configuration(root), NoopTransport, storeMove = failingMove).shutdown(); fail("configure must stop") }
            catch (error: TranthorException) { assertUnavailable(error) }
            assertEquals("{broken", store.stateFile.readText())
            assertTrue(consent.contentEquals(store.consentFile.readBytes()))
            assertFalse(store.corruptFile.exists())
            // The failed initializer also released its lease for the next successful recovery.
            configureJvm(configuration(root), NoopTransport).shutdown()
        } finally { root.deleteRecursively() }
    }

    @Test fun firstRunStateWriteFailureLeavesRecoverableConsentOnlyResidue() = runBlocking {
        val root = root()
        try {
            try {
                configureJvm(configuration(root), NoopTransport, storeWrite = { bytes, file ->
                    if (file.name == "state.json") throw IOException("EACCES")
                    TempRenameFileIo.write(file, bytes)
                }).shutdown()
                fail("Injected first-run state write must fail configure")
            } catch (error: TranthorException) { assertUnavailable(error, IOException::class.java) }
            val store = JsonStore(root, "default")
            assertFalse(store.stateFile.exists())
            assertTrue(store.consentFile.exists())
            assertFalse(store.load().optedOut)
            val sdk = configureJvm(configuration(root), NoopTransport)
            assertFalse(sdk.isOptedOut); sdk.shutdown()
        } finally { root.deleteRecursively() }
    }

    @Test fun initialConsentAndStateWriteErrorsPreserveUnderlyingDiagnosticsAndReleaseLease() = runBlocking {
        for (filename in listOf("consent.json", "state.json")) {
            val root = root()
            try {
                try {
                    configureJvm(configuration(root), NoopTransport, storeWrite = { bytes, file ->
                        if (file.name == filename) throw IOException("ENOSPC")
                        TempRenameFileIo.write(file, bytes)
                    }).shutdown()
                    fail("Full disk must fail configure")
                } catch (error: TranthorException) {
                    assertUnavailable(error, IOException::class.java)
                    assertEquals("ENOSPC", error.cause?.message)
                }
                configureJvm(configuration(root), NoopTransport).shutdown()
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun futureStateAndConsentFailClosedUntilExplicitOptIn() = runBlocking {
        for (filename in listOf("state.json", "consent.json")) {
            val root = root()
            try {
                val store = JsonStore(root, "default")
                store.save(StoredState()); store.saveConsent(false)
                val file = File(store.directory, filename)
                file.writeText(file.readText().replace(Regex("\"version\":\\d+"), "\"version\":3"))
                val sdk = configureJvm(configuration(root), NoopTransport)
                assertTrue(sdk.isOptedOut)
                sdk.optIn(); sdk.shutdown()
                assertFalse(JsonStore(root, "default").load().optedOut)
            } finally { root.deleteRecursively() }
        }
    }

    /** Saves must not re-read or re-decode state (O(n) per admission). */
    @Test fun savesReadAndVerifyStateOnlyOnceAtLoad() = runBlocking {
        val root = root()
        try {
            val seed = JsonStore(root, "default"); seed.save(StoredState()); seed.saveConsent(false)
            val reads = ArrayList<File>()
            val sdk = configureJvm(configuration(root), NoopTransport, storeRead = { file -> synchronized(reads) { reads.add(file) }; TempRenameFileIo.read(file) })
            repeat(10) { sdk.track("e$it") }
            sdk.shutdown()
            assertEquals("Single-writer saves must not re-read state", 1, reads.count { it.name == "state.json" })
            assertEquals(1, reads.count { it.name == "consent.json" })
        } finally { root.deleteRecursively() }
    }
}
