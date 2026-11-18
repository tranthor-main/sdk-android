package com.tranthor.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The write key is stored only as Keystore AES-GCM ciphertext and is removable with Forget key. */
@RunWith(AndroidJUnit4::class)
class SecretStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun roundTripsCiphertextOnlyAndDeletes() {
        val store = SecretStore(context)
        store.delete()
        assertNull(store.read())
        store.write("fixture-not-a-real-key")
        assertEquals("fixture-not-a-real-key", store.read())
        val raw = context.getSharedPreferences("example_secure", android.content.Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse("Plaintext must never be stored", raw.contains("fixture-not-a-real-key"))
        store.delete()
        assertNull(store.read())
    }
}
