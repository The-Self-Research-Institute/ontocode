#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
set -a; source ./.env.deploy; set +a
chmod 600 "$DEV_SSH_KEY" 2>/dev/null || true
ssh -o BatchMode=yes -i "$DEV_SSH_KEY" "$DEV_EC2_HOST" bash -s <<'REMOTE'
set -e
echo '=== ontocode.ttl ==='
docker exec ontocode-fuseki head -30 /fuseki/configuration/ontocode.ttl || true
echo '=== config.ttl ==='
docker exec ontocode-fuseki head -30 /config.ttl || true
echo '=== health ==='
docker inspect ontocode-fuseki --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}'
REMOTE
