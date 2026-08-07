#!/usr/bin/env bash
# =============================================================================
# OntoCode — one command to release: docker (web), desktop installers, VS Code VSIX.
#
# deploy --mode dev|prod|all --changes <svc,svc,...|all> --platform <web,vscode,desktop|all>
#
# Every (mode × platform) combination runs as its OWN concurrent branch — e.g.
# --mode all --platform web,vscode fires 4 branches at once (dev-web, prod-web,
# dev-vscode, prod-vscode). Within one branch, steps that must be sequential still
# are (push before deploy, build before upload) — everything else fans out.
#
# --mode all runs dev AND prod fully in parallel, on purpose, per your call — there
# is NO gate requiring dev to succeed before prod starts. Use --mode dev first if
# you want that safety net.
#
# Flags (all three required):
#   --mode dev|prod|all
#   --changes <service>[,<service>...]|all
#         Known services: fuseki graphdb auth gateway editor reasoner-worker
#         swrl plugin plugin-init web
#         "all" expands to everything EXCEPT fuseki and graphdb — fuseki is a rarely-
#         changed base image, graphdb is a dead build target (no compose service uses
#         it anymore). Name either explicitly (--changes fuseki) to still build it.
#         mongo isn't ours to build at all, so it's never in this list.
#   --platform <web|vscode|desktop>[,...]|all
#         web     = build+push the --changes services, then SSH deploy (docker compose)
#         desktop = build win+linux installers, then upload them
#         vscode  = build+package the VS Code extension VSIX
#
# Environment:
#   dev:  DEV_EC2_HOST=user@host   DEV_API_BASE=https://...   (required for --mode dev/all)
#         DEV_REGISTRY (default ontocode)  DEV_VERSION (default dev)
#         DEV_SSH_KEY=/path/to/dev.pem   (optional — omit to use ssh-agent/default key)
#   prod: EC2_HOST=user@host       API_BASE=https://ontocodeapi.selfresearch.org (default)
#         PROD_REGISTRY (default sindhujacoretopia)  PROD_VERSION (default latest)
#         PROD_SSH_KEY=/path/to/prod.pem (optional — omit to use ssh-agent/default key)
#   Both: EC2_DIR=/opt/ontocode (default)
#   Desktop upload: ADMIN_USER=admin@coretopia.com  ADMIN_PASSWORD=...
#
# Examples:
#   # Deploy just the web service to dev
#   DEV_EC2_HOST=ubuntu@1.2.3.4 DEV_API_BASE=https://dev-api.example.com \
#     ./scripts/deploy-coretopia-release.sh --mode dev --changes web --platform web
#
#   # Full release, both environments, everything, all in parallel
#   ADMIN_USER=... ADMIN_PASSWORD=... EC2_HOST=ubuntu@<prod-ip> \
#   DEV_EC2_HOST=ubuntu@<dev-ip> DEV_API_BASE=https://<dev-api> \
#     ./scripts/deploy-coretopia-release.sh --mode all --changes all --platform all
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Local, gitignored config (DEV_EC2_HOST, DEV_API_BASE, EC2_HOST, ADMIN_PASSWORD, ...) —
# see .env.deploy for the template. Optional: everything still works via inline env vars
# if this file doesn't exist.
if [[ -f "$ROOT/.env.deploy" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT/.env.deploy"
fi

usage() {
  sed -n '2,48p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

ALL_SERVICES=(fuseki graphdb auth gateway editor reasoner-worker swrl plugin plugin-init web)
# --changes all expands to this, NOT to ALL_SERVICES:
#  - fuseki is a heavy, rarely-changed base image (custom Jena build)
#  - graphdb was removed — no docker-compose file references a graphdb service anymore,
#    it's a dead build target (Dockerfile.graphdb is a leftover from the pre-Fuseki setup)
#  - mongo isn't ours to build at all, so it's never in ALL_SERVICES either
# All three stay valid for explicit --changes use in case you still need one of them.
DEFAULT_ALL_SERVICES=(auth gateway editor reasoner-worker swrl plugin plugin-init web)
ALL_PLATFORMS=(web vscode desktop)
EC2_DIR="${EC2_DIR:-/opt/ontocode}"

MODE_ARG=""
CHANGES_ARG=""
PLATFORM_ARG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --mode) shift; MODE_ARG="${1:-}"; shift ;;
    --changes) shift; CHANGES_ARG="${1:-}"; shift ;;
    --platform) shift; PLATFORM_ARG="${1:-}"; shift ;;
    *) echo "Unknown arg: $1" >&2; usage 1 ;;
  esac
done

[[ -n "$MODE_ARG" ]]     || { echo "ERROR: --mode dev|prod|all is required" >&2; usage 1; }
[[ -n "$CHANGES_ARG" ]]  || { echo "ERROR: --changes <service,...>|all is required" >&2; usage 1; }
[[ -n "$PLATFORM_ARG" ]] || { echo "ERROR: --platform <web,vscode,desktop>|all is required" >&2; usage 1; }

case "$MODE_ARG" in
  all)  MODES=(dev prod) ;;
  dev|prod) MODES=("$MODE_ARG") ;;
  *) echo "ERROR: --mode must be dev, prod, or all" >&2; usage 1 ;;
esac

if [[ "$CHANGES_ARG" == "all" ]]; then
  SERVICES=("${DEFAULT_ALL_SERVICES[@]}")
else
  IFS=',' read -ra SERVICES <<< "$CHANGES_ARG"
  for s in "${SERVICES[@]}"; do
    if [[ ! " ${ALL_SERVICES[*]} " == *" $s "* ]]; then
      echo "ERROR: unknown service '$s' in --changes (known: ${ALL_SERVICES[*]})" >&2
      exit 1
    fi
  done
fi

if [[ "$PLATFORM_ARG" == "all" ]]; then
  PLATFORMS=("${ALL_PLATFORMS[@]}")
else
  IFS=',' read -ra PLATFORMS <<< "$PLATFORM_ARG"
  for p in "${PLATFORMS[@]}"; do
    if [[ ! " ${ALL_PLATFORMS[*]} " == *" $p "* ]]; then
      echo "ERROR: unknown platform '$p' in --platform (known: ${ALL_PLATFORMS[*]})" >&2
      exit 1
    fi
  done
fi

# ── Resolve per-mode config (explicit env vars still win over the built-in defaults) ──

declare -A REG VER HOST API CFLAGS VSIXFILE SSHKEY

resolve_mode() {
  local m="$1"
  case "$m" in
    dev)
      REG[dev]="${DEV_REGISTRY:-ontocode}"
      VER[dev]="${DEV_VERSION:-dev}"
      HOST[dev]="${DEV_EC2_HOST:?Set DEV_EC2_HOST (dev SSH target, e.g. ubuntu@1.2.3.4)}"
      API[dev]="${DEV_API_BASE:?Set DEV_API_BASE (dev API URL, e.g. https://dev-api.example.com)}"
      CFLAGS[dev]="-f docker-compose.yml "
      VSIXFILE[dev]=".env.dev-release"
      SSHKEY[dev]="${DEV_SSH_KEY:-}"
      ;;
    prod)
      REG[prod]="${PROD_REGISTRY:-sindhujacoretopia}"
      VER[prod]="${PROD_VERSION:-latest}"
      HOST[prod]="${EC2_HOST:?Set EC2_HOST (prod SSH target)}"
      API[prod]="${API_BASE:-https://ontocodeapi.selfresearch.org}"
      CFLAGS[prod]="-f docker-compose.production.yml -f docker-compose.r6i-xlarge.yml "
      VSIXFILE[prod]=".env.prod-release"
      SSHKEY[prod]="${PROD_SSH_KEY:-}"
      ;;
  esac
}
for m in "${MODES[@]}"; do resolve_mode "$m"; done

echo "============================================"
echo " OntoCode release"
echo " Modes     : ${MODES[*]}"
echo " Services  : ${SERVICES[*]}"
echo " Platforms : ${PLATFORMS[*]}"
if [[ "$MODE_ARG" == "all" ]]; then
  echo " NOTE: --mode all — dev and prod branches run in parallel, no dev-first gate."
fi
echo "============================================"

LOG_DIR="$(mktemp -d)"
declare -A BRANCH_PID

# ── web: build+push the requested services, then SSH deploy (sequential within branch) ──

branch_web() {
  local m="$1"
  ./build-and-push.sh "${REG[$m]}" "${VER[$m]}" "${SERVICES[@]}" || return 1
  local ssh_opts=(-o BatchMode=yes)
  if [[ -n "${SSHKEY[$m]}" ]]; then
    # Checked here, not eagerly in resolve_mode — the key path is host-specific
    # (e.g. a WSL-internal /home/... path is invalid from Git Bash on Windows), and
    # SSH is only actually needed for this platform, not desktop/vscode.
    if [[ ! -f "${SSHKEY[$m]}" ]]; then
      echo "ERROR: ${m^^}_SSH_KEY is set to '${SSHKEY[$m]}' but that file doesn't exist on this host" >&2
      return 1
    fi
    ssh_opts+=(-i "${SSHKEY[$m]}")
  fi
  ssh "${ssh_opts[@]}" "${HOST[$m]}" "cd $EC2_DIR && DOCKER_REGISTRY=${REG[$m]} VERSION=${VER[$m]} docker compose ${CFLAGS[$m]}up -d --pull always && docker compose ${CFLAGS[$m]}ps" || return 1
}

# ── desktop: only the platform(s) native to the CURRENT host, then uploaded ───────────
#
# Cross-building win from a Linux host requires Wine — confirmed unreliable specifically
# against files on a DrvFs-mounted Windows drive (/mnt/e/...): rcedit-ia32.exe died with
# signal:killed plus Wine internals errors (setupapi:do_file_copyW). Rather than keep
# fighting Wine-in-WSL, this only ever builds what's native to the host it's actually
# running on — same detection build-desktop.sh already uses. Run this script once from
# WSL/Linux (gets linux) and once from Windows Git Bash (gets win); each upload is
# independent so either can run without the other.
#
# win and linux would ALSO race if ever built together — `npm run dist:$platform`
# independently re-runs `prepare-resources` (which bundles mongod/JRE into the SAME
# `electron-app/resources/backend/` directory regardless of target platform), so two
# platforms at once corrupts the output (confirmed: FileAlreadyExistsException on
# jre/bin/java) even setting Wine aside.

host_desktop_platform() {
  case "$(uname -s)" in
    Darwin*)              echo mac ;;
    Linux*)               echo linux ;;
    MINGW*|MSYS*|CYGWIN*) echo win ;;
    *)                    echo linux ;;
  esac
}

build_desktop() {
  local platform="$1"
  ( cd "$ROOT/electron-app" && npm run "dist:$platform" )
}

upload_installer() {
  local api_base="$1" platform="$2" file_path="$3" filename="$4"
  if [[ -z "${ADMIN_USER:-}" || -z "${ADMIN_PASSWORD:-}" ]]; then
    echo "ERROR: Set ADMIN_USER and ADMIN_PASSWORD for the desktop platform" >&2
    return 1
  fi
  local token
  token=$(curl -sf -X POST "$api_base/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASSWORD\"}" \
    | sed -n 's/.*"jwt"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
  if [[ -z "$token" ]]; then
    echo "ERROR: login to $api_base failed — no jwt in response" >&2
    return 1
  fi
  echo ">> Uploading $filename → $api_base (platform=$platform)"
  curl -f -X POST "$api_base/api/downloads/upload" \
    -H "Authorization: Bearer $token" \
    -F "platform=$platform" -F "filename=$filename" -F "file=@$file_path"
  echo "   Public download: $api_base/api/downloads/$platform"
}

branch_desktop() {
  local m="$1"
  local host_platform
  host_platform="$(host_desktop_platform)"
  echo ">> Host is $host_platform — building only the $host_platform installer (no cross-build, no Wine)."
  if [[ "$host_platform" == "linux" ]]; then
    echo "   Run this same command from Windows Git Bash to also build+upload the win installer."
  fi
  build_desktop "$host_platform" || { echo "ERROR: desktop $host_platform build failed" >&2; return 1; }
  local fail=0

  local api_base="${API[$m]}"
  local DIST="$ROOT/electron-app/dist-electron"
  shopt -s nullglob
  local win=( "$DIST"/*Setup*x64*.exe )
  [[ ${#win[@]} -gt 0 ]] && { local f; f=$(ls -t "${win[@]}" | head -1); upload_installer "$api_base" "windows-x64" "$f" "$(basename "$f")" || fail=1; }
  local dmg_arm=( "$DIST"/*arm64*.dmg )
  [[ ${#dmg_arm[@]} -gt 0 ]] && { local f; f=$(ls -t "${dmg_arm[@]}" | head -1); upload_installer "$api_base" "mac-arm64" "$f" "$(basename "$f")" || fail=1; }
  local dmg_x64=( "$DIST"/*x64*.dmg )
  [[ ${#dmg_x64[@]} -gt 0 ]] && { local f; f=$(ls -t "${dmg_x64[@]}" | head -1); upload_installer "$api_base" "mac-x64" "$f" "$(basename "$f")" || fail=1; }
  local appimages=( "$DIST"/*.AppImage )
  [[ ${#appimages[@]} -gt 0 ]] && { local f; f=$(ls -t "${appimages[@]}" | head -1); upload_installer "$api_base" "linux-x64" "$f" "$(basename "$f")" || fail=1; }
  local debs=( "$DIST"/*.deb )
  [[ ${#debs[@]} -gt 0 ]] && { local f; f=$(ls -t "${debs[@]}" | head -1); upload_installer "$api_base" "linux-deb" "$f" "$(basename "$f")" || fail=1; }
  shopt -u nullglob
  return $fail
}

# ── vscode: build+package the VSIX pointed at that mode's API (fully independent) ────

branch_vscode() {
  local m="$1"
  local vsix_file="${VSIXFILE[$m]}"
  local api_base="${API[$m]}"
  ( cd "$ROOT/ontology-vscode-extension" && {
    if [[ "$m" == "dev" ]]; then
      # No static dev-release env file is committed (dev API host isn't hardcoded) —
      # generate it from DEV_API_BASE right before the build instead.
      cat > "$vsix_file" <<EOF
# Generated by deploy-coretopia-release.sh --mode dev — do not edit by hand
CLOUD_GATEWAY_URL=$api_base
CLOUD_EDITOR_URL=$api_base
CLOUD_PLUGIN_URL=$api_base:8087
EOF
    fi
    [[ -f "$vsix_file" ]] || { echo "ERROR: $vsix_file not found" >&2; exit 1; }
    ENV_FILE="$vsix_file" npm run bundle:all || exit 1
    npm run package || exit 1
  } )
}

# ── Launch one branch per (mode × platform) combination, all concurrently ────────────

for m in "${MODES[@]}"; do
  for p in "${PLATFORMS[@]}"; do
    key="$m-$p"
    log="$LOG_DIR/$key.log"
    case "$p" in
      web)     ( branch_web "$m" )     > "$log" 2>&1 & ;;
      desktop) ( branch_desktop "$m" ) > "$log" 2>&1 & ;;
      vscode)  ( branch_vscode "$m" )  > "$log" 2>&1 & ;;
    esac
    BRANCH_PID["$key"]=$!
  done
done

# Heartbeat while branches run silently in the background (their real output only
# goes to log files, to avoid garbling concurrent output on screen) — otherwise a
# 10-30+ minute run looks identical to a hung one. Doesn't block the real `wait`
# below; `kill -0` just polls liveness without consuming the exit status.
START_TIME=$(date +%s)
while true; do
  running=()
  for key in "${!BRANCH_PID[@]}"; do
    kill -0 "${BRANCH_PID[$key]}" 2>/dev/null && running+=("$key")
  done
  [[ ${#running[@]} -eq 0 ]] && break
  echo ">> still running ($(( $(date +%s) - START_TIME ))s elapsed):"
  for key in "${running[@]}"; do
    last_line="$(tail -n 1 "$LOG_DIR/$key.log" 2>/dev/null)"
    echo "   $key: ${last_line:-<starting up...>}"
  done
  sleep 20
done

FAILED=()
for key in "${!BRANCH_PID[@]}"; do
  if ! wait "${BRANCH_PID[$key]}"; then
    FAILED+=("$key")
  fi
done

echo ""
echo "============================================"
if [[ ${#FAILED[@]} -eq 0 ]]; then
  echo "   SUCCESS — all branches completed: ${!BRANCH_PID[*]}"
else
  echo "   FAILED branches: ${FAILED[*]}"
fi
echo "============================================"
for key in "${!BRANCH_PID[@]}"; do
  log="$LOG_DIR/$key.log"
  [[ -f "$log" ]] || continue
  echo ""
  echo "── $key log ──────────────────────────────"
  if [[ " ${FAILED[*]} " == *" $key "* ]]; then
    tail -n 60 "$log"
  else
    tail -n 10 "$log"
  fi
done

[[ ${#FAILED[@]} -eq 0 ]]
