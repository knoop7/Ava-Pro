# Ava Fleet Console — HTTP API Protocol Contract

> Shared contract between backend (Kotlin) and frontend (JS) agents.
> Protocol version: **1** (`FleetStatusBuilder.PROTOCOL_VERSION`)

---

## Transport & Security

| Property | Value |
|---|---|
| Transport | HTTP/1.1 over TCP, LAN only |
| Default port | `8888` (`FleetManager.DEFAULT_PORT`) |
| Auth | Access **password** in `experimental.clusterAccessToken` (default `1234` when cluster first enabled). Browser login gate sends the same string as `X-Ava-Fleet-Token`, or query `password` / `token` / `fleetToken`. Empty password = open LAN (compat). |
| CORS | `Access-Control-Allow-Origin: *`; allow header `X-Ava-Fleet-Token` |
| Cache | `Cache-Control: no-store` on all API responses |
| Max concurrent | 12 clients |
| Request timeout | 12 s (SSE/MJPEG use 45 s soTimeout) |

**Gated when token configured:** settings read/write/export/import, screen frame/mjpeg/scrcpy/capture, tap, logs, shell, cluster proxies.  
**Always open:** `/v1/hello`, static console assets, `/v1/status`, `/v1/devices`, `/v1/telemetry`, `/v1/events`, `/v1/settings/schema`.

`GET /v1/hello` and `/v1/status` include `auth: { required, header, query, scheme }`.

---

## UDP Discovery Identity

Devices advertise presence via **UDP broadcast** on port **19848**
(`AvaVoiceProtocol.PORT`), reusing the existing Ava voice beacon protocol.

### Beacon wire format

```
AVA_VOICE_BEACON|{deviceId}|{deviceName}|{deviceType}|{hostIp}|clusterPort={0|8888}|webConsole={0|1}
```

- `AVA_VOICE_BEACON` — fixed prefix (`AvaVoiceProtocol.BEACON_PREFIX`)
- `deviceType` — one of `phone`, `tablet`, `speaker`, `tv`, `unknown`
- `clusterPort=0` — Ava identity present, cluster **agent** off
- `clusterPort=8888` — cluster agent API on that port (screen/shell/settings; website optional)
- `webConsole=0|1` — whether the peer also serves the website SPA (hub only; older peers omit this)
- Beacon interval: **5 s** (`BEACON_INTERVAL_MS`)
- Device stale after: **30 s** (`DEVICE_STALE_MS`)

### Query

```
AVA_VOICE_QUERY|{requesterId}
```

Peers reply with their beacon immediately.

### Discovery holders

Multiple subsystems share the same UDP socket via refcount holders:
`voice`, `fleet`, `presence`.

---

## Existing Endpoints

### GET /v1/hello

Lightweight handshake. Returns identity + capabilities.

```json
{
  "ok": true,
  "protocolVersion": 1,
  "product": "ava-cluster",
  "identity": "ava",
  "deviceId": "ava_123456",
  "deviceName": "Living Room",
  "appVersion": "2.8.0",
  "port": 8888,
  "capabilities": ["status.read", "events.sse", "discovery.udp", "..."]
}
```

### GET /v1/status

Full device snapshot (same payload pushed via SSE). Includes telemetry,
screen state, device list, and console source.

```json
{
  "ok": true,
  "protocolVersion": 1,
  "product": "ava-cluster",
  "identity": "ava",
  "deviceId": "ava_123456",
  "local": true,
  "deviceName": "Living Room",
  "ip": "192.168.1.42",
  "port": 8888,
  "accessUrl": "http://192.168.1.42:8888",
  "appVersion": "2.8.0",
  "androidVersion": "Android 14 (SDK 34)",
  "model": "Pixel 7",
  "manufacturer": "Google",
  "voicePort": 12345,
  "voiceSatellite": "running",
  "uptimeMs": 86400000,
  "capabilities": ["..."],
  "discovery": {
    "protocol": "ava-voice-udp",
    "port": 19848,
    "running": true,
    "advertisedClusterPort": 8888
  },
  "screen": {
    "available": true,
    "mode": "scrcpy-ready",
    "displayWidth": 1080,
    "displayHeight": 2400,
    "tapViaAccessibility": true,
    "scrcpy": { "canLaunch": true, "shellBackend": "shizuku", "running": false }
  },
  "devices": ["... (array of device objects)"],
  "deviceCount": 3,
  "telemetry": { "... (FleetTelemetry snapshot)" },
  "consoleSource": "bundled",
  "ts": 1719000000000
}
```

Legacy alias: `GET /api/status` → same response.

### GET /v1/devices

Device directory from UDP voice discovery.

```json
{
  "ok": true,
  "discovery": "ava-voice-udp",
  "discoveryPort": 19848,
  "identity": "ava",
  "count": 3,
  "devices": [
    {
      "id": "ava_123456",
      "name": "Living Room",
      "host": "192.168.1.42",
      "type": "phone",
      "identity": "ava",
      "local": true,
      "clusterEnabled": true,
      "clusterPort": 8888,
      "accessUrl": "http://192.168.1.42:8888",
      "lastSeenMs": 1719000000000,
      "source": "local"
    },
    {
      "id": "ava_789012",
      "name": "Kitchen",
      "host": "192.168.1.43",
      "type": "tablet",
      "identity": "ava",
      "local": false,
      "clusterEnabled": true,
      "clusterPort": 8888,
      "accessUrl": "http://192.168.1.43:8888",
      "lastSeenMs": 1719000000000,
      "source": "ava-voice-udp"
    }
  ]
}
```

### GET /v1/telemetry

Full telemetry snapshot including slow sensors (light, ambient temp).

Legacy alias: `GET /v1/metrics` → same response.

```json
{
  "cpu": { "percent": 12.5, "loadavg": { "m1": 1.2, "m5": 0.8, "m15": 0.6 } },
  "memory": { "usedPercent": 65.3, "usedBytes": 2147483648, "totalBytes": 4294967296 },
  "storage": { "freeGb": 28.4, "usedPercent": 55 },
  "battery": { "levelPercent": 85, "temperatureC": 31.2, "chargeSource": "usb", "voltageV": 4.1 },
  "thermal": { "statusLabel": "nominal", "zones": [{ "name": "cpu0", "type": "cpu", "celsius": 42 }] },
  "wifi": { "rssiDbm": -45, "linkSpeedMbps": 300, "ssid": "HomeNet" },
  "process": { "pssKb": 65000, "javaHeapUsedBytes": 33554432, "javaHeapMaxBytes": 268435456 },
  "sensors": { "lightLux": 350, "ambientTemperatureC": 24.5, "deferred": false },
  "modules": [{ "id": "com.example.mod", "name": "My Mod", "version": "1.0", "enabled": true, "hasUpdate": false, "missingPermissions": 0, "description": "..." }],
  "history": {
    "cpuPercent": [{ "t": 1719000000000, "v": 12.5 }],
    "memoryUsedPercent": [{ "t": 1719000000000, "v": 65.3 }]
  }
}
```

### GET /v1/events

Server-Sent Events stream. Pushes `status` events every ~2 s with the
same payload as `/v1/status`.

```
Content-Type: text/event-stream; charset=utf-8

id: 1
event: status
data: { ... /v1/status payload ... }
```

### GET /v1/screen/frame.jpg

Latest JPEG for the console.

**Modes**
- `?oneshot=1&force=1` — one-shot `screencap` (first frame / device wall). Does not start scrcpy.
- Default (after `POST /v1/screen/scrcpy/start`) — live JPEG from `FleetScrcpyBridge` (`X-Ava-Screen-Mode: scrcpy-mediacodec-jpeg`).

On failure: `503` with `{ "ok": false, "error": "scrcpy_not_started" | "need_shizuku_or_root" | "waiting_first_frame" | … }`.

### GET /v1/screen/last.jpg

Serve the most recent one-shot JPEG **without capturing again** (used by Screen Capture mod Image URL). Auth via `?password=` / fleet token as usual. `404` when no shot exists yet.

### GET /v1/shell

Shell capability status (`backend`: `shizuku` | `root` | null). Same privilege plane as `adb shell`.

### POST /v1/shell/exec

Run a shell command via Shizuku user-service or `su`.

```json
{ "command": "id", "timeoutSec": 15 }
```

```json
{ "ok": true, "code": 0, "stdout": "uid=2000(shell)…", "stderr": "", "backend": "shizuku", "adbEquivalent": true }
```

Blocked: reboot / wipe / fork-bomb style commands.

### POST /v1/cluster/shell

Proxy shell to a peer Ava (avoids browser CORS).

```json
{ "host": "192.168.1.103", "port": 8888, "command": "getprop ro.product.model" }
```

Capabilities: `shell.exec`, `cluster.shell`.

### GET /v1/screen/mjpeg

Continuous MJPEG from the same scrcpy bridge (`multipart/x-mixed-replace`).

| Query param | Default | Range | Description |
|---|---|---|---|
| `intervalMs` | 120 | 50–1000 | ms between frames |

### POST /v1/input/tap

Remote touch (currently Accessibility service; scrcpy control socket planned).

| Query param | Required | Description |
|---|---|---|
| `x` | yes | Screen X coordinate |
| `y` | yes | Screen Y coordinate |

```json
{ "ok": true, "x": 540, "y": 1200 }
```

Returns `400` if x/y missing, `503` if Accessibility not connected.

### GET /v1/logs

Ava **process** logcat dump (this device). Not full-system ADB.

| Query param | Default | Description |
|---|---|---|
| `limit` | 50 | Max lines (hard-capped at 50) |
| `force` | false | Bypass server throttle cache |

Hard rules:
- Max **50** lines per response
- Server throttle: min **1500 ms** between real `logcat` executions (returns cached + `throttled: true`)
- Scope: current Ava PID (`logcat -d --pid=<pid>`)

`logcat` only survives as long as the process does, so the same response also
carries `incidents`: the last 20 abnormal ends of Ava, kept on the device across
restarts. They ride along on a request the console already makes — no extra
endpoint, no polling, no push.

`incidents` is empty and `incidentsEnabled` is `false` until someone turns on
**Settings - Service - Logs - Show and export logs** on the device itself. The
records are always written; that switch only decides whether they leave the
device. Capability: `incidents.read`.

| Incident field | Description |
|---|---|
| `ts` | Wall clock of the incident (ms) |
| `kind` | `crash_java`, `crash_native`, `stall_restart`, `stall_only`, `renderer_crash`, `restart_user`, `exit_user`, `kill_remote` |
| `reason` | Short cause, e.g. the exception class or `device_control` |
| `detail` | Stack trace or context, truncated to 2000 chars over the wire |
| `stuckMs` | How long the main thread was wedged (stall kinds only, else 0) |
| `uptimeMs` | Process uptime when it ended |
| `version` | App version that produced the record |

```json
{
  "ok": true,
  "throttled": false,
  "retryAfterMs": 0,
  "source": "logcat",
  "scope": "ava-process",
  "pid": 12345,
  "packageName": "com.example.ava",
  "deviceId": "ava_…",
  "deviceName": "kitchen_panel",
  "limit": 50,
  "count": 12,
  "minIntervalMs": 1500,
  "ts": 1719000000000,
  "lines": [
    {
      "time": "07-21 16:01:02.345",
      "pid": 12345,
      "tid": 12345,
      "level": "I",
      "tag": "FleetHttpServer",
      "message": "Listening on port 8888",
      "raw": "07-21 16:01:02.345 12345 12345 I FleetHttpServer: Listening on port 8888"
    }
  ],
  "incidentsEnabled": true,
  "incidents": [
    {
      "ts": 1718999000000,
      "kind": "stall_restart",
      "reason": "main_thread_stall",
      "detail": "",
      "stuckMs": 15320,
      "uptimeMs": 862000,
      "version": "0.7.0"
    }
  ]
}
```

### POST /v1/cluster/pull-logs

Fetch peer device logs via the hosting Ava (avoids browser CORS/mixed issues).

```json
{ "host": "192.168.1.103", "port": 8888, "limit": 50 }
```

Response: same shape as `/v1/logs`, plus `peerHost` / `peerPort`.

Capabilities: `logs.read`, `cluster.pull-logs`.

### Scrcpy realtime layer (primary screen path)

Bundled Genymobile **scrcpy-server v3.3.4** (~89 KiB) in `assets/scrcpy-server`.

**Design (research → implementation in progress):**

1. Console **manual Start** → `POST /v1/screen/scrcpy/start`
2. Device starts server as shell via **Shizuku/Root** (`tunnel_forward=true`, `audio=false`, `control=false`; taps use Accessibility)
3. App process connects as scrcpy client on `localabstract:scrcpy` (`FleetScrcpyBridge`)
4. **Current refresh layer:** MediaCodec decode H.264 → latest JPEG → `GET /v1/screen/frame` / MJPEG (~120ms poll)
5. **Next:** WebSocket proxy of Annex-B / length-prefixed NALs → browser **WebCodecs** (no re-encode)

| Method | Path | Notes |
|---|---|---|
| GET | `/v1/screen/scrcpy` | Status + `bridge` (framesDecoded, consoleBridge) |
| POST | `/v1/screen/scrcpy/start` | Push + `app_process` + attach LocalSocket bridge |
| POST | `/v1/screen/scrcpy/stop` | Kill server + bridge |
| GET | `/v1/screen/frame` | Latest JPEG from scrcpy MediaCodec layer |
| GET | `/v1/screen/mjpeg` | Multipart of the same latest JPEG |

Requires shell UID (`canLaunch`). `consoleBridge`: `idle` → `mediacodec-jpeg` → (planned) `ws-h264-webcodecs`.

---

## New Endpoints — Settings Contract

### GET /v1/settings

Returns all settings grouped for the console UI, with current values.

```json
{
  "ok": true,
  "revision": 42,
  "groups": [
    {
      "id": "voice",
      "titleKey": "settingsGroupVoice",
      "tier": "basic",
      "haPublished": true,
      "fields": [
        {
          "path": "voice.satellite.name",
          "type": "string",
          "value": "Living Room",
          "haPublished": true
        },
        {
          "path": "voice.satellite.port",
          "type": "int",
          "value": 12345
        },
        {
          "path": "voice.wake.enabled",
          "type": "bool",
          "value": true
        }
      ]
    },
    {
      "id": "cluster",
      "titleKey": "settingsGroupCluster",
      "tier": "advanced",
      "fields": []
    }
  ],
  "settings": {
    "voice.satellite.name": "Living Room",
    "voice.satellite.port": 12345,
    "voice.wake.enabled": true
  }
}
```

| Field | Description |
|---|---|
| `revision` | Monotonic counter; incremented on every apply |
| `groups[].tier` | `"basic"` or `"advanced"` — UI can filter |
| `groups[].haPublished` | Some groups map to HA entities |
| `fields[].path` | Dot-separated setting path matching Ava datastore |
| `fields[].type` | `string`, `int`, `bool`, `float`, `enum`, `json` |
| `fields[].haPublished` | If this field is exposed to Home Assistant |
| `settings` | Flat map of all paths → current values |

### GET /v1/settings/schema

Same structure as `/v1/settings` but **without** `value` fields or
`settings` map. Used by frontend to build forms before values load.

### GET /v1/settings/export

Returns the raw ava-backup v1 JSON (same format the Android backup
screen produces).

```json
{
  "format": "ava-backup",
  "version": 1,
  "exportedAt": "2024-06-22T12:00:00Z",
  "device": { "id": "ava_123456", "name": "Living Room" },
  "settings": { "...flat or nested map..." },
  "mods": []
}
```

### POST /v1/settings/import

Import an ava-backup v1 JSON file.

**Request body**: the complete ava-backup JSON object.

| Query param | Description |
|---|---|
| `dryRun=1` | Validate only, do not apply |

**Response**:

```json
{
  "ok": true,
  "applied": 42,
  "errors": []
}
```

On validation failure:

```json
{
  "ok": false,
  "applied": 0,
  "errors": [
    { "path": "voice.satellite.port", "error": "out_of_range", "detail": "1–65535" }
  ]
}
```

### POST /v1/settings/apply

Apply partial settings changes.

**Request body**:

```json
{
  "settings": {
    "voice.satellite.name": "Kitchen",
    "voice.wake.enabled": false
  }
}
```

Settings keys can be flat dot-paths or nested objects — backend accepts
both. Matches the Ava `APPLY_SETTINGS` intent contract.

**Response**:

```json
{
  "ok": true,
  "applied": 2,
  "revision": 43,
  "restartRequired": true
}
```

### GET /v1/settings/ha

Home Assistant integration switches — which settings are published as
HA entities.

```json
{
  "ok": true,
  "switches": [
    {
      "path": "screen.power.control",
      "labelKey": "settingsScreenPowerControl",
      "enabled": true,
      "entityHint": "switch.ava_screen_power"
    },
    {
      "path": "browser.ha.display",
      "labelKey": "settingsBrowserHaDisplay",
      "enabled": false
    }
  ]
}
```

---

## New Endpoints — Cluster Exchange

Cross-device settings transfer over LAN HTTP.

### POST /v1/cluster/probe

Probe a peer device to check reachability and identity.

**Request body**:

```json
{
  "host": "192.168.1.43",
  "port": 8888
}
```

`port` defaults to `8888` if omitted.

**Response**:

```json
{
  "ok": true,
  "hello": { "... /v1/hello response from peer ..." },
  "statusSummary": {
    "deviceName": "Kitchen",
    "appVersion": "2.8.0",
    "voiceSatellite": "running"
  }
}
```

On failure:

```json
{
  "ok": false,
  "error": "connection_refused"
}
```

### POST /v1/cluster/pull-settings

Pull a settings backup from a remote peer.

**Request body**:

```json
{
  "host": "192.168.1.43",
  "port": 8888
}
```

**Response**:

```json
{
  "ok": true,
  "backup": {
    "format": "ava-backup",
    "version": 1,
    "...": "... full ava-backup JSON from peer ..."
  }
}
```

### POST /v1/cluster/push-settings

Push a settings backup to a remote peer.

**Request body**:

```json
{
  "host": "192.168.1.43",
  "port": 8888,
  "backup": {
    "format": "ava-backup",
    "version": 1,
    "...": "... ava-backup JSON to apply on peer ..."
  }
}
```

**Response**:

```json
{
  "ok": true,
  "peerResponse": {
    "ok": true,
    "applied": 42,
    "errors": []
  }
}
```

---

## Capabilities

Advertised in `/v1/hello` → `capabilities` array.

### Existing

| Capability | Description |
|---|---|
| `status.read` | `/v1/status` available |
| `events.sse` | `/v1/events` SSE stream |
| `discovery.udp` | UDP beacon on 19848 |
| `telemetry.read` | `/v1/telemetry` deep snapshot |
| `modules.read` | Module list in telemetry |
| `screen.frame` | Single JPEG screenshot |
| `screen.mjpeg` | Continuous MJPEG stream |
| `input.tap` | Remote touch via Accessibility |
| `console.skeleton` | Static console HTML served |

### New (settings & cluster)

| Capability | Description |
|---|---|
| `settings.read` | `/v1/settings`, `/v1/settings/schema`, `/v1/settings/ha` |
| `settings.write` | `/v1/settings/apply` |
| `settings.export` | `/v1/settings/export` |
| `settings.import` | `/v1/settings/import` |
| `cluster.probe` | `/v1/cluster/probe` |
| `cluster.transfer` | `/v1/cluster/pull-settings`, `/v1/cluster/push-settings` |
| `logs.read` | `/v1/logs` Ava process logcat (≤50, throttled) |
| `incidents.read` | `/v1/logs` `incidents` array (device switch, default off) |
| `cluster.pull-logs` | `/v1/cluster/pull-logs` peer log proxy |

---

## Static Console Assets

Served from two sources (first hit wins):

1. **Hot dir**: `{externalFilesDir}/fleet-console/{file}`
2. **Bundled assets**: `assets/fleet/{file}`

Allowed files: `index.html`, `app.css`, `app.js`, `i18n.js`, `ava-icon.webp`, `ava-icon-light.webp`.

`consoleSource` field in `/v1/status` reports `"hot"` or `"bundled"`.

---

## Error Conventions

All JSON error responses follow:

```json
{
  "ok": false,
  "error": "error_code_snake_case",
  "detail": "optional human-readable detail"
}
```

Standard HTTP status codes: `400`, `404`, `405`, `503`.
