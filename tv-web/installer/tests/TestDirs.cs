namespace Tally.SamsungInstaller.Tests;

/// <summary>Temporary folders for one test run, all removed when the test process ends.</summary>
internal static class TestDirs
{
    private static readonly string Root = Directory.CreateTempSubdirectory("tally-samsung-tests-").FullName;

    static TestDirs() => AppDomain.CurrentDomain.ProcessExit += (_, _) =>
    {
        try
        {
            Directory.Delete(Root, recursive: true);
        }
        catch (IOException)
        {
            // best effort
        }
    };

    public static string New() => Directory.CreateDirectory(Path.Combine(Root, Guid.NewGuid().ToString("N")[..8])).FullName;
}
