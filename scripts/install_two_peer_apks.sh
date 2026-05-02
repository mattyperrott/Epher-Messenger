#!/bin/zsh
set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEBUG_APK="${DEBUG_APK:-$ROOT_DIR/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk}"
PEER_APK="${PEER_APK:-$ROOT_DIR/app/build/outputs/apk/peer/app-arm64-v8a-peer.apk}"

if [[ ! -f "$DEBUG_APK" ]]; then
  echo "Debug APK not found at $DEBUG_APK" >&2
  exit 1
fi

if [[ ! -f "$PEER_APK" ]]; then
  echo "Peer APK not found at $PEER_APK" >&2
  exit 1
fi

serials=("${(@f)$( "$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }' )}")
if [[ "${#serials[@]}" -lt 2 ]]; then
  echo "Need at least two attached adb devices/emulators; found ${#serials[@]}." >&2
  exit 1
fi

echo "Installing debug app on ${serials[1]}"
"$ADB_BIN" -s "${serials[1]}" install -r "$DEBUG_APK"

echo "Installing peer app on ${serials[2]}"
"$ADB_BIN" -s "${serials[2]}" install -r "$PEER_APK"

echo "Installed:"
echo "  ${serials[1]} -> com.epher.app.debug"
echo "  ${serials[2]} -> com.epher.app.peer"
