using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.Web.WebView2.Core;
using Microsoft.Windows.Storage.Pickers;
using Windows.Storage.Streams;

namespace OfflineMaps.App;

public sealed partial class MainWindow
{
    private bool _savingMapImage;
    private int _snapshotId;
    private TaskCompletionSource<bool>? _snapshotReady;
    private CancellationTokenSource? _mapCapture;

    private bool CanSaveMapImage => !_closing && !_savingMapImage && _pageReady &&
        _shownMap is not null && MapView.Visibility == Visibility.Visible;

    private void UpdateMapImageCommand() => SaveMapImageItem.IsEnabled = CanSaveMapImage;

    private async void SaveMapImage_Click(object sender, RoutedEventArgs e) => await SaveMapImageAsync();

    private async Task SaveMapImageAsync()
    {
        if (!CanSaveMapImage || MapView.CoreWebView2 is not { } web)
        {
            return;
        }

        _savingMapImage = true;
        UpdateMapImageCommand();
        try
        {
            // Capture before showing the picker. Moving or resizing the window while
            // choosing a filename cannot change the image being saved.
            byte[] png = await CaptureMapAsync(web);
            if (_closing)
            {
                return;
            }
            var picker = new FileSavePicker(AppWindow.Id)
            {
                SuggestedFileName = $"Offline Map {DateTime.Now:yyyy-MM-dd HH-mm-ss}",
                DefaultFileExtension = ".png",
                SuggestedStartLocation = PickerLocationId.PicturesLibrary,
            };
            picker.FileTypeChoices.Add("PNG image", [".png"]);
            if (await picker.PickSaveFileAsync() is { } file && !_closing)
            {
                await Task.Run(() => WriteExportFile(file.Path, png, overwrite: true));
                if (!_closing)
                {
                    StatusText.Text = $"Saved {Path.GetFileName(file.Path)}";
                }
            }
        }
        catch (Exception ex)
        {
            AppLog.Write($"Map image could not be saved: {ex}");
            if (!_closing)
            {
                string message = ex is OperationCanceledException or TimeoutException
                    ? "The map did not finish drawing. Try again once the map is visible and still."
                    : ex.Message;
                ShowError("The map image could not be saved", message);
            }
        }
        finally
        {
            _savingMapImage = false;
            if (!_closing)
            {
                UpdateMapImageCommand();
            }
        }
    }

    private async Task<byte[]> CaptureMapAsync(CoreWebView2 web)
    {
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(12));
        _mapCapture = deadline;
        _snapshotReady = new(TaskCreationOptions.RunContinuationsAsynchronously);
        long width = (long)Math.Ceiling(MapView.ActualWidth * Root.XamlRoot.RasterizationScale);
        long height = (long)Math.Ceiling(MapView.ActualHeight * Root.XamlRoot.RasterizationScale);
        try
        {
            Post(new JsonObject { ["kind"] = "snapshot", ["id"] = ++_snapshotId });
            if (!await _snapshotReady.Task.WaitAsync(deadline.Token))
            {
                throw new TimeoutException();
            }
            byte[] png = await ReadMapPreviewAsync(web).WaitAsync(deadline.Token);
            deadline.Token.ThrowIfCancellationRequested();
            double scale = Root.XamlRoot.RasterizationScale;
            if (width != (long)Math.Ceiling(MapView.ActualWidth * scale) ||
                height != (long)Math.Ceiling(MapView.ActualHeight * scale))
            {
                throw new InvalidOperationException("The map was resized. Save the image again at the new size.");
            }
            return png;
        }
        finally
        {
            _snapshotReady = null;
            _mapCapture = null;
        }
    }

    private static async Task<byte[]> ReadMapPreviewAsync(CoreWebView2 web)
    {
        // Keep the stream alive until WebView2 finishes, even if the caller times out.
        using var preview = new InMemoryRandomAccessStream();
        await web.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, preview);
        preview.Seek(0);
        using var input = preview.AsStreamForRead();
        using var png = new MemoryStream();
        await input.CopyToAsync(png);
        if (png.Length < 33)
        {
            throw new IOException("The map view returned an empty image. Try saving the image again.");
        }
        return png.ToArray();
    }

    private static void WriteExportFile(string path, byte[] data, bool overwrite)
    {
        // Write beside the destination so the final rename is on the same volume.
        string temp = Path.Combine(Path.GetDirectoryName(path)!, $".offline-maps-{Guid.NewGuid():N}.tmp");
        var file = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        try
        {
            using (file)
            {
                file.Write(data);
                file.Flush(flushToDisk: true);
            }
            File.Move(temp, path, overwrite);
        }
        catch
        {
            try
            {
                File.Delete(temp);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                AppLog.Write($"Temporary export file could not be removed: {ex.Message}");
            }
            throw;
        }
    }
}
