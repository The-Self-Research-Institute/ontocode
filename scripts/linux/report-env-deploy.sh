#!/usr/bin/env bash

set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
if [[ ! -f .env.deploy ]]; then
  echo "MISSING_FILE .env.deploy"
  exit 1
fi
set -a
# shellcheck disable=SC1091
source ./.env.deploy
set +a

check() {
  local k="$1" mode="${2:-req}"
  local v="${!k-}"
  if [[ -n "$v" ]]; then
    echo "OK $k"
  elif [[ "$mode" == "opt" ]]; then
    echo "OPTIONAL_EMPTY $k"
  else
    echo "MISSING_OR_EMPTY $k"
  fi
}

echo "=== required for --mode dev (remote web/desktop + local VSIX) ==="
check DEV_EC2_HOST
check DEV_API_BASE
check ADMIN_USER
check ADMIN_PASSWORD
check GIT_USERNAME
check GIT_TOKEN
check DOCKER_USERNAME
check DOCKER_PASSWORD
echo "=== optional for --mode dev ==="
check DEV_SSH_KEY opt
check DEV_REGISTRY opt
check DEV_VERSION opt
check DEV_EC2_DIR opt
echo "=== required for --mode prod (remote web/desktop) ==="
check EC2_HOST
check GIT_USERNAME
check GIT_TOKEN
check DOCKER_USERNAME
check DOCKER_PASSWORD
echo "=== optional for --mode prod ==="
check API_BASE opt
check PROD_SSH_KEY opt
check PROD_EC2_DIR opt
check PROD_REGISTRY opt
check PROD_VERSION opt
check EC2_DIR opt
echo "=== shared optional ==="
check ADMIN_USER opt
check ADMIN_PASSWORD opt
