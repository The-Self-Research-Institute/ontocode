@echo off
REM Patch the VS Code Web Extension Host CSP to allow external HTTP connections
REM Run this after .vscode-test-web is regenerated (e.g., after npm run test-web)

echo Patching Extension Host CSP for external HTTP connections...

set "SEARCH_DIR=ontology-vscode-extension\.vscode-test-web"

for /r "%SEARCH_DIR%" %%f in (webWorkerExtensionHostIframe.html) do (
    echo Found: %%f
    powershell -Command "(Get-Content '%%f') -replace \"connect-src 'self' https: wss: http://localhost:\* http://127.0.0.1:\* ws://localhost:\* ws://127.0.0.1:\*\", \"connect-src 'self' http: https: wss: ws: http://localhost:* http://127.0.0.1:* ws://localhost:* ws://127.0.0.1:*\" | Set-Content '%%f'"
    echo Patched: %%f
)

echo Done! Restart the VS Code Web server for changes to take effect.
