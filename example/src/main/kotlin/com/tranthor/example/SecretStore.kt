package com.tranthor.example

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the manually entered write key encrypted with a non-exportable AES-256-GCM key held in the
 * Android Keystore (EncryptedSharedPreferences is deprecated). Only ciphertext and IV reach
 * SharedPreferences, which are excluded from backup (allowBackup=false). The key is never logged.
 */
class SecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("example_secure", Context.MODE_PRIVATE)

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun write(secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        // Synchronous commit: a failed save must be reported to the user, not lost in the background.
        preferences.edit(commit = true) { putString(PREF, stored) }
        if (preferences.getString(PREF, null) != stored) throw IllegalStateException("Unable to save")
    }

    /** Null when nothing is saved. Throws when the Keystore entry cannot decrypt (for example after a restore). */
    fun read(): String? {
        val stored = preferences.getString(PREF, null) ?: return null
        val (iv, ciphertext) = stored.split(':').takeIf { it.size == 2 } ?: throw IllegalStateException("Corrupt entry")
        val key = key(create = false) ?: throw IllegalStateException("Missing Keystore key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    fun delete() {
        preferences.edit(commit = true) { remove(PREF) }
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.takeIf { it.containsAlias(ALIAS) }?.deleteEntry(ALIAS)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tranthor_example_write_key"
        const val PREF = "write_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
