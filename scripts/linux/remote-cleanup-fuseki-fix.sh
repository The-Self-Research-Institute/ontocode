#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source ./.env.deploy; set +a
chmod 600 "$DEV_SSH_KEY"
ssh -o BatchMode=yes -i "$DEV_SSH_KEY" "$DEV_EC2_HOST" 'docker rm -f fuseki-fix 2>/dev/null; echo cleaned'
