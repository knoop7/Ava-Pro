# Ava 0.7.6 · GAMBIT

This release took almost two weeks. The usual rhythm is one week, sometimes half; this one ran long because something genuinely differentiated got done: a problem this ecosystem has never truly solved, from the beginning until now, is cracked.

What problem? Voice assistants have been promised for years, and what ships is always a half-product — what understands can't act, and what can act needs integrations, plugins, and config glued together first. **0.7.6 installs as one app, complete out of the box.** No integration, no plugin, no root, not a line of YAML. Say one sentence, and the lights, the screen, pages, and settings all play out.

One thing from the heart: this was never about shipping a pretty Android app. A beautiful interface, a kiosk-style ornament, a pile of features doing busywork on the user's behalf — none of that is the goal. The goal is function: genuinely solving problems, the ones that truly bothered us and bothered our families.

This year brought a wave of lookalike projects, many vibe-coded in a rush: pretty screenshots, a demo that runs, and they fall apart on real hardware. What filled these two weeks was exhausting work: run every speakable sentence through real devices again and again, split "sent" from "done," sand down every place that can jam. What ships has to be a stable, finished form — something quickly generated is no answer at all.

Every feature is actually callable by the AI: house devices, apps on this device, system settings, the terminal — all grown on the same core. One device index, one execution ledger, one receipt system. "Turn off the light and play some jazz" splits into two ordered steps and only speaks when done. Swap in a cheaper model and nothing is lost. Reads go out in parallel, writes go in order, and the reply starts the moment the receipt lands. Proven on real hardware from Android 5 to 16.

That's why it's called GAMBIT: no take-backs. The spoken sentence is the move, and the pieces are capabilities that actually land — where they land, and whether they landed, every step with a receipt. Every step has to win; "sent" doesn't count. We can gamble on this one because the material is real.

## Good news

A brand-new, rebuilt project is already on the way: **ava-lite**. Ava's voice assistant stripped completely out — none of the extras, just one pure voice service. For anyone who loves pure and wants voice and only voice, stay tuned: release could be very soon. It will debut paired with a powerful project.


## Pick a brain first

A small local model that runs with the network unplugged is enough, a cloud model costing a few cents works, and an existing OpenAI-compatible endpoint plugs right in. For the easy path with good results, grab a free key at build.nvidia.com, set the endpoint to `https://integrate.api.nvidia.com/v1`, pick the OpenAI-compatible option, and it runs in minutes. Five endpoint slots; with the fallback chain on, two transport failures on the current slot move the task — ledger included — to the next configured endpoint, and completed actions never run twice.

Path: **Ava Settings -> Voice configuration -> Text to understanding -> LLM language engine**

## One sentence, the whole formation

The board carries a full chess set. The king is Ava itself — the whole game is played around it. The second crown is the Android core underneath — every piece stands on it, and even the hidden piece is in hand: the terminal, with ADB commands issued by the AI directly. Both crowns stay in hand. The rest of the formation:

| Piece | Covers | Say it and it plays |
| --- | --- | --- |
| **King · this Ava** | the center, everything local | "dim the screen," "screen off," "lock this one," "mute the mic," "stop talking," "set ten minutes," "how long is left," "open Dream Clock," "open the weather overlay," "open Quick Entities," "louder," "mute this song" |
| **Queen · house devices** | the strongest piece, strikes in every direction | "kitchen lights on," "dim the pendant to thirty," "make it warm white," "blinds halfway," "thermostat to seventy-two," "oscillate the fan," "humidifier to forty," "start the Roomba," "start the mower," "turn on I'm home," "run goodnight," "lock the front door," "arm away," "is anyone at the front door," "how warm is the kitchen," "is the garage closed," "what's on the TV," "which lights are in the living room" |
| **Rooks · pages and settings** | straight lines, pushed to the end | "open automations," "go to scripts," "go to scenes," "switch to the dark theme," "show today's energy," "go to backups," "open Zigbee," "what does this page say," "set TTS volume to sixty," "go to touch pad settings," "go to the microphone," "open voiceprint" |
| **Knight · the web** | the L-move, jumps off the board and back | "will it rain in London tomorrow," "open this URL," "open the second one," "scroll down," "tap sign in," "copy that paragraph," "paste it in the box," "refresh," "go back," "continue" |
| **Bishops · music and nearby Avas** | long diagonals across the board | "play some Taylor Swift," "play my commute playlist," "put on a jazz station," "next," "skip to a minute thirty," "back ten seconds," "tell the living room dinner's ready," "call the kitchen," "video the living room," "tell everyone dinner's ready," "which Avas are around" |
| **Pawns · apps, and the piece in hand** | the front rank | "open Calculator," "open Spotify in a floating window," "tap Pause." The pawn is also the piece in hand: recorded touch-pad automations replay with one tap, and every tool set can be switched off individually — manual moves and AI moves play together. |

The rules in one breath: a sentence splits into ordered jobs and a failed first never lets the second fire blind; same-named devices come back as candidates, not guesses; locks and alarms act only on named targets and codes pass verbatim, never invented; a camera frame goes to the model's eyes for the answer; once the browser opens the task isolates and website words can't move house pieces; every page visit returns to where it started; a tap that didn't land is never claimed as done.

Moves that need no pieces still play: "what's the date," "tell me a joke" — a recognised voice gets its name, answered in the language spoken.

And that's far from all — this only lays out part of what can be imagined. Plenty more is waiting to be discovered, with a few easter eggs hidden inside. Use it lots, and feedback is always welcome.

## Every step has a receipt

The model says "it's off." Did the light actually go off? Every call gets a definite status: observed, sent, queued, applied, unknown. The model can only speak to the status — "sent" never becomes "done." Every write goes into the ledger; the same command again returns the earlier record instead of re-running. A call that fails twice is blocked on the third try so the model says what's missing. Same-named devices aren't guessed — candidates come back and it asks. An unknown service is read one section at a time from the guide, not the whole manual stuffed into the prompt. Long jobs get a ten-minute budget; when a slice fills, Ava silently continues from the ledger and checkpoint, up to eight slices — no need to call "continue" again.

## Touch pad: this screen is no longer for touching

A wall-mounted panel carries a pain that's been there for years: it's the most-touched glass in the home. Every tap leaves a fingerprint, and a week in, the whole panel shines with oil. The panel isn't always mounted at a comfortable height, small buttons take a second try, and flour, oil, and water from busy hands all end up on the screen. So we built the touch pad: triple-tap the bottom-left corner and a translucent pad floats up — a finger slides on the pad, the cursor moves on the screen, and the screen is only for looking. Tap to click, long-press to hold, two fingers for back and scroll, three-finger pinch resizes floating app windows. The cursor follows an acceleration curve — slow drags land on the pixel, fast flicks cross to the far corner — with haptics on every move.

Automation solves a second pain: every morning the same page, the same scroll, the same button — repetition that shouldn't be repeated. Press record on the title bar and do it once normally — taps, swipes, back, and the pauses between them all get recorded. On play, Ava returns to the page it was recorded on, then steps through, flashing a mark at each landing point. Five slots, loopable.

Why no root at all: root means flashing, voided warranty, and a security bar that most panels simply can't clear. The whole pad runs on standard Android overlay and accessibility APIs — taps, swipes, pinches, record and replay, nothing missing. Everything it needed to do, it does — and the price paid is nothing. Android 7 or newer; the sidebar has an entry too.

Path: **Ava Settings -> Device services -> Sidebar -> Touch pad**; automation is in the pad's title bar


## Voice configuration, reordered the way a turn happens

A voice turn is really six steps: wake, capture, recognition, understanding, execution, speech. These settings used to sit on different pages, and a Home Assistant pipeline bundled recognition, conversation, and speech together — replacing one often replaced the other two. 0.7.6 rebuilds the whole page in those six steps: the voice channel master switch, wake settings, microphone configuration, smart voiceprint, speech-to-text (STT engine), text-to-understanding, understanding-to-speech (TTS engine), and ambient listening events. Worth stressing: it perfectly reuses the MOD store — the two most important engines install directly from there.

Wake gets a second gate again: the first engine hears, a second engine re-checks the same clip — Strict+ lets it through when unsure so family is never unheard, Extreme refuses when unsure. Self-learning gathers positive and negative samples from the home and only publishes a new head when nine in ten genuine wakes survive. Voiceprint templates are stored per engine, and enrolment and real wakes use the same PCM. And a key fix: misleading STOP triggers — a stray STOP can no longer pause things at will.

The voice button deserves emphasis once more: at night when the house is asleep, quiet matters most, and the same wake word shouted all day wears thin on everyone. Press once, speak, done. Hold-to-talk now runs up to two minutes, automatically working around the native ESPHome limit by renewing the window at about 13 seconds — on release, HA receives one complete sentence of unlimited length, STT combined freely. At night there's an independent quiet-reply level, and auto gain only ever adds, never cuts below the floor that was set.

Path: **Ava Settings -> Voice configuration**

## Stuck? Leave from the shade

When a full-screen overlay covers Back, pull down the system shade: each trapping layer posts its own row, tap Exit and that layer closes, with the Home Assistant display switch following. Works on Android 5 through 16 ([#218](https://github.com/knoop7/Ava/discussions/218), thank you @floco). Dashboards heavy with entities get slow — the WebView Health Manager tightens the subscription to what the current page needs, with pressure and frame rate visible.

## Fixes & Thanks

- [#212](https://github.com/knoop7/Ava/issues/212): Sendspin background crash on Android 5.1.1, moved to the API 21 constructor. Thank you @ly2199 for the logcat.
- [#213](https://github.com/knoop7/Ava/issues/213): Sendspin instability on Music Assistant 2.11, same root as [#210](https://github.com/knoop7/Ava/issues/210). Thank you @pantherale0 for the comparison.
- [#215](https://github.com/knoop7/Ava/issues/215): Bluetooth proxy refused with `GATT 133` in 1 ms; now reconnects per HA's address type and remembers fast failures for 10 minutes. Thank you @oooshk for the low-level logs.
- [#216](https://github.com/knoop7/Ava/issues/216): browser dead after picking Gecko on older devices; the host adds a recovery ladder covering Android 5–16. Thank you @pantherale0 for isolating it on Echo Show 8 and Portal.
- [#217](https://github.com/knoop7/Ava/issues/217): Meta Portal reported Chrome 131 as Chrome 14 and the legacy transpiler wrecked Lovelace. Now reads the User-Agent; the transpiler can be turned off in browser settings. Thank you @sng492 for reporting.
- [#221](https://github.com/knoop7/Ava/issues/221): two NSPanel Pros shared one ESPHome identity and stole each other. First-time identity now mixes in the wlan0 MAC; already-colliding units regenerate in one tap under Device identity. Thank you @solarexpertscr for reporting.
- [#218](https://github.com/knoop7/Ava/discussions/218): Quick Entities fullscreen with no visible back. Thank you @floco for the ThinkSmart View report.

