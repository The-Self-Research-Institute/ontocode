#Requires -Version 5.1
<#
.SYNOPSIS
  Windows-host prerequisites for OntoCode (.exe / desktop). Can auto-install missing JDKs.

.EXAMPLE
  .\scripts\check-windows-prereqs.ps1 -ForDesktopExe -AutoInstall
#>
[CmdletBinding()]
param(
    [switch]$ForDesktopExe,
    [switch]$RequireDocker,
    [switch]$Soft,
    [switch]$AutoInstall,
    [switch]$NoAutoInstall
)

# Default: auto-install when used from build-and-push (pass -AutoInstall explicitly from cmd).
if ($NoAutoInstall) { $AutoInstall = $false }

$ErrorActionPreference = "Continue"
$failed = @()

function Write-Ok($m) { Write-Host "[OK] $m" -ForegroundColor Green }
function Write-Bad($m) { Write-Host "[MISSING] $m" -ForegroundColor Red; $script:failed += $m }
function Write-Warn($m) { Write-Host "[WARN] $m" -ForegroundColor Yellow }
function Write-Info($m) { Write-Host "[..] $m" -ForegroundColor Cyan }

function Find-JdkHome([int]$Major) {
    foreach ($var in @("JAVA_HOME_$Major", "JDK_${Major}_HOME", "JAVA${Major}_HOME")) {
        $val = [Environment]::GetEnvironmentVariable($var, "Process")
        if (-not $val) { $val = [Environment]::GetEnvironmentVariable($var, "User") }
        if (-not $val) { $val = [Environment]::GetEnvironmentVariable($var, "Machine") }
        if ($val -and (Test-Path (Join-Path $val "bin\java.exe"))) { return $val }
    }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        $line = & (Join-Path $env:JAVA_HOME "bin\java.exe") -version 2>&1 | Select-Object -First 1 | Out-String
        if ($line -match "version `"$Major" -or $line -match "version `"1\.$Major") { return $env:JAVA_HOME }
    }
    foreach ($root in @(
        "${env:ProgramFiles}\Java",
        "${env:ProgramFiles}\Eclipse Adoptium",
        "${env:ProgramFiles}\Microsoft",
        "${env:ProgramFiles}\Amazon Corretto"
    )) {
        if (-not (Test-Path $root)) { continue }
        $hit = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match "(jdk-?$Major|jdk$Major|temurin-$Major|ms-?$Major)" } |
            Select-Object -First 1
        if ($hit -and (Test-Path (Join-Path $hit.FullName "bin\java.exe"))) { return $hit.FullName }
    }
    return $null
}

function Update-SessionPathFromMachine {
    $machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = "$machinePath;$userPath"
}

function Install-WingetPackage([string]$Id, [string]$Label) {
    $winget = Get-Command winget -ErrorAction SilentlyContinue
    if (-not $winget) { return $false }
    Write-Info "Installing $Label via winget ($Id)..."
    Write-Host "         (may prompt for admin / UAC)" -ForegroundColor Gray
    & winget install --id $Id -e --accept-package-agreements --accept-source-agreements --disable-interactivity
    $ok = ($LASTEXITCODE -eq 0 -or $LASTEXITCODE -eq -1978335189) # already installed codes vary
    # -1978335189 = INFO_PACKAGE_ALREADY_INSTALLED sometimes; also accept 0
    if ($LASTEXITCODE -eq 0) { return $true }
    # Some winget versions return other success-ish codes when already present
    if ($LASTEXITCODE -in @( -1978335189, -1978335135 )) { return $true }
    Write-Warn "winget exit code $LASTEXITCODE for $Id"
    return ($LASTEXITCODE -eq 0)
}

function Install-ChocoPackage([string]$Name, [string]$Label) {
    $choco = Get-Command choco -ErrorAction SilentlyContinue
    if (-not $choco) { return $false }
    Write-Info "Installing $Label via chocolatey ($Name)..."
    & choco install $Name -y
    return ($LASTEXITCODE -eq 0)
}

function Ensure-Jdk([int]$Major) {
    $jdkHome = Find-JdkHome $Major
    if ($jdkHome) {
        Write-Ok "JDK $Major -> $jdkHome"
        return $true
    }

    if (-not $AutoInstall) {
        if ($ForDesktopExe) { Write-Bad "JDK $Major (SWRL / desktop)" }
        else { Write-Warn "JDK $Major not found (set JAVA_HOME_$Major)" }
        return $false
    }

    Write-Warn "JDK $Major missing - attempting auto-install..."
    $wingetId = "Microsoft.OpenJDK.$Major"
    $chocoName = if ($Major -eq 17) { "microsoft-openjdk17" } else { "microsoft-openjdk21" }

    $installed = Install-WingetPackage -Id $wingetId -Label "JDK $Major"
    if (-not $installed) {
        $installed = Install-ChocoPackage -Name $chocoName -Label "JDK $Major"
    }

    Update-SessionPathFromMachine
    # Give the installer a moment; refresh lookup
    Start-Sleep -Seconds 2
    $jdkHome = Find-JdkHome $Major
    if ($jdkHome) {
        Write-Ok "JDK $Major installed -> $jdkHome"
        # Persist helper env for this user (optional convenience)
        [Environment]::SetEnvironmentVariable("JAVA_HOME_$Major", $jdkHome, "User")
        Set-Item -Path "Env:JAVA_HOME_$Major" -Value $jdkHome
        return $true
    }

    if ($ForDesktopExe) {
        Write-Bad "JDK $Major - auto-install failed. Install manually: winget install -e --id Microsoft.OpenJDK.$Major"
    } else {
        Write-Warn "JDK $Major still missing after auto-install attempt"
    }
    return $false
}

Write-Host ""
Write-Host "======== Windows prerequisites ========" -ForegroundColor Cyan
Write-Host "Purpose: Windows .exe / host tools (not WSL)" -ForegroundColor Gray
if ($AutoInstall) { Write-Host "Auto-install: ON (winget/choco for missing JDKs)" -ForegroundColor Gray }

# Node
$node = Get-Command node -ErrorAction SilentlyContinue
$npm = Get-Command npm -ErrorAction SilentlyContinue
if ($node -and $npm) {
    Write-Ok "Node $($node.Source) ($(node -v)) / npm $(npm -v)"
} else {
    if ($AutoInstall) {
        Write-Warn "Node.js missing - attempting winget install OpenJS.NodeJS.LTS..."
        $okNode = Install-WingetPackage -Id "OpenJS.NodeJS.LTS" -Label "Node.js LTS"
        Update-SessionPathFromMachine
        $node = Get-Command node -ErrorAction SilentlyContinue
        $npm = Get-Command npm -ErrorAction SilentlyContinue
        if ($node -and $npm) { Write-Ok "Node $(node -v) / npm $(npm -v)" }
        elseif ($ForDesktopExe) { Write-Bad "Node.js + npm (required for Windows .exe)" }
        else { Write-Warn "Node.js/npm still missing" }
    }
    elseif ($ForDesktopExe) {
        Write-Bad "Node.js + npm (required for Windows .exe via electron-builder)"
        Write-Host "         Install: https://nodejs.org/ (LTS 18+)" -ForegroundColor Yellow
    } else {
        Write-Warn "Node.js/npm not on Windows PATH (needed for .exe builds)"
    }
}

# JDKs (auto-install when requested)
$null = Ensure-Jdk 17
$null = Ensure-Jdk 21

# Maven
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvn) { Write-Ok "Maven -> $($mvn.Source)" }
elseif ($ForDesktopExe) {
    if ($AutoInstall -and (Get-Command choco -ErrorAction SilentlyContinue)) {
        Write-Warn "Maven missing - attempting choco install maven..."
        if (Install-ChocoPackage -Name "maven" -Label "Maven") {
            Update-SessionPathFromMachine
            $mvn = Get-Command mvn -ErrorAction SilentlyContinue
            if ($mvn) { Write-Ok "Maven -> $($mvn.Source)" }
            else { Write-Warn "Maven installed but not yet on PATH - open a new terminal if builds fail" }
        }
    } else {
        Write-Warn "Maven not on PATH (build-desktop.bat may need it for jars)"
    }
}

# Docker Desktop
$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($docker) {
    docker info 1>$null 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Ok "Docker Desktop engine reachable from Windows"
    } else {
        if ($RequireDocker) { Write-Bad "Docker Desktop installed but engine not running - start Docker Desktop" }
        else { Write-Warn "Docker Desktop engine not running (needed for --platform web)" }
    }
} else {
    if ($RequireDocker) { Write-Bad "Docker Desktop not on PATH" }
    else { Write-Warn "Docker not on Windows PATH" }
}

# WSL
$wsl = Get-Command wsl -ErrorAction SilentlyContinue
if ($wsl) { Write-Ok "WSL available (used for Docker builds / Linux / VSIX)" }
else { Write-Bad "WSL not found (install: wsl --install -d Ubuntu-22.04)" }

Write-Host "========================================" -ForegroundColor Cyan

if ($failed.Count -gt 0) {
    Write-Host ""
    Write-Host "Windows prerequisites missing:" -ForegroundColor Red
    $failed | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Host ""
    if ($AutoInstall) {
        Write-Host "Auto-install was attempted but some items still failed (often needs Admin / UAC)." -ForegroundColor Yellow
        Write-Host "Re-run this CMD as Administrator, or install manually:" -ForegroundColor Yellow
        Write-Host "  winget install -e --id Microsoft.OpenJDK.17" -ForegroundColor Yellow
        Write-Host "  winget install -e --id Microsoft.OpenJDK.21" -ForegroundColor Yellow
    }
    if (-not $Soft) { exit 1 }
    exit 0
}

Write-Host "[OK] Windows prerequisites satisfied" -ForegroundColor Green
exit 0
