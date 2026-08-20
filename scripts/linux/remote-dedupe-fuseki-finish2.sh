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

echo "[remote] stop fuseki, remove duplicate ontocode.ttl from volume"
docker stop ontocode-fuseki || true

# Find volume mount path on host
VOL=$(docker volume inspect "$(docker inspect ontocode-fuseki --format '{{range .Mounts}}{{if eq .Destination "/fuseki/databases"}}{{.Name}}{{end}}{{end}}')" -f '{{.Mountpoint}}' 2>/dev/null || true)
echo "databases vol mountpoint=$VOL"

# configuration may live beside databases or under /fuseki in another mount
# Use a temporary helper container with the fuseki volumes
docker rm -f fuseki-fix 2>/dev/null || true
# Reuse same image and mounts as fuseki
IMG=$(docker inspect ontocode-fuseki --format '{{.Config.Image}}')
docker create --name fuseki-fix --volumes-from ontocode-fuseki "$IMG" sleep 60 >/dev/null
docker start fuseki-fix >/dev/null
docker exec fuseki-fix sh -c '
  mkdir -p /fuseki/configuration/disabled-dupes
  if [ -f /fuseki/configuration/ontocode.ttl ]; then
    cp -a /fuseki/configuration/ontocode.ttl /fuseki/configuration/disabled-dupes/ontocode.ttl
    rm -f /fuseki/configuration/ontocode.ttl
    echo removed ontocode.ttl
  else
    echo ontocode.ttl already absent
  fi
  ls -la /fuseki/configuration
'
docker rm -f fuseki-fix >/dev/null

docker start ontocode-fuseki
for i in $(seq 1 24); do
  st=$(docker inspect ontocode-fuseki --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}")
  echo "  fuseki: $st"
  [[ "$st" == "healthy" ]] && break
  [[ $i -eq 8 ]] && docker logs ontocode-fuseki --tail 20 2>&1 || true
  sleep 5
done

COMPOSE=(docker compose -f docker-compose.yml)
[[ -f docker-compose.de.ec2.yml ]] && COMPOSE+=(-f docker-compose.de.ec2.yml)
DOCKER_REGISTRY="$REG" VERSION="$VER" "${COMPOSE[@]}" up -d
DOCKER_REGISTRY="$REG" VERSION="$VER" "${COMPOSE[@]}" ps

cd ontology-vscode-extension
npm install --no-fund --no-audit
npm install --no-save webpack-bundle-analyzer --no-fund --no-audit || true
[[ -d webview-src ]] && (cd webview-src && npm install --no-fund --no-audit || true)
[[ -n "${DEV_API_BASE:-}" ]] && printf "VITE_API_BASE_URL=%s\n" "$DEV_API_BASE" > .env.dev-release
ENV_FILE=.env.dev-release npm run bundle:all
find . -maxdepth 2 -name "*.vsix" -type f -printf "%p %s bytes\n" || true
echo "[remote] ALL DONE"
REMOTE
