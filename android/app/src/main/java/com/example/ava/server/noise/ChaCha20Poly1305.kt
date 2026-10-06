package com.example.ava.server.noise

import java.math.BigInteger
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 8439 ChaCha20-Poly1305 AEAD. Used by Noise `25519_ChaChaPoly_SHA256`.
 *
 * API 28+ uses the platform engine, which matches Home Assistant. Android 7
 * (and 8) have no `ChaCha20-Poly1305` provider — an OEM engine that accepts
 * the name and then fails the MAC must not be used either — so those
 * releases stay on the in-tree RFC 8439 path.
 */
internal object ChaCha20Poly1305 {
    /** Unit tests set this to check the in-tree RFC 8439 path, not JCE. */
    internal var forceInTree = false

    private const val TAG_LEN = 16
    private val P = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
    private val MOD_2_128 = BigInteger.ONE.shiftLeft(128)

    fun encrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20 nonce must be 12 bytes" }
        platformAead(encrypt = true, key, nonce, ad, plaintext)?.let { return it }
        val otk = poly1305Key(key, nonce)
        val ciphertext = chacha20(key, nonce, 1u, plaintext)
        return ciphertext + poly1305(otk, ad, ciphertext)
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20 nonce must be 12 bytes" }
        require(ciphertextAndTag.size >= TAG_LEN) { "ciphertext too short" }
        platformAead(encrypt = false, key, nonce, ad, ciphertextAndTag)?.let { return it }
        val ciphertext = ciphertextAndTag.copyOf(ciphertextAndTag.size - TAG_LEN)
        val tag = ciphertextAndTag.copyOfRange(ciphertextAndTag.size - TAG_LEN, ciphertextAndTag.size)
        val expected = poly1305(poly1305Key(key, nonce), ad, ciphertext)
        if (!constantTimeEquals(tag, expected)) {
            throw MacFailureException("ChaCha20-Poly1305 MAC failure")
        }
        return chacha20(key, nonce, 1u, ciphertext)
    }

    private fun platformAead(
        encrypt: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        ad: ByteArray,
        data: ByteArray,
    ): ByteArray? {
        if (forceInTree || !platformChaChaAvailable()) return null
        return try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(
                if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "ChaCha20"),
                IvParameterSpec(nonce),
            )
            if (ad.isNotEmpty()) cipher.updateAAD(ad)
            cipher.doFinal(data)
        } catch (e: AEADBadTagException) {
            throw MacFailureException("ChaCha20-Poly1305 MAC failure")
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Platform AEAD arrived in Android 9. Unit tests report SDK 0 and use the in-tree path. */
    private fun platformChaChaAvailable(): Boolean {
        val sdk = try {
            android.os.Build.VERSION.SDK_INT
        } catch (_: Throwable) {
            0
        }
        return sdk >= 28
    }

    private fun poly1305Key(key: ByteArray, nonce: ByteArray): ByteArray =
        chacha20(key, nonce, 0u, ByteArray(32))

    internal fun chacha20(key: ByteArray, nonce: ByteArray, counter: UInt, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var offset = 0
        var blockCounter = counter
        val keystream = ByteArray(64)
        while (offset < input.size) {
            chacha20Block(key, nonce, blockCounter, keystream)
            val n = minOf(64, input.size - offset)
            for (i in 0 until n) {
                out[offset + i] = (input[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += n
            blockCounter += 1u
        }
        return out
    }

    private fun chacha20Block(key: ByteArray, nonce: ByteArray, counter: UInt, out: ByteArray) {
        val s = IntArray(16)
        s[0] = 0x61707865
        s[1] = 0x3320646e
        s[2] = 0x79622d32
        s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = load32(key, i * 4)
        s[12] = counter.toInt()
        for (i in 0 until 3) s[13 + i] = load32(nonce, i * 4)
        val w = s.copyOf()
        repeat(10) {
            quarterRound(w, 0, 4, 8, 12)
            quarterRound(w, 1, 5, 9, 13)
            quarterRound(w, 2, 6, 10, 14)
            quarterRound(w, 3, 7, 11, 15)
            quarterRound(w, 0, 5, 10, 15)
            quarterRound(w, 1, 6, 11, 12)
            quarterRound(w, 2, 7, 8, 13)
            quarterRound(w, 3, 4, 9, 14)
        }
        for (i in 0 until 16) store32(out, i * 4, w[i] + s[i])
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }

    internal fun poly1305(key: ByteArray, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val rBytes = key.copyOf(16)
        rBytes[3] = (rBytes[3].toInt() and 15).toByte()
        rBytes[7] = (rBytes[7].toInt() and 15).toByte()
        rBytes[11] = (rBytes[11].toInt() and 15).toByte()
        rBytes[15] = (rBytes[15].toInt() and 15).toByte()
        rBytes[4] = (rBytes[4].toInt() and 252).toByte()
        rBytes[8] = (rBytes[8].toInt() and 252).toByte()
        rBytes[12] = (rBytes[12].toInt() and 252).toByte()
        val r = fromLittleEndian(rBytes)
        val s = fromLittleEndian(key.copyOfRange(16, 32))
        var acc = BigInteger.ZERO
        // RFC 8439 §2.8: zero-pad each field to 16 bytes before the 2^128 marker.
        // A short block with the marker at 8*n is a different number, and Noise
        // handshake payloads are not a multiple of 16, so the tag never matched.
        acc = absorb(acc, r, pad16(ad))
        acc = absorb(acc, r, pad16(ciphertext))
        val lens = ByteArray(16)
        store64(lens, 0, ad.size.toLong())
        store64(lens, 8, ciphertext.size.toLong())
        acc = absorbBlock(acc, r, lens, 16)
        val tag = acc.add(s).mod(MOD_2_128)
        return toLittleEndian(tag, 16)
    }

    private fun pad16(data: ByteArray): ByteArray {
        val rem = data.size and 15
        if (rem == 0) return data
        val padded = ByteArray(data.size + (16 - rem))
        System.arraycopy(data, 0, padded, 0, data.size)
        return padded
    }

    private fun absorb(acc: BigInteger, r: BigInteger, data: ByteArray): BigInteger {
        if (data.isEmpty()) return acc
        var a = acc
        var i = 0
        while (i < data.size) {
            val n = minOf(16, data.size - i)
            val block = ByteArray(16)
            System.arraycopy(data, i, block, 0, n)
            a = absorbBlock(a, r, block, n)
            i += n
        }
        return a
    }

    private fun absorbBlock(acc: BigInteger, r: BigInteger, block: ByteArray, used: Int): BigInteger {
        val n = fromLittleEndian(block).setBit(used * 8)
        return acc.add(n).mod(P).multiply(r).mod(P)
    }

    private fun fromLittleEndian(bytes: ByteArray): BigInteger {
        val be = bytes.reversedArray()
        return BigInteger(1, be)
    }

    private fun toLittleEndian(n: BigInteger, size: Int): ByteArray {
        val be = n.toByteArray()
        val unsigned = if (be.isNotEmpty() && be[0] == 0.toByte()) {
            be.copyOfRange(1, be.size)
        } else {
            be
        }
        val out = ByteArray(size)
        val copy = minOf(unsigned.size, size)
        for (i in 0 until copy) {
            out[i] = unsigned[unsigned.size - 1 - i]
        }
        return out
    }

    private fun load32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private fun store32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    private fun store64(b: ByteArray, off: Int, v: Long) {
        var x = v
        for (i in 0 until 8) {
            b[off + i] = x.toByte()
            x = x ushr 8
        }
    }

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
        return d == 0
    }
}

internal class MacFailureException(message: String) : Exception(message)
