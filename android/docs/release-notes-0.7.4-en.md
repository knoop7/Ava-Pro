<img width="480" height="200" alt="Ava Pro 0 7 4 Banner" src="https://github.com/user-attachments/assets/c34d38b593e4481c8b38bcfe685e275f" />


# Ava 0.7.4 - Dreaming of 2018

In 2018, these tablets went up on the wall. 1 GB RAM, system WebView frozen at Chromium 69. That year, HA's custom cards were a few lines of simple HTML. Eight years later, these tablets need to run Bubble card, glassmorphism, UIX, WebRTC camera streams.

0.7.4 makes them run.

## Patch

Shipped after 0.7.4, included in the current build.

**Wake strictness can go further.** The engine's own sensitivity has a ceiling — turning it up more does not get more accurate, it just stops hearing you. The Wake word 1 and Wake word 2 sliders are now two zones: the left side is the same sensitivity as before; past the end, two extra confirmation steps begin.

- **Strict+**: after a wake is heard, Ava checks it once more. Fewer false wakes. The Open engine is slightly slower; micro does not add wait.
- **Max**: the strictest check. Far away or spoken softly, the phrase may be missed.

Leave it alone for daily use. If the TV or a hallway passer-by keeps waking the tablet, try Strict+ first. Crossing a step ticks the vibrator; devices with no motor stay quiet. On Android 12 and older tablets the sliders often had no haptic at all — that is now wired through the rest of Settings as well.

Path: **Ava Settings -> Voice Configuration -> Wake word**

- If a settings file is damaged, Ava keeps a backup before restoring factory defaults. Home Assistant login and wake words are no longer wiped with no copy left.
- If the system temporarily revokes “display over other apps”, voice-message receive and similar switches are not permanently turned off. When the permission returns, the features are still on.
- The “ignore battery optimization” system prompt appears at most once per device. A service restart or crash recovery will not ask again. You can still grant it later from onboarding, the permission page, or service settings.
- Repeated background-service restarts no longer keep growing memory.
- After Browser Health Steward is turned off, a dashboard that is using too much memory still refreshes itself at a quiet moment, so older tablets do not flash white in a crash loop.

## System WebView is now the default path

Running today's HA frontend on a 2018 tablet, heavy custom cards render wrong. For three years the answer was always "install the Gecko engine pack": a second process, a second memory footprint, a second set of permissions, two OTAs, 200 MB of RAM stripped from a tablet already scraping by.

The compatibility layer moves into the system WebView. Syntax is lowered on the fly so old engines parse today's JavaScript. Missing APIs are polyfilled. Camera cards stop flashing white. 32-bit memory exhaustion self-heals instead of crash-looping. What gets intercepted is syntax lowering only; on failure, original bytes are returned; card logic is never modified. The Gecko pack can be uninstalled and kept as an escape pod; the same compatibility layer is now in the Gecko pack too. The 200 MB it used to eat now lets dashboard, intercom, and camera run simultaneously without dropping frames, for the first time on old hardware.

The layer reaches down to Chromium 69. Modern border-radius, spacing, icon sizes, and small buttons such as Add integration and Add assistant no longer break their edges on old engines. Popup cards stay inside the viewport instead of overflowing the scrollbar and Ava's sidebar. The HA navigation bar insets slightly so it no longer fights the sidebar. Calendar cards no longer flash white on unrelated entity-registry pushes.

The browser sidebar gains page zoom: tap to expand the slider in place, bidirectional with the Home Assistant scale entity, collapsed when idle. New installs turn sidebar tools on by default (Tampermonkey stays off), so the first open already shows them. The Web console input has an expand control so long scripts can be written fullscreen before send.

Off-screen cards pause layout and paint, WebSocket subscription density steps down under the screensaver, CSS animations and audio/video decoding pause when the browser is hidden. Same 2018 tablet, lower CPU and GPU load than 0.7.3, better touch response.

Path: **Ava Settings -> Display & Browser -> Browser settings**

## Experimental

- **Stop-word engine echo handling rewritten**: the assistant is reading the weather forecast, you say "stop", nothing happens. The machine's own audio is bouncing from the speaker back into the microphone, swamping the human voice. 0.7.4 feeds the playback reference signal into the detector, learns per-band echo-path gains, subtracts the machine contribution from the mic signal, and classifies on the remaining "human evidence." Reliable barge-in during TTS playback. See Wiki.

### Bayesian occupancy fusion

A wall-mounted tablet sits on the wall, screen on. Does it know someone is present?

A PIR sensor triggers when someone moves in the next room. Face detection fails in low light. A single sensor is always caught between false triggers and missed detections.

Six observation sources fuse into a single occupancy probability on-device: face, frame motion, touch, vibration, proximity, voiceprint. Fusion is not a vote. Four layers stack: Bayes gives each signal its own weight of evidence — a face is near-certain, a faint desk vibration only a weak hint; hive inertia keeps a sitting person from being marked gone, while a brief walk-through fades fast; causal hysteresis stops the state flickering around the threshold; the house prior rides the existing LAN identity beacon, so when another room just saw someone, this room believes its first weak signals sooner.

Path: **Ava Settings -> Advanced -> Experimental**

## Fixes

- **Floating app window, all Android versions covered**: Android 7–9 use system freeform mode; Android 10+ prefer scrcpy virtual display mirror, degrading to freeform when the mirror cannot start.
- Gecko engine pack installer improved: when ROM rewrites the APK and breaks SHA comparison, installation is confirmed by version code.
- Fixed update popup reappearing after killing the app.
- Browser sidebar handle matches main app sidebar: 72 dp visual length, 36 dp hit width.
- Screensaver WebView correctly inherits font size and dark-mode sync from the main browser.
- Fixed screen-brightness fallback fighting the active-window overlay on non-root / non-Shizuku devices.
- Home-screen core-service widget now shows connecting, disconnected, and error, not only running and stopped; the music widget shows artist and progress; the sensor widget backfills its history wave from HA, and its corner icon prefers the one stored on the matching quick entity.
- Sendspin paired progress is bidirectional: a finger seek moves both sides, a track change reseats the timeline from the start, and artist/title no longer linger after unpair.
- With the cluster website console on, the HA device page gains a Visit button that opens this device's Fleet console.

## Thanks

Thank you to @ds2000(https://github.com/knoop7/Ava/pull/204) for diagnosing and fixing microphone capture recovery after audio server restart; to everyone whose issues, logs, recordings, and screenshots shaped this release. 2018 hardware deserves 2026 designs.
