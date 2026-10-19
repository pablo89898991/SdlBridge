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
- The bridge screen must be opened once per phone boot; it cannot launch itself from the background.
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
