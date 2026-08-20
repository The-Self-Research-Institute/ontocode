#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
set -a; source ./.env.deploy; set +a
chmod 600 "$DEV_SSH_KEY" 2>/dev/null || true
DIR="${DEV_EC2_DIR:-${EC2_DIR:-/home/ubuntu/ontocode}}"
REG="${DEV_REGISTRY:-ontocode}"
VER="${DEV_VERSION:-dev}"

ssh -o BatchMode=yes -i "$DEV_SSH_KEY" "$DEV_EC2_HOST" \
  "export DEV_API_BASE=$(printf %q "${DEV_API_BASE:-}") DIR=$(printf %q "$DIR") REG=$(printf %q "$REG") VER=$(printf %q "$VER"); bash -s" <<'REMOTE'
set -euo pipefail
cd "$DIR"

echo "[remote] fix fuseki duplicate /ontocode config"
# List config files naming ontocode
docker exec ontocode-fuseki sh -c 'ls -la /fuseki/configuration 2>/dev/null | head -50' || true
docker exec ontocode-fuseki sh -c 'grep -l "ontocode\|#dsName\|fuseki:name" /fuseki/configuration/*.ttl 2>/dev/null' || true

# Common cause: both ontocode.ttl and a copy register the same service name.
# Keep ontocode.ttl; move other files that declare name </ontocode> aside.
docker exec ontocode-fuseki sh -c '
  mkdir -p /fuseki/configuration/disabled-dupes
  for f in /fuseki/configuration/*.ttl; do
    base=$(basename "$f")
    [ "$base" = "ontocode.ttl" ] && continue
    if grep -Eq "fuseki:name +</ontocode>|fuseki:name \"/ontocode\"" "$f" 2>/dev/null; then
      echo "disable dupe $base"
      mv "$f" /fuseki/configuration/disabled-dupes/
    fi
  done
  ls -la /fuseki/configuration
'

docker restart ontocode-fuseki
echo "[remote] waiting for fuseki health..."
for i in $(seq 1 30); do
  st=$(docker inspect ontocode-fuseki --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" 2>/dev/null || echo missing)
  echo "  try $i: $st"
  [[ "$st" == "healthy" ]] && break
  # if crash-loop, show logs once
  if [[ "$st" == "unhealthy" || "$st" == "exited" ]] && [[ $i -eq 5 ]]; then
    docker logs ontocode-fuseki --tail 20 2>&1 || true
  fi
  sleep 5
done

COMPOSE=(docker compose -f docker-compose.yml)
[[ -f docker-compose.de.ec2.yml ]] && COMPOSE+=(-f docker-compose.de.ec2.yml)

echo "[remote] start remaining services"
DOCKER_REGISTRY="$REG" VERSION="$VER" "${COMPOSE[@]}" up -d --no-deps \
  auth gateway editor swrl reasoner-worker plugin plugin-init web || true
# full up to wire depends_on once fuseki ok
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
