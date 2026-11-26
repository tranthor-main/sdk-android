package com.tranthor.sdk.conformance

import com.tranthor.sdk.BatchFailure
import com.tranthor.sdk.Configuration
import com.tranthor.sdk.IdentityTimings
import com.tranthor.sdk.JsonValue
import com.tranthor.sdk.Notice
import com.tranthor.sdk.TranthorSDK
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Platform specifics of one runner target (JVM host or instrumented device). */
internal interface ConformanceEnvironment {
    val adapterName: String
    val capabilities: List<String>
    val lifecycleSource: String?
    fun readText(path: String): String
    fun scenarioFiles(): List<String>
    fun temporaryDirectory(): File
    suspend fun makeSdk(configuration: Configuration, timings: IdentityTimings): TranthorSDK
    /** Called before the SDK is created for each executed scenario. */
    suspend fun prepare(scenario: J) {}
    /** Delivers the real OS lifecycle signal (os-hook) and awaits it. */
    suspend fun background()
    suspend fun finish(scenario: J) {}
    fun emit(line: String)
}

/** Callbacks run serially on the SDK executor; a lock keeps snapshots FIFO-consistent. */
internal class ErrorLog {
    private val entries = ArrayList<BatchFailure>()
    @Synchronized fun append(failure: BatchFailure) { entries.add(failure) }
    @Synchronized fun snapshot(): List<BatchFailure> = ArrayList(entries)
}

/** Diagnostic notices, which are not errors: kept apart from [ErrorLog] and compared against `expect.notices`. */
internal class NoticeLog {
    private val entries = ArrayList<Notice>()
    @Synchronized fun append(notice: Notice) { entries.add(notice) }
    @Synchronized fun snapshot(): List<Notice> = ArrayList(entries)
}

/** [storedAnonymousId] is the SDK's stored anonymousId read right after the call (identity-link only). */
internal class Call(val method: String, val data: J, val before: Double, val after: Double, val storedAnonymousId: String? = null)
internal class Operation(val kind: String, val before: Double, val after: Double)

internal fun now(): Double = System.currentTimeMillis().toDouble()

internal class Summary(val passed: Int, val failed: Int, val skipped: Int, val xfailed: Int, val validated: Int)

/** Loads, validates and runs every scenario; emits one JSON line per scenario plus a summary. */
internal class ConformanceSuite(private val environment: ConformanceEnvironment, private val host: String) {
    suspend fun run(filter: String?): Summary {
        // Loopback mock only: 127.0.0.1 (JVM, or a device via adb reverse) or 10.0.2.2 (the emulator's host alias).
        require(host.startsWith("http://127.0.0.1:") || host.startsWith("http://10.0.2.2:"), "Conformance is local-only")
        val version = environment.readText("VERSION").trim()
        val schema = Schema(JsonValue.parse(environment.readText("scenario.schema.json")))
        val scenarios = environment.scenarioFiles().sorted().map { JsonValue.parse(environment.readText("scenarios/$it")) }
        require(scenarios.isNotEmpty(), "No scenarios found")
        val ids = HashSet<String>()
        // Validate every file before filtering or executing any scenario.
        for (scenario in scenarios) {
            schema.validate(scenario)
            validateReferences(scenario)
            val id = scenario["id"]?.stringValue ?: throw CheckFailure("Missing ID")
            require(ids.add(id), "Duplicate scenario ID")
        }
        val knownFailures = emptyList<KnownFailure>()
        val declaration = Declaration(environment.adapterName, environment.capabilities, version, environment.lifecycleSource, knownFailures)
        declaration.validate(scenarios, version)
        var passed = 0; var failed = 0; var skipped = 0; var xfailed = 0
        for (scenario in scenarios) {
            val id = scenario["id"]?.stringValue ?: ""
            if (filter != null && filter != id) continue // Diagnostic mode still validates all files.
            val skip = skipReason(scenario, environment.capabilities)
            if (skip != null) {
                skipped++
                environment.emit(obj("id" to str(id), "result" to str("skip"), "reason" to str(skip)).toJson())
                continue
            }
            val known = knownFailures.firstOrNull { it.id == id }
            try {
                environment.prepare(scenario)
                try { ScenarioRunner(environment, host, scenario).run() } finally { environment.finish(scenario) }
                if (known != null) {
                    failed++
                    environment.emit(obj("id" to str(id), "result" to str("fail"), "reason" to str("Unexpected pass; remove knownFailure")).toJson())
                } else {
                    passed++
                    environment.emit(obj("id" to str(id), "result" to str("pass")).toJson())
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (known != null) {
                    xfailed++
                    environment.emit(obj("id" to str(id), "result" to str("xfail"), "reason" to str("${known.reason} (${known.tracking}) $error")).toJson())
                } else {
                    failed++
                    environment.emit(obj("id" to str(id), "result" to str("fail"), "reason" to str(error.toString())).toJson())
                }
            }
        }
        environment.emit(obj(
            "adapter" to str(environment.adapterName),
            "capabilities" to arr(environment.capabilities.map(::str)),
            "suiteVersion" to str(version),
            "knownFailures" to arr(knownFailures.map { str(it.id) }),
            "validated" to num(scenarios.size),
            "passed" to num(passed), "failed" to num(failed), "skipped" to num(skipped), "xfailed" to num(xfailed),
            "lifecycleSource" to (environment.lifecycleSource?.let(::str) ?: JsonValue.Null),
        ).toJson())
        if (filter != null) require(ids.contains(filter), "Unknown diagnostic scenario")
        return Summary(passed, failed, skipped, xfailed, scenarios.size)
    }
}

internal class ScenarioRunner(
    private val environment: ConformanceEnvironment,
    private val host: String,
    private val scenario: J,
) {
    private val directory = File(environment.temporaryDirectory(), "tranthor-conformance-" + UUID.randomUUID())
    private val errors = ErrorLog()
    private val notices = NoticeLog()
    private var sdk: TranthorSDK? = null
    private val calls = HashMap<String, Call>()
    private val operations = HashMap<String, Operation>()
    private val stamps = HashMap<String, Pair<String, String>>()
    private val linking = environment.capabilities.contains("identity-link")
    private val anonymousIdRefs = HashMap<String, String>()
    private var offset = 0.0
    // Identity tokens are minted by the mock at the moment they are needed, so their expiry is relative to now.
    // Written from the SDK's provider thread (mint) and read from the runner's: concurrent.
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val providerCalls = ArrayList<String>()
    // The scripted provider belongs to the scenario, not to the SDK instance, so a restart keeps its position.
    private val providerScript = ArrayDeque(scenario["config"]?.get("identity")?.get("provider")?.arrayValue ?: emptyList())

    private suspend fun mint(spec: J): String {
        val body = linkedMapOf("sub" to spec["sub"]!!, "ttlSeconds" to spec["ttlSeconds"]!!)
        spec["valid"]?.let { body["valid"] = it }
        val token = http("/control/identity/token", JsonValue.ObjectValue(body))["token"]?.stringValue ?: throw CheckFailure("Mock did not mint a token")
        tokens[spec["ref"]?.stringValue ?: throw CheckFailure("Token without ref")] = token
        return token
    }

    /** One scripted outcome per call, in order; once the script is spent it answers null. */
    private suspend fun scriptedProvider(userId: String): String? {
        val outcome = synchronized(providerCalls) {
            providerCalls.add(userId)
            providerScript.removeFirstOrNull()
        } ?: obj("result" to str("null"))
        outcome["delayMs"]?.numberValue?.takeIf { it > 0 }?.let { delay(it.toLong()) }
        return when (outcome["result"]?.stringValue) {
            "hang" -> awaitCancellation()
            "error" -> throw IllegalStateException("identity provider failed")
            "token" -> mint(outcome)
            else -> null
        }
    }

    /** One ref names one stored anonymousId; distinct refs must hold distinct values. */
    private fun bindAnonymousId(ref: String, value: String?, label: String) {
        if (value.isNullOrEmpty()) throw CheckFailure("$label: anonymousId must be a nonempty string")
        val bound = anonymousIdRefs[ref]
        if (bound != null) { require(bound == value, "$label: anonymousId differs from ref $ref"); return }
        anonymousIdRefs.entries.firstOrNull { it.value == value }?.let { throw CheckFailure("$label: ref $ref reuses the anonymousId of ref ${it.key}") }
        anonymousIdRefs[ref] = value
    }

    private suspend fun http(path: String, body: J? = null): J = withContext(Dispatchers.IO) {
        val connection = URL(host + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.useCaches = false
            if (body != null) {
                val bytes = body.toJson().toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            require(status == 200, "Control API failed $path ($status)")
            JsonValue.parse(connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun makeSdk(): TranthorSDK {
        val config = scenario["config"] ?: obj()
        val log = errors
        return environment.makeSdk(Configuration(
            writeKey = config["writeKey"]?.stringValue ?: "",
            host = host + (config["hostSuffix"]?.stringValue ?: ""),
            batchSize = config["batchSize"]?.numberValue,
            flushIntervalMs = config["flushIntervalMs"]?.numberValue,
            retries = config["retries"]?.numberValue,
            baseDelay = config["baseDelay"]?.numberValue,
            // caller-overrides requires exact equality of the original context object.
            // Every other scenario exercises the production context default.
            automaticContext = scenario["id"]?.stringValue != "caller-overrides",
            storageNamespace = "scenario",
            storageDirectory = directory,
            onError = { log.append(it) },
            onNotice = { notices.append(it) },
            // The presence of `config.identity.provider` means the adapter configures a provider; timings alone do not.
            identityTokenProvider = if (config["identity"]?.get("provider") != null) ::scriptedProvider else null,
            appId = config["appId"]?.stringValue,
        ), IdentityTimings(
            providerTimeoutMs = config["identity"]?.get("providerTimeoutMs")?.numberValue?.toLong() ?: 20_000,
            providerBackoffMs = config["identity"]?.get("providerBackoffMs")?.numberValue?.toLong() ?: 60_000,
        ))
    }

    suspend fun run() {
        try {
            http("/control/reset", obj())
            val samples = ArrayList<Double>()
            repeat(3) {
                val before = now()
                val response = http("/control/time")
                val after = now()
                val server = response["nowMs"]?.numberValue ?: throw CheckFailure("Missing server clock")
                samples.add(server - (before + after) / 2)
            }
            offset = samples.sorted()[1]
            // The script takes `responses` only; the identity mode is a separate control call.
            http("/control/script", obj("responses" to (scenario["server"]?.get("responses") ?: arr(emptyList()))))
            scenario["server"]?.get("identity")?.get("mode")?.let { http("/control/identity", obj("mode" to it)) }
            sdk = makeSdk()
            for (step in scenario["steps"]?.arrayValue ?: emptyList()) execute(step)
        } catch (error: Throwable) {
            if (error is CancellationException) { dispose(); throw error }
            // Capture raw request timing/stamps before disposing.
            val reason = try { scenarioFailureReason(error, requests()) } catch (snapshot: Throwable) {
                "$error; requests unavailable: $snapshot"
            }
            dispose()
            throw CheckFailure(reason)
        }
        dispose()
    }

    private suspend fun dispose() {
        sdk?.shutdown(); sdk = null
        directory.deleteRecursively()
    }

    private suspend fun execute(step: J) {
        val current = sdk ?: throw CheckFailure("Missing SDK")
        val op = step["op"]?.stringValue ?: ""
        val before = now()
        when (op) {
            "emit" -> {
                val data = step["data"] ?: obj()
                val method = step["method"]?.stringValue ?: ""
                val user = data["userId"]?.stringValue
                val anonymous = data["anonymousId"]?.stringValue
                val id = data["messageId"]?.stringValue
                val time = data["timestamp"]?.stringValue
                val context = data["context"]?.objectValue
                val properties = data["properties"]?.objectValue
                // A token is minted first and handed to identify beside the data, never inside it.
                val identityToken = step["identityToken"]?.let { mint(it) }
                val callBefore = now()
                when (method) {
                    "identify" -> current.identify(user, anonymous, data["traits"]?.objectValue, id, time, context, identityToken)
                    "track" -> current.track(data["event"]?.stringValue ?: "", user, anonymous, properties, data["files"]?.arrayValue, id, time, context)
                    "page" -> current.page(data["name"]?.stringValue, user, anonymous, properties, id, time, context)
                    "screen" -> current.screen(data["name"]?.stringValue, user, anonymous, properties, id, time, context)
                    else -> throw CheckFailure("Unknown method")
                }
                val after = now()
                calls[step["call"]?.stringValue ?: ""] = Call(method, data, callBefore, after, if (linking) current.anonymousId else null)
            }
            "wait" -> delay((step["ms"]?.numberValue ?: 0.0).toLong())
            "flush" -> {
                val rejected = withTimeoutOrNull(5000) {
                    try { current.flush(); false } catch (error: Exception) { if (error is CancellationException) throw error; true }
                }
                if (rejected == null) {
                    current.shutdown()
                    throw CheckFailure("Flush did not settle within 5 seconds")
                }
                require(now() - before < 5000, "Flush exceeded its five-second deadline")
                step["rejected"]?.booleanValue?.let { require(it == rejected, "Flush rejected=$rejected, expected $it") }
            }
            "restart" -> {
                current.shutdown()
                sdk = null // Destroy the instance before recreating; never flush or re-emit.
                sdk = makeSdk()
            }
            "optOut" -> current.optOut()
            "optIn" -> current.optIn()
            "background" -> environment.background()
            "assert" -> assertExpectation(step)
            "anonymousId" -> bindAnonymousId(step["ref"]?.stringValue ?: "", current.anonymousId, "step")
            else -> throw CheckFailure("Unknown operation $op")
        }
        step["label"]?.stringValue?.let { operations[it] = Operation(op, before, now()) }
    }

    private suspend fun requests(): List<J> = http("/control/requests")["requests"]?.arrayValue ?: emptyList()

    private suspend fun assertExpectation(step: J) {
        val expect = step["expect"] ?: obj()
        val expectedRequests = expect["requests"]?.arrayValue ?: emptyList()
        val expectedErrors = expect["errors"]?.arrayValue ?: emptyList()
        val deadline = now() + (step["timeoutMs"]?.numberValue ?: 3000.0)
        while (now() < deadline) {
            val records = requests()
            val callbacks = errors.snapshot()
            if (records.size >= expectedRequests.size && callbacks.size >= expectedErrors.size &&
                notices.snapshot().size >= (expect["notices"]?.arrayValue?.size ?: 0)) break
            delay(10)
        }
        delay((step["observeMs"]?.numberValue ?: 100.0).toLong())
        val records = requests()
        val callbacks = errors.snapshot()
        for (record in records) validateEnvelope(record["body"])
        // A token travels only in `identityTokens`: never inside an event or an error report.
        for (record in records) for (item in record["body"]?.get("batch")?.arrayValue ?: emptyList()) {
            require(item["identityToken"] == null, "An item carries identityToken")
            val text = item.toJson()
            for (token in tokens.values) require(!text.contains(token), "An identity token is inside an event")
        }
        require(records.size == expectedRequests.size, "Request count ${records.size}, expected ${expectedRequests.size}")
        require(callbacks.size == expectedErrors.size, "Error count ${callbacks.size}, expected ${expectedErrors.size}")
        val wire = HashMap<String, J>()
        expectedRequests.forEachIndexed { index, expected ->
            val record = records[index]
            for (key in listOf("method", "path")) expected[key]?.let { subset(it, record[key], key) }
            for ((key, value) in expected["headers"]?.objectValue ?: emptyMap()) {
                if (key == "content-type") {
                    val media = record["headers"]?.get(key)?.stringValue?.split(';')?.first()?.trim()?.lowercase()
                    val wanted = value.stringValue?.split(';')?.first()?.trim()?.lowercase()
                    require(media == wanted, "Content-Type mismatch")
                } else {
                    subset(value, record["headers"]?.get(key), "header $key")
                }
            }
            expected["context"]?.let { subset(it, record["body"]?.get("context"), "batch context") }
            // Tokens are named by ref; an absent field and an empty list both mean no token.
            expected["identityTokens"]?.arrayValue?.let { refs ->
                val wanted = refs.map { tokens[it.stringValue ?: ""] ?: throw CheckFailure("Token ${it.stringValue} was never minted") }
                val actual = record["body"]?.get("identityTokens")?.arrayValue?.map { it.stringValue } ?: emptyList()
                require(actual == wanted, "Request $index identityTokens: expected refs ${refs.map { it.stringValue }}, got ${actual.map { token -> tokens.entries.firstOrNull { it.value == token }?.key ?: "<unknown>" }}")
            }
            val batch = record["body"]?.get("batch")?.arrayValue ?: emptyList()
            val items = expected["batch"]?.arrayValue ?: emptyList()
            require(batch.size == items.size, "Batch count request $index")
            items.forEachIndexed { position, item ->
                val label = item["call"]?.stringValue ?: ""
                val call = calls[label] ?: throw CheckFailure("Missing call $label")
                val actual = batch[position]
                subset(str(call.method), actual["type"], "$label.type")
                // Original values are exact, including nested objects, unlike fields subset checks.
                for ((key, original) in call.data.objectValue ?: emptyMap()) {
                    require(actual[key] == original, "Original call value changed $label.$key")
                }
                for (key in listOf("userId", "anonymousId")) {
                    if (call.data[key] != null) continue
                    if (linking && key == "anonymousId" && call.method == "identify" && !call.data["userId"]?.stringValue.isNullOrEmpty()) {
                        // identity-link: a login identify carries the anonymousId stored once the call returned.
                        require(actual[key]?.stringValue == call.storedAnonymousId, "Identify $label must carry the stored anonymousId")
                    } else {
                        require(actual[key] == null, "Unexpected identity $label.$key")
                    }
                }
                item["anonymousIdRef"]?.stringValue?.let { bindAnonymousId(it, actual["anonymousId"]?.stringValue, label) }
                item["fields"]?.let { subset(it, actual, label) }
                checkStamp(actual, call, label)
                wire[label] = actual
            }
        }
        for (error in callbacks) {
            val report = error.error.toString()
            for (token in tokens.values) {
                require(!report.contains(token), "An identity token is inside an error report")
                for (event in error.events) require(!JsonValue.ObjectValue(event).toJson().contains(token), "An identity token is inside an error event")
            }
        }
        for (notice in notices.snapshot()) {
            val report = notice.message + (notice.reason ?: "")
            for (token in tokens.values) require(!report.contains(token), "An identity token is inside a notice")
        }
        expect["notices"]?.arrayValue?.let { wanted ->
            // Notices are not errors: an ordered list of { kind, count?, reason? }, each listed field compared.
            val actual = notices.snapshot()
            require(actual.size == wanted.size, "Notice count ${actual.size}, expected ${wanted.size}: ${actual.map { it.kind }}")
            wanted.forEachIndexed { index, expected ->
                val notice = actual[index]
                require(expected["kind"]?.stringValue == notice.kind.name.lowercase().replace('_', '-'), "Notice $index kind ${notice.kind}")
                expected["count"]?.numberValue?.let { require(notice.count?.toDouble() == it, "Notice $index count ${notice.count}") }
                expected["reason"]?.stringValue?.let { require(notice.reason == it, "Notice $index reason ${notice.reason}") }
            }
        }
        expect["providerCalls"]?.arrayValue?.let { wanted ->
            val actual = synchronized(providerCalls) { ArrayList(providerCalls) }
            require(actual == wanted.map { it.stringValue }, "Provider calls $actual, expected ${wanted.map { it.stringValue }}")
        }
        expectedErrors.forEachIndexed { index, expected ->
            val error = callbacks[index]
            require(error.attempts.toDouble() == expected["attempts"]?.numberValue, "Error attempts mismatch")
            expected["status"]?.let { status ->
                require(status == (error.error.status?.let { num(it) } ?: JsonValue.Null), "Error status mismatch")
            }
            val labels = expected["eventCalls"]?.arrayValue ?: emptyList()
            require(error.events.size == labels.size, "Error event count mismatch")
            labels.forEachIndexed { position, label ->
                val event = wire[label.stringValue ?: ""] ?: throw CheckFailure("Callback call absent from wire")
                for (key in listOf("messageId", "type")) require(error.events[position][key] == event[key], "Callback $key mismatch")
            }
        }
        for (delay in expect["delays"]?.arrayValue ?: emptyList()) {
            val to = delay["to"]?.numberValue ?: throw CheckFailure("Missing to")
            val end = records[to.toInt()]["receivedAtMs"]?.numberValue ?: 0.0
            val fromIndex = delay["from"]?.numberValue
            val start = if (fromIndex != null) {
                records[fromIndex.toInt()]["receivedAtMs"]?.numberValue ?: 0.0
            } else {
                val label = delay["from"]?.stringValue ?: ""
                val before = calls[label]?.before ?: operations[label]?.before ?: throw CheckFailure("Missing delay source")
                Math.floor(before + offset)
            }
            val delta = end - start
            require(delta >= (delay["minMs"]?.numberValue ?: 0.0) && delta <= (delay["maxMs"]?.numberValue ?: 0.0), "Delay $delta outside ${delay.toJson()}")
        }
        for (boundary in expect["restartBetween"]?.arrayValue ?: emptyList()) {
            val operation = operations[boundary["label"]?.stringValue ?: ""]
            if (operation == null || operation.kind != "restart") throw CheckFailure("Missing restart")
            boundary["from"]?.numberValue?.let { from ->
                require((records[from.toInt()]["receivedAtMs"]?.numberValue ?: 0.0) <= Math.floor(operation.before + offset), "Restart begins before source request")
            }
            boundary["to"]?.numberValue?.let { to ->
                require(Math.floor(operation.after + offset) <= (records[to.toInt()]["receivedAtMs"]?.numberValue ?: 0.0), "Request arrived before restart finished")
            }
        }
    }

    private fun checkStamp(event: J, call: Call, label: String) {
        val id = event["messageId"]?.stringValue
        val time = event["timestamp"]?.stringValue
        if (id.isNullOrEmpty() || time == null) throw CheckFailure("Missing stamp")
        val prior = stamps[label]
        if (prior != null) {
            require(prior.first == id && prior.second == time, "Restamped $label")
        } else {
            if (call.data["messageId"] == null) {
                for ((other, stamp) in stamps) {
                    if (other != label && calls[other]?.data?.get("messageId") == null) require(stamp.first != id, "Duplicate generated ID")
                }
            }
            stamps[label] = id to time
        }
        if (call.data["timestamp"] == null) {
            val ms = parseTimestamp(time) ?: throw CheckFailure("Invalid timestamp format")
            require(ms >= call.before - 50 && ms <= call.after + 50, "Timestamp outside call-time band $label")
        }
    }
}
