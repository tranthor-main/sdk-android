package com.tranthor.sdk

/**
 * The one-time move of a release 0.1.0 state file (version 1), which kept unsent events inside the state file in
 * `active` (the batch in flight) and `queued`. Later versions keep each event in its own file in the queue folder.
 */
internal object LegacyEvents {
    /** The events of a state file older than version 2, in send order; empty for any later version. */
    fun read(stateBytes: ByteArray): List<QueueItem> {
        val root = JsonValue.parse(String(stateBytes, Charsets.UTF_8)).objectValue ?: throw IllegalArgumentException("State is not an object")
        val version = root["version"]?.let { StoredState.strictInt(it) } ?: 0
        return if (version < 2) items(root["active"]) + items(root["queued"]) else emptyList()
    }

    private fun items(value: JsonValue?): List<QueueItem> = when (value) {
        null, JsonValue.Null -> emptyList()
        is JsonValue.ArrayValue -> value.value.map { QueueItem.of(it.objectValue ?: throw IllegalArgumentException("Invalid queue item")) }
        else -> throw IllegalArgumentException("Invalid queue")
    }
}
