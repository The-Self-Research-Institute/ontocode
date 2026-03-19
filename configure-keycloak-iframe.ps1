# Configure Keycloak to Allow Iframe Embedding for VS Code
# This script disables CSP frame-ancestors restriction for the ontocode realm

param(
    [string]$KeycloakUrl = "http://localhost:9080",
    [string]$AdminUser = "admin",
    [string]$AdminPassword = "admin",
    [string]$Realm = "ontocode"
)

Write-Host "🔧 Configuring Keycloak realm for iframe embedding..." -ForegroundColor Cyan
Write-Host ""

# Get admin access token
Write-Host "1. Authenticating as admin..." -ForegroundColor Yellow
$tokenUrl = "$KeycloakUrl/realms/master/protocol/openid-connect/token"
$tokenBody = @{
    client_id = "admin-cli"
    username = $AdminUser
    password = $AdminPassword
    grant_type = "password"
}

try {
    $tokenResponse = Invoke-RestMethod -Uri $tokenUrl -Method Post -Body $tokenBody -ContentType "application/x-www-form-urlencoded"
    $accessToken = $tokenResponse.access_token
    Write-Host "   ✅ Authenticated successfully" -ForegroundColor Green
} catch {
    Write-Host "   ❌ Failed to authenticate: $_" -ForegroundColor Red
    Write-Host ""
    Write-Host "Make sure Keycloak is running:" -ForegroundColor Yellow
    Write-Host "   docker-compose -f docker-compose.keycloak.yml up -d" -ForegroundColor Cyan
    exit 1
}

# Get current realm configuration
Write-Host ""
Write-Host "2. Fetching realm configuration..." -ForegroundColor Yellow
$realmUrl = "$KeycloakUrl/admin/realms/$Realm"
$headers = @{
    Authorization = "Bearer $accessToken"
    "Content-Type" = "application/json"
}

try {
    $realmConfig = Invoke-RestMethod -Uri $realmUrl -Method Get -Headers $headers
    Write-Host "   ✅ Realm configuration retrieved" -ForegroundColor Green
} catch {
    Write-Host "   ❌ Failed to get realm: $_" -ForegroundColor Red
    Write-Host ""
    Write-Host "The realm '$Realm' may not exist yet." -ForegroundColor Yellow
    Write-Host "Create it first using: ./create-keycloak-client.ps1" -ForegroundColor Cyan
    exit 1
}

# Update security settings to allow iframe embedding
Write-Host ""
Write-Host "3. Updating security settings..." -ForegroundColor Yellow

# Modify the realm configuration
# Set browserSecurityHeaders to allow iframe embedding
$realmConfig.browserSecurityHeaders = @{
    "contentSecurityPolicy" = "frame-src 'self'; frame-ancestors 'self' vscode-webview://*; object-src 'none';"
    "contentSecurityPolicyReportOnly" = ""
    "xContentTypeOptions" = "nosniff"
    "xRobotsTag" = "none"
    "xFrameOptions" = "SAMEORIGIN"
    "xXSSProtection" = "1; mode=block"
    "strictTransportSecurity" = "max-age=31536000; includeSubDomains"
}

# Send update request
try {
    $jsonBody = $realmConfig | ConvertTo-Json -Depth 10
    Invoke-RestMethod -Uri $realmUrl -Method Put -Headers $headers -Body $jsonBody -ContentType "application/json" | Out-Null
    Write-Host "   ✅ Security settings updated" -ForegroundColor Green
} catch {
    Write-Host "   ❌ Failed to update realm: $_" -ForegroundColor Red
    exit 1
}

# Verify the changes
Write-Host ""
Write-Host "4. Verifying configuration..." -ForegroundColor Yellow
try {
    $updatedConfig = Invoke-RestMethod -Uri $realmUrl -Method Get -Headers $headers
    $csp = $updatedConfig.browserSecurityHeaders.contentSecurityPolicy
    
    if ($csp -match "frame-ancestors") {
        Write-Host "   ✅ CSP configured: $csp" -ForegroundColor Green
    } else {
        Write-Host "   ⚠️  CSP may not be properly configured" -ForegroundColor Yellow
    }
    
    Write-Host ""
    Write-Host "═══════════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host "✅ Keycloak realm configured successfully!" -ForegroundColor Green
    Write-Host "═══════════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "The Keycloak login page can now be embedded in VS Code extension iframes." -ForegroundColor White
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor Yellow
    Write-Host "1. Rebuild the VS Code extension:" -ForegroundColor White
    Write-Host "   cd ontology-vscode-extension" -ForegroundColor Cyan
    Write-Host "   npm run compile" -ForegroundColor Cyan
    Write-Host "   npm run bundle:extension" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "2. Reload VS Code window (F1 → Developer: Reload Window)" -ForegroundColor White
    Write-Host ""
    Write-Host "3. Test login in OntoCode panel" -ForegroundColor White
    Write-Host ""
    
} catch {
    Write-Host "   ⚠️  Could not verify configuration: $_" -ForegroundColor Yellow
}

Write-Host "Configuration complete!" -ForegroundColor Green
