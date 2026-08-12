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
#   --changes|--deploy <service>[,<service>...]|all
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
#   Paths: DEV_EC2_DIR and PROD_EC2_DIR (per environment). Optional legacy EC2_DIR
#          is used as fallback for either if the specific var is unset.
#          Defaults: DEV_EC2_DIR=/home/ubuntu/ontocode  PROD_EC2_DIR=/opt/ontocode
#   Desktop upload: ADMIN_USER=admin@coretopia.com  ADMIN_PASSWORD=...
#
# Examples:
#   # Deploy just the web service to dev
#   DEV_EC2_HOST=ubuntu@1.2.3.4 DEV_API_BASE=https://dev-api.example.com \
#     ./scripts/deploy-coretopia-release.sh --mode dev --changes web --platform web
#
#   # Preferred short entry (root):
#   ./build-and-push --mode dev --platform all --deploy all
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

# Match remote-dev-release / EC2: amd64-only unless caller overrides.
# Multi-arch (…,linux/arm64) uses QEMU on amd64 hosts and often breaks apk.
export BUILD_PLATFORMS="${BUILD_PLATFORMS:-linux/amd64}"

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
# Legacy single path — prefer DEV_EC2_DIR / PROD_EC2_DIR below.
EC2_DIR="${EC2_DIR:-}"

MODE_ARG=""
CHANGES_ARG=""
PLATFORM_ARG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --mode) shift; MODE_ARG="${1:-}"; shift ;;
    --changes|--deploy) shift; CHANGES_ARG="${1:-}"; shift ;;
    --platform) shift; PLATFORM_ARG="${1:-}"; shift ;;
    *) echo "Unknown arg: $1" >&2; usage 1 ;;
  esac
done

[[ -n "$MODE_ARG" ]]     || { echo "ERROR: --mode dev|prod|all is required" >&2; usage 1; }
[[ -n "$CHANGES_ARG" ]]  || { echo "ERROR: --changes|--deploy <service,...>|all is required" >&2; usage 1; }
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

declare -A REG VER HOST API CFLAGS VSIXFILE SSHKEY DIR

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
      DIR[dev]="${DEV_EC2_DIR:-${EC2_DIR:-/home/ubuntu/ontocode}}"
      ;;
    prod)
      REG[prod]="${PROD_REGISTRY:-ontocode}"
      VER[prod]="${PROD_VERSION:-latest}"
      HOST[prod]="${EC2_HOST:?Set EC2_HOST (prod SSH target)}"
      API[prod]="${API_BASE:-https://ontocodeapi.selfresearch.org}"
      CFLAGS[prod]="-f docker-compose.production.yml -f docker-compose.r6i-xlarge.yml "
      VSIXFILE[prod]=".env.prod-release"
      SSHKEY[prod]="${PROD_SSH_KEY:-}"
      DIR[prod]="${PROD_EC2_DIR:-${EC2_DIR:-/opt/ontocode}}"
      ;;
  esac
}
for m in "${MODES[@]}"; do resolve_mode "$m"; done

echo "============================================"
echo " OntoCode release"
echo " Modes     : ${MODES[*]}"
echo " Services  : ${SERVICES[*]}"
echo " Platforms : ${PLATFORMS[*]}"
echo " BUILD_PLATFORMS : $BUILD_PLATFORMS  (docker buildx; override to add arm64)"
if [[ "$MODE_ARG" == "all" ]]; then
  echo " NOTE: --mode all — dev and prod branches run in parallel, no dev-first gate."
fi
echo "============================================"
echo ""

# JDK / Node / Docker — prefer the dedicated WSL checker when available.
# shellcheck disable=SC1091
source "$ROOT/scripts/check-jdk-prereqs.sh"
_wsl_flags=()
for p in "${PLATFORMS[@]}"; do
  case "$p" in
    web) _wsl_flags+=(--web) ;;
    desktop) _wsl_flags+=(--desktop) ;;
    vscode) _wsl_flags+=(--vscode) ;;
  esac
done
if [[ -x "$ROOT/scripts/check-wsl-prereqs.sh" ]] || [[ -f "$ROOT/scripts/check-wsl-prereqs.sh" ]]; then
  # Prefer Linux /usr/bin/docker (Engine in Ubuntu). Strip Windows Docker stubs from PATH.
  export PATH="/usr/local/bin:/usr/bin:/bin:${PATH}"
  # Re-run prereq repair/start (daemon may have stopped between cmd check and release).
  bash "$ROOT/scripts/check-wsl-prereqs.sh" "${_wsl_flags[@]}" --auto-install || exit 1
else
  _needs_host_jdk=0
  for p in "${PLATFORMS[@]}"; do
    [[ "$p" == "desktop" ]] && _needs_host_jdk=1
  done
  if [[ $_needs_host_jdk -eq 1 ]]; then
    require_jdk_prereqs
  else
    require_jdk_prereqs_soft || true
  fi
fi
echo ""

LOG_DIR="$(mktemp -d)"
declare -A BRANCH_PID

# ── web: build+push the requested services, then SSH deploy (sequential within branch) ──

branch_web() {
  local m="$1"
  echo "[progress][$m-web] $(date '+%H:%M:%S') START image build+push"
  echo "[progress][$m-web] registry=${REG[$m]} version=${VER[$m]}"
  echo "[progress][$m-web] services=${SERVICES[*]}"
  echo "[progress][$m-web] BUILD_PLATFORMS=$BUILD_PLATFORMS"
  BUILD_PLATFORMS="$BUILD_PLATFORMS" ./build-and-push.sh "${REG[$m]}" "${VER[$m]}" "${SERVICES[@]}" || return 1
  echo "[progress][$m-web] $(date '+%H:%M:%S') images pushed OK — starting SSH deploy"
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
  echo "[progress][$m-web] SSH → ${HOST[$m]} (${DIR[$m]})"
  ssh "${ssh_opts[@]}" "${HOST[$m]}" "cd '${DIR[$m]}' && DOCKER_REGISTRY=${REG[$m]} VERSION=${VER[$m]} docker compose ${CFLAGS[$m]}up -d --pull always && docker compose ${CFLAGS[$m]}ps" || return 1
  echo "[progress][$m-web] $(date '+%H:%M:%S') DONE deploy"
}

# ── desktop: native installer for this host, then upload ─────────────────────
#
# Cross-building win FROM Linux/WSL with Wine is unreliable on /mnt/<drive> paths.
# So we:
#   1) Always build the installer native to THIS host (linux in WSL, win in Git Bash).
#   2) If we are inside WSL and Windows is reachable (cmd.exe), ALSO build the
#      Windows installer on the Windows side — sequentially after Linux, because
#      both targets share electron-app/resources and must not run in parallel.
#      (Hybrid remote path builds linux on EC2 || Windows .exe on PC — that IS parallel.)

# Prefer real Linux Node in WSL. Interop often prepends Windows npm
# (/mnt/c/.../nodejs) which then prints a bogus "WSL 1 is not supported" error.
sanitize_path_for_linux_tools() {
  local cleaned="" part
  IFS=':' read -ra _parts <<< "${PATH:-}"
  for part in "${_parts[@]}"; do
    case "$part" in
      /mnt/[a-zA-Z]/*|"/mnt/"[a-zA-Z]/*) continue ;;
      *) cleaned="${cleaned:+$cleaned:}$part" ;;
    esac
  done
  export PATH="$cleaned"
}

ensure_linux_nodejs() {
  sanitize_path_for_linux_tools
  # Common user installs
  if [[ -s "$HOME/.nvm/nvm.sh" ]]; then
    # shellcheck disable=SC1091
    source "$HOME/.nvm/nvm.sh"
  fi
  if [[ -x "$HOME/.local/share/fnm/fnm" ]]; then
    eval "$("$HOME/.local/share/fnm/fnm" env)"
  fi

  if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    echo "ERROR: No Linux Node.js/npm on PATH inside WSL." >&2
    echo "  Windows Node was on PATH (/mnt/...) and cannot build Linux packages." >&2
    echo "  Install Linux Node, then retry:" >&2
    echo "    curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -" >&2
    echo "    sudo apt-get install -y nodejs" >&2
    echo "  Or: sudo apt-get install -y nodejs npm" >&2
    return 1
  fi

  local node_path npm_path
  node_path="$(command -v node)"
  npm_path="$(command -v npm)"
  case "$node_path" in
    /mnt/*)
      echo "ERROR: 'node' still resolves to Windows ($node_path)." >&2
      return 1
      ;;
  esac
  case "$npm_path" in
    /mnt/*)
      echo "ERROR: 'npm' still resolves to Windows ($npm_path)." >&2
      return 1
      ;;
  esac

  echo "[progress] using Linux node=$(node -v) npm=$(npm -v) ($node_path)"
}

host_desktop_platform() {
  case "$(uname -s)" in
    Darwin*)              echo mac ;;
    Linux*)               echo linux ;;
    MINGW*|MSYS*|CYGWIN*) echo win ;;
    *)                    echo linux ;;
  esac
}

is_wsl() {
  grep -qiE 'microsoft|wsl' /proc/version 2>/dev/null
}

build_desktop() {
  local platform="$1"
  ( cd "$ROOT/electron-app" && npm run "dist:$platform" )
}

# Build Windows installer by invoking the Windows host from WSL (no Wine).
build_desktop_win_via_windows_host() {
  if ! command -v cmd.exe >/dev/null 2>&1 && ! command -v powershell.exe >/dev/null 2>&1; then
    echo "[progress][desktop] Windows host tools not available from WSL — skipping win installer"
    return 1
  fi
  local win_electron
  win_electron="$(wslpath -w "$ROOT/electron-app" 2>/dev/null)" || {
    echo "ERROR: wslpath failed for $ROOT/electron-app" >&2
    return 1
  }
  echo "[progress][desktop] Building Windows installer on Windows host..."
  echo "          path: $win_electron"
  # Sequential: must not overlap with a Linux dist that touches the same resources/
  if command -v cmd.exe >/dev/null 2>&1; then
    cmd.exe /c "cd /d \"${win_electron}\" && npm run dist:win" || return 1
  else
    powershell.exe -NoProfile -Command "Set-Location -LiteralPath '${win_electron}'; npm run dist:win" || return 1
  fi
}

upload_installer() {
  local api_base="$1" platform="$2" file_path="$3" filename="$4"
  if [[ -z "${ADMIN_USER:-}" || -z "${ADMIN_PASSWORD:-}" ]]; then
    echo "ERROR: Set ADMIN_USER and ADMIN_PASSWORD for the desktop platform" >&2
    return 1
  fi
  local token
  # Login: short timeout so a hung auth API fails fast.
  token=$(curl -sf --connect-timeout 15 --max-time 60 -X POST "$api_base/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASSWORD\"}" \
    | sed -n 's/.*"jwt"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
  if [[ -z "$token" ]]; then
    echo "ERROR: login to $api_base failed — no jwt in response" >&2
    return 1
  fi
  local bytes mb
  bytes=$(wc -c <"$file_path" | tr -d ' ')
  mb=$(( bytes / 1024 / 1024 ))
  # Progress board only shows the latest [progress] line — update per file so
  # a long curl (often 300–400MB installers) does not look stuck.
  echo "[progress] uploading $filename ($mb MiB) → $platform"
  echo ">> Uploading $filename ($mb MiB) → $api_base (platform=$platform)"
  # Large installers: allow up to 30m; fail instead of hanging forever.
  curl -f --connect-timeout 30 --max-time 1800 -X POST "$api_base/api/downloads/upload" \
    -H "Authorization: Bearer $token" \
    -F "platform=$platform" -F "filename=$filename" -F "file=@$file_path"
  echo ""
  echo "   Public download: $api_base/api/downloads/$platform"
}

branch_desktop() {
  local m="$1"
  local host_platform
  host_platform="$(host_desktop_platform)"
  local api_base="${API[$m]}"
  local fail=0
  local built_native=0

  echo "[progress][$m-desktop] $(date '+%H:%M:%S') START (host=$host_platform)"

  # 1) Native installer for this environment (needs Linux node when host=linux)
  if [[ "$host_platform" == "linux" ]]; then
    if ensure_linux_nodejs; then
      echo "[progress][$m-desktop] Building native 'linux' installer"
      if build_desktop "linux"; then
        echo "[progress][$m-desktop] $(date '+%H:%M:%S') native linux build OK"
        built_native=1
      else
        echo "ERROR: desktop linux build failed" >&2
        fail=1
      fi
    else
      echo "[progress][$m-desktop] No Linux Node.js — skipping Linux installer"
      echo "          Install with: curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - && sudo apt-get install -y nodejs"
      fail=1
    fi
  else
    echo "[progress][$m-desktop] Building native '$host_platform' installer"
    build_desktop "$host_platform" || { echo "ERROR: desktop $host_platform build failed" >&2; return 1; }
    built_native=1
    echo "[progress][$m-desktop] $(date '+%H:%M:%S') native $host_platform build OK"
  fi

  # 2) From WSL, also build Windows installer on the real Windows side
  if [[ "$host_platform" == "linux" ]] && is_wsl; then
    echo "[progress][$m-desktop] WSL detected — Linux then Windows (shared electron-app/resources — not parallel by design)"
    if build_desktop_win_via_windows_host; then
      echo "[progress][$m-desktop] $(date '+%H:%M:%S') Windows installer build OK"
      # Windows build success can still make the branch useful even if Linux node was missing
      if [[ $built_native -eq 0 ]]; then fail=0; fi
    else
      echo "[progress][$m-desktop] Windows installer build skipped/failed"
      echo "          Tip: run from Windows: build-and-push.cmd --mode $m --platform desktop --deploy all"
    fi
  elif [[ "$host_platform" == "linux" ]]; then
    echo "[progress][$m-desktop] Pure Linux host — Linux installer only (no Windows .exe here)"
  fi

  echo "[progress][$m-desktop] $(date '+%H:%M:%S') uploading available installers..."
  local DIST="$ROOT/electron-app/dist-electron"
  shopt -s nullglob
  # NSIS artifact is typically "OntoCode Setup <ver>.exe" (no arch token). Also accept *x64*.
  local win=( "$DIST"/*[Ss]etup*.exe )
  if [[ ${#win[@]} -eq 0 ]]; then
    win=( "$DIST"/*Setup*x64*.exe )
  fi
  # Drop blockmaps if a shell glob ever picks them up
  local win_filtered=()
  local _w
  for _w in "${win[@]+"${win[@]}"}"; do
    [[ "$_w" == *.blockmap ]] && continue
    win_filtered+=("$_w")
  done
  win=("${win_filtered[@]+"${win_filtered[@]}"}")
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

  if [[ $fail -eq 0 ]]; then
    echo "[progress][$m-desktop] $(date '+%H:%M:%S') DONE"
  else
    echo "[progress][$m-desktop] $(date '+%H:%M:%S') finished with warnings/errors (see above)"
  fi
  return $fail
}

# ── vscode: build+package the VSIX pointed at that mode's API (fully independent) ────

branch_vscode() {
  local m="$1"
  local vsix_file="${VSIXFILE[$m]}"
  local api_base="${API[$m]}"
  echo "[progress][$m-vscode] $(date '+%H:%M:%S') START package VSIX (api=$api_base)"
  ensure_linux_nodejs || return 1
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
    echo "[progress][$m-vscode] npm run bundle:all"
    ENV_FILE="$vsix_file" npm run bundle:all || exit 1
    echo "[progress][$m-vscode] npm run package"
    npm run package || exit 1
  } )
  echo "[progress][$m-vscode] $(date '+%H:%M:%S') DONE"
}

# ── Launch one branch per (mode × platform) combination, all concurrently ────────────

BRANCH_COUNT=0
for m in "${MODES[@]}"; do
  for p in "${PLATFORMS[@]}"; do
    key="$m-$p"
    log="$LOG_DIR/$key.log"
    echo "[progress] launching branch $key (log: $log)"
    case "$p" in
      web)     ( branch_web "$m" )     > "$log" 2>&1 & ;;
      desktop) ( branch_desktop "$m" ) > "$log" 2>&1 & ;;
      vscode)  ( branch_vscode "$m" )  > "$log" 2>&1 & ;;
    esac
    BRANCH_PID["$key"]=$!
    BRANCH_COUNT=$((BRANCH_COUNT + 1))
  done
done

echo ""
echo "[progress] $BRANCH_COUNT branch(es) running in parallel — live status every 8s"
echo ""

# Live status board: show recent progress lines + announce completions immediately.
START_TIME=$(date +%s)
declare -A BRANCH_DONE
COMPLETED=0

while true; do
  running=()
  for key in "${!BRANCH_PID[@]}"; do
    if kill -0 "${BRANCH_PID[$key]}" 2>/dev/null; then
      running+=("$key")
    elif [[ -z "${BRANCH_DONE[$key]:-}" ]]; then
      # First time we notice this PID exited — report it now (don't wait for final wait).
      if wait "${BRANCH_PID[$key]}"; then
        echo "[progress] ✓ COMPLETED $key  ($(( $(date +%s) - START_TIME ))s)"
        BRANCH_DONE["$key"]=ok
      else
        echo "[progress] ✗ FAILED    $key  ($(( $(date +%s) - START_TIME ))s) — last lines:"
        tail -n 8 "$LOG_DIR/$key.log" 2>/dev/null | sed 's/^/    /'
        BRANCH_DONE["$key"]=fail
      fi
      COMPLETED=$((COMPLETED + 1))
    fi
  done
  [[ ${#running[@]} -eq 0 ]] && break

  echo "------------------------------------------------------------"
  echo "[progress] $(date '+%H:%M:%S')  elapsed $(( $(date +%s) - START_TIME ))s  |  done ${COMPLETED}/${BRANCH_COUNT}  |  still: ${running[*]}"
  for key in "${running[@]}"; do
    # Prefer a tagged progress line if present; else last 2 log lines.
    prog="$(grep '\[progress\]' "$LOG_DIR/$key.log" 2>/dev/null | tail -n 1)"
    if [[ -n "$prog" ]]; then
      echo "  • $key"
      echo "      $prog"
    else
      echo "  • $key"
      tail -n 2 "$LOG_DIR/$key.log" 2>/dev/null | sed 's/^/      /' || echo "      <starting...>"
    fi
  done
  sleep 8
done

FAILED=()
for key in "${!BRANCH_PID[@]}"; do
  if [[ "${BRANCH_DONE[$key]:-}" == "fail" ]]; then
    FAILED+=("$key")
  elif [[ -z "${BRANCH_DONE[$key]:-}" ]]; then
    if ! wait "${BRANCH_PID[$key]}"; then
      FAILED+=("$key")
    fi
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
