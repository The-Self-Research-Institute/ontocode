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

echo "[remote] deep clean :27017"
docker rm -f ontocode-mongo 2>/dev/null || true
# Kill docker-proxy / any listener on 27017
for pid in $(sudo lsof -t -iTCP:27017 -sTCP:LISTEN 2>/dev/null || true); do
  echo "  killing PID $pid"
  sudo kill -9 "$pid" 2>/dev/null || true
done
sudo fuser -k 27017/tcp 2>/dev/null || true
# Stale docker networks sometimes leave proxies
docker network prune -f >/dev/null 2>&1 || true
sleep 2
echo "  listeners now:"
sudo ss -ltnp | grep 27017 || echo "  (none)"

# Prefer internal-only mongo bind if host port still contested:
# write a tiny override that drops host publish (compose network still works).
cat > /tmp/ontocode-mongo-noport.yml <<'YAML'
services:
  mongo:
    ports: !reset []
YAML
# Compose v2 may not support !reset — fallback: map unused host port
cat > /tmp/ontocode-mongo-altport.yml <<'YAML'
services:
  mongo:
    ports:
      - "127.0.0.1:27027:27017"
YAML

COMPOSE=(docker compose -f docker-compose.yml)
[[ -f docker-compose.de.ec2.yml ]] && COMPOSE+=(-f docker-compose.de.ec2.yml)

echo "[remote] compose up (try normal)"
if ! DOCKER_REGISTRY="$REG" VERSION="$VER" "${COMPOSE[@]}" up -d --pull always; then
  echo "[remote] normal up failed — retry mongo on 27027 host port"
  DOCKER_REGISTRY="$REG" VERSION="$VER" "${COMPOSE[@]}" -f /tmp/ontocode-mongo-altport.yml up -d --pull always
fi

DOCKER_REGISTRY="$REG" VERSION="$VER" "${COMPOSE[@]}" ps

echo "[remote] vscode"
cd ontology-vscode-extension
npm install --no-fund --no-audit
npm install --no-save webpack-bundle-analyzer --no-fund --no-audit || true
[[ -d webview-src ]] && (cd webview-src && npm install --no-fund --no-audit || true)
[[ -n "${DEV_API_BASE:-}" ]] && printf 'VITE_API_BASE_URL=%s\n' "$DEV_API_BASE" > .env.dev-release
ENV_FILE=.env.dev-release npm run bundle:all
find . -maxdepth 2 -name '*.vsix' -type f -printf '%p %s bytes\n' || true
echo "[remote] ALL DONE"
REMOTE
