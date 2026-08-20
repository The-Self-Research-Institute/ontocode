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

echo "[remote] patch mongo host port 27017 -> 27027 (avoid ghost bind)"
for f in docker-compose.de.ec2.yml "$HOME/docker-compose.de.ec2.yml" docker-compose.yml; do
  if [[ -f "$f" ]]; then
    cp -a "$f" "$f.bak.ports"
    sed -i -E 's/"127\.0\.0\.1:27017:27017"/"127.0.0.1:27027:27017"/g; s/"27017:27017"/"127.0.0.1:27027:27017"/g' "$f"
    echo "  patched $f"
    grep -n '2701' "$f" | head -n 5 || true
  fi
done

docker rm -f ontocode-mongo 2>/dev/null || true
# Clear leftover docker publish state
sudo iptables -t nat -L DOCKER -n 2>/dev/null | grep 27017 || true

COMPOSE=(docker compose -f docker-compose.yml)
[[ -f docker-compose.de.ec2.yml ]] && COMPOSE+=(-f docker-compose.de.ec2.yml)

echo "[remote] compose up"
DOCKER_REGISTRY="$REG" VERSION="$VER" "${COMPOSE[@]}" up -d --pull always
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
