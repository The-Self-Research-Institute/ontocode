#!/usr/bin/env bash
set -euo pipefail
export PATH="/usr/bin:/usr/local/bin:/bin:${PATH:-}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/.env.deploy"
sudo service docker start >/dev/null 2>&1 || true
sudo chmod 666 /var/run/docker.sock 2>/dev/null || true
: "${DOCKER_USERNAME:?missing DOCKER_USERNAME}"
: "${DOCKER_PASSWORD:?missing DOCKER_PASSWORD}"
printf '%s\n' "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin
echo "LOGIN_OK as $DOCKER_USERNAME"
