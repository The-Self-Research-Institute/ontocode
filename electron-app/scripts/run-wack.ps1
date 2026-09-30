param(
    [Parameter(Mandatory = $true)][string]$Appx,
    [string]$Publisher = 'CN=48F0ADC2-1FD0-4664-AD5E-C80F7D22F534',
    [string]$ReportDir = (Join-Path $env:TEMP 'ontocode-wack')
)

$ErrorActionPreference = 'Stop'
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this from an elevated PowerShell (WACK needs admin).'
}

$kit = 'C:\Program Files (x86)\Windows Kits\10'
$appcert = Join-Path $kit 'App Certification Kit\appcert.exe'
$signtool = Get-ChildItem (Join-Path $kit 'bin') -Recurse -Filter signtool.exe |
    Where-Object { $_.FullName -match '\\x64\\' } | Sort-Object FullName -Descending | Select-Object -First 1
if (-not (Test-Path $appcert)) { throw "appcert.exe not found under $kit" }
if (-not $signtool) { throw "signtool.exe not found under $kit\bin" }

$appxPath = (Resolve-Path $Appx).Path
$signed = Join-Path ([IO.Path]::GetTempPath()) ("wack-" + [IO.Path]::GetFileName($appxPath))
Copy-Item $appxPath $signed -Force

$cert = New-SelfSignedCertificate -Type Custom -Subject $Publisher -KeyUsage DigitalSignature `
    -FriendlyName 'OntoCode WACK test' -CertStoreLocation 'Cert:\CurrentUser\My' `
    -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}')
$cer = Join-Path ([IO.Path]::GetTempPath()) 'ontocode-wack-test.cer'
Export-Certificate -Cert $cert -FilePath $cer | Out-Null
Import-Certificate -FilePath $cer -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null

try {
    & $signtool.FullName sign /fd SHA256 /sha1 $cert.Thumbprint $signed
    if ($LASTEXITCODE -ne 0) { throw "signtool failed ($LASTEXITCODE)" }

    New-Item -ItemType Directory -Force $ReportDir | Out-Null
    $report = Join-Path (Resolve-Path $ReportDir).Path ("wack-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.xml')
    & $appcert reset
    & $appcert test -appxpackagepath $signed -reportoutputpath $report
    Write-Host "Report: $report"
} finally {
    Get-ChildItem 'Cert:\LocalMachine\TrustedPeople' | Where-Object { $_.Thumbprint -eq $cert.Thumbprint } | Remove-Item
    Remove-Item "Cert:\CurrentUser\My\$($cert.Thumbprint)"
    Remove-Item $cer, $signed -Force -ErrorAction SilentlyContinue
}
