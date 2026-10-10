package com.bibo.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The encrypted binding is excluded from backup; key material remains in Android Keystore. */
internal object MentorCredentials {
    private const val ALIAS = "bibo.mentor.openrouter"
    private fun prefs(context: Context) = context.getSharedPreferences("mentor_credentials", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(context: Context, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        check(prefs(context).edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit()) { "Secure key storage failed" }
        context.getSharedPreferences("mentor", Context.MODE_PRIVATE).edit().remove("api_key").commit()
    }
    @Synchronized fun read(context: Context): String? = try {
        val legacy = context.getSharedPreferences("mentor", Context.MODE_PRIVATE).getString("api_key", null)
        if (!legacy.isNullOrBlank()) save(context, legacy)
        val encoded = prefs(context).getString("ciphertext", null) ?: return null
        val iv = prefs(context).getString("iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
        String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8).takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
    @Synchronized fun clear(context: Context) {
        prefs(context).edit().clear().commit()
        context.getSharedPreferences("mentor", Context.MODE_PRIVATE).edit().remove("api_key").commit()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
    }
}
