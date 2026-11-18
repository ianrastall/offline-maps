# Desktop work handoff

Prepared 17 November 2026 for a fresh Codex session in `D:\jupiter\offline-maps`.

## Start here

Ian has explicitly pivoted from Android to Windows desktop work. Review and shore up actual problems, add **PNG snapshots of the visible map**, and prioritize publishing usable releases. Complete a small tested desktop milestone rather than letting a broad review delay delivery. Ian is concerned about possible infrastructure disruption; this is the motivation for practical offline readiness, not evidence of a particular attack or deadline.

This session prepared the handoff only. No desktop fixes or snapshot feature have been implemented, and no new desktop build or installed-app test was run. Repository state at the start of this handoff: clean `main`, synchronized with `origin/main`, commit `6c4f71f77dc5ae47da4c6b361547292bd32da0bb`. Fetch/check again before editing; preserve any subsequent local work.

Suggested first prompt for the new session:

> Continue the desktop milestone in docs/desktop-handoff.md. Review and fix blocking problems, implement PNG snapshots of the visible map, verify the installed Windows app, and publish the next tested desktop release. Work on main and push milestones without asking.

## Authorization and delivery

- Read `D:\jupiter\AGENTS.md`, `D:\jupiter\jupiter-plan.md` and the applicable `_standards` documents. Desktop UI is WinUI 3/C# over Rust, importing sibling `D:\jupiter\jupiter-ui` and its Jupiter.UI style checks. Never use NumberBox.
- Ian explicitly authorizes commits, pulls, pushes and release publication without repeated permission requests. Work directly on **main**; no branches, worktrees or PRs unless requested. Commit/push useful milestones and the final release record.
- A desktop GUI milestone requires an **installer**, installation/upgrade on this machine, the app's named Start Menu entry and verified launch. A portable publish folder is insufficient. Preserve maps, settings and saved places. Publication is part of completion, not an optional follow-up.
- Do not spawn agents unless Ian or applicable instructions explicitly request delegation. Read the computer-use skill before using native UI tools if that becomes necessary.
- Do not disable the user's desktop networking or interrupt unrelated work without coordination. Local/offline test controls and a separate app profile are preferable where sufficient; report what was actually tested.

## Current releases and verification

- Latest Windows release: **[v0.18.1](https://github.com/ianrastall/offline-maps/releases/tag/v0.18.1)**, published 16 November, source `434a34e`. Its recorded installed upgrade/Start launch and data preservation are in `docs/validation.md`, section 0.18.1. Windows version remains `0.18.1` in `Directory.Build.props` and the Cargo workspace. Locate the actual registered install/Start shortcut; older app names/install folders may persist through renames.
- Android **[0.5.0 preview](https://github.com/ianrastall/offline-maps/releases/tag/android-v0.5.0)** is complete, published and verified installed on the Galaxy S26 FE. Release source `23cc4a2b77f1f55313874fbbd4072d16dcb004c8`; final publication docs are `6c4f71f`. No Android work is required for this desktop milestone. Android tags are separate prereleases and leave Windows as latest.
- Latest shared-code verification, during Android 0.5.0: **120 Rust workspace tests, 11 Windows interop tests, formatting and Clippy with warnings denied passed**. Android debug/release/lint and GPS marker checks passed. This does **not** mean the current shared core has been packaged and manually accepted in a new Windows installer.
- Since Windows 0.18.1, shared changes include saved-place backup/merge support and journaled transfer replacement/recovery, plus conservative map pruning when the active pointer is unreadable. Review the Windows integration and rebuild/retest it. Preserve Android behavior when changing shared `web/` or Rust code.
- Windows has local map/search, saved places, region downloads/updates/resume, prepared stations/addresses/outdoor places, terrain/contours, FEMA flood zones and public lands, a world overview and 540 catalog regions. No routing or live conditions. `readme.md` and `docs/validation.md` carry details/history.

## Focused review before the feature

Reproduce issues before calling them defects. Prioritize startup/open-store failures, blank/stale maps, data preservation, search, download cancellation/resume and failures that block snapshots. Fix and verify blockers; record lower-priority findings without turning this into an indefinite audit.

Concrete source observations from this handoff:

1. **No product snapshot command exists.** `tools/acceptance/cdp.mjs shot` captures a WebView page through an explicitly enabled local DevTools port for acceptance tests only. Do not require a DevTools port or external screenshot utility in the shipped feature.
2. **Page readiness differs from rendering readiness.** `MainWindow.Map.cs` sets `_pageReady` on the page's `ready` message, before map tiles finish drawing. `web/map.js` currently posts `idle` only once after initial map creation; the Windows host does not handle it. Snapshot logic needs a bounded, current-view readiness strategy, including after pan/zoom/style/layer changes. This is a feature integration concern, not a reproduced blank-snapshot bug.
3. **WebView failure recovery deserves a focused check.** `StartMapViewAsync` handles `ProcessFailed` by clearing readiness, hiding the map and calling `web.Reload()` for every process-failure kind. Review appropriate recovery for the actual failure kinds, including browser-process exit, and guard asynchronous capture against reload/close. No failure was injected in this handoff.
4. **Known wording gap** recorded under 0.18.0 in `docs/validation.md`: Regions' source line describes the available prepared download even for a locally processed installed extras copy. Inspect `RegionsWindow.xaml.cs` around `SourceText` and distinguish download provenance from installed provenance if fixing it in this milestone.
5. Existing backlog in `docs/validation.md#next-stages`: accessibility/keyboard order, screen reader names, high contrast, text scaling, light theme, mixed DPI, and a clean Windows 11 install. Check the affected export flow now; don't claim full coverage without doing it.

## Visible-map PNG feature

Use the following as the intended scope; routine implementation choices do not need another user question.

- Add a discoverable native command, suggested **File > Save map image…**, with a PNG Save dialog and sensible filename. Export the **current visible map viewport**, including current labels, overlays, selected visible markers/popups, scale and attribution. Exclude the native menu, inspector pane, status bar and desktop. No arbitrary larger-area export, PDF, printing or Android UI is needed now.
- Preserve visible OpenStreetMap/Geofabrik and any applicable terrain/overlay attribution. The page already uses a noncompact MapLibre attribution control and a scale. Capture the rendered page surface so DOM attribution/markers survive; a bare WebGL canvas export would omit them. Make sure credits remain legible at narrow widths.
- Preferred approach to evaluate: WebView2's native preview capture into a PNG stream, using a window-owned Save picker consistent with the installed Windows App SDK. Confirm the exact supported API/stream types from local SDK metadata and official documentation before coding. The current folder picker uses `Microsoft.Windows.Storage.Pickers` with `AppWindow.Id` in `SettingsWindow.xaml.cs`.
- Define capture timing so the PNG matches the view the user requested. Wait for the requested view to finish loading with a bounded timeout, prevent duplicate exports, and avoid capturing dialog/menu artifacts. Handle startup, loading, empty overview, resize, renderer failure and window closure without hanging or falsely reporting success. Do not add a permanent WebGL `preserveDrawingBuffer` cost merely for export unless a demonstrated requirement justifies it.
- Saving must work without internet. Cancel should leave no file. Handle invalid/unwritable/full destinations and overwrite safely, without truncating an existing file before a complete image is available. Show concise native success/error feedback. Do not alter the map position, selected place or settings merely to capture it.
- If adding a shortcut (suggested Ctrl+Shift+S), wire native accelerators **and** map-focus forwarding in `web/map.js` and `MainWindow.Map.cs::HandleMapKey`. Preserve Android/shared shortcut behavior; update `docs/jupiter-exceptions.md` if its shortcut list changes.

Main implementation locations:

| File | Responsibility |
| --- | --- |
| `src/OfflineMaps.App/MainWindow.xaml` | Native File menu, accelerators, map viewport |
| `src/OfflineMaps.App/MainWindow.Map.cs` | WebView initialization, messages, readiness, recovery, map-focus shortcuts |
| `src/OfflineMaps.App/MainWindow.xaml.cs` | Window lifecycle, errors, core/status and command state |
| `src/OfflineMaps.App/SettingsWindow.xaml.cs` | Existing window-owned picker pattern |
| `web/map.js`, `web/map.css` | MapLibre, attribution, scale, page messages, shared Android renderer |
| `tools/acceptance/offline-check.ps1`, `cdp.mjs` | Existing desktop acceptance scaffolding; inspect side effects before running |
| `build/build.ps1`, `build/installer.iss` | Checks, native publish, installer, upgrade identity |

## Build environment and acceptance

Configured tools are in `D:\lang`; `. D:\lang\Enter-Lang.ps1` establishes Rust/Android tools. **The first `dotnet` on PATH is the older D:\lang copy**, which failed the pinned SDK requirement during Android milestone interop testing. `global.json` requests 10.0.114 with roll-forward; `C:\Program Files\dotnet\dotnet.exe` has the compatible 10.0.115 installed. Use a fresh `pwsh -NoProfile` process, clear `DOTNET_ROOT`, and put `C:\Program Files\dotnet` first on that process's PATH before invoking the normal build. Do not repurpose `$HOME` or modify the user's global environment.

`build/build.ps1` runs Rust formatting/Clippy/tests, interop tests, native publish/Jupiter style validation and Inno Setup. It verifies the Microsoft-signed WebView2 offline runtime installer and bundled assets. The runtime installer and sibling Jupiter.UI targets were present at handoff. It recreates only `dist/OfflineMaps`; respect Windows path-verification rules before any cleanup. Version fields in `Cargo.toml` and `Directory.Build.props` must agree. A feature release such as **0.19.0** is a reasonable next version; inspect current state before assigning it. Keep Android's independent 0.5.0 version unchanged.

Verify once at the appropriate completed milestone:

1. Run normal relevant checks/build, including the full release command for delivery; fix failures. Add focused tests only for meaningful behavior, such as readiness/error handling, not tests that mirror trivial menu plumbing.
2. Use a separate settings profile (`--settings <directory>`) and copied/isolated test data where appropriate. Real user maps/settings/saved places must remain intact. Existing data may be on a custom drive; derive it from the app/settings locally without printing private places.
3. Inspect actual exported PNGs: valid signature/dimensions, populated map rather than black/blank WebGL, roads/labels, all selected layers, readable credits/scale and selected visible markers; no native chrome. Cover dark/light, a resized/high-DPI viewport, overview without detailed regions, after pan/zoom/layer changes, cancellation and a failed save. Check snapshot command/shortcut with map and native focus.
4. Verify local/offline map/search and snapshot operation and recovery from relevant renderer/loading failures. Never describe simulated network rejection as a physical networking-disabled test. Do not unexpectedly disconnect this desktop session.
5. Build installer, install/upgrade, verify Start Menu launch and the actual installed snapshot command/output. Verify original settings/saved places/maps are preserved. Document any clean-machine/DPI/accessibility checks that remain untested.
6. **Publish the tested Windows release promptly**, with installer and SHA-256, concrete notes and honest limits. Verify GitHub asset size/digest and tag target, update README/`docs/validation.md`, commit/push the publication record and finish with clean synchronized main. Do not stop at “ready to publish.”

## Privacy and stopping point

`artifacts/`, `data/` and `dist/` are ignored. Android acceptance contains private saved-place JSON and phone coordinates; never attach or publish them. Use public locations for screenshots destined for Git/release notes. Keep the persistent Android signing key and DPAPI credentials outside Git. Phone work is finished and its Wi-Fi was restored; no USB troubleshooting is needed for desktop work.

Finish this first desktop milestone with fixes, snapshots and a published installer. Leave broader routing, Android field/battery/GPS work, mass dataset rebuilding and a redesign for subsequent scope decisions.
