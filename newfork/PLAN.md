# SDLApiDroidDeck -- the merged app (SDL3DroidDeck)

The repo **SDLApiDroidDeck** builds one APK, **SDL3DroidDeck**: upstream DroidDeck + the SDL3Deck patch + the
SDL3Bridge app built in. Decided with the owner:

0. **Every controller.** Whatever is fixed or added must work for all SDL3-supported controllers, never one vendor
   only (see `CLAUDE.md`).
1. **App ID stays `com.droiddeck.launcher`**: installs over SDL3Deck / DroidDeck and keeps the games and data.
   Signing: upstream's workflow signs with the committed AOSP test key on every branch but main (the release
   re-signing job only runs on upstream's main) -- the same key SDL3Deck builds have. Never build on `main` there.
2. **Launcher label: SDL3DroidDeck** (`SDL3_APP_LABEL`, step 6).
3. **README on the main page**: `newfork/README.md` -> `README.md` on `main`; the sync also puts it on the build
   branch.

## How it is put together

- `main`: `README.md`, `LICENSE` (upstream's GPLv3), `.github/workflows/sync.yml` (= `newfork/sync.yml`) and
  `.github/workflows/build.yml` (= `newfork/build.yml`, a stub so "Build APK" can be dispatched on the build branch).
- Build branch `sdl3droiddeck`: filled by the sync -- newest upstream, then
  `SDL3_EMBED_BRIDGE=1 SDL3_APP_LABEL=SDL3DroidDeck bash SdlBridge/droiddeck/patch-droiddeck.sh .` from a fresh
  clone of SdlBridge. Nothing of SdlBridge is copied into SDLApiDroidDeck by hand: push to SdlBridge, run the sync.
- Step 30 of the patcher (only with `SDL3_EMBED_BRIDGE=1`): SDL3 source from its pinned release (`droiddeck/sdl3.env`,
  SHA-256) into `app/src/main/cpp/sdl3bridge/SDL` + its Android Java classes; the bridge's native loop
  (`libmain.so`) and Java (`com.example.dsedge`, unchanged package) and resources (`sdl3_*`); manifest entries with
  `android:process=":sdl3"`; `App.kt` skips DroidDeck's set-up in that process; SDL3 leaves Bluetooth to DroidDeck.
- Runtime: `DualSenseBridge.bridgePackage()` / `SdlBridgeLink` use the bridge in the app's own package when it is
  there, else the separate SDL3Bridge app (SDL3Deck keeps working as before). The bridge notices it is built in
  (`Host.java`): own preference names, no update check of its own.
- The separate SDL3Bridge app and SDL3Deck stay buildable as before (SDL3Deck = no `SDL3_EMBED_BRIDGE`).

## Moving to it

Install over SDL3Deck, uninstall the separate SDL3Bridge app, set the bridge's settings again (Settings ->
SDL3Bridge), allow USB access once.
