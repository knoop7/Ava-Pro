<img width="2555" height="1011" alt="Ava Pro Pixel-Art Banner" src="https://github.com/user-attachments/assets/3a0adaa7-5803-4ede-b32e-c5bc082820a8" />

# Ava Pro · Home Assistant's Android Companion

[![DeepWiki](https://img.shields.io/badge/DeepWiki-AI_Docs-003366?style=for-the-badge&labelColor=002244&logoColor=white)](https://deepwiki.com/knoop7/Ava)
![GitHub Downloads](https://img.shields.io/github/downloads/knoop7/ava/total?style=for-the-badge&logo=github&color=0D1117&labelColor=21262d&logoColor=white&label=DOWNLOADS)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-ffdd00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/knoop7)

For more practical guides, visit the [Wiki](https://github.com/knoop7/Ava/wiki).


native Android platform built specifically for Home Assistant. A single, lightweight installation on existing devices replaces standalone ESP32 Bluetooth proxy, dedicated satellite voice receiver, intercom hardware.

**It connects via the standard ESPHome API Officially certified secure millisecond-level protocol. No MQTT, no HACS, and no custom integration required.**

---


### Features

* **dominate screen:** Ava Pro runs as a background service. Voice feedback, clock, weather, media controls, and quick switches appear as floating overlays above any app you're currently using. They're disposable, and you retain control of your device.

* **overheat device:** The core engine is written in native C++, not wrapped in a browser container. Even on a wall-mounted tablet, idle CPU usage is near zero. Multiple devices that have been running on the wall for years have shown no overheating issues.

* **compatible with virtually any device:** Supports Android 5.0 to 16. It runs on old Fire tablets, retired phones, car infotainment systems, smart mirrors, and single-board computers.

* **All functions can be disabled:** Device's Bluetooth chip too weak? Simply disable the Bluetooth proxy. Don't need intercom? One-click deactivation. Just want a pure voice satellite? Cut it down to voice only. Each module is completely decoupled, enabling only the functions your device can handle.

---

### Capabilities

* **Voice** — C++ inference pipeline. Custom wake words, dual wake support. Acoustic echo cancellation (wake works while music plays). Local voiceprint recognition (<120ms, distinguishes family members, filters TV audio). Ambient sound detection (glass break, baby cry, doorbell, alarm, siren). All processed on-device.

* **Bluetooth** — ESPHome BLE proxy. IRK private address resolution. RSSI distance filtering with away-delay presence. Screen-off scanning. Extends Bluetooth coverage to every room where a device is placed. No ESP32 required.

* **Audio** — LAN room-to-room intercom and voice messaging. Sendspin multi-room sync with Music Assistant, 5.4-level lossless. Audio continues uninterrupted across overlay transitions.

* **Display** — Multi-layer overlays in one process. Floating panels, animated notification scenes, Stream Deck–style quick entities, Dawn magazine screensaver with Smart AOD and Pixel Shift. Optional Home Launcher mode.

* **Camera** — Native binary direct pipeline. Zero-latency, no HTTP bridge, no frame buffering. Snapshots and motion detection as Home Assistant sensors.

* **Sensors** — Native ESPHome environment pipeline. Light, magnetic field, proximity, battery, Wi‑Fi, storage, memory, uptime. Foreground service keeps updates alive with screen off. Light + proximity fusion for adaptive wake.

* **Fleet** — UDP LAN discovery + ADB. Remote screen control, shell, logs, config push. Passcode and authorized deep-link access. One console for every device in the house.

* **Mods** — DexClassLoader runtime loading. Offline ZIP import. Install and use directly: DLNA renderer, AirPlay receiver, Edge TTS (400+ voices), offline STT engine, Zigbee gateway, biometric auth, GPS, OpenClaw on-device AI, camera RTSP/MJPEG to NVR, device packs (Echo Show, Portal, Phicomm R1…). Install, update, or remove without touching the core.

  
---


### Start

**1. Install**

Download the latest APK from the [Releases](https://github.com/knoop7/Ava/releases).

**2. Connect**

Install the APK → open Home Assistant → Ava appears in the discovered ESPHome devices → click Add.

**3. Specialty**

Ava pairs beautifully with community-built Home Assistant UI. For ready-made control panels and blueprints, check out the work of **@lone-baggie** — everything installs and works right away:

- [**Home Assistant Blueprints**](https://github.com/lone-baggie/home-assistant-blueprints) — automation blueprints that pair with Ava for quick scene setups.
- [**HTML Views for Home Assistant**](https://github.com/lone-baggie/HTML-views-for-Home-assistant) — polished HTML dashboard views that connect directly to Ava.


---


## Lineage 

Special thanks to the original author for contributing the initial concept and design **@brownard**
Ava Pro is based on the original [brownard/Ava](https://github.com/brownard/Ava) 


Powered by [Home Assistant](https://www.home-assistant.io/) | [ESPHome](https://esphome.io/) | [WebRTC](https://webrtc.org/) | [Music Assistant](https://music-assistant.io/) | [Sendspin](https://github.com/music-assistant/sendspin) | [openWakeWord](https://github.com/dscripka/openWakeWord) | [microWakeWord](https://github.com/kahrendt/microWakeWord) | [ONNX Runtime](https://onnxruntime.ai/) | [TensorFlow Lite](https://ai.google.dev/edge/litert) | [GeckoView](https://mozilla.github.io/geckoview/) | [Shizuku](https://github.com/RikkaApps/Shizuku) | [Jetpack Compose](https://developer.android.com/jetpack/compose) | [Media3](https://developer.android.com/media/media3) | [Protocol Buffers](https://protobuf.dev/) | [Opus](https://opus-codec.org/)

Ava Pro is licensed under [CC BY-NC-ND 4.0](https://creativecommons.org/licenses/by-nc-nd/4.0/): you may share it with attribution, and you may not use it commercially or distribute a copied or modified version.

