#!/usr/bin/env bash
# Upload available desktop installers from electron-app/dist-electron to a mode's downloads API.
# Usage: ./scripts/upload-desktop-installers.sh <dev|prod>
# Env: sources .env.deploy (DEV_API_BASE / API_BASE, ADMIN_USER, ADMIN_PASSWORD).
# Never prints secrets.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODE="${1:-}"
if [[ "$MODE" != "dev" && "$MODE" != "prod" ]]; then
  echo "Usage: $0 <dev|prod>" >&2
  exit 1
fi

if [[ -f "$ROOT/.env.deploy" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env.deploy"
  set +a
fi

if [[ "$MODE" == "dev" ]]; then
  API_BASE="${DEV_API_BASE:?Set DEV_API_BASE for mode=dev}"
else
  API_BASE="${API_BASE:-https://ontocodeapi.selfresearch.org}"
fi
API_BASE="${API_BASE%/}"

if [[ -z "${ADMIN_USER:-}" || -z "${ADMIN_PASSWORD:-}" ]]; then
  echo "ERROR: Set ADMIN_USER and ADMIN_PASSWORD for desktop upload" >&2
  exit 1
fi

DIST="$ROOT/electron-app/dist-electron"
VERSION=""
if [[ -f "$ROOT/electron-app/package.json" ]]; then
  VERSION=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$ROOT/electron-app/package.json" | head -1)
fi
RELEASE_NOTES="Beta ${VERSION:-unknown} - in-app update testing"

upload_one() {
  local platform="$1" file_path="$2"
  local filename bytes mb token
  filename="$(basename "$file_path")"
  bytes=$(wc -c <"$file_path" | tr -d ' ')
  mb=$(( bytes / 1024 / 1024 ))
  echo "[progress] uploading $filename ($mb MiB) → $platform ($MODE)"
  token=$(curl -sf --connect-timeout 15 --max-time 60 -X POST "$API_BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASSWORD\"}" \
    | sed -n 's/.*"jwt"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
  if [[ -z "$token" ]]; then
    echo "ERROR: login to API failed — no jwt" >&2
    return 1
  fi
  curl -f --connect-timeout 30 --max-time 3600 -X POST "$API_BASE/api/downloads/upload" \
    -H "Authorization: Bearer $token" \
    -F "platform=$platform" \
    -F "filename=$filename" \
    -F "version=${VERSION:-}" \
    -F "releaseNotes=$RELEASE_NOTES" \
    -F "file=@$file_path"
  echo ""
  echo "   Public download: $API_BASE/api/downloads/$platform"
}

pick_newest() {
  # Args: glob patterns relative to DIST; prints newest existing file path or nothing.
  local f newest="" newest_m=0 m
  for f in "$@"; do
    [[ -f "$f" ]] || continue
    # skip blockmaps / side artifacts
    case "$f" in
      *.blockmap) continue ;;
    esac
    m=$(stat -c %Y "$f" 2>/dev/null || stat -f %m "$f" 2>/dev/null || echo 0)
    if [[ "$m" -ge "$newest_m" ]]; then
      newest="$f"
      newest_m="$m"
    fi
  done
  [[ -n "$newest" ]] && printf '%s\n' "$newest"
}

fail=0
shopt -s nullglob

# Windows NSIS: "OntoCode Setup 1.1.0-beta.18.exe" (no arch token in name).
# Also accept legacy *x64* names if ever produced.
win_file="$(pick_newest "$DIST"/*[Ss]etup*.exe "$DIST"/*Setup*x64*.exe || true)"
if [[ -n "${win_file:-}" ]]; then
  upload_one "windows-x64" "$win_file" || fail=1
else
  echo "[progress] no Windows Setup .exe found under $DIST — skip windows-x64"
fi

dmg_arm="$(pick_newest "$DIST"/*arm64*.dmg || true)"
[[ -n "${dmg_arm:-}" ]] && { upload_one "mac-arm64" "$dmg_arm" || fail=1; }

dmg_x64="$(pick_newest "$DIST"/*x64*.dmg || true)"
[[ -n "${dmg_x64:-}" ]] && { upload_one "mac-x64" "$dmg_x64" || fail=1; }

appimage="$(pick_newest "$DIST"/*.AppImage || true)"
[[ -n "${appimage:-}" ]] && { upload_one "linux-x64" "$appimage" || fail=1; }

deb="$(pick_newest "$DIST"/*.deb || true)"
[[ -n "${deb:-}" ]] && { upload_one "linux-deb" "$deb" || fail=1; }

shopt -u nullglob

if [[ $fail -ne 0 ]]; then
  echo "ERROR: one or more desktop uploads failed ($MODE)" >&2
  exit 1
fi
echo "[progress] desktop uploads OK ($MODE)"
exit 0
