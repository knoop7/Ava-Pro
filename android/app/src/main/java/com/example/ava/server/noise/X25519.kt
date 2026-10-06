package com.example.ava.server.noise

import java.math.BigInteger
import java.security.SecureRandom

/**
 * RFC 7748 X25519. Used by Noise `25519_*` DH.
 */
internal object X25519 {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val BASE = BigInteger.valueOf(9)

    fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray {
        val raw = ByteArray(32)
        random.nextBytes(raw)
        return clamp(raw)
    }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarmult(privateKey, encodeU(BASE))

    fun sharedSecret(privateKey: ByteArray, peerPublic: ByteArray): ByteArray =
        scalarmult(privateKey, peerPublic)

    fun scalarmult(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32) { "X25519 scalar must be 32 bytes" }
        require(u.size == 32) { "X25519 u-coordinate must be 32 bytes" }
        val k = decodeScalar(scalar)
        val uCoord = decodeU(u)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = uCoord
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = k.testBit(t).toInt()
            swap = swap xor kt
            cswap(swap, x2, x3).also { x2 = it.first; x3 = it.second }
            cswap(swap, z2, z3).also { z2 = it.first; z3 = it.second }
            swap = kt
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).mod(P).let { it.multiply(it).mod(P) }
            z3 = da.subtract(cb).mod(P).let { it.multiply(it).mod(P).multiply(uCoord).mod(P) }
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }
        cswap(swap, x2, x3).also { x2 = it.first; x3 = it.second }
        cswap(swap, z2, z3).also { z2 = it.first; z3 = it.second }
        val result = x2.multiply(z2.modInverse(P)).mod(P)
        return encodeU(result)
    }

    private fun Boolean.toInt(): Int = if (this) 1 else 0

    private fun clamp(raw: ByteArray): ByteArray {
        val k = raw.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        return k
    }

    private fun decodeScalar(k: ByteArray): BigInteger = fromLittleEndian(clamp(k))

    private fun decodeU(u: ByteArray): BigInteger {
        val copy = u.copyOf()
        copy[31] = (copy[31].toInt() and 127).toByte()
        return fromLittleEndian(copy)
    }

    private fun encodeU(u: BigInteger): ByteArray = toLittleEndian(u.mod(P), 32)

    private fun cswap(swap: Int, a: BigInteger, b: BigInteger): Pair<BigInteger, BigInteger> {
        val dummy = a.subtract(b).mod(P).multiply(BigInteger.valueOf(swap.toLong())).mod(P)
        return a.subtract(dummy).mod(P) to b.add(dummy).mod(P)
    }

    private fun fromLittleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

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
}
