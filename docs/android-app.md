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

## Android 0.1.0 preview

Published 17 November 2026 as [android-v0.1.0](https://github.com/ianrastall/offline-maps/releases/tag/android-v0.1.0), built from `9a0a6e0b0397332b4d12acb5762463e5f630e0d2`. The signed APK is 127,815,671 bytes; SHA-256 `a93d8ace46d1af51c10acbc2a0e92b1b21c1f027c43c63eef2aca4b833b32cc2`, matching GitHub's asset digest. It is marked as a prerelease and leaves the Windows release as latest.

The first build has foreground satellite GPS, visible/used satellite counts while waiting, fix accuracy and age, follow mode, offline search and saved places. Android uses Kotlin/Jetpack Compose and the shared map page in WebView as Ian chose on 15 November. GPS fixes come from `LocationManager.GPS_PROVIDER`; no network provider or Play Services is required. A fix older than 30 seconds is shown as stale and cannot be saved as the current position. Direction is the GPS direction of travel when available, not a compass heading. Touching the map suspends following; Follow recenters on a fresh fix.

The app asks for precise location only when GPS is enabled, stops requesting fixes in the background and restarts when foregrounded. The manifest has no internet permission. Direct phone downloads are not part of this first build.

Build using the configured Android environment:

```powershell
. D:\lang\Enter-Lang.ps1
android\build.ps1 -Configuration Debug
android\build.ps1 -Configuration Release
```

Release builds use a persistent signing key under `%LOCALAPPDATA%\Jupiter\OfflineMaps\AndroidSigning`, outside Git. The password is protected for the Windows account with DPAPI. Keep the signing key and credential file for future installed upgrades. `dist` receives the APK. Android app versions are separate from Windows versions.

### Verification, 17 November 2026

- Rust workspace: 104 tests passed; formatting and Clippy with warnings denied passed. The GPS marker regression check (`node --test tools/acceptance/gps-marker.test.mjs`) runs in the Android build. It checks MapLibre's required position-before-activation order, following, reuse, stale fixes and removal when GPS is off.
- Android debug and signed release builds passed; lint passed with dependency/version/style warnings. The APK's v2 signature verifies. Dependencies use the existing SDK 36 toolchain, AGP 9.5.0, Kotlin/Compose compiler 2.2.10 and Compose BOM 2025.10.01. The minimum Android version is 10 (API 29); the device's WebView needs document-start script and web-message support.
- Galaxy S26 FE (SM-S741U, Android 17): the signed app installed alongside the renderer proof. Illinois imported through the actual Android file picker, including the search index, stations/addresses, terrain and overlays. Roads, labels, route shields, contour lines, stations and attribution rendered. Offline search found Springfield and selecting it moved the map there. The phone was already in airplane mode; Android reported `Active default network: none`, and the installed app had no Internet permission.
- Physical satellite GPS is **not yet verified**: Android accepted the foreground GPS registration, but no fix arrived during the first attempt (the diagnostic's last satellite count was three). USB then lost its ADB interface; the last installed phone build predates the subsequent GPS-marker, follow and satellite-status fixes. Install the latest APK once ADB is restored before relying on that phone build's GPS. Do not treat the old renderer proof as the application.
- Android 16 emulator: imported the same real Illinois transfer. A simulated Chicago satellite fix showed the position marker and ±5 m accuracy, recentered at zoom 14 and could be saved. Panning suspended following and left the GPS marker at its actual position. Denying location permission left the offline map usable. Returning to the home screen removed the app's GPS listener and left the GPS provider off; returning resumed requests. A saved GPS point and the imported map survived an in-place APK reinstall, with the saved-place file byte-for-byte unchanged.
- The initial Compose/WebView layout resolved percentage and viewport-unit heights to zero. The Android document-start shim now sets the page height from `innerHeight` on initialization and resize. The map renders at the full measured height on both devices. The emulator uses software graphics for repeatable hidden-window rendering.

Still to check: a fresh satellite fix on the real phone with the final build, extended walking/battery behavior, a signed release reinstall on the phone, renderer-process recovery, and low-space/cancel behavior through Android's picker (core cancellation/failure preservation is covered). Phone downloads, imported-region updates, saved-place exchange and routing remain later stages.
