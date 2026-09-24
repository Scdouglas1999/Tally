using System.Text.RegularExpressions;
using Tally.SamsungInstaller.Sdb;

namespace Tally.SamsungInstaller.Tv;

public enum InstallOutcome
{
    Installed,
    /// <summary>The TV does not trust the signing (a Tizen certificate on a TV that needs a Samsung one).</summary>
    CertificateNotTrusted,
    /// <summary>Tally is installed with another author certificate: uninstall first.</summary>
    AuthorMismatch,
    /// <summary>A Samsung distributor certificate that does not list this TV.</summary>
    WrongTv,
    /// <summary>A certificate whose start is after the TV's clock.</summary>
    NotYetValid,
    Expired,
    /// <summary>The TV's Tizen is older than the package needs.</summary>
    TizenTooOld,
    Failed,
}

public sealed record InstallResult(InstallOutcome Outcome, string Log, string? Reason)
{
    public bool Ok => Outcome == InstallOutcome.Installed;
}

/// <summary>
/// A connected TV: what it is (Tizen version, DUID) and the installer's steps on it, with the commands Tizen Studio
/// uses on TVs (recorded against the emulator): push to &lt;sdk_toolpath&gt;/tmp, <c>0 vd_appinstall &lt;app id&gt;
/// &lt;path&gt;</c>, <c>0 was_execute &lt;app id&gt;</c>, <c>0 vd_appuninstall &lt;app id&gt;</c>.
/// </summary>
public sealed class TvDevice(SdbClient sdb, SdbCapability capability, string duid)
{
    public SdbClient Sdb { get; } = sdb;
    public SdbCapability Capability { get; } = capability;
    public string Duid { get; } = duid;

    public string TizenVersion => Capability.PlatformVersion;
    public int TizenMajor => Capability.PlatformMajor;

    /// <summary>Tizen 7 and newer (2023 on) install only packages signed with a Samsung certificate.</summary>
    public bool NeedsSamsungCertificate => TizenMajor >= 7;

    public static async Task<TvDevice> OpenAsync(SdbClient sdb, CancellationToken ct)
    {
        var capability = await sdb.GetCapabilityAsync(ct).ConfigureAwait(false);
        var duid = await ReadDuidAsync(sdb, capability, ct).ConfigureAwait(false);
        return new TvDevice(sdb, capability, duid);
    }

    /// <summary>
    /// The TV's DUID, which a Samsung distributor certificate must list. The command depends on the sdbd generation
    /// (as Samsung's own tools choose it): no secure protocol → /opt/etc/duid-gadget 2.0; product_version 1.0 →
    /// 0 getduidgadget; otherwise 0 getduid.
    /// </summary>
    public static async Task<string> ReadDuidAsync(SdbClient sdb, SdbCapability capability, CancellationToken ct)
    {
        var command = !capability.SecureProtocol ? "/opt/etc/duid-gadget 2.0"
            : capability["product_version"] == "1.0" ? "0 getduidgadget"
            : "0 getduid";
        var output = await sdb.ShellAsync(command, null, ct).ConfigureAwait(false);
        return ParseDuid(output);
    }

    public static string ParseDuid(string output)
    {
        foreach (var line in output.Split('\n').Select(l => l.Trim()).Reverse())
        {
            if (Regex.IsMatch(line, "^[A-Za-z0-9]{10,64}$"))
            {
                return line;
            }
        }

        return "";
    }

    /// <summary>Copies the package to the TV and installs it; <paramref name="onLine"/> sees the TV's progress lines.</summary>
    public async Task<InstallResult> InstallAsync(byte[] wgt, Action<string>? onLine, CancellationToken ct)
    {
        var remote = Capability.SdkToolPath.TrimEnd('/') + "/tmp/Tally.wgt";
        await Sdb.PushAsync(wgt, remote, ct: ct).ConfigureAwait(false);
        var pending = "";
        var log = await Sdb.ShellAsync($"0 vd_appinstall {ShellPackage.ApplicationId} {remote}", chunk =>
        {
            pending += chunk;
            int nl;
            while ((nl = pending.IndexOf('\n')) >= 0)
            {
                var line = pending[..nl].TrimEnd('\r');
                pending = pending[(nl + 1)..];
                if (line.Length > 0)
                {
                    onLine?.Invoke(line);
                }
            }
        }, ct).ConfigureAwait(false);
        return ParseInstall(log);
    }

    /// <summary>What vd_appinstall printed, as an outcome ("install completed" or "install failed[118, -12], reason: …").</summary>
    public static InstallResult ParseInstall(string log)
    {
        if (Regex.IsMatch(log, @"install completed", RegexOptions.IgnoreCase))
        {
            return new InstallResult(InstallOutcome.Installed, log, null);
        }

        var failed = Regex.Match(log, @"install failed\[(?<code>[^\]]*)\](?:, reason: (?<reason>.*))?", RegexOptions.IgnoreCase);
        var reason = failed.Success ? failed.Groups["reason"].Value.Trim() : null;
        var code = failed.Success ? failed.Groups["code"].Value : "";
        var text = (reason ?? log).ToLowerInvariant();
        var outcome =
            !failed.Success ? InstallOutcome.Failed
            : text.Contains("author certificate not match") || text.Contains("author signature") && text.Contains("not match")
                || text.Contains("different certificate") || text.Contains("signature not match") ? InstallOutcome.AuthorMismatch
            : text.Contains("not valid yet") ? InstallOutcome.NotYetValid
            : text.Contains("expired") ? InstallOutcome.Expired
            : text.Contains("duid") || text.Contains("device id") || text.Contains("device unique") ? InstallOutcome.WrongTv
            : text.Contains("certificate") || code.Contains("-12") ? InstallOutcome.CertificateNotTrusted
            : code.Contains("-4") || text.Contains("api version") || text.Contains("required_version") ? InstallOutcome.TizenTooOld
            : InstallOutcome.Failed;
        return new InstallResult(outcome, log, reason);
    }

    /// <summary>Starts Tally on the TV. False when the TV said it could not.</summary>
    public async Task<bool> LaunchAsync(CancellationToken ct)
    {
        var output = await Sdb.ShellAsync($"0 was_execute {ShellPackage.ApplicationId}", null, ct).ConfigureAwait(false);
        return !output.Contains("failed", StringComparison.OrdinalIgnoreCase);
    }

    public async Task<string> UninstallAsync(CancellationToken ct) =>
        await Sdb.ShellAsync($"0 vd_appuninstall {ShellPackage.ApplicationId}", null, ct).ConfigureAwait(false);

    /// <summary>Whether Tally is on the TV (0 vd_applist lists installed apps' ids).</summary>
    public async Task<bool> IsInstalledAsync(CancellationToken ct)
    {
        var list = await Sdb.ShellAsync("0 vd_applist", null, ct).ConfigureAwait(false);
        return list.Contains(ShellPackage.ApplicationId, StringComparison.Ordinal);
    }
}
