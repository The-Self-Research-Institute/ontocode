#!/bin/sh
set -e

echo "[OntoCode] Starting VS Code Web Extension..."

patch_csp() {
  local file="$1"
  if [ -f "$file" ]; then
    echo "[OntoCode] Patching CSP in: $file"
    sed -i "s|connect-src 'self' https: wss: http://localhost:\* http://127.0.0.1:\* ws://localhost:\* ws://127.0.0.1:\*|connect-src 'self' http: https: wss: ws: http://localhost:* http://127.0.0.1:* ws://localhost:* ws://127.0.0.1:*|g" "$file"
    echo "[OntoCode] CSP patched successfully."
  fi
}

IFRAME_FILE=$(find /extension/.vscode-test-web /root/.vscode-test-web -name "webWorkerExtensionHostIframe.html" 2>/dev/null | head -1)

if [ -z "$IFRAME_FILE" ]; then
  echo "[OntoCode] No cached VS Code Web assets found. Triggering download..."

  vscode-test-web \
    --browserType=none \
    --host=0.0.0.0 \
    --port=3001 \
    --extensionDevelopmentPath=/extension \
    /workspace/projects &
  DOWNLOAD_PID=$!

  echo "[OntoCode] Waiting for VS Code Web assets to download..."
  for i in $(seq 1 300); do
    IFRAME_FILE=$(find /extension/.vscode-test-web /root/.vscode-test-web -name "webWorkerExtensionHostIframe.html" 2>/dev/null | head -1)
    if [ -n "$IFRAME_FILE" ]; then
      echo "[OntoCode] Iframe file found after ${i}s"
      break
    fi
    sleep 1
  done

  echo "[OntoCode] Waiting for server to be fully ready..."
  for i in $(seq 1 300); do
    if wget -q --spider http://127.0.0.1:3001/ 2>/dev/null; then
      echo "[OntoCode] Temp server is ready after ${i}s more"
      break
    fi
    sleep 1
  done

  kill $DOWNLOAD_PID 2>/dev/null || true
  wait $DOWNLOAD_PID 2>/dev/null || true
  sleep 1
fi

PATCHED=0
for f in $(find /extension/.vscode-test-web /root/.vscode-test-web -name "webWorkerExtensionHostIframe.html" 2>/dev/null); do
  patch_csp "$f"
  PATCHED=1
done

if [ "$PATCHED" = "0" ]; then
  echo "[OntoCode] WARNING: Could not find webWorkerExtensionHostIframe.html to patch!"
  echo "[OntoCode] HTTP connections to external API servers may be blocked."
fi

echo "[OntoCode] Checking workspace directory..."
if [ ! -d "/workspace/projects" ]; then
  echo "[OntoCode] Creating workspace directory..."
  mkdir -p /workspace/projects
fi

chmod -R 755 /workspace 2>/dev/null || true

if [ ! -f "/workspace/projects/README.md" ]; then
  echo "[OntoCode] Initializing workspace with README..."
  cat > /workspace/projects/README.md << 'EOF'
# OntoCode Workspace

This folder is the workspace for VS Code Web running on your server.

## How to use:

1. Upload ontology files (.owl, .rdf, .ttl) using the "Open Local File" button in the editor
2. Or add files directly to this folder on your server
3. Files added here will appear in VS Code Web after a browser refresh

## Supported formats:
- .owl (OWL/RDF XML)
- .rdf (RDF XML)
- .ttl (Turtle)
- .n3 (Notation3)
- .nt (N-Triples)
- .jsonld (JSON-LD)

## Location:
- Container: /workspace/projects
- Host: ./data/projects (mounted via Docker volume)
EOF
  chmod 644 /workspace/projects/README.md
fi

if [ ! -d "/workspace/projects/.vscode" ]; then
  echo "[OntoCode] Creating .vscode workspace settings..."
  mkdir -p /workspace/projects/.vscode
  cat > /workspace/projects/.vscode/settings.json << 'EOF'
{
  "files.associations": {
    "*.owl": "xml",
    "*.rdf": "xml",
    "*.ttl": "turtle"
  },
  "files.exclude": {
    "**/.git": true,
    "**/.DS_Store": true
  }
}
EOF
  chmod 644 /workspace/projects/.vscode/settings.json
fi

echo "[OntoCode] Workspace contents:"
ls -laR /workspace/projects/ 2>/dev/null || echo "  (unable to list, permission issue?)"

echo "[OntoCode] Testing workspace write permissions..."
if touch /workspace/projects/.write-test 2>/dev/null; then
  rm -f /workspace/projects/.write-test
  echo "  ✓ Workspace is writable"
else
  echo "  ✗ WARNING: Workspace is not writable!"
fi

APP_JS=$(find /usr/local/lib/node_modules/@vscode/test-web -name "app.js" -path "*/server/*" 2>/dev/null | head -1)
if [ -n "$APP_JS" ]; then
  if ! grep -q "app.proxy" "$APP_JS"; then
    sed -i 's/const app = new Koa();/const app = new Koa();\n    app.proxy = true;/' "$APP_JS"
    echo "[OntoCode] Patched Koa app.proxy = true for X-Forwarded-Proto support"
  else
    echo "[OntoCode] Koa app.proxy already set"
  fi
fi

echo "[OntoCode] Starting VS Code Web Server on port ${VSCODE_WEB_PORT:-3000}..."
echo "[OntoCode] Workspace folder: /workspace/projects"
echo "[OntoCode] Extension path: /extension"

exec vscode-test-web \
  --browserType=none \
  --host=${VSCODE_WEB_HOST:-0.0.0.0} \
  --port=${VSCODE_WEB_PORT:-3000} \
  --extensionDevelopmentPath=/extension \
  /workspace/projects
