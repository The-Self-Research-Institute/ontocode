# Move GraphDB Data to Drive with More Space
# This script helps move GraphDB data from C: drive to another location

param(
    [Parameter(Mandatory=$true)]
    [string]$NewLocation,  # e.g., "D:\GraphDB-Data"
    
    [Parameter(Mandatory=$false)]
    [string]$GraphDBHome = "C:\GraphDB\graphdb-free-10.7.0",  # Adjust to your GraphDB installation
    
    [Parameter(Mandatory=$false)]
    [switch]$KeepOldData  # Copy existing data to new location
)

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "GraphDB Data Directory Migration Tool" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host ""

# Check if GraphDB is running
$graphDBProcess = Get-Process | Where-Object { $_.ProcessName -like "*graphdb*" -or $_.ProcessName -like "*java*" -and $_.CommandLine -like "*graphdb*" }
if ($graphDBProcess) {
    Write-Host "[ERROR] GraphDB appears to be running!" -ForegroundColor Red
    Write-Host "Please stop GraphDB before running this script." -ForegroundColor Yellow
    Write-Host "Process(es) found: $($graphDBProcess.ProcessName -join ', ')" -ForegroundColor Yellow
    exit 1
}

# Create new directories
Write-Host "[1] Creating new data directories at: $NewLocation" -ForegroundColor Green
New-Item -ItemType Directory -Path "$NewLocation\data" -Force | Out-Null
New-Item -ItemType Directory -Path "$NewLocation\work" -Force | Out-Null
New-Item -ItemType Directory -Path "$NewLocation\logs" -Force | Out-Null
Write-Host "    ✓ Directories created" -ForegroundColor Green
Write-Host ""

# Copy existing data if requested
$oldDataPath = "$env:APPDATA\GraphDB"
if ($KeepOldData -and (Test-Path $oldDataPath)) {
    Write-Host "[2] Copying existing GraphDB data..." -ForegroundColor Green
    Write-Host "    From: $oldDataPath" -ForegroundColor Gray
    Write-Host "    To: $NewLocation" -ForegroundColor Gray
    
    if (Test-Path "$oldDataPath\data") {
        Write-Host "    Copying data folder..." -ForegroundColor Yellow
        Copy-Item -Path "$oldDataPath\data\*" -Destination "$NewLocation\data" -Recurse -Force
    }
    
    Write-Host "    ✓ Data copied" -ForegroundColor Green
} else {
    Write-Host "[2] Skipping data copy (starting fresh)" -ForegroundColor Yellow
}
Write-Host ""

# Update GraphDB configuration
$configFile = "$GraphDBHome\conf\graphdb.properties"
Write-Host "[3] Updating GraphDB configuration..." -ForegroundColor Green
Write-Host "    Config file: $configFile" -ForegroundColor Gray

if (Test-Path $configFile) {
    # Backup original config
    Copy-Item $configFile "$configFile.backup.$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Write-Host "    ✓ Backup created" -ForegroundColor Green
    
    # Read current config
    $configContent = Get-Content $configFile
    
    # Convert Windows path to GraphDB format (forward slashes)
    $graphDBPath = $NewLocation -replace '\\', '/'
    
    # Add or update properties
    $newConfig = @()
    $foundHome = $false
    $foundData = $false
    $foundWork = $false
    $foundLogs = $false
    
    foreach ($line in $configContent) {
        if ($line -match '^\s*graphdb\.home\s*=') {
            $newConfig += "graphdb.home=$graphDBPath"
            $foundHome = $true
        }
        elseif ($line -match '^\s*graphdb\.data\s*=') {
            $newConfig += "graphdb.data=$graphDBPath/data"
            $foundData = $true
        }
        elseif ($line -match '^\s*graphdb\.workbench\s*=') {
            $newConfig += "graphdb.workbench=$graphDBPath/work"
            $foundWork = $true
        }
        elseif ($line -match '^\s*graphdb\.logs\s*=') {
            $newConfig += "graphdb.logs=$graphDBPath/logs"
            $foundLogs = $true
        }
        else {
            $newConfig += $line
        }
    }
    
    # Add properties if not found
    if (-not $foundHome) { $newConfig += "graphdb.home=$graphDBPath" }
    if (-not $foundData) { $newConfig += "graphdb.data=$graphDBPath/data" }
    if (-not $foundWork) { $newConfig += "graphdb.workbench=$graphDBPath/work" }
    if (-not $foundLogs) { $newConfig += "graphdb.logs=$graphDBPath/logs" }
    
    # Write updated config
    $newConfig | Out-File -FilePath $configFile -Encoding UTF8
    Write-Host "    ✓ Configuration updated" -ForegroundColor Green
} else {
    Write-Host "    [WARNING] Config file not found: $configFile" -ForegroundColor Yellow
    Write-Host "    You may need to manually create/edit it" -ForegroundColor Yellow
}
Write-Host ""

# Check free space on new location
$drive = Split-Path -Qualifier $NewLocation
$disk = Get-PSDrive -Name $drive.TrimEnd(':')
$freeSpaceGB = [math]::Round($disk.Free / 1GB, 2)
$totalSpaceGB = [math]::Round(($disk.Used + $disk.Free) / 1GB, 2)
$freePercent = [math]::Round(($disk.Free / ($disk.Used + $disk.Free)) * 100, 1)

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "✓ MIGRATION COMPLETE!" -ForegroundColor Green
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "New GraphDB location: $NewLocation" -ForegroundColor Green
Write-Host "Disk space on $drive" -ForegroundColor Cyan
Write-Host "  Free: $freeSpaceGB GB ($freePercent%)" -ForegroundColor Green
Write-Host "  Total: $totalSpaceGB GB" -ForegroundColor Gray
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Yellow
Write-Host "  1. Start GraphDB" -ForegroundColor White
Write-Host "  2. Check logs to verify new location" -ForegroundColor White
Write-Host "  3. Recreate repository if needed" -ForegroundColor White
Write-Host "  4. Test with a small ontology upload" -ForegroundColor White
Write-Host ""
Write-Host "GraphDB config: $configFile" -ForegroundColor Gray
Write-Host ""
