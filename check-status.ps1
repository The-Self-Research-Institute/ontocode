#!/usr/bin/env pwsh
<#
.SYNOPSIS
    OntoCode - System Status Check

.DESCRIPTION
    Checks the status of Docker, Node.js, and all OntoCode services
#>

$ErrorActionPreference = "Continue"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  OntoCode System Status" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Function to check if a command exists
function Test-Command {
    param($Command)
    try {
        if (Get-Command $Command -ErrorAction Stop) { return $true }
    } catch { return $false }
}

# Check Docker
Write-Host "[1/3] Docker Status" -ForegroundColor Yellow
Write-Host "-------------------"
if (Test-Command "docker") {
    $dockerVersion = docker --version
    try {
        docker ps | Out-Null
        Write-Host "[OK] Docker: RUNNING" -ForegroundColor Green
        Write-Host "     $dockerVersion" -ForegroundColor Gray
    }
    catch {
        Write-Host "[X] Docker: INSTALLED but NOT RUNNING" -ForegroundColor Red
        Write-Host "    Please start Docker Desktop" -ForegroundColor Yellow
    }
}
else {
    Write-Host "[X] Docker: NOT INSTALLED" -ForegroundColor Red
}

Write-Host ""

# Check Node.js
Write-Host "[2/3] Node.js Status" -ForegroundColor Yellow
Write-Host "-------------------"
if (Test-Command "node") {
    $nodeVersion = node --version
    Write-Host "[OK] Node.js: INSTALLED - $nodeVersion" -ForegroundColor Green
}
else {
    Write-Host "[X] Node.js: NOT INSTALLED" -ForegroundColor Red
}

if (Test-Command "npm") {
    $npmVersion = npm --version
    Write-Host "[OK] NPM: INSTALLED - v$npmVersion" -ForegroundColor Green
}
else {
    Write-Host "[X] NPM: NOT INSTALLED" -ForegroundColor Red
}

Write-Host ""

# Check Docker Containers
Write-Host "[3/3] Container Status" -ForegroundColor Yellow
Write-Host "-------------------"
try {
    $containers = docker compose ps --format json 2>&1 | ConvertFrom-Json
    
    if ($containers) {
        $runningCount = 0
        $totalCount = 0
        
        Write-Host ""
        Write-Host "Service               Status            Ports" -ForegroundColor Cyan
        Write-Host "-------               ------            -----" -ForegroundColor Cyan
        
        foreach ($container in $containers) {
            $totalCount++
            $name = $container.Service.PadRight(20)
            $status = $container.State
            $ports = $container.Publishers | ForEach-Object { "$($_.PublishedPort)->$($_.TargetPort)" }
            $portsStr = if ($ports) { $ports -join ", " } else { "-" }
            
            if ($status -eq "running") {
                $runningCount++
                Write-Host "$name " -NoNewline
                Write-Host "RUNNING".PadRight(17) -ForegroundColor Green -NoNewline
                Write-Host " $portsStr" -ForegroundColor Gray
            }
            else {
                Write-Host "$name " -NoNewline
                Write-Host "$status".PadRight(17) -ForegroundColor Yellow -NoNewline
                Write-Host " $portsStr" -ForegroundColor Gray
            }
        }
        
        Write-Host ""
        Write-Host "Summary: $runningCount/$totalCount services running" -ForegroundColor $(if ($runningCount -eq $totalCount) { "Green" } else { "Yellow" })
    }
    else {
        Write-Host "[X] No containers running" -ForegroundColor Yellow
        Write-Host "    Run 'install-and-run.bat' or '.\install-and-run.ps1' to start" -ForegroundColor Gray
    }
}
catch {
    Write-Host "[X] No containers running" -ForegroundColor Yellow
    Write-Host "    Run 'install-and-run.bat' or '.\install-and-run.ps1' to start" -ForegroundColor Gray
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Service URLs" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  API Gateway:     http://localhost:80"
Write-Host "  Auth Service:    http://localhost:8086"
Write-Host "  OWL Editor:      http://localhost:8083"
Write-Host "  SWRL Service:    http://localhost:8084"
Write-Host "  Plugin Service:  http://localhost:8087"
Write-Host "  GraphDB:         http://localhost:7200"
Write-Host "  MongoDB:         mongodb://localhost:27017"
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
