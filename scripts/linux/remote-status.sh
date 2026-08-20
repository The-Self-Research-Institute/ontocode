#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
set -a; source ./.env.deploy; set +a
chmod 600 "$DEV_SSH_KEY" 2>/dev/null || true
ssh -o BatchMode=yes -i "$DEV_SSH_KEY" "$DEV_EC2_HOST" bash -s <<'REMOTE'
set -e
echo '=== ps ==='
docker ps -a --format 'table {{.Names}}\t{{.Status}}' | head -40
echo '=== fuseki logs ==='
docker logs ontocode-fuseki --tail 50 2>&1 || true
echo '=== fuseki health ==='
docker inspect ontocode-fuseki --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' 2>&1 || true
echo '=== mongo ==='
docker inspect ontocode-mongo --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' 2>&1 || true
REMOTE
