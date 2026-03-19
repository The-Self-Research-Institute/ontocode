# OntoCode with Keycloak - Quick Setup Script (Windows)
# This script sets up OntoCode with Keycloak OIDC authentication

$ErrorActionPreference = "Stop"

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "OntoCode with Keycloak Setup" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host ""

# Check if docker is installed
try {
    docker --version | Out-Null
    Write-Host "✅ Docker is installed" -ForegroundColor Green
} catch {
    Write-Host "❌ Docker is not installed. Please install Docker Desktop first." -ForegroundColor Red
    exit 1
}

# Check if docker-compose is installed
try {
    docker-compose --version | Out-Null
    Write-Host "✅ Docker Compose is installed" -ForegroundColor Green
} catch {
    Write-Host "❌ Docker Compose is not installed. Please install Docker Compose first." -ForegroundColor Red
    exit 1
}

Write-Host ""

# Step 1: Start Keycloak and database
Write-Host "Step 1: Starting Keycloak and PostgreSQL..." -ForegroundColor Yellow
docker-compose -f docker-compose.keycloak.yml up -d keycloak-db keycloak

Write-Host "Waiting for Keycloak to start (this may take 60-90 seconds)..."
Start-Sleep -Seconds 30

# Check if Keycloak is healthy
$keycloakHealthy = $false
for ($i = 1; $i -le 12; $i++) {
    $status = docker-compose -f docker-compose.keycloak.yml ps keycloak
    if ($status -match "healthy") {
        $keycloakHealthy = $true
        break
    }
    Write-Host "Waiting for Keycloak to be ready... ($i/12)"
    Start-Sleep -Seconds 5
}

if (-not $keycloakHealthy) {
    Write-Host "❌ Keycloak failed to start. Check logs with: docker-compose -f docker-compose.keycloak.yml logs keycloak" -ForegroundColor Red
    exit 1
}

Write-Host "✅ Keycloak is running" -ForegroundColor Green
Write-Host "   Admin console: http://localhost:8080"
Write-Host "   Username: admin"
Write-Host "   Password: admin"
Write-Host ""

# Step 2: Configure Keycloak (manual step)
Write-Host "Step 2: Keycloak Configuration" -ForegroundColor Yellow
Write-Host "Please complete the following in Keycloak Admin Console:"
Write-Host ""
Write-Host "1. Open: http://localhost:8080"
Write-Host "2. Login with admin/admin"
Write-Host "3. Create a realm named: ontocode"
Write-Host "4. Create a client:"
Write-Host "   - Client ID: ontocode-auth"
Write-Host "   - Client authentication: ON"
Write-Host "   - Valid redirect URIs: http://localhost:8086/*"
Write-Host "5. Go to Credentials tab and copy the Client Secret"
Write-Host "6. Create a test user with email and password"
Write-Host ""
Read-Host "Press Enter when Keycloak configuration is complete"
Write-Host ""

# Get client secret from user
Write-Host "Enter the Keycloak Client Secret:" -ForegroundColor Yellow
$KEYCLOAK_CLIENT_SECRET = Read-Host -AsSecureString
$BSTR = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($KEYCLOAK_CLIENT_SECRET)
$CLIENT_SECRET_PLAIN = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR)
Write-Host ""

if ([string]::IsNullOrWhiteSpace($CLIENT_SECRET_PLAIN)) {
    Write-Host "❌ Client secret cannot be empty" -ForegroundColor Red
    exit 1
}

# Create .env file if it doesn't exist
if (-not (Test-Path .env)) {
    Write-Host "Creating .env file..."
    @"
# Keycloak Configuration
KEYCLOAK_CLIENT_SECRET=$CLIENT_SECRET_PLAIN

# Admin Configuration
ADMIN_PASSWORD=admin123

# SMTP Configuration (optional - leave empty to skip)
SMTP_HOST=
SMTP_PORT=
SMTP_USERNAME=
SMTP_PASSWORD=
"@ | Out-File -FilePath .env -Encoding UTF8
    Write-Host "✅ .env file created" -ForegroundColor Green
} else {
    # Update existing .env file
    $envContent = Get-Content .env
    if ($envContent -match "KEYCLOAK_CLIENT_SECRET") {
        $envContent = $envContent -replace "KEYCLOAK_CLIENT_SECRET=.*", "KEYCLOAK_CLIENT_SECRET=$CLIENT_SECRET_PLAIN"
        $envContent | Set-Content .env
    } else {
        Add-Content .env "KEYCLOAK_CLIENT_SECRET=$CLIENT_SECRET_PLAIN"
    }
    Write-Host "✅ .env file updated" -ForegroundColor Green
}
Write-Host ""

# Step 3: Start all services
Write-Host "Step 3: Starting all OntoCode services..." -ForegroundColor Yellow
docker-compose -f docker-compose.keycloak.yml up -d

Write-Host "Waiting for all services to start..."
Start-Sleep -Seconds 30

# Check service health
Write-Host ""
Write-Host "Checking service health..." -ForegroundColor Yellow

$services = @("mongodb", "graphdb", "keycloak", "ontology-auth", "ontology-editor", "ontology-gateway")
$allHealthy = $true

foreach ($service in $services) {
    $status = docker-compose -f docker-compose.keycloak.yml ps $service
    if ($status -match "healthy|Up") {
        Write-Host "✅ $service is running" -ForegroundColor Green
    } else {
        Write-Host "❌ $service is not healthy" -ForegroundColor Red
        $allHealthy = $false
    }
}

Write-Host ""

if ($allHealthy) {
    Write-Host "==================================================" -ForegroundColor Green
    Write-Host "✅ OntoCode with Keycloak is running!" -ForegroundColor Green
    Write-Host "==================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Access the services:"
    Write-Host "  • Keycloak Admin:   http://localhost:8080 (admin/admin)"
    Write-Host "  • OntoCode Gateway: http://localhost:80"
    Write-Host "  • Auth Service:     http://localhost:8086"
    Write-Host "  • Editor Service:   http://localhost:8083"
    Write-Host "  • GraphDB:          http://localhost:7200"
    Write-Host "  • MongoDB:          mongodb://localhost:27017"
    Write-Host ""
    Write-Host "Test OIDC authentication:"
    Write-Host "  curl http://localhost:8086/api/auth/oidc/providers"
    Write-Host ""
    Write-Host "Login with Keycloak:"
    Write-Host "  Open: http://localhost:8086/oauth2/authorization/keycloak"
    Write-Host "  Or use VS Code command: OntoCode: Login with OIDC/SSO"
    Write-Host ""
    Write-Host "View logs:"
    Write-Host "  docker-compose -f docker-compose.keycloak.yml logs -f [service-name]"
    Write-Host ""
    Write-Host "Stop all services:"
    Write-Host "  docker-compose -f docker-compose.keycloak.yml down"
    Write-Host ""
} else {
    Write-Host "==================================================" -ForegroundColor Red
    Write-Host "⚠️  Some services are not healthy" -ForegroundColor Red
    Write-Host "==================================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "Check logs for details:"
    Write-Host "  docker-compose -f docker-compose.keycloak.yml logs"
    Write-Host ""
    Write-Host "Try restarting failed services:"
    Write-Host "  docker-compose -f docker-compose.keycloak.yml restart [service-name]"
}
