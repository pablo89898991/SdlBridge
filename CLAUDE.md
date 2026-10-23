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
- Commit messages end with the attribution lines this session's instructions give.
- Never push to upstream Droid-Deck/droiddeck. Delete temporary upstream clones after use.

## DroidDeck integration
- Thin fork: every DroidDeck change is made by `droiddeck/patch-droiddeck.sh` (exact anchors; a feature is left
  out with a warning rather than breaking the build). Check changes against the newest upstream.
- Plan for the merged app "SDLApiDroidDeck" (repo name): `newfork/PLAN.md` (README for its main page:
  `newfork/README.md`).
