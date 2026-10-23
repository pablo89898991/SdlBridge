# DroidDeckSteamNativeHook -- setup notes (to do after the hook-up latency fix)

Things agreed for the new repo, kept here so they are not forgotten:

0. **Every controller.** Whatever is fixed or added must work for all SDL3-supported controllers, never one
   vendor only (see `CLAUDE.md`). Copy `CLAUDE.md` into the new repo too.

1. **README on the main page.** `newfork/README.md` goes to the ROOT of the new repo's default branch (`main`) as
   `README.md` -- that is what GitHub shows on the repo's main page. The daily sync never touches `main` (it only
   writes the build branch), so it stays.
2. **README on the build branch too.** Also copy it to `sdl3/README.md` on the build branch: the patcher
   (step 24) then puts it at that branch's root after every upstream overlay, instead of upstream's README.
3. **One APK**: DroidDeck + SDL3 + the bridge, applied by the patcher on top of the newest upstream (thin fork,
   daily auto-sync kept). The bridge runs in its own process inside the app (`android:process=":sdl3"`).
4. **Bridge settings** live in DroidDeck (Setup tab + session drawer page); the bridge's own screen stays as the
   controller test screen.
5. **To decide when we start**: app ID -- keep `com.droiddeck.launcher` (replaces DroidDeck / SDL3Deck, keeps
   data) or a new one (installs side by side, fresh setup).
6. **License**: GPLv3 like upstream; README says it is an unofficial fork (already in the README).
