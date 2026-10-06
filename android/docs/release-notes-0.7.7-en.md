## Patch

This is what landed after 0.7.7 was written.

- **Show after screen on**, off by default. When the screen is turned on, the screensaver comes up and the page underneath stays. A screen turned on by a touch, or by moving close, stays on that page. On the Behavior & wake card, under Proximity wake. Thank you @popy2k14 [#231](https://github.com/knoop7/Ava-Pro/issues/231).
- **Pull to refresh waits.** The finger rests at the top, then pulls down. A flick across the top stays with the page.
- **The voice button snaps to all four edges.** Left, right, top, bottom. On a cold start the caption layers are already hidden behind the screen, so a press no longer flashes first. Turning button mode off is what removes them.
- **An Android 10 Portal no longer silences the microphone.** When freeform is already allowed, a corner sliver that cannot be touched keeps the mic open. Ava does not turn that system switch on. Thank you @engfabiodario [#235](https://github.com/knoop7/Ava-Pro/issues/235).
- **A self-update no longer leaves the app unresponsive.** When this Ava installs an update from Home Assistant, the download and the install wait run in the background, and the screen stays usable. Thank you @popy2k14 [#233](https://github.com/knoop7/Ava-Pro/issues/233).
- **A self-update can install silently.** With Shizuku, root, or device owner, the install finishes without a confirm dialog. Device admin alone still asks once. Thank you @popy2k14 [#232](https://github.com/knoop7/Ava-Pro/issues/232).
- **Spanish.** OK, Cancel, and Close stay in English. Thank you @CarlosTohe [#234](https://github.com/knoop7/Ava-Pro/pull/234).
- **The media player entity no longer loops.** A song plays from its original address, so length and position keep moving. Reconnect this device in Home Assistant for it to take effect.

# Ava 0.7.7

This week didn't invent a new way of playing. It landed the moves that were already being said and still weren't lining up. Time is the clearest of them.

"Time ten seconds tomorrow" is the countdown on the flip clock. When it hits zero, it stops on this screen. "Wake me at seven tomorrow morning," "remind me at two to take my medicine," "an alarm for the day after tomorrow" are clock times, and they can land on any day. A length and a clock face split apart; say both in one sentence and both get done, with no extra question about which is which. "Settings" opens Ava's settings. "Set an alarm" sets an alarm. A house alarm panel still needs that device named. An unnamed clock time stays on this Ava.

When the time comes, an alarm sits on the screen. Tap it to change the time, pick a ringtone, or delete that one. While it rings the icon turns red and breathes; one tap stops it, and stopping clears it. Twenty at most; a full list waits until one is removed. Only the ones that are set show up in Home Assistant, named "task event (alarm)" and "task event (reminder)," with -1 from the second onward. Date and time go both ways: change it in Home Assistant and this side follows; say it here and the entity over there is that same time. Empty slots take no place. They are still there after a reboot.

"How many minutes are left" reads the countdown that is running and speaks the time remaining. The countdown is the flip-clock face, and the reply stops at "done, this much left." Say stop while something is ringing, and the one that is ringing stops — countdown, alarm, and reminder on the same path.

## Ask the house, and ask this device

"How many lights are on in the whole house" counts that type from the index, speaks the ones it can read, and skips a row it can't. The sentence still finishes. "Is the live dashboard on" reads whether the switches on this page are on or off right now, and can flip them. The mod store needs no screenshot: the catalog, what's installed, and what's on or off all come back as text. Say install, turn off, or uninstall, and it does that by name.

The draft in the model's head stays inside the turn, for the model to keep thinking with. What gets spoken is the sentence meant to be heard.

## Smart screensaver exit

With a custom-URL screensaver up, a notification scene was drawn behind it, so it never showed ([#223](https://github.com/knoop7/Ava-Pro/issues/223), thank you @popy2k14 for writing it up on an Echo Show 8). The notification now sits on top of the screensaver. The switch is called Smart Screensaver Exit, and it is on by default. Turn it off, and a notification dismisses the screensaver the same way a tap on the screen does.

In the comment on that issue, @popy2k14 added one line: after a scene interrupts the screensaver, the idle timer has to start over. With Smart Screensaver Exit off, that is what happens — the timeout counts again from the moment the scene appears. While the scene is still up, the screensaver does not come back over it.

Sending the same scene twice did nothing: Home Assistant's dropdown treats the current option as unchanged, so the device never got the second command ([#226](https://github.com/knoop7/Ava-Pro/issues/226), also @popy2k14). After a scene shows, the dropdown returns to `idle`, and the same scene can be chosen again. An older automation that sends `reset` or `...` still hides the scene that is up.

Right after boot, before the network is up, a custom scene list used to come back empty ([#229](https://github.com/knoop7/Ava-Pro/pull/229), thank you @popy2k14). Ava now shows the last copy that loaded, then retries the fetch, and replaces the cache when that succeeds.

The round microphone stays put when a notification scene is already above it, or when an app has already shrunk to a floating window.

Path: **Ava Settings -> Screensaver -> Behavior & wake -> Smart Screensaver Exit**

Custom scenes: **Ava Settings -> Extensions -> Displays & Scenes -> General settings -> Custom Scene Config**

## The Dream Clock can be locked

On a wall, used as a clock, a sideways swipe changes the theme while a timer is still running. After the lock, theme swipes fold away and the timer stays. Full-bleed and flip still switch inside this clock's own settings, and the face crosses over in a short overlap. The four timer buttons are taller, with a haptic on each press. The round pause button is larger, and the clock behind it dims a step.

## Touch pad

Three fingers down collapses it; the same gesture again walks the cycle back open. While an automation is recording, keys typed on the keyboard are recorded too, and playback follows the pace they were recorded at. When the pad shrinks small, slots 1 through 5 are still there — scroll sideways and they show. Deleting one, the trash can travels across.

On the voice card, while "turn off the light and play some jazz" is scrolling on its own, a finger press stops it.

## Pages and layers

The browser's row in the notification shade is the same thing as the sidebar and the display switch in Home Assistant: hidden means hidden. Exit is what closes it. Reload tears it down and brings it back, so it doesn't die halfway stuck. Pull to refresh lives inside the page, the same inset treatment as the direction keys.

Entering settings, the liquid glass eases from blurred to clear. When several floating layers stack, Back stays in the top layer. These layers follow Ava's own orientation. The Dream Clock sits above the browser.

The remote's focus ring goes transparent while the sidebar is open, and shows again when a remote is actually moving through the screen.

## Finished speaking detection

How long a silence has to last before this turn is finished is now a choice. The three steps are the same item as Finished speaking detection on this satellite in Home Assistant, and a change on either side is the same change: Aggressive, 0.25 s; Default, 0.7 s; Relaxed, 1.25 s. When silence reaches the selected length, this recognition ends. Until Home Assistant is connected, the control is not shown.

Path: **Ava Settings -> Voice Config -> Speech-to-text -> STT listening engine -> Finished speaking detection**

On Android 5 and 6, the voice path crashed the moment recognized text arrived: those systems have no `UnicodeScript`. It now judges the character itself, and the voice path can finish on those older devices.

## Fixes & Thanks

- [#223](https://github.com/knoop7/Ava-Pro/issues/223): a custom-URL screensaver covered notification scenes. With Smart Screensaver Exit off, a scene dismisses the screensaver and the idle timer starts over. Thank you @popy2k14.
- [#229](https://github.com/knoop7/Ava-Pro/pull/229): the custom scene list was empty after boot. Ava shows the last cached copy, then retries the fetch. Thank you @popy2k14.
- [#226](https://github.com/knoop7/Ava-Pro/issues/226): the same scene sent twice never appeared. The dropdown returns to `idle` after it shows. Thank you @popy2k14.
- [#228](https://github.com/knoop7/Ava-Pro/issues/228): Echo Show 8 (1st gen) wrote display-off as brightness 1. With the Echo Show mod installed, 0 is screen off and 1 is the dimmest step that still lights. Thank you @popy2k14 for the comparison on LineageOS 18.1.
- [#230](https://github.com/knoop7/Ava-Pro/issues/230): the wake ripple drew a `RuntimeShader` into a software-rendered window and took down the main thread. A software canvas now uses a plain ripple. Thank you @scenix222 for the stack on a Galaxy S21.
- [#219](https://github.com/knoop7/Ava-Pro/issues/219): opening Browser Display on Android 5.1.1 crashed on `getRootWindowInsets`, the same call for System WebView and Gecko. Below API 23 that call is no longer made. Thank you @oriabkov for retesting on a ZX-WA1012T.

The voice-message page keeps its own back control. Dynamic captions use a larger size on small screens, with space between the orb and the words.
