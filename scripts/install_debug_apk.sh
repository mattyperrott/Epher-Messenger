#!/bin/zsh
set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
APK_PATH="${1:-$(pwd)/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk}"

if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found at $APK_PATH" >&2
  exit 1
fi

serials=("${@:2}")
if [[ "${#serials[@]}" -eq 0 ]]; then
  serials=("${(@f)$( "$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" && $1 ~ /^emulator-/ { print $1 }' )}")
fi

if [[ "${#serials[@]}" -eq 0 ]]; then
  echo "No running emulator devices found." >&2
  exit 1
fi

for serial in "${serials[@]}"; do
  echo "Installing on $serial"
  "$ADB_BIN" -s "$serial" install -r "$APK_PATH"
done
