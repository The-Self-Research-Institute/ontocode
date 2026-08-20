#!/usr/bin/env bash

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
set -a
# shellcheck disable=SC1091
source ./.env.deploy
set +a

HOST="${DEV_EC2_HOST:?}"
DIR="${DEV_EC2_DIR:-${EC2_DIR:-/home/ubuntu/ontocode}}"
KEY="${DEV_SSH_KEY:-}"
REG="${DEV_REGISTRY:-ontocode}"
VER="${DEV_VERSION:-dev}"
ssh_opts=(-o BatchMode=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=accept-new)
[[ -n "$KEY" && -f "$KEY" ]] && { chmod 600 "$KEY" 2>/dev/null || true; ssh_opts+=(-i "$KEY"); }

scp "${ssh_opts[@]}" "$ROOT/Dockerfile.swrl" "${HOST}:${DIR}/Dockerfile.swrl"

ssh "${ssh_opts[@]}" "$HOST" \
  "export DOCKER_USERNAME=$(printf %q "$DOCKER_USERNAME") DOCKER_PASSWORD=$(printf %q "$DOCKER_PASSWORD") DEV_API_BASE=$(printf %q "${DEV_API_BASE:-}") DIR=$(printf %q "$DIR") REG=$(printf %q "$REG") VER=$(printf %q "$VER"); bash -s" <<'REMOTE'
set -euo pipefail
cd "$DIR"
echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin >/dev/null
echo "[remote] docker login OK"

# Remaining web images after auth/gateway/editor/reasoner-worker succeeded
echo "[remote] building remaining: swrl plugin plugin-init web"
./build-and-push.sh "$REG" "$VER" swrl plugin plugin-init web

echo "[remote] compose up"
if [[ -f docker-compose.de.ec2.yml ]]; then
  DOCKER_REGISTRY="$REG" VERSION="$VER" docker compose -f docker-compose.yml -f docker-compose.de.ec2.yml up -d --pull always
  DOCKER_REGISTRY="$REG" VERSION="$VER" docker compose -f docker-compose.yml -f docker-compose.de.ec2.yml ps
else
  DOCKER_REGISTRY="$REG" VERSION="$VER" docker compose -f docker-compose.yml up -d --pull always
  docker compose -f docker-compose.yml ps
fi

echo "[remote] vscode deps + bundle"
cd ontology-vscode-extension
npm install --no-fund --no-audit
# ensure analyzer present
npm install --no-save webpack-bundle-analyzer --no-fund --no-audit || true
if [[ -d webview-src ]]; then
  (cd webview-src && npm install --no-fund --no-audit && npm install --no-save @rollup/rollup-linux-x64-gnu --no-fund --no-audit || true)
fi
if [[ -n "${DEV_API_BASE:-}" ]]; then
  printf 'VITE_API_BASE_URL=%s\n' "$DEV_API_BASE" > .env.dev-release
fi
ENV_FILE=.env.dev-release npm run bundle:all
echo "[remote] vscode OK"
ls -la *.vsix 2>/dev/null || ls -la dist/*.vsix 2>/dev/null || true

echo "[remote] RESUME OK"
REMOTE
