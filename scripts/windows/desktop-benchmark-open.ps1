# Measures OWLAPI parse time for local .owl fixtures (Protégé-style open benchmark).
# Usage: .\scripts\desktop-benchmark-open.ps1 [path-to.owl]
#        .\scripts\desktop-benchmark-open.ps1   # runs default fixture set

param(
    [string[]]$Files = @()
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$editorDir = Join-Path $repoRoot "ontology-editor"

if ($Files.Count -eq 0) {
    $defaults = @(
        (Join-Path $repoRoot "scripts\load-test\fixtures\small-20kb.owl"),
        (Join-Path $repoRoot "scripts\load-test\fixtures\medium-10mb.owl"),
        (Join-Path $repoRoot "test-data\sample-ontology.owl"),
        (Join-Path $repoRoot "company-swrl-test.owl")
    )
    $Files = $defaults | Where-Object { Test-Path $_ }
}

if ($Files.Count -eq 0) {
    Write-Error "No .owl files found. Pass a path: .\scripts\desktop-benchmark-open.ps1 C:\path\to\file.owl"
}

Write-Host ""
Write-Host "OWLAPI open benchmark (Protege-style parse only, no Fuseki)" -ForegroundColor Cyan
Write-Host ("{0,-50} {1,10} {2,12}" -f "File", "Size (MB)", "Parse (ms)")
Write-Host ("-" * 76)

foreach ($f in $Files) {
    $full = Resolve-Path $f
    $sizeMb = [math]::Round((Get-Item $full).Length / 1MB, 2)
    $escaped = $full.Path

    Push-Location $editorDir
    try {
        mvn -q test-compile -DskipTests 2>&1 | Out-Null
        mvn -q "dependency:build-classpath" "-Dmdep.outputFile=target/cp.txt" "-DincludeScope=test" 2>&1 | Out-Null
        $cp = Get-Content target/cp.txt -Raw
        $classpath = "target/test-classes;target/classes;$cp"
        $output = & java -cp $classpath self.research.ontology.owlEditor.benchmark.DesktopOpenBenchmark $escaped 2>&1
    } finally {
        Pop-Location
    }

    $ms = ($output | Select-String -Pattern 'PARSE_MS=(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if (-not $ms) {
        $ms = "ERR"
        Write-Host $output
    }
    $name = Split-Path $full -Leaf
    Write-Host ("{0,-50} {1,10} {2,12}" -f $name, $sizeMb, $ms)
}

Write-Host ""
Write-Host "After opening in desktop, GET http://127.0.0.1:13080/api/desktop/open-metrics/{projectId}" -ForegroundColor DarkGray
Write-Host "for import total ms (includes copy + warm wait)." -ForegroundColor DarkGray
