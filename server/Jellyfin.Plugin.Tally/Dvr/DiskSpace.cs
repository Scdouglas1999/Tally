using System;
using System.IO;
using System.Runtime.InteropServices;

namespace Jellyfin.Plugin.Tally.Dvr;

/// <summary>Free and total space of the drive (or mount) a folder is on.</summary>
public static class DiskSpace
{
    public sealed record Info(long FreeBytes, long TotalBytes);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetDiskFreeSpaceExW(string directoryName, out ulong freeBytesAvailable, out ulong totalBytes, out ulong totalFreeBytes);

    /// <summary>Null when it cannot be read (a path that does not exist yet is looked up through its nearest existing
    /// parent, so a folder about to be created still gets an answer).</summary>
    public static Info? For(string path)
    {
        try
        {
            var probe = Path.GetFullPath(path);
            while (!Directory.Exists(probe))
            {
                var parent = Path.GetDirectoryName(probe);
                if (string.IsNullOrEmpty(parent) || parent == probe)
                {
                    return null;
                }

                probe = parent;
            }

            // Windows: GetDiskFreeSpaceEx answers for any folder, including shares (\\nas\recordings) and volumes
            // mounted into a folder, which a drive-letter DriveInfo cannot. Linux and macOS: a DriveInfo is any path,
            // and statvfs reports the mount it is on, so a folder on a separate volume gets that volume's numbers.
            if (OperatingSystem.IsWindows())
            {
                var folder = probe.EndsWith('\\') ? probe : probe + "\\";
                if (GetDiskFreeSpaceExW(folder, out var available, out var total, out _))
                {
                    return new Info((long)available, (long)total);
                }

                var root = new DriveInfo(Path.GetPathRoot(probe)!);
                return new Info(root.AvailableFreeSpace, root.TotalSize);
            }

            var drive = new DriveInfo(probe);
            return new Info(drive.AvailableFreeSpace, drive.TotalSize);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            return null;
        }
    }
}
