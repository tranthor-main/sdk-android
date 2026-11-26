package com.tranthor.sdk

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Plain JVM: no lifecycle hooks, temp+rename atomic files. */
internal class JvmPlatform(
    private val baseDirectory: File? = null,
    override val appVersion: String = "",
    override val osVersion: String = System.getProperty("os.version") ?: "",
    private val clock: () -> Long = System::currentTimeMillis,
    override val packageName: String = "",
) : Platform {
    override fun currentTimeMs(): Long = clock()
    override fun defaultStorageDirectory(): File =
        baseDirectory ?: File(System.getProperty("java.io.tmpdir"), "TranthorSDK")
    override val fileIo: FileIo = TempRenameFileIo
    override fun automaticContext(): Map<String, JsonValue> = jsonObjectOf(
        "library" to mapOf("name" to TranthorSDKVersion.LIBRARY_NAME, "version" to TranthorSDKVersion.CURRENT),
        "os" to mapOf("name" to (System.getProperty("os.name") ?: "JVM"), "version" to (System.getProperty("os.version") ?: "")),
        "device" to mapOf("manufacturer" to "", "model" to (System.getProperty("os.arch") ?: "")),
        "app" to mapOf("name" to "", "version" to "", "build" to "", "namespace" to ""),
        "locale" to Locale.getDefault().toLanguageTag(),
        "timezone" to TimeZone.getDefault().id,
    )
    override fun logDebug(message: String) {}
    override suspend fun installLifecycle(sdk: TranthorSDK): (suspend () -> Unit)? = null
}

/** Write-temp, fsync, rename. Works identically on the JVM host and on Android (no java.nio.file below API 26). */
internal object TempRenameFileIo : FileIo {
    override fun read(file: File): ByteArray? {
        // FileNotFoundException is thrown for both ENOENT and EACCES; never treat it as "missing".
        if (!file.exists()) return null
        return file.readBytes()
    }
    override fun write(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            if (!temp.renameTo(file)) throw IOException("Unable to replace ${file.name}")
        } catch (error: IOException) {
            temp.delete()
            throw error
        }
    }
    override fun move(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("Unable to move ${from.name}")
    }
    override fun delete(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Unable to delete ${file.name}")
    }
}

/** A store on the plain JVM: write-temp, fsync, rename. */
internal fun JsonStore(base: File, namespace: String): JsonStore = JsonStore(base, namespace, TempRenameFileIo)
