#!/usr/bin/env bash

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -f "$ROOT/.env.deploy" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT/.env.deploy"
fi

export BUILD_PLATFORMS="${BUILD_PLATFORMS:-linux/amd64}"

DESKTOP_BUILD_LOCK="$ROOT/.desktop-build.lock"

usage() {
  sed -n '2,48p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

ALL_SERVICES=(fuseki graphdb auth gateway editor reasoner-worker swrl plugin plugin-init web)

DEFAULT_ALL_SERVICES=(auth gateway editor reasoner-worker swrl plugin plugin-init web)
ALL_PLATFORMS=(web vscode windows linux mac)

EC2_DIR="${EC2_DIR:-}"

MODE_ARG=""
CHANGES_ARG=""
PLATFORM_ARG=""
LINUX_ONLY_ARG=""
REMOTE_BUILD_ARG=0
UPLOAD_ONLY_ARG=0
REMOTE_BUILD_SUPPORTED=("${DEFAULT_ALL_SERVICES[@]}")

declare -A COMPOSE_SERVICE_NAME=(
  [auth]=auth [gateway]=gateway [editor]=owl-editor [reasoner-worker]=reasoner-worker
  [swrl]=swrl-service [plugin]=plugin-service [plugin-init]=plugin-init [web]=webapp
)
declare -A REMOTE_BUILD_DOCKERFILE=(
  [auth]=Dockerfile.auth [gateway]=Dockerfile.gateway [editor]=Dockerfile.editor
  [reasoner-worker]=Dockerfile.reasoner-worker [swrl]=Dockerfile.swrl
  [plugin]=Dockerfile.plugin [plugin-init]=Dockerfile.plugin-init [web]=Dockerfile.webapp
)

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --mode) shift; MODE_ARG="${1:-}"; shift ;;
    --changes|--deploy) shift; CHANGES_ARG="${1:-}"; shift ;;
    --platform) shift; PLATFORM_ARG="${1:-}"; shift ;;
    --linux-only) shift; LINUX_ONLY_ARG="${1:-}"; shift ;;
    --remote-build) REMOTE_BUILD_ARG=1; shift ;;
    --upload-only) UPLOAD_ONLY_ARG=1; shift ;;
    *) echo "Unknown arg: $1" >&2; usage 1 ;;
  esac
done

[[ -n "$MODE_ARG" ]]     || { echo "ERROR: --mode dev|prod|all is required" >&2; usage 1; }
[[ -n "$CHANGES_ARG" ]]  || { echo "ERROR: --changes|--deploy <service,...>|all is required" >&2; usage 1; }
[[ -n "$PLATFORM_ARG" ]] || { echo "ERROR: --platform <web,vscode,windows,linux,mac>|all is required" >&2; usage 1; }

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

if [[ $REMOTE_BUILD_ARG -eq 1 ]]; then
  for s in "${SERVICES[@]}"; do
    if [[ ! " ${REMOTE_BUILD_SUPPORTED[*]} " == *" $s "* ]]; then
      echo "ERROR: --remote-build only supports these services today: ${REMOTE_BUILD_SUPPORTED[*]} (got '$s')" >&2
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
      CFLAGS[dev]="-f docker-compose.de.ec2.yml "
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
    web) [[ $REMOTE_BUILD_ARG -eq 1 ]] || _wsl_flags+=(--web) ;;
    linux) [[ $REMOTE_BUILD_ARG -eq 1 ]] || _wsl_flags+=(--desktop) ;;
    windows|mac) _wsl_flags+=(--desktop) ;;
    vscode) _wsl_flags+=(--vscode) ;;
  esac
done
if [[ ${#_wsl_flags[@]} -eq 0 ]]; then
  : # every requested platform is handled by --remote-build — no local WSL/Docker/JDK needed.
  # (check-wsl-prereqs.sh defaults to checking everything when called with no --web/--desktop/
  # --vscode flags, so it must not be invoked at all here, not just with an empty flag list.)
elif [[ -x "$ROOT/scripts/check-wsl-prereqs.sh" ]] || [[ -f "$ROOT/scripts/check-wsl-prereqs.sh" ]]; then

  export PATH="/usr/local/bin:/usr/bin:/bin:${PATH}"

  bash "$ROOT/scripts/check-wsl-prereqs.sh" "${_wsl_flags[@]}" --auto-install || exit 1
else
  _needs_host_jdk=0
  for p in "${PLATFORMS[@]}"; do
    case "$p" in windows|linux|mac) _needs_host_jdk=1 ;; esac
  done
  if [[ $_needs_host_jdk -eq 1 ]]; then
    require_jdk_prereqs
  else
    require_jdk_prereqs_soft || true
  fi
fi
echo ""

RUN_TS="$(date +%Y_%m_%d_%H_%M)"
LOG_DIR="$ROOT/.deploy-app/$RUN_TS"
mkdir -p "$LOG_DIR"
DEPLOY_START_SHA="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null)"
DEPLOY_START_BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null)"
echo "[progress] deploying commit ${DEPLOY_START_SHA:-unknown} on branch ${DEPLOY_START_BRANCH:-unknown}"
echo "[progress] logs: $LOG_DIR"
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

# Builds the image on the target host instead of locally + pushing to a registry.
# Only for services with a small, known Dockerfile build context (see
# REMOTE_BUILD_SUPPORTED and docker-compose.remote-build.yml) — avoids local
# buildx/dockerd entirely, at the cost of running the build on the target box.
branch_web_remote_build() {
  local m="$1"
  local ssh_opts=(-o BatchMode=yes)
  local rsync_rsh="ssh -o BatchMode=yes"
  if [[ -n "${SSHKEY[$m]}" ]]; then
    if [[ ! -f "${SSHKEY[$m]}" ]]; then
      echo "ERROR: ${m^^}_SSH_KEY is set to '${SSHKEY[$m]}' but that file doesn't exist on this host" >&2
      return 1
    fi
    ssh_opts+=(-i "${SSHKEY[$m]}")
    rsync_rsh="ssh -o BatchMode=yes -i ${SSHKEY[$m]}"
  fi

  local compose_services=() dockerfiles=() s
  for s in "${SERVICES[@]}"; do
    compose_services+=("${COMPOSE_SERVICE_NAME[$s]}")
    dockerfiles+=("${REMOTE_BUILD_DOCKERFILE[$s]}")
  done

  local rsync_paths=(pom.xml checkstyle.xml checkstyle-suppressions.xml .mvn/ shared/ \
    ontology-auth/pom.xml ontology-auth/src/ \
    ontology-gateway/pom.xml ontology-gateway/src/ \
    ontology-editor/pom.xml ontology-editor/src/ \
    ontology-swrl/pom.xml ontology-swrl/src/ \
    ontology-plugin-service/pom.xml ontology-plugin-service/src/ \
    ontology-reasoner-worker/pom.xml ontology-reasoner-worker/src/ \
    "${dockerfiles[@]}" docker-compose.remote-build.yml)
  if [[ " ${SERVICES[*]} " == *" web "* ]]; then
    local webapp_env="$ROOT/ontology-vscode-extension/webview-src/.env.production"
    {
      echo "# Generated by deploy-coretopia-release.sh --mode $m — do not edit by hand"
      echo "VITE_CLOUD_GATEWAY_URL=${API[$m]}"
      echo "VITE_CLOUD_EDITOR_URL=${API[$m]}"
      echo "VITE_CLOUD_PLUGIN_URL=${API[$m]}:8087"
    } > "$webapp_env"
    echo "[progress][$m-web] regenerated webview .env.production → ${API[$m]}"
    rsync_paths+=(ontology-vscode-extension/package.json ontology-vscode-extension/webview-src/)
  fi
  if [[ " ${SERVICES[*]} " == *" plugin-init "* ]]; then
    rsync_paths+=(plugins/ scripts/package.json scripts/manage-plugins.js)
  fi

  echo "[progress][$m-web] $(date '+%H:%M:%S') START remote build → ${HOST[$m]}:${DIR[$m]}"
  echo "[progress][$m-web] rsync build context (${SERVICES[*]})"
  rsync -azR -e "$rsync_rsh" \
    --exclude 'target' \
    --exclude '*.class' \
    --exclude 'node_modules' \
    "${rsync_paths[@]}" \
    "${HOST[$m]}:${DIR[$m]}/" || return 1

  echo "[progress][$m-web] $(date '+%H:%M:%S') rsync OK — building + starting on remote: ${compose_services[*]}"
  ssh "${ssh_opts[@]}" "${HOST[$m]}" \
    "cd '${DIR[$m]}' && DOCKER_REGISTRY=${REG[$m]} VERSION=${VER[$m]} docker compose ${CFLAGS[$m]}-f docker-compose.remote-build.yml build ${compose_services[*]} && DOCKER_REGISTRY=${REG[$m]} VERSION=${VER[$m]} docker compose ${CFLAGS[$m]}-f docker-compose.remote-build.yml up -d ${compose_services[*]} && docker compose ${CFLAGS[$m]}ps" || return 1
  echo "[progress][$m-web] $(date '+%H:%M:%S') DONE remote build+deploy"
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
  local platform="$1" update_host="$2"
  (
    flock -x 9
    cd "$ROOT/electron-app" && ONTOCODE_UPDATE_HOST="$update_host" npm run "dist:$platform"
  ) 9>"$DESKTOP_BUILD_LOCK"
}

build_desktop_win_via_windows_host() {
  local update_host="$1"
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

  # WSL interop mis-escapes argv containing \"..\" sequences when reconstructing the Windows
  # command line, so avoid embedding quoted paths in a cmd.exe/powershell.exe -Command string —
  # write a .bat with no nested quoting and invoke that instead.
  local bat_file="$ROOT/electron-app/.dist-win-build.bat"
  cat > "$bat_file" <<EOF
@echo off
set ONTOCODE_UPDATE_HOST=${update_host}
cd /d "${win_electron}"
call npm run dist:win
EOF
  local win_bat
  win_bat="$(wslpath -w "$bat_file")" || { echo "ERROR: wslpath failed for $bat_file" >&2; return 1; }

  local rc=0
  (
    flock -x 9
    if command -v cmd.exe >/dev/null 2>&1; then
      cmd.exe /c "$win_bat"
    else
      powershell.exe -NoProfile -Command "& '$win_bat'"
    fi
  ) 9>"$DESKTOP_BUILD_LOCK" || rc=1
  rm -f "$bat_file"
  return $rc
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

  local ver=""
  [[ -f "$ROOT/electron-app/package.json" ]] && ver=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$ROOT/electron-app/package.json" | head -1)

  echo "[progress] uploading $filename ($mb MiB) → $platform (version=${ver:-unset})"
  echo ">> Uploading $filename ($mb MiB) → $api_base (platform=$platform)"

  curl -f --connect-timeout 30 --max-time 1800 -X POST "$api_base/api/downloads/upload" \
    -H "Authorization: Bearer $token" \
    -F "platform=$platform" -F "filename=$filename" -F "version=${ver:-}" -F "file=@$file_path"
  local curl_rc=$?
  echo ""
  if [[ $curl_rc -ne 0 ]]; then
    echo "ERROR: upload of $filename failed (curl exit $curl_rc)" >&2
    return 1
  fi
  echo "   Public download: $api_base/api/downloads/$platform"
}

upload_windows_installer() {
  local api_base="$1"
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
  shopt -u nullglob
  if [[ ${#win[@]} -eq 0 ]]; then
    echo "ERROR: no Windows installer found in $DIST" >&2
    return 1
  fi

  local win_arm64=() win_x64=()
  local _w2
  for _w2 in "${win[@]}"; do
    case "$_w2" in
      *arm64*) win_arm64+=("$_w2") ;;
      *)       win_x64+=("$_w2") ;;
    esac
  done
  local fail=0
  [[ ${#win_x64[@]}   -gt 0 ]] && { local f; f=$(ls -t "${win_x64[@]}"   | head -1); upload_installer "$api_base" "windows-x64"   "$f" "$(basename "$f")" || fail=1; }
  [[ ${#win_arm64[@]} -gt 0 ]] && { local f; f=$(ls -t "${win_arm64[@]}" | head -1); upload_installer "$api_base" "windows-arm64" "$f" "$(basename "$f")" || fail=1; }
  return $fail
}

upload_linux_installers() {
  local api_base="$1"
  local DIST="$ROOT/electron-app/dist-electron"
  local fail=0
  shopt -s nullglob
  local appimages=( "$DIST"/*.AppImage )
  local debs=( "$DIST"/*.deb )
  local flatpaks=( "$DIST"/*.flatpak )
  shopt -u nullglob
  if [[ ${#appimages[@]} -eq 0 && ${#debs[@]} -eq 0 && ${#flatpaks[@]} -eq 0 ]]; then
    echo "ERROR: no Linux installer (.AppImage/.deb/.flatpak) found in $DIST" >&2
    return 1
  fi

  local appimg_arm64=() appimg_x64=() deb_arm64=() deb_x64=()
  local _ai _d
  for _ai in "${appimages[@]+"${appimages[@]}"}"; do
    case "$_ai" in
      *arm64*) appimg_arm64+=("$_ai") ;;
      *)       appimg_x64+=("$_ai") ;;
    esac
  done
  for _d in "${debs[@]+"${debs[@]}"}"; do
    case "$_d" in
      *arm64*) deb_arm64+=("$_d") ;;
      *)       deb_x64+=("$_d") ;;
    esac
  done
  [[ ${#appimg_x64[@]}   -gt 0 ]] && { local f; f=$(ls -t "${appimg_x64[@]}"   | head -1); upload_installer "$api_base" "linux-x64"       "$f" "$(basename "$f")" || fail=1; }
  [[ ${#appimg_arm64[@]} -gt 0 ]] && { local f; f=$(ls -t "${appimg_arm64[@]}" | head -1); upload_installer "$api_base" "linux-arm64"     "$f" "$(basename "$f")" || fail=1; }
  [[ ${#deb_x64[@]}      -gt 0 ]] && { local f; f=$(ls -t "${deb_x64[@]}"      | head -1); upload_installer "$api_base" "linux-deb"       "$f" "$(basename "$f")" || fail=1; }
  [[ ${#deb_arm64[@]}    -gt 0 ]] && { local f; f=$(ls -t "${deb_arm64[@]}"    | head -1); upload_installer "$api_base" "linux-deb-arm64" "$f" "$(basename "$f")" || fail=1; }
  [[ ${#flatpaks[@]}     -gt 0 ]] && { local f; f=$(ls -t "${flatpaks[@]}"    | head -1); upload_installer "$api_base" "linux-flatpak" "$f" "$(basename "$f")" || fail=1; }
  return $fail
}

upload_mac_installers() {
  local api_base="$1"
  local DIST="$ROOT/electron-app/dist-electron"
  local fail=0
  shopt -s nullglob
  local dmg_arm=( "$DIST"/*arm64*.dmg )
  local dmg_x64=( "$DIST"/*x64*.dmg )
  shopt -u nullglob
  if [[ ${#dmg_arm[@]} -eq 0 && ${#dmg_x64[@]} -eq 0 ]]; then
    echo "ERROR: no macOS installer (.dmg) found in $DIST" >&2
    return 1
  fi
  [[ ${#dmg_arm[@]} -gt 0 ]] && { local f; f=$(ls -t "${dmg_arm[@]}" | head -1); upload_installer "$api_base" "mac-arm64" "$f" "$(basename "$f")" || fail=1; }
  [[ ${#dmg_x64[@]} -gt 0 ]] && { local f; f=$(ls -t "${dmg_x64[@]}" | head -1); upload_installer "$api_base" "mac-x64" "$f" "$(basename "$f")" || fail=1; }
  return $fail
}

# One platform per call — explicit, no host-detection ambiguity about which
# installer(s) a bare `--platform desktop` would produce.
branch_desktop_windows() {
  local m="$1"
  local api_base="${API[$m]}"
  local update_host="${api_base#https://}"
  local host_platform
  host_platform="$(host_desktop_platform)"
  echo "[progress][$m-windows] $(date '+%H:%M:%S') START (host=$host_platform)"

  case "$host_platform" in
    win)
      build_desktop "win" "$update_host" || { echo "ERROR: windows build failed" >&2; return 1; }
      ;;
    linux)
      if is_wsl; then
        build_desktop_win_via_windows_host "$update_host" || { echo "ERROR: windows build via WSL cross-call to the Windows host failed" >&2; return 1; }
      else
        echo "ERROR: can't build a Windows installer from a plain Linux host — run this from Windows, or from WSL (it cross-builds via the Windows host)" >&2
        return 1
      fi
      ;;
    *)
      echo "ERROR: can't build a Windows installer from '$host_platform' — run this from Windows or WSL instead" >&2
      return 1
      ;;
  esac

  echo "[progress][$m-windows] $(date '+%H:%M:%S') build OK — uploading"
  if upload_windows_installer "$api_base"; then
    echo "[progress][$m-windows] $(date '+%H:%M:%S') DONE"
  else
    echo "[progress][$m-windows] $(date '+%H:%M:%S') finished with errors (see above)"
    return 1
  fi
}

# Builds the linux desktop installer on the target host over SSH instead of
# locally — avoids this host's WSL flakiness (9P mount stalls, network stalls)
# entirely by building on a real Linux box. Mirrors branch_web_remote_build's
# rsync-then-ssh-build pattern, then pulls the finished .AppImage/.deb files
# back and hands them to the normal (unchanged) upload_linux_installers.
branch_desktop_linux_remote_build() {
  local m="$1"
  local api_base="${API[$m]}"
  local update_host="${api_base#https://}"
  local ssh_opts=(-o BatchMode=yes)
  local rsync_rsh="ssh -o BatchMode=yes"
  if [[ -n "${SSHKEY[$m]}" ]]; then
    if [[ ! -f "${SSHKEY[$m]}" ]]; then
      echo "ERROR: ${m^^}_SSH_KEY is set to '${SSHKEY[$m]}' but that file doesn't exist on this host" >&2
      return 1
    fi
    ssh_opts+=(-i "${SSHKEY[$m]}")
    rsync_rsh="ssh -o BatchMode=yes -i ${SSHKEY[$m]}"
  fi

  local remote_dir="${DIR[$m]}/desktop-build"
  local git_commit
  git_commit="$(git -C "$ROOT" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"

  local -A want=([x64]=0 [arm64]=0 [flatpak]=0)
  if [[ -z "$LINUX_ONLY_ARG" ]]; then
    want[x64]=1; want[arm64]=1; want[flatpak]=1
  else
    local _part
    IFS=',' read -ra _linux_only_parts <<< "$LINUX_ONLY_ARG"
    for _part in "${_linux_only_parts[@]}"; do
      case "$_part" in
        x64|arm64|flatpak) want[$_part]=1 ;;
        *) echo "ERROR: unknown --linux-only component '$_part' (expected: x64, arm64, flatpak)" >&2; return 1 ;;
      esac
    done
  fi
  local dist_targets=()
  [[ ${want[x64]} -eq 1 ]] && dist_targets+=("dist:linux:x64")
  [[ ${want[arm64]} -eq 1 ]] && dist_targets+=("dist:linux:arm64")
  [[ ${want[flatpak]} -eq 1 ]] && dist_targets+=("dist:linux:flatpak")
  if [[ ${#dist_targets[@]} -eq 0 ]]; then
    echo "ERROR: --remote-build linux has nothing to build (--linux-only=$LINUX_ONLY_ARG)" >&2
    return 1
  fi

  if [[ -z "${ADMIN_USER:-}" || -z "${ADMIN_PASSWORD:-}" ]]; then
    echo "ERROR: Set ADMIN_USER and ADMIN_PASSWORD for the desktop platform" >&2
    return 1
  fi
  local ver=""
  [[ -f "$ROOT/electron-app/package.json" ]] && ver=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$ROOT/electron-app/package.json" | head -1)

  if [[ $UPLOAD_ONLY_ARG -eq 1 ]]; then
    echo "[progress][$m-linux] $(date '+%H:%M:%S') --upload-only — skipping rsync/build, uploading whatever's already at ${HOST[$m]}:$remote_dir"
  else
    echo "[progress][$m-linux] $(date '+%H:%M:%S') START remote build → ${HOST[$m]}:$remote_dir (commit $git_commit)"
    ssh "${ssh_opts[@]}" "${HOST[$m]}" "mkdir -p '$remote_dir'" || return 1

    echo "[progress][$m-linux] rsync source"
    rsync -azR -e "$rsync_rsh" \
      --exclude 'node_modules' --exclude 'dist-electron*' \
      --exclude 'electron-app/resources/backend' --exclude 'electron-app/logs' \
      pom.xml shared/ \
      ontology-auth/pom.xml ontology-auth/src/ \
      ontology-editor/pom.xml ontology-editor/src/ \
      ontology-plugin-service/pom.xml ontology-plugin-service/src/ \
      ontology-desktop/pom.xml ontology-desktop/src/ \
      ontology-gateway/pom.xml ontology-gateway/src/ \
      ontology-swrl/pom.xml ontology-swrl/src/ \
      ontology-reasoner-worker/pom.xml ontology-reasoner-worker/src/ \
      electron-app/ ontology-vscode-extension/package.json ontology-vscode-extension/webview-src/ \
      "${HOST[$m]}:$remote_dir/" || return 1
    echo "[progress][$m-linux] $(date '+%H:%M:%S') rsync OK — building + uploading each target on remote (${dist_targets[*]})"
  fi

  local local_creds local_script
  local_creds="$(mktemp)"
  local_script="$(mktemp)"
  trap 'rm -f "${local_creds:-}" "${local_script:-}"' RETURN
  chmod 600 "$local_creds"
  printf 'ADMIN_USER=%q\nADMIN_PASSWORD=%q\n' "$ADMIN_USER" "$ADMIN_PASSWORD" > "$local_creds"
  cat > "$local_script" <<'REMOTESCRIPT'
#!/bin/bash
set -uo pipefail
CREDS_FILE="$1"; API_BASE="$2"; VERSION="$3"; REMOTE_DIR="$4"; UPDATE_HOST="$5"; GIT_COMMIT="$6"; UPLOAD_ONLY="$7"; shift 7
TARGETS=("$@")
DIST="$REMOTE_DIR/electron-app/dist-electron"

# shellcheck disable=SC1090
source "$CREDS_FILE"
rm -f "$CREDS_FILE"
TOKEN=$(curl -sf --connect-timeout 15 --max-time 60 -X POST "$API_BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASSWORD\"}" \
  | sed -n 's/.*"jwt"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
unset ADMIN_USER ADMIN_PASSWORD
if [ -z "$TOKEN" ]; then echo "ERROR: login to $API_BASE failed" >&2; exit 1; fi
echo "[progress] remote login OK"

upload_one() {
  local platform="$1" file="$2"
  local mb=$(( $(wc -c <"$file" | tr -d ' ') / 1024 / 1024 ))
  local attempt
  for attempt in 1 2 3; do
    echo "[progress] uploading $(basename "$file") (${mb} MiB) -> $platform (attempt $attempt/3)"
    if curl -f --connect-timeout 30 --max-time 1800 -X POST "$API_BASE/api/downloads/upload" \
        -H "Authorization: Bearer $TOKEN" \
        -F "platform=$platform" -F "filename=$(basename "$file")" -F "version=$VERSION" -F "file=@$file"; then
      echo ""
      return 0
    fi
    echo ""
    echo "WARNING: upload of $(basename "$file") failed (attempt $attempt/3)" >&2
    [[ $attempt -lt 3 ]] && sleep 10
  done
  echo "ERROR: upload of $(basename "$file") failed after 3 attempts" >&2
  return 1
}

# Uploads whatever matches $1 (a glob, already expanded by the caller) for the given
# platform, picking the most recently built file if more than one is present.
upload_latest() {
  local platform="$1"; shift
  local -a matches=("$@")
  [[ ${#matches[@]} -eq 0 ]] && return 0
  local f
  f=$(ls -t "${matches[@]}" | head -1)
  upload_one "$platform" "$f"
}

FAIL=0
if [[ "$UPLOAD_ONLY" -eq 0 ]]; then
  cd "$REMOTE_DIR" || exit 1
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  export PATH="$JAVA_HOME/bin:$PATH"
  echo "[progress] building shared backend (commit $GIT_COMMIT)"
  if ! mvn -pl ontology-editor,ontology-auth,ontology-plugin-service -am clean install -DskipTests -q -Dgit.commit="$GIT_COMMIT"; then
    echo "ERROR: shared backend build failed — no target can be built" >&2
    exit 1
  fi
  if ! (cd ontology-desktop && mvn clean package -DskipTests -q -Dgit.commit="$GIT_COMMIT"); then
    echo "ERROR: ontology-desktop jar build failed — no target can be built" >&2
    exit 1
  fi
  (cd ontology-vscode-extension/webview-src && [ -d node_modules ] || npm install)
  cd electron-app || exit 1
  [ -d node_modules ] || npm install

  for target in "${TARGETS[@]}"; do
    echo "[progress] building dist:linux:$target"
    if ONTOCODE_UPDATE_HOST="$UPDATE_HOST" npm run "dist:linux:$target"; then
      echo "[progress] $target build OK — uploading immediately (not waiting for other targets)"
    else
      echo "WARNING: $target build failed — skipping its upload, continuing with remaining targets" >&2
      FAIL=1
      continue
    fi
    cd "$DIST" || { FAIL=1; continue; }
    shopt -s nullglob
    case "$target" in
      x64)
        appimg=( *.AppImage ); appimg_x64=(); for f in "${appimg[@]+"${appimg[@]}"}"; do [[ "$f" == *arm64* ]] || appimg_x64+=("$f"); done
        deb=( *.deb ); deb_x64=(); for f in "${deb[@]+"${deb[@]}"}"; do [[ "$f" == *arm64* ]] || deb_x64+=("$f"); done
        upload_latest linux-x64 "${appimg_x64[@]+"${appimg_x64[@]}"}" || FAIL=1
        upload_latest linux-deb "${deb_x64[@]+"${deb_x64[@]}"}" || FAIL=1
        ;;
      arm64)
        appimg_arm64=( *arm64*.AppImage )
        deb_arm64=( *arm64*.deb )
        upload_latest linux-arm64 "${appimg_arm64[@]+"${appimg_arm64[@]}"}" || FAIL=1
        upload_latest linux-deb-arm64 "${deb_arm64[@]+"${deb_arm64[@]}"}" || FAIL=1
        ;;
      flatpak)
        flatpaks=( *.flatpak )
        upload_latest linux-flatpak "${flatpaks[@]+"${flatpaks[@]}"}" || FAIL=1
        ;;
    esac
    shopt -u nullglob
    cd "$REMOTE_DIR/electron-app" || exit 1
  done
else
  echo "[progress] --upload-only — uploading whatever's already in $DIST"
  cd "$DIST" || exit 1
  shopt -s nullglob
  appimages=( *.AppImage ); debs=( *.deb ); flatpaks=( *.flatpak )
  shopt -u nullglob
  for f in "${appimages[@]+"${appimages[@]}"}"; do
    case "$f" in *arm64*) p=linux-arm64 ;; *) p=linux-x64 ;; esac
    upload_one "$p" "$f" || FAIL=1
  done
  for f in "${debs[@]+"${debs[@]}"}"; do
    case "$f" in *arm64*) p=linux-deb-arm64 ;; *) p=linux-deb ;; esac
    upload_one "$p" "$f" || FAIL=1
  done
  for f in "${flatpaks[@]+"${flatpaks[@]}"}"; do
    upload_one linux-flatpak "$f" || FAIL=1
  done
fi
exit $FAIL
REMOTESCRIPT

  scp -q "${ssh_opts[@]}" "$local_creds" "${HOST[$m]}:/tmp/.ontocode-creds-$$" || return 1
  ssh "${ssh_opts[@]}" "${HOST[$m]}" "chmod 600 /tmp/.ontocode-creds-$$" || return 1
  local -a target_names=()
  for t in "${dist_targets[@]}"; do target_names+=("${t#dist:linux:}"); done
  if ssh "${ssh_opts[@]}" "${HOST[$m]}" \
      "bash -s -- '/tmp/.ontocode-creds-$$' '$api_base' '${ver:-unknown}' '$remote_dir' '$update_host' '$git_commit' '$UPLOAD_ONLY_ARG' ${target_names[*]}" \
      < "$local_script"; then
    echo "[progress][$m-linux] $(date '+%H:%M:%S') DONE"
  else
    echo "[progress][$m-linux] $(date '+%H:%M:%S') finished with errors (see above) — but any target that built successfully was already uploaded"
    return 1
  fi
}

branch_desktop_linux() {
  local m="$1"
  if [[ $REMOTE_BUILD_ARG -eq 1 ]]; then
    branch_desktop_linux_remote_build "$m"
    return $?
  fi
  local api_base="${API[$m]}"
  local update_host="${api_base#https://}"
  local host_platform
  host_platform="$(host_desktop_platform)"
  echo "[progress][$m-linux] $(date '+%H:%M:%S') START (host=$host_platform)"

  if [[ "$host_platform" != "linux" ]]; then
    echo "ERROR: can't build a Linux installer from '$host_platform' — run this from Linux or WSL instead" >&2
    return 1
  fi

  ensure_linux_nodejs || return 1

  local -A want=([x64]=0 [arm64]=0 [flatpak]=0)
  if [[ -z "$LINUX_ONLY_ARG" ]]; then
    want[x64]=1; want[arm64]=1; want[flatpak]=1
  else
    local _part
    IFS=',' read -ra _linux_only_parts <<< "$LINUX_ONLY_ARG"
    for _part in "${_linux_only_parts[@]}"; do
      case "$_part" in
        x64|arm64|flatpak) want[$_part]=1 ;;
        *) echo "ERROR: unknown --linux-only component '$_part' (expected: x64, arm64, flatpak)" >&2; return 1 ;;
      esac
    done
    echo "[progress][$m-linux] --linux-only=$LINUX_ONLY_ARG — skipping everything else, uploading whatever else already exists on disk"
  fi

  local build_ok=0 attempted_core=0
  if [[ ${want[x64]} -eq 1 ]]; then
    attempted_core=1
    local x64_log="$LOG_DIR/$m-linux-x64.log"
    echo "[progress][$m-linux] x64 build → $x64_log"
    if ( flock -x 9; cd "$ROOT/electron-app" && ONTOCODE_UPDATE_HOST="$update_host" npm run dist:linux:x64 ) 9>"$DESKTOP_BUILD_LOCK" > "$x64_log" 2>&1; then
      build_ok=1
    else
      echo "WARNING: linux x64 build failed — see $x64_log" >&2
    fi
  fi
  if [[ ${want[arm64]} -eq 1 ]]; then
    attempted_core=1
    local arm64_log="$LOG_DIR/$m-linux-arm64.log"
    echo "[progress][$m-linux] arm64 build → $arm64_log"
    if ( flock -x 9; cd "$ROOT/electron-app" && ONTOCODE_UPDATE_HOST="$update_host" npm run dist:linux:arm64 ) 9>"$DESKTOP_BUILD_LOCK" > "$arm64_log" 2>&1; then
      build_ok=1
    else
      echo "WARNING: linux arm64 build failed — see $arm64_log — continuing with whatever succeeded" >&2
    fi
  fi
  if [[ $attempted_core -eq 1 && $build_ok -eq 0 ]]; then
    echo "ERROR: requested linux build(s) all failed" >&2
    return 1
  fi

  if [[ ${want[flatpak]} -eq 1 ]]; then
    if command -v flatpak-builder >/dev/null 2>&1; then
      local flatpak_log="$LOG_DIR/$m-linux-flatpak.log"
      echo "[progress][$m-linux] flatpak-builder found — building flatpak bundle too → $flatpak_log"
      if ! ( flock -x 9; cd "$ROOT/electron-app" && ONTOCODE_UPDATE_HOST="$update_host" npm run dist:linux:flatpak ) 9>"$DESKTOP_BUILD_LOCK" > "$flatpak_log" 2>&1; then
        echo "WARNING: flatpak build failed — see $flatpak_log — continuing with AppImage/deb only" >&2
      fi
    else
      echo "[progress][$m-linux] flatpak-builder not installed — skipping flatpak bundle"
      echo "          Install with: sudo apt-get install -y flatpak flatpak-builder && flatpak remote-add --if-not-exists flathub https://flathub.org/repo/flathub.flatpakrepo && flatpak install -y flathub org.freedesktop.Platform//25.08 org.freedesktop.Sdk//25.08"
    fi
  fi

  echo "[progress][$m-linux] $(date '+%H:%M:%S') build OK — uploading"
  if upload_linux_installers "$api_base"; then
    echo "[progress][$m-linux] $(date '+%H:%M:%S') DONE"
  else
    echo "[progress][$m-linux] $(date '+%H:%M:%S') finished with errors (see above)"
    return 1
  fi
}

branch_desktop_mac() {
  local m="$1"
  local api_base="${API[$m]}"
  local update_host="${api_base#https://}"
  local host_platform
  host_platform="$(host_desktop_platform)"
  echo "[progress][$m-mac] $(date '+%H:%M:%S') START (host=$host_platform)"

  if [[ "$host_platform" != "mac" ]]; then
    echo "ERROR: can't build a macOS installer from '$host_platform' — this only works on a Mac host (no cross-build)" >&2
    return 1
  fi

  build_desktop "mac" "$update_host" || { echo "ERROR: mac build failed" >&2; return 1; }

  echo "[progress][$m-mac] $(date '+%H:%M:%S') build OK — uploading"
  if upload_mac_installers "$api_base"; then
    echo "[progress][$m-mac] $(date '+%H:%M:%S') DONE"
  else
    echo "[progress][$m-mac] $(date '+%H:%M:%S') finished with errors (see above)"
    return 1
  fi
}

branch_vscode() {
  local m="$1"
  local vsix_file="${VSIXFILE[$m]}"
  local api_base="${API[$m]}"
  echo "[progress][$m-vscode] $(date '+%H:%M:%S') START package VSIX (api=$api_base)"
  ensure_linux_nodejs || return 1
  ( cd "$ROOT/ontology-vscode-extension" && {
    cat > "$vsix_file" <<EOF
# Generated by deploy-coretopia-release.sh --mode $m — do not edit by hand
CLOUD_GATEWAY_URL=$api_base
CLOUD_EDITOR_URL=$api_base
CLOUD_PLUGIN_URL=$api_base:8087
EOF
    [[ -f "$vsix_file" ]] || { echo "ERROR: $vsix_file not found" >&2; exit 1; }
    echo "[progress][$m-vscode] npm run bundle:all"
    ENV_FILE="$vsix_file" npm run bundle:all || exit 1
    echo "[progress][$m-vscode] npm run package"
    npm run package || exit 1
  } )
  echo "[progress][$m-vscode] $(date '+%H:%M:%S') DONE"
}

# Desktop bundles auth+owlEditor+ontology-plugin-service into one merged JAR
# (ontology-desktop). prepare-resources.js only ever COPIES whatever jar is
# already sitting in ontology-desktop/target/ — it never invokes Maven. So this
# rebuild has to happen here, once, before any desktop platform branch starts
# (windows/linux/mac run concurrently and would otherwise race `mvn clean` on
# the same target/ directory). Must use `clean install`/`clean package`, not
# plain `package` — spring-boot-maven-plugin's repackage goal can silently
# reuse a stale packaged jar on an incremental build otherwise.
rebuild_desktop_backend() {
  local needs_desktop=0 p
  for p in "${PLATFORMS[@]}"; do
    case "$p" in
      windows|mac) needs_desktop=1 ;;
      linux) [[ $REMOTE_BUILD_ARG -eq 1 ]] || needs_desktop=1 ;;
    esac
  done
  [[ $needs_desktop -eq 1 ]] || return 0

  local java_home="${JDK21_HOME:-${JAVA_HOME:-}}"
  if [[ -z "$java_home" || ! -x "$java_home/bin/java" ]]; then
    echo "ERROR: JDK 21 not found (checked \$JDK21_HOME/\$JAVA_HOME) — can't rebuild the desktop backend jar" >&2
    return 1
  fi
  local git_commit
  git_commit="$(git -C "$ROOT" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"

  echo "[progress][desktop-backend] rebuilding auth+owlEditor+ontology-plugin-service+ontology-desktop (commit $git_commit)"
  (
    export JAVA_HOME="$java_home"
    export PATH="$JAVA_HOME/bin:$PATH"
    cd "$ROOT" && mvn -pl ontology-editor,ontology-auth,ontology-plugin-service -am clean install -DskipTests -q -Dgit.commit="$git_commit" \
      && cd "$ROOT/ontology-desktop" && mvn clean package -DskipTests -q -Dgit.commit="$git_commit"
  )
  if [[ $? -ne 0 ]]; then
    echo "ERROR: desktop backend rebuild failed — see above" >&2
    return 1
  fi
  echo "[progress][desktop-backend] rebuilt OK — $ROOT/ontology-desktop/target/ontology-desktop-1.0.0.jar"
}
rebuild_desktop_backend || exit 1

BRANCH_COUNT=0
for m in "${MODES[@]}"; do
  for p in "${PLATFORMS[@]}"; do
    key="$m-$p"
    log="$LOG_DIR/$key.log"
    echo "[progress] launching branch $key (log: $log)"
    {
      echo "============================================================"
      echo " OntoCode deploy — $key"
      echo " started : $(date '+%Y-%m-%d %H:%M:%S')"
      echo " commit  : $(git -C "$ROOT" rev-parse --short=12 HEAD 2>/dev/null) $(git -C "$ROOT" log -1 --format='%s' 2>/dev/null)"
      echo " branch  : $(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null)"
      echo " services: ${SERVICES[*]}"
      echo "============================================================"
    } > "$log"
    case "$p" in
      web)
        if [[ $REMOTE_BUILD_ARG -eq 1 ]]; then
          ( branch_web_remote_build "$m" ) >> "$log" 2>&1 &
        else
          ( branch_web "$m" ) >> "$log" 2>&1 &
        fi
        ;;
      windows) ( branch_desktop_windows "$m" ) >> "$log" 2>&1 & ;;
      linux)   ( branch_desktop_linux "$m" )   >> "$log" 2>&1 & ;;
      mac)     ( branch_desktop_mac "$m" )     >> "$log" 2>&1 & ;;
      vscode)  ( branch_vscode "$m" )  >> "$log" 2>&1 & ;;
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
DEPLOY_END_SHA="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null)"
echo "   Started at commit : ${DEPLOY_START_SHA:-unknown}"
echo "   Ended at commit   : ${DEPLOY_END_SHA:-unknown}"
if [[ -n "$DEPLOY_START_SHA" && "$DEPLOY_START_SHA" != "$DEPLOY_END_SHA" ]]; then
  echo "   *** WARNING: the git checkout changed WHILE this deploy was running. ***"
  echo "   *** Branches that read source files after the change may have built a mix of commits. ***"
  echo "   *** Don't switch branches or commit in this repo while a deploy is in flight. ***"
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
