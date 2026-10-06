package com.example.ava.server.noise

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class EspHomeNoiseTest {
    @Test
    fun rfc8439AeadVector() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val ad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
            .toByteArray(Charsets.US_ASCII)
        val expected = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691",
        )
        val sealed = ChaCha20Poly1305.encrypt(key, nonce, ad, plaintext)
        assertArrayEquals(expected, sealed)
        assertArrayEquals(plaintext, ChaCha20Poly1305.decrypt(key, nonce, ad, sealed))
    }

    @Test
    fun rfc8439AeadVectorInTree() {
        ChaCha20Poly1305.forceInTree = true
        try {
            rfc8439AeadVector()
        } finally {
            ChaCha20Poly1305.forceInTree = false
        }
    }

    @Test
    fun rfc7748X25519Vector() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePub = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        assertArrayEquals(alicePub, X25519.publicKey(alicePriv))
        assertArrayEquals(bobPub, X25519.publicKey(bobPriv))
        assertArrayEquals(shared, X25519.sharedSecret(alicePriv, bobPub))
        assertArrayEquals(shared, X25519.sharedSecret(bobPriv, alicePub))
    }

    @Test
    fun rfc7748X25519NineTimesNine() {
        val nine = hex("0900000000000000000000000000000000000000000000000000000000000000")
        val expected = hex("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079")
        assertArrayEquals(expected, X25519.publicKey(nine))
        assertArrayEquals(
            hex("e0cd1b31facf845835db463d8257724c37770e21ed10680c603a18eefebcb143"),
            X25519.sharedSecret(nine, expected),
        )
    }

    @Test
    fun x25519MatchesCryptographyRandomPairs() {
        val pairs = listOf(
            Triple(
                "d851855e0d4ba0dc8aaa5f0843db84ffa721c1187b5254603f0dce4c9118756f",
                "e002127f4dae002d26d987491104a895b900885c35742ee2fd11751a66fbb75d",
                "84bc32898f2bddc2db59b70ea91c0982cadb4f1a92454b5ae8f64fb866c87225",
            ),
            Triple(
                "20db389f6fec48a811e1024f90f63218c4cda7210103766c1d8f1326108cac5e",
                "d8ff128aa87447f1ecb85d3e1a10bbf801151c54c312d53039d6bae6b48f4c5f",
                "7095aeb97acb2976e209dcc24d8caaed68ff241d2557f6363afa938f91473450",
            ),
            Triple(
                "e8abe880beb1c357314ef7253b4d5d805e82dd5f5f6c1889f8f51bd65097f37a",
                "2828415a487ea5f9207b4e995713c9c6e768a9f4ac69f2f7aa10a229ef80f348",
                "80b02155fa1332280f6f73101977db5e90d379546abfc715df99dac6b7eccd52",
            ),
            Triple(
                "189fa684b038c37a5dfa86e4d55dd5a0b14e7629ecb073e007dbb16cd537ea50",
                "5019fefe96361f894075db67775b0faecf6f27064522b423310e341acae48f5b",
                "9051f9f655b7b544e0fab3ccccec73b7b3acf63f28ed2631e0912030551bb516",
            ),
        )
        for ((aPrivHex, bPrivHex, sharedHex) in pairs) {
            val aPriv = hex(aPrivHex)
            val bPriv = hex(bPrivHex)
            val shared = hex(sharedHex)
            val aPub = X25519.publicKey(aPriv)
            val bPub = X25519.publicKey(bPriv)
            assertArrayEquals(shared, X25519.sharedSecret(aPriv, bPub))
            assertArrayEquals(shared, X25519.sharedSecret(bPriv, aPub))
        }
    }

    @Test
    fun emptyKeyMeansEncryptionOff() {
        assertNull(EspHomeNoisePsk.decodeOrNull(""))
        assertNull(EspHomeNoisePsk.decodeOrNull("   "))
        assertTrue(EspHomeNoisePsk.isValidOrEmpty(""))
        assertFalse(EspHomeNoisePsk.isValidOrEmpty("not-base64"))
        val generated = EspHomeNoisePsk.generateBase64()
        val decoded = EspHomeNoisePsk.decodeOrNull(generated)
        assertNotNull(decoded)
        assertEquals(32, decoded!!.size)
        assertEquals(generated, Base64.getEncoder().encodeToString(decoded))
    }

    @Test
    fun decodeAcceptsHaCopiedKeyWithNewlines() {
        val raw = ByteArray(32) { (it + 1).toByte() }
        val key = Base64.getEncoder().encodeToString(raw)
        val wrapped = key.chunked(16).joinToString("\n")
        assertArrayEquals(raw, EspHomeNoisePsk.decodeOrNull(wrapped))
        assertArrayEquals(raw, EspHomeNoisePsk.decodeOrNull(" $key "))
        assertTrue(EspHomeNoisePsk.isValidOrEmpty(wrapped))
    }

    @Test
    fun emptyHelloPrologueMatchesHomeAssistant() {
        val expected = "NoiseAPIInit".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)
        assertArrayEquals(expected, EspHomeNoiseResponder.emptyHelloPrologue())
    }

    @Test
    fun handshakeAndTransportRoundtrip() {
        val psk = ByteArray(32) { (it + 3).toByte() }
        val initiator = EspHomeNoiseInitiator(psk)
        val responder = EspHomeNoiseResponder(psk, "kitchen_ava", "02:11:22:33:44:55")

        val hello = initiator.clientHello()
        assertEquals(0, hello.size)
        val serverHello = responder.serverHello(hello)
        assertEquals(0x01, serverHello[0].toInt() and 0xff)
        assertTrue(String(serverHello, Charsets.UTF_8).contains("kitchen_ava"))

        val reply = responder.handshakeReply(initiator.firstHandshake())
        initiator.finish(reply)

        val encrypted = initiator.encryptPacket(type = 3, payload = "hello".toByteArray())
        val (type, payload) = responder.decryptPacket(encrypted)
        assertEquals(3, type)
        assertEquals("hello", String(payload, Charsets.UTF_8))

        val back = responder.encryptPacket(type = 4, payload = "ok".toByteArray())
        val (backType, backPayload) = initiator.decryptPacket(back)
        assertEquals(4, backType)
        assertEquals("ok", String(backPayload, Charsets.UTF_8))
    }

    @Test
    fun flynnNoiseNNpsk0EmptyPrologue() {
        replayFlynnNNpsk0(
            prologue = ByteArray(0),
            msg0 = "358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254e7136508cb8178281204abd62e9f2a3e",
            msg1 = "64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d48466922f3b7824001193c077abd8b7a73030",
        )
    }

    @Test
    fun flynnNoiseNNpsk0HomeAssistantPrologue() {
        // aioesphomeapi 45.6.1 hardcodes prologue NoiseAPIInit||0x00 0x00 (empty client hello).
        // Bytes from noiseprotocol 0.3.1 + ESPHome ChaCha backend, Flynn ephemeral keys.
        replayFlynnNNpsk0(
            prologue = EspHomeNoiseResponder.emptyHelloPrologue(),
            msg0 = "358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254daad72fb008141cf4f77770e093d5f20",
            msg1 = "64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d484663fe706c1f3b7aee3b43c0e4f5b6dd282",
        )
    }

    @Test
    fun homeAssistantHandshakeWireFrames() {
        val psk = ByteArray(32) { (it + 3).toByte() }
        val initiator = EspHomeNoiseInitiator(psk)
        val responder = EspHomeNoiseResponder(psk, "kitchen_ava", "02:11:22:33:44:55")
        val hello = initiator.clientHello()
        assertEquals(0, hello.size)
        val serverHello = responder.serverHello(hello)
        assertEquals(0x01, serverHello[0].toInt() and 0xff)
        assertEquals(0, serverHello[1 + "kitchen_ava".length].toInt())
        val handshake = initiator.firstHandshake()
        assertEquals(0, handshake[0].toInt())
        assertEquals(49, handshake.size)
        val reply = responder.handshakeReply(handshake)
        assertEquals(0, reply[0].toInt())
        assertEquals(49, reply.size)
        initiator.finish(reply)
    }

    @Test
    fun flynnNoiseNNpsk0WithPrologue() {
        replayFlynnNNpsk0(
            prologue = hex("6e6f74736563726574"),
            msg0 = "358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd1662546e96a20116b68fd776478e81d11779ca",
            msg1 = "64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d4846666f51bc44f88917daa53fb4529499b55",
        )
    }

    @Test
    fun flynnNoiseNNpsk0HandshakePayload() {
        val psk = FLYNN_PSK
        val initiator = NoiseNNpsk0.initiator(psk, ByteArray(0), FLYNN_INIT_E)
        val responder = NoiseNNpsk0.responder(psk, ByteArray(0), FLYNN_RESP_E)
        val payload0 = hex("746573745f6d73675f30")
        val payload1 = hex("746573745f6d73675f31")
        val written0 = initiator.writeMessage(payload0)
        assertArrayEquals(
            hex("358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd1662547c78f22f8cea986f934a675201c7f431a539e50c9be46986fa89"),
            written0,
        )
        assertArrayEquals(payload0, responder.readMessage(written0))
        val written1 = responder.writeMessage(payload1)
        assertArrayEquals(
            hex("64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d484666913ea64c74c2f63ee5e814fda25301b9508e4685ee02e9852fd"),
            written1,
        )
        assertArrayEquals(payload1, initiator.readMessage(written1))
    }

    @Test
    fun decodePacketHonorsDeclaredDataLen() {
        val payload = "abc".toByteArray()
        val plain = byteArrayOf(0, 7, 0, 3) + payload + byteArrayOf(9, 9, 9)
        val (type, data) = decodeEspHomeNoisePacket(plain)
        assertEquals(7, type)
        assertArrayEquals(payload, data)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodePacketRejectsShortDeclaredLen() {
        decodeEspHomeNoisePacket(byteArrayOf(0, 1, 0, 5, 1, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun encryptPacketRejectsOversizePayload() {
        val psk = ByteArray(32) { 1 }
        val initiator = EspHomeNoiseInitiator(psk)
        val responder = EspHomeNoiseResponder(psk, "ava", "00:00:00:00:00:00")
        responder.serverHello(initiator.clientHello())
        initiator.finish(responder.handshakeReply(initiator.firstHandshake()))
        responder.encryptPacket(1, ByteArray(EspHomeNoiseResponder.MAX_PLAINTEXT + 1))
    }

    @Test(expected = MacFailureException::class)
    fun wrongPskFailsClosed() {
        val good = ByteArray(32) { 1 }
        val bad = ByteArray(32) { 2 }
        val initiator = EspHomeNoiseInitiator(bad)
        val responder = EspHomeNoiseResponder(good, "ava", "00:00:00:00:00:00")
        responder.serverHello(initiator.clientHello())
        responder.handshakeReply(initiator.firstHandshake())
    }

    private fun replayFlynnNNpsk0(prologue: ByteArray, msg0: String, msg1: String) {
        val initiator = NoiseNNpsk0.initiator(FLYNN_PSK, prologue, FLYNN_INIT_E)
        val responder = NoiseNNpsk0.responder(FLYNN_PSK, prologue, FLYNN_RESP_E)
        val written0 = initiator.writeMessage()
        assertArrayEquals(hex(msg0), written0)
        assertArrayEquals(ByteArray(0), responder.readMessage(written0))
        val written1 = responder.writeMessage()
        assertArrayEquals(hex(msg1), written1)
        assertArrayEquals(ByteArray(0), initiator.readMessage(written1))

        val initTransport = initiator.split()
        val respTransport = responder.split()
        val yellow = hex("79656c6c6f777375626d6172696e65")
        val sub = hex("7375626d6172696e6579656c6c6f77")
        val msg2 = hex("b349a522c145762c7c737ac1d1425ce1fb25c7cca626177ee4ceed3cd6fb3d")
        val msg3 = hex("b41e24399dc3f1ad2faf82868700e4bf31bb89f6616e1d6a92802bb8ad80d6")
        assertArrayEquals(msg2, initTransport.encrypt(yellow))
        assertArrayEquals(yellow, respTransport.decrypt(msg2))
        assertArrayEquals(msg3, respTransport.encrypt(sub))
        assertArrayEquals(sub, initTransport.decrypt(msg3))
    }

    private fun hex(value: String): ByteArray {
        val clean = value.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private val FLYNN_PSK = ByteArray(32) { i ->
            "2176657279736563726574766572797365637265747665727973656372657421"
                .substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        private val FLYNN_INIT_E = ByteArray(32) { i ->
            "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
                .substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        private val FLYNN_RESP_E = ByteArray(32) { i ->
            "4142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60"
                .substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
