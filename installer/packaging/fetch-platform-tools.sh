#!/usr/bin/env bash
set -euo pipefail

# Populates installer/packaging/resources/platform-tools for local builds and CI.
# Binaries are intentionally not committed to Git.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE="$HERE/.cache"
DEST_WIN="$HERE/resources/windows/platform-tools"
DEST_MAC="$HERE/resources/macos/platform-tools"
mkdir -p "$CACHE" "$DEST_WIN" "$DEST_MAC"

WIN_URL="https://dl.google.com/android/repository/platform-tools_r34.0.4-windows.zip"
WIN_SHA="520a453af1d0baf1939a80038a816cb8de40d000542b1a71bf60ba6fc80784d9"
MAC_URL="https://dl.google.com/android/repository/platform-tools_r34.0.4-darwin.zip"
MAC_SHA="c60d9ee0acade673b649543b2d53c632ba54f9ca083ed612176105117fa16102"

fetch() {
  local url="$1"
  local sha="$2"
  local file="$3"
  if [ ! -f "$file" ]; then
    curl -fL --retry 3 --retry-delay 2 -o "$file" "$url"
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    echo "$sha  $file" | sha256sum -c -
  else
    echo "$sha  $file" | shasum -a 256 -c -
  fi
}

extract() {
  local zip="$1"
  local dest="$2"
  if command -v unzip >/dev/null 2>&1; then
    unzip -o -q "$zip" -d "$dest"
  elif command -v 7z >/dev/null 2>&1; then
    7z x -y -o"$dest" "$zip" >/dev/null
  else
    echo "unzip or 7z is required" >&2
    exit 1
  fi
}

WIN_ZIP="$CACHE/platform-tools_r34.0.4-windows.zip"
MAC_ZIP="$CACHE/platform-tools_r34.0.4-darwin.zip"
fetch "$WIN_URL" "$WIN_SHA" "$WIN_ZIP"
fetch "$MAC_URL" "$MAC_SHA" "$MAC_ZIP"

rm -rf "$CACHE/win" "$CACHE/mac"
extract "$WIN_ZIP" "$CACHE/win"
extract "$MAC_ZIP" "$CACHE/mac"

cp "$CACHE/win/platform-tools/adb.exe" "$DEST_WIN/"
cp "$CACHE/win/platform-tools/AdbWinApi.dll" "$DEST_WIN/"
cp "$CACHE/win/platform-tools/AdbWinUsbApi.dll" "$DEST_WIN/"
cp "$CACHE/mac/platform-tools/adb" "$DEST_MAC/"
chmod +x "$DEST_MAC/adb"

echo "platform-tools ready:"
echo "  $DEST_WIN/adb.exe"
echo "  $DEST_MAC/adb"
