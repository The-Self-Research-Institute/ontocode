#!/bin/bash
# Build multi-platform Docker images and push to registry.
#
# Usage:
#   ./build-and-push.sh [registry] [version] [services...]
#
# Examples:
#   ./build-and-push.sh                              # build all services
#   ./build-and-push.sh ontocode latest editor       # build only editor
#   ./build-and-push.sh ontocode latest auth editor  # build auth + editor
#   ./build-and-push.sh ontocode latest gateway web  # build gateway + web
#
# Available service names:
#   fuseki  graphdb  auth  gateway  editor  reasoner-worker  swrl  plugin  plugin-init  web
#
# On EC2 (pull & restart after pushing):
#   ./build-and-push.sh ontocode latest editor && \
#   ssh ec2 "cd /opt/ontocode && docker compose pull owl-editor && docker compose up -d owl-editor"

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# Local deploy/login secrets (gitignored)
if [[ -f "$ROOT/.env.deploy" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT/.env.deploy"
fi

REGISTRY=${1:-ontocode}
VERSION=${2:-latest}
# Everything from arg 3 onward is a service filter (empty = build all)
shift 2 2>/dev/null || true
FILTER=("$@")

# Platforms: default amd64-only (DEV/prod EC2 + typical WSL hosts).
# Dual-arch triggers QEMU arm64 and often fails apk/apt under emulation.
# Override when you need Apple Silicon / multi-arch:
#   BUILD_PLATFORMS=linux/amd64,linux/arm64
BUILD_PLATFORMS="${BUILD_PLATFORMS:-linux/amd64}"

# ── Helpers ──────────────────────────────────────────────────────────────────

should_build() {
    local name="$1"
    if [ ${#FILTER[@]} -eq 0 ]; then
        return 0  # no filter → build everything
    fi
    for f in "${FILTER[@]}"; do
        if [ "$f" = "$name" ]; then
            return 0
        fi
    done
    return 1
}

ts() { date '+%H:%M:%S'; }

# Ordered catalog so progress [i/n] is stable and predictable.
ALL_BUILD_SERVICES=(fuseki graphdb auth gateway editor reasoner-worker swrl plugin plugin-init web)
TO_BUILD=()
for _svc in "${ALL_BUILD_SERVICES[@]}"; do
    should_build "$_svc" && TO_BUILD+=("$_svc")
done
BUILD_TOTAL=${#TO_BUILD[@]}
BUILD_INDEX=0

next_step() {
    BUILD_INDEX=$((BUILD_INDEX + 1))
    echo ""
    echo "============================================================"
    echo " [$(ts)] STEP ${BUILD_INDEX}/${BUILD_TOTAL}: $1"
    echo "============================================================"
}

build() {
    local label="$1"   # e.g. "auth"
    local tag="$2"     # e.g. ontocode-auth
    local file="$3"    # e.g. Dockerfile.auth
    local extra="${4:-}"  # optional extra flags like --no-cache

    next_step "build+push $tag ($label)"
    echo " Dockerfile : $file"
    echo " Image      : $REGISTRY/$tag:$VERSION"
    echo " Platforms  : $BUILD_PLATFORMS"
    echo " Started    : $(ts)"
    local _t0
    _t0=$(date +%s)
    local build_args=()
    if [[ "$tag" == "ontocode-web" ]]; then
        if [[ "$VERSION" == "dev" ]]; then
            build_args=(--build-arg "API_BASE_URL=https://ontocodedevapi.selfresearch.org")
        else
            build_args=(--build-arg "API_BASE_URL=https://ontocodeapi.selfresearch.org")
        fi
    fi
    # shellcheck disable=SC2086
    docker buildx build \
        --platform "$BUILD_PLATFORMS" \
        -t "$REGISTRY/$tag:$VERSION" \
        -f "$file" \
        "${build_args[@]}" \
        $extra \
        --push \
        --progress=plain \
        .
    echo " [$(ts)] DONE  $tag pushed  (${BUILD_INDEX}/${BUILD_TOTAL})  elapsed=$(( $(date +%s) - _t0 ))s"
}

# Builds run SEQUENTIALLY. Default is linux/amd64 to avoid QEMU arm64.
fail() {
    echo ""
    echo "============================================"
    echo "   BUILD FAILED at step ${BUILD_INDEX}/${BUILD_TOTAL} — see error above"
    echo "============================================"
    docker buildx rm ontocode-builder 2>/dev/null || true
    exit 1
}

# ── Header ────────────────────────────────────────────────────────────────────

echo "============================================"
echo "   Building OntoCode Images"
echo "   Registry : $REGISTRY"
echo "   Version  : $VERSION"
echo "   Platforms: $BUILD_PLATFORMS  (override with BUILD_PLATFORMS=...)"
if [ ${#FILTER[@]} -gt 0 ]; then
    echo "   Services : ${FILTER[*]}"
else
    echo "   Services : ALL"
fi
echo "   Planned  : ${TO_BUILD[*]:-(none)}"
echo "   Steps    : $BUILD_TOTAL"
echo "============================================"
echo ""

if [ "$BUILD_TOTAL" -eq 0 ]; then
    echo "Nothing to build (empty service filter)."
    exit 0
fi

# ── Prerequisites ─────────────────────────────────────────────────────────────

echo "Checking prerequisites..."
# Prefer Ubuntu Docker Engine over Windows Docker Desktop stub on /mnt/c.
export PATH="/usr/local/bin:/usr/bin:/bin:${PATH:-}"
# Drop Windows drive mounts from PATH so docker.exe cannot shadow /usr/bin/docker.
_clean_path=""
IFS=':' read -ra _pparts <<< "${PATH}"
for _p in "${_pparts[@]}"; do
  case "$_p" in /mnt/[a-zA-Z]/*) continue ;; *) _clean_path="${_clean_path:+$_clean_path:}$_p" ;; esac
done
export PATH="$_clean_path"
hash -r 2>/dev/null || true

if [[ ! -x /usr/bin/docker ]] && ! command -v docker >/dev/null 2>&1; then
    echo "ERROR: docker not found. In WSL: curl -fsSL https://get.docker.com | sudo sh"
    exit 1
fi
if ! docker info >/dev/null 2>&1; then
    if command -v sudo >/dev/null 2>&1; then
      sudo service docker start 2>/dev/null || sudo systemctl start docker 2>/dev/null || true
      sleep 2
    fi
fi
if ! docker info >/dev/null 2>&1 && sudo docker info >/dev/null 2>&1; then
    sudo chmod 666 /var/run/docker.sock 2>/dev/null || true
fi
if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker daemon not reachable (need /usr/bin/docker Engine in WSL)."
    echo "  sudo service docker start"
    echo "  Or: curl -fsSL https://get.docker.com | sudo sh"
    exit 1
fi
if ! docker buildx version >/dev/null 2>&1; then
    echo "ERROR: docker buildx not available."
    exit 1
fi
echo "OK: docker + buildx ($(command -v docker))"

# Host JDKs (SWRL=17, other Java modules=21). Soft for pure Docker pushes.
# Optional on EC2 when only Docker builds run (image already has JDK).
if [[ -f "$ROOT/scripts/check-jdk-prereqs.sh" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT/scripts/check-jdk-prereqs.sh"
  require_jdk_prereqs_soft || true
else
  echo "NOTE: scripts/check-jdk-prereqs.sh missing — skipping host JDK check (OK for Docker-only builds)"
fi

# Docker Hub login (optional via .env.deploy: DOCKER_USERNAME + DOCKER_PASSWORD or DOCKER_HUB_TOKEN)
if [[ -n "${DOCKER_USERNAME:-}" && -n "${DOCKER_PASSWORD:-${DOCKER_HUB_TOKEN:-}}" ]]; then
    echo "Logging into Docker Hub as ${DOCKER_USERNAME}..."
    if printf '%s\n' "${DOCKER_PASSWORD:-${DOCKER_HUB_TOKEN}}" \
      | docker login -u "$DOCKER_USERNAME" --password-stdin; then
        echo "OK: docker login"
    else
        echo "ERROR: docker login failed (check DOCKER_USERNAME / DOCKER_PASSWORD in .env.deploy)" >&2
        exit 1
    fi
elif ! docker system info 2>/dev/null | grep -qi "Username:"; then
    echo "WARNING: Not logged in to Docker Hub."
    echo "         Add to .env.deploy then re-run:"
    echo "           : \"\${DOCKER_USERNAME:=your-hub-user}\""
    echo "           : \"\${DOCKER_PASSWORD:=your-hub-access-token}\""
    echo "         Or once:  docker login"
    echo "         Continuing anyway — push will fail if auth is missing."
fi
echo ""

# ── Buildx setup ─────────────────────────────────────────────────────────────

echo "Setting up buildx..."
docker buildx create --name ontocode-builder --use --driver docker-container 2>/dev/null \
    || docker buildx use ontocode-builder
docker buildx inspect --bootstrap
echo ""

# ── Builds ───────────────────────────────────────────────────────────────────

trap fail ERR

should_build fuseki && {
    next_step "build+push ontocode-fuseki (fuseki)"
    echo " Image: $REGISTRY/ontocode-fuseki:6.1.0 (platform linux/amd64)"
    docker buildx build \
        --platform linux/amd64 \
        -t "$REGISTRY/ontocode-fuseki:6.1.0" \
        -f fuseki-docker/Dockerfile \
        --push \
        --progress=plain \
        fuseki-docker
    echo " [$(ts)] DONE  ontocode-fuseki:6.1.0 pushed  (${BUILD_INDEX}/${BUILD_TOTAL})"
}
should_build graphdb    && build "graphdb"      ontocode-graphdb     Dockerfile.graphdb
should_build auth       && build "auth"         ontocode-auth        Dockerfile.auth
should_build gateway    && build "gateway"      ontocode-gateway     Dockerfile.gateway
should_build editor     && build "editor"       ontocode-editor      Dockerfile.editor
should_build reasoner-worker && build "reasoner-worker" ontocode-reasoner-worker Dockerfile.reasoner-worker
should_build swrl       && build "swrl"         ontocode-swrl        Dockerfile.swrl
should_build plugin     && build "plugin"       ontocode-plugin      Dockerfile.plugin
should_build plugin-init && build "plugin-init" ontocode-plugin-init Dockerfile.plugin-init
should_build web        && build "web"          ontocode-web         Dockerfile.webapp "--no-cache"

trap - ERR

# ── Cleanup & summary ─────────────────────────────────────────────────────────

echo ""
echo "Cleaning up buildx builder..."
docker buildx rm ontocode-builder 2>/dev/null || true

echo ""
echo "============================================"
echo "   SUCCESS!"
echo "============================================"
echo ""
echo "To deploy on EC2:"
echo "  docker compose pull && docker compose up -d"
echo ""
echo "To deploy a single service on EC2:"
echo "  docker compose pull owl-editor && docker compose up -d owl-editor"
echo ""
