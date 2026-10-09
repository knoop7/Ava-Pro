package com.example.ava.server.noise

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

    /**
     * RFC 8439 Poly1305 over `ad || pad16(ad) || ct || pad16(ct) || le64 lengths`.
     * 26-bit limbs, no per-block allocation. The previous BigInteger accumulator
     * allocated several objects per 16-byte block; on Android 7 that is every
     * Sendspin audio frame, and the panel GC-thrashed.
     */
    internal fun poly1305(key: ByteArray, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val t0 = u32(key, 0)
        val t1 = u32(key, 4)
        val t2 = u32(key, 8)
        val t3 = u32(key, 12)
        val r0 = t0 and 0x3ffffffL
        val r1 = ((t0 ushr 26) or (t1 shl 6)) and 0x3ffff03L
        val r2 = ((t1 ushr 20) or (t2 shl 12)) and 0x3ffc0ffL
        val r3 = ((t2 ushr 14) or (t3 shl 18)) and 0x3f03fffL
        val r4 = (t3 ushr 8) and 0x00fffffL
        val s1 = r1 * 5
        val s2 = r2 * 5
        val s3 = r3 * 5
        val s4 = r4 * 5
        val h = LongArray(5)
        val tail = ByteArray(16)
        absorbPadded(ad, h, tail, r0, r1, r2, r3, r4, s1, s2, s3, s4)
        absorbPadded(ciphertext, h, tail, r0, r1, r2, r3, r4, s1, s2, s3, s4)
        tail.fill(0)
        store64(tail, 0, ad.size.toLong())
        store64(tail, 8, ciphertext.size.toLong())
        polyBlock(tail, 0, h, r0, r1, r2, r3, r4, s1, s2, s3, s4)

        var h0 = h[0]
        var h1 = h[1]
        var h2 = h[2]
        var h3 = h[3]
        var h4 = h[4]
        var c = h1 ushr 26
        h1 = h1 and 0x3ffffffL
        h2 += c; c = h2 ushr 26; h2 = h2 and 0x3ffffffL
        h3 += c; c = h3 ushr 26; h3 = h3 and 0x3ffffffL
        h4 += c; c = h4 ushr 26; h4 = h4 and 0x3ffffffL
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffffL
        h1 += c

        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and 0x3ffffffL
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and 0x3ffffffL
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and 0x3ffffffL
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and 0x3ffffffL
        val g4 = h4 + c - (1L shl 26)
        var mask = (g4 ushr 63) - 1L
        g0 = g0 and mask
        g1 = g1 and mask
        g2 = g2 and mask
        g3 = g3 and mask
        val g4m = g4 and mask
        mask = mask.inv()
        h0 = (h0 and mask) or g0
        h1 = (h1 and mask) or g1
        h2 = (h2 and mask) or g2
        h3 = (h3 and mask) or g3
        h4 = (h4 and mask) or g4m

        var n0 = (h0 or (h1 shl 26)) and 0xffffffffL
        var n1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        var n2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        var n3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL
        var f = n0 + u32(key, 16); n0 = f and 0xffffffffL
        f = n1 + u32(key, 20) + (f ushr 32); n1 = f and 0xffffffffL
        f = n2 + u32(key, 24) + (f ushr 32); n2 = f and 0xffffffffL
        f = n3 + u32(key, 28) + (f ushr 32); n3 = f and 0xffffffffL
        val out = ByteArray(16)
        store32(out, 0, n0.toInt())
        store32(out, 4, n1.toInt())
        store32(out, 8, n2.toInt())
        store32(out, 12, n3.toInt())
        return out
    }

    private fun absorbPadded(
        data: ByteArray,
        h: LongArray,
        tail: ByteArray,
        r0: Long, r1: Long, r2: Long, r3: Long, r4: Long,
        s1: Long, s2: Long, s3: Long, s4: Long,
    ) {
        var off = 0
        while (off + 16 <= data.size) {
            polyBlock(data, off, h, r0, r1, r2, r3, r4, s1, s2, s3, s4)
            off += 16
        }
        val rem = data.size - off
        if (rem == 0) return
        tail.fill(0)
        System.arraycopy(data, off, tail, 0, rem)
        polyBlock(tail, 0, h, r0, r1, r2, r3, r4, s1, s2, s3, s4)
    }

    /** One 16-byte block plus the 2^128 marker, reduced mod 2^130-5. */
    private fun polyBlock(
        m: ByteArray,
        off: Int,
        h: LongArray,
        r0: Long, r1: Long, r2: Long, r3: Long, r4: Long,
        s1: Long, s2: Long, s3: Long, s4: Long,
    ) {
        var h0 = h[0] + (u32(m, off) and 0x3ffffffL)
        var h1 = h[1] + ((u32(m, off + 3) ushr 2) and 0x3ffffffL)
        var h2 = h[2] + ((u32(m, off + 6) ushr 4) and 0x3ffffffL)
        var h3 = h[3] + ((u32(m, off + 9) ushr 6) and 0x3ffffffL)
        var h4 = h[4] + (u32(m, off + 12) ushr 8) + (1L shl 24)
        val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
        val d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
        val d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
        val d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
        val d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
        var c = d0 ushr 26
        h0 = d0 and 0x3ffffffL
        var e = d1 + c; c = e ushr 26; h1 = e and 0x3ffffffL
        e = d2 + c; c = e ushr 26; h2 = e and 0x3ffffffL
        e = d3 + c; c = e ushr 26; h3 = e and 0x3ffffffL
        e = d4 + c; c = e ushr 26; h4 = e and 0x3ffffffL
        e = h0 + c * 5; c = e ushr 26; h0 = e and 0x3ffffffL
        h1 += c
        // Fold the leftover carry every block. Leaving it in h1 is fine for a
        // short handshake, but a multi-kilobyte audio frame overflows the
        // 63-bit product on the next multiply.
        c = h1 ushr 26; h1 = h1 and 0x3ffffffL
        h2 += c; c = h2 ushr 26; h2 = h2 and 0x3ffffffL
        h3 += c; c = h3 ushr 26; h3 = h3 and 0x3ffffffL
        h4 += c; c = h4 ushr 26; h4 = h4 and 0x3ffffffL
        e = h0 + c * 5; c = e ushr 26; h0 = e and 0x3ffffffL
        h1 += c
        h[0] = h0
        h[1] = h1
        h[2] = h2
        h[3] = h3
        h[4] = h4
    }

    private fun u32(b: ByteArray, off: Int): Long = load32(b, off).toLong() and 0xffffffffL

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
