# Ava - Turn Any Android Device into a Smart Home Control Panel

For more practical guides, visit the [Wiki](https://github.com/knoop7/Ava/wiki). Images and detailed explanations will be added gradually.

Share a project I've been working on: **Ava**, an Android voice assistant app that turns old tablets and phones into powerful smart home control panels.

## Background

This project is based on [brownard/Ava](https://github.com/brownard/Ava) with extensive modifications and extensions. The original was a great ESPHome voice satellite implementation, but with basic features. I researched existing solutions: [Fully Kiosk](https://www.fully-kiosk.com/) is powerful but paid. [WallPanel](https://wallpanel.xyz/) is no longer maintained. The Android smart home panel space has been stagnant for years. So I decided to combine the best of these projects, using Ava as the foundation to build something truly useful.

## Core Philosophy

**No Extra Integrations**
No MQTT, no HACS. Devices are discovered natively by Home Assistant, just like ESPHome nodes.

**Built for Low-End Hardware**
Supports Android 5–16, including 32-bit devices. Cheap tablets and old phones work reliably.

**Expanding to Android 4.4–6**
Earlier Android versions are currently under active development. The goal is full BLE proxy compatibility without compromises

---

## Bluetooth Proxy - Exclusive Feature

**This is a feature no other Android panel app has.**

Your Android device becomes a complete Bluetooth gateway, extending Home Assistant's Bluetooth coverage. No ESP32 needed - just use your spare Android device!

**Features:** **BLE Proxy** - Forwards all Bluetooth Low Energy data to Home Assistant. **Whole-Home Coverage** - Deploy multiple Ava devices, each extends Bluetooth range. **Presence Detection** - Auto-detect phones, smartwatches, bands; trigger home/away automations. **All BLE Devices Compatible** - Temperature sensors, plant monitors, smart locks, all supported.

**Why This Matters:** ESP32 Bluetooth proxies need firmware flashing and configuration. Ava works out of the box - your Android device is already a powerful Bluetooth transceiver. Perfect for apartments or multi-story homes where one Bluetooth source isn't enough. Turn that dusty old tablet in the bedroom into a Bluetooth relay point.

**Presence Detection Use Cases:** Phone enters Bluetooth range → Auto lights on, AC on, play welcome message. Phone leaves for a while → Auto lights off, security mode on. Detect specific family member devices → Personalized scene triggers. Adjustable RSSI threshold and away delay for precise sensitivity control.

This feature alone is worth trying if you've struggled with Bluetooth coverage in Home Assistant.

> **Note:** Bluetooth proxy source code is not open source, only available in release builds. All other feature code is available on GitHub.

---

## Voice Messages & Calls - Local Home Intercom

Turn multiple Ava devices into a simple voice network inside your home. Send a quick voice message to another room, keep messages on a small message board, or start a live call between Ava devices on the same local network.

**Features:** **Voice Messages** - Record and send short messages to selected Ava devices. **Message Board** - Keep received messages on screen and replay them later. **Live Calls** - Call another Ava device directly for room-to-room conversation. **Local Network First** - Designed for LAN use, without a cloud account or external relay.

**Why This Matters:** A wall tablet should not only listen to Home Assistant. It should also help people at home talk to each other. Ava can become a kitchen-to-living-room intercom, a bedside message board, or a quick way to call the study without picking up a phone.

**Home Use Cases:** Kitchen says dinner is ready → living room Ava plays the message. Parent leaves a delayed reminder for a child's room → message stays available for replay. Bedroom calls the hallway panel → quick conversation without phones or extra apps.

---

## Floating Windows - Visual Differentiation

This is what makes Ava visually unique. **Floating windows overlay on top of any app** - display a full-screen HA dashboard while still seeing clock, weather, and notifications overlaid on top.

Other apps force you to choose: dashboard or clock. With Ava, you get both.

- **Always Visible** - Overlays on any app, including full-screen browsers. **HA Control** - Remotely toggle each window from Home Assistant. **Non-Intrusive** - Designed to complement, not block the main interface.

Available floating windows: Dream Clock - Elegant always-on clock. Vinyl Cover - Rotating record cover when playing music. Conversation Subtitles - Shows what you said and AI responses. Notification Scenes - Full-screen alerts for doorbell, alarms, etc.

---

## What is Ava?

Ava is a voice assistant app based on the ESPHome protocol. It turns your Android device into a voice satellite for Home Assistant.

**What can you do with it?** Control smart home devices with voice (lights, AC, music, etc.). Display beautiful screensavers and clocks. Show real-time weather information. Display full-screen notifications (doorbell, alerts, etc.). Play music with album cover display. Take photos and stream video.

Put your old phone or tablet at home, and it becomes a smart control panel!

**System Requirements:** Android 5.0 or higher. Home Assistant connection required.

> This makes Ava suitable for turning *any* phone or tablet into a native-looking Home Assistant panel, consistent with the rest of your HA ecosystem.




> Ava does not impose a custom UI identity.
> Instead, it deliberately follows Home Assistant’s visual language and iconography.
> This ensures that any device running Ava feels like an extension of Home Assistant itself, not a third-party layer on top.


---

## Quick Start

### Step 1: Install

1. Download the Ava APK file from [GitHub Releases](https://github.com/knoop7/Ava/releases). 2. Tap to install. 3. Allow all permission requests (microphone, overlay, etc.).

### Step 2: Connect to Home Assistant

**In Home Assistant:** 1. Go to **Settings** → **Devices & Services** → **Integrations**. 2. Search and add **ESPHome** integration. 3. Ava will be discovered automatically, tap **Configure**.

**In Ava app:** 1. Open Ava app. 2. Tap the **Settings** icon in the top right. 3. Select **Voice Satellite**. 4. Tap **Start Service**.

**After successful connection:** Status bar will show "Connected". You can see this device in Home Assistant.

### Step 3: Talk!

Say the wake word (default is "Hey Jarvis"), then say your command: "Turn on the living room light". "What time is it". "Play music". "What's the weather tomorrow".

---

## Features

### Voice Control

**How it works:** 1. Ava constantly listens for the wake word. 2. When you say the wake word, Ava starts recording. 3. Recording is sent to Home Assistant for recognition. 4. Home Assistant returns a response, Ava plays the voice.

**Settings:** Settings → Voice Satellite

| Setting | Description | Default |
|---------|-------------|---------|
| Device Name | Name shown in Home Assistant | device_model_voice_assistant |
| Port | ESPHome communication port | 6053 |
| Wake Word | Word that triggers voice recognition | Hey Jarvis |
| Stop Word | Word that interrupts current conversation | Stop |
| Wake Sound | Prompt sound when recording starts | Optional |
| Mute | Turn off microphone | Off |

**Supported Wake Words:** Hey Jarvis, Alexa, OK Nabu...

---

### Screensaver

**What is it:** After the device is idle for a while, a beautiful screensaver shows automatically.

**Types of screensavers:** **Dawn · Magazine Screensaver** - Rotating artwork with time, date, quotes, and live HA entity slots (recommended). **Web screensaver** - Shows web content. **Image screensaver** - Shows images you choose. **Simple Clock** - Floating clock with weather and HA status slots.

**Settings:** Settings → Screensaver

| Option | Description |
|--------|-------------|
| Enable | Turn screensaver on or off |
| Timeout | How long before screensaver shows (like 30 seconds) |
| Type | Choose image or web |
| URL | If you choose web, enter the web address |

---

### Weather Display

**What is it:** Shows current weather information on screen.

**What it shows:** Temperature, Weather condition (sunny, cloudy, rain, etc.), Humidity, Wind direction and speed, Air quality.

**Settings:** Settings → Interaction → Weather

---

### Notification Scenes

**What is it:** When smart home events happen, shows beautiful full-screen notifications.

**Built-in Scenes:**

| Scene ID | Scene Name | Purpose |
|----------|------------|---------|
| morning | Good Morning | Daily greeting |
| doorbell | Doorbell Ring | Doorbell alert |
| water_leak | Water Leak | Water sensor triggered |
| smoke | Smoke Alarm | Smoke sensor triggered |
| someone_home | Someone Home | Presence detection |
| package | Package Arrived | Delivery notification |
| timer | Timer Finished | Timer reminder |
| weather_alert | Weather Alert | Severe weather warning |

**90+ built-in scenes** - doorbell, smoke alarm, good morning, birthday wishes, timers. A notification scene library (0.6.9) lets you preview, copy, and edit scenes with banner styles (top/center/bottom), an expanded icon library, and per-scene color, animation, and sound configuration. Custom scene URLs supported. Scenes load from GitHub JSON files (scenes_zh.json/scenes_en.json) and cache locally for offline use.

**Settings:** Settings → Interaction → Notification Scenes

| Setting | Description |
|---------|-------------|
| Display Duration | How long notification shows (5-30 seconds) |
| Sound | Sound played when notification appears |
| Custom Scene URL | Load custom scenes from network |

**Trigger in Home Assistant:**
```yaml
service: select.select_option
target:
  entity_id: select.your_device_name_notification_scene
data:
  option: "doorbell"
```

---

### Music Playback

**What is it:** Ava can play music and show beautiful album covers.

**Special features:** Vinyl record style cover display. Auto-fetch Music covers. Voice announcements (TTS). Synced lyrics with expanded lyrics wall (0.6.5), karaoke lyrics (0.6.6), five-band equalizer for HA and Music Assistant (0.6.5), Music Assistant WebSocket console with queue management and multi-room playback transfer (0.6.8), lyric offset/follow controls (0.6.9).

**Settings:** Settings → Interaction

| Setting | Description |
|---------|-------------|
| Volume | Media playback volume |
| Vinyl Cover | Show album cover when playing music |
| Conversation Subtitles | Show voice conversation text |

**Play music in Home Assistant:**
```yaml
service: media_player.play_media
target:
  entity_id: media_player.your_device_name
data:
  media_content_id: "http://example.com/music.mp3"
  media_content_type: "music"
```

---

### Camera

**What is it:** Use the device camera to take photos or record video.

**What it can do:** Take photos and send to Home Assistant. Live video streaming (native ESPHome protocol, not MJPEG). Supports front and back camera (auto-detects available cameras, fixed single-lens devices in 0.6.6). Smart frame dropping for smooth performance. Camera view can be displayed on the screensaver layer triggered by Home Assistant (0.6.9).

**Settings:** Settings → Experimental → Camera

| Setting | Description |
|---------|-------------|
| Enable Camera | Turn on camera feature |
| Use Front Camera | Default to front or back camera |
| Video Frame Rate | 1-15 fps |
| Video Resolution | 240p-720p |

**In Home Assistant:** Camera appears as a camera entity. Add camera card to your dashboard.

---

### Built-in Browser

**What is it:** Display web pages inside Ava.

**What it can do:** Show Home Assistant dashboards. Show any web page. Inject custom CSS/JS. Pull-to-refresh support. Dual Pages (0.6.4) — two live WebViews side by side. WebView Health Steward (0.6.5–0.6.8) with entity-stream scheduling, chunked card rendering, dormant quieting, entity filtering, and dashboard outage recovery. HA Kiosk sidebar toggle. GeckoView engine pack for old devices.

**Settings:** Settings → Browser

| Setting | Description |
|---------|-------------|
| Homepage URL | Default web page address |
| Render Mode | Hardware/Software rendering |
| Custom CSS | Inject custom styles |
| Custom JS | Inject custom scripts |

**Control in Home Assistant:**
```yaml
service: text.set_value
target:
  entity_id: text.your_device_name_browser_url
data:
  value: "http://your-ha-address:8123/lovelace/0"
```

---

### Sensors

**What is it:** Ava reads device sensors and exposes them to Home Assistant.

**Supported sensors:** Light sensor (lux), Magnetic field sensor (μT), Proximity sensor, WiFi signal strength, Battery level, Storage space. All sensor data reported to Home Assistant as entities.

---

## Other Features

- **Audio Processing** - AEC, AGC, noise suppression, GPIO microphone array support. Five-band equalizer for HA and Music Assistant (0.6.5). Voice Stats diagnostics with real-time CPU headroom display (0.7.2). Shared echo-reference bus for voice and calls (0.7.3). **Screen Control** - Brightness sync, force orientation, proximity wake/sleep, pure-black low-brightness overlay for non-root devices (0.7.3).
- **Sendspin / Multi-Room Audio** - Sub-20ms sync via Music Assistant, adaptive playback speed correction, Opus/PCM/FLAC codecs, Music Assistant WebSocket console with full sidebar (0.7.0/0.7.2), per-device sync delay calibration (0.6.9), automatic calibration following Sendspin server playback time (0.7.0), peer-to-peer track metadata sync (0.7.1/0.7.2), dead-stream and skip-storm recovery (0.7.2).
- **Fleet Management** - Local cluster console for managing multiple Ava devices: screen control, shell access, log viewing, mod management, settings editor, ADB host, file manager (0.6.1+), landscape touch coordinate fix and thumbnail recovery (0.7.1).
- **Mod Store** - Install, configure, and manage mods that extend Ava's capabilities. Fleet-integrated mod management. Runtime permission fixes for AirPlay, DLNA, camera, and fingerprint mods (0.7.1). JAR mod compatibility on Android 14+ (0.7.3).
- **Software Update** - Redesigned in-app update flow: download progress in button, pause/resume, background auto-update (0.7.0). HA Update entity, release notes with cover art, reinstall support (0.6.4–0.7.0). Older-version installation guidance with settings export (0.7.0). Same-version rebuild detection and GitHub download policy (0.7.3).
- **Smart AOD** - Always-On Display power-saving dark mask for screensaver, Simple Clock, and Quick Entity panel (0.6.3).
- **Screensaver Timeout = 0** - Dashboard stays visible; HA can still trigger screensaver or camera view on demand (0.6.9).

---

## 0.7.x New Features

### Chorus Wake - Multi-Device Arbitration (0.7.3)
Several Ava devices sharing the same wake phrase no longer race to answer. ~180ms LAN arbitration combines wake confidence with real sound pressure. The nearest device takes the conversation; others show a breathing dim field. No cloud, MQTT, or extra automation needed.

### ESPHome Noise Encryption (0.7.3)
Optional ChaCha20-Poly1305 encrypted voice-satellite connections via standard Noise NNpsk0 handshake. Built-in ESPHome baseline raised to 2026.10.1. Plaintext path preserved for existing setups.

### Bluetooth Presence AI (0.7.2)
On-device radio inference engine: multi-signal temporal reasoning, dark-screen verification, tiered scanner recovery. Distinguishes "device left" from "receiver went deaf." Works across rooted, Shizuku, and non-root devices with Device Admin.

### C++ OpenWakeWord Engine (0.7.1/0.7.2)
Native C++ streaming engine with ~86% compute reduction. 96-dimensional acoustic embedding shared across wake words. ONNX graph reconstruction with stateful layer retention. microWakeWord native regression verification for children and female voices.

### Home Assistant Direct Connection (0.7.2)
Discover local HA instances or sign in with URL + token. Browse Assist pipelines, select preferred pipeline. Entity search for weather, media players, Quick Entities, Dawn cards. HLS camera streaming in Quick Entity with gesture zoom/pan.

### WebView Performance Controls (0.7.3)
Three overall policies (High Performance / Adaptive / Power Saver). WebView Health Steward with entity-stream scheduling, chunked card rendering, dormant quieting, and entity filtering. Floating browser memory and refresh load controls.

### Screen Gesture Sensor (0.7.0)
Diagnostic gesture sensor reports to Home Assistant. Fingers & Corners, Digits (0-9), and Geometry (triangle, heart, wave, etc.) detected on-device. No gesture performs an action by itself — automations decide the meaning.

### Settings Search (0.7.3)
Global search across categories, pages, and individual controls. Navigate directly to the correct level.

### Tablet Split Settings (0.7.2)
Landscape tablets gain true split layout: directory on left, page on right. Expanded info uses full pane.

### French Interface (0.7.3)
Complete French translation including all 0.7.3 pages. Also supports German, Portuguese, Russian, Vietnamese, and Chinese.

### Setup Wizard (0.7.1)
Five-step guide for new installations: permissions, starting Ava, HA setup, voice pipeline, completion. Existing installs skip automatically.

### Advanced Diagnostics (0.7.0)
Music Playing, Last Used App, Bluetooth, and Network Type sensors. Sync once when leaving page to keep low-power panels responsive.

---

## Permissions

**Required:** Microphone - To hear you speak. Overlay - To show screensaver, notifications, weather, etc. Foreground Service - To keep service running.

**Optional:** Camera - For photos and video. Bluetooth - To detect if you're home. Location - Required for Bluetooth scanning. System Settings - Screen brightness control.

**Root (Recommended):** Better background protection, boot scripts, screen control, reboot. Works without Root, but more stable with it.

---

## Stability

Optimized for 24/7 operation: battery optimization exemption, WiFi wake lock, auto-reconnect, boot auto-start, auto-recovery when killed.

---

## Use Cases

- Wall tablet as whole-home control center + Bluetooth relay
- Bedside voice alarm + bedroom Bluetooth coverage
- Kitchen timer tablet + kitchen sensor range extension
- Doorway video doorbell + entrance presence detection

---

## Pricing

**Completely free**, no charges.

Donations welcome but entirely voluntary, all features available.

---

## Privacy

**No data collection.** Voice recognition processed locally only. No tracking, no account required. Camera/microphone data only sent to your own Home Assistant. I personally guarantee no malicious behavior.

---

## FAQ

### Ava can't hear me

**Check these:** 1. Is microphone permission granted? Go to phone Settings → Apps → Ava → Permissions → Microphone → Allow. 2. Is wake word set correctly? Settings → Voice Satellite → Wake Words. 3. Is the device muted? Check volume buttons.

### Can't connect to Home Assistant

**Check these:** 1. Are device and Home Assistant on the same WiFi? 2. Is Home Assistant address correct? 3. Is ESPHome integration enabled in Home Assistant?

### Screensaver not showing

**Check these:** 1. Is screensaver enabled? Settings → Screensaver → Enable. 2. Is overlay permission granted? Go to phone Settings → Apps → Ava → Permissions → Overlay → Allow. 3. Is timeout set? Settings → Screensaver → Timeout.


---

## Tips

### 1. Choose a Good Wake Word

Choose a word that's easy to say but you don't often say in normal conversation. This way Ava won't trigger by accident.

### 2. Protect the Screen

If the device screen is on for long periods: Lower screen brightness. Use screensaver (screensaver moves content to prevent burn-in).

### 3. Keep Network Stable

Good WiFi signal means better voice recognition accuracy.

### 4. Update Regularly

Check for app updates. New versions have more features and fixes. Ava has built-in auto-update.

---

## Download

Latest APK: **https://github.com/knoop7/Ava/releases**. Built-in auto-update feature.

---

## Get Help

Having problems? Get help here: **GitHub Issues**: https://github.com/knoop7/Ava/issues. **Questions or suggestions? Open an issue on GitHub!** I'll continue fixing bugs and adding features.

---

## Building from Source

### Prerequisites
- **JDK 17** or higher
- **Android Studio** Hedgehog (2023.1.1) or higher
- **Android SDK** with API level 34
- **NDK** (for native audio processing)

### macOS
```bash
brew install openjdk@17
git clone https://github.com/knoop7/Ava.git
cd Ava
./gradlew assembleDebug    # Debug APK
./gradlew assembleRelease  # Release APK
```
APK location: `app/build/outputs/apk/debug/app-debug.apk` or `app/build/outputs/apk/release/app-release-unsigned.apk`

### Windows
```powershell
# Install JDK from https://adoptium.net/, add JAVA_HOME
git clone https://github.com/knoop7/Ava.git
cd Ava
.\gradlew.bat assembleDebug    # Debug APK
.\gradlew.bat assembleRelease  # Release APK
```
APK location: `app\build\outputs\apk\debug\app-debug.apk` or `app\build\outputs\apk\release\app-release-unsigned.apk`

### Linux
```bash
sudo apt install openjdk-17-jdk  # Debian/Ubuntu
# or sudo dnf install java-17-openjdk-devel  # Fedora
git clone https://github.com/knoop7/Ava.git
cd Ava
./gradlew assembleDebug    # Debug APK
./gradlew assembleRelease  # Release APK
```

### Android Studio
1. Open Android Studio → File → Open → Select Ava folder. 2. Wait for Gradle sync. 3. Build → Build Bundle(s) / APK(s) → Build APK(s).

### Signing Release APK
1. Create keystore: `keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-alias`
2. Create `keystore.properties` in project root:
```properties
storePassword=your_store_password
keyPassword=your_key_password
keyAlias=my-alias
storeFile=../my-release-key.jks
```
3. Build: `./gradlew assembleRelease`

### Troubleshooting
- **Gradle sync failed**: Check internet, delete `.gradle`, File → Invalidate Caches → Restart
- **NDK not found**: Android Studio → SDK Manager → SDK Tools → NDK (Side by side) → Install
- **Out of memory**: Add to `gradle.properties`: `org.gradle.jvmargs=-Xmx4g`

---

## Credits

- Original Project: [brownard/Ava](https://github.com/brownard/Ava)
- ESPHome: https://esphome.io/
- Home Assistant: https://www.home-assistant.io/

---

*Last Updated: 2026-08-25 · Current release: 0.7.3 (CONFIDENCE)*
