#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source ./.env.deploy; set +a
chmod 600 "$DEV_SSH_KEY"
DIR="${DEV_EC2_DIR:-/home/ubuntu/ontocode}"
ssh -o BatchMode=yes -i "$DEV_SSH_KEY" "$DEV_EC2_HOST" bash -s <<REMOTE
set -e
cd "$DIR"
echo "=== remote HEAD ==="
git rev-parse --short HEAD
echo "=== does remote source have Dim/Hide search UI? ==="
grep -n "searchFilterMode\|Dim vs Hide\|handleDeepDive" plugins/graph-view-plugin/src/AdvancedGraphView.tsx | head -20 || echo MISSING_IN_SOURCE
echo "=== plugin-init logs (last 80) ==="
docker logs ontocode-plugin-init --tail 80 2>&1 || true
echo "=== when was plugin-init image created ==="
docker image inspect ontocode/ontocode-plugin-init:dev --format 'Created={{.Created}} Id={{.Id}}' 2>&1 || true
REMOTE
