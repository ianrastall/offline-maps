# Offline Maps

Download detailed road maps for Windows or Android and use them without an internet connection. Both apps include a world overview and offer 540 downloadable regions across six continents. Installed maps, search and saved places work offline without an account.

## Install and download a map

| Platform | Download | First map |
| --- | --- | --- |
| Windows 11 24H2 or later, x64 | [Windows 0.22.0](https://github.com/ianrastall/offline-maps/releases/tag/v0.22.0) | Run `OfflineMaps-0.22.0-win-x64-setup.exe`, then open **Offline Maps** from Start. Choose **Map > Regions…**, select a region and click **Download map**. |
| Android 10 or later, arm64 or x86-64 | [Android 0.11.0 preview](https://github.com/ianrastall/offline-maps/releases/tag/android-v0.11.0) | Open `OfflineMaps-android-0.11.0-release.apk` and install it over the existing release preview. Choose **Maps > Download maps** to download a region. |

To start with prepared maps on Android, unzip the Delaware and D.C. starter library and select the extracted folder in **Maps > Restore map library**. To keep installed maps through a reinstall, set up a shared backup in **Maps > Map backup**. [Starter library ZIP](https://github.com/ianrastall/offline-maps/releases/download/android-v0.9.0/OfflineMaps-Starter-Delaware-DC.zip) · [Backup and restore guide](GUIDE.md#keep-and-back-up-maps-and-saved-places)

Download the areas you’ll need while you’re connected. Wait until each region is ready, then disconnect and check that you can zoom to street level and find a place you expect to use. To prepare several regions on Windows and copy them over USB, use **Map > Export maps for Android…**. [User and developer guide](GUIDE.md#using-offline-maps)

## What you can do offline

- Browse detailed roads and labels across installed regions. The built-in overview provides context around them.
- Search for places, streets, landmarks and transit. Add the optional **stations and addresses** data for house numbers, fuel and charging stations, and outdoor places such as shelters and drinking water.
- View optional terrain shading and contours, plus mapped FEMA flood hazard zones and USGS public lands in supported U.S. regions.
- Save places on either platform and exchange JSON backups between Windows and Android. On Android, show your satellite GPS position while the app is open.
- Save the visible map as a PNG. On Windows, use **File > Save map image…** or **Ctrl+Shift+S**. On Android, use **More > Save map image**.

Routing and live traffic, closures and active flooding are outside the app’s scope. Some Indic and Southeast Asian scripts and Thaana can render with broken letter shapes.

## Build and maintain the project

The Windows app uses WinUI 3 and the Android app uses Kotlin and Jetpack Compose. Both use the Rust map core and render the same local MapLibre page. The [user and developer guide](GUIDE.md#developing-offline-maps) covers the build prerequisites, Windows installer and Android APK commands, package storage and verification.

## Data and licenses

The project’s code is in the public domain under The Unlicense. The built-in overview comes from Natural Earth. Detailed maps and prepared search data derive from OpenStreetMap and are distributed under the ODbL. Terrain comes from Mapterhorn, and the U.S. overlays come from FEMA and USGS. [Project license](LICENSE) · [Data sources](docs/sources-and-architecture.md) · [Third-party notices](assets/notices/THIRD-PARTY-NOTICES.txt)
