# ================================================================
# Create Keycloak Client for OntoCode Auth Service
# ================================================================

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Keycloak Client Configuration" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Configuration
$KEYCLOAK_URL = "http://localhost:9080"
$ADMIN_USER = "admin"
$ADMIN_PASSWORD = "admin"
$REALM = "ontocode"
$CLIENT_ID = "ontocode-auth"
$CLIENT_SECRET = "ontocode-secret-2024"

Write-Host "[+] Authenticating with Keycloak..." -ForegroundColor Yellow

try {
    $tokenResponse = Invoke-RestMethod -Uri "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" `
        -Method Post `
        -Body @{
            username = $ADMIN_USER
            password = $ADMIN_PASSWORD
            grant_type = 'password'
            client_id = 'admin-cli'
        } `
        -ContentType 'application/x-www-form-urlencoded'
    
    $adminToken = $tokenResponse.access_token
    Write-Host "[OK] Authenticated successfully" -ForegroundColor Green
} catch {
    Write-Host "[ERROR] Failed to authenticate: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[+] Checking if client '$CLIENT_ID' exists..." -ForegroundColor Yellow

try {
    $clients = Invoke-RestMethod -Uri "$KEYCLOAK_URL/admin/realms/$REALM/clients" `
        -Headers @{Authorization = "Bearer $adminToken"}
    
    $existingClient = $clients | Where-Object { $_.clientId -eq $CLIENT_ID }
    
    if ($existingClient) {
        Write-Host "[INFO] Client '$CLIENT_ID' already exists, deleting it..." -ForegroundColor Yellow
        Invoke-RestMethod -Uri "$KEYCLOAK_URL/admin/realms/$REALM/clients/$($existingClient.id)" `
            -Method Delete `
            -Headers @{Authorization = "Bearer $adminToken"} | Out-Null
        Write-Host "[OK] Existing client deleted" -ForegroundColor Green
    }
} catch {
    Write-Host "[WARNING] Error checking existing client: $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "[+] Creating new client '$CLIENT_ID'..." -ForegroundColor Yellow

$clientConfig = @{
    clientId = $CLIENT_ID
    name = "OntoCode Auth Service"
    description = "OAuth2 client for OntoCode authentication service"
    enabled = $true
    protocol = "openid-connect"
    publicClient = $false
    bearerOnly = $false
    standardFlowEnabled = $true
    implicitFlowEnabled = $false
    directAccessGrantsEnabled = $true
    serviceAccountsEnabled = $true
    authorizationServicesEnabled = $false
    redirectUris = @(
        "http://localhost:8086/login/oauth2/code/keycloak",
        "http://localhost:80/login/oauth2/code/keycloak",
        "http://localhost:8086/api/auth/oidc/success*",
        "http://localhost/api/auth/oidc/success*",
        "vscode://self.ontocode-extension/*"
    )
    webOrigins = @(
        "http://localhost:8086",
        "http://localhost:80",
        "http://localhost",
        "vscode://self.ontocode-extension"
    )
    attributes = @{
        "access.token.lifespan" = "3600"
        "client.secret.creation.time" = [string][int](Get-Date -UFormat %s)
        "oauth2.device.authorization.grant.enabled" = "false"
        "oidc.ciba.grant.enabled" = "false"
    }
} | ConvertTo-Json -Depth 10

try {
    $response = Invoke-RestMethod -Uri "$KEYCLOAK_URL/admin/realms/$REALM/clients" `
        -Method Post `
        -Headers @{
            Authorization = "Bearer $adminToken"
            "Content-Type" = "application/json"
        } `
        -Body $clientConfig
    
    Write-Host "[OK] Client created successfully" -ForegroundColor Green
} catch {
    Write-Host "[ERROR] Failed to create client: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[+] Getting client ID..." -ForegroundColor Yellow

try {
    $clients = Invoke-RestMethod -Uri "$KEYCLOAK_URL/admin/realms/$REALM/clients" `
        -Headers @{Authorization = "Bearer $adminToken"}
    
    $client = $clients | Where-Object { $_.clientId -eq $CLIENT_ID }
    
    if (!$client) {
        Write-Host "[ERROR] Client created but not found in list" -ForegroundColor Red
        exit 1
    }
    
    $clientUuid = $client.id
    Write-Host "[OK] Client UUID: $clientUuid" -ForegroundColor Green
} catch {
    Write-Host "[ERROR] Failed to get client: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[+] Setting client secret..." -ForegroundColor Yellow

$secretConfig = @{
    type = "secret"
    value = $CLIENT_SECRET
} | ConvertTo-Json

try {
    Invoke-RestMethod -Uri "$KEYCLOAK_URL/admin/realms/$REALM/clients/$clientUuid/client-secret" `
        -Method Post `
        -Headers @{
            Authorization = "Bearer $adminToken"
            "Content-Type" = "application/json"
        } `
        -Body $secretConfig | Out-Null
    
    Write-Host "[OK] Client secret set" -ForegroundColor Green
} catch {
    Write-Host "[WARNING] Failed to set secret via POST, trying regeneration..." -ForegroundColor Yellow
    
    try {
        $secretResponse = Invoke-RestMethod -Uri "$KEYCLOAK_URL/admin/realms/$REALM/clients/$clientUuid/client-secret" `
            -Method Get `
            -Headers @{Authorization = "Bearer $adminToken"}
        
        Write-Host "[INFO] Current secret: $($secretResponse.value)" -ForegroundColor Cyan
    } catch {
        Write-Host "[ERROR] Could not retrieve secret: $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host " Client Configuration Complete!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "Client Details:" -ForegroundColor Cyan
Write-Host "  Realm: $REALM"
Write-Host "  Client ID: $CLIENT_ID"
Write-Host "  Client Secret: $CLIENT_SECRET"
Write-Host "  Issuer URI: $KEYCLOAK_URL/realms/$REALM"
Write-Host ""
Write-Host "Update docker-compose.keycloak.yml environment variables:" -ForegroundColor Yellow
Write-Host "  KEYCLOAK_CLIENT_SECRET=$CLIENT_SECRET" -ForegroundColor White
Write-Host ""
