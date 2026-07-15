#!/usr/bin/env pwsh
<#
.SYNOPSIS
    OntoCode - Docker-Only Installation (No Node.js Required)

.DESCRIPTION
    This script starts the entire OntoCode platform including the VS Code web
    editor, all running in Docker containers. You don't need Node.js installed!

.EXAMPLE
    .\docker-install.ps1

.EXAMPLE
    .\docker-install.ps1 -NoBuild

.EXAMPLE
    .\docker-install.ps1 -SkipBrowser
#>

[CmdletBinding()]
param(
    [switch]$SkipBrowser,
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  OntoCode Docker-Only Installation" -ForegroundColor Cyan
Write-Host "  (No Node.js Required!)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check if Docker is installed
Write-Host "[1/5] Checking Docker..." -ForegroundColor Yellow
if (-not (Get-Command "docker" -ErrorAction SilentlyContinue)) {
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

# Ensure data directory exists
Write-Host ""
Write-Host "[2/5] Preparing workspace directory..." -ForegroundColor Yellow
if (-not (Test-Path "data\projects")) {
    Write-Host "Creating data\projects directory..." -ForegroundColor Gray
    New-Item -ItemType Directory -Path "data\projects" -Force | Out-Null
}
Write-Host "[OK] Workspace directory ready" -ForegroundColor Green

# Stop any existing containers
Write-Host ""
Write-Host "[3/5] Cleaning up existing containers..." -ForegroundColor Yellow
try {
    docker compose down -v 2>&1 | Out-Null
    Write-Host "[OK] Cleanup complete" -ForegroundColor Green
}
catch {
    Write-Host "[WARNING] Cleanup had some issues, continuing..." -ForegroundColor Yellow
}

# Build and start all Docker services
Write-Host ""
Write-Host "[4/5] Building and starting all services..." -ForegroundColor Yellow
Write-Host "This includes: MongoDB, GraphDB, Auth, Gateway, Editor, SWRL, Plugins, and VS Code Web" -ForegroundColor Gray
Write-Host "First run may take 5-10 minutes..." -ForegroundColor Yellow

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
Write-Host "[OK] All Docker services started successfully" -ForegroundColor Green

# Wait for services to be ready
Write-Host ""
Write-Host "[5/5] Waiting for all services to initialize..." -ForegroundColor Yellow
Write-Host "This includes starting the VS Code web server..." -ForegroundColor Gray
Start-Sleep -Seconds 45

# Check service health
Write-Host ""
Write-Host "Checking service health..." -ForegroundColor Yellow
$containers = docker compose ps --format json | ConvertFrom-Json
$healthyServices = 0
$totalServices = 0

foreach ($container in $containers) {
    $totalServices++
    $status = $container.State
    $name = $container.Service
    if ($status -eq "running") {
        $healthyServices++
        Write-Host "  ✓ $name" -ForegroundColor Green
    }
    else {
        Write-Host "  ✗ $name ($status)" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Installation Complete!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "All services running in Docker:" -ForegroundColor Cyan
Write-Host ""
Write-Host "  PRIMARY ACCESS:" -ForegroundColor Yellow
Write-Host "  - VS Code Web Editor:  " -NoNewline -ForegroundColor White
Write-Host "http://localhost:3000" -ForegroundColor Cyan
Write-Host "     (Open this in your browser to start editing)" -ForegroundColor Gray
Write-Host ""
Write-Host "  BACKEND SERVICES:" -ForegroundColor Yellow
Write-Host "  - API Gateway:         http://localhost:80" -ForegroundColor White
Write-Host "  - Auth Service:        http://localhost:8086" -ForegroundColor White
Write-Host "  - OWL Editor:          http://localhost:8083" -ForegroundColor White
Write-Host "  - SWRL Service:        http://localhost:8084" -ForegroundColor White
Write-Host "  - Plugin Service:      http://localhost:8087" -ForegroundColor White
Write-Host "  - MongoDB:             mongodb://localhost:27017" -ForegroundColor White
Write-Host "  - GraphDB:             http://localhost:7200" -ForegroundColor White
Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "Status: $healthyServices/$totalServices services running" -ForegroundColor $(if ($healthyServices -eq $totalServices) { "Green" } else { "Yellow" })
Write-Host ""

if (-not $SkipBrowser) {
    Write-Host "Opening VS Code Web Editor in your browser..." -ForegroundColor Yellow
    Start-Sleep -Seconds 2
    Start-Process "http://localhost:3000"
}

Write-Host ""
Write-Host "Useful commands:" -ForegroundColor Cyan
Write-Host "  Stop all services:     docker compose down" -ForegroundColor White
Write-Host "  View web editor logs:  docker compose logs -f vscode-web" -ForegroundColor White
Write-Host "  View all logs:         docker compose logs -f" -ForegroundColor White
Write-Host "  Restart web editor:    docker compose restart vscode-web" -ForegroundColor White
Write-Host ""
