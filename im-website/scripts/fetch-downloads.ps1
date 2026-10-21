# Collect client artifacts into im-website/public/downloads for the official site.
# Renames are normalized to ASCII (no spaces / CJK) so static links stay URL-safe.
#   Veytalk-Setup-<ver>.exe               <- electron/release/*Setup*.exe  (NSIS installer)
#   Veytalk-Windows-x64-portable.zip      <- zip of electron/release/win-unpacked (no-install build)
#   Veytalk-Android.apk                   <- first *.apk found under repo (skipped when absent)
# Usage: powershell -ExecutionPolicy Bypass -File scripts/fetch-downloads.ps1
#        (or: npm run fetch:downloads)
$ErrorActionPreference = 'Stop'
$site = Split-Path -Parent $PSScriptRoot          # im-website/
$root = Split-Path -Parent $site                  # repo root
$dest = Join-Path $site 'public\downloads'
New-Item -ItemType Directory -Force -Path $dest | Out-Null

# Read version from electron/package.json (single source of truth for artifact names)
$pkg = Get-Content (Join-Path $root 'electron\package.json') -Raw -Encoding UTF8
$ver = ($pkg | ConvertFrom-Json).version
Write-Host "electron version: $ver"

# 1) NSIS installer -> Veytalk-Setup-<ver>.exe
$exe = Get-ChildItem (Join-Path $root 'electron\release\*Setup*.exe') -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*.blockmap' } | Select-Object -First 1
if ($exe) {
    $target = Join-Path $dest "Veytalk-Setup-$ver.exe"
    Copy-Item $exe.FullName $target -Force
    Write-Host "[ok] installer  -> Veytalk-Setup-$ver.exe ($([math]::Round($exe.Length/1MB,1)) MB)"
} else {
    Write-Warning '[skip] no *Setup*.exe under electron/release; run "npm run build" in electron/ first'
}

# 2) win-unpacked dir -> Veytalk-Windows-x64-portable.zip (Compress-Archive is slow for hundreds of MB)
$unpacked = Join-Path $root 'electron\release\win-unpacked'
if (Test-Path $unpacked) {
    $zip = Join-Path $dest "Veytalk-Windows-x64-portable.zip"
    Write-Host "zipping win-unpacked (this may take a few minutes)..."
    Compress-Archive -Path (Join-Path $unpacked '*') -DestinationPath $zip -CompressionLevel Optimal
    Write-Host "[ok] portable -> Veytalk-Windows-x64-portable.zip ($([math]::Round((Get-Item $zip).Length/1MB,1)) MB)"
} else {
    Write-Warning '[skip] electron/release/win-unpacked not found; run "npm run build:dir" in electron/ first'
}

# 3) Android apk (optional; repo may not carry one yet)
$apk = Get-ChildItem (Join-Path $root '*.apk') -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch 'node_modules' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($apk) {
    Copy-Item $apk.FullName (Join-Path $dest 'Veytalk-Android.apk') -Force
    Write-Host "[ok] android    -> Veytalk-Android.apk (from $($apk.FullName))"
} else {
    Write-Warning '[skip] no *.apk found in repo; android download button will 404 until one is placed'
}

Write-Host "`ndownloads dir: $dest"
Get-ChildItem $dest -File | Where-Object { $_.Name -ne 'README.md' } |
    ForEach-Object { Write-Host ("  {0}  {1} MB" -f $_.Name, [math]::Round($_.Length/1MB,1)) }
