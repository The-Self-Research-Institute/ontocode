#!/usr/bin/env bash
set -euo pipefail

if [ "${1:-}" = "" ]; then
  echo "Usage:"
  echo "  bash scripts/delete-user-complete.sh user@example.com"
  echo
  echo "Dry-run is the default. To delete permanently:"
  echo "  bash scripts/delete-user-complete.sh user@example.com --execute --yes"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
node "$SCRIPT_DIR/../delete-user-complete.js" "$@"
