#!/usr/bin/env bash

set -euo pipefail

export PATH="/usr/local/bin:/usr/bin:/bin:${PATH:-}"

_clean=""
IFS=':' read -ra _parts <<< "${PATH}"
for _p in "${_parts[@]}"; do
  case "$_p" in /mnt/[a-zA-Z]/*) continue ;; *) _clean="${_clean:+$_clean:}$_p" ;; esac
done
export PATH="/usr/local/bin:/usr/bin:/bin${_clean:+:$_clean}"
hash -r 2>/dev/null || true

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

echo "== Docker config (no Windows credential helpers) =="
mkdir -p "$HOME/.docker"
CFG="$HOME/.docker/config.json"
if [[ -f "$CFG" ]]; then
  cp -a "$CFG" "$CFG.bak.ontocode.$(date +%s)" || true
fi
python3 - "$CFG" <<'PY'
import json, os, sys
p = sys.argv[1]
data = {}
if os.path.isfile(p):
    try:
        with open(p) as f:
            data = json.load(f)
    except Exception:
        data = {}
for k in ("credsStore", "credStore"):
    v = str(data.get(k, ""))
    if "desktop" in v.lower() or v.endswith(".exe") or "wincred" in v.lower():
        data.pop(k, None)
helpers = data.get("credHelpers") or {}
if isinstance(helpers, dict):
    for h, v in list(helpers.items()):
        if isinstance(v, str) and ("desktop" in v.lower() or v.endswith(".exe")):
            helpers.pop(h, None)
    if helpers:
        data["credHelpers"] = helpers
    else:
        data.pop("credHelpers", None)
if data.get("currentContext") in ("desktop-linux", "desktop-windows"):
    data.pop("currentContext", None)
with open(p, "w") as f:
    json.dump(data, f, indent=2)
    f.write("\n")
print(open(p).read())
PY

echo "== Start Docker Engine =="
sudo service docker start >/dev/null 2>&1 || sudo systemctl start docker >/dev/null 2>&1 || true
sleep 2
if [[ -S /var/run/docker.sock ]]; then
  sudo chmod 666 /var/run/docker.sock 2>/dev/null || true
fi
docker info >/dev/null
echo "OK: $(command -v docker) daemon up"

echo "== Docker Hub connectivity =="
if ! curl -fsS --connect-timeout 25 -o /dev/null \
  "https://auth.docker.io/token?service=registry.docker.io"; then
  echo "WARN: auth.docker.io not reachable yet — will retry pulls"
else
  echo "OK: auth.docker.io reachable"
fi

echo "== Pre-pull base images (retries) =="
pull_retry() {
  local img="$1" n=1
  while [[ $n -le 5 ]]; do
    echo "  pull $img (try $n/5)..."
    if docker pull "$img"; then
      return 0
    fi
    sleep $((n * 5))
    n=$((n + 1))
  done
  echo "ERROR: failed to pull $img"
  return 1
}

pull_retry maven:3.9-eclipse-temurin-21-alpine || true
pull_retry eclipse-temurin:21-jre || true

echo "== Fix webview-src Rollup Linux optional deps =="
WEB="$ROOT/ontology-vscode-extension/webview-src"
if [[ -d "$WEB" ]]; then
  cd "$WEB"

  rm -rf node_modules
  if [[ -f package-lock.json ]]; then
    npm ci --no-fund --no-audit || npm install --no-fund --no-audit
  else
    npm install --no-fund --no-audit
  fi

  npm install --no-save @rollup/rollup-linux-x64-gnu --no-fund --no-audit || true
  node -e "require('rollup'); console.log('OK: rollup loads')"
  cd "$ROOT"
fi

echo "== Ensure buildx builder =="
docker buildx create --name ontocode-builder --use --driver docker-container 2>/dev/null \
  || docker buildx use ontocode-builder
docker buildx inspect --bootstrap >/dev/null
echo "OK: buildx ready"
echo "ALL FIXES DONE"
