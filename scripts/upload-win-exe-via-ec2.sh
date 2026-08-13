#!/usr/bin/env bash
# SCP newest Windows Setup .exe to the mode's EC2 host, then curl-upload from there
# to that mode's downloads API (same auth/flow as deploy-coretopia-release.sh).
#
# Usage: ./scripts/upload-win-exe-via-ec2.sh <dev|prod>
# Why via EC2: home-network curl of ~700MiB often dies mid-upload; EC2→API is reliable.
# Never prints secrets.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODE="${1:-}"
if [[ "$MODE" != "dev" && "$MODE" != "prod" ]]; then
  echo "Usage: $0 <dev|prod>" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source ./.env.deploy
set +a

: "${ADMIN_USER:?Set ADMIN_USER in .env.deploy}"
: "${ADMIN_PASSWORD:?Set ADMIN_PASSWORD in .env.deploy}"

if [[ "$MODE" == "dev" ]]; then
  HOST="${DEV_EC2_HOST:?Set DEV_EC2_HOST}"
  DIR="${DEV_EC2_DIR:-${EC2_DIR:-/home/ubuntu/ontocode}}"
  KEY="${DEV_SSH_KEY:-}"
  API_BASE="${DEV_API_BASE:?Set DEV_API_BASE}"
else
  HOST="${EC2_HOST:?Set EC2_HOST}"
  DIR="${PROD_EC2_DIR:-${EC2_DIR:-/opt/ontocode}}"
  KEY="${PROD_SSH_KEY:-}"
  API_BASE="${API_BASE:-https://ontocodeapi.selfresearch.org}"
fi
API_BASE="${API_BASE%/}"

DIST="$ROOT/electron-app/dist-electron"
FILE="$(ls -t "$DIST"/*[Ss]etup*.exe 2>/dev/null | grep -v '\.blockmap$' | head -1 || true)"
[[ -n "${FILE:-}" && -f "$FILE" ]] || { echo "ERROR: no Setup exe under $DIST" >&2; exit 1; }
FILENAME="$(basename "$FILE")"
BYTES=$(wc -c <"$FILE" | tr -d ' ')
MB=$(( BYTES / 1024 / 1024 ))
REMOTE_TMP="$DIR/.upload-tmp"
REMOTE_NAME="OntoCode-Setup-upload.exe"
REMOTE_PATH="${REMOTE_TMP}/${REMOTE_NAME}"

ssh_opts=(-o BatchMode=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=accept-new
  -o ServerAliveInterval=30 -o ServerAliveCountMax=120)
RSYNC_RSH="ssh -o BatchMode=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30 -o ServerAliveCountMax=120"
if [[ -n "$KEY" ]]; then
  [[ -f "$KEY" ]] || { echo "ERROR: SSH key path set but file missing" >&2; exit 1; }
  chmod 600 "$KEY" 2>/dev/null || true
  ssh_opts+=(-i "$KEY")
  RSYNC_RSH="$RSYNC_RSH -i $KEY"
fi

echo "Mode=$MODE File=$FILENAME ($MB MiB)"
echo "API_BASE len=${#API_BASE} remote=$REMOTE_PATH"
ssh "${ssh_opts[@]}" "$HOST" "mkdir -p '$REMOTE_TMP'"
# Prefer rsync (resume-friendly); space-free remote name avoids OpenSSH "ambiguous target"
if command -v rsync >/dev/null 2>&1; then
  echo ">>> rsync -P to EC2..."
  rsync -avP --partial --compress --timeout=120 -e "$RSYNC_RSH" \
    "$FILE" "${HOST}:${REMOTE_PATH}"
else
  echo ">>> scp -C to EC2 (no rsync)..."
  scp -C "${ssh_opts[@]}" "$FILE" "${HOST}:${REMOTE_PATH}"
fi
echo ">>> transfer OK — uploading from EC2..."

ssh "${ssh_opts[@]}" "$HOST" \
  "export API_BASE=$(printf %q "$API_BASE") ADMIN_USER=$(printf %q "$ADMIN_USER") ADMIN_PASSWORD=$(printf %q "$ADMIN_PASSWORD") REMOTE_FILE=$(printf %q "$REMOTE_PATH") FILENAME=$(printf %q "$FILENAME") DIR=$(printf %q "$DIR"); bash -s" <<'REMOTE'
set -euo pipefail
BYTES=$(wc -c <"$REMOTE_FILE" | tr -d ' ')
MB=$(( BYTES / 1024 / 1024 ))
echo "[ec2] file=$FILENAME size=${MB}MiB"
TOKEN=$(curl -sf --connect-timeout 15 --max-time 60 -X POST "$API_BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASSWORD\"}" \
  | sed -n 's/.*"jwt"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
[[ -n "$TOKEN" ]] || { echo "ERROR: login failed" >&2; exit 1; }
echo "[ec2] login OK"
VERSION=$(python3 -c "import json; print(json.load(open(\"$DIR/electron-app/package.json\")).get('version',''))" 2>/dev/null || true)
curl -f --connect-timeout 30 --max-time 3600 -X POST "$API_BASE/api/downloads/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "platform=windows-x64" \
  -F "filename=$FILENAME" \
  -F "version=${VERSION:-}" \
  -F "releaseNotes=Beta ${VERSION:-unknown} - in-app update testing" \
  -F "file=@$REMOTE_FILE"
echo ""
echo "[ec2] UPLOAD_OK"
echo "[ec2] Public download: $API_BASE/api/downloads/windows-x64"
rm -f "$REMOTE_FILE" || true
REMOTE

echo "UPLOAD_OK"
echo "Public download: $API_BASE/api/downloads/windows-x64"
