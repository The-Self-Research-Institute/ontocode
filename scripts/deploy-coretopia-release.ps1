# =============================================================================
# OntoCode - Windows driver for sindhujacoretopia deploy + desktop upload
#
# From repo root (PowerShell):
#   .\scripts\deploy-coretopia-release.ps1
#   .\scripts\deploy-coretopia-release.ps1 -Services auth,editor,web,gateway
#   .\scripts\deploy-coretopia-release.ps1 -PushOnly
#   .\scripts\deploy-coretopia-release.ps1 -DesktopWin -UploadDesktop
#   .\scripts\deploy-coretopia-release.ps1 -Include fuseki
#
# Env / params:
#   -Registry sindhujacoretopia  -Version latest
#   -Ec2Host ubuntu@your-ec2-dns   -Ec2Dir /opt/ontocode
#   -ApiBase https://ontocodeapi.selfresearch.org
#   -AdminUser / -AdminPassword (for GridFS installer upload)
# =============================================================================
param(
    [string]$Registry = $(if ($env:REGISTRY) { $env:REGISTRY } else { "sindhujacoretopia" }),
    [string]$Version = $(if ($env:VERSION) { $env:VERSION } else { "latest" }),
    [string[]]$Services = @("auth", "gateway", "editor", "web"),
    [string[]]$Include = @(),
    [string]$Ec2Host = $env:EC2_HOST,
    [string]$Ec2Dir = $(if ($env:EC2_DIR) { $env:EC2_DIR } else { "/opt/ontocode" }),
    [string]$ComposeFile = "docker-compose.production.yml",
    [string]$ApiBase = $(if ($env:API_BASE) { $env:API_BASE } else { "https://ontocodeapi.selfresearch.org" }),
    [string]$AdminUser = $env:ADMIN_USER,
    [string]$AdminPassword = $env:ADMIN_PASSWORD,
    [switch]$PushOnly,
    [switch]$DeployOnly,
    [switch]$DesktopWin,
    [switch]$DesktopMac,
    [switch]$DesktopLinux,
    [switch]$UploadDesktop,
    [switch]$UseAmd64Only
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

if ($Include.Count -gt 0) {
    $Services = $Services + $Include
}

$map = @{
    "editor"  = "owl-editor"
    "reasoner-worker" = "reasoner-worker"
    "auth"    = "ontology-auth"
    "gateway" = "gateway"
    "web"     = "ontocode-web"
    "swrl"    = "swrl-service"
    "plugin"  = @("plugin-service", "plugin-init")
    "fuseki"  = "fuseki"
}

# Docker image build definitions (same as build-and-push.sh)
$dockerBuilds = @{
    "graphdb"      = @{ Tag = "ontocode-graphdb";     File = "Dockerfile.graphdb" }
    "auth"         = @{ Tag = "ontocode-auth";        File = "Dockerfile.auth" }
    "gateway"      = @{ Tag = "ontocode-gateway";     File = "Dockerfile.gateway" }
    "editor"       = @{ Tag = "ontocode-editor";      File = "Dockerfile.editor" }
    "reasoner-worker" = @{ Tag = "ontocode-reasoner-worker"; File = "Dockerfile.reasoner-worker" }
    "swrl"         = @{ Tag = "ontocode-swrl";        File = "Dockerfile.swrl" }
    "plugin"       = @{ Tag = "ontocode-plugin";      File = "Dockerfile.plugin" }
    "plugin-init"  = @{ Tag = "ontocode-plugin-init"; File = "Dockerfile.plugin-init" }
    "web"          = @{ Tag = "ontocode-web";           File = "Dockerfile.webapp"; Extra = "--no-cache" }
}

function Test-DockerAvailable {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return $false }
    docker version 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
}

function Get-GitBashExe {
    foreach ($p in @(
        "${env:ProgramFiles}\Git\bin\bash.exe",
        "${env:ProgramFiles(x86)}\Git\bin\bash.exe",
        "$env:LOCALAPPDATA\Programs\Git\bin\bash.exe"
    )) {
        if (Test-Path $p) { return $p }
    }
    return $null
}

function Invoke-BuildPushNative {
    param(
        [string[]]$Names,
        [string]$Platforms
    )
    $platformLabel = $Platforms
    Write-Host "============================================"
    Write-Host " Building Docker images (PowerShell + docker)"
    Write-Host " Registry : $Registry"
    Write-Host " Version  : $Version"
    Write-Host " Platforms: $platformLabel"
    Write-Host " Services : $($Names -join ' ')"
    Write-Host "============================================"

    Push-Location $Root
    try {
        Write-Host "Setting up buildx..."
        docker buildx create --name ontocode-builder --use --driver docker-container 2>$null
        if ($LASTEXITCODE -ne 0) { docker buildx use ontocode-builder 2>$null }
        docker buildx inspect --bootstrap
        if ($LASTEXITCODE -ne 0) { throw "docker buildx setup failed" }

        foreach ($name in $Names) {
            if ($name -eq "fuseki") {
                Write-Host ""
                Write-Host "-- fuseki --"
                docker buildx build --platform linux/amd64 `
                    -t "${Registry}/ontocode-fuseki:6.1.0" `
                    -f fuseki-docker/Dockerfile --push fuseki-docker
                if ($LASTEXITCODE -ne 0) { throw "fuseki build failed" }
                Write-Host "OK ontocode-fuseki:6.1.0 pushed"
                continue
            }
            if (-not $dockerBuilds.ContainsKey($name)) {
                Write-Host "WARN: unknown service '$name' - skipped"
                continue
            }
            $b = $dockerBuilds[$name]
            Write-Host ""
            Write-Host "-- $name ($($b.Tag)) --"
            $buildArgs = @(
                "buildx", "build",
                "--platform", $Platforms,
                "-t", "${Registry}/$($b.Tag):$Version",
                "-f", $b.File
            )
            if ($b.Extra) { $buildArgs += $b.Extra }
            $buildArgs += @("--push", ".")
            & docker @buildArgs
            if ($LASTEXITCODE -ne 0) { throw "$($b.Tag) build failed" }
            Write-Host "OK $($b.Tag) pushed"
        }

        docker buildx rm ontocode-builder 2>$null
    } finally {
        Pop-Location
    }
}

function Invoke-BuildPush {
    param([string[]]$Names)
    $platforms = if ($UseAmd64Only) { "linux/amd64" } else { "linux/amd64,linux/arm64" }

    # Prefer Windows Docker Desktop (PowerShell) - avoids WSL bash without docker.
    if (Test-DockerAvailable) {
        Write-Host ">> Using Docker from Windows (Docker Desktop)..."
        Invoke-BuildPushNative -Names $Names -Platforms $platforms
        return
    }

    if ($UseAmd64Only) {
        Write-Host ">> AMD64-only build - uses build-and-push-amd64.bat (all services)"
        & "$Root\build-and-push-amd64.bat" $Registry $Version
        if ($LASTEXITCODE -ne 0) { throw "build-and-push-amd64.bat failed (exit $LASTEXITCODE)" }
        return
    }

    $gitBash = Get-GitBashExe
    if ($gitBash) {
        Write-Host ">> Using Git Bash (not WSL): $gitBash"
        Push-Location $Root
        try {
            & $gitBash -lc "./build-and-push.sh '$Registry' '$Version' $($Names -join ' ')"
            if ($LASTEXITCODE -ne 0) { throw "build-and-push.sh failed (exit $LASTEXITCODE)" }
        } finally {
            Pop-Location
        }
        return
    }

    throw @"
Docker is not available in this shell.
- Start Docker Desktop and ensure 'docker' works in PowerShell:  docker version
- Or enable WSL integration for your distro in Docker Desktop settings
- Do not rely on default 'bash' if it points to WSL without docker
"@
}

function Invoke-Ec2Deploy {
    param([string[]]$Names)
    if (-not $Ec2Host) {
        Write-Host ">> Skipping EC2 deploy - set -Ec2Host or `$env:EC2_HOST"
        return
    }
    $compose = @()
    foreach ($n in $Names) {
        if ($map.ContainsKey($n)) {
            $v = $map[$n]
            if ($v -is [array]) { $compose += $v } else { $compose += $v }
        }
    }
    $pull = ($compose | ForEach-Object { "docker compose -f $ComposeFile pull $_" }) -join " && "
    $up   = ($compose | ForEach-Object { "docker compose -f $ComposeFile up -d $_" }) -join " && "
    $remote = "cd $Ec2Dir && $pull && $up && docker compose -f $ComposeFile ps"
    Write-Host ">> SSH deploy: $Ec2Host"
    ssh $Ec2Host $remote
}

function Build-Desktop {
    param([string]$Target)
    Push-Location "$Root\electron-app"
    switch ($Target) {
        "win"   { npm run dist:win }
        "mac"   { npm run dist:mac }
        "linux" { npm run dist:linux }
    }
    Pop-Location
}

function Upload-Installer {
    param([string]$Platform, [string]$FilePath)
    if (-not (Test-Path $FilePath)) { throw "File not found: $FilePath" }
    if (-not $AdminUser -or -not $AdminPassword) {
        throw "Set -AdminUser and -AdminPassword (or env ADMIN_USER / ADMIN_PASSWORD)"
    }
    $loginBody = @{ username = $AdminUser; password = $AdminPassword } | ConvertTo-Json
    $login = Invoke-RestMethod -Uri "$ApiBase/api/auth/login" -Method POST -Body $loginBody -ContentType "application/json"
    $token = $login.jwt
    if (-not $token) { throw "Login failed: no jwt in response" }

    $filename = [System.IO.Path]::GetFileName($FilePath)
    $pkgJson = Get-Content "$Root\electron-app\package.json" -Raw | ConvertFrom-Json
    $version = $pkgJson.version
    $releaseNotes = "Beta $version - in-app update testing"
    Write-Host ">> Upload $filename for platform=$Platform version=$version"
    curl.exe -f -X POST "$ApiBase/api/downloads/upload" `
        -H "Authorization: Bearer $token" `
        -F "platform=$Platform" `
        -F "filename=$filename" `
        -F "version=$version" `
        -F "releaseNotes=$releaseNotes" `
        -F "file=@$FilePath"
    Write-Host "   Download URL: $ApiBase/api/downloads/$Platform"
}

Write-Host "============================================"
Write-Host " Registry : $Registry  Version: $Version"
Write-Host " Services : $($Services -join ', ')"
Write-Host "============================================"

if (-not $DeployOnly) {
    Invoke-BuildPush -Names $Services
}

if (-not $PushOnly) {
    Invoke-Ec2Deploy -Names $Services
}

if ($DesktopWin) {
    Write-Host ">> Windows NSIS installer..."
    Build-Desktop win
    Get-ChildItem "$Root\electron-app\dist-electron\*.exe" -ErrorAction SilentlyContinue
}

if ($DesktopMac) {
    Write-Host ">> macOS DMG (run on macOS)..."
    Build-Desktop mac
}

if ($DesktopLinux) {
    Write-Host ">> Linux AppImage + deb..."
    Build-Desktop linux
}

if ($UploadDesktop) {
    $dist = "$Root\electron-app\dist-electron"
    $exe = Get-ChildItem "$dist\*Setup*x64*.exe" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($exe) { Upload-Installer -Platform "windows-x64" -FilePath $exe.FullName }
    $dmgArm = Get-ChildItem "$dist\*arm64*.dmg" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($dmgArm) { Upload-Installer -Platform "mac-arm64" -FilePath $dmgArm.FullName }
    $appImage = Get-ChildItem "$dist\*.AppImage" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($appImage) { Upload-Installer -Platform "linux-x64" -FilePath $appImage.FullName }
    $deb = Get-ChildItem "$dist\*.deb" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($deb) { Upload-Installer -Platform "linux-deb" -FilePath $deb.FullName }
}

Write-Host "Done."
