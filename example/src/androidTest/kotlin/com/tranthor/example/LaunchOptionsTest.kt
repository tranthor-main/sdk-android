package com.tranthor.example

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tranthor.sdk.Configuration
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MainActivity is exported, so any app can launch it with host or key extras. Those overrides must
 * never reach a saved key or the user's own storage namespace.
 */
@RunWith(AndroidJUnit4::class)
class LaunchOptionsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scope = MainScope()

    @Before fun seedStoredKey() {
        SecretStore(context).write("Basic stored-fixture-key")
        context.getSharedPreferences("example", android.content.Context.MODE_PRIVATE).edit().remove("host").commit()
    }

    @After fun cleanUp() {
        SecretStore(context).delete()
        scope.cancel()
    }

    private fun model(intent: Intent): ExampleModel {
        lateinit var model: ExampleModel
        instrumentation.runOnMainSync { model = ExampleModel(context, LaunchOptions.from(intent), scope, ExamplePush(context, scope, false)) }
        return model
    }

    @Test fun launchHostAloneNeverUsesSavedKey() {
        val model = model(Intent().putExtra(LaunchOptions.HOST, "http://127.0.0.1:64740"))
        assertFalse("Saved key paired with a launch-supplied host",
            model.host == "http://127.0.0.1:64740" && model.writeKey == "Basic stored-fixture-key")
        assertEquals(Configuration.DEFAULT_HOST, model.host)
    }

    @Test fun nonLoopbackLaunchHostIsIgnoredEvenWithLaunchKey() {
        val model = model(Intent().putExtra(LaunchOptions.HOST, "https://attacker.example").putExtra(LaunchOptions.WRITE_KEY, "attacker-key"))
        assertEquals(Configuration.DEFAULT_HOST, model.host)
        assertEquals("Basic stored-fixture-key", model.writeKey)
        assertFalse(model.keyFromLaunch)
    }

    @Test fun releaseBuildsIgnoreEveryLaunchOverride() {
        val options = LaunchOptions.from(Intent().putExtra(LaunchOptions.HOST, "http://127.0.0.1:43189")
            .putExtra(LaunchOptions.WRITE_KEY, "conformance-fixture").putExtra(LaunchOptions.FLUSH_INTERVAL, "60000")
            .putExtra(LaunchOptions.UI_TESTING, true), debuggable = false)
        assertEquals(null, options.host); assertEquals(null, options.writeKey); assertEquals(null, options.flushIntervalMs)
        assertFalse(options.uiTesting)
    }

    @Test fun loopbackHostWithLaunchKeyIsAcceptedInDebugBuilds() {
        val model = model(Intent().putExtra(LaunchOptions.HOST, "http://127.0.0.1:43189").putExtra(LaunchOptions.WRITE_KEY, "conformance-fixture"))
        assertEquals(BuildConfig.DEBUG, model.host == "http://127.0.0.1:43189")
        assertTrue(model.writeKey == "conformance-fixture" || !BuildConfig.DEBUG)
    }

    private fun namespaceOf(options: LaunchOptions): String {
        val namespaceScope = MainScope()
        try {
            lateinit var model: ExampleModel
            instrumentation.runOnMainSync { model = ExampleModel(context, options, namespaceScope, ExamplePush(context, namespaceScope, false)) }
            val field = ExampleModel::class.java.getDeclaredField("namespace").apply { isAccessible = true }
            return field.get(model) as String
        } finally { namespaceScope.cancel() }
    }

    @Test fun launchOverrideSessionUsesItsOwnNamespace() {
        val normal = namespaceOf(LaunchOptions.from(Intent()))
        val overridden = namespaceOf(LaunchOptions.from(Intent().putExtra(LaunchOptions.HOST, "http://127.0.0.1:43189")
            .putExtra(LaunchOptions.WRITE_KEY, "conformance-fixture")))
        assertEquals("android-example", normal)
        assertTrue("Launch-override session must use a separate namespace, got $overridden", overridden != normal)
    }
}
