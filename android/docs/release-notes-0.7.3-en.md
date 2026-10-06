<img width="480" height="200" alt="Ava Pro 0 7 3 Banner" src="https://github.com/user-attachments/assets/08499d94-1a18-4ab2-aaf8-055aaafb742d" />


# Ava 0.7.3 - BOUNCE

multi-device wake arbitration, ESPHome Noise encryption, an ADB control gate, graded WebView performance scheduling, Settings search, a complete French interface, a screen-off CPU keep-alive, a model-free "Stop" barge-in detector, Home Assistant WebSocket direct connection, a floating app window, a Camera Vision mod, and Android 14+ mod loading repair, while raising the built-in ESPHome baseline to **2026.10.1**.

## Post-release patch

Fixed after 0.7.3 shipped, included in the current build:

- @wsardisson(https://github.com/knoop7/Ava/issues/200): Onboarding "Add this device" now shows the actual ESPHome port the service binds, not a hardcoded 6053.
- @wsardisson(https://github.com/knoop7/Ava/issues/201): ESPHome node name no longer gains a `_2` suffix on every service restart — stale NSD registration is cleared before re-announcing, so `voice_action` services stop accumulating in Home Assistant.
- @wsardisson(https://github.com/knoop7/Ava/issues/202): Intercom inbound path no longer wedges after service restarts and permission changes — incoming rings and accepts are no longer silently dropped until a full reboot.
- @wsardisson(https://github.com/knoop7/Ava/issues/203): Call state machine rejects mismatched accept/hang-up against stale session IDs, preventing peers from desyncing after rapid hang_up/start churn. Incoming auto-answered calls now dismiss the screensaver and surface the call UI.

Additional fixes in this patch:

- Stop-word model selection: invalid or stale model IDs fall back to the built-in DSP detector instead of leaving stop detection broken.
- Wake word no longer fires during TTS playback; cursor wake-up timing window tightened.
- Floating subtitle overlay no longer leaks the player surface on dismiss.
- Toast rendering on irregular DPI screens no longer clips or offsets.
- Bluetooth presence RSSI threshold no longer over-triggers on marginal signal fluctuation.
- Software Update release-notes fingerprint stable across same-day fetches.
- Floating window rotate/resize geometry persisted and restored across orientation changes.
- Chorus Wake arbiter no longer drops a yield under rapid multi-device wake collision.
- Screen brightness fallback on non-Root devices no longer fights the active window overlay.
- HA entity state sync replays a full snapshot when watched entities change, preventing stale values after reconnect.
- **Settings Style** page adds a manual **Display scale** slider (75%–200%, 5% steps). The scale wraps the whole UI consistently — density, dp dimensions, and every layout breakpoint move together, so a misconfigured panel that reports too-large dp widths no longer leaves every sp/dp rendering physically tiny. 100% short-circuits to the untouched context, so devices that never touch the slider keep today's rendering bit for bit. When the reported configuration is outside the design envelope (common on white-label tablets), Ava offers the slider once as a nudge. The same page also toggles landscape split view and the main-list bottom pull-handle. Path: **Ava Settings -> Advanced -> Settings style**
- **Wake continuity**: ok_nabu and other wake words can fire in quick succession — one sentence no longer drops the session. In continuous conversation mode, after TTS finishes a continue chime plays and the satellite re-enters listening with the same HA conversation ID, no wake word needed. Wake pre-roll capture buffers mic frames from the moment the wake fires, through the earcon and arbiter chain — speech spoken over the chime is spliced to the queue head when the uplink opens, so HA's first frame is your voice, not the chime tail. TTS echo self-trigger protection: when the assistant's response text contains the wake phrase, wake detection is held for the estimated TTS duration so the assistant cannot wake itself; wake and stop channels are separated, so a response mentioning the stop phrase does not lock wake, and a response mentioning the wake phrase does not lock stop.

## Performance: giving the CPU back to the device

0.7.3 is built around performance. The three most CPU-hungry pipelines — wake-word detection, stop-word recognition, and WebView rendering — all went on a diet at the same time.

The **wake-word engine** continues on its C++ native streaming architecture, cutting compute by roughly **86%** versus the old approach. A 96-dimensional acoustic embedding is shared across all wake words, so one inference pass serves multiple keywords; per 40 ms scheduling tick, inference cost stays well under the real-time budget. 0.7.3 further tightens scheduling after silence — once quiet is confirmed, expensive inference pauses within 150–250 ms, dropping idle-frame CPU usage to near zero.

**Stop-word recognition** discards the machine-learning model entirely — zero TFLite, zero ONNX, zero inference cost, zero model loading, zero GPU/NPU usage, zero extra memory. The new native DSP engine cuts stop-word CPU usage by nearly **100%** versus the old model-based approach: the old path ran a neural-network inference every frame, the new path only does lightweight signal processing on the few frames where sound is actually present — idle-frame cost is zero. Accuracy is higher, not lower — all 27 behavioral tests pass, covering child, female, male, and deep/creaky voices, gain invariance holds from 0.25× to 8× clipping, and a quiet room produces zero false triggers where the old model could not.

**WebView rendering** introduces three performance tiers and layered scheduling. Off-screen cards pause layout and paint, subscriptions step down when covered by the screensaver, and CSS animations plus audio/video decoding pause when the browser is hidden — each layer tunable on its own. Select `browser_power_mode` in Home Assistant to swap gears in real time — no restart.

Together, these three mean the same tablet runs wake detection, intercom, and a dashboard simultaneously at lower CPU load — quieter fans, less heat, longer battery. Bounce light, and you bounce high.

## Floating app window: Ava Pro's own floating-window technology

<img width="1216" height="638" alt="Mac_2026-08-26 17 15 07" src="https://github.com/user-attachments/assets/4c127f76-f055-430d-9296-fd17fbe21a65" />


A wall-mounted tablet is a Home Assistant dashboard, but it is also an Android device — checking a delivery, replying to a message, opening a calculator, watching a camera, scanning a QR code should not bury the dashboard. 0.7.3 lets selected apps open in a draggable, resizable floating window instead — the dashboard keeps refreshing underneath, the window floats on top, and neither gets in the other's way.

This is not Android's native freeform multi-window. Native freeform is gated by system version, vendor customization, and desktop-mode toggles — most tablets simply do not support it or ship with a broken implementation. Ava Pro's own floating-window technology: the target app runs on an independent virtual display created by Ava, its video is hardware-decoded H.264 streamed straight onto a floating TextureView, and touch events are forwarded back verbatim over the control channel — to the target app it is just a normal screen, to the user it is a real window floating over the dashboard. Drag the title bar to move, drag the bottom-right corner to resize, and the window's position and size are remembered for next time. One window at a time, globally unique.

A few scenarios: someone rings the doorbell, a camera window floats up over the dashboard to see who it is without switching away; a recipe app floats beside the temperature card while you cook; a calculator floats for homework while the dashboard's light controls stay untouched; scanning a QR code, checking a tracking number, replying to a message — all without leaving the desktop.

Requires Shizuku (ADB mode) or Root, and Android 12L or newer. When the requirements are not met, it falls back to fullscreen automatically, never blocking. In the Minimal Launcher app picker, tag an app with the window icon to enable it.

Path: **Ava Settings -> Service features -> Minimal Launcher -> App picker**

## Camera Vision: one camera, three senses

0.7.3 adds **Camera Vision** to the Mod Store — a mod that turns the device camera into a Home Assistant sensing hub.

Once installed, the camera becomes a live camera entity in Home Assistant with selectable front/back lens, frame rate, resolution, and JPEG quality. On top of that, it runs three on-device recognition modes simultaneously, all on by default, each independently toggleable:

- **Face detection**: BlazeFace Full Range Sparse, detecting faces up to 5 m with XNNPACK acceleration. Exposes "person detected" and "face count" sensors.
- **Gesture recognition**: BlazePalm + 21-point hand landmark, recognizing open palm and finger count. Exposes "open palm" and "gesture" sensors.
- **QR scanning**: ZXing decode, automatically parsing Home Assistant tag URLs and reporting tag IDs. Exposes "last QR", "HA tag ID", and "scan count" sensors.

The most practical piece is **Screensaver Person Wake**: it hooks face detection into Ava's screensaver — you walk up, the screensaver dismisses; you stay, it does not come back; you walk away, the idle countdown resumes. Far more reliable than Ava's built-in brightness-difference probe. Detection runs on a separate thread from capture, so the live preview stays smooth.

Camera Vision is mutually exclusive with Camera Stream Mod: when enabled it owns the camera, and Ava's built-in remote camera steps aside so the two pipelines never fight over Camera2.

0.7.3 also fixes JAR-based mod loading on Android 14+ — the system refuses writable DEX/JAR files, so JARs are now marked read-only immediately after writing, checked again before loading, and existing installations are repaired automatically. Thanks to @scenix222(https://github.com/knoop7/Ava/issues/197) for fully diagnosing the cause.

Up. Suspended. Caught. Bounce again.

At the highest point, what you fear is not the height — it is that no one catches you. This release makes sure every bounce has a floor beneath it. Only the right device answers; the rest yield in silence. Links can be encrypted, gates can be closed, every layer of performance can be tuned on its own. The screen-off heartbeat is no longer swallowed, a single "Stop" catches the assistant mid-sentence, and state and actions take the shortest path.

Bounce as high as you like; the net holds.

Special thanks to @Kayett(https://github.com/knoop7/Ava/discussions/194). ESPHome encryption, protection for external Android controls, Settings search, an anonymous-statistics switch, and the GitHub download policy requested in Discussion #194 are now part of 0.7.3. Turning Anonymous statistics off immediately pauses Clarity and clears its local cache. Locales other than Chinese and Russian use official GitHub URLs only; Chinese and Russian retain mirrors solely as an availability fallback.

Thank you as well to @scenix222(https://github.com/knoop7/Ava/issues/197) for diagnosing the Android 14+ mod JAR loading failure; to @mclarenlm(https://github.com/knoop7/Ava/issues/196) for reporting the RTSP startup crash on non-root Android 7; to @Zhephyr54(https://github.com/knoop7/Ava/issues/195) for finding the lost kiosk-mode query parameters and proposing French support; and to @lone-baggie(https://github.com/knoop7/Ava/issues/157) for the real microphone recordings. Thank you also to @SeraphicRav and @rdehuyss for responding to 0.7.2.

## Chorus Wake: one phrase, one best answer

Several Ava devices using the same wake phrase no longer race to answer together. After hearing the phrase, each device enters a roughly **180 ms** local-network arbitration that combines wake confidence with real pre-gain sound pressure. The device that heard the clearest, usually nearest signal takes the conversation. Near ties rotate consistently instead of permanently favoring one hotter microphone.

A device that yields does not turn off its microphone or disappear. It remains online and shows a soft dim field with three breathing points in the active wake color. The winner broadcasts session completion three times when speech ends, stops, or times out, and the other devices return smoothly. A 90-second safety bound prevents one lost end packet from leaving them silent forever.

Arbitration packets never contain microphone audio — only device identity, wake identity, time, and a compact score. Real pre-gain sound pressure carries more weight than model confidence, preventing a distant high-sensitivity microphone from repeatedly stealing the conversation. If LAN arbitration cannot operate, the local device falls back to a normal wake. It needs no cloud, MQTT, or extra automation.

Chorus Wake is enabled by default. It can be disabled when there is only one Ava or when avoiding the 180 ms arbitration window matters more.

Path: **Ava Settings -> Voice Configuration -> Chorus wake**

## ESPHome Noise: encrypted voice-satellite connections

<img width="780" height="460" alt="Mac_2026-08-26 17 27 04" src="https://github.com/user-attachments/assets/b72fe21c-a472-40c3-b68a-79c8cbfb5580" />


Ava 0.7.3 raises its built-in ESPHome protocol and firmware baseline to **2026.10.1**. The ESPHome API now supports the standard **Noise NNpsk0** handshake: a device generates a 32-byte Base64 key and uses the same key in its Home Assistant ESPHome configuration. Connections complete X25519 negotiation and pre-shared-key authentication before voice, entities, Bluetooth proxy traffic, or service calls enter a ChaCha20-Poly1305 protected channel.

An empty key preserves the compatible plaintext path. Ava advertises Noise support and requires encryption only after the user explicitly configures a key.

Path: **Ava Settings -> Connection -> ESPHome -> API encryption key**

## External control gains a master gate

ADB and local broadcasts can wake Ava, mute the microphone, start or stop services, apply settings, grant permissions, or reload mods. 0.7.3 adds one independent gate across this inbound control path. Turning off **ADB Control** causes those broadcasts from ADB and other apps to be ignored. Home Assistant microphone and wake entities remain inside their existing boundaries.

Path: **Ava Settings -> Advanced -> Android Intent Executor -> ADB Control**

## WebView performance controls: every layer is optional

The same tablet is a video intercom by day, a bedside card overnight, and a full dashboard on weekends. 0.7.3 lets the browser pick one overall pace first, then fine-tune layer by layer.

- **High performance**: cameras and live pages update continuously, every frame delivered. For video intercom, live monitoring.
- **Adaptive**: full speed while you are looking, easing off once you walk away. The comfortable default for everyday Home Assistant dashboards.
- **Power saver**: for status pages with no live video, enters rest sooner and pauses drawing until a touch restores it. Bedside cards, door screens, text-only status.

All three can be switched directly from Home Assistant — `browser_power_mode` is a select entity. Pick and it takes effect immediately, no Ava restart. One `select_option` in an automation swaps the gear on a schedule.

Path: **Ava Settings -> Display & Browser -> Browser Settings**

## A rebuilt echo-reference bus for voice and calls


Software echo cancellation now uses a reference-counted shared far-end bus. The voice satellite and LAN call path can hold playback reference simultaneously. Pausing one no longer blinds the other's AEC. When several playback sources overlap, reference audio is mixed in floating point and receives headroom only at final readout instead of being clipped by each writer. This repairs nonlinear distortion that an adaptive filter could not learn when speech, music, and calls overlapped.

## One "Stop" silences everything

<img width="500" height="646" alt="Mac_2026-08-26 17 29 56" src="https://github.com/user-attachments/assets/8f89cdc5-648b-4986-b440-6474b1d911f8" />


The alarm is ringing, or the assistant is mid-sentence — you only need to say "Stop." Once is enough.

0.7.3 completely rewrites stop-word recognition **without using any machine-learning model**. The old approach relied on a tiny classifier model that would fire on its own in a quiet room; the new engine discards the model entirely in favor of a judgment method based purely on the signal itself, staying silent in that same quiet room yet catching the exact moment you actually speak.

27 full behavioral tests, all passing: an isolated "Stop" is recognized across volume levels, while long hisses, bare vowels, and running speech are correctly rejected; "Stop" from child, female, male, and deep/creaky voices is caught, and fast clipped and drawn-out "Stooop" deliveries too; real-device regressions (room tails, glottal-pulse dips, slow /t/ closures) were fixed one by one; a user's "Stop" is still caught under TTS residual overlay, while TTS residual alone never false-triggers. Gain invariance holds from 0.25x to 8x clipping.

It tells "Stop" apart from ambient noise, tells your voice apart from the audio Ava itself is playing, and tells a single interruption apart from a string of repeated shouts. Alarms, continuous conversation, and assistant replies are all fully supported and activate automatically. It stays on standby during the wake phase, standing up only once a conversation or alarm is truly underway.

The stop word is now configurable in Voice Configuration: the default is the built-in native DSP "Stop" (no model, no sensitivity slider); you can also swap in any model from the current wake engine as the stop word with its own sensitivity; or turn it off entirely when you do not need it.

Path: **Ava Settings -> Voice Configuration -> Stop word**

## Keep the CPU awake while the screen is off: the link no longer swallowed by deep sleep

On battery-powered tablets, vendor power policy often pushes the CPU into deep sleep once the screen turns off. The keep-alive heartbeat between Ava and Home Assistant, and the Wi-Fi renewal, all need an awake CPU to fire on schedule — but once the CPU sleeps, the renewal itself sleeps too, and the link drops.

0.7.3 adds a **Keep CPU awake when screen is off** switch on the Keep Running page. When enabled, Ava prevents deep sleep while the screen is off, keeping the Home Assistant connection alive; the moment the screen turns on, normal behavior resumes. If the process exits unexpectedly, the system reclaims it automatically.

The switch is on by default and aimed at battery tablets that need to stay resident while the screen is off. Always-plugged devices, or those that do not need keep-alive while dark, can turn it off to save a little power.

Path: **Ava Settings -> Service features -> Keep Running -> Keep CPU awake when screen is off**

## Settings can finally be searched directly

The Settings sidebar gains global search. Ava searches top-level categories, nested pages, and individual controls, then navigates directly to the correct level. Results use the same names shown in Settings, so users no longer need to know whether a feature belongs under Interaction, Device Services, or Advanced. Back navigation in landscape split mode is repaired at the same time.

## Home Assistant WebSocket direct: state, actions, and cameras take the shortest path

0.7.3 completes Home Assistant direct connection. Entity state used to wait for HA to call back into Ava's ESPHome API; **Live state over WebSocket** now streams entity state straight from the Home Assistant WebSocket without waiting for that callback, falling back to the ESPHome channel automatically when unavailable. The moment a newly watched entity is subscribed, the session restarts and replays a full snapshot, so a new entity is populated immediately.

**Call via WebSocket** sends quick-entity toggles, media, and scenes through the WebSocket directly instead of queueing behind the ESPHome connection. **Signed-in camera and covers** let quick-entity cameras and media covers use your Home Assistant login token instead of relying on the ESPHome address to derive an IP.

After Ava's Bluetooth master switch is disabled, Home Assistant may retain remote Bluetooth devices discovered through that proxy. 0.7.3 can remove those remnants manually or automatically, deleting only entries explicitly routed through this Ava. The ESPHome device, Bluetooth integration, and entries shared by other proxies remain untouched.

Path: **Ava Settings -> Connection -> Home Assistant -> Core / More**

## A more complete desktop and screensaver

- The Auto-restart page is upgraded to **Keep Running**, gathering boot auto-start, crash recovery, and screen-off keep-alive into one entry. The ESPHome entry description now mentions the API encryption key.
- Minimal Launcher's app drawer gains alphabetic sections, fast scrolling, and edge fades. Icon-pack loading, widget dragging, and drawer crashes are repaired.
- Simple Clock adds independent **Simple portrait** and **Portrait magazine** layouts. Time, date, AM/PM, and weather redistribute for narrow screens without changing the landscape design.
- Voice Message gains device search. Ava gains a complete French interface, including the pages introduced in this release.
- Screen control on devices without Root or Shizuku gains a pure-black low-brightness overlay fallback. It changes only the active window brightness, never the system brightness.

## Community issues and remaining repairs

<img width="540" height="274" alt="Mac_2026-08-26 17 22 44" src="https://github.com/user-attachments/assets/2e614c31-4a0e-4539-b04f-e8f6893ffca7" />

- @mclarenlm(https://github.com/knoop7/Ava/issues/196): Camera Stream no longer enters its RTSP accept loop before the ServerSocket exists on Android 7, and failed starts release resources correctly.
- @Zhephyr54(https://github.com/knoop7/Ava/issues/195): browser URLs preserve `?kiosk`, `hide_header`, and `hide_sidebar`, merging correctly with Ava's built-in kiosk mode.
- Music Assistant playback groups gain stable identities. Ava rejects commands from foreign Sendspin groups, preventing independent groups from contaminating one another's queue.
- Software Update reuses GitHub release data fetched earlier the same day; Chinese and Russian environments race available mirrors and remember the winning route.
- Startup crashes in Home Assistant HLS playback, Minimal Launcher widgets and dragging, and the service-state card are repaired.

