#!/bin/sh
set -e

echo "[OntoCode] Starting VS Code Web Extension..."

# ---------------------------------------------------------------
# Patch the Extension Host Web Worker iframe CSP
# The default CSP only allows HTTP to localhost/127.0.0.1, which
# blocks connections to cloud API servers (e.g. http://13.x.x.x).
# We add 'http:' and 'ws:' as general schemes so the extension
# host can reach any backend.
# ---------------------------------------------------------------
patch_csp() {
  local file="$1"
  if [ -f "$file" ]; then
    echo "[OntoCode] Patching CSP in: $file"
    sed -i "s|connect-src 'self' https: wss: http://localhost:\* http://127.0.0.1:\* ws://localhost:\* ws://127.0.0.1:\*|connect-src 'self' http: https: wss: ws: http://localhost:* http://127.0.0.1:* ws://localhost:* ws://127.0.0.1:*|g" "$file"
    echo "[OntoCode] CSP patched successfully."
  fi
}

# Patch any existing cached VS Code Web assets
for f in $(find /extension/.vscode-test-web /root/.vscode-test-web -name "webWorkerExtensionHostIframe.html" 2>/dev/null); do
  patch_csp "$f"
done

echo "[OntoCode] Starting VS Code Web Server on port ${VSCODE_WEB_PORT:-3000}..."

# Start vscode-test-web — it will reuse cached assets (already patched above)
exec vscode-test-web \
  --browserType=none \
  --host=${VSCODE_WEB_HOST:-0.0.0.0} \
  --port=${VSCODE_WEB_PORT:-3000} \
  --extensionDevelopmentPath=/extension \
  /workspace/projects
