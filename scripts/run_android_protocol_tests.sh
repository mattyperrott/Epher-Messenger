#!/bin/zsh
set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT_DIR"

serials=("${(@f)$( "$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }' )}")
if [[ "${#serials[@]}" -eq 0 ]]; then
  echo "No attached adb device/emulator found." >&2
  exit 1
fi

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.epher.app.security.RoomRekeyInstrumentedTest,com.epher.app.security.RoomRetentionWipeInstrumentedTest
