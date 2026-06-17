# One-off helper: create build-assets/icon-512.png from icon.png (512x512).
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'build-assets\icon.png'
$dst = Join-Path $root 'build-assets\icon-512.png'
$img = [System.Drawing.Image]::FromFile($src)
try {
    $bmp = New-Object System.Drawing.Bitmap 512, 512
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($img, 0, 0, 512, 512)
    $g.Dispose()
    $bmp.Save($dst, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "Wrote $dst"
}
finally {
    $img.Dispose()
}
