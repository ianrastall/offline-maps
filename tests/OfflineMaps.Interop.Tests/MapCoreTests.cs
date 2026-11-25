using System.Net;
using System.Net.Sockets;
using System.Text;
using Google.Protobuf;
using OfflineMaps.Interop;
using OfflineMaps.Interop.Messages;
using Xunit;

namespace OfflineMaps.Interop.Tests;

public sealed class TempStore : IDisposable
{
    public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), $"offline-maps-interop-{Guid.NewGuid():N}");

    public void Dispose()
    {
        try
        {
            Directory.Delete(Path, recursive: true);
        }
        catch (DirectoryNotFoundException)
        {
        }
    }
}

public class MapCoreTests
{
    [Fact]
    public void AbiVersionMatches()
    {
        MapCore.EnsureCompatible();
        Assert.Equal(MapCore.ExpectedAbiVersion, NativeMethods.om_abi_version());
    }

    [Fact]
    public void EmptyStoreHasNoMapAndRefusesSearch()
    {
        using var store = new TempStore();
        using var core = MapCore.Open(store.Path);
        var status = core.GetStatus();
        Assert.Empty(status.Regions);
        Assert.Null(status.Coverage);
        Assert.Equal(System.IO.Path.GetFullPath(store.Path), System.IO.Path.GetFullPath(status.StoreDirectory));

        var error = Assert.Throws<MapCoreException>(() => core.Search("springfield", 39.8, -89.65));
        Assert.Equal(ErrorKind.Unavailable, error.Kind);
        Assert.Empty(core.Tile(14, 4203, 6089));
        Assert.Empty(core.Tile(99, 0, 0));
        Assert.Empty(core.TerrainTile(12, 1046, 1520));
        Assert.Equal(ErrorKind.NotFound, Assert.Throws<MapCoreException>(() => core.Update(UpdateTarget.Terrain, "us-il")).Kind);
        var catalog = core.Catalog();
        Assert.True(catalog.Count > 300, $"{catalog.Count} regions");
        // Every region says where it is listed; regions elsewhere carry their country and continent.
        Assert.All(catalog, r => Assert.False(string.IsNullOrEmpty(r.Country) || string.IsNullOrEmpty(r.Continent), r.Id));
        Assert.Equal(100, catalog.Count(r => r.Continent == "North America" && r.Country != "Greenland"));
        Assert.Contains(catalog, r => r.Id == "mx" && r.Name == "All of Mexico");
        Assert.Contains(catalog, r => r.Id == "us-il" && r.Name == "Illinois");
        core.RemoveRegion("us-in");
        Assert.Equal(ErrorKind.InvalidArgument, Assert.Throws<MapCoreException>(() => core.RemoveRegion("us-zz")).Kind);
        Assert.Contains("FeatureCollection", core.StationsGeoJson());
        Assert.Contains("FeatureCollection", core.StationsGeoJson(new Bounds { West = 190, South = 40, East = 200, North = 41 }));
        Assert.Equal(ErrorKind.InvalidArgument, Assert.Throws<MapCoreException>(
            () => core.StationsGeoJson(new Bounds { West = 2, South = 0, East = 1, North = 1 })).Kind);
        using (var empty = new TempStore())
        {
            Directory.CreateDirectory(empty.Path);
            Assert.Equal(ErrorKind.InvalidData, Assert.Throws<MapCoreException>(() => core.ImportLibrary(empty.Path)).Kind);
        }
        Assert.False(core.Progress().Active);
        core.Cancel();
    }

    [Fact]
    public void SavedPlacesRoundTripWithUnicode()
    {
        using var store = new TempStore();
        using (var core = MapCore.Open(store.Path))
        {
            var place = core.AddPlace("Café Shelter — east door", "Bring ID ✓", 40.15, -88.96);
            Assert.Equal(1ul, place.Id);
            core.UpdatePlace(place.Id, "Shelter", "Bring ID ✓");
            var invalid = Assert.Throws<MapCoreException>(() => core.AddPlace("", "", 40, -89));
            Assert.Equal(ErrorKind.InvalidArgument, invalid.Kind);
            var outside = Assert.Throws<MapCoreException>(() => core.AddPlace("x", "", 91, 0));
            Assert.Equal(ErrorKind.InvalidArgument, outside.Kind);
        }
        using (var core = MapCore.Open(store.Path))
        {
            var places = core.GetStatus().Places;
            Assert.Single(places);
            Assert.Equal("Shelter", places[0].Name);
            Assert.Equal("Bring ID ✓", places[0].Note);
            core.RemovePlace(1);
            Assert.Equal(ErrorKind.NotFound, Assert.Throws<MapCoreException>(() => core.RemovePlace(1)).Kind);
        }
    }

    [Fact]
    public void SavedPlaceBackupPreviewsAndMergesAcrossStores()
    {
        using var source = new TempStore();
        using var destination = new TempStore();
        using var phone = MapCore.Open(source.Path);
        using var desktop = MapCore.Open(destination.Path);
        var original = desktop.AddPlace("Library", "", 40, -89);
        var shelter = phone.AddPlace("Café Shelter", "East door\nBring ID ✓", 41, -88);
        phone.AddPlace("Library", "", 40, -89);
        // A valid backup can exceed the old 1 MiB request limit.
        string backup = phone.ExportPlaces().PadRight(16 * 1024 * 1024 - 4);

        var preview = desktop.ImportPlaces(backup);
        Assert.Equal(1u, preview.Added);
        Assert.Equal(1u, preview.Skipped);
        Assert.Equal(original, Assert.Single(desktop.GetStatus().Places));

        Assert.Equal(preview, desktop.ImportPlaces(backup, apply: true));
        var places = desktop.GetStatus().Places;
        Assert.Equal(2, places.Count);
        Assert.Contains(original, places);
        Assert.Contains(places, place => place.Id != shelter.Id &&
            place.Name == shelter.Name && place.Note == shelter.Note && place.CreatedAt == shelter.CreatedAt);
        var repeated = desktop.ImportPlaces(backup, apply: true);
        Assert.Equal(0u, repeated.Added);
        Assert.Equal(2u, repeated.Skipped);
        Assert.Throws<MapCoreException>(() => desktop.ImportPlaces("{", apply: true));
        Assert.Equal(places, desktop.GetStatus().Places);
    }

    [Fact]
    public void RelativeStoreIsATypedCreationError()
    {
        var error = Assert.Throws<MapCoreException>(() => MapCore.Open("relative-folder"));
        Assert.Equal(ErrorKind.InvalidArgument, error.Kind);
    }

    [Fact]
    public void UnreachableServerIsANetworkError()
    {
        using var store = new TempStore();
        using var core = MapCore.Open(store.Path, packageUrl: "http://127.0.0.1:9/package.mbtiles");
        var error = Assert.Throws<MapCoreException>(() => core.Update(UpdateTarget.Map, "us-il"));
        Assert.Equal(ErrorKind.Network, error.Kind);
        Assert.False(core.GetStatus().Updating);
    }

    [Fact]
    public async Task CancellingAnUpdateCheckDiscardsTheDelayedResponse()
    {
        using var store = new TempStore();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        using var core = MapCore.Open(store.Path, packageUrl: $"http://127.0.0.1:{port}/map.mbtiles");

        var checking = Task.Run(() => core.Update(UpdateTarget.Map, "us-il", checkOnly: true));
        using var client = await listener.AcceptTcpClientAsync(timeout.Token);
        using var stream = client.GetStream();
        using var reader = new StreamReader(stream, Encoding.ASCII, leaveOpen: true);
        Assert.StartsWith("HEAD ", await reader.ReadLineAsync(timeout.Token));
        while (await reader.ReadLineAsync(timeout.Token) is { Length: > 0 })
        {
        }

        core.Cancel();
        Assert.True(core.Progress().Cancelling);
        await stream.WriteAsync("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n"u8.ToArray(), timeout.Token);
        var error = await Assert.ThrowsAsync<MapCoreException>(() => checking.WaitAsync(timeout.Token));
        Assert.Equal(ErrorKind.Cancelled, error.Kind);
        Assert.False(core.GetStatus().Updating);
    }

    [Fact]
    public unsafe void MalformedAndEmptyRequestsAreRejected()
    {
        using var store = new TempStore();
        using var core = MapCore.Open(store.Path);
        var handle = typeof(MapCore).GetField("_handle", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance)!
            .GetValue(core) as CoreSafeHandle;
        byte[] garbage = [0xff, 0xff, 0xff, 0xff];
        fixed (byte* p = garbage)
        {
            Assert.Equal(NativeStatus.MalformedRequest, NativeMethods.om_call(handle!, p, (nuint)garbage.Length, out var result));
            Assert.True(result.IsInvalid);
        }
        var empty = Assert.Throws<MapCoreException>(() => core.Call(new Request()));
        Assert.Equal(ErrorKind.InvalidArgument, empty.Kind);
    }

    [Fact]
    public void MessagesRoundTripWithUnknownFields()
    {
        // A newer core may add fields; this shell must still read what it knows.
        var status = new Status { ContentVersion = "us-il:1:", SearchReady = true };
        // Field 99, length-delimited: tag (99 << 3) | 2 = 794, as the varint 0x9A 0x06.
        byte[] bytes = [.. status.ToByteArray(), 0x9A, 0x06, 3, (byte)'n', (byte)'e', (byte)'w'];
        var parsed = Status.Parser.ParseFrom(bytes);
        Assert.Equal("us-il:1:", parsed.ContentVersion);
        Assert.True(parsed.SearchReady);
        Assert.Equal(bytes.Length, parsed.ToByteArray().Length);
    }

    [Fact]
    public void ConcurrentCallsDoNotInterfere()
    {
        using var store = new TempStore();
        using var core = MapCore.Open(store.Path);
        Parallel.For(0, 64, i =>
        {
            core.AddPlace($"Place {i}", "", 40 + i / 100.0, -89);
            _ = core.Tile(10, 262, 389);
            _ = core.GetStatus();
        });
        Assert.Equal(64, core.GetStatus().Places.Count);
    }

    [Fact]
    public void CatalogShowsPreparedSizesWherePublished()
    {
        using var store = new TempStore();
        using var core = MapCore.Open(store.Path);
        var prepared = core.Catalog().Where(r => r.PreparedExtrasBytes > 0).ToList();
        Assert.NotEmpty(prepared);
        // A prepared file keeps only what is read from the raw extract; an uninhabited region's
        // (its empty tables, about 70 KB) can exceed a raw extract of a few kilobytes.
        Assert.All(prepared, r => Assert.True(r.PreparedExtrasBytes < Math.Max(r.ExtrasBytes, 1UL << 20), r.Id));
    }

    /// <summary>
    /// Installs the smallest region's prepared stations and addresses through the app's path,
    /// from OFFLINE_MAPS_TEST_BUNDLES: a folder made by `offline-maps-dev prepare-extras`, served
    /// on this computer, or the address of a published one (the extras release).
    /// </summary>
    [Fact]
    public async Task PreparedExtrasWhenAvailable()
    {
        string? source = Environment.GetEnvironmentVariable("OFFLINE_MAPS_TEST_BUNDLES");
        if (source is null)
        {
            return;
        }
        bool published = source.StartsWith("https://", StringComparison.Ordinal);
        string json = published
            ? await new HttpClient().GetStringAsync($"{source.TrimEnd('/')}/manifest.json")
            : await File.ReadAllTextAsync(System.IO.Path.Combine(source, "manifest.json"));
        using var manifest = System.Text.Json.JsonDocument.Parse(json);
        var smallest = manifest.RootElement.GetProperty("extras").EnumerateObject()
            .MinBy(e => e.Value.GetProperty("bytes").GetUInt64());
        string id = smallest.Name;
        ulong bytes = smallest.Value.GetProperty("bytes").GetUInt64();
        using var server = published ? null : new FolderServer(source);
        using var store = new TempStore();
        using var core = MapCore.Open(store.Path, bundlesUrl: server?.Url ?? source);
        var check = core.Update(UpdateTarget.Extras, id, checkOnly: true);
        Assert.True(check.Available);
        Assert.Equal(bytes, check.RemoteBytes);
        Assert.False(core.Update(UpdateTarget.Extras, id).UpToDate);
        var extras = Assert.Single(core.GetStatus().Regions, r => r.Id == id).Extras;
        Assert.NotNull(extras);
        Assert.True(extras.Prepared);
        Assert.True(core.Update(UpdateTarget.Extras, id).UpToDate);
        if (published)
        {
            // A region with no prepared file is checked against its raw extract (a size query to
            // Geofabrik; nothing is downloaded).
            var listed = manifest.RootElement.GetProperty("extras").EnumerateObject().Select(e => e.Name).ToHashSet();
            // (Once every region is listed there is none to check.)
            var unlisted = core.Catalog().FirstOrDefault(r => !listed.Contains(r.Id) && r.Id != "us-il" && r.ExtrasUrl.Contains("geofabrik.de", StringComparison.Ordinal));
            if (unlisted is not null)
            {
                var raw = core.Update(UpdateTarget.Extras, unlisted.Id, checkOnly: true);
                Assert.True(raw.Available, unlisted.Id);
                Assert.InRange(raw.RemoteBytes, unlisted.ExtrasBytes / 2, unlisted.ExtrasBytes * 2);
            }
        }
    }

    /// <summary>Uses a real store when OFFLINE_MAPS_TEST_STORE names one.</summary>
    [Fact]
    public void RealStoreWhenAvailable()
    {
        string? path = Environment.GetEnvironmentVariable("OFFLINE_MAPS_TEST_STORE");
        if (path is null)
        {
            return;
        }
        using var core = MapCore.Open(path);
        var status = core.GetStatus();
        var illinois = Assert.Single(status.Regions, r => r.Id == "us-il");
        Assert.NotNull(illinois.Map);
        Assert.True(status.SearchReady, status.MapProblem);
        Assert.True(core.Tile(14, 4203, 6089).Length > 1000);
        var results = core.Search("905 e main st carbondale", 39.8, -89.65);
        if (illinois.Extras is not null)
        {
            Assert.Equal("905 East Main Street", results[0].Name);
            Assert.Equal("address", results[0].Kind);
        }
        Assert.Equal("Springfield", core.Search("springfield", null, null, 1)[0].Name);
    }
}
