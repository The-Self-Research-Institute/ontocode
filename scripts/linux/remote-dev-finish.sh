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

ssh "${ssh_opts[@]}" "$HOST" \
  "export DEV_API_BASE=$(printf %q "${DEV_API_BASE:-}") DIR=$(printf %q "$DIR") REG=$(printf %q "$REG") VER=$(printf %q "$VER"); bash -s" <<'REMOTE'
set -euo pipefail
cd "$DIR"

echo "[remote] diagnosing :27017..."
sudo ss -ltnp | grep 27017 || true
docker ps -a --filter publish=27017 --format 'table {{.ID}}\t{{.Names}}\t{{.Status}}\t{{.Ports}}' || true
# Stop anything holding 27017 (stale mongo / leftover container)
docker ps -aq --filter publish=27017 | xargs -r docker rm -f
# Also common name
docker rm -f ontocode-mongo 2>/dev/null || true
# Host mongod if any
if command -v systemctl >/dev/null 2>&1; then
  sudo systemctl stop mongod 2>/dev/null || true
fi
sudo fuser -k 27017/tcp 2>/dev/null || true
sleep 2
sudo ss -ltnp | grep 27017 || echo "[remote] :27017 free"

echo "[remote] compose up"
if [[ -f docker-compose.de.ec2.yml ]]; then
  DOCKER_REGISTRY="$REG" VERSION="$VER" docker compose -f docker-compose.yml -f docker-compose.de.ec2.yml up -d --pull always
  DOCKER_REGISTRY="$REG" VERSION="$VER" docker compose -f docker-compose.yml -f docker-compose.de.ec2.yml ps
else
  DOCKER_REGISTRY="$REG" VERSION="$VER" docker compose -f docker-compose.yml up -d --pull always
  docker compose -f docker-compose.yml ps
fi

echo "[remote] vscode bundle"
cd ontology-vscode-extension
npm install --no-fund --no-audit
npm install --no-save webpack-bundle-analyzer --no-fund --no-audit || true
if [[ -d webview-src ]]; then
  (cd webview-src && npm install --no-fund --no-audit && npm install --no-save @rollup/rollup-linux-x64-gnu --no-fund --no-audit || true)
fi
if [[ -n "${DEV_API_BASE:-}" ]]; then
  printf 'VITE_API_BASE_URL=%s\n' "$DEV_API_BASE" > .env.dev-release
fi
ENV_FILE=.env.dev-release npm run bundle:all
echo "[remote] VSIX artifacts:"
find . -maxdepth 2 -name '*.vsix' -type f -printf '%p %s\n' 2>/dev/null || true

echo "[remote] FINISH OK"
REMOTE
