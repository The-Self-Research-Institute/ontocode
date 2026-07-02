# Extract OntoCode Windows installer from the Docker build image.
# Run from repo root after: docker build -f electron-app/Dockerfile.win -t ontocode-desktop-win .

$ErrorActionPreference = "Stop"
$image = if ($args[0]) { $args[0] } else { "ontocode-desktop-win" }
$outDir = if ($args[1]) { $args[1] } else { (Resolve-Path (Join-Path $PSScriptRoot "..\dist-electron")).Path }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$id = docker create $image
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
try {
    docker cp "${id}:/dist-electron/." $outDir
    Write-Host "`nExtracted to: $outDir"
    Get-ChildItem $outDir -Filter "*.exe" | ForEach-Object { Write-Host "  Installer: $($_.FullName)" }
} finally {
    docker rm $id | Out-Null
}
