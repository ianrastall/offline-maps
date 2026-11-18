using System.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Windows.Storage.Pickers;
using OfflineMaps.Interop;

namespace OfflineMaps.App;

public sealed partial class MainWindow
{
    // Match the portable format's limit in the Rust core before reading a file.
    private const int MaxPlaceBackupBytes = 16 * 1024 * 1024;
    private bool _exchangingPlaces;

    private bool CanExchangePlaces => !_closing && !_exchangingPlaces && _core is not null &&
        _status is { PlacesProblem.Length: 0 };

    private async void ExportPlaces_Click(object sender, RoutedEventArgs e) =>
        await ExchangePlacesAsync("The saved-place backup could not be exported", async core =>
        {
            var picker = new FileSavePicker(AppWindow.Id)
            {
                Title = "Export saved places to a new file",
                SuggestedFileName = $"Offline Maps places {DateTime.Now:yyyy-MM-dd HH-mm-ss}",
                DefaultFileExtension = ".json",
                SuggestedStartLocation = PickerLocationId.DocumentsLibrary,
            };
            picker.FileTypeChoices.Add("Saved-place backup", [".json"]);
            if (await picker.PickSaveFileAsync() is not { } file || _closing)
            {
                return;
            }
            await Task.Run(() =>
            {
                if (File.Exists(file.Path))
                {
                    throw new IOException("Choose a new name for the backup file.");
                }
                WriteExportFile(file.Path, Encoding.UTF8.GetBytes(core.ExportPlaces()), overwrite: false);
            });
            if (!_closing)
            {
                AnnounceStatus($"Saved {Path.GetFileName(file.Path)}");
            }
        });

    private async void ImportPlaces_Click(object sender, RoutedEventArgs e) =>
        await ExchangePlacesAsync("Saved places could not be imported", async core =>
        {
            var picker = new FileOpenPicker(AppWindow.Id)
            {
                Title = "Open a saved-place backup",
                SuggestedStartLocation = PickerLocationId.DocumentsLibrary,
            };
            picker.FileTypeFilter.Add(".json");
            if (await picker.PickSingleFileAsync() is not { } file || _closing)
            {
                return;
            }
            var (backup, preview) = await Task.Run(() =>
            {
                string json = ReadPlaceBackup(file.Path);
                return (json, core.ImportPlaces(json));
            });
            if (_closing)
            {
                return;
            }
            if (preview.Added == 0)
            {
                await ShowDialogAsync(new ContentDialog
                {
                    Title = "No new saved places",
                    Content = Text(preview.Skipped == 0
                        ? "The backup contains no saved places."
                        : $"The backup contains {DuplicateCount(preview.Skipped)}."),
                    CloseButtonText = "Close",
                });
                return;
            }
            var answer = await ShowDialogAsync(new ContentDialog
            {
                Title = "Import saved places?",
                Content = Text($"Add {PlaceCount(preview.Added)} and skip {DuplicateCount(preview.Skipped)}?"),
                PrimaryButtonText = "Import places",
                CloseButtonText = "Cancel",
                DefaultButton = ContentDialogButton.Close,
            });
            if (answer != ContentDialogResult.Primary || _closing)
            {
                return;
            }
            var result = await Task.Run(() => core.ImportPlaces(backup, apply: true));
            if (!_closing)
            {
                await RefreshStatusAsync();
                ShowPlaces();
                AnnounceStatus($"Added {PlaceCount(result.Added)}. Skipped {DuplicateCount(result.Skipped)}.");
            }
        });

    private async Task ExchangePlacesAsync(string errorTitle, Func<MapCore, Task> exchange)
    {
        if (!CanExchangePlaces || _core is not { } core)
        {
            return;
        }
        _exchangingPlaces = true;
        UpdatePlaceCommands();
        try
        {
            await exchange(core);
        }
        catch (Exception ex)
        {
            if (!_closing)
            {
                ShowError(errorTitle, ex.Message);
            }
        }
        finally
        {
            _exchangingPlaces = false;
            if (!_closing)
            {
                UpdatePlaceCommands();
            }
        }
    }

    private static string ReadPlaceBackup(string path)
    {
        using var input = File.OpenRead(path);
        if (input.Length > MaxPlaceBackupBytes)
        {
            throw new IOException("Saved-place backups are limited to 16 MB.");
        }
        byte[] data = new byte[input.Length];
        input.ReadExactly(data);
        var utf8 = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);
        ReadOnlySpan<byte> bytes = data;
        if (bytes.StartsWith(Encoding.UTF8.Preamble))
        {
            bytes = bytes[Encoding.UTF8.Preamble.Length..];
        }
        return utf8.GetString(bytes);
    }

    private static string PlaceCount(uint count) => count == 1 ? "1 place" : $"{count} places";
    private static string DuplicateCount(uint count) => count == 1 ? "1 exact duplicate" : $"{count} exact duplicates";
}
