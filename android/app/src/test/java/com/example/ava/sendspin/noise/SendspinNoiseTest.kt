package com.example.ava.sendspin.noise

import com.example.ava.server.noise.ChaCha20Poly1305
import com.example.ava.server.noise.X25519
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class SendspinNoiseTest {
    @Test
    fun sentinelPskIsSha256OfLabel() {
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("sendspin-sentinel-psk-v1".toByteArray(Charsets.US_ASCII))
        assertArrayEquals(expected, SendspinNoiseCodec.SENTINEL_PSK)
        assertArrayEquals(
            hex("1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3"),
            SendspinNoiseCodec.SENTINEL_PSK,
        )
    }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    @Test
    fun android7Base64UrlMatchesPeerId() {
        val pub = X25519.publicKey(ByteArray(32) { 0x22 })
        val encoded = SendspinNoiseCodec.encodeUrl(pub)
        assertEquals("D6poTtKIZ7l_Smot7l34zpdOdrcBjj8iocTPJnhXDyA", encoded)
        assertArrayEquals(pub, SendspinNoiseCodec.decodeUrl(encoded))
        assertArrayEquals(pub, SendspinNoiseCodec.decodeUrl("$encoded="))
    }

    @Test
    fun sentinelPskIdMatchesPublishedValue() {
        assertEquals(
            "GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo",
            SendspinNoiseCodec.SENTINEL_PSK_ID,
        )
        assertEquals(
            SendspinNoiseCodec.SENTINEL_PSK_ID,
            SendspinNoiseCodec.pskIdFor(SendspinNoiseCodec.SENTINEL_PSK),
        )
    }

    @Test
    fun kkpsk2RoundtripAndType0Json() {
        val clientPriv = X25519.generatePrivateKey()
        val serverPriv = X25519.generatePrivateKey()
        val clientPub = X25519.publicKey(clientPriv)
        val serverPub = X25519.publicKey(serverPriv)
        val prologue = "client/init-bytes|server/init-bytes".toByteArray(Charsets.UTF_8)

        val initiator = NoiseKKpsk2.initiator(serverPriv, clientPub, prologue)
        val responder = NoiseKKpsk2.responder(clientPriv, serverPub, prologue)

        val inner = JSONObject()
            .put("psk_id", SendspinNoiseCodec.SENTINEL_PSK_ID)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val msg1 = initiator.writeMessage1(inner)
        assertEquals(String(inner, Charsets.UTF_8), String(responder.readMessage1(msg1), Charsets.UTF_8))

        val msg2 = responder.writeMessage2(
            SendspinNoiseCodec.SENTINEL_PSK,
            "{}".toByteArray(Charsets.UTF_8),
        )
        assertEquals("{}", String(initiator.readMessage2(SendspinNoiseCodec.SENTINEL_PSK, msg2), Charsets.UTF_8))

        val serverTransport = SendspinNoiseTransport(initiator.split())
        val clientTransport = SendspinNoiseTransport(responder.split())

        val toClient = serverTransport.encryptJson("""{"type":"server/hello"}""")
        assertEquals(1, toClient.size)
        val fromServer = clientTransport.decryptFrame(toClient[0])!!
        assertEquals(SendspinNoiseTransport.MSG_JSON, fromServer[0].toInt() and 0xFF)
        assertEquals(
            """{"type":"server/hello"}""",
            String(fromServer, 1, fromServer.size - 1, Charsets.UTF_8),
        )

        val toServer = clientTransport.encryptJson("""{"type":"client/hello"}""")
        val fromClient = serverTransport.decryptFrame(toServer[0])!!
        assertEquals(
            """{"type":"client/hello"}""",
            String(fromClient, 1, fromClient.size - 1, Charsets.UTF_8),
        )
    }

    /**
     * Known-answer vector produced by python-noise (the library aiosendspin /
     * Music Assistant use), with fixed static and ephemeral keys and the
     * Sentinel PSK. Ava is the responder: it must decrypt the server's
     * message 1, emit a byte-identical message 2, and agree on the transport
     * keys. This catches any deviation from the reference (e.g. the psk-pattern
     * rule that every `e` token also calls MixKey).
     */
    @Test
    fun kkpsk2MatchesPythonNoiseVectorInTree() {
        ChaCha20Poly1305.forceInTree = true
        try {
            kkpsk2MatchesPythonNoiseVector()
        } finally {
            ChaCha20Poly1305.forceInTree = false
        }
    }

    @Test
    fun kkpsk2MatchesPythonNoiseVector() {
        val serverStatic = ByteArray(32) { 0x11 }
        val clientStatic = ByteArray(32) { 0x22 }
        val clientEphemeral = ByteArray(32) { 0x44 }
        val clientInit = """{"type":"client/init","payload":{"client_id":"D6poTtKIZ7l_Smot7l34zpdOdrcBjj8iocTPJnhXDyA","version":1,"suite":"25519_ChaChaPoly_SHA256"}}"""
        val serverInit = """{"type":"server/init","payload":{"server_id":"e06Qm75__kTEZaIgA31gjuNYl9Me-XLwf3SJLLD3PxM","version":1}}"""
        assertEquals(
            JSONObject(clientInit).getJSONObject("payload").getString("client_id"),
            SendspinNoiseCodec.b64urlEncode(X25519.publicKey(clientStatic)),
        )
        val prologue = clientInit.toByteArray(Charsets.UTF_8) + serverInit.toByteArray(Charsets.UTF_8)

        val msg1 = hex(
            "7b0d47d93427f8311160781c7c733fd89f88970aef490d8aa0ee19a4cb8a1b142dc64c4cd1ef954e0aa20083e9151e59157973653f6741c210c280a98810ec48f50293551c7a8702e15bd03b42b9cc52b93d220bd84d338f0e4fa6f5aa1df2d38205564ee95a84dd",
        )
        val expectedMsg2 = hex(
            "ff2ee45601ec1b67310c7790404585ae697331eee1c1f8cf2419731c1fff3e6b44244796550d6ca623c1b50a203ab6dc169b",
        )
        val expectedHandshakeHash = hex("15a6f7bbf9b821c9202939573673218380cb7f7794827573e8dc4465599ae8d1")
        val ctServerHello = hex(
            "6f11de56842005905a29be6b73cff69e56749cab159343fb083e664e5a41c9d163df1b0cef68d5043f12526b3c117a07796554f074bc93a718f02617e0a7cc50",
        )
        val ctClientHello = hex("cd59a09a4baa756143c9313079831d5fc4ee82174ab7c943f10cad974bdea789d0967325565443c1")

        val responder = NoiseKKpsk2.responder(
            localStaticPriv = clientStatic,
            remoteStaticPub = X25519.publicKey(serverStatic),
            prologue = prologue,
            ephemeralPrivate = clientEphemeral,
        )
        val inner = JSONObject(String(responder.readMessage1(msg1), Charsets.UTF_8))
        assertEquals(SendspinNoiseCodec.SENTINEL_PSK_ID, inner.getString("psk_id"))

        val msg2 = responder.writeMessage2(SendspinNoiseCodec.SENTINEL_PSK, "{}".toByteArray(Charsets.UTF_8))
        assertArrayEquals(expectedMsg2, msg2)
        assertArrayEquals(expectedHandshakeHash, responder.handshakeHash)

        val transport = SendspinNoiseTransport(responder.split())
        val serverHello = transport.decryptFrame(ctServerHello)!!
        assertEquals(0, serverHello[0].toInt() and 0xFF)
        assertEquals(
            """{"type":"server/hello","payload":{"name":"MA"}}""",
            String(serverHello, 1, serverHello.size - 1, Charsets.UTF_8),
        )
        val out = transport.encryptJson("""{"type":"client/hello"}""")
        assertEquals(1, out.size)
        assertArrayEquals(ctClientHello, out[0])
    }

    @Test
    fun handshakeWireUsesExactPrologueBytes() {
        val clientPriv = X25519.generatePrivateKey()
        val identity = SendspinNoiseIdentity(clientPriv, X25519.publicKey(clientPriv))
        val serverPriv = X25519.generatePrivateKey()
        val serverId = SendspinNoiseCodec.b64urlEncode(X25519.publicKey(serverPriv))

        val sent = ArrayList<String>()
        val hs = SendspinNoiseHandshake(identity) { text ->
            sent += text
            true
        }
        assertTrue(hs.start())
        val clientInit = sent.single()
        assertEquals("client/init", JSONObject(clientInit).getString("type"))
        assertEquals(identity.peerId, JSONObject(clientInit).getJSONObject("payload").getString("client_id"))

        val serverInit = JSONObject()
            .put("type", "server/init")
            .put(
                "payload",
                JSONObject()
                    .put("server_id", serverId)
                    .put("version", SendspinNoiseCodec.PROTOCOL_VERSION)
                    .put("suite", SendspinNoiseCodec.SUITE_CHACHA),
            )
            .toString()
        assertTrue(hs.onText(serverInit) is SendspinNoiseHandshake.Event.Waiting)

        val prologue = clientInit.toByteArray(Charsets.UTF_8) + serverInit.toByteArray(Charsets.UTF_8)
        val initiator = NoiseKKpsk2.initiator(serverPriv, identity.publicBytes, prologue)
        val inner = JSONObject()
            .put("psk_id", SendspinNoiseCodec.SENTINEL_PSK_ID)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val handshakeFrame = JSONObject()
            .put("type", "noise/handshake")
            .put(
                "payload",
                JSONObject().put("data", SendspinNoiseCodec.b64urlEncode(initiator.writeMessage1(inner))),
            )
            .toString()

        val established = hs.onText(handshakeFrame)
        assertTrue(established is SendspinNoiseHandshake.Event.Established)
        assertEquals(2, sent.size)

        val msg2 = SendspinNoiseCodec.b64urlDecode(
            JSONObject(sent[1]).getJSONObject("payload").getString("data"),
        )!!
        assertEquals(
            "{}",
            String(initiator.readMessage2(SendspinNoiseCodec.SENTINEL_PSK, msg2), Charsets.UTF_8),
        )

        val serverTransport = SendspinNoiseTransport(initiator.split())
        val clientTransport = (established as SendspinNoiseHandshake.Event.Established).transport
        val frames = serverTransport.encryptJson("""{"type":"server/hello","payload":{}}""")
        val plain = clientTransport.decryptFrame(frames[0])!!
        assertEquals(0, plain[0].toInt() and 0xFF)
        assertEquals("server/hello", JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8)).getString("type"))
    }

    @Test
    fun firstFrameServerHelloIsLegacy() {
        val priv = X25519.generatePrivateKey()
        val hs = SendspinNoiseHandshake(SendspinNoiseIdentity(priv, X25519.publicKey(priv))) { true }
        assertTrue(hs.start())
        val ev = hs.onText("""{"type":"server/hello","payload":{"server_id":"legacy"}}""")
        assertTrue(ev is SendspinNoiseHandshake.Event.LegacyHello)
        assertEquals(
            "legacy",
            (ev as SendspinNoiseHandshake.Event.LegacyHello).payload.getString("server_id"),
        )
    }

    @Test
    fun unknownPskIdAnswersWithSentinel() {
        val clientPriv = X25519.generatePrivateKey()
        val identity = SendspinNoiseIdentity(clientPriv, X25519.publicKey(clientPriv))
        val serverPriv = X25519.generatePrivateKey()
        val serverId = SendspinNoiseCodec.b64urlEncode(X25519.publicKey(serverPriv))
        val sent = ArrayList<String>()
        val hs = SendspinNoiseHandshake(identity) { sent += it; true }
        hs.start()
        val serverInit = JSONObject()
            .put("type", "server/init")
            .put(
                "payload",
                JSONObject()
                    .put("server_id", serverId)
                    .put("version", 1)
                    .put("suite", SendspinNoiseCodec.SUITE_CHACHA),
            )
            .toString()
        hs.onText(serverInit)
        val prologue = sent[0].toByteArray(Charsets.UTF_8) + serverInit.toByteArray(Charsets.UTF_8)
        val initiator = NoiseKKpsk2.initiator(serverPriv, identity.publicBytes, prologue)
        val inner = JSONObject().put("psk_id", "not-the-sentinel").toString().toByteArray()
        val frame = JSONObject()
            .put("type", "noise/handshake")
            .put(
                "payload",
                JSONObject().put("data", SendspinNoiseCodec.b64urlEncode(initiator.writeMessage1(inner))),
            )
            .toString()
        val established = hs.onText(frame)
        assertTrue(established is SendspinNoiseHandshake.Event.Established)
        assertTrue((established as SendspinNoiseHandshake.Event.Established).sentinelFallback)
        val msg2 = SendspinNoiseCodec.b64urlDecode(
            JSONObject(sent[1]).getJSONObject("payload").getString("data"),
        )!!
        assertEquals(
            "{}",
            String(initiator.readMessage2(SendspinNoiseCodec.SENTINEL_PSK, msg2), Charsets.UTF_8),
        )
    }
}
