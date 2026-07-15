#!/usr/bin/env bash
# Extract OntoCode Windows installer from the Docker build image.
# Usage (repo root): ./electron-app/scripts/docker-extract-desktop.sh [image] [out-dir]

set -euo pipefail
IMAGE="${1:-ontocode-desktop-win}"
OUT_DIR="${2:-$(cd "$(dirname "$0")/.." && pwd)/dist-electron}"
mkdir -p "$OUT_DIR"
ID="$(docker create "$IMAGE")"
trap 'docker rm -f "$ID" >/dev/null 2>&1 || true' EXIT
docker cp "$ID:/dist-electron/." "$OUT_DIR"
echo ""
echo "Extracted to: $OUT_DIR"
ls -la "$OUT_DIR"/*.exe 2>/dev/null || ls -la "$OUT_DIR"
