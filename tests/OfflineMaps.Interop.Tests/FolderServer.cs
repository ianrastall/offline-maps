using System.Net;
using System.Net.Sockets;
using System.Text;

namespace OfflineMaps.Interop.Tests;

/// <summary>
/// Serves the files of a folder over HTTP on the loopback address, as a release's file list
/// does: GET and HEAD, whole files only (range requests are answered in full), 404 for
/// anything else. Each connection is closed after one response.
/// </summary>
public sealed class FolderServer : IDisposable
{
    private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
    private readonly string _folder;
    private readonly CancellationTokenSource _stop = new();

    public FolderServer(string folder)
    {
        _folder = Path.GetFullPath(folder);
        _listener.Start();
        _ = Task.Run(AcceptAsync);
    }

    /// <summary>The folder's address, with no trailing slash.</summary>
    public string Url => $"http://127.0.0.1:{((IPEndPoint)_listener.LocalEndpoint).Port}";

    private async Task AcceptAsync()
    {
        while (!_stop.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(_stop.Token);
            }
            catch (Exception e) when (e is OperationCanceledException or ObjectDisposedException or SocketException)
            {
                return;
            }
            _ = Task.Run(() => AnswerAsync(client));
        }
    }

    private async Task AnswerAsync(TcpClient client)
    {
        using (client)
        await using (var stream = client.GetStream())
        {
            using var reader = new StreamReader(stream, Encoding.ASCII, leaveOpen: true);
            string[] request = (await reader.ReadLineAsync() ?? "").Split(' ');
            while (!string.IsNullOrEmpty(await reader.ReadLineAsync()))
            {
            }
            string method = request.Length > 1 ? request[0] : "";
            string name = request.Length > 1 ? Uri.UnescapeDataString(request[1].TrimStart('/')) : "";
            string path = Path.GetFullPath(Path.Combine(_folder, name));
            bool found = (method is "GET" or "HEAD") && name.Length > 0 && !name.Contains('/') && !name.Contains('\\')
                && path.StartsWith(_folder, StringComparison.OrdinalIgnoreCase) && File.Exists(path);
            if (!found)
            {
                await stream.WriteAsync(Encoding.ASCII.GetBytes("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
                return;
            }
            var info = new FileInfo(path);
            string head = $"HTTP/1.1 200 OK\r\nContent-Length: {info.Length}\r\nContent-Type: application/octet-stream\r\n"
                + $"ETag: \"{info.Length}-{info.LastWriteTimeUtc.Ticks}\"\r\nLast-Modified: {info.LastWriteTimeUtc:R}\r\nConnection: close\r\n\r\n";
            await stream.WriteAsync(Encoding.ASCII.GetBytes(head));
            if (method == "GET")
            {
                await using var file = File.OpenRead(path);
                await file.CopyToAsync(stream);
            }
        }
    }

    public void Dispose()
    {
        _stop.Cancel();
        _listener.Stop();
        _stop.Dispose();
    }
}
