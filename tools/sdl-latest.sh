#!/usr/bin/env bash
# Prints the newest stable SDL3 release as "x.y.z" (e.g. 3.4.20). Needs gh with a token (GH_TOKEN).
# SDL's repository also publishes SDL2 releases (release-2.x) and SDL3 previews (prereleases, odd minor versions such
# as 3.3.x), and "the latest release" can be any of them: only full releases of an even SDL3 minor version count.
set -euo pipefail
gh api 'repos/libsdl-org/SDL/releases?per_page=100' \
  --jq '.[] | select(.draft == false and .prerelease == false) | .tag_name' |
  grep -E '^release-3\.[0-9]*[02468]\.[0-9]+$' | sed 's/^release-//' | sort -V | tail -1 | grep .
