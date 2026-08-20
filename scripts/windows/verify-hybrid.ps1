# Quick audit check for hybrid dev stack (mongo+fuseki docker, java via mvn).
#   .\scripts\verify-hybrid.ps1

$ErrorActionPreference = "Continue"
$failed = 0

function Check($Name, $Script) {
    try {
        $ok = & $Script
        if ($ok) {
            Write-Host "[PASS] $Name" -ForegroundColor Green
            return
        }
        Write-Host "[FAIL] $Name" -ForegroundColor Red
        $script:failed++
    } catch {
        Write-Host "[FAIL] $Name — $($_.Exception.Message)" -ForegroundColor Red
        $script:failed++
    }
}

Check "Docker mongo" {
    $s = docker inspect ontocode-mongo --format "{{.State.Status}}" 2>$null
    return $s -eq "running"
}

Check "Docker fuseki" {
    $s = docker inspect ontocode-fuseki --format "{{.State.Status}}" 2>$null
    return $s -eq "running"
}

Check "Fuseki ping" {
    $r = Invoke-WebRequest -Uri "http://localhost:3030/`$/ping" -UseBasicParsing -TimeoutSec 10
    return $r.StatusCode -eq 200
}

Check "Auth health" {
    $r = Invoke-WebRequest -Uri "http://localhost:8086/actuator/health" -UseBasicParsing -TimeoutSec 15
    return $r.StatusCode -eq 200
}

Check "Editor health" {
    $r = Invoke-WebRequest -Uri "http://localhost:8083/actuator/health" -UseBasicParsing -TimeoutSec 15
    return $r.StatusCode -eq 200
}

Check "Gateway health" {
    $r = Invoke-WebRequest -Uri "http://localhost/actuator/health" -UseBasicParsing -TimeoutSec 15
    return $r.StatusCode -eq 200
}

Check "Login API" {
    $body = '{"username":"admin@coretopia.com","password":"LocalLoadTest1!"}'
    $r = Invoke-WebRequest -Uri "http://localhost/api/auth/login" -Method POST -Body $body `
        -ContentType "application/json" -UseBasicParsing -TimeoutSec 30
    $j = $r.Content | ConvertFrom-Json
    return [bool]($j.jwt -or $j.token)
}

Write-Host ""
if ($failed -eq 0) {
    Write-Host "All checks passed ($((Get-Date).ToString('u')))" -ForegroundColor Green
    exit 0
}
Write-Host "$failed check(s) failed" -ForegroundColor Red
exit 1
