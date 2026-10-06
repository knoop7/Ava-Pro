<img width="480" height="200" alt="Ava Pro 0 7 5 UI Banner" src="https://github.com/user-attachments/assets/38c7cd40-99ea-47e9-8d95-ef4ae29545ff" />



## Patch

This is the patch after 0.7.5 shipped. Thank you @open-forest for [#211](https://github.com/knoop7/Ava/issues/211).

- **Bluetooth claims no longer sit forever.** Simulated slots and LAN UDP stay. A claim expires: this panel keeps it through the 25-second scan rest; a quiet peer is gone after 20 seconds.
- **A stronger panel can take a slot.** Takeover needs about 8 dB more, held 12 seconds, three times. Old `AVA_CLAIM:id:address` packets still work; no RSSI cannot win.
- **Home Assistant unsubscribe does not drop a live claim.** Full-slot recovery looks at real GATT only, so a BTHome occupancy does not freeze the scanner.
- **Android 12 and below get a fake sidebar blur.** It follows the Liquid Glass slider. The plate stays thicker. Overlays stay as thicker material and do not fake a blur.
- **Device Controls uses two tiles.** Restart/exit and crash records sit side by side, blue refresh and grey log.
- **STT on the D-handle sits inner-top, not mid-screen.**
- **Long STT wraps on the mic** (11 characters × 3 lines) instead of being thrown to the tail.
- **The bubble moves out and up.** Opaque plate, kiss the inner D edge, stay on-screen, never flip to the wall.
- **Button and Hybrid no longer leak wake chrome.** Tap or hold does not show the ripple, Esper orb, or edge meters.
- **Button-turn TTS no longer flashes a black floor.** The full-screen caption gradient stays off until the wake orb needs it.
- **Loud TTS no longer trips Stop.** A virtual pad is subtracted from the microphone during playback. Nothing extra is played from the speaker.
- **Sendspin auto-calibrate locks 0 at the speaker jack.** It subtracts the path in front of the DAC. The delay slider is unchanged.
- **Cold start no longer hides overlay Back** when weather binds after Dream Clock.
- **Settings chrome waits 10 seconds after the last touch** before folding.



## Ava 0.7.5 - week

gathers what landed in the same week: a new device can inherit an existing setup, wake detection can adapt to the room, devices without a suitable wake phrase or with only a remote remain usable, and spoken volume can rise with ambient noise.


## New devices inherit setup with a pairing code


Adding another tablet no longer means configuring voice, browser, mods, and widgets from scratch. Two Ava devices on the same LAN transfer directly: the sender shows a six-digit pairing code, the receiver enters it, and the copy starts. The window stays open for five minutes. The exchange is encrypted on the LAN — no cloud, no USB stick.

Interface and services, browser, voice, music, mods, launcher icons, and widget layout can be selected individually. Home Assistant and Music Assistant credentials are excluded by default. MAC address and ESPHome node name never leave the source, so Home Assistant treats the receiver as a new device. Nothing is applied until the receiver confirms. First-run onboarding does not open pairing; Send or Receive is started from Backup & restore.

Path: **Ava Settings -> Backup & restore -> Clone**

## Wake words learn on-device to cut hallway false triggers

Passing the hallway or leaving the television on can wake a wall tablet. This week adds on-device wake learning. Wakes that become a conversation are kept as positive samples; no-text and immediately cancelled wakes count as negative. Samples stay on the device. Recordings are not uploaded.

A newly trained verifier does not replace the factory one immediately. It is published only after cross-validation: at least 90% of genuine wakes retained, false-wake rejection not worse. Until there is enough evidence, the factory verifier remains. Open Wake Word and microWakeWord share this layer. Open's embedding path now runs as on-device C++ instead of ONNX. The settings page shows learning state, with retrain and clear.

Path: **Ava Settings -> Voice configuration -> Wake learning**

## Start talking from a floating button, without a wake word

<img width="882" height="650" alt="Mac_2026-09-05 02 53 49" src="https://github.com/user-attachments/assets/7fb6b09b-a82a-4ee3-a177-a79f32f23d95" />


When a wake phrase is inconvenient, a floating microphone on the left or right edge can start the session. It rests as a D-handle and becomes a disc when pulled out. Three modes:

- **Voice** keeps the wake word and hides the button
- **Button** starts sessions only from the microphone; wake detection, the catalog, and earcons turn off together
- **Hybrid** allows either the wake word or the button

A tap lets VAD finish the utterance; hold-to-talk submits on release. A tap during STT or TTS stops the turn and starts listening again. Playing music ducks while the user speaks. User text appears beside the button; the reply uses a centered three-line subtitle. Devices with only a remote can move through Settings with the D-pad and open the sidebar with the menu key. Touch devices do not boot with a selection box.

Path: **Ava Settings -> Voice configuration -> Wake mode**

## Spoken volume rises automatically with room noise

A weather announcement can be too loud at night and too quiet when the room is busy. [#209](https://github.com/knoop7/Ava/issues/209) asked for automatic gain from ambient noise, without lowering the user's setting.

Idle microphone level estimates room noise and adds on top of the TTS slider. A quiet room keeps the set value. A loud room can add about 55 percentage points, never past full scale. The slider marks quiet, normal, noisy, and loud. The Home Assistant volume entity follows the actual output. Off by default.

Path: **Ava Settings -> Voice configuration -> Voice reply**

## Dream Clock adds a full-bleed analog and a flip face

<img width="1414" height="660" alt="Mac_2026-09-05 02 30 46" src="https://github.com/user-attachments/assets/d4bfc3c2-1a48-450a-ae06-8b1c3823908e" />
<img width="1420" height="656" alt="Mac_2026-09-05 02 30 38" src="https://github.com/user-attachments/assets/949c9e69-6ff5-4bdd-ad93-e86d5b433cde" />


A wall tablet can stay on as a clock without remaining on the original circular analog. Dream Clock now has two families. Mechanical is the existing circular analog, chosen only in Settings. StandBy has a full-bleed analog page and a flip page; swipe up or down on the overlay to switch. Hours, minutes, and optional seconds flip independently. Hiding seconds stretches the hour and minute cards. Swipe left or right through 52 themes — Obsidian, Porcelain, Aurora, and others — not a three-way black, white, and color set. 12-hour time and League Gothic or system fonts are optional.

The flip page can run a local countdown: 5, 10, or 25 minutes. A custom duration appears only after a Home Assistant `timer.*` is bound. Zero can play an alarm; a tap on the clock stops it. `select.dream_clock_flip_style` can be published so a Home Assistant choice applies immediately, and a swipe on the device writes back. An optional switch shows or hides the overlay remotely. Smart AOD fades a dark cover after idle; taps through 85% depth still hit the clock, and a first tap above that only wakes. The settings button can be locked with a long press so it no longer appears.


Path: **Ava Settings -> Screensaver -> Dream Clock**

## Update disclosure leads with this device

When a release is available, the disclosure sheet leads with this device: Update or Later. Other devices wait for the next step, so the sheet does not list the whole house first. The Home Assistant update entity can check again immediately, without waiting for the 12-hour cycle. Later is remembered and does not return on every launch.

Path: **Ava Settings -> Device service -> Software update**

## Music keeps old and new servers in sync


Current Music Assistant uses a Noise handshake; older servers still use plaintext hello. Both paths remain. A failed handshake falls back for three minutes. Cover, artist, and title update after a track change. Lyrics that fail a duration fit are skipped. Clock calibration follows time already leaving the speaker, not a separate delay model. The default DSP recognizes German "Stopp" spoken closer to "schtopp" or "shtop" without a separately trained stop word.

The first 300 ms of TTS playback is guarded so the announcement does not interrupt itself.

## Liquid Glass on the sidebar and overlays

<img width="1412" height="670" alt="Mac_2026-09-05 02 45 17" src="https://github.com/user-attachments/assets/0628d4db-c263-4eac-b2fe-b165f043fd1a" />


Liquid Glass is used on the sidebar, floating windows, notifications, volume, and the bottom dock. Settings list cards stay clear. With glass on, the sidebar keeps vertical inset, sits 10 dp off the screen edge, uses rounded corners, and dissolves at the scroll edges — 30 dp in portrait, 20 dp in landscape. Intensity defaults to 50%, with a two-second preview on the slider. Light mode uses a thin edge; dark mode uses black-grey. Turning it off leaves no blur on Dream Clock or the browser. Opening the sidebar covers the system navigation bar. Floating app-window title bars stay opaque.

The Home Assistant header insets only the inner buttons, so the bar no longer tears a gap. Light and dark switch in-page, without a full reload. On Facebook Portal compact desktops, app icons no longer cover the store. Rooted floating windows no longer create a virtual display as uid 0. New landscape installs default to left index and right detail; portrait stays single-column. Settings, Permission Manager, and Mod Store support remote-control focus navigation.

Path: **Ava Settings -> App style -> Liquid Glass**




## Community

- Android 5.1 crashed on launch: the alarm, onboarding permissions, toast, and camera check called APIs that exist only on Android 6, which became `NoSuchMethodError` as soon as the app reached the foreground. Each call site now branches by API; on 5.x, overlay and battery-optimization grants already given at install are not sent to Android 6 settings pages.
- [#206](https://github.com/knoop7/Ava/issues/206): the Camera Vision mod recognizes EAN/UPC barcodes alongside QR. This is a mod update and does not add a permanent camera load to the main APK. Thank you to @pantherale0 for the request.
- [#208](https://github.com/knoop7/Ava/issues/208): rooted Echo Show devices gain automatic overlay permission. Hangs after long runtime still need a separate log. Thank you to @MichaelaMer for the Echo Show 8 report and reproduction steps.
- [#209](https://github.com/knoop7/Ava/issues/209): TTS volume can rise with ambient noise. This first version covers spoken replies only, not media or alerts. Thank you to @pantherale0 for the request.

Thank you to @pantherale0 and @MichaelaMer for the device details, logs, and retests in the issue tracker.
