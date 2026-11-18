package com.tranthor.sdk

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SDK registers its push Installation as reserved track events through the durable queue. */
class InstallationTest {
    private val namespace = "installation"
    private fun config(root: File) = Configuration("fixture", host = "http://127.0.0.1:1", batchSize = 100,
        flushIntervalMs = 60000, storageNamespace = namespace, storageDirectory = root)

    private fun queued(root: File) = loadState(root, namespace).queued.map { it.value }
    private fun Map<String, JsonValue>.event() = this["event"]?.stringValue
    private fun Map<String, JsonValue>.property(name: String) = this["properties"]?.objectValue?.get(name)?.stringValue
    private fun installationEvents(root: File) = queued(root).filter { it.event()?.startsWith("Installation ") == true }

    private fun Map<String, JsonValue>.time() = java.time.Instant.parse(this["timestamp"]!!.stringValue)
    private fun assertStrictlyLater(earlier: Map<String, JsonValue>, later: Map<String, JsonValue>) =
        assertTrue("${later.event()} at ${later.time()} must be after ${earlier.event()} at ${earlier.time()}", later.time() > earlier.time())

    private fun withRoot(block: suspend (File) -> Unit) = runBlocking {
        val root = tempRoot("tranthor-installation")
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun aTokenForwardedBeforeSignInIsHeldAndRegisteredToTheCustomerAtIdentify() = withRoot { root ->
        val sdk = configureJvm(config(root), NoopTransport)
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        // Nobody is signed in: a phone is registered only to an identified customer.
        assertEquals(emptyList<String?>(), installationEvents(root).map { it.event() })
        sdk.identify("user-1")
        sdk.shutdown()
        val event = installationEvents(root).single()
        assertEquals("track", event["type"]?.stringValue)
        assertEquals("Installation Registered", event.event())
        assertEquals("user-1", event["userId"]?.stringValue)
        assertEquals("android", event.property("platform"))
        assertEquals("com.example.shop", event.property("appId"))
        assertEquals("fcm-token-1", event.property("token"))
        assertEquals("granted", event.property("permissionState"))
        assertEquals(TranthorSDKVersion.CURRENT, event.property("sdkVersion"))
        assertEquals(null, event["properties"]?.objectValue?.get("environment"))
    }

    @Test fun sameRegistrationIsSentOncePerAppStartAndPermissionChangesResend() = withRoot { root ->
        val sdk = configureJvm(config(root), NoopTransport)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.DENIED)
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.DENIED)
        sdk.shutdown()
        val events = installationEvents(root)
        assertEquals(listOf("Installation Registered", "Installation Registered"), events.map { it.event() })
        assertEquals(listOf("granted", "denied"), events.map { it.property("permissionState") })
    }

    @Test fun tokenRefreshRemovesTheOldTokenBeforeRegisteringTheNewOne() = withRoot { root ->
        val sdk = configureJvm(config(root), NoopTransport)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.registerInstallation("fcm-token-2", "com.example.shop", InstallationPermission.GRANTED)
        sdk.shutdown()
        val (first, removed, registered) = installationEvents(root)
        assertEquals("Installation Registered", first.event())
        assertEquals("Installation Removed", removed.event())
        assertEquals("fcm-token-1", removed.property("token"))
        assertEquals(mapOf("platform" to "android", "appId" to "com.example.shop", "token" to "fcm-token-1"),
            removed["properties"]?.objectValue?.mapValues { it.value.stringValue })
        assertEquals("user-1", removed["userId"]?.stringValue)
        assertEquals("Installation Registered", registered.event())
        assertEquals("fcm-token-2", registered.property("token"))
        assertStrictlyLater(removed, registered)
    }

    @Test fun registrationSurvivesRestartSoALaterTokenRefreshStillRemovesTheOldToken() = withRoot { root ->
        val first = configureJvm(config(root), NoopTransport)
        first.identify("user-1")
        first.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        first.shutdown()
        val second = configureJvm(config(root), NoopTransport)
        // Each app start re-registers an unchanged binding once ("last seen").
        second.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        second.shutdown()
        val third = configureJvm(config(root), NoopTransport)
        third.registerInstallation("fcm-token-2", "com.example.shop", InstallationPermission.GRANTED)
        third.shutdown()
        val events = installationEvents(root)
        assertEquals(listOf("Installation Registered" to "fcm-token-1", "Installation Registered" to "fcm-token-1",
            "Installation Removed" to "fcm-token-1", "Installation Registered" to "fcm-token-2"),
            events.map { it.event() to it.property("token") })
    }

    @Test fun resetRemovesTheInstallationUnderTheOutgoingCustomerAndDoesNotRegisterTheSignedOutPhone() = withRoot { root ->
        val sdk = configureJvm(config(root), NoopTransport)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.reset()
        // The once-per-app-start registration must not bring the signed-out phone back either.
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.shutdown()
        val events = installationEvents(root)
        assertEquals(listOf("Installation Registered" to "user-1", "Installation Removed" to "user-1"),
            events.map { it.event() to it["userId"]?.stringValue })
        assertEquals("fcm-token-1", events.last().property("token"))
        assertStrictlyLater(events.first(), events.last())
    }

    @Test fun identifyWithoutAUserIdDoesNotRegisterTheHeldToken() = withRoot { root ->
        val sdk = configureJvm(config(root), NoopTransport)
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        // Traits for a visitor, and an empty user id, sign nobody in.
        sdk.identify(traits = mapOf("plan" to JsonValue.StringValue("trial")))
        sdk.identify("")
        sdk.shutdown()
        assertEquals(listOf("identify", "identify"), queued(root).map { it["type"]?.stringValue })
    }

    @Test fun resetWhileNobodyIsSignedInSendsNoInstallationEvent() = withRoot { root ->
        val sdk = configureJvm(config(root), NoopTransport)
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.reset()
        sdk.shutdown()
        assertEquals(emptyList<String?>(), installationEvents(root).map { it.event() })
    }

    @Test fun identifyRegistersTheTokenUnderTheNewIdentityAfterReset() = withRoot { root ->
        val sdk = configureJvm(config(root), NoopTransport)
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.identify("user-1")
        sdk.identify("user-1", traits = mapOf("plan" to JsonValue.StringValue("pro")))
        sdk.reset()
        sdk.identify("user-2")
        sdk.shutdown()
        val events = installationEvents(root)
        fun who(event: Map<String, JsonValue>) = event["userId"]?.stringValue ?: "anonymous"
        assertEquals(listOf("Installation Registered" to "user-1", "Installation Removed" to "user-1", "Installation Registered" to "user-2"),
            events.map { it.event() to who(it) })
        assertTrue(events.all { it.property("token") == "fcm-token-1" })
        events.zipWithNext().forEach { (earlier, later) -> assertStrictlyLater(earlier, later) }
        // The identify event itself is admitted before the registration it causes.
        val all = queued(root).map { it["type"]?.stringValue + ":" + (it.event() ?: who(it)) }
        assertTrue(all.toString(), all.indexOf("identify:user-2") < all.lastIndexOf("track:Installation Registered"))
    }

    @Test fun optInReregistersATokenRegisteredOrRefreshedWhileOptedOut() = withRoot { root ->
        val sdk = configureJvm(config(root), NoopTransport)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optOut()
        assertTrue(installationEvents(root).isEmpty())
        sdk.registerInstallation("fcm-token-2", "com.example.shop", InstallationPermission.GRANTED)
        assertTrue("Nothing is collected while opted out", installationEvents(root).isEmpty())
        sdk.optIn()
        sdk.shutdown()
        assertEquals(listOf("Installation Registered" to "fcm-token-2"), installationEvents(root).map { it.event() to it.property("token") })
    }

    @Test fun optInWhileAlreadyOptedInDoesNotReregister() = withRoot { root ->
        val sdk = configureJvm(config(root), NoopTransport)
        sdk.identify("user-1")
        sdk.registerInstallation("fcm-token-1", "com.example.shop", InstallationPermission.GRANTED)
        sdk.optIn()
        sdk.optIn()
        sdk.shutdown()
        assertEquals(1, installationEvents(root).size)
    }
}
