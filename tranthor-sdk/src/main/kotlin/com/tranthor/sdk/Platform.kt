package com.tranthor.sdk

import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * The only seam between the platform-neutral core and Android. Production uses `AndroidPlatform`;
 * the JVM tests use a plain-JVM implementation, so queue, retry, transport and persistence logic
 * run identically in both.
 */
internal interface Platform {
    /** Base directory for namespaces when [Configuration.storageDirectory] is null. */
    fun defaultStorageDirectory(): File
    val fileIo: FileIo
    fun automaticContext(): Map<String, JsonValue>
    fun logDebug(message: String)
    /** The host app's version name and the OS release, reported with `Installation Registered` ("" when unknown). */
    val appVersion: String
    val osVersion: String
    /** The application's package name: the default app id sent with every request ("" when unknown, which sends none). */
    val packageName: String
    /** Wall-clock time for Installation event timestamps (a fixed clock in host tests). */
    fun currentTimeMs(): Long = System.currentTimeMillis()
    /** Installs process lifecycle hooks; returns a disposer. Called once per instance. */
    suspend fun installLifecycle(sdk: TranthorSDK): (suspend () -> Unit)?
}

/**
 * UTC timestamp with exactly three fractional digits (`yyyy-MM-ddTHH:mm:ss.SSSZ`), computed without
 * locale-sensitive or non-thread-safe formatters.
 */
internal fun timestamp(epochMillis: Long = System.currentTimeMillis()): String {
    val millis = Math.floorMod(epochMillis, 1000L)
    val epochSeconds = Math.floorDiv(epochMillis, 1000L)
    val days = Math.floorDiv(epochSeconds, 86_400L)
    val secondOfDay = Math.floorMod(epochSeconds, 86_400L)
    // Civil-from-days (Howard Hinnant), valid for the proleptic Gregorian calendar.
    val z = days + 719_468
    val era = Math.floorDiv(z, 146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    return String.format(
        Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
        year, month, day, secondOfDay / 3600, (secondOfDay / 60) % 60, secondOfDay % 60, millis,
    )
}

/** Parses the exact form [timestamp] writes back to epoch milliseconds; null for anything else. */
internal fun parseTimestamp(value: String): Long? {
    val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})Z$").matchEntire(value) ?: return null
    val (y, m, d, hh, mm, ss, ms) = match.destructured
    val month = m.toLong()
    val year = y.toLong() - if (month <= 2) 1 else 0
    // Days-from-civil (Howard Hinnant), the inverse of the conversion in timestamp().
    val era = Math.floorDiv(year, 400L)
    val yoe = year - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + d.toLong() - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146_097 + doe - 719_468
    return ((days * 86_400 + hh.toLong() * 3600 + mm.toLong() * 60 + ss.toLong()) * 1000) + ms.toLong()
}
