package com.tranthor.sdk

/** The OS notification permission reported with `Installation Registered`. */
public enum class InstallationPermission(internal val wireValue: String) {
    GRANTED("granted"),
    DENIED("denied"),
    /** The SDK could not determine the permission; the server treats the Installation as reachable. */
    UNKNOWN("unknown"),
}
