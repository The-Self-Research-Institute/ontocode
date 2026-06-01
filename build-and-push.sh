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
#   fuseki  graphdb  auth  gateway  editor  swrl  plugin  plugin-init  web
#
# On EC2 (pull & restart after pushing):
#   ./build-and-push.sh ontocode latest editor && \
#   ssh ec2 "cd /opt/ontocode && docker compose pull owl-editor && docker compose up -d owl-editor"

set -euo pipefail

REGISTRY=${1:-ontocode}
VERSION=${2:-latest}
# Everything from arg 3 onward is a service filter (empty = build all)
shift 2 2>/dev/null || true
FILTER=("$@")

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

build() {
    local label="$1"   # e.g. "[2/8] auth"
    local tag="$2"     # e.g. ontocode-auth
    local file="$3"    # e.g. Dockerfile.auth
    local extra="${4:-}"  # optional extra flags like --no-cache

    echo ""
    echo "── $label ──────────────────────────────────"
    # shellcheck disable=SC2086
    docker buildx build \
        --platform linux/amd64,linux/arm64 \
        -t "$REGISTRY/$tag:$VERSION" \
        -f "$file" \
        $extra \
        --push .
    echo "✓ $tag pushed"
}

fail() {
    echo ""
    echo "============================================"
    echo "   BUILD FAILED — see error above"
    echo "============================================"
    docker buildx rm ontocode-builder 2>/dev/null || true
    exit 1
}

# ── Header ────────────────────────────────────────────────────────────────────

echo "============================================"
echo "   Building Multi-Platform OntoCode Images"
echo "   Registry : $REGISTRY"
echo "   Version  : $VERSION"
echo "   Platforms: linux/amd64, linux/arm64"
if [ ${#FILTER[@]} -gt 0 ]; then
    echo "   Services : ${FILTER[*]}"
else
    echo "   Services : ALL"
fi
echo "============================================"
echo ""

# ── Buildx setup ─────────────────────────────────────────────────────────────

echo "Setting up buildx..."
docker buildx create --name ontocode-builder --use --driver docker-container 2>/dev/null \
    || docker buildx use ontocode-builder
docker buildx inspect --bootstrap
echo ""

# ── Builds ───────────────────────────────────────────────────────────────────

trap fail ERR

should_build fuseki     && docker buildx build \
    --platform linux/amd64 \
    -t "$REGISTRY/ontocode-fuseki:6.1.0" \
    -f fuseki-docker/Dockerfile \
    --push fuseki-docker && echo "✓ ontocode-fuseki:6.1.0 pushed"
should_build graphdb    && build "[1/8] graphdb"     ontocode-graphdb     Dockerfile.graphdb
should_build auth       && build "[2/8] auth"        ontocode-auth        Dockerfile.auth
should_build gateway    && build "[3/8] gateway"     ontocode-gateway     Dockerfile.gateway
should_build editor     && build "[4/8] editor"      ontocode-editor      Dockerfile.editor
should_build swrl       && build "[5/8] swrl"        ontocode-swrl        Dockerfile.swrl
should_build plugin     && build "[6/8] plugin"      ontocode-plugin      Dockerfile.plugin
should_build plugin-init && build "[7/8] plugin-init" ontocode-plugin-init Dockerfile.plugin-init
should_build web        && build "[8/8] web"         ontocode-web         Dockerfile.webapp "--no-cache"

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
