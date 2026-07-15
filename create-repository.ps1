# Create GraphDB Repository for OntoCode
# This script creates the 'ontocode' repository in GraphDB

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "   Creating GraphDB Repository" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# Check if GraphDB is running
Write-Host "Checking if GraphDB is running..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "http://localhost:7200/rest/repositories" -UseBasicParsing -TimeoutSec 5
    Write-Host "✓ GraphDB is running!" -ForegroundColor Green
} catch {
    Write-Host "✗ GraphDB is not accessible at http://localhost:7200" -ForegroundColor Red
    Write-Host "Please ensure GraphDB container is running: docker-compose ps" -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host ""

# Check if repository already exists
Write-Host "Checking if 'ontocode' repository exists..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "http://localhost:7200/repositories/ontocode" -UseBasicParsing -TimeoutSec 5
    Write-Host "✓ Repository 'ontocode' already exists!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Repository URL: http://localhost:7200/repositories/ontocode" -ForegroundColor Cyan
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 0
} catch {
    Write-Host "Repository doesn't exist yet, creating..." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Creating repository 'ontocode'..." -ForegroundColor Yellow
Write-Host ""

# Repository configuration in Turtle format
$config = @"
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix rep: <http://www.openrdf.org/config/repository#> .
@prefix sr: <http://www.openrdf.org/config/repository/sail#> .
@prefix sail: <http://www.openrdf.org/config/sail#> .
@prefix graphdb: <http://www.ontotext.com/config/graphdb#> .

[] a rep:Repository ;
    rep:repositoryID "ontocode" ;
    rdfs:label "OntoCode Repository (No Inference)" ;
    rep:repositoryImpl [
        rep:repositoryType "graphdb:SailRepository" ;
        sr:sailImpl [
            sail:sailType "graphdb:Sail" ;
            graphdb:ruleset "empty" ;
            graphdb:base-URL "http://example.org/graphdb#" ;
            graphdb:defaultNS "" ;
            graphdb:entity-index-size "10000000" ;
            graphdb:entity-id-size "32" ;
            graphdb:imports "" ;
            graphdb:repository-type "file-repository" ;
            graphdb:storage-folder "storage" ;
            graphdb:enable-context-index "false" ;
            graphdb:enablePredicateList "true" ;
            graphdb:in-memory-literal-properties "true" ;
            graphdb:enable-literal-index "true" ;
            graphdb:check-for-inconsistencies "false" ;
            graphdb:disable-sameAs "true" ;
            graphdb:query-timeout "0" ;
            graphdb:query-limit-results "0" ;
            graphdb:throw-QueryEvaluationException-on-timeout "false" ;
            graphdb:read-only "false" ;
        ]
    ] .
"@

# Save config to temp file
$tempFile = [System.IO.Path]::GetTempFileName()
$configFile = $tempFile -replace '\.tmp$', '.ttl'
$config | Out-File -FilePath $configFile -Encoding UTF8

try {
    # Create repository
    $boundary = [System.Guid]::NewGuid().ToString()
    $fileContent = Get-Content -Path $configFile -Raw
    
    $body = @"
--$boundary
Content-Disposition: form-data; name="config"; filename="config.ttl"
Content-Type: text/turtle

$fileContent
--$boundary--
"@

    $headers = @{
        "Content-Type" = "multipart/form-data; boundary=$boundary"
    }

    $response = Invoke-WebRequest -Uri "http://localhost:7200/rest/repositories" `
        -Method POST `
        -Headers $headers `
        -Body $body `
        -UseBasicParsing `
        -TimeoutSec 30

    if ($response.StatusCode -eq 201 -or $response.StatusCode -eq 200) {
        Write-Host ""
        Write-Host "============================================" -ForegroundColor Green
        Write-Host "   Repository Created Successfully!" -ForegroundColor Green
        Write-Host "============================================" -ForegroundColor Green
        Write-Host ""
        Write-Host "Repository: ontocode" -ForegroundColor Cyan
        Write-Host "URL: http://localhost:7200/repositories/ontocode" -ForegroundColor Cyan
        Write-Host "Ruleset: empty (NO INFERENCE)" -ForegroundColor Cyan
        Write-Host ""
        Write-Host "You can now upload ontology files!" -ForegroundColor Green
        Write-Host ""
    } else {
        throw "Unexpected status code: $($response.StatusCode)"
    }
} catch {
    Write-Host ""
    Write-Host "============================================" -ForegroundColor Red
    Write-Host "   Failed to create repository via API" -ForegroundColor Red
    Write-Host "============================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "Error: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host ""
    Write-Host "Please create manually:" -ForegroundColor Yellow
    Write-Host "1. Open http://localhost:7200" -ForegroundColor Yellow
    Write-Host "2. Go to Setup → Repositories" -ForegroundColor Yellow
    Write-Host "3. Click 'Create new repository'" -ForegroundColor Yellow
    Write-Host "4. Repository ID: ontocode" -ForegroundColor Yellow
    Write-Host "5. Ruleset: empty (disable inference)" -ForegroundColor Yellow
    Write-Host "6. Click Create" -ForegroundColor Yellow
    Write-Host ""
} finally {
    # Cleanup temp file
    if (Test-Path $configFile) {
        Remove-Item $configFile -Force
    }
}

Write-Host ""
Read-Host "Press Enter to exit"
