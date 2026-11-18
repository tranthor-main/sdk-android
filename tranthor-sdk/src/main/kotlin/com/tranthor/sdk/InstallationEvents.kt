package com.tranthor.sdk

import java.util.UUID

internal const val INSTALLATION_REGISTERED = "Installation Registered"
internal const val INSTALLATION_REMOVED = "Installation Removed"

/** Everything a re-registration would change on the server. In memory only, so each app start re-registers once. */
internal fun installationFingerprint(state: StoredState, platform: Platform, installation: StoredInstallation): List<String> =
    listOf(installation.token, installation.appId, state.userId?.let { "user:$it" } ?: "anonymous:${state.anonymousId}",
        installation.permission.wireValue, platform.appVersion, platform.osVersion, TranthorSDKVersion.CURRENT)

/** An `Installation Removed` under the stored identity, carrying only the contract's three properties. */
internal fun installationRemoval(
    state: StoredState, context: Map<String, JsonValue>, time: String, installation: StoredInstallation,
): LinkedHashMap<String, JsonValue> {
    val data = LinkedHashMap<String, JsonValue>()
    data["messageId"] = JsonValue.StringValue(UUID.randomUUID().toString())
    data["timestamp"] = JsonValue.StringValue(time)
    val user = state.userId
    if (user != null) data["userId"] = JsonValue.StringValue(user) else data["anonymousId"] = JsonValue.StringValue(state.anonymousId)
    if (context.isNotEmpty()) data["context"] = JsonValue.ObjectValue(LinkedHashMap(context))
    data["event"] = JsonValue.StringValue(INSTALLATION_REMOVED)
    data["properties"] = JsonValue.of(mapOf("platform" to "android", "appId" to installation.appId, "token" to installation.token))
    return data
}

/** An `Installation Registered` under the stored identity: the removal's properties plus permission and app, OS and SDK versions. */
internal fun installationRegistration(
    state: StoredState, context: Map<String, JsonValue>, platform: Platform, time: String, installation: StoredInstallation,
): LinkedHashMap<String, JsonValue> {
    val data = installationRemoval(state, context, time, installation)
    data["event"] = JsonValue.StringValue(INSTALLATION_REGISTERED)
    val properties = LinkedHashMap(data["properties"]!!.objectValue!!)
    properties["permissionState"] = JsonValue.StringValue(installation.permission.wireValue)
    platform.appVersion.takeIf { it.isNotEmpty() }?.let { properties["appVersion"] = JsonValue.StringValue(it) }
    platform.osVersion.takeIf { it.isNotEmpty() }?.let { properties["osVersion"] = JsonValue.StringValue(it) }
    properties["sdkVersion"] = JsonValue.StringValue(TranthorSDKVersion.CURRENT)
    data["properties"] = JsonValue.ObjectValue(properties)
    return data
}

/**
 * The `Installation Removed` an opt-out leaves to deliver. It carries no automatic context, because
 * consent was just withdrawn.
 */
internal fun optOutRemoval(state: StoredState, installation: StoredInstallation, time: String): QueueItem {
    val data = installationRemoval(state, emptyMap(), time, installation)
    data["type"] = JsonValue.StringValue("track")
    return QueueItem.of(data)
}

/**
 * One removal per token: [installation]'s, and every earlier opt-out's still undelivered, reissued
 * (new messageId, new time). A registration admitted since then may have reached the server, which
 * keeps a token's latest event by time and drops a messageId it has already seen.
 */
internal fun optOutRemovals(state: StoredState, installation: StoredInstallation?, nextTime: () -> String): MutableList<QueueItem> {
    fun target(item: QueueItem) = item.value["properties"]?.objectValue?.let { it["appId"]?.stringValue to it["token"]?.stringValue }
    val removals = state.pendingRemovals
        .filter { installation == null || target(it) != (installation.appId to installation.token) }
        .mapTo(ArrayList()) { earlier ->
            val data = LinkedHashMap(earlier.value)
            data["messageId"] = JsonValue.StringValue(UUID.randomUUID().toString())
            data["timestamp"] = JsonValue.StringValue(nextTime())
            QueueItem.of(data)
        }
    installation?.let { removals.add(optOutRemoval(state, it, nextTime())) }
    return removals
}

/** An Installation event as a host may see it: the same event with its push token redacted. Other events are unchanged. */
internal fun withoutToken(event: Map<String, JsonValue>): Map<String, JsonValue> {
    val name = event["event"]?.stringValue
    if (name != INSTALLATION_REGISTERED && name != INSTALLATION_REMOVED) return event
    val properties = event["properties"]?.objectValue ?: return event
    if ("token" !in properties) return event
    return event + ("properties" to JsonValue.ObjectValue(properties + ("token" to JsonValue.StringValue("<redacted>"))))
}

/**
 * Removes events until [queue] fits [capacity] and returns them. Ordinary events go first, oldest first: a
 * queued `Installation Registered` or `Installation Removed` is never dropped for an ordinary one.
 * When only Installation events are left, the oldest one that a newer event for the same token supersedes goes,
 * so the newest per token is kept; with none superseded, the oldest goes.
 */
internal fun trimQueue(queue: ArrayDeque<QueueItem>, capacity: Int): List<QueueItem> {
    val removed = ArrayList<QueueItem>()
    while (queue.size > capacity) {
        val ordinary = queue.indexOfFirst { installationTarget(it) == null }
        val index = if (ordinary >= 0) ordinary else queue.indices.firstOrNull { index ->
            val target = installationTarget(queue[index])
            (index + 1 until queue.size).any { installationTarget(queue[it]) == target }
        } ?: 0
        removed.add(queue.removeAt(index))
    }
    return removed
}

/** The (appId, token) of an Installation event, or null for an ordinary event. */
private fun installationTarget(item: QueueItem): Pair<String?, String?>? {
    val value = item.value
    if (value["type"]?.stringValue != "track") return null
    val event = value["event"]?.stringValue
    if (event != INSTALLATION_REGISTERED && event != INSTALLATION_REMOVED) return null
    val properties = value["properties"]?.objectValue ?: return null
    return properties["appId"]?.stringValue to properties["token"]?.stringValue
}
