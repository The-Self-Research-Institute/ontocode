#!/usr/bin/env bash
# =============================================================================
# OntoCode — build, push (sindhujacoretopia), deploy EC2, optional desktop upload
#
# Usage (from repo root):
#   ./scripts/deploy-coretopia-release.sh                    # build+push+deploy default services
#   ./scripts/deploy-coretopia-release.sh --push-only auth editor web
#   ./scripts/deploy-coretopia-release.sh --deploy-only
#   ./scripts/deploy-coretopia-release.sh --desktop-win --upload-desktop
#   ./scripts/deploy-coretopia-release.sh --include fuseki    # only if Fuseki image changed
#
# Environment (optional overrides):
#   REGISTRY=sindhujacoretopia  VERSION=latest
#   EC2_HOST=ubuntu@your-ec2-ip   EC2_DIR=/opt/ontocode
#   API_BASE=https://ontocodeapi.selfresearch.org
#   ADMIN_USER=admin@coretopia.com  ADMIN_PASSWORD=...
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

REGISTRY="${REGISTRY:-sindhujacoretopia}"
VERSION="${VERSION:-latest}"
EC2_HOST="${EC2_HOST:-}"
EC2_DIR="${EC2_DIR:-/opt/ontocode}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.production.yml}"
API_BASE="${API_BASE:-https://ontocodeapi.selfresearch.org}"
ADMIN_USER="${ADMIN_USER:-}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"

# Default: services touched by recent web/auth/editor UI fixes.
# Skip mongo (image), plugin, swrl, fuseki unless you pass them explicitly.
DEFAULT_SERVICES=(auth gateway editor web)

DO_PUSH=true
DO_DEPLOY=true
DO_DESKTOP_WIN=false
DO_DESKTOP_MAC=false
DO_DESKTOP_LINUX=false
DO_UPLOAD_DESKTOP=false
SERVICES=()

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --push-only) DO_DEPLOY=false; shift ;;
    --deploy-only) DO_PUSH=false; shift ;;
    --desktop-win) DO_DESKTOP_WIN=true; shift ;;
    --desktop-mac) DO_DESKTOP_MAC=true; shift ;;
    --desktop-linux) DO_DESKTOP_LINUX=true; shift ;;
    --upload-desktop) DO_UPLOAD_DESKTOP=true; shift ;;
    --include) shift; SERVICES+=("$1"); shift ;;
    auth|gateway|editor|reasoner-worker|web|fuseki|graphdb|swrl|plugin|plugin-init)
      SERVICES+=("$1"); shift ;;
    *) echo "Unknown arg: $1"; usage 1 ;;
  esac
done

if [[ ${#SERVICES[@]} -eq 0 ]]; then
  SERVICES=("${DEFAULT_SERVICES[@]}")
fi

echo "============================================"
echo " OntoCode release — $REGISTRY:$VERSION"
echo " Push services : ${SERVICES[*]}"
echo " Deploy EC2    : $DO_DEPLOY (${EC2_HOST:-<set EC2_HOST>})"
echo "============================================"

# ── 1. Build & push Docker images ─────────────────────────────────────────────
if $DO_PUSH; then
  if [[ ! -x ./build-and-push.sh ]]; then
    chmod +x ./build-and-push.sh
  fi
  echo ""
  echo ">> Building and pushing: ${SERVICES[*]}"
  ./build-and-push.sh "$REGISTRY" "$VERSION" "${SERVICES[@]}"
fi

# ── 2. Deploy on EC2 (pull + up changed services) ─────────────────────────────
if $DO_DEPLOY; then
  if [[ -z "$EC2_HOST" ]]; then
    echo ""
    echo ">> Skipping EC2 deploy (EC2_HOST not set)."
    echo "   Example: EC2_HOST=ubuntu@ec2-xx-xx-xx-xx.compute.amazonaws.com \\"
    echo "            ./scripts/deploy-coretopia-release.sh --deploy-only"
  else
    # Map build script names → docker compose service names
    compose_services=()
    for s in "${SERVICES[@]}"; do
      case "$s" in
        editor)  compose_services+=(owl-editor) ;;
        reasoner-worker) compose_services+=(reasoner-worker) ;;
        auth)    compose_services+=(ontology-auth) ;;
        gateway) compose_services+=(gateway) ;;
        web)     compose_services+=(ontocode-web) ;;
        swrl)    compose_services+=(swrl-service) ;;
        plugin)  compose_services+=(plugin-service plugin-init) ;;
        fuseki)  compose_services+=(fuseki) ;;
        *)       echo "WARN: no compose mapping for '$s' — add manually on EC2" ;;
      esac
    done

    if [[ ${#compose_services[@]} -eq 0 ]]; then
      echo ">> No compose services to restart."
    else
      PULL_LINE=""
      UP_LINE=""
      for cs in "${compose_services[@]}"; do
        PULL_LINE+="docker compose -f $COMPOSE_FILE pull $cs && "
        UP_LINE+="docker compose -f $COMPOSE_FILE up -d $cs && "
      done
      REMOTE="cd $EC2_DIR && ${PULL_LINE% && } && ${UP_LINE% && } && docker compose -f $COMPOSE_FILE ps"
      echo ""
      echo ">> Deploying on $EC2_HOST ..."
      ssh "$EC2_HOST" "$REMOTE"
    fi
  fi
fi

# ── 3. Desktop installers (local build) ───────────────────────────────────────
build_desktop() {
  local platform="$1"
  cd "$ROOT/electron-app"
  case "$platform" in
    win)   npm run dist:win ;;
    mac)   npm run dist:mac ;;
    linux) npm run dist:linux ;;
  esac
  cd "$ROOT"
}

if $DO_DESKTOP_WIN; then
  echo ""
  echo ">> Building Windows installer (NSIS)..."
  build_desktop win
  echo "   Output: electron-app/dist-electron/"
  ls -la "$ROOT/electron-app/dist-electron/"*.exe 2>/dev/null || true
fi

if $DO_DESKTOP_MAC; then
  echo ""
  echo ">> Building macOS DMG (requires macOS + Xcode tools for best results)..."
  build_desktop mac
  ls -la "$ROOT/electron-app/dist-electron/"*.dmg 2>/dev/null || true
fi

if $DO_DESKTOP_LINUX; then
  echo ""
  echo ">> Building Linux AppImage + deb..."
  build_desktop linux
  ls -la "$ROOT/electron-app/dist-electron/"*.{AppImage,deb} 2>/dev/null || true
fi

# ── 4. Upload installers to API (GridFS — served by auth on EC2) ─────────────
upload_installer() {
  local platform="$1"
  local file_path="$2"
  local filename="$3"

  if [[ -z "$ADMIN_USER" || -z "$ADMIN_PASSWORD" ]]; then
    echo "ERROR: Set ADMIN_USER and ADMIN_PASSWORD for --upload-desktop"
    return 1
  fi

  echo ">> Logging in to $API_BASE ..."
  local token
  token=$(curl -sf -X POST "$API_BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASSWORD\"}" \
    | sed -n 's/.*"jwt"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

  if [[ -z "$token" ]]; then
    echo "ERROR: login failed — no jwt in response"
    return 1
  fi

  echo ">> Uploading $filename → platform=$platform"
  curl -f -X POST "$API_BASE/api/downloads/upload" \
    -H "Authorization: Bearer $token" \
    -F "platform=$platform" \
    -F "filename=$filename" \
    -F "file=@$file_path"
  echo ""
  echo "   Public download: $API_BASE/api/downloads/$platform"
}

if $DO_UPLOAD_DESKTOP; then
  DIST="$ROOT/electron-app/dist-electron"
  shopt -s nullglob
  win=( "$DIST"/*Setup*x64*.exe )
  if [[ ${#win[@]} -gt 0 ]]; then
    WIN_EXE=$(ls -t "${win[@]}" | head -1)
    upload_installer "windows-x64" "$WIN_EXE" "$(basename "$WIN_EXE")"
  fi
  dmg_arm=( "$DIST"/*arm64*.dmg )
  if [[ ${#dmg_arm[@]} -gt 0 ]]; then
    upload_installer "mac-arm64" "$(ls -t "${dmg_arm[@]}" | head -1)" "$(basename "$(ls -t "${dmg_arm[@]}" | head -1)")"
  fi
  dmg_x64=( "$DIST"/*x64*.dmg )
  if [[ ${#dmg_x64[@]} -gt 0 ]]; then
    upload_installer "mac-x64" "$(ls -t "${dmg_x64[@]}" | head -1)" "$(basename "$(ls -t "${dmg_x64[@]}" | head -1)")"
  fi
  appimages=( "$DIST"/*.AppImage )
  if [[ ${#appimages[@]} -gt 0 ]]; then
    upload_installer "linux-x64" "$(ls -t "${appimages[@]}" | head -1)" "$(basename "$(ls -t "${appimages[@]}" | head -1)")"
  fi
  debs=( "$DIST"/*.deb )
  if [[ ${#debs[@]} -gt 0 ]]; then
    upload_installer "linux-deb" "$(ls -t "${debs[@]}" | head -1)" "$(basename "$(ls -t "${debs[@]}" | head -1)")"
  fi
  shopt -u nullglob
fi

echo ""
echo "Done."
