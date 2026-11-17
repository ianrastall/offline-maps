# Offline Maps

A Windows app for downloading road maps before an emergency and using them without internet access. Regions: the US states, the District of Columbia, Puerto Rico and the US Virgin Islands; the Canadian provinces and territories; Mexico, as a whole or by state; and 440 regions in 190 other countries and territories across every continent but Antarctica: 286 from Geofabrik's map packages and 154 built from Geofabrik's data and published in this repository's [`regions` release](https://github.com/ianrastall/offline-maps/releases/tag/regions) (among them China's provinces, Central America and the Caribbean, Australia's states, England's counties, and Fiji, New Zealand and Russia's Far East split at the 180th meridian), in any combination. Labels are drawn in every script the bundled Noto Sans covers, Chinese, Japanese, Korean, Arabic and Hebrew included; towns and countries show their English names where OpenStreetMap has them. Scripts whose letters must be joined or reordered (the Indic scripts, Thai, Lao, Khmer, Myanmar, Thaana) are drawn with broken letter shapes, which those regions say in Map > Regions. Everything runs on your computer; there is no account, server or tracking.

## Install

Download `OfflineMaps-<version>-win-x64-setup.exe` from [Releases](https://github.com/ianrastall/offline-maps/releases) and run it. It installs for the current user without administrator rights, adds Offline Maps to the Start Menu and registers an uninstaller. Requires Windows 11 24H2 or later, x64. Maps, saved places and settings (in `%LOCALAPPDATA%\Jupiter\OfflineMaps`, or the map folder chosen in Settings) are kept through upgrades and uninstalling.

Then open Map > Regions, choose regions and download them while you have a connection.

## Features

- **Overview:** a map of the whole world is built in (Natural Earth: coast, lakes, rivers, state, province and country borders, main highways, cities, country names), so the map shows every country from the first start and around the regions you install. Zoomed out, the overview is the whole map; closer in, each installed region's own map replaces it. Pointing at a region that can be downloaded and has no map installed highlights it; clicking it offers its download.
- **Map:** downloads the map package for each chosen region (Geofabrik's, or for Canada, Alaska and the Mexican states one made from Geofabrik's data and published in this repository's [`regions` release](https://github.com/ianrastall/offline-maps/releases/tag/regions)) with resume, verification and atomic activation, and shows the installed regions as one map with MapLibre in WebView2, entirely from local files. Tiles on a border between installed regions are merged. Light and dark map styles show interstate, US and state route shields.
- **Terrain:** an optional download per region of Mapterhorn elevation tiles (made from national elevation models where they are open and Copernicus GLO-30 elsewhere; to zoom 12, about 15 m per pixel), drawn as hillshading and contour lines in feet or metres. The credits line on the map names the producers of the terrain in view. View > Terrain shading and View > Contour lines turn them on and off.
- **Flood zones and public lands:** an optional download per region of FEMA flood hazard zones and USGS PAD-US public lands (coloured by public access), prebuilt by `tools/layers/build_overlays.py` and published in this repository's [`overlays` release](https://github.com/ianrastall/offline-maps/releases/tag/overlays). View > Flood zones and View > Public lands turn them on and off. US regions only.
- **Downloads and updates:** queued and run one at a time. Installed maps never expire. Map > Check for updates (or Check for updates in Map > Regions) asks the servers whether anything newer exists, without downloading, and lists what is newer with dates and sizes; nothing is downloaded until you choose. Geofabrik rebuilds its files daily, so a newer map usually adds only recent edits.
- **Search:** finds places, streets, landmarks, transit and water offline, across all installed regions, with or without accents and special letters ("queretaro" finds Querétaro, "lodz" Łódź, "muenchen" or "munchen" München, "kobenhavn" København), with common street abbreviations in English, Spanish, French, German, Dutch, Polish, Czech, Italian and Portuguese (St, Ave, Av., Calz., boul., Str., ul., nám., p.za), German and Dutch compound streets written short or in full ("hauptstr" finds Hauptstraße), and stations and services by their name in 17 European languages ("tankstelle", "apteka", "ziekenhuis"). House numbers may come before or after the street ("Hauptstraße 5"). An optional second download adds fuel stations, charging stations, house-number addresses and outdoor places (shelters, drinking water, campgrounds, public toilets), read from Geofabrik's raw extract; outdoor places are also drawn on the map (View > Outdoor places).
- **Saved places:** kept apart from map packages, so they survive map updates, app upgrades and uninstalling.
- **Not included:** routing, and current conditions such as road closures or active flooding. Flood zones are FEMA's mapped hazard areas, not forecasts.

## Data and licences

The app's own code is in the public domain under [The Unlicense](LICENSE). Bundled components keep their own licences (MapLibre GL JS and maplibre-contour BSD-3-Clause, VersaTiles style MIT, sprites CC0, Noto Sans OFL, Rust crates MIT/Apache-2.0); see [THIRD-PARTY-NOTICES.txt](assets/notices/THIRD-PARTY-NOTICES.txt).

The built-in overview is made from [Natural Earth](https://www.naturalearthdata.com/) (public domain) by `tools/overview/build_overview.py`. Other map data is not part of the program. You download it yourself, under its own terms:

- Maps, stations and addresses: Geofabrik, © OpenStreetMap contributors, [ODbL 1.0](https://www.openstreetmap.org/copyright). Stations and addresses are downloaded already prepared from this repository's `extras` release where one is published, and read from Geofabrik's raw extract otherwise.
- Terrain: [Mapterhorn](https://mapterhorn.com/attribution), made from open elevation data that differs by region: the USGS in the United States, national surveys in Japan, Canada, New Zealand and most of Europe, and Copernicus GLO-30 elsewhere. The credits line on the map and Map > Map information name the producers and licences of installed terrain; `THIRD-PARTY-NOTICES.txt` lists them all.
- Flood zones: FEMA National Flood Hazard Layer; public lands: USGS PAD-US. Both are US Government works in the public domain. The flood data's effective date is shown in Map > Regions.

Status (16 November 2026): version 0.18.1, with a built-in world overview and 540 regions: the US (Alaska included), Canada and Mexico by state, and 440 regions in 190 other countries and territories. All 52 US regions' flood zones and public lands are published. A WinUI 3/C# app over a Rust core. On 11 November 2026 the installed app passed the offline acceptance test: with no network, it showed the Illinois map and found places, addresses, stations, outdoor places and saved places ([validation](docs/validation.md#offline-acceptance-test)).

Android [0.3.0 preview](https://github.com/ianrastall/offline-maps/releases/tag/android-v0.3.0) (17 November 2026): a separate phone app downloads regions directly or imports desktop regions over USB, with foreground satellite GPS and offline search. Saved places now have manual coordinates, editable names/notes, filtering, confirmed removal, and portable JSON backup/import with validation and duplicate skipping. Desktop exchange uses this repository's checked command-line helper. The Galaxy S26 FE passed signed upgrades with maps and original saved-place entries retained, direct DC downloads of all four layer types, offline address/station search/rendering and saved-place backup exchange. Wi-Fi only is the default; keep the app open while downloading, then Resume after a pause. Earlier warm offline GPS verification followed an initial online acquisition. See [Android setup and verification](docs/android-app.md), including the remaining cold-start and field checks. The Windows installer remains the normal desktop release.

Planned: Geofabrik's region maps republished in this repository's releases (processed stations and addresses are published since 0.18.0) and further desktop-to-phone transfer support. See the [North America and Android plan](docs/north-america-and-android.md) and [worldwide plan](docs/worldwide.md).

- [Project brief](docs/project-brief.md)
- [North America and Android plan](docs/north-america-and-android.md)
- [Worldwide plan](docs/worldwide.md) (proposed 14 November 2026)
- [Sources and technical decisions](docs/sources-and-architecture.md)
- [Renderer proof and decision](docs/renderer-proof.md)
- [Android renderer proof](docs/android-renderer-proof.md) (15 November 2026)
- [Implementation stages and verification](docs/validation.md)
- [Jupiter conformance](docs/jupiter-exceptions.md)

## Layout

| Path | Contents |
| --- | --- |
| `crates/offline-maps-core` | Rust domain core: region catalog, packages, downloads, store, tile merging, search, extras, terrain (PMTiles index reader), saved places |
| `crates/offline-maps-ffi` | C ABI facade (`offline_maps_ffi.dll`, ABI version 1) for the WinUI shell |
| `crates/offline-maps-dev` | Development command-line tools (not shipped) |
| `proto/offline_maps.proto` | Messages between the shell and the core |
| `src/OfflineMaps.Interop` | Managed bindings (`MapCore`) with owned handles |
| `src/OfflineMaps.App` | WinUI 3 app |
| `web/` | The map page hosted in WebView2 |
| `tests/OfflineMaps.Interop.Tests` | Managed/native boundary tests |
| `build/` | Release script, native build target, installer script |
| `tools/regions-build/` | Map files for regions Geofabrik does not publish on their own (Mexican states, Canada, Alaska), published in the `regions` release |
| `tools/acceptance/` | Scripted check of the installed app (map, search, stations, addresses, saved places), with or without a network |
| `tools/layers/` | Overlay build (flood zones, public lands): Python with GDAL wheels and Planetiler; run by the maintainer, not part of the app |

## Build and release

Windows 11 x64; Rust 1.99.1, .NET SDK 10.0.114 and Inno Setup 6. The shared style library [Jupiter.UI](https://github.com/ianrastall/jupiter-ui) must be cloned beside this repository:

```powershell
git clone https://github.com/ianrastall/offline-maps
git clone https://github.com/ianrastall/jupiter-ui
cd offline-maps
```

One-time prerequisites:

- MapLibre, maplibre-contour, mapbox-gl-rtl-text, sprites and glyphs (Node 24; pinned hashes): `cd proof/renderer; npm ci; node fetch-assets.mjs`.
- The signed WebView2 offline runtime installer at `third_party/webview2/MicrosoftEdgeWebView2RuntimeInstallerX64.exe` (the Evergreen Standalone x64 installer). It is not committed.

Release (checks, Rust and interop tests, publish, installer to `dist/`):

```powershell
pwsh build/build.ps1
```

Development checks:

```powershell
cargo test --workspace --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo fmt --all -- --check
dotnet test tests/OfflineMaps.Interop.Tests -c Release
dotnet build src/OfflineMaps.App -c Release
```

The app's data lives in `%LOCALAPPDATA%\Jupiter\OfflineMaps`:

- `settings.json`, the log and the WebView2 profile;
- `Maps\` for map packages, stations and addresses, and saved places, unless another map folder is chosen in Settings > Map folder > Change. Changing it offers to move the maps and saved places there (renamed on the same drive, copied and then removed on another; cancelling or a failure leaves them where they were) or to use the new folder as it is. Only the app's own region folders and saved-places files are moved; other files in either folder are left alone.

For testing, these command-line options and environment variables change where data is kept and downloaded from:

- `--store DIR` and `--settings DIR` use other folders.
- `OFFLINE_MAPS_PACKAGE_URL` and `OFFLINE_MAPS_EXTRAS_URL` download from another server, such as the dev server below.
- `OFFLINE_MAPS_OVERLAYS_URL` downloads flood zones and public lands from another address (the folder holding `<region>-overlays.mbtiles`).

## Development tools

```powershell
node tools/build-styles.mjs
python tools/third-party-notices.py
cargo run --release -p offline-maps-dev -- inspect data/sources/illinois-shortbread-1.0.mbtiles
cargo run --release -p offline-maps-dev -- serve data/sources/illinois-shortbread-1.0.mbtiles [--extras FILE] [--source-pbf FILE]
cargo run --release -p offline-maps-dev -- probe
cargo run --release -p offline-maps-dev -- download data/store [--url URL] [--stop-after BYTES]
cargo run --release -p offline-maps-dev -- download-extras data/store [--url URL] [--stop-after BYTES]
cargo run --release -p offline-maps-dev -- search data/store "main st clinton" [--near LAT,LON]
cargo run --release -p offline-maps-dev -- extract data/sources/illinois-latest.osm.pbf data/illinois-extras.sqlite
cargo run --release -p offline-maps-dev -- terrain-size <west> <south> <east> <north>
cargo run -p offline-maps-dev -- terrain-sources crates/offline-maps-core/src/terrain_sources_data.rs assets/notices/terrain-sources.txt
python -I tools/extract-boxes.py data/catalog/index-v1.json data/catalog/extract-boxes.txt
```

- `terrain-sources` reads Mapterhorn's coverage index (1.6 MB) and writes whose elevation data each catalog region's terrain is made from: the core's table and the list in the third-party notices. Run it after the catalog or Mapterhorn's sources change, then `cargo fmt` and `python tools/third-party-notices.py`.
- `probe` sends one HEAD request. `terrain-size` and `tools/extract-boxes.py` measure inputs of `tools/make-regions.py` (terrain bytes from Mapterhorn's index; each raw extract's header box from its first 64 KB).
- `download` and `download-extras` without `--url` download from Geofabrik (about 363 MB each). The dev server serves local copies at `http://127.0.0.1:8765/source/package.mbtiles` and, with `--source-pbf`, at `/source/extract.osm.pbf`.
- The dev server's browser page is at http://127.0.0.1:8765/.
- Map data goes under `data/`, which is not committed.
