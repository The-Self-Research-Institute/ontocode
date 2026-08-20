#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
set -a
# shellcheck disable=SC1091
source ./.env.deploy
set +a
ssh_opts=(-o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new)
[[ -n "${DEV_SSH_KEY:-}" && -f "$DEV_SSH_KEY" ]] && ssh_opts+=(-i "$DEV_SSH_KEY")
chmod 600 "$DEV_SSH_KEY" 2>/dev/null || true

ssh "${ssh_opts[@]}" "$DEV_EC2_HOST" 'bash -s' <<'REMOTE'
set -e
echo "=== /opt ==="
sudo ls -la /opt 2>/dev/null | head -40 || ls -la /opt 2>/dev/null | head -40
echo "=== home ==="
ls -la ~ | head -25
echo "=== find ontocode dirs ==="
find /home /opt /var/www -maxdepth 4 -type d -iname '*ontocode*' 2>/dev/null | head -30
echo "=== find compose files ==="
find /home /opt -maxdepth 5 \( -name 'docker-compose*.yml' -o -name 'compose*.yml' \) 2>/dev/null | head -30
echo "=== running containers ==="
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}' 2>/dev/null | head -40
echo "=== disk/mem ==="
df -h / | tail -1
nproc
REMOTE
