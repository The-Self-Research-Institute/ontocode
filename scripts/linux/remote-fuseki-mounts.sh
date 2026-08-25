#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
set -a; source ./.env.deploy; set +a
chmod 600 "$DEV_SSH_KEY" 2>/dev/null || true
ssh -o BatchMode=yes -i "$DEV_SSH_KEY" "$DEV_EC2_HOST" bash -s <<'REMOTE'
set -e
echo '=== fuseki mounts ==='
docker inspect ontocode-fuseki --format '{{range .Mounts}}{{.Type}} {{.Source}} -> {{.Destination}}{{println}}{{end}}'
echo '=== find ontocode.ttl on host ==='
sudo find /var/lib/docker/volumes /home/ubuntu -name 'ontocode.ttl' 2>/dev/null | head -20
REMOTE
