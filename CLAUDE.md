# Working on SDL3Bridge / SDL3Deck -- rules from the project owner

## Every controller, always
SDL3Bridge is for **every SDL3-supported controller** (USB or Bluetooth, any vendor: Xbox, Switch / Switch 2,
8BitDo, Flydigi, Steam Controller, PlayStation, generic HID, ...). It is NOT a Sony-first app.
- Any fix or feature, even when it was found with one controller (often a DualSense Edge), must work for **all**
  controllers. Never ship a Sony-only (or any single-vendor) fix.
- Don't rely on one vendor's report layout. Prefer what works for any controller: SDL3's generic APIs, the
  controller's own HID report descriptor, or behaviour learned from the reports themselves.
- Vendor-specific code only where a protocol truly requires it (e.g. the Steam Controller 2026 passthrough), and
  then other controllers must still be handled.
- Test with a non-Sony (generic) controller too -- in simulations as well.

## Git / delivery
- The owner pushes from Termux (`~/SdlBridge-clean` = this repo, `~/SDL3Deck` = the DroidDeck fork, branch
  `sdl3deck`, patch files in `sdl3/`). Give complete Termux commands.
- Commit BEFORE `git pull --rebase` (git refuses to pull with uncommitted changes).
- SDL3Deck: the sync bot commits to `sdl3deck` daily, so start from
  `git fetch origin && git reset --hard origin/sdl3deck`, then copy the `droiddeck/` files into `sdl3/`.
- SDLApiDroidDeck (the merged app, SDL3DroidDeck): its `main` holds only README / LICENSE / workflows; its sync
  takes the bridge and the patcher straight from this repo (`SDL3_EMBED_BRIDGE=1`, step 30), so changes go here.
  Never let it build on `main` (upstream's release-signing job runs there).
- Commit messages end with the attribution lines this session's instructions give.
- Never push to upstream Droid-Deck/droiddeck. Delete temporary upstream clones after use.

## DroidDeck integration
- Thin fork: every DroidDeck change is made by `droiddeck/patch-droiddeck.sh` (exact anchors; a feature is left
  out with a warning rather than breaking the build). Check changes against the newest upstream: run the whole
  patcher on a fresh clone of upstream main in both modes (plain and `SDL3_EMBED_BRIDGE=1`), twice (the second run
  must change nothing), and fix every TODO / ERROR. Optional features: all edits of a feature are computed first
  and written only when every anchor matched (never half a feature), and a miss is `warn`, not `die`.
- When upstream fixes something the patcher fixed, detect it and leave upstream's version (e.g. step 28).
- Upstream's CI sometimes downloads pinned files from the release of the repo being built (`$GITHUB_REPOSITORY`).
  The fork has no such releases: fetch them from upstream's release instead (keeping upstream's SHA-256 check).
- Wrapping a library function from a preload (`LD_PRELOAD` / `/etc/ld.so.preload`): never rely on
  `dlsym(RTLD_NEXT)` alone. Steam's client links libXtst (and others) privately, so RTLD_NEXT finds nothing and
  the call is silently dropped; fall back to `dlopen("<soname>", RTLD_NOLOAD)` + `dlsym(handle)`. Test it with a
  library loaded via `dlopen(RTLD_LOCAL)`.
- A background thread in the preload library: never give the mutex / condition variable it waits on static storage
  that is destroyed at exit (destroying a condition variable with a waiter blocks forever: the app hangs on quit).
  Allocate them once with `new` and never free them, as the hook's other globals are.
- Every session type, not only the Steam session: on DroidDeck's desktop (KDE; Steam's desktop UI too) Steam is one
  program among others, may not be running, and may be the x86-64 client under FEX. There the Steam client, games
  Steam starts and other desktop programs each see different controller nodes (step 29, `FAKE_EVDEV_DESKTOP`), and
  Hook-up waits for the Steam client's heartbeat. Check a controller change in both kinds of session.
- Windows programs: `droiddeck-proton-run` picks the Proton; `droiddeck-wine` (step 33) replaces a pick that needs
  Steam's ARM64 Proton when it isn't downloaded (DroidDeck-Proton, downloaded once if missing) and makes the desktop's
  Wine menu (shared C: drive = compatdata/3589834995). Windows components for desktop programs: step 32. Settings ->
  Wine (Proton updates, `WineSettings.kt`): step 34. Never make Windows programs depend on the Steam client.
- The merged app "SDLApiDroidDeck" (repo name; app label SDL3DroidDeck, app ID com.droiddeck.launcher):
  `newfork/PLAN.md`, its workflows `newfork/sync.yml` + `newfork/build.yml` + `newfork/publish.yml` (releases),
  README `newfork/README.md`. The bridge
  runs there in the app's `:sdl3` process; bridge code must keep working both built in and as its own app
  (`Host.embedded()` on the bridge side, `DualSenseBridge.bridgePackage()` on DroidDeck's).
