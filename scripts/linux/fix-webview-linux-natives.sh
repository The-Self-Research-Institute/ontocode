#!/usr/bin/env bash
set -euo pipefail
export PATH="/usr/bin:/usr/local/bin:/bin:${PATH:-}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WEB="$ROOT/ontology-vscode-extension/webview-src"
cd "$WEB"
echo "node=$(command -v node) $(node -v)"
echo "cwd=$PWD"
npm install --no-fund --no-audit @rollup/rollup-linux-x64-gnu @esbuild/linux-x64
node -e 'require("rollup"); console.log("OK: rollup loads")'
echo "rollup packages:"
ls -1 node_modules/@rollup 2>/dev/null || true
