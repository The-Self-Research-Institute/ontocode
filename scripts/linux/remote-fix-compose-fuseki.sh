#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
set -a; source ./.env.deploy; set +a
chmod 600 "$DEV_SSH_KEY" 2>/dev/null || true
DIR="${DEV_EC2_DIR:-${EC2_DIR:-/home/ubuntu/ontocode}}"
REG="${DEV_REGISTRY:-ontocode}"
VER="${DEV_VERSION:-dev}"

scp -o BatchMode=yes -i "$DEV_SSH_KEY" \
  "$ROOT/docker-compose.de.ec2.yml" \
  "${DEV_EC2_HOST}:${DIR}/docker-compose.de.ec2.yml"
scp -o BatchMode=yes -i "$DEV_SSH_KEY" \
  "$ROOT/docker-compose.de.ec2.yml" \
  "${DEV_EC2_HOST}:/home/ubuntu/docker-compose.de.ec2.yml" || true

ssh -o BatchMode=yes -i "$DEV_SSH_KEY" "$DEV_EC2_HOST" \
  "export DEV_API_BASE=$(printf %q "${DEV_API_BASE:-}") DIR=$(printf %q "$DIR") REG=$(printf %q "$REG") VER=$(printf %q "$VER"); bash -s" <<'REMOTE'
set -euo pipefail
cd "$DIR"

echo "[remote] recreate fuseki without duplicate config mount"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.de.ec2.yml)
DOCKER_REGISTRY="$REG" VERSION="$VER" "${COMPOSE[@]}" up -d --force-recreate fuseki
for i in $(seq 1 24); do
  st=$(docker inspect ontocode-fuseki --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}")
  echo "  fuseki=$st"
  [[ "$st" == "healthy" ]] && break
  [[ $i -eq 8 ]] && docker logs ontocode-fuseki --tail 20 2>&1 || true
  sleep 5
done

DOCKER_REGISTRY="$REG" VERSION="$VER" "${COMPOSE[@]}" up -d
DOCKER_REGISTRY="$REG" VERSION="$VER" "${COMPOSE[@]}" ps

echo "[remote] vscode"
cd ontology-vscode-extension
npm install --no-fund --no-audit
npm install --no-save webpack-bundle-analyzer --no-fund --no-audit || true
[[ -d webview-src ]] && (cd webview-src && npm install --no-fund --no-audit || true)
[[ -n "${DEV_API_BASE:-}" ]] && printf "VITE_API_BASE_URL=%s\n" "$DEV_API_BASE" > .env.dev-release
ENV_FILE=.env.dev-release npm run bundle:all
find . -maxdepth 2 -name "*.vsix" -type f -printf "%p %s bytes\n" || true
echo "[remote] ALL DONE"
REMOTE
