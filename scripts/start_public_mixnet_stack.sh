#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR/runtime"

PUBLIC_HOST="${EPHER_PUBLIC_HOST:-$(hostname -I 2>/dev/null | awk '{print $1}')}"
if [[ -z "$PUBLIC_HOST" ]]; then
  echo "Unable to determine public host. Set EPHER_PUBLIC_HOST and retry." >&2
  exit 1
fi

export EPHER_PUBLIC_HOST="$PUBLIC_HOST"
export EPHER_MIXNET_RELAY_BIND="${EPHER_MIXNET_RELAY_BIND:-0.0.0.0}"
export EPHER_MIXNET_RELAY_PORT="${EPHER_MIXNET_RELAY_PORT:-9797}"
export EPHER_MIXNET_PROVIDER_BIND="${EPHER_MIXNET_PROVIDER_BIND:-0.0.0.0}"
export EPHER_MIXNET_PROVIDER_PORT="${EPHER_MIXNET_PROVIDER_PORT:-9798}"
export EPHER_MIXNET_PROVIDER_URL="${EPHER_MIXNET_PROVIDER_URL:-http://$PUBLIC_HOST:${EPHER_MIXNET_PROVIDER_PORT}/provider}"

export EPHER_MIXNET_NODE_BIND="${EPHER_MIXNET_NODE_BIND:-127.0.0.1}"
export EPHER_MIXNET_NODE_A_URL="${EPHER_MIXNET_NODE_A_URL:-http://127.0.0.1:9801/forward}"
export EPHER_MIXNET_NODE_B_URL="${EPHER_MIXNET_NODE_B_URL:-http://127.0.0.1:9802/forward}"
export EPHER_MIXNET_NODE_C_URL="${EPHER_MIXNET_NODE_C_URL:-http://127.0.0.1:9803/forward}"

cleanup() {
  jobs -pr | xargs -r kill 2>/dev/null || true
}

trap cleanup EXIT INT TERM

node ./scripts/dev-mixnet-provider.js &
EPHER_MIXNET_NODE_ID="mix-a" EPHER_MIXNET_NODE_PORT="9801" node ./scripts/dev-mixnet-node.js &
EPHER_MIXNET_NODE_ID="mix-b" EPHER_MIXNET_NODE_PORT="9802" node ./scripts/dev-mixnet-node.js &
EPHER_MIXNET_NODE_ID="mix-c" EPHER_MIXNET_NODE_PORT="9803" node ./scripts/dev-mixnet-node.js &

node ./scripts/dev-mixnet-relay.js
