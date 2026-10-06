package com.example.ava.server.noise

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Noise `NNpsk0` handshake + transport for
 * `Noise_NNpsk0_25519_ChaChaPoly_SHA256`.
 *
 * Tokens: `-> psk, e` then `<- e, ee`. Ava is the responder; tests also
 * exercise the initiator so a wrong transcript fails closed.
 */
internal class NoiseNNpsk0 private constructor(
    private val initiator: Boolean,
    psk: ByteArray,
    prologue: ByteArray,
    private val forcedEphemeral: ByteArray?,
) {
    private val psk = psk.copyOf()
    private var ck: ByteArray
    private var h: ByteArray
    private var k: ByteArray? = null
    private var n: Long = 0
    private var localPrivate: ByteArray? = null
    private var localPublic: ByteArray? = null
    private var remotePublic: ByteArray? = null
    private var wrote = false
    private var read = false
    private var complete = false

    init {
        require(psk.size == EspHomeNoisePsk.SIZE) { "Noise PSK must be 32 bytes" }
        val name = PROTOCOL_NAME
        h = if (name.size <= HASH_LEN) name.copyOf(HASH_LEN) else sha256(name)
        ck = h.copyOf()
        mixHash(prologue)
    }

    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        val prefix: ByteArray
        if (initiator && !wrote) {
            mixKeyAndHash(psk)
            val e = generateEphemeral()
            mixHash(e)
            mixKey(e)
            wrote = true
            prefix = e
        } else if (!initiator && read && !wrote) {
            val e = generateEphemeral()
            mixHash(e)
            mixKey(e)
            mixKey(X25519.sharedSecret(localPrivate!!, remotePublic!!))
            wrote = true
            complete = true
            prefix = e
        } else {
            error("unexpected Noise write")
        }
        return prefix + encryptAndHash(payload)
    }

    fun readMessage(message: ByteArray): ByteArray {
        require(message.size >= 32 + 16) { "Noise handshake message too short" }
        val peerE = message.copyOf(32)
        val ciphertext = message.copyOfRange(32, message.size)
        if (!initiator && !read) {
            mixKeyAndHash(psk)
            remotePublic = peerE
            mixHash(peerE)
            mixKey(peerE)
            read = true
        } else if (initiator && wrote && !read) {
            remotePublic = peerE
            mixHash(peerE)
            mixKey(peerE)
            mixKey(X25519.sharedSecret(localPrivate!!, remotePublic!!))
            read = true
            complete = true
        } else {
            error("unexpected Noise read")
        }
        return decryptAndHash(ciphertext)
    }

    fun split(): NoiseTransport {
        check(complete) { "Noise handshake is not finished" }
        val (k1, k2) = hkdf(ck, ByteArray(0), 2)
        return if (initiator) {
            NoiseTransport(CipherState(k1), CipherState(k2))
        } else {
            NoiseTransport(CipherState(k2), CipherState(k1))
        }
    }

    private fun generateEphemeral(): ByteArray {
        val priv = if (forcedEphemeral != null) {
            require(forcedEphemeral.size == 32) { "ephemeral private key must be 32 bytes" }
            forcedEphemeral.copyOf()
        } else {
            X25519.generatePrivateKey()
        }
        val pub = X25519.publicKey(priv)
        localPrivate = priv
        localPublic = pub
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
        val PROTOCOL_NAME = "Noise_NNpsk0_25519_ChaChaPoly_SHA256".toByteArray(Charsets.US_ASCII)
        private const val HASH_LEN = 32

        fun initiator(
            psk: ByteArray,
            prologue: ByteArray,
            ephemeralPrivate: ByteArray? = null,
        ): NoiseNNpsk0 =
            NoiseNNpsk0(
                initiator = true,
                psk = psk,
                prologue = prologue,
                forcedEphemeral = ephemeralPrivate,
            )

        fun responder(
            psk: ByteArray,
            prologue: ByteArray,
            ephemeralPrivate: ByteArray? = null,
        ): NoiseNNpsk0 =
            NoiseNNpsk0(
                initiator = false,
                psk = psk,
                prologue = prologue,
                forcedEphemeral = ephemeralPrivate,
            )
    }
}

internal class NoiseTransport(
    private val send: CipherState,
    private val recv: CipherState,
) {
    fun encrypt(plaintext: ByteArray): ByteArray = send.encrypt(plaintext)
    fun decrypt(ciphertext: ByteArray): ByteArray = recv.decrypt(ciphertext)
}

internal class CipherState(key: ByteArray) {
    private val key = key.copyOf()
    private var n: Long = 0

    fun encrypt(plaintext: ByteArray): ByteArray {
        val out = ChaCha20Poly1305.encrypt(key, noiseNonce(n), ByteArray(0), plaintext)
        n += 1
        return out
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val out = ChaCha20Poly1305.decrypt(key, noiseNonce(n), ByteArray(0), ciphertext)
        n += 1
        return out
    }
}

internal fun noiseNonce(n: Long): ByteArray {
    val nonce = ByteArray(12)
    var v = n
    for (i in 4 until 12) {
        nonce[i] = (v and 0xff).toByte()
        v = v ushr 8
    }
    return nonce
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
