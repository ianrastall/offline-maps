using Microsoft.UI.Xaml;
using OfflineMaps.Interop;
using OfflineMaps.Interop.Messages;

namespace OfflineMaps.App;

/// <summary>
/// Changing the map folder (Settings > Map folder). The core moves its own maps and saved places and rolls back
/// on failure or cancel; the shell records the new folder before the originals are removed, then reopens the
/// core on the new folder.
/// </summary>
public sealed partial class MainWindow
{
    private string? _movingFrom;
    private MapCore? _overviewCore;
    private bool _openingMapFolder;

    /// <summary>Raised after the map folder changes.</summary>
    internal event Action? MapFolderChanged;

    /// <summary>The core is open, so its maps can be moved.</summary>
    internal bool HasCore => _core is not null;

    private async void RetryMapFolder_Click(object sender, RoutedEventArgs e) =>
        await OpenConfiguredFolderAsync();

    private async Task OpenConfiguredFolderAsync()
    {
        if (_openingMapFolder || _closing || _core is not null)
        {
            return;
        }
        _openingMapFolder = true;
        try
        {
            var opened = await TryOpenFolderAsync(AppPaths.StoreDir, requireExisting:
                AppPaths.StoreOverride is null && _settings.MapFolder is not null);
            if (_closing)
            {
                opened?.Dispose();
                return;
            }
            if (opened is not null)
            {
                await ActivateFolderAsync(opened);
            }
            else
            {
                if (_overviewCore is null && AppPaths.HasOverview)
                {
                    try
                    {
                        var cache = Path.Combine(AppPaths.SettingsDir, "OverviewCache");
                        var overview = await Task.Run(() => MapCore.Open(cache, overviewPath: AppPaths.OverviewPath));
                        if (_closing)
                        {
                            overview.Dispose();
                            return;
                        }
                        _overviewCore = overview;
                    }
                    catch (MapCoreException ex)
                    {
                        AppLog.Write($"The world overview could not start: {ex.Message}");
                    }
                }
                await RefreshStatusAsync();
                if (!_closing && _overviewCore is not null && MapView.CoreWebView2 is null)
                {
                    await StartMapViewAsync();
                }
            }
        }
        finally
        {
            _openingMapFolder = false;
        }
        await PrepareFolderSearchAsync();
    }

    /// <summary>
    /// Makes <paramref name="folder"/> the map folder. With <paramref name="move"/>, the maps and saved places
    /// move there first; if that fails or is cancelled they stay where they are and the folder is unchanged.
    /// </summary>
    internal async Task ChangeMapFolderAsync(string folder, bool move)
    {
        if (IsBusy || _closing || AppPaths.StoreOverride is not null)
        {
            return;
        }
        if (_exchangingPlaces)
        {
            ShowError("The map folder cannot be changed", "Finish the saved-place backup or import first.");
            return;
        }
        string from = AppPaths.StoreDir;
        string done = $"Map folder changed to {folder}";
        string? leftover = null;
        if (move && _core is { } core)
        {
            _movingFrom = from;
            string? message;
            try
            {
                message = await RunAsync(null, $"Moving the maps to {folder}", async () =>
                {
                    var result = await Task.Run(() => core.MoveStore(folder));
                    // Recorded before the originals are removed, so the maps are never left only in a
                    // folder the settings do not name.
                    try
                    {
                        UseFolder(folder);
                    }
                    catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                    {
                        // The core has completed the move. Use that location for this session,
                        // and keep any cross-volume originals until the setting can be saved.
                        AppPaths.StoreDir = folder;
                        _settings.MapFolder = AppPaths.SameFolder(folder, AppPaths.DefaultStoreDir) ? null : folder;
                        leftover = $"The maps are at {folder}. The new folder setting could not be saved. {ex.Message}";
                    }
                    if (result.Copied && leftover is null)
                    {
                        UpdateBar.Message = $"Removing the originals from {from}";
                        try
                        {
                            await Task.Run(core.RemoveMovedSource);
                        }
                        catch (MapCoreException ex)
                        {
                            AppLog.Write($"Removing the moved maps failed: {ex.Message}");
                            leftover = ex.Message;
                        }
                    }
                    return result.Bytes == 0 ? done : $"Moved the maps to {folder}";
                }, "The maps could not be moved");
            }
            finally
            {
                _movingFrom = null;
            }
            if (message is null)
            {
                await FinishAsync($"Cancelled. The maps are still in {from}.");
                return;
            }
            done = message;
            _core = null;
            core.Dispose();
            await OpenConfiguredFolderAsync();
        }
        else
        {
            _openingMapFolder = true;
            try
            {
                var opened = await TryOpenFolderAsync(folder, requireExisting: true);
                if (opened is null)
                {
                    return;
                }
                if (_closing)
                {
                    opened.Dispose();
                    return;
                }
                try
                {
                    UseFolder(folder);
                }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                {
                    opened.Dispose();
                    ShowError("The map folder setting could not be saved", ex.Message);
                    return;
                }
                await ActivateFolderAsync(opened);
            }
            finally
            {
                _openingMapFolder = false;
            }
        }
        if (_closing || _core is null)
        {
            return;
        }
        await FinishAsync(done);
        ShowPlaces();
        if (leftover is not null)
        {
            ShowError("The map move needs attention", leftover);
        }
        MapFolderChanged?.Invoke();
        await PrepareFolderSearchAsync();
    }

    private void UseFolder(string folder)
    {
        string? previous = _settings.MapFolder;
        _settings.MapFolder = AppPaths.SameFolder(folder, AppPaths.DefaultStoreDir) ? null : folder;
        try
        {
            _settings.Save();
        }
        catch
        {
            _settings.MapFolder = previous;
            throw;
        }
        AppPaths.StoreDir = folder;
        AppLog.Write($"Map folder changed to {folder}");
    }

    private async Task<MapCore?> TryOpenFolderAsync(string folder, bool requireExisting)
    {
        try
        {
            return await Task.Run(() =>
            {
                if (requireExisting && !Directory.Exists(folder))
                {
                    throw new MapCoreException(ErrorKind.Storage, "The folder is unavailable.");
                }
                return MapCore.Open(folder, AppPaths.PackageUrl, AppPaths.ExtrasUrl, AppPaths.OverlaysUrl, AppPaths.OverviewPath, AppPaths.BundlesUrl);
            });
        }
        catch (MapCoreException ex)
        {
            AppLog.Write($"The map core could not open {folder}: {ex.Message}");
            if (!_closing)
            {
                if (_core is null)
                {
                    ShowError("Map folder unavailable", $"Reconnect the drive for {AppPaths.StoreDir} and try again.", "Retry map folder", RetryMapFolder_Click);
                    ErrorBar.IsClosable = false;
                    StatusText.Text = "Map folder unavailable";
                    MapMessage.Text = ex.Message;
                }
                else
                {
                    ShowError("The map folder could not be opened", $"{folder}. {ex.Message}");
                }
            }
            return null;
        }
    }

    private async Task ActivateFolderAsync(MapCore opened)
    {
        _core?.Dispose();
        _core = opened;
        _overviewCore?.Dispose();
        _overviewCore = null;
        ErrorBar.IsOpen = false;
        _searchGeneration++;
        _checks.Clear();
        _checkedAt.Clear();
        if (_catalog.Count == 0)
        {
            try
            {
                _catalog = await Task.Run(opened.Catalog);
            }
            catch (MapCoreException ex)
            {
                AppLog.Write($"The region list could not be read: {ex.Message}");
            }
        }
        if (_closing)
        {
            return;
        }
        // Reload tiles when switching from the overview or another map folder.
        if (_shownMap is not null)
        {
            _shownMap = "";
        }
        await RefreshStatusAsync();
        ShowPlaces();
        if (!_closing && MapView.CoreWebView2 is null)
        {
            await StartMapViewAsync();
        }
    }

    private async Task PrepareFolderSearchAsync()
    {
        if (!_closing && _status is { SearchReady: false } status && status.Regions.Any(r => r.Map is not null))
        {
            await PrepareSearchAsync();
        }
    }
}
