package com.example.ava.sendspin.noise

import android.content.Context
import com.example.ava.server.noise.X25519

/**
 * Long-lived X25519 identity used only on the encrypted Sendspin path.
 * Legacy plaintext hello keeps [android.provider.Settings.Secure.ANDROID_ID].
 */
internal class SendspinNoiseIdentity(
    val privateBytes: ByteArray,
    val publicBytes: ByteArray,
) {
    val peerId: String = SendspinNoiseCodec.b64urlEncode(publicBytes)

    companion object {
        private const val PREFS = "sendspin_noise_identity"
        private const val KEY_PRIV = "x25519_priv_b64u"

        @Volatile
        private var cached: SendspinNoiseIdentity? = null

        fun get(context: Context): SendspinNoiseIdentity {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val created = loadOrCreate(context.applicationContext)
                cached = created
                return created
            }
        }

        private fun loadOrCreate(context: Context): SendspinNoiseIdentity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_PRIV, null)
            if (!stored.isNullOrBlank()) {
                val priv = SendspinNoiseCodec.b64urlDecode(stored)
                if (priv != null && priv.size == SendspinNoiseCodec.KEY_SIZE) {
                    return SendspinNoiseIdentity(priv, X25519.publicKey(priv))
                }
            }
            val priv = X25519.generatePrivateKey()
            val ident = SendspinNoiseIdentity(priv, X25519.publicKey(priv))
            prefs.edit().putString(KEY_PRIV, SendspinNoiseCodec.b64urlEncode(priv)).apply()
            return ident
        }
    }
}
