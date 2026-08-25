#!/usr/bin/env pwsh
<#
.SYNOPSIS
    OntoCode - One-Click Installation (PowerShell)

.DESCRIPTION
    This script will:
    1. Check prerequisites (Docker, Node.js)
    2. Build all Docker services
    3. Start the entire platform
    4. Build and launch the VS Code web extension

.EXAMPLE
    .\install-and-run.ps1

.EXAMPLE
    .\install-and-run.ps1 -SkipBrowser
#>

[CmdletBinding()]
param(
    [switch]$SkipBrowser,
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  OntoCode One-Click Installation" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Function to check if a command exists
function Test-Command {
    param($Command)
    try {
        if (Get-Command $Command -ErrorAction Stop) {
            return $true
        }
    }
    catch {
        return $false
    }
}

# Check if Docker is installed
Write-Host "[1/6] Checking Docker..." -ForegroundColor Yellow
if (-not (Test-Command "docker")) {
    Write-Host "[ERROR] Docker is not installed or not in PATH" -ForegroundColor Red
    Write-Host "Please install Docker Desktop from: https://www.docker.com/products/docker-desktop" -ForegroundColor Red
    exit 1
}

# Check if Docker is running
try {
    docker ps | Out-Null
    Write-Host "[OK] Docker is running" -ForegroundColor Green
}
catch {
    Write-Host "[ERROR] Docker is not running" -ForegroundColor Red
    Write-Host "Please start Docker Desktop and try again" -ForegroundColor Red
    exit 1
}

# Check if Node.js is installed
Write-Host ""
Write-Host "[2/6] Checking Node.js..." -ForegroundColor Yellow
if (-not (Test-Command "node")) {
    Write-Host "[ERROR] Node.js is not installed or not in PATH" -ForegroundColor Red
    Write-Host "Please install Node.js from: https://nodejs.org/" -ForegroundColor Red
    exit 1
}
$nodeVersion = node --version
Write-Host "[OK] Node.js is installed ($nodeVersion)" -ForegroundColor Green

# Stop any existing containers
Write-Host ""
Write-Host "[3/6] Cleaning up existing containers..." -ForegroundColor Yellow
try {
    docker compose down -v 2>&1 | Out-Null
    Write-Host "[OK] Cleanup complete" -ForegroundColor Green
}
catch {
    Write-Host "[WARNING] Cleanup had some issues, continuing..." -ForegroundColor Yellow
}

# Build and start all Docker services
Write-Host ""
Write-Host "[4/6] Building and starting Docker services..." -ForegroundColor Yellow
Write-Host "This may take several minutes on first run..." -ForegroundColor Yellow

if ($NoBuild) {
    docker compose up -d
}
else {
    docker compose up -d --build
}

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Failed to start Docker services" -ForegroundColor Red
    exit 1
}
Write-Host "[OK] Docker services started successfully" -ForegroundColor Green

# Wait for services to be ready
Write-Host ""
Write-Host "[5/6] Waiting for services to initialize..." -ForegroundColor Yellow
Start-Sleep -Seconds 30

# Check service health
Write-Host "Checking service health..." -ForegroundColor Yellow
$healthyServices = 0
$totalServices = 0

$containers = docker compose ps --format json | ConvertFrom-Json
foreach ($container in $containers) {
    $totalServices++
    $status = $container.State
    $name = $container.Service
    if ($status -eq "running") {
        $healthyServices++
        Write-Host "  ✓ $name is running" -ForegroundColor Green
    }
    else {
        Write-Host "  ✗ $name is $status" -ForegroundColor Yellow
    }
}

Write-Host "[OK] $healthyServices/$totalServices services are running" -ForegroundColor Green

# Build and launch VS Code web extension
Write-Host ""
Write-Host "[6/6] Building and launching VS Code Web Extension..." -ForegroundColor Yellow
Push-Location ontology-vscode-extension

try {
    # Check if node_modules exists, install if not
    if (-not (Test-Path "node_modules")) {
        Write-Host "Installing extension dependencies..." -ForegroundColor Yellow
        npm install
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to install dependencies"
        }
    }

    # Build web extension bundle
    Write-Host "Building web extension bundle..." -ForegroundColor Yellow
    npm run bundle:web
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to build extension bundle"
    }

    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "  Installation Complete!" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Services running at:" -ForegroundColor Cyan
    Write-Host "  - API Gateway:     http://localhost:80" -ForegroundColor White
    Write-Host "  - Auth Service:    http://localhost:8086" -ForegroundColor White
    Write-Host "  - OWL Editor:      http://localhost:8083" -ForegroundColor White
    Write-Host "  - SWRL Service:    http://localhost:8084" -ForegroundColor White
    Write-Host "  - Plugin Service:  http://localhost:8087" -ForegroundColor White
    Write-Host "  - MongoDB:         mongodb://localhost:27017" -ForegroundColor White
    Write-Host "  - GraphDB:         http://localhost:7200" -ForegroundColor White
    Write-Host ""

    if (-not $SkipBrowser) {
        Write-Host "Starting VS Code Web Editor..." -ForegroundColor Yellow
        Write-Host "The editor will open in your default browser." -ForegroundColor Yellow
        Write-Host ""
        Write-Host "========================================" -ForegroundColor Green
        
        # Launch VS Code web extension
        npm run test-web
    }
    else {
        Write-Host "Skipping browser launch (use -SkipBrowser:$false to enable)" -ForegroundColor Yellow
        Write-Host ""
        Write-Host "To start the web editor manually, run:" -ForegroundColor Cyan
        Write-Host "  cd ontology-vscode-extension" -ForegroundColor White
        Write-Host "  npm run test-web" -ForegroundColor White
    }
}
catch {
    Write-Host "[ERROR] $_" -ForegroundColor Red
    Pop-Location
    exit 1
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host "To stop all services, run: docker compose down" -ForegroundColor Cyan
Write-Host ""
