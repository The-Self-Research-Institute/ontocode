#!/usr/bin/env bash

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -f "$ROOT/.env.deploy" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT/.env.deploy"
fi

export BUILD_PLATFORMS="${BUILD_PLATFORMS:-linux/amd64}"

usage() {
  sed -n '2,48p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

ALL_SERVICES=(fuseki graphdb auth gateway editor reasoner-worker swrl plugin plugin-init web)

DEFAULT_ALL_SERVICES=(auth gateway editor reasoner-worker swrl plugin plugin-init web)
ALL_PLATFORMS=(web vscode desktop)

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

  export PATH="/usr/local/bin:/usr/bin:/bin:${PATH}"

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

  echo "[progress] uploading $filename ($mb MiB) → $platform"
  echo ">> Uploading $filename ($mb MiB) → $api_base (platform=$platform)"

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

  if [[ "$host_platform" == "linux" ]] && is_wsl; then
    echo "[progress][$m-desktop] WSL detected — Linux then Windows (shared electron-app/resources — not parallel by design)"
    if build_desktop_win_via_windows_host; then
      echo "[progress][$m-desktop] $(date '+%H:%M:%S') Windows installer build OK"

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

  local win=( "$DIST"/*[Ss]etup*.exe )
  if [[ ${#win[@]} -eq 0 ]]; then
    win=( "$DIST"/*Setup*x64*.exe )
  fi

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

branch_vscode() {
  local m="$1"
  local vsix_file="${VSIXFILE[$m]}"
  local api_base="${API[$m]}"
  echo "[progress][$m-vscode] $(date '+%H:%M:%S') START package VSIX (api=$api_base)"
  ensure_linux_nodejs || return 1
  ( cd "$ROOT/ontology-vscode-extension" && {
    if [[ "$m" == "dev" ]]; then

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

START_TIME=$(date +%s)
declare -A BRANCH_DONE
COMPLETED=0

while true; do
  running=()
  for key in "${!BRANCH_PID[@]}"; do
    if kill -0 "${BRANCH_PID[$key]}" 2>/dev/null; then
      running+=("$key")
    elif [[ -z "${BRANCH_DONE[$key]:-}" ]]; then

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
