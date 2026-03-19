# Verify Keycloak Theme Installation
# This script checks if the OntoCode theme is properly installed and configured

Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  OntoCode Theme Verification Tool" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

$allPassed = $true

# Test 1: Check if Docker is running
Write-Host "[1/7] Checking Docker status..." -NoNewline
try {
    docker ps | Out-Null
    Write-Host " PASS" -ForegroundColor Green
} catch {
    Write-Host " FAIL" -ForegroundColor Red
    Write-Host "      Docker is not running. Please start Docker Desktop." -ForegroundColor Yellow
    $allPassed = $false
}

# Test 2: Check if theme directory exists
Write-Host "[2/7] Checking theme directory..." -NoNewline
if (Test-Path "keycloak-themes/ontocode") {
    Write-Host " PASS" -ForegroundColor Green
} else {
    Write-Host " FAIL" -ForegroundColor Red
    Write-Host "      Theme directory not found: keycloak-themes/ontocode" -ForegroundColor Yellow
    $allPassed = $false
}

# Test 3: Check theme files
Write-Host "[3/7] Checking theme files..." -NoNewline
$requiredFiles = @(
    "keycloak-themes/ontocode/login/theme.properties",
    "keycloak-themes/ontocode/login/resources/css/ontocode.css",
    "keycloak-themes/ontocode/login/resources/img/ontocode-logo.svg",
    "keycloak-themes/ontocode/account/theme.properties",
    "keycloak-themes/ontocode/email/theme.properties"
)

$missingFiles = @()
foreach ($file in $requiredFiles) {
    if (-not (Test-Path $file)) {
        $missingFiles += $file
    }
}

if ($missingFiles.Count -eq 0) {
    Write-Host " PASS" -ForegroundColor Green
} else {
    Write-Host " FAIL" -ForegroundColor Red
    Write-Host "      Missing files:" -ForegroundColor Yellow
    foreach ($file in $missingFiles) {
        Write-Host "      - $file" -ForegroundColor Yellow
    }
    $allPassed = $false
}

# Test 4: Check if Keycloak container is running
Write-Host "[4/7] Checking Keycloak container..." -NoNewline
try {
    $container = docker ps --filter "name=ontocode-keycloak" --format "{{.Status}}"
    if ($container -and $container -match "Up") {
        Write-Host " PASS" -ForegroundColor Green
    } else {
        Write-Host " FAIL" -ForegroundColor Red
        Write-Host "      Keycloak container is not running" -ForegroundColor Yellow
        $allPassed = $false
    }
} catch {
    Write-Host " FAIL" -ForegroundColor Red
    Write-Host "      Could not check container status" -ForegroundColor Yellow
    $allPassed = $false
}

# Test 5: Check if Keycloak is accessible
Write-Host "[5/7] Checking Keycloak accessibility..." -NoNewline
try {
    $response = Invoke-WebRequest -Uri "http://localhost:9080" -Method Get -TimeoutSec 5 -ErrorAction SilentlyContinue
    if ($response.StatusCode -eq 200) {
        Write-Host " PASS" -ForegroundColor Green
    } else {
        Write-Host " FAIL" -ForegroundColor Red
        Write-Host "      Keycloak returned status code: $($response.StatusCode)" -ForegroundColor Yellow
        $allPassed = $false
    }
} catch {
    Write-Host " FAIL" -ForegroundColor Red
    Write-Host "      Keycloak is not accessible at http://localhost:9080" -ForegroundColor Yellow
    $allPassed = $false
}

# Test 6: Check docker-compose volume mount
Write-Host "[6/7] Checking volume mount configuration..." -NoNewline
if (Test-Path "docker-compose.keycloak.yml") {
    $composeContent = Get-Content "docker-compose.keycloak.yml" -Raw
    if ($composeContent -match "keycloak-themes:/opt/keycloak/themes") {
        Write-Host " PASS" -ForegroundColor Green
    } else {
        Write-Host " FAIL" -ForegroundColor Red
        Write-Host "      Volume mount not found in docker-compose.keycloak.yml" -ForegroundColor Yellow
        Write-Host "      Expected: - ./keycloak-themes:/opt/keycloak/themes" -ForegroundColor Yellow
        $allPassed = $false
    }
} else {
    Write-Host " FAIL" -ForegroundColor Red
    Write-Host "      docker-compose.keycloak.yml not found" -ForegroundColor Yellow
    $allPassed = $false
}

# Test 7: Check if theme is available in Keycloak
Write-Host "[7/7] Checking theme availability in Keycloak..." -NoNewline
try {
    # Try to get the theme list (this requires checking the container)
    $themesCheck = docker exec ontocode-keycloak ls /opt/keycloak/themes/ontocode 2>&1
    if ($themesCheck -match "login") {
        Write-Host " PASS" -ForegroundColor Green
    } else {
        Write-Host " WARN" -ForegroundColor Yellow
        Write-Host "      Theme files may not be mounted in container" -ForegroundColor Yellow
        Write-Host "      Try restarting Keycloak: .\apply-keycloak-theme.ps1" -ForegroundColor Yellow
    }
} catch {
    Write-Host " SKIP" -ForegroundColor Yellow
    Write-Host "      Could not check theme in container" -ForegroundColor Yellow
}

# Summary
Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
if ($allPassed) {
    Write-Host "  All Checks Passed! ✓" -ForegroundColor Green
    Write-Host "================================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Your theme is ready to use!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Next Steps:" -ForegroundColor Yellow
    Write-Host "1. Apply theme via CLI:" -ForegroundColor White
    Write-Host "   .\apply-theme-cli.ps1" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "2. Or configure manually:" -ForegroundColor White
    Write-Host "   http://localhost:9080/admin" -ForegroundColor Cyan
} else {
    Write-Host "  Some Checks Failed ✗" -ForegroundColor Red
    Write-Host "================================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Please address the issues above and run this script again." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Common Solutions:" -ForegroundColor Yellow
    Write-Host "1. Start Docker Desktop" -ForegroundColor White
    Write-Host "2. Run: docker-compose -f docker-compose.keycloak.yml up -d" -ForegroundColor White
    Write-Host "3. Run: .\apply-keycloak-theme.ps1" -ForegroundColor White
}
Write-Host ""
