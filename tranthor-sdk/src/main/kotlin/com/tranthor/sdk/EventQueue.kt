package com.tranthor.sdk

import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The durable event queue. Each event is its own small file in the `queue` folder, named by a zero-padded
 * sequence number: order is the number, never a file date, and admitting an event writes only that event.
 * Queued events wait to be sent; the active batch is in flight, and its files stay until it is acknowledged or
 * refused, so a restart finds it again without a separate record.
 */
internal class EventQueue(
    private val directory: File,
    private val writeBytes: (ByteArray, File) -> Unit,
    private val readBytes: (File) -> ByteArray?,
    private val moveFile: (File, File) -> Unit,
    private val deleteFile: (File) -> Unit,
) {
    private val queued = ArrayDeque<QueueItem>()
    private var active: List<QueueItem> = emptyList()
    private var nextSequence = 1L
    /** Files of events that are already gone from memory (sent or refused) but could not be deleted yet. */
    private val awaitingDeletion = LinkedHashSet<Long>()

    val hasQueued: Boolean get() = queued.isNotEmpty()
    val queuedCount: Int get() = queued.size
    val activeCount: Int get() = active.size
    val hasActive: Boolean get() = active.isNotEmpty()

    private fun fileFor(sequence: Long) = File(directory, String.format(Locale.ROOT, "%020d.json", sequence))

    /**
     * Reads every event file in order, after the one-time move of [legacy] events from a release 0.1.0 state
     * file, which are written first under fixed numbers, so repeating an interrupted move overwrites the same
     * files instead of duplicating them. Leftovers of an interrupted atomic write are cleaned up first. A file
     * that was read but does not decode is renamed to `.corrupt`, never deleted, and skipped.
     * Throws [TranthorException.StoreTemporarilyUnavailable] when a file cannot be read, and [IOException]
     * when the folder cannot be listed.
     */
    fun load(legacy: List<QueueItem>) {
        queued.clear(); active = emptyList(); awaitingDeletion.clear()
        if (legacy.isNotEmpty()) {
            ensureDirectory()
            legacy.forEachIndexed { index, item -> writeBytes(item.json.toByteArray(Charsets.UTF_8), fileFor(index + 1L)) }
        }
        if (!directory.exists()) return
        recoverInterruptedWrites()
        val names = listNames()
        val sequences = names.mapNotNull { EVENT_FILE.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }.sorted()
        for (sequence in sequences) {
            val file = fileFor(sequence)
            val bytes = try {
                readBytes(file)
            } catch (error: Exception) {
                // An unreadable file is a temporary storage failure, not damage: leave it where it is.
                throw TranthorException.StoreTemporarilyUnavailable(error)
            } ?: continue
            val item = try {
                decode(bytes)
            } catch (error: Exception) {
                tryToSetAside(file)
                continue
            }
            item.sequence = sequence
            queued.addLast(item)
        }
        // Set-aside files keep their numbers, so a later event never takes the name of one.
        val highest = names.mapNotNull { NUMBERED_FILE.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }.maxOrNull() ?: 0L
        nextSequence = highest + 1
    }

    private fun listNames(): Array<String> = directory.list() ?: throw IOException("Unable to list the event queue")

    /**
     * A crash in the middle of an atomic write leaves `NNN.json.new` (the unfinished new content) or, on older
     * Android releases, `NNN.json.bak` (the last complete content, kept while the event file is rewritten). A
     * `.new` is deleted. A `.bak` replaces its event file, which may be cut short. A deletion that fails is
     * repeated by the next start.
     */
    private fun recoverInterruptedWrites() {
        val names = listNames().toSet()
        for (name in names) {
            if (UNFINISHED_FILE.matches(name)) {
                runCatching { deleteFile(File(directory, name)) }
            } else if (BACKUP_FILE.matches(name)) {
                try {
                    moveFile(File(directory, name), File(directory, name.removeSuffix(".bak")))
                } catch (error: Exception) {
                    throw TranthorException.StoreTemporarilyUnavailable(error)
                }
            }
        }
    }

    /** Admits [items] as new files after the existing ones: all of them, or none. */
    fun append(items: List<QueueItem>) {
        ensureDirectory()
        val written = ArrayList<QueueItem>(items.size)
        try {
            for (item in items) {
                val sequence = nextSequence + written.size
                writeBytes(item.json.toByteArray(Charsets.UTF_8), fileFor(sequence))
                item.sequence = sequence
                written.add(item)
            }
        } catch (error: Exception) {
            // Their numbers stay taken, so a file that cannot be deleted now is never mistaken for a later event.
            nextSequence += written.size
            discardFilesOrRetryLater(written)
            throw error
        }
        nextSequence += items.size
        queued.addAll(items)
    }

    private fun tryToSetAside(file: File) {
        // If the rename fails too, the file is simply tried again at the next start.
        runCatching { moveFile(file, File(directory, file.name.removeSuffix(".json") + ".corrupt")) }
    }

    /** Takes back events that [append] admitted, when a later part of the same call failed. */
    fun rollback(items: List<QueueItem>) {
        discardFilesOrRetryLater(items)
        queued.removeAll { queuedItem -> items.any { it === queuedItem } }
    }

    /** Removes queued events above [capacity], preferring ordinary ones, and deletes their files. Returns them. */
    fun trim(capacity: Int): List<QueueItem> = trimQueue(queued, capacity).also(::discardFilesOrRetryLater)

    /** The events waiting to be sent, oldest first. Read only. */
    val queuedItems: List<QueueItem> get() = queued

    /** The batch in flight. Read only. */
    val activeItems: List<QueueItem> get() = active

    /**
     * Moves up to [maxSize] queued events, oldest first, into the active batch and returns it. [count] may take
     * fewer (a request carries at most 10 users when identity tokens are in use); it is asked with the queue and
     * [maxSize], and at least one event is always taken.
     */
    fun takeBatch(maxSize: Int, count: ((List<QueueItem>, Int) -> Int)? = null): List<QueueItem> {
        val wanted = count?.invoke(queued, maxSize) ?: minOf(maxSize, queued.size)
        val batch = List(wanted.coerceIn(1, minOf(maxSize, queued.size))) { queued.removeFirst() }
        active = batch
        return batch
    }

    /** Removes [items] from the active batch for good (their files are deleted, or deleted later if that fails). */
    fun removeFromActive(items: List<QueueItem>) {
        discardFilesOrRetryLater(items)
        active = active.filter { item -> items.none { it === item } }
    }

    /** Puts [items] of the active batch back at the head of the queue, in their original order; the rest stays active. */
    fun returnToHead(items: List<QueueItem>) {
        for (index in items.indices.reversed()) queued.addFirst(items[index])
        active = active.filter { item -> items.none { it === item } }
    }

    /** The active batch was delivered or refused for good: its files are deleted, or deleted later if that fails. */
    fun completeBatch() {
        discardFilesOrRetryLater(active)
        active = emptyList()
    }

    /** Puts an unacknowledged active batch back at the head of the queue, in its original order. */
    fun returnBatchToHead() {
        for (index in active.indices.reversed()) queued.addFirst(active[index])
        active = emptyList()
    }

    /** Forgets every queued and active event without touching the files (see [deleteAllFiles]). */
    fun dropFromMemory() { queued.clear(); active = emptyList() }

    /**
     * Deletes every file in the folder, including set-aside ones, and tries all of them even after a failure.
     * Returns the first failure, or null when the folder is empty.
     */
    fun deleteAllFiles(): Exception? {
        awaitingDeletion.clear()
        var failure: Exception? = null
        directory.listFiles()?.forEach {
            try { deleteFile(it) } catch (error: Exception) { if (failure == null) failure = error }
        }
        return failure
    }

    /** Deletes again the files that earlier failed to delete; the ones that still fail wait for the next call. */
    fun retryPendingDeletions() {
        val iterator = awaitingDeletion.iterator()
        while (iterator.hasNext()) {
            try { deleteFile(fileFor(iterator.next())); iterator.remove() } catch (_: Exception) {}
        }
    }

    /** A file that cannot be deleted is remembered, so its event is not sent again in this process. */
    private fun discardFilesOrRetryLater(items: Collection<QueueItem>) {
        for (item in items) {
            val sequence = item.sequence ?: continue
            try { deleteFile(fileFor(sequence)) } catch (_: Exception) { awaitingDeletion.add(sequence) }
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Unable to create the event queue folder")
    }

    private fun decode(bytes: ByteArray): QueueItem {
        val value = JsonValue.parse(String(bytes, Charsets.UTF_8)).objectValue ?: throw IllegalArgumentException("Event is not an object")
        return QueueItem.of(value)
    }

    private companion object {
        val EVENT_FILE = Regex("^(\\d{20})\\.json$")
        val NUMBERED_FILE = Regex("^(\\d{20})\\.(json|corrupt)$")
        val UNFINISHED_FILE = Regex("^\\d{20}\\.json\\.new$")
        val BACKUP_FILE = Regex("^\\d{20}\\.json\\.bak$")
    }
}
