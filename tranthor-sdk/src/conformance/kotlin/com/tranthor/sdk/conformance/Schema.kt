package com.tranthor.sdk.conformance

import com.tranthor.sdk.JsonValue

internal typealias J = JsonValue

internal class CheckFailure(message: String) : Exception(message) {
    override fun toString(): String = message ?: "CheckFailure"
}

internal fun require(condition: Boolean, message: String) {
    if (!condition) throw CheckFailure(message)
}

internal fun str(value: String): J = JsonValue.StringValue(value)
internal fun num(value: Number): J = JsonValue.NumberValue(value.toDouble())
internal fun obj(vararg pairs: Pair<String, J>): J = JsonValue.ObjectValue(linkedMapOf(*pairs))
internal fun arr(values: List<J>): J = JsonValue.ArrayValue(values)

/** Implements the draft-7 keywords used by the vendored schema, failing closed on unknown keywords. */
internal class Schema(private val root: J) {
    private val supported = setOf(
        "\$schema", "\$id", "title", "definitions", "\$ref", "type", "properties", "required",
        "additionalProperties", "enum", "const", "pattern", "minLength", "minimum", "maximum", "items", "minItems",
        "uniqueItems", "contains", "oneOf", "anyOf", "allOf", "not", "if", "then", "else",
    )

    fun validate(value: J, schema: J? = null, path: String = "$") {
        val rule = schema ?: root
        val map = rule.objectValue ?: throw CheckFailure("Invalid schema at $path")
        require(supported.containsAll(map.keys), "Unsupported schema keyword $path")
        map["\$ref"]?.stringValue?.let { ref ->
            require(ref.startsWith("#/"), "Nonlocal schema ref")
            var target: J? = root
            for (component in ref.drop(2).split('/')) target = target?.get(component)
            validate(value, target ?: throw CheckFailure("Unresolved schema ref $ref"), path)
            return
        }
        map["type"]?.let { type ->
            val types = type.arrayValue ?: listOf(type)
            require(types.any { matches(value, it.stringValue ?: "") }, "Type mismatch $path")
        }
        map["const"]?.let { require(value == it, "Const mismatch $path") }
        map["enum"]?.arrayValue?.let { require(it.contains(value), "Enum mismatch $path") }
        value.stringValue?.let { string ->
            map["minLength"]?.numberValue?.let { require(string.codePointCount(0, string.length) >= it, "Short string $path") }
            map["pattern"]?.stringValue?.let { require(Regex(it).containsMatchIn(string), "Pattern mismatch $path") }
        }
        value.numberValue?.let { n ->
            map["minimum"]?.numberValue?.let { require(n >= it, "Minimum $path") }
            map["maximum"]?.numberValue?.let { require(n <= it, "Maximum $path") }
        }
        value.objectValue?.let { obj ->
            for (key in map["required"]?.arrayValue ?: emptyList()) require(obj[key.stringValue ?: ""] != null, "Missing $path.$key")
            val properties = map["properties"]?.objectValue ?: emptyMap()
            for ((key, child) in obj) {
                val childSchema = properties[key]
                if (childSchema != null) {
                    validate(child, childSchema, "$path.$key")
                } else {
                    val extra = map["additionalProperties"]
                    if (extra == JsonValue.BooleanValue(false)) throw CheckFailure("Unknown field $path.$key")
                    if (extra?.objectValue != null) validate(child, extra, "$path.$key")
                }
            }
        }
        value.arrayValue?.let { array ->
            map["minItems"]?.numberValue?.let { require(array.size >= it, "Short array $path") }
            if (map["uniqueItems"] == JsonValue.BooleanValue(true)) {
                for (index in array.indices) require(!array.subList(0, index).contains(array[index]), "Duplicate item $path")
            }
            map["items"]?.let { items -> array.forEachIndexed { index, child -> validate(child, items, "$path[$index]") } }
            map["contains"]?.let { contains -> require(array.any { passes(it, contains) }, "Contains mismatch $path") }
        }
        for (keyword in listOf("allOf", "anyOf", "oneOf")) {
            val variants = map[keyword]?.arrayValue ?: continue
            val count = variants.count { passes(value, it, path) }
            require(
                when (keyword) { "allOf" -> count == variants.size; "oneOf" -> count == 1; else -> count >= 1 },
                "$keyword mismatch $path",
            )
        }
        map["not"]?.let { require(!passes(value, it, path), "Not mismatch $path") }
        val condition = map["if"]
        if (condition != null) {
            val branch = if (passes(value, condition)) map["then"] else map["else"]
            if (branch != null) validate(value, branch, path)
        }
    }

    private fun passes(value: J, schema: J, path: String = "$"): Boolean =
        try { validate(value, schema, path); true } catch (_: CheckFailure) { false }

    private fun matches(value: J, type: String): Boolean = when (type) {
        "object" -> value.objectValue != null
        "array" -> value.arrayValue != null
        "string" -> value.stringValue != null
        "number" -> value.numberValue != null
        "integer" -> value.numberValue?.let { it.isFinite() && Math.floor(it) == it } ?: false
        "boolean" -> value.booleanValue != null
        "null" -> value == JsonValue.Null
        else -> false
    }
}

internal fun validIndex(value: J?, count: Int): Boolean =
    value?.numberValue?.let { it >= 0 && it < count && Math.rint(it) == it } ?: false

internal fun validateReferences(scenario: J) {
    val calls = HashMap<String, J>()
    val labels = HashMap<String, String>()
    val capabilities = (scenario["capabilities"]?.arrayValue ?: emptyList()).mapNotNull { it.stringValue }
    val linking = "identity-link" in capabilities
    // Identity tokens are minted at run time and named by ref; a ref is defined once and may be named only after that.
    val refs = HashSet<String>()
    fun needs(capability: String, what: String) = require(capability in capabilities, "$what must require $capability")
    fun define(ref: String) = require(refs.add(ref), "Duplicate token ref $ref")
    scenario["config"]?.get("identity")?.let { identity ->
        needs("identity-token", "config.identity")
        for (outcome in identity["provider"]?.arrayValue ?: emptyList()) {
            if (outcome["result"]?.stringValue == "token") define(outcome["ref"]?.stringValue ?: "")
        }
    }
    if (scenario["server"]?.get("identity") != null) needs("identity-token", "server.identity")
    if (scenario["config"]?.get("appId") != null) needs("app-id", "config.appId")
    for (step in scenario["steps"]?.arrayValue ?: emptyList()) {
        val spec = step["identityToken"] ?: continue
        needs("identity-token", "emit identityToken")
        require(step["method"]?.stringValue == "identify" && step["data"]?.get("userId")?.stringValue != null, "identityToken belongs on an identify with a userId")
        define(spec["ref"]?.stringValue ?: "")
    }
    for (step in scenario["steps"]?.arrayValue ?: emptyList()) {
        val op = step["op"]?.stringValue ?: ""
        if (op == "anonymousId") require(linking, "anonymousId step must require identity-link")
        (step["call"]?.stringValue ?: step["label"]?.stringValue)?.let { label ->
            require(labels[label] == null, "Duplicate label $label")
            labels[label] = op
            if (op == "emit") calls[label] = step["data"] ?: obj()
        }
        val expect = step["expect"] ?: continue
        val requests = expect["requests"]?.arrayValue ?: emptyList()
        if (expect["providerCalls"] != null) needs("identity-token", "expect.providerCalls")
        if (expect["notices"] != null) needs("identity-token", "expect.notices")
        for (request in requests) {
            for (ref in request["identityTokens"]?.arrayValue ?: emptyList()) {
                needs("identity-token", "identityTokens")
                require(ref.stringValue in refs, "Unknown token ref ${ref.stringValue}")
            }
            for (item in request["batch"]?.arrayValue ?: emptyList()) {
                val data = calls[item["call"]?.stringValue ?: ""] ?: throw CheckFailure("Unknown item call")
                if (item["anonymousIdRef"] != null) {
                    require(linking, "anonymousIdRef must require identity-link")
                    require(data["anonymousId"] == null, "anonymousIdRef cannot check a caller-supplied anonymousId")
                }
            }
            for (key in request["headers"]?.objectValue?.keys ?: emptySet()) require(key == key.lowercase(), "Header must be lower-case")
        }
        for (error in expect["errors"]?.arrayValue ?: emptyList()) {
            for (call in error["eventCalls"]?.arrayValue ?: emptyList()) require(calls.containsKey(call.stringValue ?: ""), "Unknown error call")
        }
        for (delay in expect["delays"]?.arrayValue ?: emptyList()) {
            require((delay["minMs"]?.numberValue ?: 0.0) <= (delay["maxMs"]?.numberValue ?: 0.0), "Invalid delay bounds")
            require(validIndex(delay["to"], requests.size), "Invalid delay to")
            val from = delay["from"]?.stringValue
            if (from != null) require(labels[from] != null, "Unknown delay label")
            else require(validIndex(delay["from"], requests.size), "Invalid delay from")
        }
        for (restart in expect["restartBetween"]?.arrayValue ?: emptyList()) {
            require(labels[restart["label"]?.stringValue ?: ""] == "restart", "Invalid restart label")
            for (key in listOf("from", "to")) restart[key]?.let { require(validIndex(it, requests.size), "Invalid restart index") }
            val from = restart["from"]?.numberValue
            val to = restart["to"]?.numberValue
            if (from != null && to != null) require(from < to, "Invalid restart ordering")
        }
    }
}

internal class KnownFailure(val id: String, val reason: String, val tracking: String)

internal class Declaration(
    val name: String,
    val capabilities: List<String>,
    val suiteVersion: String,
    val lifecycleSource: String?,
    val knownFailures: List<KnownFailure>,
) {
    fun validate(scenarios: List<J>, version: String) {
        val allowed = setOf("core", "transport", "persistence", "lifecycle", "consent", "identity-link", "durable-queue", "identity-token", "app-id")
        require(name.isNotEmpty() && suiteVersion == version && version.isNotEmpty(), "Invalid declaration name/version")
        require(allowed.containsAll(capabilities) && capabilities.toSet().size == capabilities.size, "Invalid capabilities")
        lifecycleSource?.let { require(it == "os-hook" || it == "public-method", "Invalid lifecycle source") }
        if (capabilities.contains("lifecycle")) require(lifecycleSource != null, "Missing lifecycle source")
        val ids = HashSet<String>()
        for (failure in knownFailures) {
            require(ids.add(failure.id) && failure.reason.isNotEmpty() && failure.tracking.isNotEmpty(), "Invalid known failure")
            val scenario = scenarios.firstOrNull { it["id"]?.stringValue == failure.id } ?: throw CheckFailure("Unknown known failure")
            require(skipReason(scenario, capabilities) == null, "Known failure would be skipped")
        }
    }
}

/** Missing host fails unless the explicit developer opt-out is set. */
internal fun conformanceHost(environment: Map<String, String>, allowDeveloperSkip: Boolean): String? {
    environment["CONFORMANCE_HOST"]?.takeIf { it.isNotEmpty() }?.let { return it }
    if (allowDeveloperSkip && environment["CONFORMANCE_ALLOW_SKIP"] == "1") return null
    throw CheckFailure("Missing CONFORMANCE_HOST. Run scripts/test-host.sh or scripts/test-emulator.sh; conformance cannot skip silently.")
}

internal fun subset(expected: J, actual: J?, path: String) {
    if (actual == null) throw CheckFailure("Missing $path")
    val map = expected.objectValue
    if (map != null) {
        require(actual.objectValue != null, "Expected object $path")
        for ((key, child) in map) subset(child, actual[key], "$path.$key")
    } else {
        require(expected == actual, "Mismatch $path: expected $expected, got $actual")
    }
}

/**
 * Stricter Android SDK invariant: production never sends envelope context; the body is `batch`, plus
 * `identityTokens` (a list of strings) only when a user in the batch has a token.
 */
internal fun validateEnvelope(body: J?) {
    val keys = body?.objectValue?.keys
    require(keys == setOf("batch") || keys == setOf("batch", "identityTokens"), "Android SDK envelope must contain exactly batch and, with tokens, identityTokens")
    require(body?.get("batch")?.arrayValue != null, "Android SDK batch must be an array")
    body?.get("identityTokens")?.let { tokens ->
        require(tokens.arrayValue?.isNotEmpty() == true && tokens.arrayValue!!.all { it.stringValue != null }, "identityTokens must be a nonempty list of strings")
    }
}

internal fun requestDiagnostics(records: List<J>): J = arr(records.map { record ->
    obj(
        "index" to (record["index"] ?: JsonValue.Null),
        "receivedAtMs" to (record["receivedAtMs"] ?: JsonValue.Null),
        "messageIds" to arr((record["body"]?.get("batch")?.arrayValue ?: emptyList()).map { it["messageId"] ?: JsonValue.Null }),
    )
})

internal fun scenarioFailureReason(error: Throwable, records: List<J>): String =
    error.toString() + "; requests=" + requestDiagnostics(records).toJson()

/** Parses exactly `yyyy-MM-ddTHH:mm:ss.SSSZ` to epoch milliseconds, or null. */
internal fun parseTimestamp(text: String): Long? {
    val match = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})\\.([0-9]{3})Z$").matchEntire(text) ?: return null
    val (y, mo, d, h, mi, s, ms) = match.destructured
    val year = y.toLong(); val month = mo.toLong(); val day = d.toLong()
    if (month !in 1..12 || day !in 1..31 || h.toInt() > 23 || mi.toInt() > 59 || s.toInt() > 60) return null
    val adjustedYear = if (month <= 2) year - 1 else year
    val era = Math.floorDiv(adjustedYear, 400L)
    val yoe = adjustedYear - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146_097 + doe - 719_468
    return ((days * 86_400 + h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000) + ms.toLong()
}

/** Why a scenario does not run against an adapter declaring [capabilities], or null when it runs. */
internal fun skipReason(scenario: J, capabilities: List<String>): String? {
    val missing = (scenario["capabilities"]?.arrayValue ?: emptyList()).mapNotNull { it.stringValue }.filter { it !in capabilities }
    if (missing.isNotEmpty()) return "Missing: " + missing.joinToString(",")
    val replacedBy = (scenario["unless"]?.arrayValue ?: emptyList()).mapNotNull { it.stringValue }.firstOrNull { it in capabilities }
    return replacedBy?.let { "superseded by $it" }
}
