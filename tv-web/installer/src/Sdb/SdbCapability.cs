using System.Text;

namespace Tally.SamsungInstaller.Sdb;

/// <summary>
/// The answer to <c>capability:</c>: "key:value" lines (after a 2-byte length on sdbd 2.x), e.g.
/// platform_version:6.0, sdk_toolpath:/home/owner/share/tmp/sdk_tools, secure_protocol:enabled.
/// </summary>
public sealed class SdbCapability
{
    private readonly Dictionary<string, string> _values;

    private SdbCapability(Dictionary<string, string> values) => _values = values;

    public IReadOnlyDictionary<string, string> Values => _values;

    public string? this[string key] => _values.TryGetValue(key, out var v) ? v : null;

    /// <summary>"6.0", "5.5", "10.0"; empty when the device does not say.</summary>
    public string PlatformVersion => this["platform_version"] ?? "";

    /// <summary>The major Tizen version (5, 6, 7, …); 0 when unknown.</summary>
    public int PlatformMajor
    {
        get
        {
            var v = PlatformVersion;
            var dot = v.IndexOf('.');
            return int.TryParse(dot < 0 ? v : v[..dot], out var major) ? major : 0;
        }
    }

    /// <summary>Where Tizen Studio copies packages before installing them (…/sdk_tools); a default when absent.</summary>
    public string SdkToolPath => string.IsNullOrWhiteSpace(this["sdk_toolpath"]) ? "/home/owner/share/tmp/sdk_tools" : this["sdk_toolpath"]!;

    public bool SecureProtocol => this["secure_protocol"] == "enabled";

    public string ProfileName => this["profile_name"] ?? "";

    public static SdbCapability Parse(byte[] raw)
    {
        var start = 0;
        if (raw.Length >= 2 && raw[0] + (raw[1] << 8) == raw.Length - 2)
        {
            start = 2; // sdbd 2.x: a little-endian length first
        }
        else if (raw.Length >= 4 && IsHex(raw.AsSpan(0, 4)) && Convert.ToInt32(Encoding.ASCII.GetString(raw, 0, 4), 16) == raw.Length - 4)
        {
            start = 4; // the host-service form: four hex digits
        }

        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var line in Encoding.UTF8.GetString(raw, start, raw.Length - start).Split('\n'))
        {
            var colon = line.IndexOf(':');
            if (colon > 0)
            {
                values[line[..colon].Trim()] = line[(colon + 1)..].Trim().TrimEnd('\0');
            }
        }

        return new SdbCapability(values);
    }

    private static bool IsHex(ReadOnlySpan<byte> b)
    {
        foreach (var c in b)
        {
            if (!Uri.IsHexDigit((char)c))
            {
                return false;
            }
        }

        return true;
    }
}
