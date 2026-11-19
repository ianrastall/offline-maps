# Offline Maps user and developer guide

## Using Offline Maps

### Prepare an area for offline use

1. Install the app for your device. On Android, use a system WebView with document-start scripting and web messaging support. [Windows installer and Android APK](readme.md#install-and-download-a-map)
2. While connected, open **Map > Regions…** on Windows or **Maps > Download maps** on Android. Find your region, check the estimated download size and install its detailed map. Allow space for both the download and its search index. A map is ready once the app finishes verifying and preparing it.
3. Add the layers you need from the same region screen. **Stations and addresses** adds house-number search, fuel and charging stations, and outdoor places. **Terrain** adds hillshading and contour lines. **Flood zones and public lands** adds FEMA hazard areas and USGS PAD-US coverage for the 52 U.S. catalog regions that offer the overlay. On Windows, use the **View** menu to show or hide these layers.
4. Turn off the connection and try the map, a search and any layers you plan to use. On Windows, **Ctrl+F** opens Find. On Android, use **Search offline**. Try a town or street by name. Search can match names without accents, such as `queretaro` for Querétaro. If you installed the optional address data, try a street and house number.

You can install regions independently. The catalog includes U.S. states and territories, Canadian provinces and territories, Mexico as a whole or by state, and regions across Europe, Asia, Africa, the Americas and Oceania. Antarctica is available in the world overview.

On Android, you can also start with prepared Delaware and District of Columbia maps. Unzip the starter library, open **Maps > Restore map library** and select the extracted **Starter map library** folder. Once the archive is unpacked, maps and search can be imported offline. [Starter library ZIP](https://github.com/ianrastall/offline-maps/releases/download/android-v0.9.0/OfflineMaps-Starter-Delaware-DC.zip)

Installed maps have no expiration date. When you’re online, use **Map > Check for updates…** on Windows to see what’s newer before choosing a download. On Android, open an installed region from **Maps** and tap **Update** for the data you want to refresh. The current map stays available until its replacement passes verification.

On Windows, if the drive holding your map folder is unavailable at startup, the app opens the built-in world overview. Reconnect the drive and choose **Retry map folder** to reopen the detailed maps and saved places. Use **Cancel check** if you want to stop checking for updates.

Press **F6** or choose **View > Focus map** to put keyboard focus on the map. The outline shows where focus is. Use the arrow keys to pan, plus and minus to zoom, and **Ctrl+F** to return to search. Press **Enter** in search to select the first result.

To keep an image of the visible map on Android, choose **More > Save map image**. The PNG appears in **Pictures > Offline Maps** with the visible labels, layers, markers, scale and credits. You can open it in a gallery or file manager while offline.

Android downloads can continue while the screen is off or another app is open. Enable notifications for progress and **Pause** outside the app. If a transfer is interrupted, reopen **Maps > Download maps** and use **Resume**. When several regions or layers are unfinished, open **Unfinished downloads** to choose which one to resume. The list survives app restarts.

GPS is available in the foreground on Android devices with satellite location hardware. Enable it in the app, grant precise location and wait for a fresh fix. Touching the map pauses following. **Follow** recenters on your position. Warm offline fixes were verified on a physical phone. Cold acquisition after a prolonged offline period and extended battery behavior remain unverified. [Android verification](docs/android-app.md)

### Keep and back up maps and saved places

| Platform | Where to find your data |
| --- | --- |
| Windows | **Places > Export saved places…** writes a JSON backup to a new file. **Places > Import saved places…** previews the merge. **File > Settings > Map folder** shows where the app keeps installed maps and its active `saved-places.json`. The default is `%LOCALAPPDATA%\Jupiter\OfflineMaps\Maps`. Settings, logs and the WebView2 profile live in `%LOCALAPPDATA%\Jupiter\OfflineMaps`. |
| Android | **Maps > Map backup** shows the last successful backup and which installed maps and layers have a current copy. **Back up maps and places** copies them to a folder you choose. To back up places on their own, use **Saved > Export** and restore with **Saved > Import**. |

On Windows, **Map > Map information** shows installed data and source details. To free space in **Map > Regions…**, select the region and use **Remove…**. Use **File > Settings > Map folder > Change…** to switch drives or move the app’s maps and saved places. Windows upgrades and uninstalls preserve maps, places and settings.

On Android, install a newer signed APK over the existing app to keep its active maps and places. The app offers to set up a backup when your first detailed map is ready. Before uninstalling or clearing app storage, open **Maps > Map backup**, choose **Back up maps and places**, select a folder in Documents or on an SD card, and wait for confirmation. The backup includes search indexes and installed optional layers. Allow space for the copy. The app adds verified future downloads to that backup library and keeps earlier copies. Run a fresh backup before uninstalling to include any saved places you added since the previous backup.

After reinstalling, choose **Maps > Restore map library** and select the dated **Offline Maps Backup** folder. It restores missing regions and merges saved places. Restore works offline when the folder is available on the device. Run **Back up maps and places** again if you want to resume automatic map backups after reinstalling. [Android storage documentation](https://developer.android.com/training/data-storage/shared/documents-files)

### Copy maps and places between devices

1. On Windows, open **Map > Export maps for Android…**, select installed regions, and choose a destination folder outside the map store. Wait for **Map library ready**.
2. Copy the entire **Offline Maps Library** folder to the phone over USB, for example into Documents. Allow space on the phone for that folder and the app’s verified installed copy.
3. On Android, open **Maps > Restore map library** and select the copied folder. Restore adds missing regions with their offline search indexes and optional layers. You can do the export and restore offline with the maps already installed on Windows.

For a scriptable single-region export, run the following from a checkout on the Windows PC. Replace `G:\Maps` with the map folder shown in Settings, `us-il` with the region ID, and the last path with a new folder outside the map store. Wait for any update to that region to finish before exporting. Use Python 3 for the export and the pinned Rust toolchain for the verification command.

```powershell
python -I tools/export-phone.py 'G:\Maps' us-il 'D:\Transfers\Illinois'
cargo run --release --locked -p offline-maps-dev -- verify-transfer 'D:\Transfers\Illinois'
```

Copy the entire output folder over USB. On Android, choose **Maps > Import map folder**, select the folder that contains `transfer.json` and review the included layers and dates. The folder contains the map, search index and installed optional layers for that region. An update keeps any installed optional layers absent from the folder and asks for explicit confirmation if its map is older. Allow space on the phone for the copied folder and the app’s verified private copy. The import runs while the app is open and works without internet access. [Folder update details](docs/android-app.md#android-050-offline-folder-updates)

Exchange saved places separately as JSON. To send places to Android, choose **Places > Export saved places…** on Windows, copy the new file to the phone over USB and choose **Saved > Import**. To bring places back, choose **Saved > Export** on Android, copy the JSON to Windows and open it through **Places > Import saved places…**. Review how many places will be added and how many are duplicates before confirming.

Backups contain place coordinates, names and notes. The merge keeps existing entries and skips exact name, note and coordinate matches. [Saved-place backup details](docs/android-app.md#android-030-saved-place-backups-and-preparation)

## Developing Offline Maps

### How the apps fit together

Both app shells call the Rust core for the region catalog, downloads, validation, tile reads, search and saved places. Windows uses a versioned C ABI with protobuf messages and managed handle ownership. Android uses a JNI bridge and shares the store between the map screen and the download service. Both hosts intercept `https://map.local/` to serve the shared MapLibre page, bundled assets and tiles from local storage. The web renderer opens no local TCP listener and the hosts reject other origins. Requested downloads and update checks run through the native core.

The built-in overview is a Natural Earth MBTiles archive. A detailed region uses Shortbread vector tiles in `package.mbtiles` and a derived FTS5 index in `search.sqlite`. Optional extras, terrain and overlays have their own versions. Updates stage and verify files before switching the active pointer. A phone-folder replacement that touches several layers uses a journal to recover a consistent set of pointers after interruption. `saved-places.json` lives at the store root, independent of versioned packages.

Detailed maps come from Geofabrik or are built from its OpenStreetMap extracts. The project publishes the additional region files, prepared stations and addresses, and U.S. overlays as data releases. Windows can extract extras from raw source data when a prepared file is unavailable. Android downloads prepared extras. [Regions](https://github.com/ianrastall/offline-maps/releases/tag/regions) · [Extras](https://github.com/ianrastall/offline-maps/releases/tag/extras) · [Overlays](https://github.com/ianrastall/offline-maps/releases/tag/overlays)

| If you’re changing… | Start here |
| --- | --- |
| Download, store and transfer behavior | [Rust core](crates/offline-maps-core/src) and its [HTTP transport tests](crates/offline-maps-core/tests) |
| Windows menus, settings, WebView2 or installer | [WinUI app](src/OfflineMaps.App), [Jupiter conformance notes](docs/jupiter-exceptions.md) and [build scripts](build) |
| Managed/native requests and results | [Protobuf contract](proto/offline_maps.proto), [C ABI crate](crates/offline-maps-ffi), [C# bindings](src/OfflineMaps.Interop) and [interop tests](tests/OfflineMaps.Interop.Tests) |
| Android screens, GPS, imports or download service | [Android app](android/app/src/main) and [JNI crate](crates/offline-maps-android) |
| Rendering, labels or overlays on the map | [Shared map page](web), [style files](assets/styles) and [style generator](tools/build-styles.mjs). Check both hosts after changing the shared page. |
| Catalog or data builds | [Catalog generator](tools/make-regions.py), [regional tile builders](tools/regions-build), [overlay builder](tools/layers), [overview builder](tools/overview) and [prepared extras publisher](tools/bundles/publish.py) |
| Local diagnostics and release verification | [Developer CLI](crates/offline-maps-dev/src/main.rs) and [acceptance scripts](tools/acceptance) |

### Set up the shared renderer

Clone the repository and use Node.js 24 for the renderer dependencies and assets. For Windows, also clone `jupiter-ui` into the same parent directory. If you already have the Offline Maps checkout, clone only the missing sibling.

```powershell
git clone https://github.com/ianrastall/offline-maps.git
git clone https://github.com/ianrastall/jupiter-ui.git
Set-Location offline-maps
Push-Location proof/renderer
npm ci
node fetch-assets.mjs
Pop-Location
```

The renderer downloads pinned assets into `data/renderer-assets/`. Both platform builds consume those glyphs and sprites, the locked npm dependencies, and the checked-in map styles and world overview. `proof/renderer` and `proof/android` also contain the original renderer experiments. The shipping apps live under `src/OfflineMaps.App` and `android`.

### Build Windows

Use PowerShell 7 on Windows x64 with the MSVC C++ build tools, the Rust toolchain in [rust-toolchain.toml](rust-toolchain.toml) and the .NET SDK in [global.json](global.json). The Windows build imports the sibling Jupiter.UI targets and runs the shared style checks. To compile the installer, install Inno Setup 6 and put the signed runtime installer at `third_party/webview2/MicrosoftEdgeWebView2RuntimeInstallerX64.exe`. [WebView2 Evergreen Standalone x64 installer](https://go.microsoft.com/fwlink/?linkid=2124701)

Run the build and packaging commands from the repository root.

```powershell
dotnet build src/OfflineMaps.App -c Release
pwsh build/build.ps1
```

The first command builds the app for development. The release script runs formatting, Clippy, Rust and Windows interop tests, publishes the app and produces `dist/OfflineMaps-<version>-win-x64-setup.exe`. If Inno Setup is installed in a custom location, pass `-InnoCompiler` with the path to `ISCC.exe`. Keep the Windows release versions in `Cargo.toml` and `Directory.Build.props` in sync. Validate installer upgrades and Start Menu launch with an isolated test profile where possible.

### Build Android

Use the `android-v0.10.0` tag to reproduce the published preview.

On Windows, set `JAVA_HOME` to JDK 21 and `ANDROID_HOME` to the Android SDK. Install SDK platform 36, build tools 36.1.0, NDK `30.0.16248370`, Python 3 and `cargo-ndk`. Prepare the renderer assets, then run these commands from the repository root.

```powershell
rustup target add aarch64-linux-android x86_64-linux-android
pwsh -File android/build.ps1 -Configuration Debug
```

The script runs the GPS marker regression check, builds the JNI library for arm64 and x86-64, copies the shared page and assets, and runs Gradle assembly and lint. The APK is copied to `dist/`. Use `-Configuration Release` for a signed APK. The release script on Windows stores signing material outside the repo in `%LOCALAPPDATA%\Jupiter\OfflineMaps\AndroidSigning`. Keep the existing release key for in-place upgrades. Android’s version name and code are set independently in [android/app/build.gradle.kts](android/app/build.gradle.kts).

### Test changes and work with local data

```powershell
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
dotnet test tests/OfflineMaps.Interop.Tests -c Release
node --test tools/acceptance/gps-marker.test.mjs
```

Use separate Windows `--settings DIR` and `--store DIR` paths to test without writing to your everyday profile. `offline-maps-dev` can inspect an MBTiles file, serve local package fixtures, build a search index and query a store. The `serve` subcommand starts a developer-only HTTP server on loopback. For Windows download tests, `OFFLINE_MAPS_PACKAGE_URL` and `OFFLINE_MAPS_EXTRAS_URL` override package and extract URLs. `OFFLINE_MAPS_OVERLAYS_URL` points to a folder of overlay files, and `OFFLINE_MAPS_BUNDLES_URL` points to a folder containing the prepared-extras `manifest.json`.

Keep downloaded datasets under `data/`. Keep private place backups and generated build or test artifacts out of Git. For changes to downloads, verify cancellation and resume with an existing working map. For changes to activation, check that a failed update leaves installed data usable. Use the [offline acceptance procedure](docs/validation.md#offline-acceptance-test) to check real rendering and search with the network disconnected.

For a smoke test of the installed Windows app, close the app and run `powershell -NoProfile -File tools/acceptance/offline-check.ps1 -RendererOnly` from the repository root with Node.js 24 available. Add `-Fixture <map-store>` to exercise local Illinois map data. The script copies only the active Illinois files and uses a new settings profile and a sample saved place. It prints the folder containing its map image and report.

### Further reading

- [Data providers, formats and storage contract](docs/sources-and-architecture.md)
- [Windows release and validation history](docs/validation.md#0202-keyboard-access)
- [Android release and device verification history](docs/android-app.md)
- [North America and Android project plan](docs/north-america-and-android.md) and [worldwide expansion plan](docs/worldwide.md)
- [Project brief](docs/project-brief.md), [Windows 0.19.0 handoff](docs/desktop-handoff.md) and [renderer decisions for Windows](docs/renderer-proof.md) and [Android](docs/android-renderer-proof.md)
