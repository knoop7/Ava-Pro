# Ava Android source

This is the Android application source published for Ava Pro. It lives in the `android/` directory of the repository.

What is published is the application: Kotlin, UI resources, the ESPHome protocol definition, and the Kotlin calls into the native audio library. The C++ for echo cancellation, noise suppression, wake-word features, and voiceprint is not in this tree. Signing keys, the local release scripts, the Gecko engine packages, experiment recordings, and decompiled trees are not here either.

The project can be read and compared with the running app. The native library does not build without the C++ sources above.

## Top level

| Path | What it is |
| --- | --- |
| `app/` | The Android application: UI, services, settings, voice scheduling, the browser, and overlays. |
| `esphomeproto/` | The ESPHome native API. `api.proto`, `api_options.proto`, plus the Kotlin for framing, the voice assistant, and the Bluetooth proxy. |
| `microfeatures/` | Kotlin entry points for the native audio library. See the section below. |
| `gradle/` | The version catalog `libs.versions.toml`, and the Gradle wrapper. |
| `build.gradle.kts` | Root project. Declares the Android, Kotlin, and Compose plugins. |
| `settings.gradle.kts` | Three modules: `:app`, `:esphomeproto`, `:microfeatures`. |
| `gradle.properties` | Compiler memory, AndroidX, and parallel builds. No machine JDK path. |
| `gradlew`, `gradlew.bat` | Gradle start scripts. |
| `LICENSE` | Ava Pro material owned by knoop7 is CC BY-NC-ND 4.0. Portions from brownard/Ava stay under Apache 2.0. |
| `PLUGIN-EXCEPTION.md` | Independent mods may be developed and distributed, including commercially, against the published mod interfaces. That does not make the application itself commercial, or allow redistributing a modified application build. |

## `app/`

`app/build.gradle.kts` is the application module. The default build uses the system WebView. The `gecko` flavor is the separate engine package. Release signing reads a local `keystore.properties`. That file and the keystore are not in the repository.

| Path | What it is |
| --- | --- |
| `src/main/java/com/example/ava/` | Application code. Packages are listed below. |
| `src/main/res/` | Strings, themes, and icons. Strings are split by language (`values/`, `values-zh/`, and the other locales). |
| `src/main/assets/` | Bundled wake words, prompt tones, VAD, the Fleet console page, and scripts used by the browser and screensaver. |
| `src/main/aidl/` | `IShellService`, the binder used when a system command channel is required. |
| `src/gecko/` | Engine entry and browser factory compiled only into the `gecko` flavor. |
| `libs/` | The reduced ONNX Runtime the app links, `onnxruntime-reduced.aar`. |

### Packages under `com.example.ava`

| Package | What it is |
| --- | --- |
| `esphome` | The ESPHome device connection to Home Assistant, and entities such as lights, sensors, and the media player. |
| `esphome/voicesatellite` | The voice satellite: state machine, microphone input, TTS playback, screen and sensors, Bluetooth and occupancy, and the entities registered with Home Assistant. |
| `services` | Long-running services: voice satellite, overlays, screensaver, dream clock, weather, quick entities, vinyl cover, voice messages, browser, and freeform windows. |
| `ui` | Compose UI: home, settings, glass, and controls drawn on overlays. |
| `settings` | Stored settings for voice, the player, the sidebar, experiments, and the screensaver. |
| `homeassistant` | Direct Home Assistant WebSocket, discovery, and pipelines. |
| `audio` | Microphone capture, gain, and the scheduling of software echo cancellation and noise suppression. The algorithms themselves are not in this package. |
| `players` | Media and TTS playback, including the Home Assistant ffmpeg proxy. |
| `openwakeword`, `microwakeword`, `wakewordlibrary`, `vswakeword`, `wakelearn` | Loading, scoring, and learning wake-word engines. |
| `voiceprint` | Voiceprint enrollment and matching on the application side. |
| `stopword` | Stop word. |
| `detection` | Household sound events, plus face and gender detection. |
| `localllm` | On-device intent, and Remote AI turns, tools, and settings. |
| `voice` | LAN intercom: messages, calls, and video. |
| `sendspin` | The Music Assistant / Sendspin client, and its Noise handshake. |
| `server` | ChaCha20-Poly1305 for encrypted ESPHome connections. |
| `bluetooth` | Bluetooth proxy and presence on the application side. |
| `camera` | Camera capture and video. |
| `webcompat` | Browser engine, page compatibility, and the Gecko peer. |
| `mods` | Mod loading, entities, the voice pipeline, and the host side of the conversation engine. |
| `notifications` | Notification scenes and leaving fullscreen overlays. |
| `fleet` | LAN device management and its settings catalog. |
| `clock` | Alarm storage, scheduling, and sensors. |
| `touchpad` | The touch pad and the AI click cursor. |
| `appwindow` | Freeform window keep-alive. |
| `widgets` | Home-screen widgets. |
| `lyrics` | Lyrics. |
| `weather` | Weather data. |
| `sensor` | Device sensors such as light, magnetic field, and proximity. |
| `backup` | Backup and restore. |
| `update` | App update and silent install. |
| `massapi` | The Music Assistant WebSocket API. |
| `receivers` | Boot and external control broadcasts. |
| `permissions`, `wakelocks`, `nsd`, `shizuku`, `platform`, `crash`, `utils`, `net`, `multidevice` | Permissions, wake locks, mDNS, Shizuku, device differences, crash records, shared helpers, network, and multiple devices. |

## `microfeatures/`

This module keeps the Kotlin call layer only. `src/main/cpp/` is not in the repository.

| File | What it calls |
| --- | --- |
| `EchoCanceller.kt`, `EchoCanceller3.kt` | Echo cancellation |
| `NoiseSuppressor.kt` | Noise suppression |
| `MicroFrontend.kt` | Audio features used for wake words |
| `OpenWakeWordEngine.kt` | openWakeWord |
| `StopWordEngine.kt` | Stop word |
| `VoicePrintNative.kt` | Voiceprint |

`build.gradle.kts` still points at `src/main/cpp/CMakeLists.txt`. That CMake file and the C++ behind it are not in this directory.
