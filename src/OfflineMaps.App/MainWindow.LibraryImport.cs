using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Windows.Storage.Pickers;
using OfflineMaps.Interop.Messages;

namespace OfflineMaps.App;

public sealed partial class MainWindow
{
    internal bool IsImportingLibrary { get; private set; }

    /// <summary>A library export or import is running; downloads wait for it.</summary>
    internal bool IsLibraryBusy => IsExportingLibrary || IsImportingLibrary;

    private bool CanImportLibrary => !_closing && !_choosingLibrary && !IsBusy && _core is not null;

    private async void ImportLibrary_Click(object sender, RoutedEventArgs e)
    {
        if (!CanImportLibrary || _core is not { } core)
        {
            return;
        }
        _choosingLibrary = true;
        UpdateMapCommands();
        try
        {
            var picker = new FolderPicker(AppWindow.Id)
            {
                Title = "Choose the map library folder",
                SuggestedStartLocation = PickerLocationId.Downloads,
            };
            if (await picker.PickSingleFolderAsync() is not { } folder || _closing)
            {
                return;
            }
            if (IsBusy || _core != core)
            {
                ShowError("The library could not be imported", "Wait for the map operation to finish and try again.");
                return;
            }
            await ImportLibraryAsync(core, folder.Path);
        }
        catch (Exception ex)
        {
            if (!_closing)
            {
                AppLog.Write($"Map library import failed: {ex.Message}");
                ShowError("The library could not be imported", ex.Message);
            }
        }
        finally
        {
            _choosingLibrary = false;
            UpdateMapCommands();
        }
    }

    private async Task ImportLibraryAsync(Interop.MapCore core, string source)
    {
        IsImportingLibrary = true;
        CancelUpdateItem.Text = "Cancel import";
        ImportLibraryResult? result = null;
        try
        {
            await RunAsync(null, "Importing maps", () => Task.Run(() =>
            {
                result = core.ImportLibrary(source);
                return "";
            }), "The library could not be imported");
        }
        finally
        {
            IsImportingLibrary = false;
            CancelUpdateItem.Text = "Cancel download";
        }
        if (_closing)
        {
            return;
        }
        int added = result?.Added.Count ?? 0;
        await FinishAsync(result is null ? "Map import cancelled" : $"Imported {added} {(added == 1 ? "map" : "maps")}");
        if (result is not null)
        {
            await ShowDialogAsync(new ContentDialog
            {
                Title = "Map library imported",
                Content = Text(ImportSummary(result)),
                CloseButtonText = "Done",
            });
        }
    }

    internal static string ImportSummary(ImportLibraryResult result)
    {
        var lines = new List<string>
        {
            $"Added {Count(result.Added.Count, "map")}.",
        };
        if (result.LayersAdded.Count > 0)
        {
            lines.Add($"Added terrain or other layers to {Count(result.LayersAdded.Count, "installed map")}.");
        }
        if (result.Skipped > 0)
        {
            lines.Add($"{Count((int)result.Skipped, "folder")} skipped because {(result.Skipped == 1 ? "it is" : "they are")} already installed.");
        }
        if (result.NeedsMap.Count > 0)
        {
            lines.Add($"{Count(result.NeedsMap.Count, "terrain folder")} skipped because the map is not installed: {string.Join(", ", result.NeedsMap)}. Import the region's map first.");
        }
        foreach (var failure in result.Failed)
        {
            lines.Add($"{failure.Folder} could not be imported: {failure.Reason}");
        }
        return string.Join("\n\n", lines);

        static string Count(int n, string noun) => $"{n} {noun}{(n == 1 ? "" : "s")}";
    }
}
