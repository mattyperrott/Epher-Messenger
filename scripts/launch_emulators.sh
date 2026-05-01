#!/bin/zsh
set -euo pipefail

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
EMULATOR_BIN="$ANDROID_SDK_ROOT/emulator/emulator"
ADB_BIN="${ADB_BIN:-adb}"
TMP_DIR="${TMP_DIR:-$(pwd)/.tmp/emulators}"

if [[ ! -x "$EMULATOR_BIN" ]]; then
  echo "Android emulator binary not found at $EMULATOR_BIN" >&2
  exit 1
fi

headless=0
if [[ "${1:-}" == "--headless" ]]; then
  headless=1
  shift
fi

if (( $# == 0 )); then
  avds=(EpherPeer1 EpherPeer2 EpherPeer3)
else
  avds=("$@")
fi

mkdir -p "$TMP_DIR"

boot_wait() {
  local serial="$1"
  "$ADB_BIN" -s "$serial" wait-for-device >/dev/null
  until [[ "$("$ADB_BIN" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 2
  done
}

port_for_avd() {
  case "$1" in
    EpherPeer1) echo 5554 ;;
    EpherPeer2) echo 5556 ;;
    EpherPeer3) echo 5558 ;;
    *)
      echo ""
      ;;
  esac
}

for (( index = 1; index <= ${#avds[@]}; index++ )); do
  local_avd="${avds[$index]}"
  local_port="$(port_for_avd "$local_avd")"
  if [[ -z "$local_port" ]]; then
    echo "Unknown AVD name: $local_avd" >&2
    exit 1
  fi

  serial="emulator-$local_port"
  log_file="$TMP_DIR/${local_avd}.log"

  if "$ADB_BIN" devices | rg -q "^${serial}[[:space:]]+device$"; then
    echo "$local_avd already running on $serial"
    continue
  fi

  args=(
    -avd "$local_avd"
    -port "$local_port"
    -no-snapshot-load
    -no-boot-anim
    -noaudio
  )

  if [[ "$headless" -eq 1 ]]; then
    args+=(-no-window -gpu swiftshader_indirect)
  fi

  nohup "$EMULATOR_BIN" "${args[@]}" >"$log_file" 2>&1 &
  echo "Starting $local_avd on $serial"
done

for (( index = 1; index <= ${#avds[@]}; index++ )); do
  serial="emulator-$(port_for_avd "${avds[$index]}")"
  boot_wait "$serial"
  echo "$serial booted"
done
