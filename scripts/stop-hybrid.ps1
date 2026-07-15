# Stop hybrid dev: Docker mongo+fuseki and Maven Java listeners on known ports.
#   .\scripts\stop-hybrid.ps1
#   .\scripts\stop-hybrid.ps1 -KeepDocker   # only kill Java, leave mongo/fuseki up

param([switch]$KeepDocker)

$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

Write-Host "=== Stopping OntoCode hybrid dev ===" -ForegroundColor Cyan

$ports = @(80, 8083, 8084, 8086, 8087)
foreach ($port in $ports) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($c in $conns) {
        $proc = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
        if ($proc -and $proc.ProcessName -match "java") {
            Write-Host "  Stopping $($proc.ProcessName) (PID $($proc.Id)) on port $port"
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

if (-not $KeepDocker) {
    Push-Location $Root
    try {
        docker compose -f docker-compose.dev.yml stop mongo fuseki 2>&1 | Out-Host
    } finally {
        Pop-Location
    }
    Write-Host "  Docker mongo + fuseki stopped"
} else {
    Write-Host "  Docker left running (-KeepDocker)"
}

Write-Host "Done." -ForegroundColor Green
