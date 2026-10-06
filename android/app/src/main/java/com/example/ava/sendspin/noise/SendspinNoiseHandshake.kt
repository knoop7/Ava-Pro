package com.example.ava.sendspin.noise

import org.json.JSONObject

/**
 * Cleartext `client/init` → `server/init` → `noise/handshake` responder.
 * First-frame `server/hello` means the peer is a legacy server — caller
 * falls through to the existing plaintext hello path.
 */
internal class SendspinNoiseHandshake(
    private val identity: SendspinNoiseIdentity,
    private val sendText: (String) -> Boolean,
) {
    private var clientInitText: String? = null
    private var kk: NoiseKKpsk2? = null
    private var sawServerInit = false
    private var serverId: String = ""

    fun start(): Boolean {
        val payload = JSONObject()
            .put("client_id", identity.peerId)
            .put("version", SendspinNoiseCodec.PROTOCOL_VERSION)
            .put("suite", SendspinNoiseCodec.SUITE_CHACHA)
        // Android 7 org.json escapes '/' as '\/'. The prologue is the raw
        // frame, and aiosendspin parses with orjson, which accepts both, but
        // the bytes must stay the ones we actually transmit.
        val text = unescapedSolidus(
            JSONObject()
                .put("type", TYPE_CLIENT_INIT)
                .put("payload", payload)
                .toString(),
        )
        clientInitText = text
        return sendText(text)
    }

    fun onText(text: String): Event {
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Event.Failed("not json")
        return when (val type = obj.optString("type")) {
            TYPE_SERVER_HELLO -> Event.LegacyHello(text, obj.optJSONObject("payload") ?: JSONObject())
            TYPE_SERVER_INIT -> onServerInit(text, obj)
            TYPE_HANDSHAKE -> onHandshake(obj)
            TYPE_SERVER_ERROR -> {
                val reason = obj.optJSONObject("payload")?.optString("reason").orEmpty()
                Event.Failed("server/error $reason")
            }
            else -> Event.Failed("unexpected $type")
        }
    }

    private fun onServerInit(raw: String, obj: JSONObject): Event {
        if (sawServerInit) return Event.Failed("duplicate server/init")
        val payload = obj.optJSONObject("payload") ?: return Event.Failed("server/init without payload")
        if (payload.optInt("version", -1) != SendspinNoiseCodec.PROTOCOL_VERSION) {
            return Event.Failed("server/init version ${payload.opt("version")}")
        }
        val serverId = payload.optString("server_id", "")
        val serverPub = SendspinNoiseCodec.peerPublicKey(serverId)
            ?: return Event.Failed("server/init server_id len=${serverId.length}")
        val initText = clientInitText ?: return Event.Failed("server/init before client/init")
        val prologue = initText.toByteArray(Charsets.UTF_8) + raw.toByteArray(Charsets.UTF_8)
        kk = NoiseKKpsk2.responder(
            localStaticPriv = identity.privateBytes,
            remoteStaticPub = serverPub,
            prologue = prologue,
        )
        sawServerInit = true
        this.serverId = serverId
        return Event.Waiting(serverId)
    }

    private fun onHandshake(obj: JSONObject): Event {
        val session = kk ?: return Event.Failed("noise/handshake before server/init")
        val dataB64 = obj.optJSONObject("payload")?.optString("data").orEmpty()
        val cipher = SendspinNoiseCodec.b64urlDecode(dataB64)
            ?: return Event.Failed("noise/handshake data")
        val inner = try {
            session.readMessage1(cipher)
        } catch (e: Exception) {
            return Event.Failed("noise mac ${e.message}")
        }
        val innerObj = runCatching { JSONObject(String(inner, Charsets.UTF_8)) }.getOrNull()
            ?: return Event.Failed("noise payload")
        val pskId = innerObj.optString("psk_id")
        if (pskId.isEmpty()) return Event.Failed("noise missing psk_id")
        // aiosendspin message 1 also carries psk_category (sn / lt / pr). We only
        // hold the Sentinel. A long-term or pairing id is answered with the
        // Sentinel; the server admits that (allow_sentinel_fallback) instead of
        // dropping the player.
        val sentinel = pskId == SendspinNoiseCodec.SENTINEL_PSK_ID ||
            pskId == SendspinNoiseCodec.pskIdFor(SendspinNoiseCodec.SENTINEL_PSK)
        val msg2 = try {
            session.writeMessage2(
                SendspinNoiseCodec.SENTINEL_PSK,
                EMPTY_OBJECT,
            )
        } catch (e: Exception) {
            return Event.Failed("noise msg2 ${e.message}")
        }
        val reply = unescapedSolidus(
            JSONObject()
                .put("type", TYPE_HANDSHAKE)
                .put(
                    "payload",
                    JSONObject().put("data", SendspinNoiseCodec.b64urlEncode(msg2)),
                )
                .toString(),
        )
        if (!sendText(reply)) return Event.Failed("noise reply not sent")
        val transport = SendspinNoiseTransport(session.split(), serverId)
        return Event.Established(transport, sentinelFallback = !sentinel)
    }

    sealed class Event {
        data class Failed(val reason: String) : Event()
        data class Waiting(val serverId: String = "") : Event()
        data class Established(
            val transport: SendspinNoiseTransport,
            val sentinelFallback: Boolean = false,
        ) : Event()
        data class LegacyHello(val rawText: String, val payload: JSONObject) : Event()
    }

    companion object {
        const val TYPE_CLIENT_INIT = "client/init"
        const val TYPE_SERVER_INIT = "server/init"
        const val TYPE_HANDSHAKE = "noise/handshake"
        const val TYPE_SERVER_HELLO = "server/hello"
        const val TYPE_SERVER_ERROR = "server/error"
        private val EMPTY_OBJECT = "{}".toByteArray(Charsets.UTF_8)

        private fun unescapedSolidus(json: String): String = json.replace("\\/", "/")
    }
}
