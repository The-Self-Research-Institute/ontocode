# Verify GraphDB is using D: drive after restart
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "GraphDB Configuration Verification" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host ""

# Check config file
$configFile = "$env:APPDATA\GraphDB\conf\graphdb.properties"
if (Test-Path $configFile) {
    Write-Host "✓ Configuration file exists: $configFile" -ForegroundColor Green
    Write-Host ""
    Write-Host "Configuration contents:" -ForegroundColor Yellow
    Get-Content $configFile | Where-Object { $_ -match "graphdb" -and $_ -notmatch "^#" }
} else {
    Write-Host "✗ Configuration file NOT found!" -ForegroundColor Red
}

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "Checking Directory Sizes" -ForegroundColor Cyan  
Write-Host "==================================================" -ForegroundColor Cyan

# Check old location
$oldPath = "$env:APPDATA\GraphDB"
if (Test-Path $oldPath) {
    $oldSize = (Get-ChildItem $oldPath -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum / 1MB
    Write-Host "OLD location (C:): $oldPath" -ForegroundColor Yellow
    Write-Host "  Size: $([math]::Round($oldSize, 2)) MB" -ForegroundColor Gray
}

# Check new location  
$newPath = "D:\GraphDB-Data"
if (Test-Path $newPath) {
    $newSize = (Get-ChildItem $newPath -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum / 1MB
    Write-Host "NEW location (D:): $newPath" -ForegroundColor Green
    Write-Host "  Size: $([math]::Round($newSize, 2)) MB" -ForegroundColor Gray
    
    # Check if data exists
    $repoPath = "$newPath\data\repositories"
    if (Test-Path $repoPath) {
        $repos = Get-ChildItem $repoPath -Directory
        Write-Host "  Repositories found: $($repos.Count)" -ForegroundColor Cyan
        foreach ($repo in $repos) {
            Write-Host "    - $($repo.Name)" -ForegroundColor White
        }
    }
}

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "Disk Space Check" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

$drives = Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Name -eq 'C' -or $_.Name -eq 'D' }
foreach ($drive in $drives) {
    $freeGB = [math]::Round($drive.Free / 1GB, 2)
    $totalGB = [math]::Round(($drive.Used + $drive.Free) / 1GB, 2)
    $freePercent = [math]::Round(($drive.Free / ($drive.Used + $drive.Free)) * 100, 1)
    
    $color = if ($freePercent -lt 10) { "Red" } elseif ($freePercent -lt 20) { "Yellow" } else { "Green" }
    
    Write-Host "$($drive.Name): drive - Free: $freeGB GB / $totalGB GB ($freePercent%)" -ForegroundColor $color
}

Write-Host ""
Write-Host "Next: Start GraphDB and watch for logs mentioning D:\GraphDB-Data" -ForegroundColor Yellow
Write-Host ""
