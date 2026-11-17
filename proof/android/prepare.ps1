# Builds the Rust core for Android and copies the map page and the overview into the APK's
# assets, the same files the Windows project (src/OfflineMaps.App/OfflineMaps.App.csproj) puts
# in its Web and Overview folders. Run before Gradle:
#
#   . D:\lang\Enter-Lang.ps1
#   proof\android\prepare.ps1
#   cd proof\android; .\gradlew.bat assembleDebug
#
# Needs: cargo-ndk and the Android targets, `npm ci` in proof/renderer, and
# `node proof/renderer/fetch-assets.mjs` (as for the Windows build).
param([string]$AppDirectory = (Join-Path $PSScriptRoot 'app\src\main'))
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$app = [IO.Path]::GetFullPath($AppDirectory)
$allowedApps = @((Join-Path $root 'proof\android\app\src\main'), (Join-Path $root 'android\app\src\main'))
if ($app -notin $allowedApps) { throw 'The Android app directory must be one of this repository''s two Android targets.' }

Push-Location $root
try {
    cargo ndk -t arm64-v8a -t x86_64 -o (Join-Path $app 'jniLibs') build --release --locked -p offline-maps-android
    if ($LASTEXITCODE -ne 0) { throw 'cargo ndk failed' }
    # The C ABI library is built as a dependency; the bridge links the core itself.
    Get-ChildItem (Join-Path $app 'jniLibs') -Recurse -Filter 'liboffline_maps_ffi.so' | Remove-Item
} finally {
    Pop-Location
}

$assets = Join-Path $app 'assets'
if (Test-Path -LiteralPath $assets) { Remove-Item -LiteralPath $assets -Recurse -Force }
$web = Join-Path $assets 'web'
function Copy-To([string]$folder, [string[]]$files) {
    $target = Join-Path $web $folder
    New-Item -ItemType Directory -Force $target | Out-Null
    foreach ($file in $files) {
        if (-not (Test-Path $file)) { throw "Missing: $file (see the notes at the top of this script)" }
        Copy-Item $file $target
    }
}
$modules = Join-Path $root 'proof\renderer\node_modules'
$rendererAssets = Join-Path $root 'data\renderer-assets'
Copy-To '' (Get-ChildItem (Join-Path $root 'web') -File).FullName
Copy-To 'maplibre' (@('maplibre-gl.mjs', 'maplibre-gl-shared.mjs', 'maplibre-gl-worker.mjs', 'maplibre-gl.css') |
    ForEach-Object { Join-Path $modules "maplibre-gl\dist\$_" }) + (Join-Path $modules 'maplibre-gl\LICENSE.txt')
Copy-To 'contour' @((Join-Path $modules 'maplibre-contour\LICENSE'))
Copy-Item (Join-Path $modules 'maplibre-contour\dist\index.mjs') (Join-Path $web 'contour\maplibre-contour.mjs')
Copy-To 'rtl' @((Join-Path $modules '@mapbox\mapbox-gl-rtl-text\dist\mapbox-gl-rtl-text.js'), (Join-Path $modules '@mapbox\mapbox-gl-rtl-text\LICENSE.md'))
Copy-To 'styles' (Get-ChildItem (Join-Path $root 'assets\styles\offline-maps-*.json')).FullName
Copy-To 'sprites' (Get-ChildItem (Join-Path $rendererAssets 'sprites\base*')).FullName
$glyphs = Join-Path $rendererAssets 'glyphs'
foreach ($font in Get-ChildItem $glyphs -Directory) {
    Copy-To "glyphs\$($font.Name)" (Get-ChildItem $font.FullName -Filter *.pbf).FullName
}
New-Item -ItemType Directory -Force (Join-Path $assets 'overview') | Out-Null
Copy-Item (Join-Path $root 'assets\overview\world-overview.mbtiles') (Join-Path $assets 'overview')

$size = (Get-ChildItem $assets -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
'Assets: {0:N1} MB' -f $size
Get-ChildItem (Join-Path $app 'jniLibs') -Recurse -File | ForEach-Object { '{0}  {1:N1} MB' -f $_.FullName.Substring($app.Length + 1), ($_.Length / 1MB) }
