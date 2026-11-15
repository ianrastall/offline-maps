# Android renderer proof

15 November 2026 · Stage 6 of the [North America and Android plan](north-america-and-android.md#6-android-foundation). Counterpart of the Windows [renderer proof](renderer-proof.md).

## Question

On Android, should the map be the Windows app's map page (`web/`) in Android's WebView, or MapLibre Native? Both read the same tiles from the Rust core and the same style.

Result: **both work offline with the installed Illinois maps**, on the emulator and on Ian's phone. The WebView shows everything the Windows app shows, contour lines included. MapLibre Native reaches the first finished frame sooner and uses less memory, but has no contour lines and needs the page's runtime layers rewritten in Kotlin. Which to build on is Ian's decision (see [Recommendation](#recommendation)).

## Setup

| Item | Version |
| --- | --- |
| Emulator | Android Emulator 37.2.13, Pixel 7 profile (1080 × 2400, 420 dpi), Android 16 (API 36) Google APIs x86_64 image, host GPU (WHPX); WebView 133.0.6943.137 |
| Phone | Ian's Samsung Galaxy S26 FE (SM-S741U): Android 17 (API 37), One UI 9, Exynos 2500, 8 GB, 1080 × 2340 at 450 dpi; WebView 157.0.8103.38 |
| MapLibre Native | `org.maplibre.gl:android-sdk` 13.7.1 (BSD-2-Clause), OpenGL ES |
| Build | Gradle 9.8.2, Android Gradle plugin 9.5.0 (built-in Kotlin), compile and target SDK 36, minimum SDK 29, NDK 30.0.16248370 |
| Map data | The installed Windows app's Illinois folder (map 14 Nov, stations and addresses, terrain, flood zones and public lands; 902 MB) |

Versions newer than about two weeks were not used.

## What was built

- **`crates/offline-maps-android`**: a JNI bridge (`jni` 0.22.4) over the core's existing `Service`, for the Kotlin class `io.github.ianrastall.offlinemaps.core.NativeCore`: open a map folder, status, map/overview/terrain/overlay tiles, stations, close. Status is returned as the JSON the map page's `init` message carries (the Windows shell builds the same from the protobuf `Status`). Errors and panics become Java `RuntimeException`s.
- **`proof/android`**: a throwaway app with two buttons, WebView and MapLibre Native, kept on the same view. Launch extras (`mode`, `lat`, `lon`, `zoom`, `theme`) make comparisons repeatable. `prepare.ps1` builds the bridge with cargo-ndk and copies the same page files the Windows project copies (91 MB, mostly glyphs). The debug APK is 136 MB with both ABIs.
- **WebView**: the page is unchanged. Requests for `https://map.local/` are answered in `shouldInterceptRequest`, from the APK for page files and from the core for tiles and stations; every other address gets 403 and navigation away is cancelled. A document-start script provides `window.chrome.webview` over a `WebMessageListener`, so `map.js` talks to its host exactly as in WebView2. This works for MapLibre's module worker too.
- **MapLibre Native**: the style file with the page's runtime layers written into it before loading (overview, flood zones and public lands, terrain shading), since the Android SDK cannot add a layer from JSON afterwards. Route shields and the station and outdoor icons are drawn in Kotlin (stretchable shields work). Glyphs and sprites come from `asset://`; tiles are answered for `map.local` in an OkHttp interceptor installed as MapLibre's HTTP client, which refuses every other host.

## Results

First finished frame (the map's first idle), each from a cold start of the app:

| View | Phone, WebView | Phone, Native | Emulator, WebView | Emulator, Native |
| --- | --- | --- | --- | --- |
| Illinois whole, zoom 6 | 1.0 s | 0.6 s | 2.2 s | 1.2 s |
| Chicago Loop, zoom 15 | 1.6 s | 1.2 s | 3.8 s | 3.5 s |
| Bloomington–Normal, zoom 11 | 1.0 s | 0.7 s | 2.7 s | 1.4 s |
| Shawnee Hills, zoom 12.5 | 1.0 s | 0.7 s | 2.5 s | 1.2 s |
| Carbondale, zoom 15 | 1.0 s | 0.7 s | 2.5 s | 1.4 s |

Memory (proportional set size) after the Chicago view:

| | WebView (app + renderer process) | MapLibre Native |
| --- | --- | --- |
| Phone (dark theme) | 320 + 295 MB, about 615 MB | 413 MB |
| Emulator (light theme) | 197 + 262 MB, about 460 MB | 293 MB |

On the phone the views looked as on the emulator (contour lines, shading and the dark theme included), and MapLibre Native logged the same Chicago geometry message. Smoothness while panning and battery use were not measured.

Compared view by view:

- **The same:** roads and colours, water, buildings, transit stations, the overview at low zoom, interstate, US and Illinois shields (stretched to fit), fuel and charging stations, outdoor places (shelter, water, camp, toilets), public lands and flood zones, terrain shading.
- **Contour lines:** WebView only. maplibre-contour computes them in a browser worker; MapLibre Native has no equivalent, so it would need contour tiles made ahead of time or by the core from the terrain tiles.
- **Labels:** the two engines place a few labels differently (in the Chicago Loop Native showed more street names and not the "US 66 Hist" marker; at Bloomington it showed State Farm Park and not the Illinois State University golf course).
- **Dropped geometry:** at the Chicago view MapLibre Native logged "paths outside valid range of coordinate_type" for some features of some tiles: coordinates beyond its 16-bit limit, which it discards and the browser engine draws. Which features are affected was not found; nothing missing was evident at the views checked.
- **Credits and scale:** the page's credits line (OpenStreetMap, Geofabrik, FEMA, USGS, Mapterhorn and the terrain producers in view) and scale bar come with WebView. Native shows MapLibre's logo and an information button instead, and would need both made in Kotlin.
- **Errors:** no page or style errors in either, apart from the Native geometry message above.

## Other findings

- **Copying maps onto the phone.** Files that `adb` writes into the app's folder on shared storage (`Android/data/<package>`) belong to the shell user, and the app cannot read them; `run-as` cannot reach that folder either. The proof keeps maps in the app's private storage and copies them in with `adb push` to `/data/local/tmp` and `run-as … cp`. For people copying maps from the desktop (stage 7), the app needs its own import through Android's file picker.
- **The core on Android.** Opening the folder, SQLite, the file locks, terrain, overlays, the overview and stations all worked from the bridge with no change to the core.
- **Installing on a Samsung phone.** Samsung's Auto Blocker (Settings > Security and privacy) blocks USB debugging and installs from outside the stores; it has to be off to install a test build over USB and can be turned back on afterwards. The installed app keeps working.
- **The emulator is slower than the phone** (about two to three times, as the table shows), so its timings compare the two renderers but do not predict a phone's.

## Recommendation

**WebView for the first Android version.** It already shows everything the Windows app does, with one map page for both editions: features and fixes made once in `web/` reach both, and contour lines need no new work. On Ian's phone it costs about 0.3–0.4 s more to the first frame and about 200 MB more memory of 8 GB. MapLibre Native remains the option if WebView proves too slow to pan or too heavy on the battery in use; it would bring the work listed above (contour tiles, the style layers, credits and scale in Kotlin, and the dropped geometry to investigate).

## Rerun

```powershell
. D:\lang\Enter-Lang.ps1
proof\android\prepare.ps1
cd proof\android; .\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb push G:\Maps\us-il /data/local/tmp/
adb shell "run-as io.github.ianrastall.offlinemaps.proof sh -c 'mkdir -p files/maps && cp -r /data/local/tmp/us-il files/maps/'"
adb shell rm -rf /data/local/tmp/us-il
adb shell am start -n io.github.ianrastall.offlinemaps.proof/.MainActivity --es mode native --ef lat 41.8819 --ef lon -87.6278 --ef zoom 15
```

The emulator device is `om-proof` in `D:\lang\android\user\avd` (`emulator -avd om-proof -gpu host -no-metrics`).
