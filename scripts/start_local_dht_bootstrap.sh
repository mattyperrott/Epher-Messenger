#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR/runtime"

HOST_IP="${EPHER_BOOTSTRAP_HOST:-$(ipconfig getifaddr en0 2>/dev/null || true)}"
if [[ -z "$HOST_IP" ]]; then
  echo "Unable to determine a host IP. Set EPHER_BOOTSTRAP_HOST and retry." >&2
  exit 1
fi

exec node ./node_modules/hyperdht/bin.js --bootstrap --host "$HOST_IP" --port 49737
