using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using OfflineMaps.Interop.Messages;

namespace OfflineMaps.App;

public sealed partial class RegionsWindow
{
    private readonly DispatcherTimer _storageTimer = new() { Interval = TimeSpan.FromSeconds(3) };
    private bool _readingStorage;
    private bool _closed;
    private TextBlock? _storageDetails;

    private void StartStoragePolling()
    {
        _storageTimer.Tick += StorageTimer_Tick;
        _storageTimer.Start();
        _ = RefreshStorageAsync();
    }

    private void StopStoragePolling()
    {
        _closed = true;
        _storageTimer.Stop();
        _storageTimer.Tick -= StorageTimer_Tick;
    }

    private async void StorageTimer_Tick(object? sender, object e) => await RefreshStorageAsync();

    private void StorageFlyout_Opened(object sender, object e)
    {
        _storageDetails = DriveDetails;
        DriveDetails.Text = "Reading drive space…";
        _ = RefreshStorageAsync();
    }

    private void StorageFlyout_Closed(object sender, object e)
    {
        if (ReferenceEquals(_storageDetails, DriveDetails))
        {
            _storageDetails = null;
        }
    }

    private async Task RefreshStorageAsync()
    {
        if (_readingStorage || _closed)
        {
            return;
        }
        string folder = AppPaths.StoreDir;
        var details = _storageDetails;
        _readingStorage = true;
        try
        {
            var storage = await Task.Run(() => DownloadStorage.Read(folder, details is not null));
            if (_closed || folder != AppPaths.StoreDir)
            {
                return;
            }
            MapDriveText.Text = $"Map drive {storage.Destination}";
            if (details is not null && ReferenceEquals(details, _storageDetails))
            {
                details.Text = $"{folder}\nMap drive {storage.Destination}";
                if (storage.OtherDrives.Count > 0)
                {
                    details.Text += $"\n\nOther drives\n{string.Join('\n', storage.OtherDrives)}";
                }
            }
        }
        finally
        {
            _readingStorage = false;
        }
    }

    private void RefreshBulkProgress()
    {
        BulkButton.IsEnabled = _all.Count > 0 && !_main.IsBusy;
        BulkProgress.Visibility = _main.BulkStatus.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        BulkSummary.Text = _main.BulkStatus;
        BulkJobText.Text = _main.IsBulkDownloading && _main.CurrentJob is { } job
            ? $"{_main.RegionName(job.RegionId)} · {LayerName(job.Target)} · {_main.ProgressText}"
            : "";
        BulkJobText.Visibility = BulkJobText.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        CancelBulkButton.Visibility = _main.IsBulkDownloading ? Visibility.Visible : Visibility.Collapsed;
        CancelBulkButton.IsEnabled = _main.CanCancelBulk;
    }

    private void CancelBulkButton_Click(object sender, RoutedEventArgs e) => _main.CancelBulkDownload();

    private async void BulkButton_Click(object sender, RoutedEventArgs e)
    {
        if (_main.IsBusy || _storageDetails is not null)
        {
            return;
        }
        var countries = _all.Select(item => item.Country).Distinct().Order(StringComparer.CurrentCulture).ToList();
        var scopes = new ComboBox { Header = "Download scope", HorizontalAlignment = HorizontalAlignment.Stretch };
        scopes.Items.Add("All regions");
        foreach (string country in countries)
        {
            scopes.Items.Add(country);
        }
        scopes.SelectedIndex = RegionList.SelectedItem is RegionItem selected ? countries.IndexOf(selected.Country) + 1 : 0;
        string? Country() => scopes.SelectedIndex > 0 ? scopes.SelectedItem as string : null;

        var estimate = BulkText("");
        var storage = BulkText($"{AppPaths.StoreDir}\nReading drive space…");
        var content = new StackPanel { Spacing = 12 };
        content.Children.Add(scopes);
        content.Children.Add(estimate);
        content.Children.Add(BulkText("Estimated download sizes exclude search indexes and temporary files. The batch stops if a download cannot finish. Choose the same scope to continue."));
        content.Children.Add(storage);
        var dialog = new ContentDialog
        {
            Title = "Bulk download",
            Content = new ScrollViewer { Content = content, MaxHeight = 400 },
            PrimaryButtonText = "Start download",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = Root.XamlRoot,
        };
        void ShowEstimate()
        {
            var plan = _main.PlanBulkDownload(Country());
            ulong total = (ulong)plan.Sum(item => (decimal)item.Bytes);
            estimate.Text = plan.Count == 0 ? "All available layers are already installed." :
                $"{plan.Count:N0} downloads · about {Format.StorageSize(total)}\n" + string.Join('\n',
                    plan.GroupBy(item => item.Job.Target).Select(layer => $"{LayerName(layer.Key)} · {layer.Count():N0}"));
            dialog.IsPrimaryButtonEnabled = plan.Count > 0 && !_main.IsBusy;
        }
        scopes.SelectionChanged += (_, _) => ShowEstimate();
        _main.RegionsChanged += ShowEstimate;
        AutomationProperties.SetLiveSetting(estimate, AutomationLiveSetting.Polite);
        ShowEstimate();
        _storageDetails = storage;
        _ = RefreshStorageAsync();
        App.Current.Themes.Attach(dialog);
        try
        {
            if (await dialog.ShowAsync() == ContentDialogResult.Primary && !_closed)
            {
                _main.StartBulkDownload(Country());
            }
        }
        finally
        {
            _storageDetails = null;
            _main.RegionsChanged -= ShowEstimate;
            App.Current.Themes.Detach(dialog);
        }
    }

    private static TextBlock BulkText(string text) => new()
    {
        Text = text,
        TextWrapping = TextWrapping.Wrap,
        Style = (Style)Application.Current.Resources["JupiterBodyTextStyle"],
    };

    private static string LayerName(UpdateTarget layer) => layer switch
    {
        UpdateTarget.Map => "Maps and search",
        UpdateTarget.Extras => "Stations and addresses",
        UpdateTarget.Overlays => "Flood zones and public lands",
        _ => "Terrain",
    };
}
