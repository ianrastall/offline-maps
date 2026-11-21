using System.Runtime.InteropServices;

namespace OfflineMaps.App;

internal sealed partial record DownloadStorage(string Destination, IReadOnlyList<string> OtherDrives)
{
    public static unsafe DownloadStorage Read(string folder, bool includeOtherDrives)
    {
        // Resolve folder mount points and junctions. Measure the folder itself so free bytes reflect
        // the account's quota and the actual write destination, including network shares.
        var buffer = stackalloc char[512];
        string volume = GetVolumePathName(folder, buffer, 512) ? new string(buffer) : Path.GetPathRoot(folder) ?? folder;
        string destination = Describe(volume, folder);
        var others = new List<string>();
        if (includeOtherDrives)
        {
            foreach (var drive in DriveInfo.GetDrives().OrderBy(drive => drive.Name, StringComparer.OrdinalIgnoreCase))
            {
                if (!string.Equals(drive.Name, volume, StringComparison.OrdinalIgnoreCase))
                {
                    others.Add(Describe(drive.Name, drive.Name));
                }
            }
        }
        return new DownloadStorage(destination, others);
    }

    private static string Describe(string volume, string path) => GetDiskFreeSpaceEx(
        Path.EndsInDirectorySeparator(path) ? path : path + Path.DirectorySeparatorChar, out ulong free, out ulong total, out _)
        ? $"{volume} · {Format.StorageSize(free)} free of {Format.StorageSize(total)}"
        : $"{volume} · Unavailable";

    [LibraryImport("kernel32.dll", EntryPoint = "GetVolumePathNameW", StringMarshalling = StringMarshalling.Utf16)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static unsafe partial bool GetVolumePathName(string path, char* volume, uint bufferLength);

    [LibraryImport("kernel32.dll", EntryPoint = "GetDiskFreeSpaceExW", StringMarshalling = StringMarshalling.Utf16)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetDiskFreeSpaceEx(string path, out ulong free, out ulong total, out ulong totalFree);
}
