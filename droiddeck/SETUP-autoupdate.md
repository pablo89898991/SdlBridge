# Auto-updating, bridge-integrated DroidDeck

This keeps a DroidDeck APK that always tracks the newest upstream DroidDeck with the SDL3 bridge
baked in -- no manual patching, no merge conflicts.

## How it works

Your DroidDeck **build fork** holds only three added things on `main`:

    sdl3/patch-droiddeck.sh          the patcher (edits source at build time)
    sdl3/DualSenseBridge.kt          the bridge client installed into DroidDeck
    .github/workflows/sync-upstream.yml   the auto-updater

The `sync-upstream` workflow runs daily (and on demand). Each run:

1. Downloads the latest upstream `Droid-Deck/DroidDeck`.
2. Overlays it over your tree, keeping only the three files above (overwrite, never merge -> no conflicts).
3. Runs the patcher, which injects the bridge into the pristine upstream source.
4. Commits and triggers upstream's own **Build APK** workflow.

Because it overwrites instead of merging, upstream refactors never cause a git conflict. If upstream ever
renames one of the source anchors the patcher targets, the build step fails loudly (you get an email) and
the patcher prints a `TODO` naming what moved -- it never produces a half-patched APK.

## One-time setup (Termux)

The sync workflow lives on your fork's default branch (`main`) but builds on a dedicated `sdl3deck`
branch, so `main` is never overwritten.

    # clone YOUR fork
    git clone https://github.com/<you>/DroidDeck SDL3Deck && cd SDL3Deck

    # a) put ONLY the auto-updater on main
    git checkout main
    mkdir -p .github/workflows
    cp ~/storage/downloads/sync-upstream.yml .github/workflows/
    git add .github/workflows/sync-upstream.yml
    git commit -m "SDL3Deck auto-updater"
    git push

    # b) create the dedicated build branch holding just the tooling
    git checkout --orphan sdl3deck
    git rm -rf . >/dev/null 2>&1 || true
    mkdir -p sdl3
    cp ~/storage/downloads/patch-droiddeck.sh sdl3/
    cp ~/storage/downloads/DualSenseBridge.kt sdl3/
    chmod +x sdl3/patch-droiddeck.sh
    git add sdl3
    git commit -m "SDL3 bridge tooling"
    git push -u origin sdl3deck

Then on GitHub: **Actions -> Sync upstream + patch -> Run workflow**. It fills `sdl3deck` with
"latest upstream + bridge + SDL3Deck name" and triggers Build APK on that branch. After that it runs
daily on its own. Grab the APK from the Build APK run on `sdl3deck`.

## Notes / limits

- The patched APK is signed by your fork's build, so it installs over *your* DroidDeck, not an official one.
- First run replaces your fork's `main` contents with upstream+patch. If you keep other work on `main`,
  point the workflow at a dedicated branch instead (change `ref` in the two places, and dispatch it there).
- The bridge APK (SDL3BridgeDroidDeck) updates separately via its own CI + in-app updater.
