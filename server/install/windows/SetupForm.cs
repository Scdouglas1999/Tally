using System.Diagnostics;
using System.Drawing;

namespace Tally.ServerSetup;

/// <summary>One small window: what will happen, an Install button, progress, and what was done.</summary>
public sealed class SetupForm : Form, ISetupReport
{
    private static readonly Color Ground = Color.FromArgb(0x16, 0x17, 0x16);
    private static readonly Color Panel = Color.FromArgb(0x1F, 0x20, 0x1E);
    private static readonly Color Text1 = Color.FromArgb(0xE3, 0xE5, 0xDE);
    private static readonly Color Text2 = Color.FromArgb(0x9C, 0x9F, 0x96);
    private static readonly Color Amber = Color.FromArgb(0xFF, 0xB0, 0x00);

    private readonly Label _step;
    private readonly ProgressBar _progress;
    private readonly TextBox _log;
    private readonly Button _install;
    private readonly Button _close;
    private readonly bool _autoStart;
    private readonly bool _openBrowser;
    private readonly StreamWriter? _logFile;
    private bool _running;

    public SetupForm(bool autoStart, bool openBrowser, StreamWriter? logFile)
    {
        _autoStart = autoStart;
        _openBrowser = openBrowser;
        _logFile = logFile;

        Text = "Tally Server Setup";
        Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(640, 500);
        BackColor = Ground;
        ForeColor = Text1;
        Font = new Font("Segoe UI", 10f);

        var light = new Panel { BackColor = Amber, Bounds = new Rectangle(24, 30, 14, 14) };
        var title = new Label
        {
            Text = "Tally for Jellyfin",
            Font = new Font("Segoe UI Semibold", 16f),
            AutoSize = true,
            Location = new Point(48, 20)
        };
        var intro = new Label
        {
            Text = $"Adds the Tally {Setup.TallyVersion} plugin to the Jellyfin server on this computer. If there is no Jellyfin "
                   + "yet, the official Jellyfin is downloaded from repo.jellyfin.org and installed first. Jellyfin is stopped "
                   + "for a moment while the plugin is added; your libraries, users and settings are not touched.",
            ForeColor = Text2,
            Bounds = new Rectangle(24, 60, 592, 90)
        };
        _step = new Label { Text = "Ready.", Bounds = new Rectangle(24, 158, 592, 24), Font = new Font("Segoe UI Semibold", 10.5f) };
        _progress = new ProgressBar { Bounds = new Rectangle(24, 186, 592, 8), Style = ProgressBarStyle.Continuous };
        _log = new TextBox
        {
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            Bounds = new Rectangle(24, 206, 592, 220),
            BackColor = Panel,
            ForeColor = Text1,
            BorderStyle = BorderStyle.None,
            Font = new Font("Consolas", 9.5f)
        };
        _install = MakeButton("Install", 404, primary: true);
        _close = MakeButton("Close", 516, primary: false);
        _install.Click += async (_, _) => await RunAsync();
        _close.Click += (_, _) => Close();

        Controls.AddRange(new Control[] { light, title, intro, _step, _progress, _log, _install, _close });
        AcceptButton = _install;
        FormClosing += (_, e) => e.Cancel = _running;
        Shown += async (_, _) =>
        {
            if (_autoStart)
            {
                await RunAsync();
            }
        };
    }

    private Button MakeButton(string text, int x, bool primary)
    {
        var b = new Button
        {
            Text = text,
            Bounds = new Rectangle(x, 446, 100, 34),
            FlatStyle = FlatStyle.Flat,
            BackColor = primary ? Amber : Panel,
            ForeColor = primary ? Color.Black : Text1,
            Font = new Font("Segoe UI Semibold", 10f),
            Cursor = Cursors.Hand
        };
        b.FlatAppearance.BorderColor = primary ? Amber : Text2;
        return b;
    }

    private async Task RunAsync()
    {
        _running = true;
        _install.Enabled = false;
        _install.BackColor = Panel;
        _install.ForeColor = Text2;
        _install.FlatAppearance.BorderColor = Text2;
        _close.Enabled = false;
        var setup = new Setup(this);
        try
        {
            await Task.Run(() => setup.RunAsync(CancellationToken.None));
            _step.Text = "Done.";
            _step.ForeColor = Amber;
            _progress.Style = ProgressBarStyle.Continuous;
            _progress.Value = 100;
            Log(string.Empty);
            Log("What was done:");
            foreach (var line in setup.Summary)
            {
                Log("  - " + line);
            }

            if (_openBrowser && setup.OpenUrl is not null)
            {
                Log("Opening " + setup.OpenUrl);
                Process.Start(new ProcessStartInfo(setup.OpenUrl) { UseShellExecute = true });
            }
        }
        catch (Exception ex)
        {
            _step.Text = "Setup did not finish.";
            _step.ForeColor = Color.FromArgb(0xFF, 0x6B, 0x5B);
            _progress.Style = ProgressBarStyle.Continuous;
            _progress.Value = 0;
            Log(string.Empty);
            Log(ex is SetupException ? ex.Message : ex.ToString());
            foreach (var line in setup.Summary)
            {
                Log("  - done before that: " + line);
            }
        }
        finally
        {
            _running = false;
            _close.Enabled = true;
            _close.Focus();
        }
    }

    public void Step(string text) => UI(() =>
    {
        _step.Text = text + "…";
        Log(text);
    });

    public void Log(string text) => UI(() =>
    {
        _log.AppendText(text + Environment.NewLine);
        _logFile?.WriteLine($"{DateTime.Now:HH:mm:ss} {text}");
        _logFile?.Flush();
    });

    public void Progress(int? percent) => UI(() =>
    {
        if (percent is null)
        {
            _progress.Style = ProgressBarStyle.Marquee;
        }
        else
        {
            _progress.Style = ProgressBarStyle.Continuous;
            _progress.Value = Math.Clamp(percent.Value, 0, 100);
        }
    });

    private void UI(Action action)
    {
        if (InvokeRequired)
        {
            BeginInvoke(action);
        }
        else
        {
            action();
        }
    }
}
