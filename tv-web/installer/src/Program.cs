using System.Text;
using Tally.SamsungInstaller.Certificates;

namespace Tally.SamsungInstaller;

/// <summary>
/// Tally-Samsung-Installer: see <see cref="Options.Usage"/>. Every run also writes Tally-Samsung-Installer.log in the
/// temp folder (what was said, what the TV answered).
/// </summary>
internal static class Program
{
    private static async Task<int> Main(string[] args)
    {
        try
        {
            Console.OutputEncoding = Encoding.UTF8;
        }
        catch (IOException)
        {
            // no console
        }

        Options options;
        try
        {
            options = Options.Parse(args);
        }
        catch (Exception ex) when (ex is ArgumentException or FormatException)
        {
            Console.Error.WriteLine(ex.Message);
            return 2;
        }

        if (options.Help)
        {
            Console.WriteLine(Options.Usage);
            return 0;
        }

        if (options.Licenses)
        {
            Console.WriteLine(CertificateTools.EmbeddedText("certificates/NOTICE.txt"));
            Console.WriteLine(CertificateTools.EmbeddedText("certificates/LICENSE-Apache-2.0.txt"));
            return 0;
        }

        var logPath = Path.Combine(Path.GetTempPath(), "Tally-Samsung-Installer.log");
        using var log = OpenLog(logPath);
        log?.WriteLine($"--- Tally for Samsung {typeof(Program).Assembly.GetName().Version}, {DateTime.Now:yyyy-MM-dd HH:mm}, {Environment.OSVersion}, args: {string.Join(' ', args)}");
        var ui = Ui.ForConsole(log);

        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            cts.Cancel();
        };

        using var http = new HttpClient(new SocketsHttpHandler { ConnectTimeout = TimeSpan.FromSeconds(6) })
        {
            Timeout = TimeSpan.FromSeconds(60),
        };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("Tally-Samsung-Installer/1.0");
        var store = new CertificateStore(options.DataDir ?? CertificateStore.DefaultDirectory);
        var flow = new InstallerFlow(ui, options, http, store);
        int code;
        try
        {
            code = await flow.RunAsync(cts.Token);
        }
        catch (OperationCanceledException)
        {
            ui.Problem("Stopped.");
            code = 1;
        }
        catch (Exception ex)
        {
            ui.Problem("Something went wrong that this program did not expect: " + ex.Message);
            log?.WriteLine(ex.ToString());
            code = 1;
        }

        ui.Blank();
        ui.Detail($"Log: {logPath}. Certificates: {store.Directory} (keep this folder: updates need it).");
        if (!options.Yes && !Console.IsInputRedirected)
        {
            try
            {
                ui.Ask("Press Enter to close.");
            }
            catch (OperationCanceledException)
            {
                // input already closed
            }
        }

        return code;
    }

    private static StreamWriter? OpenLog(string path)
    {
        try
        {
            return new StreamWriter(path, append: true) { AutoFlush = true };
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }
}
