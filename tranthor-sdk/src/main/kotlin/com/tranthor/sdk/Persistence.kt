package com.tranthor.sdk

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** One admitted event plus its compact JSON, encoded once at admission and reused by every save. */
internal class QueueItem private constructor(val value: Map<String, JsonValue>, val json: String) {
    /** The number in the event's file name while it is in the queue folder, or null. */
    var sequence: Long? = null
    override fun equals(other: Any?): Boolean = other is QueueItem && other.json == json
    override fun hashCode(): Int = json.hashCode()
    override fun toString(): String = json

    companion object {
        /** Throws [IllegalArgumentException] for non-finite numbers. */
        fun of(value: Map<String, JsonValue>): QueueItem = QueueItem(value, JsonCodec.encodeObject(value))
    }
}

internal class StoredState(
    var anonymousId: String = newAnonymousId(),
    var userId: String? = null,
    var optedOut: Boolean = false,
    /** The push Installation this install last registered, or null. */
    var installation: StoredInstallation? = null,
    /** `Installation Removed` events made by opt-out, delivered even while opted out. */
    var pendingRemovals: MutableList<QueueItem> = ArrayList(),
    /** Identity tokens by user id (see [TokenCache]). Stored in this file with the queue, never in a shared store. */
    var tokens: TokenCache = TokenCache(),
    /** Set once an identity token has been passed to `identify`; it is never cleared. See [TranthorSDK] identity in use. */
    var identityTokenSupplied: Boolean = false,
) {
    /** Encodes identity, consent, the push installation and opt-out removals; queued events live in their own files. */
    fun encode(): ByteArray {
        val out = StringBuilder(256)
        out.append("{\"version\":").append(CURRENT_VERSION).append(",\"anonymousId\":")
        JsonCodec.encode(JsonValue.StringValue(anonymousId), out)
        userId?.let { out.append(",\"userId\":"); JsonCodec.encode(JsonValue.StringValue(it), out) }
        out.append(",\"optedOut\":").append(optedOut)
        installation?.let {
            out.append(",\"installation\":")
            JsonCodec.encode(JsonValue.of(mapOf("token" to it.token, "appId" to it.appId, "permissionState" to it.permission.wireValue)), out)
        }
        // Optional key: set once an identity token has been passed to identify; identity stays "in use" after refusals delete tokens.
        if (identityTokenSupplied) out.append(",\"identityTokenSupplied\":true")
        if (!tokens.isEmpty()) {
            // Optional key, like installation: older builds ignore it. A token is a credential: it is written
            // here, with the same protection as the queue, and nowhere else.
            out.append(",\"identityTokens\":")
            JsonCodec.encode(JsonValue.ObjectValue(tokens.users.associateWithTo(LinkedHashMap()) { JsonValue.StringValue(tokens.get(it)!!) }), out)
        }
        if (pendingRemovals.isNotEmpty()) {
            out.append(",\"pendingRemovals\":[")
            pendingRemovals.forEachIndexed { index, item -> if (index > 0) out.append(','); out.append(item.json) }
            out.append(']')
        }
        out.append('}')
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val CURRENT_VERSION = 2

        /** Strict decode; any shape/type error is damage (the caller salvages and quarantines). */
        fun decode(bytes: ByteArray): StoredState {
            val root = JsonValue.parse(String(bytes, Charsets.UTF_8)).objectValue ?: throw IllegalArgumentException("State is not an object")
            val version = root["version"]?.let { strictInt(it) } ?: 0
            require(version in 0..CURRENT_VERSION) { "Unsupported state version" }
            // Identity was required in the original format; missing it is damage, not schema evolution.
            val anonymous = root["anonymousId"]?.stringValue ?: throw IllegalArgumentException("Missing anonymous identity")
            require(isUuid(anonymous)) { "Invalid anonymous identity" }
            val user = when (val value = root["userId"]) {
                null, JsonValue.Null -> null
                is JsonValue.StringValue -> value.value
                else -> throw IllegalArgumentException("Invalid user identity")
            }
            val optedOut = when (val value = root["optedOut"]) {
                null, JsonValue.Null -> false
                is JsonValue.BooleanValue -> value.value
                else -> throw IllegalArgumentException("Invalid consent field")
            }
            return StoredState(anonymous, user, optedOut, installation(root["installation"]), pendingRemovals(root["pendingRemovals"]),
                tokens(root["identityTokens"]), root["identityTokenSupplied"]?.booleanValue == true)
        }

        /** A damaged entry is dropped, not treated as state damage: the provider supplies the token again. */
        private fun tokens(value: JsonValue?): TokenCache {
            val cache = TokenCache()
            for ((userId, token) in value?.objectValue.orEmpty()) token.stringValue?.let { cache.set(userId, it) }
            return cache
        }

        /** Damaged entries are dropped, not treated as state damage, like a damaged installation. */
        private fun pendingRemovals(value: JsonValue?): MutableList<QueueItem> =
            value?.arrayValue.orEmpty().mapNotNullTo(ArrayList()) { entry ->
                entry.objectValue?.let { runCatching { QueueItem.of(it) }.getOrNull() }
            }

        /** A damaged Installation is dropped, not treated as state damage: the next token re-registers it. */
        private fun installation(value: JsonValue?): StoredInstallation? {
            val fields = value?.objectValue ?: return null
            val token = fields["token"]?.stringValue?.takeIf { it.isNotEmpty() } ?: return null
            val appId = fields["appId"]?.stringValue?.takeIf { it.isNotEmpty() } ?: return null
            val permission = InstallationPermission.entries.firstOrNull { it.wireValue == fields["permissionState"]?.stringValue }
                ?: InstallationPermission.UNKNOWN
            return StoredInstallation(token, appId, permission)
        }

        fun strictInt(value: JsonValue): Int {
            val number = value.numberValue ?: throw IllegalArgumentException("Version is not a number")
            require(number == Math.floor(number) && Math.abs(number) < Int.MAX_VALUE) { "Version is not an integer" }
            return number.toInt()
        }

        /** Salvage only a syntactically valid JSON object's independently valid identity fields. */
        fun salvageIdentity(bytes: ByteArray): StoredState {
            val result = StoredState()
            val root = runCatching { JsonValue.parse(String(bytes, Charsets.UTF_8)).objectValue }.getOrNull() ?: return result
            root["anonymousId"]?.stringValue?.takeIf(::isUuid)?.let { result.anonymousId = it }
            result.userId = root["userId"]?.stringValue
            return result
        }

        /** True when a syntactically valid object declares a version newer than this SDK supports. */
        fun declaresFutureVersion(bytes: ByteArray): Boolean {
            val root = runCatching { JsonValue.parse(String(bytes, Charsets.UTF_8)).objectValue }.getOrNull() ?: return false
            val version = root["version"]?.numberValue ?: return false
            return version == Math.floor(version) && version > CURRENT_VERSION
        }
    }
}

/** The token, application ID and permission last sent with `Installation Registered`. The token is never logged. */
internal class StoredInstallation(val token: String, val appId: String, val permission: InstallationPermission) {
    override fun toString(): String = "StoredInstallation(token=<redacted>, appId=$appId, permission=${permission.wireValue})"
}

internal fun newAnonymousId(): String = UUID.randomUUID().toString()

private val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
internal fun isUuid(value: String): Boolean = uuidPattern.matches(value)

/** File primitives. Implementations distinguish a missing file (null) from an unreadable one (throws). */
internal interface FileIo {
    fun read(file: File): ByteArray?
    fun write(file: File, bytes: ByteArray)
    fun move(from: File, to: File)
    /** Removes [file] and any recovery copies; a missing file is not an error. */
    fun delete(file: File)
}

/**
 * The namespace's state.json, consent.json and quarantine files. One writer per namespace is
 * guaranteed by [NamespaceRegistry]; [load] verifies/quarantines once and grants write ownership.
 */
internal class JsonStore(
    base: File,
    namespace: String,
    private val io: FileIo,
    private val writeBytes: (ByteArray, File) -> Unit = { bytes, file -> io.write(file, bytes) },
    private val readBytes: (File) -> ByteArray? = { io.read(it) },
    private val moveFile: (File, File) -> Unit = { from, to -> io.move(from, to) },
    deleteFile: (File) -> Unit = { io.delete(it) },
) {
    val directory: File
    val queue: EventQueue
    val stateFile: File
    val consentFile: File
    val corruptFile: File
    val consentCorruptFile: File
    private var ownsVerifiedFile: Boolean
    /**
     * Set by [load]: consent says opted out but the state still says opted in, so the process died
     * inside optOut() between its consent and state writes.
     */
    var interruptedOptOut: Boolean = false
        private set
    /** Set by [load]: the unsent events a release 0.1.0 state file held, for the one-time move into the queue folder. */
    var legacyEvents: List<QueueItem> = emptyList()
        private set

    init {
        // Keep short namespaces readable as hex; long names use a bounded stable digest.
        val bytes = namespace.toByteArray(Charsets.UTF_8)
        val name = when {
            bytes.isEmpty() -> "empty"
            bytes.size <= 127 -> bytes.joinToString("") { "%02x".format(it) }
            else -> "sha256-" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        directory = File(base, name)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create the SDK store directory")
        }
        stateFile = File(directory, "state.json")
        consentFile = File(directory, "consent.json")
        corruptFile = File(directory, "state.corrupt.json")
        consentCorruptFile = File(directory, "consent.corrupt.json")
        queue = EventQueue(File(directory, "queue"), writeBytes, readBytes, moveFile, deleteFile)
        ownsVerifiedFile = !stateFile.exists()
    }

    /** Throws [TranthorException.StoreTemporarilyUnavailable] for I/O failures; never repairs on read failure. */
    fun load(): StoredState {
        ownsVerifiedFile = false
        // Read both files before making any repairs. A transient I/O failure is not corruption.
        val stateData = readIfPresent(stateFile)
        val consentData = readIfPresent(consentFile)
        val consent = consentData?.let(::decodeConsent)
        var state = StoredState()
        legacyEvents = emptyList()
        var unsupportedStateVersion = false
        var decodedOptedIn = false
        if (stateData != null) {
            val decoded = runCatching { StoredState.decode(stateData) to LegacyEvents.read(stateData) }.getOrNull()
            if (decoded != null) {
                state = decoded.first
                legacyEvents = decoded.second
                decodedOptedIn = !state.optedOut
            } else {
                unsupportedStateVersion = StoredState.declaresFutureVersion(stateData)
                state = StoredState.salvageIdentity(stateData)
                quarantine(stateFile, corruptFile)
            }
        }
        if (consentData != null && consent == null) quarantine(consentFile, consentCorruptFile)
        // A consent-only file is valid crash residue from first-run consent -> state writes.
        // Verified consent wins over damaged supported state; future versions fail closed.
        state.optedOut = when {
            unsupportedStateVersion -> true
            consent != null -> consent
            stateData != null || consentData != null || corruptFile.exists() || consentCorruptFile.exists() -> true
            else -> false
        }
        interruptedOptOut = decodedOptedIn && consent == true
        ownsVerifiedFile = true
        return state
    }

    private fun readIfPresent(file: File): ByteArray? = try {
        readBytes(file)
    } catch (error: Exception) {
        throw TranthorException.StoreTemporarilyUnavailable(error)
    }

    private fun quarantine(original: File, destination: File) {
        try {
            if (destination.exists() && !destination.delete()) throw IOException("Unable to replace quarantine file")
            moveFile(original, destination)
        } catch (error: Exception) {
            // load never grants write ownership if the move fails; configure must stop here.
            throw TranthorException.StoreTemporarilyUnavailable(error)
        }
    }

    fun save(state: StoredState) {
        if (!ownsVerifiedFile) throw TranthorException.StoreTemporarilyUnavailable(IOException("Store was not verified"))
        writeBytes(state.encode(), stateFile)
    }

    /** Consent is deliberately separate: routine queue/identity saves cannot reopen consent. */
    fun saveConsent(optedOut: Boolean) {
        writeBytes("{\"version\":1,\"optedOut\":$optedOut}".toByteArray(Charsets.UTF_8), consentFile)
    }

    private fun decodeConsent(bytes: ByteArray): Boolean? = runCatching {
        val root = JsonValue.parse(String(bytes, Charsets.UTF_8)).objectValue ?: return null
        val version = root["version"]?.let { StoredState.strictInt(it) } ?: 1
        if (version != 1) return null
        root["optedOut"]?.booleanValue
    }.getOrNull()
}
