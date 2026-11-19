using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Windows.Storage.Pickers;
using OfflineMaps.Interop.Messages;

namespace OfflineMaps.App;

public sealed partial class MainWindow
{
    private bool _choosingLibrary;
    private bool _exportingLibrary;

    private bool CanExportLibrary => !_closing && !_choosingLibrary && !IsBusy && _core is not null &&
        _status is { } status && status.Regions.Any(region => region.Map is not null && region.SearchReady);

    private async void ExportLibrary_Click(object sender, RoutedEventArgs e)
    {
        if (!CanExportLibrary || _status is not { } status || _core is not { } core)
        {
            return;
        }
        _choosingLibrary = true;
        UpdateMapCommands();
        try
        {
            var regions = new ListView
            {
                ItemsSource = status.Regions.Where(region => region.Map is not null && region.SearchReady).ToArray(),
                DisplayMemberPath = nameof(RegionStatus.Name),
                SelectionMode = ListViewSelectionMode.Multiple,
                MaxHeight = 340,
            };
            AutomationProperties.SetName(regions, "Installed regions to export");
            var dialog = new ContentDialog
            {
                Title = "Export maps for Android",
                Content = regions,
                PrimaryButtonText = "Choose folder",
                IsPrimaryButtonEnabled = false,
                CloseButtonText = "Cancel",
                DefaultButton = ContentDialogButton.Primary,
            };
            regions.SelectionChanged += (_, _) => dialog.IsPrimaryButtonEnabled = regions.SelectedItems.Count > 0;
            if (await ShowDialogAsync(dialog) != ContentDialogResult.Primary || _closing)
            {
                return;
            }
            string[] ids = regions.SelectedItems.Cast<RegionStatus>().Select(region => region.Id).ToArray();
            var picker = new FolderPicker(AppWindow.Id)
            {
                Title = "Choose where to save the map library",
                SuggestedStartLocation = PickerLocationId.DocumentsLibrary,
            };
            if (await picker.PickSingleFolderAsync() is not { } folder || _closing)
            {
                return;
            }
            if (IsBusy || _core != core)
            {
                ShowError("The library could not be exported", "Wait for the map operation to finish and try again.");
                return;
            }
            await ExportLibraryAsync(ids, folder.Path);
        }
        catch (Exception ex)
        {
            if (!_closing)
            {
                AppLog.Write($"Map library export failed: {ex.Message}");
                ShowError("The library could not be exported", ex.Message);
            }
        }
        finally
        {
            _choosingLibrary = false;
            UpdateMapCommands();
        }
    }

    private async Task ExportLibraryAsync(string[] ids, string destination)
    {
        var core = _core!;
        _exportingLibrary = true;
        CancelUpdateItem.Text = "Cancel export";
        string? path;
        try
        {
            path = await RunAsync(null, "Exporting maps for Android", () => Task.Run(() =>
                core.ExportLibrary(destination, ids)), "The library could not be exported");
        }
        finally
        {
            _exportingLibrary = false;
            CancelUpdateItem.Text = "Cancel download";
        }
        if (_closing)
        {
            return;
        }
        await FinishAsync(path is null ? "Map export cancelled" : $"Exported {ids.Length} {(ids.Length == 1 ? "map" : "maps")}");
        if (path is not null)
        {
            await ShowDialogAsync(new ContentDialog
            {
                Title = "Map library ready",
                Content = Text($"{path}\n\nCopy this folder to Android. Open Maps > Restore map library and select the copied folder."),
                CloseButtonText = "Done",
            });
        }
    }
}
