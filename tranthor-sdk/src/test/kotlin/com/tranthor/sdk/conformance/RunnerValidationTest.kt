package com.tranthor.sdk.conformance

import com.tranthor.sdk.BatchFailure
import com.tranthor.sdk.DeliveryFailure
import com.tranthor.sdk.JsonValue
import com.tranthor.sdk.jsonObjectOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerValidationTest {
    private fun j(text: String): J = JsonValue.parse(text)
    private fun fixture(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.use { String(it.readBytes(), Charsets.UTF_8) }

    @Test fun missingHostFailsUnlessExplicitDeveloperOptOut() {
        assertThrows(CheckFailure::class.java) { conformanceHost(emptyMap(), allowDeveloperSkip = true) }
        assertThrows(CheckFailure::class.java) { conformanceHost(mapOf("CONFORMANCE_HOST" to ""), allowDeveloperSkip = true) }
        assertNull(conformanceHost(mapOf("CONFORMANCE_ALLOW_SKIP" to "1"), allowDeveloperSkip = true))
        assertThrows(CheckFailure::class.java) { conformanceHost(mapOf("CONFORMANCE_ALLOW_SKIP" to "1"), allowDeveloperSkip = false) }
        assertEquals("http://127.0.0.1:43189",
            conformanceHost(mapOf("CONFORMANCE_HOST" to "http://127.0.0.1:43189", "CONFORMANCE_ALLOW_SKIP" to "1"), allowDeveloperSkip = false))
    }

    @Test fun envelopeRejectsContextAndOtherWrapperKeys() {
        validateEnvelope(j("""{"batch":[]}"""))
        assertThrows(CheckFailure::class.java) { validateEnvelope(j("""{"batch":[],"context":{"library":"wrong"}}""")) }
        assertThrows(CheckFailure::class.java) { validateEnvelope(j("""{"batch":[],"unexpected":true}""")) }
        assertThrows(CheckFailure::class.java) { validateEnvelope(j("""{"batch":{}}""")) }
        // identityTokens is the one other key, a nonempty list of strings.
        validateEnvelope(j("""{"batch":[],"identityTokens":["a","b"]}"""))
        assertThrows(CheckFailure::class.java) { validateEnvelope(j("""{"batch":[],"identityTokens":[]}""")) }
        assertThrows(CheckFailure::class.java) { validateEnvelope(j("""{"batch":[],"identityTokens":[1]}""")) }
        assertThrows(CheckFailure::class.java) { validateEnvelope(j("""{"batch":[],"identityTokens":"a"}""")) }
        assertThrows(CheckFailure::class.java) { validateEnvelope(null) }
    }

    @Test fun failureDiagnosticsIncludeEveryRequestIndexTimeAndMessageId() {
        val records = listOf(
            j("""{"index":0,"receivedAtMs":1200,"headers":{"authorization":"never-log-header"},"body":{"batch":[{"messageId":"a"},{"messageId":"b"}]}}"""),
            j("""{"index":1,"receivedAtMs":1510,"body":{"batch":[{"messageId":"a"}]}}"""),
        )
        val reason = scenarioFailureReason(CheckFailure("Delay outside band"), records)
        assertTrue(reason.startsWith("Delay outside band; requests="))
        assertEquals(j("""[{"index":0,"receivedAtMs":1200,"messageIds":["a","b"]},{"index":1,"receivedAtMs":1510,"messageIds":["a"]}]"""),
            j(reason.substringAfter("; requests=")))
        assertFalse(reason.contains("never-log-header"))
    }

    @Test fun callbackSnapshotsKeepFifoOrder() {
        val log = ErrorLog()
        repeat(100) { log.append(BatchFailure(DeliveryFailure(400, "fixture"), listOf(jsonObjectOf("messageId" to "$it")), 1)) }
        assertEquals((0 until 100).map { "$it" }, log.snapshot().map { it.events.first()["messageId"]?.stringValue })
        assertEquals(100, log.snapshot().size)
    }

    @Test fun schemaRejectsUnknownFieldsAndMalformedSteps() {
        val schema = Schema(j(fixture("scenario.schema.json")))
        val original = j(fixture("scenarios/anonymous-track.json"))
        schema.validate(original)
        val map = original.objectValue!!
        assertThrows(CheckFailure::class.java) { schema.validate(JsonValue.ObjectValue(map + ("unexpected" to JsonValue.BooleanValue(true)))) }
        val config = map["config"]!!.objectValue!! + ("batchSize" to JsonValue.NumberValue(1.5))
        assertThrows(CheckFailure::class.java) { schema.validate(JsonValue.ObjectValue(map + ("config" to JsonValue.ObjectValue(config)))) }
        assertThrows(CheckFailure::class.java) { schema.validate(JsonValue.ObjectValue(map + ("server" to j("""{"responses":[{"status":429,"retryAfter":"1\n"}]}""")))) }
        val badSteps = j("""[{"op":"emit","call":"a","method":"track","data":{"userId":"u"}},{"op":"assert","expect":{"requests":[],"errors":[]}}]""")
        assertThrows(CheckFailure::class.java) { schema.validate(JsonValue.ObjectValue(map + ("steps" to badSteps))) }
    }

    @Test fun invalidReferencesAndDeclarationFail() {
        val scenario = j("""{"id":"case","capabilities":["core"],"steps":[{"op":"assert","expect":{"requests":[{"batch":[{"call":"absent"}]}],"errors":[]}}]}""")
        assertThrows(CheckFailure::class.java) { validateReferences(scenario) }
        Declaration("test", listOf("core"), "1", null, emptyList()).validate(listOf(scenario), "1")
        assertThrows(CheckFailure::class.java) { Declaration("test", listOf("core", "core"), "1", null, emptyList()).validate(listOf(scenario), "1") }
        assertThrows(CheckFailure::class.java) { Declaration("test", listOf("lifecycle"), "1", null, emptyList()).validate(listOf(scenario), "1") }
        assertThrows(CheckFailure::class.java) {
            Declaration("test", listOf("core"), "1", null, listOf(KnownFailure("absent", "r", "issue-1"))).validate(listOf(scenario), "1")
        }
    }

    @Test fun identityLinkStepsAndRefsRequireTheCapability() {
        val linked = j(fixture("scenarios/identify-links-anonymous-id.json"))
        Schema(j(fixture("scenario.schema.json"))).validate(linked)
        validateReferences(linked)
        val unlinked = JsonValue.ObjectValue(linked.objectValue!! + ("capabilities" to j("""["core"]""")))
        assertThrows(CheckFailure::class.java) { validateReferences(unlinked) }
        val refOnly = j("""{"id":"case","capabilities":["core"],"steps":[{"op":"emit","call":"a","method":"identify","data":{"userId":"u"}},{"op":"assert","expect":{"requests":[{"batch":[{"call":"a","anonymousIdRef":"v"}]}],"errors":[]}}]}""")
        assertThrows(CheckFailure::class.java) { validateReferences(refOnly) }
        val explicit = j("""{"id":"case","capabilities":["core","identity-link"],"steps":[{"op":"emit","call":"a","method":"identify","data":{"userId":"u","anonymousId":"x"}},{"op":"assert","expect":{"requests":[{"batch":[{"call":"a","anonymousIdRef":"v"}]}],"errors":[]}}]}""")
        assertThrows(CheckFailure::class.java) { validateReferences(explicit) }
        Declaration("test", listOf("core", "identity-link"), "1", null, emptyList()).validate(listOf(linked), "1")
    }

    @Test fun durableQueueCapabilityIsAccepted() {
        val scenario = j(fixture("scenarios/durable-exhausted-batch-retained.json"))
        Schema(j(fixture("scenario.schema.json"))).validate(scenario)
        Declaration("test", listOf("core", "durable-queue"), "1", null, emptyList()).validate(listOf(scenario), "1")
    }

    @Test fun scenarioReplacedByADeclaredCapabilityIsSkippedWithTheReason() {
        val replaced = j(fixture("scenarios/retries-exhausted.json"))
        assertEquals("superseded by durable-queue", skipReason(replaced, listOf("core", "transport", "durable-queue")))
        assertNull(skipReason(replaced, listOf("core", "transport")))
        val needsLifecycle = j("""{"id":"case","capabilities":["core","lifecycle"],"unless":["durable-queue"],"steps":[]}""")
        assertEquals("Missing: lifecycle", skipReason(needsLifecycle, listOf("core", "durable-queue")))
    }

    @Test fun knownFailureForAScenarioSkippedByUnlessFailsLoading() {
        val replaced = j(fixture("scenarios/retries-exhausted.json"))
        val declaration = Declaration("test", listOf("core", "transport", "durable-queue"), "1", null, listOf(KnownFailure("retries-exhausted", "r", "issue-1")))
        assertThrows(CheckFailure::class.java) { declaration.validate(listOf(replaced), "1") }
        Declaration("test", listOf("core", "transport"), "1", null, listOf(KnownFailure("retries-exhausted", "r", "issue-1"))).validate(listOf(replaced), "1")
    }

    @Test fun identityScenariosLoadAndTheirCapabilitiesAndRefsAreChecked() {
        val schema = Schema(j(fixture("scenario.schema.json")))
        val scenario = j(fixture("scenarios/identity-token-403-renews-and-retries.json"))
        schema.validate(scenario)
        validateReferences(scenario)
        Declaration("test", listOf("core", "transport", "identity-token", "app-id"), "1", null, emptyList()).validate(listOf(scenario), "1")
        // The provider's `else` branch rejects a ref on a non-token outcome; `not` and `else` are real keywords here.
        val badProvider = j("""{"result":"error","ref":"t1"}""")
        val config = scenario["config"]!!.objectValue!!
        val identity = JsonValue.ObjectValue(config["identity"]!!.objectValue!! + ("provider" to JsonValue.ArrayValue(listOf(badProvider))))
        assertThrows(CheckFailure::class.java) { schema.validate(JsonValue.ObjectValue(scenario.objectValue!! + ("config" to JsonValue.ObjectValue(config + ("identity" to identity))))) }
        // Identity fields require their capability, a ref is defined once, and a request may name only a defined ref.
        assertThrows(CheckFailure::class.java) { validateReferences(JsonValue.ObjectValue(scenario.objectValue!! + ("capabilities" to j("""["core"]""")))) }
        val duplicate = j("""{"id":"d","capabilities":["core","identity-token"],"steps":[
            {"op":"emit","call":"a","method":"identify","data":{"userId":"u"},"identityToken":{"ref":"t","sub":"u","ttlSeconds":1}},
            {"op":"emit","call":"b","method":"identify","data":{"userId":"u"},"identityToken":{"ref":"t","sub":"u","ttlSeconds":1}}]}""")
        assertThrows(CheckFailure::class.java) { validateReferences(duplicate) }
        val unknown = j("""{"id":"d","capabilities":["core","identity-token"],"steps":[{"op":"emit","call":"a","method":"track","data":{"userId":"u"}},
            {"op":"assert","expect":{"requests":[{"batch":[{"call":"a"}],"identityTokens":["never-defined"]}],"errors":[]}}]}""")
        assertThrows(CheckFailure::class.java) { validateReferences(unknown) }
        // A request may name only a ref that an earlier step defined.
        val later = j("""{"id":"d","capabilities":["core","identity-token"],"steps":[
            {"op":"emit","call":"a","method":"track","data":{"userId":"u"}},
            {"op":"assert","expect":{"requests":[{"batch":[{"call":"a"}],"identityTokens":["t"]}],"errors":[]}},
            {"op":"emit","call":"b","method":"identify","data":{"userId":"u"},"identityToken":{"ref":"t","sub":"u","ttlSeconds":1}}]}""")
        assertThrows(CheckFailure::class.java) { validateReferences(later) }
        val tokenOnTrack = j("""{"id":"d","capabilities":["core","identity-token"],"steps":[{"op":"emit","call":"a","method":"track","data":{"userId":"u"},"identityToken":{"ref":"t","sub":"u","ttlSeconds":1}}]}""")
        assertThrows(CheckFailure::class.java) { validateReferences(tokenOnTrack) }
    }

    @Test fun subsetChecksNestedObjectsButArraysExactly() {
        subset(j("""{"a":{"b":1}}"""), j("""{"a":{"b":1,"c":2}}"""), "item")
        assertThrows(CheckFailure::class.java) { subset(j("[1]"), j("[1,2]"), "array") }
        assertThrows(CheckFailure::class.java) { subset(j("""{"a":1}"""), j("""{"a":2}"""), "object") }
    }

    @Test fun timestampParserIsExactAndMatchesFormatter() {
        assertEquals(1_577_934_245_006L, parseTimestamp("2020-01-02T03:04:05.006Z"))
        assertEquals(0L, parseTimestamp("1970-01-01T00:00:00.000Z"))
        for (bad in listOf("2020-01-02T03:04:05Z", "2020-01-02T03:04:05.06Z", "2020-01-02T03:04:05.006+00:00", "2020-01-02T03:04:05.006Z\n")) {
            assertNull(bad, parseTimestamp(bad))
        }
    }
}
