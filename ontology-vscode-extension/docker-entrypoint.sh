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

# Check if VS Code Web assets are already downloaded
IFRAME_FILE=$(find /extension/.vscode-test-web /root/.vscode-test-web -name "webWorkerExtensionHostIframe.html" 2>/dev/null | head -1)

if [ -z "$IFRAME_FILE" ]; then
  echo "[OntoCode] No cached VS Code Web assets found. Triggering download..."

  # Start vscode-test-web on a temp port to trigger the VS Code Insiders download
  vscode-test-web \
    --browserType=none \
    --host=0.0.0.0 \
    --port=3001 \
    --extensionDevelopmentPath=/extension \
    /workspace/projects &
  DOWNLOAD_PID=$!

  # Wait for iframe file to appear first
  echo "[OntoCode] Waiting for VS Code Web assets to download..."
  for i in $(seq 1 300); do
    IFRAME_FILE=$(find /extension/.vscode-test-web /root/.vscode-test-web -name "webWorkerExtensionHostIframe.html" 2>/dev/null | head -1)
    if [ -n "$IFRAME_FILE" ]; then
      echo "[OntoCode] Iframe file found after ${i}s"
      break
    fi
    sleep 1
  done

  # Now wait for the download to fully complete by checking the "Listening on" message
  # which vscode-test-web prints only after all assets are ready
  echo "[OntoCode] Waiting for server to be fully ready..."
  for i in $(seq 1 300); do
    if wget -q --spider http://127.0.0.1:3001/ 2>/dev/null; then
      echo "[OntoCode] Temp server is ready after ${i}s more"
      break
    fi
    sleep 1
  done

  # Stop the temporary server now that download is fully complete
  kill $DOWNLOAD_PID 2>/dev/null || true
  wait $DOWNLOAD_PID 2>/dev/null || true
  sleep 1
fi

# Patch all iframe files found
PATCHED=0
for f in $(find /extension/.vscode-test-web /root/.vscode-test-web -name "webWorkerExtensionHostIframe.html" 2>/dev/null); do
  patch_csp "$f"
  PATCHED=1
done

if [ "$PATCHED" = "0" ]; then
  echo "[OntoCode] WARNING: Could not find webWorkerExtensionHostIframe.html to patch!"
  echo "[OntoCode] HTTP connections to external API servers may be blocked."
fi

echo "[OntoCode] Starting VS Code Web Server on port ${VSCODE_WEB_PORT:-3000}..."

# Start the real server — assets are fully downloaded and patched, no re-download needed
exec vscode-test-web \
  --browserType=none \
  --host=${VSCODE_WEB_HOST:-0.0.0.0} \
  --port=${VSCODE_WEB_PORT:-3000} \
  --extensionDevelopmentPath=/extension \
  /workspace/projects
