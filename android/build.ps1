param([ValidateSet('Debug', 'Release')][string]$Configuration = 'Release')
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    node --test tools/acceptance/gps-marker.test.mjs
    if ($LASTEXITCODE -ne 0) { throw 'GPS renderer checks failed.' }
} finally { Pop-Location }
& (Join-Path $repo 'proof\android\prepare.ps1') -AppDirectory (Join-Path $PSScriptRoot 'app\src\main')
if ($LASTEXITCODE -ne 0) { throw 'Android preparation failed.' }
Push-Location $repo
try {
    python -I tools/android-notices.py
    if ($LASTEXITCODE -ne 0) { throw 'Android notices failed.' }
} finally { Pop-Location }
if ($Configuration -eq 'Release') {
    # Local, durable signing identity; kept outside the repository and protected
    # with Windows DPAPI for this account. Reused for every installed upgrade.
    $signing = Join-Path $env:LOCALAPPDATA 'Jupiter\OfflineMaps\AndroidSigning'
    New-Item -ItemType Directory -Force $signing | Out-Null
    $credentialPath = Join-Path $signing 'signing.credential.xml'
    if (-not (Test-Path -LiteralPath $credentialPath)) {
        $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
        $credential = [PSCredential]::new('offline-maps', (ConvertTo-SecureString $password -AsPlainText -Force))
        $credential | Export-Clixml -LiteralPath $credentialPath
    }
    $credential = Import-Clixml -LiteralPath $credentialPath
    $env:OFFLINE_MAPS_ANDROID_KEYSTORE = Join-Path $signing 'offline-maps.jks'
    $env:OFFLINE_MAPS_ANDROID_STORE_PASSWORD = $credential.GetNetworkCredential().Password
    $env:OFFLINE_MAPS_ANDROID_KEY_PASSWORD = $env:OFFLINE_MAPS_ANDROID_STORE_PASSWORD
    if (-not (Test-Path -LiteralPath $env:OFFLINE_MAPS_ANDROID_KEYSTORE)) {
        keytool -genkeypair -keystore $env:OFFLINE_MAPS_ANDROID_KEYSTORE -storepass:env OFFLINE_MAPS_ANDROID_STORE_PASSWORD -keypass:env OFFLINE_MAPS_ANDROID_KEY_PASSWORD -alias offline-maps -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Offline Maps, O=Ian Rastall'
        if ($LASTEXITCODE -ne 0) { throw 'APK signing key creation failed.' }
    }
}
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat "assemble$Configuration" lint
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    $folder = Join-Path $repo 'dist'
    New-Item -ItemType Directory -Force $folder | Out-Null
    $output = Join-Path $PSScriptRoot "app\build\outputs\apk\$($Configuration.ToLowerInvariant())"
    $metadata = Get-Content -LiteralPath (Join-Path $output 'output-metadata.json') -Raw | ConvertFrom-Json
    $version = $metadata.elements[0].versionName
    $apk = Join-Path $output $metadata.elements[0].outputFile
    $target = Join-Path $folder "OfflineMaps-android-$version-$($Configuration.ToLowerInvariant()).apk"
    Copy-Item -LiteralPath $apk -Destination $target
    Get-FileHash -LiteralPath $target -Algorithm SHA256
} finally {
    Pop-Location
    Remove-Item Env:OFFLINE_MAPS_ANDROID_STORE_PASSWORD,Env:OFFLINE_MAPS_ANDROID_KEY_PASSWORD -ErrorAction SilentlyContinue
}
