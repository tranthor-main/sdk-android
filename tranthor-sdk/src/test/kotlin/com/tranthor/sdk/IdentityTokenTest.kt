package com.tranthor.sdk

import com.tranthor.testing.MiniHttpServer
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity tokens through the public API, asserted on what reaches a local HTTP server: the token cache and its
 * persistence, the provider, refused tokens and unreadable identity settings, the hold and the opt-out removal.
 */
class IdentityTokenTest {
    private val requests = CopyOnWriteArrayList<MiniHttpServer.Request>()
    @Volatile private var respond: (Int, MiniHttpServer.Request) -> MiniHttpServer.Response = { _, _ -> MiniHttpServer.Response(204) }
    private val server = MiniHttpServer { request -> val index = requests.size; requests.add(request); respond(index, request) }
    private val root: File = tempRoot("tranthor-identity-token")
    private val notices = CopyOnWriteArrayList<Notice>()
    private val errors = FailureLog()

    @After fun stop() { server.close(); root.deleteRecursively() }

    private fun b64(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    /** An unsigned JWT-shaped string: the SDK never verifies, so its payload is all that matters. */
    private fun jwt(user: String, expSeconds: Long, nonce: String = "n") =
        "${b64("""{"alg":"HS256","typ":"JWT"}""")}.${b64("""{"sub":"$user","exp":$expSeconds,"jti":"$nonce"}""")}.sig"
    private val now = System.currentTimeMillis()
    private fun valid(user: String, nonce: String = "n") = jwt(user, now / 1000 + 3600, nonce)

    private fun body(index: Int) = JsonValue.parse(requests[index].body.toString(Charsets.UTF_8))
    private fun tokens(index: Int): List<String> = body(index)["identityTokens"]?.arrayValue?.map { it.stringValue!! } ?: emptyList()
    private fun users(index: Int): List<String?> = body(index)["batch"]!!.arrayValue!!.map { it["userId"]?.stringValue }
    private fun refusal(userIds: List<String>, reason: String = "missing") = MiniHttpServer.Response(403,
        mapOf("Content-Type" to "application/json"),
        """{"message":"m","code":"identity_not_verified","reason":"$reason","userIds":[${userIds.joinToString(",") { "\"$it\"" }}]}""".toByteArray())

    private suspend fun sdk(
        // A batch size no test reaches and an interval no sleeper wait matches: delivery starts only at flush().
        provider: (suspend (String) -> String?)? = null, batchSize: Int = 100, flushIntervalMs: Int = 61_000, retries: Int = 0,
        appId: String? = null, sleeper: ManualSleeper? = null, clock: () -> Long = System::currentTimeMillis,
        timings: IdentityTimings = IdentityTimings(), packageName: String = "", namespace: String = "identity",
        maxQueueSize: Int = 10_000,
    ) = configureJvm(Configuration("Basic identity-fixture==", host = server.url, batchSize = batchSize, flushIntervalMs = flushIntervalMs,
        retries = retries, baseDelay = 10, storageNamespace = namespace, storageDirectory = root, maxQueueSize = maxQueueSize,
        onError = { errors.append(it) }, onNotice = { notices.add(it) }, identityTokenProvider = provider, appId = appId),
        sleeper = sleeper, platform = JvmPlatform(clock = clock, packageName = packageName), identityTimings = timings)

    private fun stored(namespace: String = "identity"): StoredState = loadState(root, namespace)

    // ---- JWT exp ----

    @Test fun jwtExpIsReadWithoutVerifyingAndBadTokensGiveNull() {
        assertEquals(1_900_000_000L, decodeJwtExp(jwt("u", 1_900_000_000)))
        assertEquals(1_900_000_000L, decodeJwtExp(jwt("u", 1_900_000_000).replace("sig", "not-even-a-signature")))
        // Fractional seconds floor; the payload is read, not trusted.
        assertEquals(5L, decodeJwtExp("${b64("{}")}.${b64("""{"exp":5.9}""")}.x"))
        for (bad in listOf("", "a.b", "a.b.c.d", "a..c", "${b64("{}")}.${b64("[]")}.x", "${b64("{}")}.${b64("""{"exp":"soon"}""")}.x",
            "${b64("{}")}.${b64("""{"sub":"u"}""")}.x", "${b64("{}")}.${b64("""{"exp":-1}""")}.x", "${b64("{}")}.${b64("""{"exp":1e300}""")}.x",
            "${b64("{}")}.${b64("not json")}.x", "${b64("{}")}.%%%.x", "${b64("{}")}.${"A".repeat(5)}.x")) {
            assertNull("[$bad]", decodeJwtExp(bad))
        }
    }

    @Test fun tokenCacheTreatsAnUnreadableExpAsFreshAndNeverCrossesUsers() {
        val cache = TokenCache()
        cache.set("u1", "opaque-token")
        cache.set("u2", jwt("u2", 1_000))
        assertEquals("opaque-token", cache.getFresh("u1", 9_999_999_999_999, RENEW_BELOW_MS))
        assertEquals("a token with 60 s left or more", jwt("u2", 1_000), cache.getFresh("u2", 940_000, RENEW_BELOW_MS))
        assertNull("less than 60 s left", cache.getFresh("u2", 940_001, RENEW_BELOW_MS))
        assertNull(cache.getFresh("u3", 0, 0))
        assertFalse(cache.toString().contains("opaque-token"))
    }

    // ---- app id ----

    @Test fun appIdIsSentOnEveryRequestAndDefaultsToThePackageName() = runBlocking {
        val explicit = sdk(appId = "com.example.explicit", packageName = "com.example.package")
        explicit.track("a", userId = "u1"); explicit.flush(); explicit.track("b", userId = "u1"); explicit.flush(); explicit.shutdown()
        assertEquals(listOf("com.example.explicit", "com.example.explicit"), requests.map { it.headers["x-tranthor-app-id"] })
        requests.clear()
        val defaulted = sdk(packageName = "com.example.package", namespace = "default-app")
        defaulted.track("a", userId = "u1"); defaulted.flush(); defaulted.shutdown()
        assertEquals("com.example.package", requests.single().headers["x-tranthor-app-id"])
        requests.clear()
    }

    // ---- sending ----

    @Test fun aTokenIsSentBesideTheEventsOfItsOwnUserAndNeverInsideAnEvent() = runBlocking {
        val t1 = valid("u1")
        val sdk = sdk(batchSize = 3)
        sdk.identify("u1", identityToken = t1)
        sdk.track("b", userId = "u2")
        sdk.track("c") // the stored user: u1
        sdk.flush()
        assertEquals(listOf(t1), tokens(0))
        assertEquals(listOf("u1", "u2", "u1"), users(0))
        assertFalse("never inside an event", body(0)["batch"]!!.toJson().contains(t1))
        assertNull(body(0)["batch"]!!.arrayValue!![0]["identityToken"])
        sdk.track("d", userId = "u2")
        sdk.flush()
        assertEquals("a user without a token sends none", emptyList<String>(), tokens(1))
        assertNull("the field is omitted, not empty", body(1)["identityTokens"])
        sdk.shutdown()
    }

    @Test fun aBatchOfTwoUsersCarriesOneTokenPerUserInEventOrder() = runBlocking {
        val sdk = sdk(batchSize = 3)
        sdk.identify("u2", identityToken = valid("u2", "two"))
        sdk.identify("u1", identityToken = valid("u1", "one"))
        sdk.track("again", userId = "u2")
        sdk.flush()
        assertEquals(listOf(valid("u2", "two"), valid("u1", "one")), tokens(0))
        sdk.shutdown()
    }

    @Test fun anIdentifyWithoutAUserKeepsNoToken() = runBlocking {
        val sdk = sdk()
        sdk.identify(null, identityToken = valid("ghost"))
        sdk.identify("", identityToken = valid("ghost"))
        sdk.shutdown()
        assertTrue(stored().tokens.isEmpty())
    }

    @Test fun aTokenIsAttachedUntilTwoMinutesAfterItsExpiry() = runBlocking {
        var clock = now
        val sdk = sdk(clock = { clock })
        sdk.identify("u1", identityToken = jwt("u1", now / 1000 + 10))
        clock = now + 10_000 + 119_000 // exp has passed; the server still accepts it
        sdk.flush()
        assertEquals(1, tokens(0).size)
        clock = now + 10_000 + 121_000
        sdk.track("later"); sdk.flush()
        assertEquals("withheld once the server would refuse it", emptyList<String>(), tokens(1))
        sdk.shutdown()
    }

    @Test fun withoutIdentityTheBatchKeepsItsSizeAndWithIdentityItIsCutAtTenUsers() = runBlocking {
        val plain = sdk(batchSize = 12, namespace = "plain")
        for (user in 1..12) plain.track("e", userId = "user-$user")
        plain.flush(); plain.shutdown()
        assertEquals("no identity in use: batching is unchanged", listOf(12), requests.map { users(requests.indexOf(it)).size })
        requests.clear()
        val identity = sdk(provider = { null }, batchSize = 12, namespace = "identity-in-use")
        for (user in 1..12) identity.track("e", userId = "user-$user")
        identity.flush(); identity.shutdown()
        assertEquals(listOf(10, 2), requests.indices.map { users(it).size })
        assertEquals((1..10).map { "user-$it" }, users(0))
    }

    // ---- the cache's life ----

    @Test fun aTokenIsKeptWhileItsUserIsCurrentOrHasEventsWaitingAndNotLonger() = runBlocking {
        val sdk = sdk(batchSize = 10, namespace = "eviction")
        sdk.identify("u1", identityToken = valid("u1"))
        sdk.identify("u2", identityToken = valid("u2"))
        assertEquals("u1 still has an event waiting", setOf("u1", "u2"), stored("eviction").tokens.users.toSet())
        sdk.flush()
        assertEquals("u1 is sent and no longer current", listOf("u2"), stored("eviction").tokens.users)
        sdk.identify("u3", identityToken = valid("u3"))
        sdk.flush()
        assertEquals(listOf("u3"), stored("eviction").tokens.users)
        sdk.track("still queued") // an event of u3 that has not been sent yet
        sdk.reset()
        assertEquals("the outgoing user keeps its token until its events are sent", listOf("u3"), stored("eviction").tokens.users)
        sdk.flush()
        assertTrue(stored("eviction").tokens.isEmpty())
        sdk.shutdown()
    }

    @Test fun aFailedAdmissionLeavesTheCacheAsItWas() = runBlocking {
        var failWrites = false
        val sdk = configureJvm(Configuration("k", host = server.url, storageNamespace = "failed-admit", storageDirectory = root, flushIntervalMs = 60_000),
            storeWrite = { bytes, file -> if (failWrites) throw java.io.IOException("disk full") else TempRenameFileIo.write(file, bytes) })
        sdk.identify("u1", identityToken = valid("u1"))
        failWrites = true
        assertThrows(TranthorException.StoreTemporarilyUnavailable::class.java) { runBlocking { sdk.identify("u2", identityToken = valid("u2")) } }
        failWrites = false
        sdk.shutdown()
        assertEquals(listOf("u1"), stored("failed-admit").tokens.users)
    }

    // ---- persistence ----

    @Test fun theCacheSurvivesARestartAndIsSentFromTheNewInstance() = runBlocking {
        val t1 = valid("u1")
        val first = sdk(namespace = "restart")
        first.identify("u1", identityToken = t1)
        first.shutdown()
        assertEquals(t1, stored("restart").tokens.get("u1"))
        assertEquals("an optional key in the existing state file: no new version", StoredState.CURRENT_VERSION.toDouble(),
            JsonValue.parse(File(root, "${"restart".toByteArray().joinToString("") { "%02x".format(it) }}/state.json").readText())["version"]?.numberValue)
        val second = sdk(namespace = "restart")
        second.flush()
        assertEquals(listOf(t1), tokens(0))
        second.shutdown()
    }

    @Test fun aDamagedStoredTokenIsDroppedNotTreatedAsStateDamage() {
        val state = StoredState.decode("""{"version":1,"anonymousId":"${java.util.UUID.randomUUID()}","identityTokens":{"u1":"good-token","u2":5,"u3":"","u4":null}}""".toByteArray())
        assertEquals(listOf("u1"), state.tokens.users)
        val noKey = StoredState.decode("""{"version":1,"anonymousId":"${java.util.UUID.randomUUID()}"}""".toByteArray())
        assertTrue(noKey.tokens.isEmpty())
        val notAnObject = StoredState.decode("""{"version":1,"anonymousId":"${java.util.UUID.randomUUID()}","identityTokens":"x"}""".toByteArray())
        assertTrue(notAnObject.tokens.isEmpty())
    }

    @Test fun aTokenNeverLeavesThroughToStringLogsOrCallbacks() = runBlocking {
        val secret = valid("u1", "the-secret-nonce")
        val config = Configuration("Basic key==", host = server.url, identityTokenProvider = { secret })
        assertFalse(config.toString().contains("the-secret-nonce"))
        assertFalse(StoredState(tokens = TokenCache().also { it.set("u1", secret) }).tokens.toString().contains("the-secret-nonce"))
        respond = { _, _ -> refusal(listOf("u1"), "signature") }
        val sdk = sdk(namespace = "leak")
        sdk.identify("u1", identityToken = secret)
        runCatching { sdk.flush() }
        sdk.shutdown()
        val everything = notices.joinToString { it.toString() } + errors.snapshot().joinToString { it.toString() + it.events }
        assertFalse(everything.contains("the-secret-nonce"))
    }

    // ---- the provider ----

    @Test fun anExpiringTokenIsRenewedThroughTheProviderBeforeSending() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val renewed = valid("u1", "renewed")
        val sdk = sdk(provider = { user -> calls.add(user); renewed })
        sdk.identify("u1", identityToken = jwt("u1", now / 1000 + 30))
        sdk.flush()
        assertEquals(listOf("u1"), calls)
        assertEquals(listOf(renewed), tokens(0))
        sdk.shutdown()
    }

    @Test fun aFreshTokenIsNotRenewed() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val sdk = sdk(provider = { calls.add(it); null })
        sdk.identify("u1", identityToken = valid("u1"))
        sdk.flush()
        assertTrue(calls.isEmpty())
        sdk.shutdown()
    }

    @Test fun aThrowingOrEmptyProviderNeverDelaysDeliveryAndIsNotCalledAgainForSixtySeconds() = runBlocking {
        for ((name, provider) in listOf<Pair<String, suspend (String) -> String?>>(
            "throws" to { throw IllegalStateException("provider failed") },
            "cancels" to { throw kotlinx.coroutines.CancellationException("provider cancelled itself") },
            "null" to { null },
        )) {
            requests.clear()
            val sleeper = ManualSleeper() // nothing is ever released: any wait on it would hang the test
            var clock = now
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val sdk = sdk(provider = { calls.incrementAndGet(); provider(it) }, sleeper = sleeper, clock = { clock }, namespace = "throws-$name")
            sdk.track("a", userId = "u1")
            sdk.flush() // returns without releasing a sleeper, so nothing waited on the provider
            sdk.track("b", userId = "u1"); sdk.flush()
            assertEquals("$name: both batches delivered at once", 2, requests.size)
            assertEquals("$name: the provider is not called again inside its backoff", 1, calls.get())
            clock += 60_001
            sdk.track("c", userId = "u1"); sdk.flush()
            assertEquals("$name: called again after 60 s", 2, calls.get())
            sdk.shutdown()
        }
    }

    @Test fun aHangingProviderDelaysOneSendByTheTwentySecondTimeoutAndThenBacksOff() = runBlocking {
        val sleeper = ManualSleeper()
        var clock = now
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val sdk = sdk(provider = { calls.incrementAndGet(); awaitCancellation() }, sleeper = sleeper, clock = { clock })
        sdk.track("a", userId = "u1")
        val firstFlush = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { sdk.flush() }
        val timeout = sleeper.wait(20_000.0) // the default provider timeout, the only wait there is
        delay(100)
        assertEquals("the batch waits for the provider", 0, requests.size)
        sleeper.release(timeout)
        firstFlush.join()
        assertEquals("then it is sent, without a token", listOf(emptyList<String>()), requests.indices.map { tokens(it) })
        sdk.track("b", userId = "u1"); sdk.flush() // inside the 60 s backoff: no second wait
        assertEquals(2, requests.size)
        assertEquals(1, calls.get())
        sdk.shutdown()
    }

    @Test fun aLateProviderAnswerAfterTheTimeoutIsDiscarded() = runBlocking {
        val sleeper = ManualSleeper()
        val gate = kotlinx.coroutines.CompletableDeferred<String?>()
        val late = valid("u1", "late")
        val sdk = sdk(provider = { gate.await() }, sleeper = sleeper)
        sdk.track("a", userId = "u1")
        val flushing = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { sdk.flush() }
        sleeper.release(sleeper.wait(20_000.0))
        flushing.join()
        gate.complete(late)
        delay(100)
        sdk.track("b", userId = "u1"); sdk.flush()
        assertEquals("a late answer is not cached", emptyList<String>(), tokens(1))
        sdk.shutdown()
    }

    // ---- a refused identity token ----

    @Test fun aRefusedTokenIsRenewedAndTheSameBatchIsSentAgainOnce() = runBlocking {
        val renewed = valid("u1", "renewed")
        respond = { index, _ -> if (index == 0) refusal(listOf("u1"), "signature") else MiniHttpServer.Response(204) }
        val sdk = sdk(provider = { renewed })
        sdk.identify("u1", identityToken = valid("u1", "bad"))
        sdk.flush()
        assertEquals(listOf(listOf(valid("u1", "bad")), listOf(renewed)), requests.indices.map { tokens(it) })
        assertEquals(users(0), users(1))
        assertTrue(errors.snapshot().isEmpty())
        sdk.shutdown()
    }

    @Test fun aRefusalForAPreviousUserDropsOnlyThatUsersItemsAndReportsThemOnce() = runBlocking {
        respond = { index, _ -> if (index == 0) refusal(listOf("u1")) else MiniHttpServer.Response(204) }
        val sdk = sdk(batchSize = 2, namespace = "previous")
        sdk.identify("u1", identityToken = valid("u1"))
        sdk.identify("u2", identityToken = valid("u2"))
        sdk.flush() // rejected = false: the current user's items went through
        assertEquals(listOf(listOf("u1", "u2"), listOf("u2")), requests.indices.map { users(it) })
        val failure = errors.snapshot().single()
        assertEquals(403, failure.error.status)
        assertEquals("identity not verified", failure.error.message)
        assertEquals(1, failure.attempts)
        assertEquals(listOf("u1"), failure.events.map { it["userId"]?.stringValue })
        assertFalse(failure.events.toString().contains(valid("u1")))
        assertEquals("the dropped user's token is gone, the current user's stays", listOf("u2"), stored("previous").tokens.users)
        sdk.shutdown()
    }

    @Test fun withNoTokenForTheCurrentUserTheBatchIsHeldNotDroppedAndAFlushTriesAgain() = runBlocking {
        val sleeper = ManualSleeper()
        respond = { index, _ -> if (index < 2) refusal(listOf("u1")) else MiniHttpServer.Response(204) }
        val sdk = sdk(provider = { null }, sleeper = sleeper, namespace = "hold")
        sdk.identify("u1")
        val firstFlush = assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        assertEquals(403, firstFlush.status)
        assertEquals(IDENTITY_NOT_VERIFIED, firstFlush.code)
        val hold = sleeper.wait(60_000.0) // the hold lasts the provider backoff, 60 s by default
        assertTrue("nothing is reported as dropped", errors.snapshot().isEmpty())
        assertEquals(IDENTITY_REFUSED_REASON, listOf(NoticeKind.IDENTITY_REFUSED to "missing"), notices.map { it.kind to it.reason })
        // A new event does not start an attempt while held, and the queue keeps the held event first.
        sdk.track("while held", userId = "u1")
        delay(150)
        assertEquals(1, requests.size)
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } } // an explicit flush ends the hold, tries once more, reports it
        assertEquals(2, requests.size)
        sleeper.waitCancellation(hold) // the explicit flush ended the first hold; the second one is the live sleeper now
        sleeper.release(sleeper.wait(60_000.0))
        waitUntil { requests.size >= 3 }
        val resent = body(2)["batch"]!!.arrayValue!!
        assertEquals("the held event goes first, in its original order and with its stamps", body(0)["batch"]!!.arrayValue!![0], resent[0])
        assertEquals("then the one queued while it was held", "while held", resent[1]["event"]?.stringValue)
        sdk.shutdown()
    }

    @Test fun aRefusalOfATokenJustObtainedIsNotRenewedAgainAndNamesTheServersReason() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        respond = { _, _ -> refusal(listOf("u1"), "signature") }
        val sleeper = ManualSleeper()
        val sdk = sdk(provider = { calls.incrementAndGet(); valid("u1", "t${calls.get()}") }, sleeper = sleeper)
        sdk.identify("u1")
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        assertEquals("one provider call, one request: no loop", 1, calls.get())
        assertEquals(1, requests.size)
        val notice = notices.single()
        assertEquals(NoticeKind.IDENTITY_REFUSED, notice.kind)
        assertEquals("signature", notice.reason)
        assertTrue(notice.message, notice.message.contains("check the app's token signing"))
        assertTrue(errors.snapshot().isEmpty())
        sdk.shutdown()
    }

    @Test fun aRefusalWithNoUserToActOnIsAFailedSendWithTheNormalBackoff() = runBlocking {
        respond = { index, _ -> if (index == 0) refusal(listOf("u1")) else MiniHttpServer.Response(204) }
        val sdk = sdk(retries = 2, provider = { null })
        sdk.track("anonymous")
        sdk.flush()
        assertEquals(2, requests.size)
        assertEquals(NoticeKind.IDENTITY_REFUSED, notices.single().kind)
        sdk.shutdown()
    }

    @Test fun otherUsersEventsInAHeldBatchAreSentSeparately() = runBlocking {
        val sleeper = ManualSleeper()
        respond = { index, request ->
            val first = JsonValue.parse(request.body.toString(Charsets.UTF_8))["batch"]!!.arrayValue!!.map { it["userId"]?.stringValue }
            if ("u1" in first) refusal(listOf("u1")) else MiniHttpServer.Response(204)
        }
        val sdk = sdk(batchSize = 2, sleeper = sleeper, namespace = "split")
        sdk.identify("u2", identityToken = valid("u2"))
        sdk.identify("u1")
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        assertEquals(listOf(listOf("u2", "u1"), listOf("u2")), requests.indices.map { users(it) })
        assertEquals(listOf(valid("u2")), tokens(1))
        sleeper.wait(60_000.0)
        sdk.shutdown()
        assertEquals("the held event is still queued", listOf("u1"), savedEvents(root, "split").map { it["userId"]?.stringValue })
    }

    @Test fun aFailureOfTheRemainderStillHoldsTheRefusedUsersEvents() = runBlocking {
        val sleeper = ManualSleeper()
        // u1 is refused (no token); the rest of the batch (u2) is then refused for good, which must not release u1's events.
        respond = { _, request ->
            val first = JsonValue.parse(request.body.toString(Charsets.UTF_8))["batch"]!!.arrayValue!!.map { it["userId"]?.stringValue }
            if ("u1" in first) refusal(listOf("u1")) else MiniHttpServer.Response(400)
        }
        val sdk = sdk(batchSize = 2, sleeper = sleeper, namespace = "remainder-fails")
        sdk.identify("u2", identityToken = valid("u2"))
        sdk.identify("u1")
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        delay(300)
        assertEquals("u1's events were not sent without a token", listOf(listOf("u2", "u1"), listOf("u2")), requests.indices.map { users(it) })
        sleeper.wait(60_000.0) // the hold is running
        assertEquals(listOf("u1"), savedEvents(root, "remainder-fails").map { it["userId"]?.stringValue })
        sdk.shutdown()
    }

    @Test fun anAppThatNeverIdentifiesKeepsItsFirstUserAsCurrentAfterARestart() = runBlocking {
        val renewed = valid("u1", "renewed")
        val first = sdk(namespace = "never-identifies")
        first.track("queued before restart", userId = "u1")
        first.shutdown()
        val second = sdk(provider = { renewed }, namespace = "never-identifies")
        second.flush()
        // The restored queue's user is the current one, so the provider is asked for its token before the send.
        assertEquals(listOf(listOf(renewed)), requests.indices.map { tokens(it) })
        assertTrue(errors.snapshot().isEmpty())
        second.shutdown()
    }

    @Test fun pushRegisteredAfterSignInWithATokenIsSentAfterAnEarlierRefusedIdentifyHeldTheBatch() = runBlocking {
        val sleeper = ManualSleeper()
        val token = valid("u1")
        // Required, as far as this test needs: a request for u1 without its token is refused.
        respond = { _, request ->
            val sent = JsonValue.parse(request.body.toString(Charsets.UTF_8))["identityTokens"]?.arrayValue.orEmpty()
            if (sent.isEmpty()) refusal(listOf("u1")) else MiniHttpServer.Response(204)
        }
        val sdk = sdk(provider = { null }, sleeper = sleeper, namespace = "push-after-sign-in")
        // The token is forwarded before anyone is signed in: held on the device, nothing sent, nothing dropped by the server.
        sdk.registerInstallation("fcm-token-1", "com.example", InstallationPermission.GRANTED)
        sdk.flush()
        assertEquals(0, requests.size)
        sdk.identify("u1") // signed in, but the token is not ready yet
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        assertEquals(1, requests.size)
        sdk.identify("u1", identityToken = token)
        sdk.flush() // ends the hold and tries again with the token
        val events = body(requests.size - 1)["batch"]!!.arrayValue!!.mapNotNull { it["event"]?.stringValue }
        assertTrue(events.toString(), "Installation Registered" in events)
        assertEquals(listOf(token), tokens(requests.size - 1))
        assertEquals("nothing was dropped", 0, errors.snapshot().size)
        sdk.shutdown()
    }

    @Test fun anAppThatNeverUsesIdentityTokensKeepsTheOldBehaviourOnARefusal() = runBlocking {
        respond = { _, _ -> refusal(listOf("u1")) }
        val sdk = sdk(namespace = "never-uses-identity")
        sdk.track("a", userId = "u1")
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        assertEquals("reported and dropped, not held", 1, errors.snapshot().size)
        assertEquals(1, requests.size)
        assertTrue(savedEvents(root, "never-uses-identity").isEmpty())
        sdk.shutdown()
    }

    @Test fun anIdentifyWithAFreshTokenEndsAHoldAtOnce() = runBlocking {
        val sleeper = ManualSleeper()
        respond = { _, request ->
            if (JsonValue.parse(request.body.toString(Charsets.UTF_8))["identityTokens"]?.arrayValue.orEmpty().isEmpty()) refusal(listOf("u1"))
            else MiniHttpServer.Response(204)
        }
        val sdk = sdk(provider = { null }, sleeper = sleeper, namespace = "identify-ends-hold")
        sdk.identify("u1")
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        assertEquals(1, requests.size)
        val token = valid("u1")
        sdk.identify("u1", identityToken = token) // no flush: the hold ends and the batch goes
        waitUntil { requests.size >= 2 }
        assertEquals(listOf(token), tokens(1))
        sdk.shutdown()
    }

    @Test fun anExpiredTokenAndAnOptedOutUsersTokenAreEvictedFromMemoryAndDisk() = runBlocking {
        var clock = now
        val sdk = sdk(clock = { clock }, namespace = "evict")
        sdk.identify("u1", identityToken = jwt("u1", now / 1000 + 10))
        sdk.flush()
        assertEquals(listOf("u1"), stored("evict").tokens.users)
        clock = now + 10_000 + 121_000 // long past its expiry
        sdk.track("later"); sdk.flush()
        assertTrue("expired token evicted", stored("evict").tokens.isEmpty())
        val fresh = valid("u1", "fresh")
        sdk.identify("u1", identityToken = fresh)
        sdk.flush()
        assertEquals(listOf("u1"), stored("evict").tokens.users)
        sdk.optOut()
        assertTrue("opt-out removes the current user's token once nothing needs it", stored("evict").tokens.isEmpty())
        sdk.shutdown()
        // ... and it is gone from disk after a restart too.
        val again = sdk(namespace = "evict")
        again.shutdown()
        assertTrue(stored("evict").tokens.isEmpty())
    }

    @Test fun aWriteKeyRestrictionIsTerminalNotHeld() = runBlocking {
        respond = { _, _ -> MiniHttpServer.Response(403, mapOf("Content-Type" to "application/json"), """{"code":"write_key_app_not_allowed","message":"no"}""".toByteArray()) }
        val sdk = sdk(provider = { valid("u1") })
        sdk.identify("u1")
        val failure = assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        assertEquals("write_key_app_not_allowed", failure.code)
        assertEquals(1, errors.snapshot().size)
        assertEquals(1, requests.size)
        sdk.shutdown()
    }

    @Test fun anErrorBodyThatIsNotJsonStaysAnOrdinaryRefusal() = runBlocking {
        respond = { _, _ -> MiniHttpServer.Response(403, body = "<html>forbidden</html>".toByteArray()) }
        val sdk = sdk()
        sdk.track("a", userId = "u1")
        val failure = assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        assertEquals(403, failure.status)
        assertNull(failure.code)
        sdk.shutdown()
    }

    // ---- identity settings the server cannot read ----

    @Test fun anUnavailableIdentityServiceHoldsTheBatchForRetryAfterEvenWithNoRetries() = runBlocking {
        val sleeper = ManualSleeper()
        respond = { index, _ -> if (index == 0) MiniHttpServer.Response(503, mapOf("Retry-After" to "120", "Content-Type" to "application/json"),
            """{"code":"identity_settings_unavailable"}""".toByteArray()) else MiniHttpServer.Response(204) }
        val sdk = sdk(sleeper = sleeper, retries = 0)
        sdk.track("a", userId = "u1")
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        val hold = sleeper.wait(120_000.0) // the server's Retry-After, not clamped to 30 s
        assertTrue(errors.snapshot().isEmpty())
        assertEquals(NoticeKind.IDENTITY_UNAVAILABLE, notices.single().kind)
        sleeper.release(hold)
        waitUntil { requests.size == 2 }
        assertEquals(body(0)["batch"], body(1)["batch"])
        sdk.shutdown()
    }

    @Test fun theHoldHonoursRetryAfterUpToFiveMinutesAndDefaultsToThirtySeconds() = runBlocking {
        for ((header, expected) in listOf("900" to 300_000.0, "0" to 1_000.0, null to 30_000.0)) {
            requests.clear(); notices.clear()
            val sleeper = ManualSleeper()
            respond = { _, _ -> MiniHttpServer.Response(503, listOfNotNull(header?.let { "Retry-After" to it }, "Content-Type" to "application/json").toMap(),
                """{"code":"identity_secrets_unavailable"}""".toByteArray()) }
            val sdk = sdk(sleeper = sleeper, namespace = "unavailable-$header")
            sdk.track("a", userId = "u1")
            assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
            sleeper.wait(expected)
            sdk.shutdown()
        }
    }

    @Test fun anotherFiveHundredThreeKeepsItsOrdinaryRetryBudget() = runBlocking {
        respond = { _, _ -> MiniHttpServer.Response(503, mapOf("Content-Type" to "application/json"), """{"code":"something_else"}""".toByteArray()) }
        val sdk = sdk(retries = 1)
        sdk.track("a", userId = "u1")
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        assertEquals(2, requests.size)
        // The durable queue keeps the exhausted batch instead of reporting it: nothing here is an identity hold.
        assertTrue(errors.snapshot().isEmpty())
        assertTrue(notices.isEmpty())
        assertEquals(1, savedEvents(root, "identity").size)
        sdk.shutdown()
    }

    // ---- the hold's bound ----

    @Test fun whileAHoldTheQueueKeepsAtMostAThousandEventsAndReportsTheOldest() = runBlocking {
        respond = { _, _ -> refusal(listOf("u1")) }
        val sdk = sdk(provider = { null }, batchSize = 1, namespace = "bounded", sleeper = ManualSleeper())
        sdk.identify("u1")
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        repeat(HELD_QUEUE_LIMIT + 5) { sdk.track("e$it", userId = "u1") }
        sdk.shutdown()
        assertEquals(HELD_QUEUE_LIMIT, savedEvents(root, "bounded").size)
        assertEquals(6, errors.snapshot().sumOf { it.events.size })
        assertTrue(errors.snapshot().all { it.attempts == 0 })
    }

    // ---- response headers ----

    @Test fun droppedAndUnverifiedCountsOnA2xxAreSurfacedOncePerResponseWithoutIdentifiers() = runBlocking {
        respond = { index, _ -> MiniHttpServer.Response(204, if (index == 0) mapOf("x-tranthor-dropped-events" to "2") else mapOf("x-tranthor-unverified-events" to "3")) }
        val sdk = sdk()
        sdk.track("a", userId = "secret-user-id"); sdk.flush()
        sdk.track("b", userId = "secret-user-id"); sdk.flush()
        sdk.track("c", userId = "secret-user-id"); sdk.flush()
        assertEquals(listOf(NoticeKind.EVENTS_DROPPED to 2, NoticeKind.EVENTS_UNVERIFIED to 3, NoticeKind.EVENTS_UNVERIFIED to 3), notices.map { it.kind to it.count })
        assertFalse(notices.any { it.message.contains("secret-user-id") })
        sdk.shutdown()
    }

    @Test fun aNoticeCallbackThatThrowsDoesNotChangeDelivery() = runBlocking {
        respond = { _, _ -> MiniHttpServer.Response(204, mapOf("x-tranthor-dropped-events" to "1")) }
        val sdk = configureJvm(Configuration("k", host = server.url, batchSize = 1, storageNamespace = "throwing", storageDirectory = root,
            onNotice = { throw IllegalStateException("host bug") }))
        sdk.track("a", userId = "u1"); sdk.flush()
        sdk.track("b", userId = "u1"); sdk.flush()
        assertEquals(2, requests.size)
        sdk.shutdown()
    }

    // ---- opt-out removal ----

    private suspend fun optedOutWithInstallation(provider: (suspend (String) -> String?)?, namespace: String, sleeper: ManualSleeper = ManualSleeper()): TranthorSDK {
        val sdk = sdk(provider = provider, namespace = namespace, sleeper = sleeper)
        sdk.identify("u1", identityToken = valid("u1", "cached"))
        sdk.registerInstallation("fcm-token-1", "com.example", InstallationPermission.GRANTED)
        sdk.flush()
        requests.clear()
        return sdk
    }

    @Test fun theOptOutRemovalUsesItsUsersCachedToken() = runBlocking {
        val sdk = optedOutWithInstallation(null, "removal-cached")
        sdk.optOut()
        waitUntil { requests.isNotEmpty() }
        assertEquals(listOf(valid("u1", "cached")), tokens(0))
        assertEquals("Installation Removed", body(0)["batch"]!!.arrayValue!!.single()["event"]?.stringValue)
        sdk.shutdown()
    }

    @Test fun anOptOutRemovalIsDeliveredAsBeforeWhenNoProviderIsConfiguredAndNoTokenIsCached() = runBlocking {
        val sdk = sdk(namespace = "removal-none")
        sdk.identify("u1")
        sdk.registerInstallation("fcm-token-1", "com.example", InstallationPermission.GRANTED)
        sdk.flush(); requests.clear()
        sdk.optOut()
        waitUntil { requests.isNotEmpty() }
        assertNull(body(0)["identityTokens"])
        assertTrue(errors.snapshot().isEmpty())
        sdk.shutdown()
    }

    @Test fun aRefusedOptOutRemovalIsRenewedOnceAndDeliveredWithTheNewToken() = runBlocking {
        val renewed = valid("u1", "renewed")
        val sdk = optedOutWithInstallation({ renewed }, "removal-renewed")
        respond = { index, _ -> if (index == 0) refusal(listOf("u1"), "expired") else MiniHttpServer.Response(204) }
        sdk.optOut()
        waitUntil { requests.size == 2 }
        assertEquals(listOf(listOf(valid("u1", "cached")), listOf(renewed)), requests.indices.map { tokens(it) })
        assertTrue(errors.snapshot().isEmpty())
        sdk.shutdown()
    }

    @Test fun anOptOutRemovalThatCannotGetATokenIsParkedReportedAndRetriedOnTheHoldTimerUntilDelivered() = runBlocking {
        val sleeper = ManualSleeper()
        val sdk = optedOutWithInstallation({ null }, "removal-parked", sleeper)
        respond = { index, _ -> if (index < 2) refusal(listOf("u1"), "missing") else MiniHttpServer.Response(204) }
        sdk.optOut()
        waitUntil { errors.snapshot().size == 1 }
        val failure = errors.snapshot().single()
        assertEquals(403, failure.error.status)
        assertEquals("Installation Removed", failure.events.single()["event"]?.stringValue)
        assertFalse("the push token stays out of the callback", failure.events.toString().contains("fcm-token-1"))
        assertEquals(1, requests.size)
        assertEquals("kept, not dropped", 1, stored("removal-parked").pendingRemovals.size)
        sleeper.release(sleeper.wait(60_000.0)) // the same 60 s hold timer as the other holds
        waitUntil { errors.snapshot().size == 2 } // every failed attempt is reported
        assertEquals(2, requests.size)
        sleeper.release(sleeper.wait(60_000.0))
        waitUntil { requests.size == 3 }
        waitUntil { stored("removal-parked").pendingRemovals.isEmpty() }
        assertEquals("delivered at last: nothing more is reported", 2, errors.snapshot().size)
        sdk.shutdown()
    }

    @Test fun aParkedOptOutRemovalIsRetriedOnTheNextLaunch() = runBlocking {
        val sleeper = ManualSleeper()
        val sdk = optedOutWithInstallation({ null }, "removal-next-launch", sleeper)
        respond = { _, _ -> refusal(listOf("u1"), "missing") }
        sdk.optOut()
        waitUntil { errors.snapshot().size == 1 }
        sdk.shutdown()
        respond = { _, _ -> MiniHttpServer.Response(204) }
        val next = sdk(provider = { null }, namespace = "removal-next-launch")
        waitUntil { requests.size == 2 }
        waitUntil { stored("removal-next-launch").pendingRemovals.isEmpty() }
        next.shutdown()
    }

    @Test fun signingTheSameUserInAgainSupersedesAParkedRemoval() = runBlocking {
        val sleeper = ManualSleeper()
        val sdk = optedOutWithInstallation({ null }, "removal-superseded", sleeper)
        respond = { _, _ -> refusal(listOf("u1"), "missing") }
        sdk.optOut()
        waitUntil { errors.snapshot().size == 1 }
        sdk.optIn() // registers the stored token to the same signed-in user again
        assertTrue("the registration supersedes the removal", stored("removal-superseded").pendingRemovals.isEmpty())
        sdk.shutdown()
    }

    @Test fun aSignOutRemovalForAPreviousUserIsNeverDroppedForLackOfAToken() = runBlocking {
        val sleeper = ManualSleeper()
        val sdk = optedOutWithInstallation({ null }, "signout-removal", sleeper)
        respond = { index, _ -> if (index == 0) refusal(listOf("u1"), "missing") else MiniHttpServer.Response(204) }
        sdk.reset() // queues Installation Removed under u1; nobody is signed in now
        val failure = assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        assertEquals(403, failure.status)
        waitUntil { errors.snapshot().size == 1 }
        assertEquals("Installation Removed", errors.snapshot().single().events.single()["event"]?.stringValue)
        assertFalse(errors.snapshot().single().events.toString().contains("fcm-token-1"))
        assertEquals("kept in the queue", listOf("Installation Removed"), savedEventNames(root, "signout-removal"))
        sleeper.release(sleeper.wait(60_000.0))
        waitUntil { requests.size == 2 }
        waitUntil { savedEvents(root, "signout-removal").isEmpty() }
        sdk.shutdown()
    }

    // ---- identity in use (R1) ----

    @Test fun aDamagedStateFileKeepsIdentityInUse() {
        val bytes = """{"version":99,"anonymousId":"${java.util.UUID.randomUUID()}","userId":"u1","identityTokenSupplied":true,"queued":5}""".toByteArray()
        assertTrue(StoredState.salvageIdentity(bytes).identityTokenSupplied)
        assertFalse(StoredState.salvageIdentity("""{"version":99,"anonymousId":"${java.util.UUID.randomUUID()}"}""".toByteArray()).identityTokenSupplied)
    }

    @Test fun aFailedWriteWhileSupersedingAParkedRemovalLeavesItParked() = runBlocking {
        val sleeper = ManualSleeper()
        var stateWrites = 0
        var failStateWrite = -1
        val sdk = configureJvm(Configuration("Basic k==", host = server.url, batchSize = 100, flushIntervalMs = 61_000, retries = 0, baseDelay = 10,
            storageNamespace = "supersede-fails", storageDirectory = root, identityTokenProvider = { null }, onError = { errors.append(it) }),
            sleeper = sleeper, storeWrite = { bytes, file ->
                if (file.name == "state.json" && ++stateWrites == failStateWrite) throw java.io.IOException("disk full")
                TempRenameFileIo.write(file, bytes)
            })
        sdk.identify("u1", identityToken = valid("u1"))
        sdk.registerInstallation("fcm-token-1", "com.example", InstallationPermission.GRANTED)
        sdk.flush()
        requests.clear()
        respond = { _, _ -> refusal(listOf("u1"), "missing") }
        sdk.optOut()
        waitUntil { errors.snapshot().size == 1 } // parked
        val sent = requests.size
        val retryTimer = sleeper.wait(60_000.0) // the 60 s retry of the parked removal
        failStateWrite = stateWrites + 2 // optIn: consent and state first, then the state write that supersedes the removal
        assertThrows(TranthorException.StoreTemporarilyUnavailable::class.java) { runBlocking { sdk.optIn() } }
        // The removal is back and still parked: its retry timer runs again (the superseding had cancelled it).
        sleeper.waitCancellation(retryTimer)
        assertTrue(sleeper.wait(60_000.0) != retryTimer)
        assertEquals("not sent again", sent, requests.size)
        assertEquals(1, stored("supersede-fails").pendingRemovals.size)
        sdk.shutdown()
    }

    @Test fun theJavaIdentifyWithATokenHasItsOwnNameAndAcceptsANullToken() = runBlocking {
        val sdk = sdk(namespace = "java-identify")
        val client = TranthorClient::class.java.getDeclaredConstructor(TranthorSDK::class.java).apply { isAccessible = true }.newInstance(sdk)
        // `identify(id, traits, null)` stays unambiguous for Java callers of the 0.2.x overload with a callback.
        assertTrue(TranthorClient::class.java.methods.none { it.name == "identify" && it.parameterTypes.contentEquals(arrayOf(String::class.java, Map::class.java, String::class.java)) })
        val method = TranthorClient::class.java.getMethod("identifyWithIdentityToken", String::class.java, Map::class.java, String::class.java, TranthorCallback::class.java)
        val done = java.util.concurrent.CompletableFuture<Throwable?>()
        method.invoke(client, "u1", null, null, TranthorCallback<Unit> { _, error -> done.complete(error) })
        assertNull(done.get(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("u1", sdk.userId)
        sdk.shutdown()
    }

    @Test fun anAppWithNoProviderThatPassesTokensThroughIdentifyKeepsHoldingAfterTheRefusalDeletedTheToken() = runBlocking {
        val sleeper = ManualSleeper()
        respond = { _, _ -> refusal(listOf("u1"), "expired") }
        val sdk = sdk(sleeper = sleeper, namespace = "token-only")
        sdk.identify("u1", identityToken = valid("u1"))
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        assertTrue("the refusal deleted the cached token", stored("token-only").tokens.isEmpty())
        sleeper.release(sleeper.wait(60_000.0)) // the hold elapses: the batch goes again, without a token, and is refused again
        waitUntil { requests.size == 2 }
        delay(150)
        assertTrue("still held, never dropped", errors.snapshot().isEmpty())
        assertEquals(1, savedEvents(root, "token-only").size)
        assertEquals("identity stays in use across a restart", true, stored("token-only").identityTokenSupplied)
        sdk.shutdown()
        val again = sdk(namespace = "token-only", sleeper = ManualSleeper())
        assertThrows(DeliveryFailure::class.java) { runBlocking { again.flush() } }
        assertTrue(errors.snapshot().isEmpty())
        assertEquals(1, savedEvents(root, "token-only").size)
        again.shutdown()
    }

    @Test fun aRefusalWithNoUserToActOnKeepsTheOldBehaviourWhenIdentityWasNeverUsed() = runBlocking {
        respond = { _, _ -> refusal(listOf("u1")) }
        val sdk = sdk(retries = 2, namespace = "never-used-anonymous")
        sdk.track("anonymous")
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        assertEquals("a final refusal, not retried", 1, requests.size)
        assertEquals(1, errors.snapshot().size)
        assertTrue(notices.isEmpty())
        sdk.shutdown()
    }

    // ---- app id ----

    @Test fun anAppIdThatCannotBeAHeaderValueFailsConfigurationAndIsNeverReplaced() = runBlocking {
        for (bad in listOf("bad\nid", "caf\u00e9", "", "  ", " padded ")) {
            val error = assertThrows(TranthorException.InvalidAppId::class.java) {
                runBlocking { sdk(appId = bad, packageName = "com.example.package", namespace = "bad-app-id") }
            }
            assertTrue(error.message!!, error.message!!.contains("appId"))
        }
        assertEquals("nothing was sent", 0, requests.size)
        sdk(appId = "com.example.fine", packageName = "com.example.package", namespace = "good-app-id").shutdown()
    }

    // ---- a blocking supplier ----

    // ---- who is the current user after a relaunch ----

    private fun stateFile(namespace: String) = JsonStore(root, namespace).stateFile

    @Test fun aSignedOutUsersQueuedEventsAreNotTheCurrentUsersAfterARelaunch() = runBlocking {
        val first = sdk(namespace = "signed-out-relaunch")
        first.identify("u1", identityToken = valid("u1"))
        first.track("queued before sign-out")
        first.reset()
        first.shutdown()
        respond = { _, _ -> refusal(listOf("u1")) }
        val second = sdk(provider = { null }, namespace = "signed-out-relaunch", sleeper = ManualSleeper())
        runCatching { second.flush() }
        waitUntil { errors.snapshot().isNotEmpty() }
        assertEquals("dropped and reported once, not held", 2, errors.snapshot().sumOf { it.events.size })
        assertTrue(savedEvents(root, "signed-out-relaunch").isEmpty())
        assertTrue(notices.isEmpty())
        second.shutdown()
    }

    @Test fun afterASignOutADrainAndTwoRelaunchesTheNextFirstUsersRefusedEventIsHeld() = runBlocking {
        val first = sdk(namespace = "drained-relaunch")
        first.identify("u1", identityToken = valid("u1"))
        first.reset()
        first.flush() // the queue is empty now, so the next start numbers its events from 1 again
        first.shutdown()
        sdk(namespace = "drained-relaunch").shutdown()
        sdk(namespace = "drained-relaunch").shutdown()
        respond = { _, _ -> refusal(listOf("u2")) }
        val third = sdk(provider = { null }, namespace = "drained-relaunch", sleeper = ManualSleeper())
        third.track("first event of a new user", userId = "u2")
        assertThrows(DeliveryFailure::class.java) { runBlocking { third.flush() } }
        assertTrue("held, not dropped", errors.snapshot().isEmpty())
        assertEquals(1, savedEvents(root, "drained-relaunch").size)
        third.shutdown()
    }

    @Test fun aSalvagedOrUnreadableStateFileDoesNotMakeASignedOutUserCurrentAgain() = runBlocking {
        for ((name, damage) in listOf<Pair<String, (String) -> String>>(
            "unreadable" to { "{" },
            "salvaged" to { text -> text.replace(Regex("\"anonymousId\":\"[^\"]*\""), "\"anonymousId\":\"not-a-uuid\"") },
        )) {
            val namespace = "damaged-$name"
            val first = sdk(namespace = namespace)
            first.identify("u1", identityToken = valid("u1"))
            first.track("queued before sign-out")
            first.reset()
            first.shutdown()
            val file = stateFile(namespace)
            file.writeText(damage(file.readText()))
            requests.clear()
            respond = { _, _ -> refusal(listOf("u1")) }
            val second = sdk(provider = { null }, namespace = namespace, sleeper = ManualSleeper())
            runCatching { second.flush() }
            waitUntil { errors.snapshot().isNotEmpty() }
            assertTrue("$name: the signed-out user's events were dropped and reported, not held", savedEvents(root, namespace).isEmpty())
            second.shutdown()
        }
    }

    // ---- the held queue, the restored timer and the notice ----

    @Test fun aHoldTrimsTheQueueToItsLimitAsItStarts() = runBlocking {
        respond = { _, _ -> refusal(listOf("u1")) }
        val sleeper = ManualSleeper()
        // The provider hangs, so the first send waits for it while more events are queued behind it (no hold, so no limit yet).
        val sdk = sdk(provider = { awaitCancellation() }, sleeper = sleeper, namespace = "trim-on-hold", batchSize = 1)
        sdk.identify("u1")
        val providerWait = sleeper.wait(20_000.0)
        repeat(HELD_QUEUE_LIMIT + 199) { sdk.track("e$it", userId = "u1") }
        sleeper.release(providerWait) // the provider times out; the send is refused; the batch is held
        waitUntil { errors.snapshot().sumOf { it.events.size } >= 200 }
        assertEquals("the limit applies as the hold starts, not at the next admitted event", HELD_QUEUE_LIMIT, savedEvents(root, "trim-on-hold").size)
        assertTrue(errors.snapshot().all { it.attempts == 0 })
        sdk.shutdown()
    }

    @Test fun aRestoredRemovalThatAnOfflineFailureParkedDoesNotGetATimer() = runBlocking {
        val sleeper = ManualSleeper()
        var failQueueWrite = false
        val sdk = configureJvm(Configuration("Basic k==", host = server.url, batchSize = 100, flushIntervalMs = 61_000, retries = 0, baseDelay = 10,
            storageNamespace = "offline-parked", storageDirectory = root, onError = { errors.append(it) }),
            sleeper = sleeper, storeWrite = { bytes, file ->
                if (failQueueWrite && file.parentFile?.name == "queue") throw java.io.IOException("disk full")
                TempRenameFileIo.write(file, bytes)
            })
        sdk.identify("u1")
        sdk.registerInstallation("fcm-token-1", "com.example", InstallationPermission.GRANTED)
        sdk.flush()
        respond = { _, _ -> MiniHttpServer.Response(503) } // an ordinary failure parks the removal until the next trigger
        sdk.optOut()
        waitUntil { requests.size >= 1 }
        waitUntil { !sdk.deliveryCycleActive() }
        failQueueWrite = true // optIn cannot save the registration it queues
        assertThrows(TranthorException.StoreTemporarilyUnavailable::class.java) { runBlocking { sdk.optIn() } }
        // Only an identity refusal parks a removal on a timer; this one waits for a trigger, as before.
        assertThrows(TestTimeout::class.java) { runBlocking { sleeper.wait(60_000.0) } }
        sdk.shutdown()
    }

    @Test fun theHoldNoticeStatesTheHoldThatIsApplied() = runBlocking {
        // u1 is set aside for 60 s; the rest of the batch then meets a server delay of 1 s, so 60 s is what is applied.
        respond = { _, request ->
            val first = JsonValue.parse(request.body.toString(Charsets.UTF_8))["batch"]!!.arrayValue!!.map { it["userId"]?.stringValue }
            if ("u1" in first) refusal(listOf("u1")) else MiniHttpServer.Response(503, mapOf("Retry-After" to "1", "Content-Type" to "application/json"),
                """{"code":"identity_settings_unavailable"}""".toByteArray())
        }
        val sdk = sdk(batchSize = 2, sleeper = ManualSleeper(), provider = { null }, namespace = "notice-hold")
        sdk.identify("u2", identityToken = valid("u2"))
        sdk.identify("u1")
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        val unavailable = notices.first { it.kind == NoticeKind.IDENTITY_UNAVAILABLE }
        assertTrue(unavailable.message, unavailable.message.contains("60 s"))
        sdk.shutdown()
    }

    @Test fun anAbandonedBlockingProviderCallIsNotFollowedByAnotherUntilItReturns() = runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val sdk = sdk(provider = { calls.incrementAndGet(); gate.await(); null }, namespace = "blocking",
            timings = IdentityTimings(providerTimeoutMs = 50, providerBackoffMs = 50))
        sdk.track("a", userId = "u1"); sdk.flush() // times out after 50 ms; the thread is still blocked
        delay(120) // the 50 ms backoff has passed
        sdk.track("b", userId = "u1"); sdk.flush()
        assertEquals("no second call while the first is still running", 1, calls.get())
        assertEquals("delivery was not held back", 2, requests.size)
        gate.countDown()
        delay(100)
        sdk.track("c", userId = "u1"); sdk.flush()
        assertEquals("called again once it returned", 2, calls.get())
        sdk.shutdown()
    }

    // ---- a failed identify restores the hold ----

    @Test fun aFailedIdentifyRestoresTheHoldItEnded() = runBlocking {
        var failWrites = false
        val sleeper = ManualSleeper()
        respond = { _, _ -> refusal(listOf("u1")) }
        val sdk = configureJvm(Configuration("k", host = server.url, batchSize = 100, flushIntervalMs = 61_000, storageNamespace = "restore-hold", storageDirectory = root,
            identityTokenProvider = { null }, onNotice = { notices.add(it) }),
            sleeper = sleeper, storeWrite = { bytes, file -> if (failWrites) throw java.io.IOException("disk full") else TempRenameFileIo.write(file, bytes) })
        sdk.identify("u1")
        assertThrows(DeliveryFailure::class.java) { runBlocking { sdk.flush() } }
        assertEquals(1, requests.size)
        val firstHold = sleeper.wait(60_000.0)
        failWrites = true
        assertThrows(TranthorException.StoreTemporarilyUnavailable::class.java) { runBlocking { sdk.identify("u1", identityToken = valid("u1")) } }
        failWrites = false
        sdk.track("while held", userId = "u1")
        delay(200)
        assertEquals("still held: nothing was sent", 1, requests.size)
        sleeper.waitCancellation(firstHold) // the identify ended the first hold...
        assertTrue("...and a live one is running again", sleeper.wait(60_000.0) != firstHold)
        sdk.shutdown()
    }

    private companion object {
        const val IDENTITY_REFUSED_REASON = "a refusal is reported as a notice, not an error"
    }
}
