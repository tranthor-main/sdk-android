package com.tranthor.sdk

import java.io.File
import java.io.IOException
import java.util.UUID

/** A process-wide lease prevents two live instances from overwriting the same store. */
internal object NamespaceRegistry {
    private val leases = HashMap<String, UUID>()

    @Synchronized
    fun acquire(base: File, namespace: String): NamespaceLease {
        val path = try { base.canonicalPath } catch (_: IOException) { base.absolutePath }
        val key = path + "\u0000" + namespace
        if (leases.containsKey(key)) throw TranthorException.NamespaceInUse()
        val token = UUID.randomUUID()
        leases[key] = token
        return NamespaceLease(key, token)
    }

    @Synchronized
    fun release(lease: NamespaceLease) {
        if (leases[lease.key] == lease.token) leases.remove(lease.key)
    }
}

internal class NamespaceLease(val key: String, val token: UUID)
