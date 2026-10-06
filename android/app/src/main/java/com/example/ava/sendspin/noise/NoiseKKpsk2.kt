package com.example.ava.sendspin.noise

import com.example.ava.server.noise.ChaCha20Poly1305
import com.example.ava.server.noise.CipherState
import com.example.ava.server.noise.NoiseTransport
import com.example.ava.server.noise.X25519
import com.example.ava.server.noise.noiseNonce
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `Noise_KKpsk2_25519_ChaChaPoly_SHA256` as used by aiosendspin (python-noise).
 *
 * Server is initiator, Ava is responder. Tokens:
 *   message 1 `-> e, es, ss`
 *   message 2 `<- e, ee, se, psk`
 *
 * Because the pattern carries a `psk` modifier, *every* `e` token also calls
 * MixKey(e.pub) (Noise §9.2; python-noise `is_psk_handshake`), i.e. in both
 * message 1 and message 2 — not only once `k` is set.
 */
internal class NoiseKKpsk2 private constructor(
    private val initiator: Boolean,
    private val localStaticPriv: ByteArray,
    private val remoteStaticPub: ByteArray,
    prologue: ByteArray,
    private val forcedEphemeral: ByteArray?,
) {
    private val localStaticPub = X25519.publicKey(localStaticPriv)
    private var ck: ByteArray
    private var h: ByteArray
    private var k: ByteArray? = null
    private var n: Long = 0
    private var localEphemeralPriv: ByteArray? = null
    private var localEphemeralPub: ByteArray? = null
    private var remoteEphemeralPub: ByteArray? = null
    private var complete = false

    init {
        val name = PROTOCOL_NAME
        h = if (name.size <= HASH_LEN) name.copyOf(HASH_LEN) else sha256(name)
        ck = h.copyOf()
        mixHash(prologue)
        // Pre-messages: initiator static, then responder static.
        if (initiator) {
            mixHash(localStaticPub)
            mixHash(remoteStaticPub)
        } else {
            mixHash(remoteStaticPub)
            mixHash(localStaticPub)
        }
    }

    fun writeMessage1(payload: ByteArray): ByteArray {
        check(initiator && localEphemeralPriv == null)
        val ePub = generateEphemeral()
        mixHash(ePub)
        mixKey(ePub) // psk pattern: e also MixKey(e.pub)
        mixKey(X25519.sharedSecret(localEphemeralPriv!!, remoteStaticPub))
        mixKey(X25519.sharedSecret(localStaticPriv, remoteStaticPub))
        return ePub + encryptAndHash(payload)
    }

    fun readMessage1(message: ByteArray): ByteArray {
        check(!initiator && remoteEphemeralPub == null)
        require(message.size >= 32 + 16) { "Noise message 1 too short" }
        val re = message.copyOf(32)
        remoteEphemeralPub = re
        mixHash(re)
        mixKey(re) // psk pattern: e also MixKey(re)
        mixKey(X25519.sharedSecret(localStaticPriv, re))
        mixKey(X25519.sharedSecret(localStaticPriv, remoteStaticPub))
        return decryptAndHash(message.copyOfRange(32, message.size))
    }

    fun writeMessage2(psk: ByteArray, payload: ByteArray): ByteArray {
        check(!initiator && remoteEphemeralPub != null && localEphemeralPriv == null)
        require(psk.size == SendspinNoiseCodec.KEY_SIZE)
        val ePub = generateEphemeral()
        mixHash(ePub)
        mixKey(ePub)
        mixKey(X25519.sharedSecret(localEphemeralPriv!!, remoteEphemeralPub!!))
        mixKey(X25519.sharedSecret(localEphemeralPriv!!, remoteStaticPub))
        mixKeyAndHash(psk)
        complete = true
        return ePub + encryptAndHash(payload)
    }

    fun readMessage2(psk: ByteArray, message: ByteArray): ByteArray {
        check(initiator && localEphemeralPriv != null && remoteEphemeralPub == null)
        require(psk.size == SendspinNoiseCodec.KEY_SIZE)
        require(message.size >= 32 + 16) { "Noise message 2 too short" }
        val re = message.copyOf(32)
        remoteEphemeralPub = re
        mixHash(re)
        mixKey(re)
        mixKey(X25519.sharedSecret(localEphemeralPriv!!, re))
        mixKey(X25519.sharedSecret(localStaticPriv, re))
        mixKeyAndHash(psk)
        complete = true
        return decryptAndHash(message.copyOfRange(32, message.size))
    }

    fun split(): NoiseTransport {
        check(complete)
        val (k1, k2) = hkdf(ck, ByteArray(0), 2)
        return if (initiator) {
            NoiseTransport(CipherState(k1), CipherState(k2))
        } else {
            NoiseTransport(CipherState(k2), CipherState(k1))
        }
    }

    val handshakeHash: ByteArray
        get() {
            check(complete)
            return h.copyOf()
        }

    private fun generateEphemeral(): ByteArray {
        val priv = if (forcedEphemeral != null) {
            require(forcedEphemeral.size == 32)
            forcedEphemeral.copyOf()
        } else {
            X25519.generatePrivateKey()
        }
        val pub = X25519.publicKey(priv)
        localEphemeralPriv = priv
        localEphemeralPub = pub
        return pub
    }

    private fun mixHash(data: ByteArray) {
        h = sha256(h + data)
    }

    private fun mixKey(ikm: ByteArray) {
        val (nextCk, tempK) = hkdf(ck, ikm, 2)
        ck = nextCk
        k = tempK
        n = 0
    }

    private fun mixKeyAndHash(ikm: ByteArray) {
        val (nextCk, tempH, tempK) = hkdf(ck, ikm, 3)
        ck = nextCk
        mixHash(tempH)
        k = tempK
        n = 0
    }

    private fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = encryptWithAd(h, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    private fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    private fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val key = k ?: return plaintext.copyOf()
        val out = ChaCha20Poly1305.encrypt(key, noiseNonce(n), ad, plaintext)
        n += 1
        return out
    }

    private fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = k ?: return ciphertext.copyOf()
        val out = ChaCha20Poly1305.decrypt(key, noiseNonce(n), ad, ciphertext)
        n += 1
        return out
    }

    companion object {
        val PROTOCOL_NAME = "Noise_KKpsk2_25519_ChaChaPoly_SHA256".toByteArray(Charsets.US_ASCII)
        private const val HASH_LEN = 32

        fun initiator(
            localStaticPriv: ByteArray,
            remoteStaticPub: ByteArray,
            prologue: ByteArray,
            ephemeralPrivate: ByteArray? = null,
        ): NoiseKKpsk2 = NoiseKKpsk2(
            initiator = true,
            localStaticPriv = localStaticPriv,
            remoteStaticPub = remoteStaticPub,
            prologue = prologue,
            forcedEphemeral = ephemeralPrivate,
        )

        fun responder(
            localStaticPriv: ByteArray,
            remoteStaticPub: ByteArray,
            prologue: ByteArray,
            ephemeralPrivate: ByteArray? = null,
        ): NoiseKKpsk2 = NoiseKKpsk2(
            initiator = false,
            localStaticPriv = localStaticPriv,
            remoteStaticPub = remoteStaticPub,
            prologue = prologue,
            forcedEphemeral = ephemeralPrivate,
        )
    }
}

private fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
    require(outputs in 2..3)
    val temp = hmac(chainingKey, ikm)
    val out1 = hmac(temp, byteArrayOf(0x01))
    val out2 = hmac(temp, out1 + 0x02)
    if (outputs == 2) return listOf(out1, out2)
    val out3 = hmac(temp, out2 + 0x03)
    return listOf(out1, out2, out3)
}
