#!/usr/bin/env bash

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
set -a
# shellcheck disable=SC1091
source ./.env.deploy
set +a

HOST="${DEV_EC2_HOST:?}"
KEY="${DEV_SSH_KEY:-}"
DIR="${DEV_EC2_DIR:-${EC2_DIR:-/home/ubuntu/ontocode}}"
ssh_opts=(-o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new)
[[ -n "$KEY" && -f "$KEY" ]] && ssh_opts+=(-i "$KEY")

echo "Probing SSH to DEV host (user=${HOST%%@*}) dir=$DIR ..."
ssh "${ssh_opts[@]}" "$HOST" bash -s -- "$DIR" <<'REMOTE'
set -euo pipefail
DIR="$1"
echo "OK: ssh connected"
echo "hostname=$(hostname)"
echo "pwd=$(pwd)"
echo "docker=$(command -v docker || echo MISSING)"
docker info >/dev/null 2>&1 && echo "docker_daemon=OK" || echo "docker_daemon=FAIL"
echo "git=$(command -v git || echo MISSING)"
echo "node=$(command -v node || echo MISSING)"
echo "java=$(command -v java || echo MISSING)"
if [[ -d "$DIR" ]]; then
  echo "repo_dir=EXISTS $DIR"
  if [[ -d "$DIR/.git" ]]; then
    echo "git_repo=YES"
    git -C "$DIR" remote -v 2>/dev/null | head -n 2 | sed 's://*[^@]*@://***@/g' || true
    git -C "$DIR" rev-parse --abbrev-ref HEAD 2>/dev/null || true
    git -C "$DIR" rev-parse --short HEAD 2>/dev/null || true
  else
    echo "git_repo=NO"
    ls -la "$DIR" | head -n 15
  fi
else
  echo "repo_dir=MISSING $DIR"
fi
df -h / | tail -n 1
free -h | head -n 2
nproc
REMOTE
