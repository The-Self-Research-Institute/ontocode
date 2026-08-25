# Hybrid dev: Mongo + Fuseki in Docker; Java services via Maven (auto-restart on save).
#   .\scripts\start-hybrid.ps1
#   .\scripts\start-hybrid.ps1 -CoreOnly       # editor + auth + gateway only
#   .\scripts\start-hybrid.ps1 -WithFrontend   # also Vite :3001 (UI hot reload)
#   .\scripts\start-hybrid.ps1 -SkipMaven      # infra only (mongo + fuseki)
# Stop: .\scripts\stop-hybrid.ps1

param(
    [switch]$CoreOnly,
    [switch]$SkipMaven,
    [switch]$WithFrontend,
    [switch]$KeepLocalMongo
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot" }
$env:JWT_SECRET = if ($env:JWT_SECRET) { $env:JWT_SECRET } else { "ZGV2ZWxvcG1lbnQtb250b2NvZGUtand0LXNlY3JldC1mb3ItbG9jYWwtZGV2ZWxvcG1lbnQ=" }
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:MONGODB_URI = "mongodb://admin:changeme123@localhost:27017/ontocode?authSource=admin"
$env:FUSEKI_QUERY_ENDPOINT = "http://localhost:3030/ontocode/query"
$env:FUSEKI_UPDATE_ENDPOINT = "http://localhost:3030/ontocode/update"
$env:FUSEKI_GSP_ENDPOINT = "http://localhost:3030/ontocode/data"
$env:FUSEKI_ADMIN_PASSWORD = if ($env:FUSEKI_ADMIN_PASSWORD) { $env:FUSEKI_ADMIN_PASSWORD } else { "admin" }
$env:ONTOCODE_IMPORT_MAX_CONCURRENT = if ($env:ONTOCODE_IMPORT_MAX_CONCURRENT) { $env:ONTOCODE_IMPORT_MAX_CONCURRENT } else { "1" }
$env:ONTOCODE_GATEWAY_REQUIRE_JWT = "false"
$env:ONTOCODE_EDITOR_REQUIRE_JWT = "false"

$script:MongoHostPort = 27017
$env:MONGO_HOST_PORT = '27017'

function Test-PortInUse([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function Get-DockerContainerRunning([string]$Name) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        $state = docker inspect $Name --format '{{.State.Running}}' 2>$null
        return $state -eq 'true'
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Ensure-DockerMongoPort {
    $script:MongoHostPort = 27017

    if (-not (Test-PortInUse 27017)) { return }

    if (Get-DockerContainerRunning 'ontocode-mongo') {
        Write-Host '  Port 27017: Docker ontocode-mongo' -ForegroundColor Green
        return
    }

    $mongoService = Get-Service -Name 'MongoDB' -ErrorAction SilentlyContinue
    if ($mongoService -and $mongoService.Status -eq 'Running') {
        if ($KeepLocalMongo) {
            throw @"
Port 27017 is used by Windows MongoDB service, but hybrid dev requires Docker mongo (admin/changeme123).
Stop it manually (admin):  Stop-Service MongoDB
Or re-run without -KeepLocalMongo (script will use Docker on port 27018 if stop fails).
"@
        }
        Write-Host '  Stopping Windows MongoDB service (port 27017 needed for Docker mongo)...' -ForegroundColor Yellow
        try {
            Stop-Service -Name 'MongoDB' -Force -ErrorAction Stop
            Start-Sleep -Seconds 3
            if (-not (Test-PortInUse 27017)) { return }
        } catch {
            Write-Host "  Could not stop Windows MongoDB ($($_.Exception.Message))" -ForegroundColor Yellow
        }
        if (Test-PortInUse 27018) {
            throw 'Port 27018 is also in use. Stop Windows MongoDB manually (admin): Stop-Service MongoDB'
        }
        $script:MongoHostPort = 27018
        $env:MONGO_HOST_PORT = '27018'
        $env:MONGODB_URI = "mongodb://admin:changeme123@localhost:27018/ontocode?authSource=admin"
        Write-Host '  Using Docker mongo on port 27018 (Windows MongoDB still on 27017)' -ForegroundColor Yellow
        return
    }

    throw 'Port 27017 is in use by a non-Docker process. Free it so Docker mongo can bind (127.0.0.1:27017).'
}

function Wait-DockerHealthy([string]$Name, [string]$Label, [int]$MaxSec = 120) {
    $deadline = (Get-Date).AddSeconds($MaxSec)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        while ((Get-Date) -lt $deadline) {
            $health = docker inspect $Name --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-health{{end}}' 2>$null
            if ($health -eq 'healthy') {
                Write-Host "  OK $Label (Docker healthy)" -ForegroundColor Green
                return $true
            }
            if ($health -eq 'no-health' -and (Get-DockerContainerRunning $Name)) {
                Write-Host "  OK $Label (running, no healthcheck)" -ForegroundColor Green
                return $true
            }
            Start-Sleep -Seconds 2
        }
    } finally {
        $ErrorActionPreference = $prev
    }
    Write-Host "  TIMEOUT $Label Docker health" -ForegroundColor Red
    return $false
}

function Wait-HttpOk($Url, $Label, $MaxSec = 120) {
    $deadline = (Get-Date).AddSeconds($MaxSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 8
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 300) {
                Write-Host "  OK $Label" -ForegroundColor Green
                return $true
            }
        } catch { }
        Start-Sleep -Seconds 3
    }
    Write-Host "  TIMEOUT $Label ($Url)" -ForegroundColor Red
    return $false
}

Write-Host "=== OntoCode hybrid dev (Docker: mongo+fuseki | Maven: Java) ===" -ForegroundColor Cyan

Write-Host ">> Docker: mongo + fuseki (requires Docker containers, not local Windows MongoDB)"
Ensure-DockerMongoPort

$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
Push-Location $Root
try {
    $composeArgs = @('-f', 'docker-compose.dev.yml')
    docker compose @composeArgs up -d mongo fuseki 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Host '  compose up failed - trying docker start...' -ForegroundColor Yellow
        docker start ontocode-mongo 2>&1 | Out-Host
        docker start ontocode-fuseki 2>&1 | Out-Host
    }
} catch {
    Write-Host "  Docker warning: $($_.Exception.Message)" -ForegroundColor Yellow
} finally {
    Pop-Location
    $ErrorActionPreference = $prevEap
}

if (-not (Wait-DockerHealthy 'ontocode-mongo' 'Mongo' 120)) {
    throw 'Docker mongo not healthy - check: docker logs ontocode-mongo'
}
if (-not (Wait-DockerHealthy 'ontocode-fuseki' 'Fuseki' 120)) {
    if (-not (Wait-HttpOk "http://localhost:3030/`$/ping" "Fuseki" 30)) {
        throw 'Docker fuseki not healthy - check: docker logs ontocode-fuseki'
    }
}

docker ps --filter "name=ontocode-mongo" --filter "name=ontocode-fuseki" --format "  {{.Names}}: {{.Status}}"

if ($SkipMaven) {
    Write-Host "`nInfra ready. Start Maven services manually or re-run without -SkipMaven." -ForegroundColor Green
    exit 0
}

$services = @(
    @{ Name = "Auth";    Dir = "ontology-auth";    Port = 8086; Jvm = "-Xms256m -Xmx512m"; Devtools = $true },
    @{ Name = "Gateway"; Dir = "ontology-gateway"; Port = 80;  Jvm = "-Xms128m -Xmx384m"; Devtools = $false },
    @{ Name = "Editor";  Dir = "ontology-editor";  Port = 8083; Jvm = "-Xms1g -Xmx3g -XX:+UseG1GC"; Devtools = $true }
)
if (-not $CoreOnly) {
    $services += @(
        @{ Name = "SWRL";   Dir = "ontology-swrl";           Port = 8084; Jvm = "-Xms256m -Xmx512m"; Devtools = $false },
        @{ Name = "Plugin"; Dir = "ontology-plugin-service"; Port = 8087; Jvm = "-Xms128m -Xmx256m"; Devtools = $false }
    )
}

Write-Host "`n>> Starting Maven services (separate windows, devtools on Auth + Editor)..."
foreach ($svc in $services) {
    $dir = Join-Path $Root $svc.Dir
    $jvm = $svc.Jvm
    $jvmEsc = $jvm -replace "'", "''"
    if ($svc.Devtools) {
        $mvnLine = 'mvn spring-boot:run -q ''-Dspring-boot.run.addResources=true'' ''-Dspring.devtools.restart.enabled=true'''
    } else {
        $mvnLine = 'mvn spring-boot:run -q'
    }
    $cmdParts = @(
        "`$env:JAVA_HOME='$($env:JAVA_HOME -replace "'","''")'",
        "`$env:JAVA_TOOL_OPTIONS='$jvmEsc'",
        "`$env:JWT_SECRET='$env:JWT_SECRET'",
        "`$env:MONGODB_URI='$env:MONGODB_URI'",
        "`$env:FUSEKI_QUERY_ENDPOINT='$env:FUSEKI_QUERY_ENDPOINT'",
        "`$env:FUSEKI_UPDATE_ENDPOINT='$env:FUSEKI_UPDATE_ENDPOINT'",
        "`$env:FUSEKI_GSP_ENDPOINT='$env:FUSEKI_GSP_ENDPOINT'",
        "`$env:FUSEKI_ADMIN_PASSWORD='$env:FUSEKI_ADMIN_PASSWORD'",
        "`$env:ONTOCODE_IMPORT_MAX_CONCURRENT='$env:ONTOCODE_IMPORT_MAX_CONCURRENT'",
        "`$env:ONTOCODE_EDITOR_REQUIRE_JWT='false'",
        "`$env:ONTOCODE_GATEWAY_REQUIRE_JWT='false'"
    )
    if ($svc.Dir -ne "ontology-gateway") {
        $cmdParts += "`$env:SPRING_PROFILES_ACTIVE='dev'"
    }
    $cmdParts += "Set-Location '$dir'"
    $cmdParts += $mvnLine
    $cmdText = $cmdParts -join '; '
    Start-Process powershell -ArgumentList @('-NoExit', '-Command', $cmdText) -WindowStyle Normal
    Write-Host ('  Started ' + $svc.Name + ' on port ' + $svc.Port)
    if ($svc.Port -eq 8086) { Start-Sleep -Seconds 12 }
    elseif ($svc.Port -eq 8083) { Start-Sleep -Seconds 5 }
}

Write-Host "`n>> Waiting for core health endpoints..."
Start-Sleep -Seconds 15
$null = Wait-HttpOk "http://localhost:8086/actuator/health" "Auth" 180
$null = Wait-HttpOk "http://localhost:8083/actuator/health" "Editor" 240
$null = Wait-HttpOk "http://localhost/actuator/health" "Gateway" 120

Write-Host "`n=== Hybrid stack ===" -ForegroundColor Cyan
Write-Host "  MongoDB   $env:MONGODB_URI  (Docker ontocode-mongo)"
Write-Host "  Fuseki    http://localhost:3030  (admin UI, admin/admin)"
Write-Host "  Gateway   http://localhost"
Write-Host '  Auth      http://localhost:8086  (DevTools auto-restart on .java save)'
Write-Host '  Editor    http://localhost:8083  (DevTools auto-restart on .java save)'
Write-Host "  Verify    .\scripts\verify-hybrid.ps1"
Write-Host "  Stop      .\scripts\stop-hybrid.ps1"
Write-Host ""
Write-Host "  Auto-refresh:" -ForegroundColor Yellow
Write-Host '    Java (auth/editor): save .java file -> DevTools restarts in ~5-15s'
Write-Host '    UI:            cd ontology-vscode-extension\webview-src; npm run dev -> http://localhost:3001 (Vite HMR)'

if ($WithFrontend) {
    $uiDir = Join-Path $Root "ontology-vscode-extension\webview-src"
    $uiCmd = 'Set-Location ''' + $uiDir + '''; npm run dev'
    Start-Process powershell -ArgumentList @('-NoExit', '-Command', $uiCmd) -WindowStyle Normal
    Write-Host '  Started Vite frontend -> http://localhost:3001' -ForegroundColor Green
}
