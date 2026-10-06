package com.example.ava.fleet

import android.content.Context
import com.example.ava.voice.AvaVoiceDevice
import com.example.ava.voice.AvaVoiceDiscovery
import com.example.ava.voice.AvaVoiceProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fleet device directory from UDP Ava beacons.
 *
 * Live wireless ADB peers are listed by [FleetAdbHost.devicesJson] and merged
 * in the console. Remembered-but-gone serials must not reappear here.
 *
 * - clusterPort / clusterEnabled = peer advertised Ava HTTP agent only (never invented).
 * - consoleEnabled = peer serves the website SPA (optional).
 */
object FleetDeviceDirectory {
    fun devicesJson(context: Context, localPort: Int): JSONObject {
        val devices = JSONArray()
        devices.put(localEntry(context, localPort))
        val seenIds = HashSet<String>()
        val localId = AvaVoiceDiscovery.localId().ifBlank {
            AvaVoiceDiscovery.resolveLocalDeviceId(context)
        }
        seenIds.add(localId)
        for (peer in AvaVoiceDiscovery.devices.value) {
            if (!seenIds.add(peer.id)) continue
            devices.put(peerEntry(peer))
        }
        return JSONObject()
            .put("ok", true)
            .put("discovery", "ava-voice-udp+adb")
            .put("discoveryPort", AvaVoiceProtocol.PORT)
            .put("identity", "ava")
            .put("count", devices.length())
            .put("devices", devices)
    }

    fun devicesArray(context: Context, localPort: Int): JSONArray =
        devicesJson(context, localPort).getJSONArray("devices")

    private fun localEntry(context: Context, localPort: Int): JSONObject {
        val id = AvaVoiceDiscovery.localId().ifBlank {
            AvaVoiceDiscovery.resolveLocalDeviceId(context)
        }
        val name = AvaVoiceDiscovery.localName()
        val host = AvaVoiceDiscovery.localHost().ifBlank {
            FleetNetwork.getLocalIpAddress(context).orEmpty()
        }
        val type = AvaVoiceDiscovery.localType().wireValue
        val consoleEnabled = FleetManager.isServingWebConsole()
        val accessUrl = if (consoleEnabled) {
            FleetNetwork.buildAccessUrl(
                host.takeIf { it.isNotBlank() && it != "0.0.0.0" },
                localPort,
            )
        } else {
            ""
        }
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("host", host)
            .put("type", type)
            .put("identity", "ava")
            .put("local", true)
            .put("clusterEnabled", true)
            .put("consoleEnabled", consoleEnabled)
            .put("clusterPort", localPort)
            .put("accessUrl", accessUrl)
            .put("lastSeenMs", System.currentTimeMillis())
            .put("source", "local")
            .put("sources", JSONArray().put("local"))
    }

    private fun peerEntry(peer: AvaVoiceDevice): JSONObject {
        // Trust the existing UDP beacon only.
        // clusterPort >0 = peer opted into cluster agent; webConsole = website SPA.
        val clusterPort = peer.clusterPort?.coerceIn(0, 65535) ?: 0
        val clusterEnabled = clusterPort > 0
        // Legacy peers without webConsole advertised full console when port > 0.
        val consoleEnabled = when {
            peer.webConsole != null -> peer.webConsole == true
            else -> clusterEnabled
        }
        // accessUrl only when peer serves the website SPA (agent-only returns JSON stub).
        val accessUrl = if (consoleEnabled) {
            FleetNetwork.buildAccessUrl(peer.host, clusterPort)
        } else {
            ""
        }
        return JSONObject()
            .put("id", peer.id)
            .put("name", peer.name)
            .put("host", peer.host)
            .put("type", peer.type.wireValue)
            .put("identity", peer.identity)
            .put("local", false)
            .put("clusterEnabled", clusterEnabled)
            .put("consoleEnabled", consoleEnabled)
            .put("clusterPort", clusterPort)
            .put("accessUrl", accessUrl)
            .put("lastSeenMs", peer.lastSeenMs)
            .put("source", "ava-voice-udp")
            .put("sources", JSONArray().put("ava-voice-udp"))
    }
}
