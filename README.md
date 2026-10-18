# DualSense Bridge (SDL3)

Android app that uses SDL3 to read game controllers over USB (DualSense, DualSense Edge, DualShock 4,
Xbox, Switch Pro, ... anything SDL3 supports) and streams them to DroidDeck through the same bound
service protocol as the first bridge. The DroidDeck patch (`DualSenseBridge.kt`) works unchanged.

## Test screen
Opening the app shows a live controller diagram. Orange = held now, green = worked at least once,
grey = not tested yet. A summary line counts buttons and shows sticks / triggers / gyro / accel / touchpad.
Buttons: Rumble (0.7 s), Light (cycles lightbar colours), Reset (clear green marks), Update.

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
