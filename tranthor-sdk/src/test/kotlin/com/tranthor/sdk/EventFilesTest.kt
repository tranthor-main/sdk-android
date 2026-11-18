package com.tranthor.sdk

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each queued event is its own small file, so the cost of an event does not depend on the length of the queue. */
class EventFilesTest {
    private fun root(): File = tempRoot("event-files")

    @Test fun bytesWrittenByOneTrackDoNotGrowWithTheQueue() = runBlocking {
        val root = root()
        try {
            val written = AtomicLong()
            val sdk = configureJvm(
                Configuration("fixture", batchSize = 1_000_000, flushIntervalMs = 3_600_000, automaticContext = false, storageDirectory = root),
                NoopTransport, storeWrite = { bytes, file -> written.addAndGet(bytes.size.toLong()); TempRenameFileIo.write(file, bytes) },
            )
            written.set(0)
            sdk.track("Opened")
            val withEmptyQueue = written.get()
            repeat(499) { sdk.track("Opened") }
            written.set(0)
            sdk.track("Opened")
            val withFullQueue = written.get()
            sdk.shutdown()
            assertTrue("one track with 0 queued wrote $withEmptyQueue bytes", withEmptyQueue in 1 until 2048)
            assertTrue("one track with 500 queued wrote $withFullQueue bytes", withFullQueue in 1 until 2048)
        } finally { root.deleteRecursively() }
    }

    private fun queueFolder(root: File) = File(JsonStore(root, "default").directory, "queue")

    private fun configuration(root: File, batchSize: Int = 100, maxQueueSize: Int = 10_000, onError: ((BatchFailure) -> Unit)? = null) =
        Configuration("fixture", batchSize = batchSize, flushIntervalMs = 3_600_000, automaticContext = false,
            storageDirectory = root, maxQueueSize = maxQueueSize, onError = onError)

    @Test fun orderAndStampsSurviveARestartIncludingABatchThatWasInFlight() = runBlocking {
        val root = root()
        try {
            val firstTransport = ManualTransport()
            val first = configureJvm(configuration(root, batchSize = 2), firstTransport)
            first.track("a", messageId = "m-a", timestamp = "2026-11-01T00:00:01.000Z")
            first.track("b", messageId = "m-b", timestamp = "2026-11-01T00:00:02.000Z")
            firstTransport.wait(1)
            first.track("c", messageId = "m-c", timestamp = "2026-11-01T00:00:03.000Z")
            val shutdown = async(Dispatchers.Default) { first.shutdown() }
            waitUntil { first.isStopped() }
            firstTransport.complete(0, DeliveryFailure(503, "down"))
            shutdown.await()
            val transport = ManualTransport()
            transport.autoSucceedFutureRequests()
            val second = configureJvm(configuration(root), transport)
            second.track("d", messageId = "m-d", timestamp = "2026-11-01T00:00:04.000Z")
            assertEquals("A new event is numbered after the restored ones", listOf("a", "b", "c", "d"), savedEventNames(root))
            second.flush()
            second.shutdown()
            val sent = transport.snapshot().flatten()
            assertEquals(listOf("a", "b", "c", "d"), sent.map { it["event"]?.stringValue })
            assertEquals(listOf("m-a", "m-b", "m-c", "m-d"), sent.map { it["messageId"]?.stringValue })
            assertEquals(listOf("2026-11-01T00:00:01.000Z", "2026-11-01T00:00:02.000Z", "2026-11-01T00:00:03.000Z", "2026-11-01T00:00:04.000Z"),
                sent.map { it["timestamp"]?.stringValue })
            assertTrue(savedEvents(root).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun anEventFileThatCannotBeDecodedIsSetAsideAndTheOthersAreStillDelivered() = runBlocking {
        val root = root()
        try {
            val first = configureJvm(configuration(root), NoopTransport)
            for (name in listOf("a", "b", "c")) first.track(name)
            first.shutdown()
            val damaged = "{\"type\":\"track\",\"event\":".toByteArray()
            File(queueFolder(root), "00000000000000000002.json").writeBytes(damaged)
            val transport = ManualTransport()
            transport.autoSucceedFutureRequests()
            val second = configureJvm(configuration(root), transport)
            second.flush()
            second.shutdown()
            assertEquals(listOf("a", "c"), transport.snapshot().flatten().map { it["event"]?.stringValue })
            assertEquals(listOf("00000000000000000002.corrupt"), queueFolder(root).list()!!.toList())
            assertTrue(damaged.contentEquals(File(queueFolder(root), "00000000000000000002.corrupt").readBytes()))
        } finally { root.deleteRecursively() }
    }

    @Test fun anEventFileThatCannotBeReadStopsConfigureAndStaysInPlaceUnderItsName() = runBlocking {
        val root = root()
        try {
            val first = configureJvm(configuration(root), NoopTransport)
            for (name in listOf("a", "b", "c")) first.track(name)
            first.shutdown()
            val files = listOf("00000000000000000001.json", "00000000000000000002.json", "00000000000000000003.json")
            try {
                configureJvm(configuration(root), NoopTransport, storeRead = { file ->
                    if (file.name == "00000000000000000002.json") throw java.io.IOException("EIO")
                    TempRenameFileIo.read(file)
                }).shutdown()
                org.junit.Assert.fail("An unreadable event file must stop configure")
            } catch (_: TranthorException.StoreTemporarilyUnavailable) {}
            assertEquals(files, queueFolder(root).list()!!.sorted())
            val transport = ManualTransport()
            transport.autoSucceedFutureRequests()
            val second = configureJvm(configuration(root), transport)
            second.flush()
            second.shutdown()
            assertEquals(listOf("a", "b", "c"), transport.snapshot().flatten().map { it["event"]?.stringValue })
        } finally { root.deleteRecursively() }
    }

    @Test fun theNumberOfAFileSetAsideIsNeverReused() = runBlocking {
        val root = root()
        try {
            val first = configureJvm(configuration(root), NoopTransport)
            for (name in listOf("a", "b", "c")) first.track(name)
            first.shutdown()
            val damaged = "{\"type\":\"track\",\"event\":".toByteArray()
            File(queueFolder(root), "00000000000000000003.json").writeBytes(damaged)
            // The first restart sets the damaged file aside; the next one finds only the files before it.
            configureJvm(configuration(root), NoopTransport).shutdown()
            val third = configureJvm(configuration(root), NoopTransport)
            third.track("d")
            third.shutdown()
            assertEquals(
                listOf("00000000000000000001.json", "00000000000000000002.json", "00000000000000000003.corrupt", "00000000000000000004.json"),
                queueFolder(root).list()!!.sorted())
            assertTrue(damaged.contentEquals(File(queueFolder(root), "00000000000000000003.corrupt").readBytes()))
            assertEquals(listOf("a", "b", "d"), savedEventNames(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun leftoversOfAnInterruptedAtomicWriteAreDeletedOrRestoredAtLoad() = runBlocking {
        val root = root()
        try {
            val first = configureJvm(configuration(root), NoopTransport)
            for (name in listOf("a", "b", "c")) first.track(name)
            first.shutdown()
            val folder = queueFolder(root)
            // The second event's file is missing and only its backup is left.
            check(File(folder, "00000000000000000002.json").renameTo(File(folder, "00000000000000000002.json.bak")))
            // The third event's rewrite was cut short: the backup holds the last complete content.
            val third = File(folder, "00000000000000000003.json")
            File(folder, "00000000000000000003.json.bak").writeBytes(third.readBytes())
            third.writeText("{\"type\":\"track\",\"ev")
            // A write that never finished.
            File(folder, "00000000000000000004.json.new").writeText("{\"type\":\"track\",\"ev")
            val transport = ManualTransport()
            transport.autoSucceedFutureRequests()
            val second = configureJvm(configuration(root), transport)
            assertEquals(listOf("00000000000000000001.json", "00000000000000000002.json", "00000000000000000003.json"), folder.list()!!.sorted())
            second.flush()
            second.shutdown()
            assertEquals(listOf("a", "b", "c"), transport.snapshot().flatten().map { it["event"]?.stringValue })
        } finally { root.deleteRecursively() }
    }

    @Test fun eventsLeftByAFailedDeletionAtOptOutAreNeverSentAfterOptIn() = runBlocking {
        val root = root()
        try {
            var failing = false
            val delete: (File) -> Unit = { file -> if (failing) throw java.io.IOException("EIO"); TempRenameFileIo.delete(file) }
            val first = configureJvm(configuration(root), NoopTransport, storeDelete = delete)
            first.track("old-1"); first.track("old-2")
            failing = true
            runCatching { first.optOut() }
            assertEquals("The files could not be deleted", listOf("old-1", "old-2"), savedEventNames(root))
            failing = false
            first.optIn()
            first.track("new")
            first.shutdown()
            val transport = ManualTransport()
            transport.autoSucceedFutureRequests()
            val second = configureJvm(configuration(root), transport)
            second.flush()
            second.shutdown()
            assertEquals(listOf("new"), transport.snapshot().flatten().map { it["event"]?.stringValue })
        } finally { root.deleteRecursively() }
    }

    @Test fun optInThatCannotDeleteTheLeftoverFilesFailsAndTheUserStaysOptedOut() = runBlocking {
        val root = root()
        try {
            var failing = false
            val delete: (File) -> Unit = { file -> if (failing) throw java.io.IOException("EIO"); TempRenameFileIo.delete(file) }
            val sdk = configureJvm(configuration(root), NoopTransport, storeDelete = delete)
            sdk.track("old")
            failing = true
            runCatching { sdk.optOut() }
            try {
                sdk.optIn()
                org.junit.Assert.fail("optIn must fail while the old events cannot be deleted")
            } catch (_: TranthorException.StoreTemporarilyUnavailable) {}
            assertTrue(sdk.isOptedOut)
            sdk.track("ignored")
            assertEquals(listOf("old"), savedEventNames(root))
            sdk.shutdown()
            val restarted = configureJvm(configuration(root), NoopTransport)
            assertTrue(restarted.isOptedOut)
            restarted.shutdown()
        } finally { root.deleteRecursively() }
    }

    @Test fun aDeliveredBatchWhoseFilesCouldNotBeDeletedIsNotSentAgainAndItsDeletionIsRetried() = runBlocking {
        val root = root()
        try {
            var failing = false
            val transport = ManualTransport()
            transport.autoSucceedFutureRequests()
            val sdk = configureJvm(configuration(root), transport,
                storeDelete = { file -> if (failing) throw java.io.IOException("EIO"); TempRenameFileIo.delete(file) })
            sdk.track("a")
            failing = true
            sdk.flush()
            failing = false
            assertEquals("The file is still there after the failed deletion", listOf("a"), savedEventNames(root))
            sdk.flush()
            assertEquals(listOf(listOf("a")), transport.snapshot().map { batch -> batch.map { it["event"]?.stringValue } })
            assertEquals("The deletion was retried", emptyList<String>(), savedEventNames(root))
            sdk.shutdown()
        } finally { root.deleteRecursively() }
    }

    @Test fun aResetThatCannotBeSavedChangesNeitherTheIdentityInMemoryNorOnDisk() = runBlocking {
        val root = root()
        try {
            var failing = false
            val sdk = configureJvm(configuration(root), NoopTransport, storeWrite = { bytes, file ->
                if (failing && file.name == "state.json") throw java.io.IOException("EIO")
                TempRenameFileIo.write(file, bytes)
            })
            sdk.identify(userId = "user-1")
            val anonymous = sdk.anonymousId
            failing = true
            try {
                sdk.reset()
                org.junit.Assert.fail("reset must throw when its write fails")
            } catch (_: TranthorException.StoreTemporarilyUnavailable) {}
            assertEquals("user-1", sdk.userId)
            assertEquals(anonymous, sdk.anonymousId)
            sdk.shutdown()
            val stored = loadState(root)
            assertEquals("user-1", stored.userId)
            assertEquals(anonymous, stored.anonymousId)
        } finally { root.deleteRecursively() }
    }

    @Test fun optingOutDeletesTheQueueFiles() = runBlocking {
        val root = root()
        try {
            val sdk = configureJvm(configuration(root), NoopTransport)
            sdk.track("a"); sdk.track("b")
            assertEquals(listOf("a", "b"), savedEventNames(root))
            sdk.optOut()
            assertEquals(emptyList<String>(), queueFolder(root).list()!!.toList())
            sdk.shutdown()
        } finally { root.deleteRecursively() }
    }

    @Test fun aProcessKilledAfterTheConsentWriteSendsNothingOnRestartAndFinishesTheDeletion() = runBlocking {
        val root = root()
        try {
            var killed = false
            val dying = configureJvm(configuration(root), NoopTransport, storeWrite = { bytes, file ->
                if (killed) throw java.io.IOException("process killed")
                TempRenameFileIo.write(file, bytes)
                if (file.name == "consent.json" && String(bytes).contains("true")) killed = true
            })
            dying.track("a"); dying.track("b")
            runCatching { dying.optOut() }
            dying.shutdown()
            assertEquals("The deletion had not happened yet", listOf("a", "b"), savedEventNames(root))
            val transport = ManualTransport()
            val restarted = configureJvm(configuration(root), transport)
            restarted.flush()
            restarted.shutdown()
            assertEquals(0, transport.count())
            assertEquals(emptyList<String>(), queueFolder(root).list()!!.toList())
            assertTrue(restarted.isOptedOut)
        } finally { root.deleteRecursively() }
    }

    @Test fun aFullQueueRemovesTheOldestFilesAndReportsThemWithZeroAttempts() = runBlocking {
        val root = root()
        try {
            val errors = FailureLog()
            val sdk = configureJvm(configuration(root, maxQueueSize = 3, onError = errors::append), NoopTransport)
            for (index in 0 until 8) sdk.track("e$index")
            assertEquals(listOf("e5", "e6", "e7"), savedEventNames(root))
            assertEquals(3, queueFolder(root).list()!!.size)
            sdk.shutdown()
            val failure = errors.snapshot().single()
            assertEquals(0, failure.attempts)
            assertEquals(listOf("e0", "e1", "e2", "e3", "e4"), failure.events.map { it["event"]?.stringValue })
        } finally { root.deleteRecursively() }
    }

    /** The state file of release 0.1.0 with one batch in flight (`active`) and two events waiting (`queued`). */
    private val release010State = """
        {"version":1,"anonymousId":"3f1d0a52-6c7e-4b1a-9d2e-8a1f5c0b7e44","userId":"user-9","optedOut":false,
         "queued":[
          {"type":"track","event":"queued-1","messageId":"m-q1","timestamp":"2026-10-30T10:00:03.000Z","userId":"user-9"},
          {"type":"track","event":"queued-2","messageId":"m-q2","timestamp":"2026-10-30T10:00:04.000Z","userId":"user-9"}],
         "active":[
          {"type":"track","event":"active-1","messageId":"m-a1","timestamp":"2026-10-30T10:00:01.000Z","userId":"user-9"},
          {"type":"track","event":"active-2","messageId":"m-a2","timestamp":"2026-10-30T10:00:02.000Z","userId":"user-9"}]}
    """.trimIndent()

    private fun seedRelease010(root: File) {
        val store = JsonStore(root, "default")
        store.stateFile.writeText(release010State)
        store.consentFile.writeText("{\"version\":1,\"optedOut\":false}")
    }

    private fun assertMigratedEventsSentOnce(root: File, transport: ManualTransport) {
        val sent = transport.snapshot().flatten()
        assertEquals(listOf("active-1", "active-2", "queued-1", "queued-2"), sent.map { it["event"]?.stringValue })
        assertEquals(listOf("m-a1", "m-a2", "m-q1", "m-q2"), sent.map { it["messageId"]?.stringValue })
        assertEquals(listOf("2026-10-30T10:00:01.000Z", "2026-10-30T10:00:02.000Z", "2026-10-30T10:00:03.000Z", "2026-10-30T10:00:04.000Z"),
            sent.map { it["timestamp"]?.stringValue })
        assertTrue(savedEvents(root).isEmpty())
    }

    @Test fun aRelease010StateFileIsMovedToQueueFilesInOrderAndRewrittenWithoutThem() = runBlocking {
        val root = root()
        try {
            seedRelease010(root)
            val transport = ManualTransport()
            transport.autoSucceedFutureRequests()
            val sdk = configureJvm(configuration(root), transport)
            assertEquals(listOf("active-1", "active-2", "queued-1", "queued-2"), savedEventNames(root))
            val state = JsonValue.parse(JsonStore(root, "default").stateFile.readText()).objectValue!!
            assertEquals(2.0, state["version"]?.numberValue)
            assertEquals(null, state["queued"]); assertEquals(null, state["active"])
            assertEquals("3f1d0a52-6c7e-4b1a-9d2e-8a1f5c0b7e44", state["anonymousId"]?.stringValue)
            assertEquals("user-9", sdk.userId)
            sdk.flush()
            sdk.shutdown()
            assertMigratedEventsSentOnce(root, transport)
        } finally { root.deleteRecursively() }
    }

    @Test fun aRelease010StateOfAnOptedOutUserSendsNothingAndLeavesNoQueueFiles() = runBlocking {
        val root = root()
        try {
            val store = JsonStore(root, "default")
            store.stateFile.writeText(release010State.replace("\"optedOut\":false", "\"optedOut\":true"))
            store.consentFile.writeText("{\"version\":1,\"optedOut\":true}")
            val transport = ManualTransport()
            transport.autoSucceedFutureRequests()
            val sdk = configureJvm(configuration(root), transport)
            sdk.flush()
            sdk.shutdown()
            assertTrue(sdk.isOptedOut)
            assertEquals(0, transport.count())
            assertEquals("No queue file was written", emptyList<String>(), queueFolder(root).list()?.toList().orEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun aMoveInterruptedBeforeTheStateFileWasRewrittenNeitherDuplicatesNorLosesEvents() = runBlocking {
        val root = root()
        try {
            seedRelease010(root)
            try {
                configureJvm(configuration(root), NoopTransport, storeWrite = { bytes, file ->
                    if (file.name == "state.json") throw java.io.IOException("process killed")
                    TempRenameFileIo.write(file, bytes)
                }).shutdown()
                org.junit.Assert.fail("The interrupted move must stop configure")
            } catch (_: TranthorException.StoreTemporarilyUnavailable) {}
            assertEquals(4, queueFolder(root).list()!!.size)
            val transport = ManualTransport()
            transport.autoSucceedFutureRequests()
            val sdk = configureJvm(configuration(root), transport)
            assertEquals(listOf("active-1", "active-2", "queued-1", "queued-2"), savedEventNames(root))
            sdk.flush()
            sdk.shutdown()
            assertMigratedEventsSentOnce(root, transport)
        } finally { root.deleteRecursively() }
    }

    /** The default storage directory is `noBackupFilesDir`, which Android excludes from backup: nothing may be written outside it. */
    @Test fun everyFileTheSdkWritesLivesInsideTheStorageDirectory() = runBlocking {
        val root = root()
        try {
            val sdk = configureJvm(configuration(root), NoopTransport)
            sdk.track("a")
            sdk.shutdown()
            val written = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.sorted().toList()
            assertEquals(listOf("64656661756c74/consent.json", "64656661756c74/queue/00000000000000000001.json", "64656661756c74/state.json"), written)
        } finally { root.deleteRecursively() }
    }
}
