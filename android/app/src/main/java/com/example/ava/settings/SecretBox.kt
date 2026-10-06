package com.example.ava.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals short secrets (API tokens) with an AES-GCM key that lives in the
 * Android Keystore, so the settings JSON on disk no longer carries them in
 * clear. The key never leaves the hardware-backed store; a copied file is
 * useless on another device.
 *
 * Both directions are lenient on purpose: a value that is not sealed is
 * returned as-is (old files upgrade on their next write), and a value that
 * cannot be opened — key lost after a restore, keystore unavailable — becomes
 * blank rather than a crash, so the user re-enters the token instead of
 * losing the rest of the settings.
 */
object SecretBox {
    private const val TAG = "SecretBox"
    private const val ALIAS = "ava_settings_secret_v1"
    private const val PREFIX = "enc:v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun isSealed(value: String): Boolean = value.startsWith(PREFIX)

    fun seal(plain: String): String {
        if (plain.isEmpty() || isSealed(plain)) return plain
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "keystore unavailable; storing secret unsealed (${e.javaClass.simpleName})")
            plain
        }
    }

    fun open(stored: String): String {
        if (!isSealed(stored)) return stored
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "sealed secret could not be opened; it must be entered again (${e.javaClass.simpleName})")
            ""
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
