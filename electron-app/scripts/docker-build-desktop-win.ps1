# One-shot: Docker build Windows OntoCode.exe installer + extract to electron-app/dist-electron
# Run from repo root:  .\electron-app\scripts\docker-build-desktop-win.ps1

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$dockerfile = Join-Path $repoRoot "electron-app\Dockerfile.win"
$image = "ontocode-desktop-win"

Write-Host "=== Docker build: Windows OntoCode installer ===" -ForegroundColor Cyan
Write-Host "Context: $repoRoot"
docker build -f $dockerfile -t $image $repoRoot
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& (Join-Path $PSScriptRoot "docker-extract-desktop.ps1") $image
Write-Host "`nDone. Run: electron-app\dist-electron\win-unpacked\OntoCode.exe" -ForegroundColor Green
