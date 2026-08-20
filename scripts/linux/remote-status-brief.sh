#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
set -a; source ./.env.deploy; set +a
chmod 600 "$DEV_SSH_KEY" 2>/dev/null || true
DIR="${DEV_EC2_DIR:-${EC2_DIR:-/home/ubuntu/ontocode}}"
ssh -o BatchMode=yes -o ConnectTimeout=15 -i "$DEV_SSH_KEY" "$DEV_EC2_HOST" bash -s <<REMOTE
set -e
cd "$DIR"
echo "=== containers ==="
docker ps -a --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}' | head -40
echo "=== vsix ==="
find ontology-vscode-extension -maxdepth 2 -name '*.vsix' -type f -printf '%p %s bytes\n' 2>/dev/null || echo '(none)'
echo "=== HEAD ==="
git rev-parse --short HEAD 2>/dev/null; git rev-parse --abbrev-ref HEAD 2>/dev/null
REMOTE
