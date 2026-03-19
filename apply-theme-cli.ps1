# Apply OntoCode Theme via Keycloak Admin CLI
# This script automatically configures the realm to use the OntoCode theme

Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  OntoCode Theme CLI Configurator" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

# Configuration
$KEYCLOAK_URL = "http://localhost:9080"
$ADMIN_USER = "admin"
$ADMIN_PASSWORD = "admin"
$REALM_NAME = "ontocode"
$THEME_NAME = "ontocode"

Write-Host "Configuring Keycloak at: $KEYCLOAK_URL" -ForegroundColor Yellow
Write-Host "Realm: $REALM_NAME" -ForegroundColor Yellow
Write-Host "Theme: $THEME_NAME" -ForegroundColor Yellow
Write-Host ""

# Step 1: Authenticate and get admin token
Write-Host "Step 1: Authenticating with Keycloak..." -ForegroundColor Yellow
try {
    $authBody = @{
        username = $ADMIN_USER
        password = $ADMIN_PASSWORD
        grant_type = "password"
        client_id = "admin-cli"
    }
    
    $authResponse = Invoke-RestMethod -Uri "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" `
        -Method Post -Body $authBody -ContentType "application/x-www-form-urlencoded"
    
    $accessToken = $authResponse.access_token
    Write-Host "✓ Authentication successful" -ForegroundColor Green
} catch {
    Write-Host "✗ Authentication failed: $_" -ForegroundColor Red
    exit 1
}

# Step 2: Check if realm exists
Write-Host ""
Write-Host "Step 2: Checking if realm '$REALM_NAME' exists..." -ForegroundColor Yellow
$headers = @{
    Authorization = "Bearer $accessToken"
    "Content-Type" = "application/json"
}

try {
    $realm = Invoke-RestMethod -Uri "$KEYCLOAK_URL/admin/realms/$REALM_NAME" `
        -Method Get -Headers $headers -ErrorAction SilentlyContinue
    Write-Host "✓ Realm '$REALM_NAME' exists" -ForegroundColor Green
} catch {
    Write-Host "! Realm '$REALM_NAME' not found. Would you like to create it? (Y/N)" -ForegroundColor Yellow
    $createRealm = Read-Host
    
    if ($createRealm -eq "Y" -or $createRealm -eq "y") {
        Write-Host "Creating realm '$REALM_NAME'..." -ForegroundColor Yellow
        
        $realmConfig = @{
            realm = $REALM_NAME
            enabled = $true
            displayName = "OntoCode"
            loginTheme = $THEME_NAME
            accountTheme = $THEME_NAME
            emailTheme = $THEME_NAME
        } | ConvertTo-Json
        
        try {
            Invoke-RestMethod -Uri "$KEYCLOAK_URL/admin/realms" `
                -Method Post -Headers $headers -Body $realmConfig
            Write-Host "✓ Realm created successfully" -ForegroundColor Green
        } catch {
            Write-Host "✗ Failed to create realm: $_" -ForegroundColor Red
            exit 1
        }
    } else {
        Write-Host "Exiting without creating realm." -ForegroundColor Yellow
        exit 0
    }
}

# Step 3: Apply theme to realm
Write-Host ""
Write-Host "Step 3: Applying OntoCode theme to realm..." -ForegroundColor Yellow

$themeConfig = @{
    loginTheme = $THEME_NAME
    accountTheme = $THEME_NAME
    emailTheme = $THEME_NAME
} | ConvertTo-Json

try {
    Invoke-RestMethod -Uri "$KEYCLOAK_URL/admin/realms/$REALM_NAME" `
        -Method Put -Headers $headers -Body $themeConfig
    Write-Host "✓ Theme applied successfully" -ForegroundColor Green
} catch {
    Write-Host "✗ Failed to apply theme: $_" -ForegroundColor Red
    exit 1
}

# Step 4: Verify theme configuration
Write-Host ""
Write-Host "Step 4: Verifying theme configuration..." -ForegroundColor Yellow
try {
    $updatedRealm = Invoke-RestMethod -Uri "$KEYCLOAK_URL/admin/realms/$REALM_NAME" `
        -Method Get -Headers $headers
    
    Write-Host "  Login Theme: $($updatedRealm.loginTheme)" -ForegroundColor Cyan
    Write-Host "  Account Theme: $($updatedRealm.accountTheme)" -ForegroundColor Cyan
    Write-Host "  Email Theme: $($updatedRealm.emailTheme)" -ForegroundColor Cyan
    
    if ($updatedRealm.loginTheme -eq $THEME_NAME) {
        Write-Host "✓ Theme configuration verified" -ForegroundColor Green
    } else {
        Write-Host "! Theme may not be applied correctly" -ForegroundColor Yellow
    }
} catch {
    Write-Host "✗ Failed to verify theme: $_" -ForegroundColor Red
}

Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  Configuration Complete!" -ForegroundColor Green
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "You can now test the theme:" -ForegroundColor Yellow
Write-Host "1. Access the login page:" -ForegroundColor White
Write-Host "   $KEYCLOAK_URL/realms/$REALM_NAME/account" -ForegroundColor Cyan
Write-Host ""
Write-Host "2. OIDC Login URL:" -ForegroundColor White
Write-Host "   $KEYCLOAK_URL/realms/$REALM_NAME/protocol/openid-connect/auth" -ForegroundColor Cyan
Write-Host ""
