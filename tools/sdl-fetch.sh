#!/usr/bin/env bash
# sdl-fetch.sh DEST [VERSION SHA256]
# Unpacks SDL3's release source into DEST (e.g. app/jni/SDL), checked against its SHA-256. Without VERSION and SHA256
# it takes the pinned release from droiddeck/sdl3.env -- the one SDL3DroidDeck builds with, kept current by the
# "SDL update" workflow -- so the bridge app and SDL3DroidDeck always use the same SDL.
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
dest=$1
if [ $# -ge 3 ]; then SDL_VERSION=$2; SDL_SHA256=$3; else . "$here/droiddeck/sdl3.env"; fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
url="https://github.com/libsdl-org/SDL/releases/download/release-$SDL_VERSION/SDL3-$SDL_VERSION.tar.gz"
curl -fsSL --retry 4 --retry-all-errors --connect-timeout 20 --max-time 600 -o "$tmp/sdl.tar.gz" "$url"
echo "$SDL_SHA256  $tmp/sdl.tar.gz" | sha256sum -c - >/dev/null || {
  echo "sdl-fetch: SDL3-$SDL_VERSION.tar.gz does not match its pinned SHA-256" >&2; exit 1; }
rm -rf "$dest"
mkdir -p "$dest"
tar -xzf "$tmp/sdl.tar.gz" -C "$dest" --strip-components=1
echo "sdl-fetch: SDL $SDL_VERSION in $dest (SHA-256 checked)"
