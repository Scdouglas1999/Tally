namespace Tally.ServerSetup;

/// <summary>
/// Tally-Server-Setup.exe [/auto] [/quiet] [/nobrowser]
///   (no switch)  the window; the install starts when Install is pressed
///   /auto        the window, starting the install right away
///   /quiet       no window: prints to the console it was started from; exit code 0 = done, 1 = failed
///   /nobrowser   do not open Jellyfin in the browser at the end
/// Every run also writes %TEMP%\Tally-Server-Setup.log.
/// </summary>
internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        bool Has(string flag) => args.Any(a => a.Equals("/" + flag, StringComparison.OrdinalIgnoreCase) || a.Equals("--" + flag, StringComparison.OrdinalIgnoreCase));
        using var logFile = OpenLog();
        logFile?.WriteLine($"--- Tally Server Setup {Setup.TallyVersion}, {DateTime.Now:yyyy-MM-dd HH:mm}, args: {string.Join(' ', args)}");

        if (Has("quiet"))
        {
            ConsoleControl.AttachToParent();
            var report = new ConsoleReport(logFile);
            var setup = new Setup(report);
            try
            {
                setup.RunAsync(CancellationToken.None).GetAwaiter().GetResult();
                report.Log("What was done:");
                foreach (var line in setup.Summary)
                {
                    report.Log("  - " + line);
                }

                if (!Has("nobrowser") && setup.OpenUrl is not null)
                {
                    System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(setup.OpenUrl) { UseShellExecute = true });
                }

                return 0;
            }
            catch (Exception ex)
            {
                report.Log("Setup did not finish: " + (ex is SetupException ? ex.Message : ex.ToString()));
                return 1;
            }
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new SetupForm(Has("auto"), !Has("nobrowser"), logFile));
        return 0;
    }

    private static StreamWriter? OpenLog()
    {
        try
        {
            return new StreamWriter(Path.Combine(Path.GetTempPath(), "Tally-Server-Setup.log"), append: true);
        }
        catch (IOException)
        {
            return null;
        }
    }

    private sealed class ConsoleReport(StreamWriter? logFile) : ISetupReport
    {
        private int _lastPercent = -1;

        public void Step(string text) => Log("> " + text);

        public void Log(string text)
        {
            try
            {
                Console.WriteLine(text);
            }
            catch (IOException)
            {
                // no console to write to (started without one, or detached while stopping Jellyfin): the log file has it
            }

            logFile?.WriteLine($"{DateTime.Now:HH:mm:ss} {text}");
            logFile?.Flush();
        }

        public void Progress(int? percent)
        {
            if (percent is int p && p / 10 != _lastPercent / 10)
            {
                _lastPercent = p;
                Log($"  {p}%");
            }
        }
    }
}
