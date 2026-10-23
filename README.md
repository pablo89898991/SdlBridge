# SDL3BridgeDroidDeck

Android app that uses SDL3 to read game controllers over USB (DualSense, DualSense Edge, DualShock 4,
Xbox, Switch Pro, ... anything SDL3 supports) and streams them to DroidDeck through the same bound
service protocol as the first bridge. The DroidDeck patch (`DualSenseBridge.kt`) works unchanged.

## Test screen
Opening the app shows a live controller diagram. Orange = held now, green = worked at least once,
grey = not tested yet. A summary line counts buttons and shows sticks / triggers / gyro / accel / touchpad.
Buttons: Rumble (0.7 s), Light (cycles lightbar colours), Reset (clear green marks), Update.

## Settings (on the bridge screen)
- **Touchpad layout**: Split (decoupled: left half = left trackpad, right half = right trackpad, click goes to the
  side you touch), Single (whole pad = right trackpad), or Off. The diagram shows the split.
- **Extra buttons**: L4 / R4 / L5 / R5 can also be pressed by Mute, touchpad click, Create, Options, L3 or R3. The
  real Edge back / Fn buttons always count. A button assigned this way stops acting as itself.
- **Light**: red / green / blue, brightness, player LEDs. Saved, applied on connect. A client that sets the
  lightbar (DroidDeck) overrides it until you change it here again.

## Updates
- Every push builds an APK and publishes it as a GitHub Release (`build-N`, notes contain `sdl=<version>`).
- A weekly scheduled run checks the latest stable SDL3 release and publishes a new build only when it changed.
- The app checks the repo's latest release when it opens (the repo must be PUBLIC); tapping the update
  button opens the APK download. Android then asks you to install it over the old version.
- All builds use the fixed test key `app/bridge-debug.keystore`, so updates install over each other.
  The key is public in the repo: fine for a personal test app, do not reuse it for anything else.
- GitHub pauses scheduled workflows after 60 days without repo activity; run the workflow once to wake it.

## Build
Push this folder to GitHub and run the "Build SDL3 bridge APK" workflow (or just push). Download the APK
from the run's Artifacts, or from the repo's Releases.

## Use
1. Uninstall any older bridge app (different signing key; only needed once).
2. Install this APK, open it once, plug the controller in with USB-C, allow USB access.
3. Switch to DroidDeck. The bridge keeps running in the background with a notification.

## Known limits
- USB only for full features; classic Bluetooth DualSense raw features are not available to Android apps.
- Adaptive trigger effects are not implemented; rumble and lightbar are.
- Controllers that reach the phone only through Android's input (Bluetooth, read by Android rather than SDL3's USB
  driver) can be read only while the bridge screen is in front; USB controllers work in the background.
- Untested: written without being able to build or run it. Report the first error.

## Forced SDL3 USB driver

Settings -> "SDL3 driver":
- **Forced** (default): the bridge ignores Android's basic input and only uses controllers served by SDL3's own USB (HIDAPI) driver. That is the only mode with Edge back buttons/Fn, touchpad data, lightbar and gyro from the controller itself. It needs a USB-C cable; Bluetooth cannot work.
- **Auto**: also accepts Android basic input.
- **Force SDL3 USB driver** button: asks Android for USB access to every controller-like USB device (using SDL's own permission action), then restarts SDL's gamepad enumeration. Pick "always" in the dialog.

The status line says "Forced SDL3 USB mode: waiting..." until SDL's driver has the controller.

## Gyro speed

"Gyro (sent to DroidDeck)": speed slider (100% = 1:1, up to 800%) and per-axis invert. Keep `GYRO_GAIN = 1f` in DroidDeck's `DualSenseBridge.kt` so the two don't multiply.

Axis remap: each DroidDeck gyro axis (X/Y/Z) can be fed from any controller axis, with optional invert. The accelerometer follows by default so gravity stays consistent with rotation.

Touchpad orientation: invert X, invert Y and swap X/Y, applied to both trackpads (swap first, then invert).

## Multiple controllers (up to 4)

The bridge opens every SDL3-supported controller it sees (up to 4) and sends each as its own player slot.
- Player 1 keeps all features: touchpad, Edge back buttons/Fn, lightbar and player LEDs from the settings screen, gyro.
- Players 2-4 are plain gamepads in DroidDeck: buttons, sticks, triggers, gyro, rumble, and a fixed lightbar colour (red, green, orange).
- Wire protocol additions: frames carry `slot` (0..3), `vid` and `pid`; message 12 (`slot`) means that controller was unplugged; a rumble request may carry `slot`.
- The Android copy of a controller that SDL already reads through its USB driver is never opened as an extra player.
- If player 1 is unplugged the next controller moves up to player 1.
- DroidDeck needs `padbridge-slots.patch` (PadBridge slot API) and the new `DualSenseBridge.kt`.

## One-command DroidDeck integration (droiddeck/patch-droiddeck.sh)

Instead of copying files and applying patches by hand, run the bundled patcher against your DroidDeck
source tree in Termux:

    bash ~/SdlBridge/droiddeck/patch-droiddeck.sh ~/DroidDeck

It installs DualSenseBridge.kt, adds the PadBridge slot API + duplicate-input gates, the manifest
permission/queries, and the SessionActivity dispatch gate. It is idempotent (safe to re-run) and prints
TODO lines for the two things that must be placed by hand once: the `attach()`/`detach()` calls in your
session start/stop and the `isActive()` guard in PadMotion.kt.

Note: this edits DroidDeck's SOURCE before you build it. An installed app cannot patch another installed
APK on a non-rooted phone (a re-signed APK no longer matches DroidDeck's signature), so the integration
has to be compiled into DroidDeck.

## Auto-updating DroidDeck (droiddeck/sync-upstream.yml)

For a DroidDeck APK that always tracks latest upstream with the bridge baked in, see
droiddeck/SETUP-autoupdate.md. A scheduled workflow re-applies droiddeck/patch-droiddeck.sh to fresh
upstream and rebuilds -- overwrite, not merge, so no conflicts.

## Gyro deadzone & calibration

In "Gyro (sent to DroidDeck)":
- **Gyro deadzone (deg/s)**: motion smaller than this is treated as zero, so the view doesn't drift while you hold still. Applied in real deg/s, before the speed %.
- **Calibrate (hold still)**: lay the controller flat and still, then tap. It samples ~0.7s of gyro, measures the resting drift per axis, and subtracts it from then on. Shown as the bias line; **Reset calibration** clears it.

Both are bridge-side only; no DroidDeck change needed.

## Universal controller test layout

The test screen adapts to whatever SDL3 reports for the connected controller: it draws only the buttons,
sticks, triggers, touchpad(s) and paddles that actually exist, and labels the face/menu buttons for the
controller kind (PlayStation ✕●■▲, Xbox A/B/X/Y, Nintendo B/A/Y/X, Steam, generic). Controllers with two
trackpads (Steam Controller 2015 and 2026) show both pads with separate finger dots. Driven by a
capability report from the native layer, so new SDL3-supported controllers work without code changes.

## Floating bubble (chat-head)

"Floating bubble" -> "Show floating bubble over other apps": a draggable chat-head over any app.
- **Tap**: turns the SDL3 driver on (forced USB) or off (releases the controller to Android). Green = on, red = off.
- **Hold 3s**: opens the bridge app.
- **Double tap**: Steam Native Hook-up on/off. The bubble turns **blue** ("HOOK") while it is on; double tap
  again to go back to your previous "Present controller as" choice.
- **Drag to the bottom**: a remove zone appears (like Messenger); drop the bubble on it to dismiss.
- Position is remembered. Needs the "Draw over other apps" permission (grant it, then toggle on).


## USB audio

"USB audio": shows any USB/headset audio output Android sees (e.g. the controller's audio device when
plugged in via USB-C) and a "Switch audio output" button that opens Android's media-output picker.
Android controls routing, so a normal app can't force game audio to a device directly; this is the
supported way to switch between the controller and the phone.

## Present controller as (native Sony controller)

"Controller mode" -> "Present controller as": Steam Deck (default) / DualSense / DualShock 4. The DualSense
and DS4 options use DroidDeck's native hidraw emulation (FakeInputWriter.setPadType), so Steam sees a REAL
Sony controller with gyro, touchpad and back buttons through Steam Input, instead of the Steam Deck pad.
Give Steam a few seconds (or restart the session) after switching. (Note: this re-presents the primary
controller as a DualSense; it is not a second simultaneous controller.)

Also available: DualSense Edge, Steam Controller 2026 (28de:1302) and Steam Controller 2015 (28de:1102).
For the 2015 model the touchpad is always split into its two trackpads, the D-pad clicks the left trackpad's
edges, the right stick is unused (the real controller has none) and back buttons / Fn map to the two grips.

## Any SDL3-supported controller

"Controller source" defaults to **Auto**: every controller SDL3 supports is used. Plugged in by USB, SDL3's
own driver reads it with all its extras (gyro, touchpads, back paddles, misc buttons) -- PlayStation, Xbox,
Switch / Switch 2, Steam Controller 2015, 8BitDo, Flydigi, Hori, PowerA and the rest of SDL3's list. Over
Bluetooth (or any controller SDL3's USB driver can't open) it is read through Android's input instead. An
Android duplicate of a controller SDL3 already drives over USB is ignored, so nothing is doubled. A generic
controller with no SDL mapping gets a default layout. "Forced" keeps the old USB-only behaviour.

## Steam Native Hook-up ("Present controller as" -> Steam Native Hook-up)

The bridge hands Steam the REAL controller instead of re-presenting it. SDL3 identifies which USB interface
is a game controller (anything SDL3's USB driver supports), lets go of it, and opens it raw; the bridge then
sends DroidDeck the controller's real identity (vendor/product id, release, name, serial, interface, HID report
descriptor read from the device) and every HID report, both ways. DroidDeck shows it as /dev/hidraw24..27
with a kernel-like sysfs description, so Steam's own driver for that controller runs -- exactly like plugging
it into a PC: a Flydigi Vader 4 Pro with its 4 back buttons, C/Z and gyro; a DualSense / Edge with touchpad,
mute, paddles and Fn; a Switch Pro; an Xbox controller; and so on. Steam handles calibration, lights,
haptics and gyro itself. Game rumble is handed to Steam through its virtual pad's force feedback, so Steam
rumbles the controller with its own driver too. Up to 4 controllers at once, hot-plug supported.

Limits: controllers that only reach Android through Bluetooth can't be handed over raw (Android doesn't give
apps raw HID access to them); they keep working as DroidDeck's Steam Deck pad. Bridge-side settings (remaps,
gyro tuning, lightbar) don't apply to hooked-up controllers -- configure them in Steam, like on a PC.

## Steam Controller (2026) over USB cable or wireless puck

Plug a Steam Controller (2026) in with a cable (28de:1302), or plug in its wireless puck (28de:1304/1305), and
allow USB access. The bridge does not decode it: every HID report goes to DroidDeck unchanged and everything
Steam sends back (haptics, settings, feature reads) goes to the controller, so Steam drives it natively --
capacitive stick touch, grip sense, trackpad pressure, gyro and haptics -- without Bluetooth latency. In
DroidDeck it appears as DroidDeck's own Steam Controller (the same node its Bluetooth support uses), next to
the regular pad; it is never a bridge player and the "Present controller as" setting does not apply to it.
One Steam Controller is passed through at a time.

**With Steam Native Hook-up on**, the Steam Controller (2026) is handed to Steam as itself instead -- the real
28de:1302 (cable) or 28de:1304/1305 (puck) interface with its real report descriptor, through the hook-up fast
path, exactly like on a PC. Android doesn't say which interface is the controller, so the bridge watches the
candidates and hands over only the one that sends controller data (or where the puck reports a connected
controller): one hook-up slot per actual controller, and the boot keyboard/mouse (lizard mode) interfaces are
left to Android. Switching hook-up off gives it back to the passthrough above (only after hook-up has released
it, so the two never hold the same interface).


## Performance (gyro / trackpad latency)

- **Poll rate** ("Controller mode" section): 250 / 500 / 1000 Hz (default 500). Higher = lower gyro/trackpad
  latency for more CPU/battery; no accuracy is lost either way.
- The DroidDeck client handles bridge frames on a dedicated thread (not the UI thread), which removes jitter.
- Stick throttle is 4 ms and trackpad change-detection is 11-bit for smoother motion.

## Per-player settings (P1–P4 tabs)

The "Player settings" section has four tabs (P1–P4). Gyro (speed, invert, axis remap, deadzone, calibration),
touchpad (layout, invert, swap), back-button remap, and lightbar (RGB, brightness, player LED) are stored
independently per player, applied by controller slot. Global settings (SDL3 driver, poll rate, bubble, USB
audio, present-as) stay outside the tabs. Each controller's lightbar is now driven per-slot natively.

## Button remap (any button -> grip or trackpad)

Per player, each of 8 targets — L4, R4, L5, R5, and left/right trackpad click and touch — can be driven by
ANY controller button, including extra buttons SDL exposes as MISC2..6 (e.g. a Flydigi Vader's C and Z).
The bridge now carries those extra buttons in a second mask. Example: 4 back buttons as L4/R4/L5/R5, and
C/Z as left/right trackpad click — giving Steam-Input parity across SDL3 controllers.

## Hook-up fast path (low latency)

With a DroidDeck build from this fork, Steam Native Hook-up skips every hop it can:
- DroidDeck hands the bridge its shared hook-up file once (as a file descriptor over the existing connection).
- A dedicated high-priority thread in the bridge picks up each controller report within ~250 us of SDL's USB
  thread receiving it and writes it straight into Steam's FIFO in that file, waking Steam's reader with a futex
  (before: once per main-loop pass, then Java, a Binder message per report, DroidDeck's app, and Steam's side
  checking every 500 us).
- Steam's output reports (rumble, lights) and feature reads are served from the same file by the bridge's own
  writer thread, the moment Steam queues them (checked every 500 us; before: once per main-loop pass, one USB
  write after another, so under heavy rumble Steam's queue filled up and Steam had to wait for room). On Steam's
  side such a wait no longer holds the hook's controller lock (it held up Steam's virtual pad and reads), and an
  output report waits at most 4 ms -- if the controller can't take them that fast, the newest is dropped (the
  next one carries the current rumble / light state again).
- DroidDeck still owns the controller list and its sysfs description; while the bridge serves a slot it keeps a
  heartbeat in the file and DroidDeck leaves that slot alone. If the fast path is unavailable (older DroidDeck,
  file not mappable), everything falls back to messages automatically.

Measured in a test with the real DroidDeck code and hook (Binder hop not even counted for the old path):
median 1.45 ms -> 0.29 ms, p90 2.3 ms -> 0.44 ms from report to Steam. The status line shows "fast path".

## No input lag build-up

Every input path keeps only fresh state waiting, never a growing line of old input:
- The bridge sends a frame to DroidDeck only when the controller state changed (plus 20 ms after a change and a
  keep-alive every 100 ms), instead of on every loop pass.
- DroidDeck takes the bridge's messages on the Binder thread that receives them: hook-up reports and Steam
  Controller states go straight into shared memory, and frames are "latest wins" per controller -- only the newest
  waits for the worker thread, every button / trackpad change is kept so no tap is lost, and a stick/gyro-only
  frame that arrives more than 50 ms old is dropped. The worker thread runs at URGENT_DISPLAY priority.
- Steam reads every controller node (hooked-up and DroidDeck's own pads) from a mailbox that hands it the newest
  state, see "Steam reads the newest reports" below.
- Lag meters in pad.log: "bridge: input lag, last 10 s: ..." (DroidDeck) and "hookup: ... / deck: ... reports/s,
  Steam read ..." (Steam side) appear only when input was late.

Simulated with a worker that is too slow: a plain queue was 5.3 s behind after 10 s of play; latest-wins stays
within ~1 ms and keeps every button change.

## Latency meter (pad.log)

Where a button press spends its time -- any controller, hooked-up or one of DroidDeck's own pads -- logged every 10 s
while there are presses (plus the first 12 presses one by one). Buttons the controller's HID descriptor declares
are timed directly; for the rest, a learned button-like bit that turns on counts (see below):
- `latency: button press waited for Steam to read it` -- from the report being handed to Steam until Steam read it
  (mouse / keyboard mappings see this delay too).
- `latency: Steam read a button press -> on Steam's virtual pad (Steam's own processing)` -- gamepad mappings only.
- `latency: Steam's virtual pad -> game read` (game process).
- `hookup: /dev/hidraw24: N reports/s, Steam read M/s (all waiting each time | the newest each time), avg X ms after
  they arrived ...` (`deck: Steam Deck pad: ...` for DroidDeck's own pads) when Steam was slow to read, and which
  way Steam reads (`Steam takes every report waiting each time` / `Steam reads one report at a time`).
- `mouse: ...` -- Steam Input's mouse / keyboard output on its way to the game. Steam sends XTest events to the
  game's X server (Xwayland); the trace shows the X server handing them to gamescope (`X server handed mouse button
  0x110 press to gamescope (libei)`), gamescope receiving them (`gamescope received mouse button ...`), and the
  input device each side set up (which kinds of input it can send). A click that never reaches a game shows the
  step where it stops. Buttons are logged one by one at first, then every 100th; keys a few times to compare.
  (Steam's own XTest calls are not traced: Steam loads that library privately, and wrapping it dropped its input.)
- `hookup: ... the controller fell behind` (Steam's output reports waited for room) and `hookup: report 0xNN read took
  X ms` (a slow feature read).

## Steam reads the newest reports (every controller)

Every controller node Steam reads goes through a mailbox: the hooked-up controllers (`/dev/hidraw24..27`) and
DroidDeck's own pads (Steam Deck, DualSense / Edge, DualShock 4, Steam Controller 2015 and 2026 -- every
"Present controller as" mode). Steam's read() of the node is answered from it; the node's socket only carries a
1-byte "something to read" token, so poll()/epoll work as on a real node (read(), readv() and the fortified
`__read_chk` all go through it). Before, reports went into a small socket queue: when Steam read slowly it filled
up, the newest reports were the ones dropped and Steam read old ones -- a press reached it one or more of its read
cycles late, and short presses could be lost.

How the reports are handed over follows how Steam reads, learned while it runs (pad.log says which):
- **Steam takes everything waiting each time** (like SDL's update loop): it gets every report in order, the newest
  last -- the newest state is in that same read, and gyro / touchpad keep all their samples.
- **Steam takes one report and goes away** (busy): it gets the newest one; older ones are skipped.

No press or release is ever lost: a report is only skipped when that loses nothing -- not when a button-like bit
changed and changed back before Steam read it (a press shorter than Steam's read cycle); then both reach Steam in
order. This works for any controller without knowing its layout: DroidDeck learns from the reports which bits are
"moving" (sticks, gyro, accelerometer, touch, noise: they change in more than a quarter of reports, no button is
pressed that fast) and which bytes are counters / timestamps, and treats the carry of an analog value crossing a
boundary as analog too; buttons the controller's HID descriptor declares are always protected. Only report kinds
that stream (256 reports within 5 s) are ever skipped, so replies to Steam's commands always arrive one by one. At
most 8 reports wait (the oldest skippable one goes first).

Simulated with a Steam that comes back only every 15-45 ms, press to Steam (15 ms taps):

| | Before (socket queue) | Now |
|---|---|---|
| DroidDeck's Steam Deck pad, Steam takes all, 250 Hz | avg 26 ms, max 58; 40 of 46 taps; 185 reports/s | avg 18-20 ms, max 43; all taps; 220 reports/s |
| same, 1000 Hz (low latency) | avg 37 ms; 25 of 44 taps | avg 13 ms; all taps |
| Steam Deck pad, Steam takes one at a time, 250 / 1000 Hz | avg 159 / 136 ms, max 240 | avg 19 ms, max 42 |
| Hooked-up controller (generic and Sony), Steam takes all | avg 28-38 ms (first mailbox: 14-17 ms, 50 reports/s) | avg 14-16 ms, 225-270 reports/s |
| Hooked-up controller, Steam takes one at a time | (first mailbox: avg 25-35 ms, max 320-460) | avg 15 ms, max 42 |
| Steam reading in time | 2.2 ms | 1.8 ms (unchanged) |

## Controller-driven mouse works out of the box

DroidDeck's Performance page has "Draw the cursor inside gamescope" (Session fixes), which upstream leaves off to
save a compositor plane. With it off, Steam Input's mouse output from a controller is incomplete: a button bound to
a mouse click does nothing (keyboard bindings and mouse movement still work). SDL3Deck starts it ON; the switch is
still there to turn it off.

## Steam's controller threads at higher priority

Inside Steam, the threads that read controllers (hooked-up ones and DroidDeck's own pads -- every controller), the
one that writes Steam's virtual pad, and DroidDeck's own report threads run at nice -8, like Android's input
threads. On a phone the game and the x86 emulation keep the CPU busy; Steam's controller loop otherwise waits its
turn behind them, which is when it reads late. pad.log: "thread N (...) now runs at higher priority".

## SDL3Bridge tab in DroidDeck's Settings

With a DroidDeck build from this fork, Setup has an **SDL3Bridge** tab, and the in-session drawer has the same
**SDL3Bridge** page (USB icon, between Game profile and Session) to change things while playing:
- Status: the bridge's status line, each connected controller (player, name, ids, battery), controllers handed to
  Steam (hidraw node, fast path), the Steam Controller (2026) passthrough.
- Controller mode: Present controller as (incl. Steam Native Hook-up), SDL3 driver on/off, controller source
  (Auto / Forced USB), poll rate, floating bubble.
- Per player (P1-P4): gyro speed, invert, axis remap, accelerometer follows, deadzone, calibration and reset;
  touchpad layout, invert X/Y, swap; all 8 button remaps; lightbar red/green/blue, brightness, player LEDs.
- **Open SDL3Bridge** for the controller test screen, USB access and USB audio.

The tab has its own connection to the bridge (it never receives controller frames): it asks for the settings
state once a second while open (MSG_SETTINGS_GET 40) and sends single changes (MSG_SETTINGS_SET 41); the bridge
answers with everything, including the choice lists (MSG_SETTINGS 42), and applies a change exactly as its own
screen does. The patcher adds the tab only if DroidDeck's settings widgets still have the signatures it was written
against, so an upstream redesign leaves the tab out instead of breaking the build. (If the bridge's own screen is
open while you change things in DroidDeck, it shows the new values after it is reopened.)

## Runs silently in the background (status bar icon)

SDL3 runs inside the bridge's background service (SdlEngine), not inside a screen: when DroidDeck connects, a
controller is plugged in, or the app is opened, the service starts SDL3's loop and nothing comes to the front.
The bridge shows a status bar icon (a controller) with its current status; tapping it opens the bridge screen,
which is now only the test screen and settings and can be opened and closed at any time. It keeps running until
the app is force-stopped. Android 13+ asks once for notification permission (needed for the icon). If Android
does not allow a foreground service at that moment, the icon is shown as a plain ongoing notification and
DroidDeck's connection keeps the bridge alive.

SDL3's Java side expects "the app's screen" as its context and reads the app's files and assets through it while
it starts (gamepad mappings): with no screen at all that lookup crashed the whole app (the first background builds
stayed at "Starting..." and the app would not open). A stand-in context (an Activity object that is never shown,
whose calls go to the app) is now given to SDL3, and SDL3's mappings file (`files/gamepad_map.txt`, empty unless
you add mappings) is handed to it directly.

Self-protecting: before each background start the bridge saves a marker, cleared when SDL3's loop reports its
first status. If SDL3 stays silent for 10 s, its loop ends, or the app dies during the attempt, the bridge restarts
and uses the fallback until the app is updated: nothing opens by itself; the status, the status bar icon and
DroidDeck's SDL3Bridge page say "Open SDL3Bridge once to start SDL3". Opening SDL3Bridge then starts SDL3 the old
way in a transparent screen behind the bridge's screen (SdlHostActivity), which stays open as usual; SDL3 keeps
running when you leave it (Back only steps back so it is not closed). Each step and, after a failure, the bridge's
own log (crash report included) go to DroidDeck's session log as "sdl3bridge: engine: ..."; DroidDeck's SDL3Bridge
page shows how SDL3 runs ("SDL3 runs in ...").
