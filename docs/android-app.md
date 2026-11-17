# Android app

17 November 2026. Work toward stages 6 and 7 of the [North America and Android plan](north-america-and-android.md). The renderer comparison remains in `proof/android`; the application is in `android` with its own install identity, `io.github.ianrastall.offlinemaps`.

## Offline transfer milestone

The desktop can export one installed region, including its prepared search index and whichever optional layers are installed:

```powershell
python -I tools/export-phone.py G:\Maps us-il D:\Transfers\Illinois
cargo run --release --locked -p offline-maps-dev -- verify-transfer D:\Transfers\Illinois
```

Choose a new output folder outside the map store. The export copies only active files, never in-progress downloads or obsolete versions. `transfer.json` lists each file's size and SHA-256. Copy the whole resulting folder to the phone over USB, then use Maps > Import map folder and select it through Android's file picker. No internet is needed. Desktop saved places are not part of a region transfer.

The shared Rust core verifies every listed file, the map's desktop checksum, Shortbread layers and coverage, SQLite integrity, the search index's association with that map, extras formats and optional layers. An imported region is activated by one directory rename only after verification. Cancellation or a failed check leaves installed regions and saved places intact. This first import format adds regions; it refuses to replace a region already installed. Updating imported regions and saved-place exchange remain later work.

Validation of this milestone: five transfer tests passed, including activation, cancellation, path rejection, checksum failure and preservation on duplicate import. The full workspace suite passed (104 tests). A real 945.3 MB Illinois export, including all optional layers, passed `verify-transfer`.

## App delivery in progress

The intended first build has foreground satellite GPS, fix accuracy and age, follow mode, offline search and saved places. Android uses Kotlin/Jetpack Compose and the shared map page in WebView as Ian chose on 15 November. GPS fixes come from `LocationManager.GPS_PROVIDER`; no network provider or Play Services is required. A fix older than 30 seconds is shown as stale and cannot be saved as the current position. Direction is the GPS direction of travel when available, not a compass heading.

The app asks for precise location only when GPS is enabled, stops requesting fixes in the background and restarts when foregrounded. The manifest has no internet permission. Direct phone downloads are not part of this first build.

Build using the configured Android environment:

```powershell
. D:\lang\Enter-Lang.ps1
android\build.ps1 -Configuration Debug
android\build.ps1 -Configuration Release
```

Release builds use a persistent signing key under `%LOCALAPPDATA%\Jupiter\OfflineMaps\AndroidSigning`, outside Git. The password is protected for the Windows account with DPAPI. Keep the signing key and credential file for future installed upgrades. `dist` receives the APK. Android app versions are separate from Windows versions.
