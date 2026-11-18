# Jupiter conformance

Target: canonical WinUI 3/C# + Rust core architecture and Jupiter style standard 1.1.

The app imports Jupiter.UI (`build/Jupiter.UI.targets`): shared resources, the accent and theme services, the Settings > Appearance controls, and the style check in its default error mode. The check reports 0 violations in the app's XAML. No NumberBox is used. The installer gives a top-level Start Menu entry named Offline Maps, registers an uninstaller, and leaves user data in `%LOCALAPPDATA%\Jupiter\OfflineMaps` through upgrades and uninstalls.

## Exceptions

1. **Embedded map surface (selected 8 November 2026).** The map itself is rendered by MapLibre GL JS inside WebView2, drawing local Shortbread vector tiles with bundled style, glyphs and sprites. This is a content-rendering exception to native-control defaults, following the Lichess Local precedent. Basis: [renderer proof](renderer-proof.md). The XAML marks it at the `WebView2` element.
   - The WinUI shell owns the menu bar, search pane, info bars, status bar, dialogs, Settings, window lifecycle, installer identity and the shared Jupiter.UI theme and accent controls.
   - The map's own controls (scale, attribution) and markers receive Jupiter.UI theme and accent values at runtime. No separate interface palette is maintained.
   - The map style colors, route shields and the charging-station icon are a content palette (map styling).
   - All web content is local. The page, MapLibre, styles, sprites and glyphs are read from the app's `Web` folder, and tiles and stations come from the Rust core. All of it is answered by the shell's WebView2 request handler for `https://map.local/`.
   - A folder mapping is not used, because requests to a mapped host bypass the handler. Every other origin is refused with 403, navigation away from `map.local` is cancelled, permissions are denied, and the page has a same-origin Content Security Policy.
   - There is no TCP listener.
   - The Rust core remains the authority for packages, validation, search and saved places. The web view only renders and reports map interactions (view changes, context menu, clicked saved places, shortcuts).
2. **Keyboard shortcuts from the map (8 November 2026).** While the map has focus, WebView2 does not pass keys to the window's menu accelerators, so the page forwards the app's shortcuts (Ctrl+F, Ctrl+,, Ctrl+B, Ctrl+D, Ctrl+Shift+C, Ctrl+Shift+S, Ctrl+0, F11) to the shell. Browser accelerator keys are disabled. F6 moves focus to the map canvas from the Windows shell or its embedded page. The canvas shows an outline when it has keyboard focus.
3. **Status bar truncation (8 November 2026).** Long status text (map and extras dates) is trimmed with an ellipsis at narrow window widths, as in Mix2DO. The same facts are in Map > Map information.

## Android edition

Decided 15 November 2026. Jupiter's standard and Jupiter.UI cover Windows desktop apps; there is no Android standard, so the phone app follows Android's own conventions and records its choices here.

- **Stack:** Kotlin with Jetpack Compose for the interface; the Rust core through a JNI bridge (`crates/offline-maps-android`), the same core the Windows app uses.
- **Map surface:** the Windows app's map page (`web/`, MapLibre GL JS) in Android's WebView, unchanged, as on Windows in WebView2. Chosen by Ian over MapLibre Native after the [Android renderer proof](android-renderer-proof.md). The same rules apply as on Windows: everything is answered locally for `https://map.local/` (page files from the APK, tiles and stations from the core), every other address is refused, navigation away is cancelled, and there is no TCP listener. `window.chrome.webview` is provided over a WebMessageListener so the page needs no Android-specific code.
- **Distribution:** an APK on the GitHub releases (stage 6 of the plan). The Windows installer rule does not apply to it.

The application preview lives in `android/` since 17 November 2026, separately from `proof/android`. Its Compose controls use Android's Material 3 dark theme; the map retains the shared content styles. The Android host fixes an initial WebView zero-height layout through its document-start shim and stops GPS following on native map touch-down without consuming the gesture. Its persistent release signing key is outside the repository. See [Android verification](android-app.md).
