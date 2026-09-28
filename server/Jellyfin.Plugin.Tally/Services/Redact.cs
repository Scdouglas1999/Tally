using System;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>Addresses as they may appear in logs and error messages. IPTV playlist addresses carry the account in
/// them (<c>get.php?username=…&amp;password=…</c>, <c>user:pass@host</c>), and logs get pasted into bug reports and
/// error messages reach every signed-in user, so only the scheme, host, port and path are shown.</summary>
public static class Redact
{
    public static string Url(string? url)
    {
        if (string.IsNullOrWhiteSpace(url))
        {
            return string.Empty;
        }

        if (!Uri.TryCreate(url.Trim(), UriKind.Absolute, out var uri) || string.IsNullOrEmpty(uri.Host))
        {
            return "(address hidden)";
        }

        var hidden = uri.Query.Length > 1 || uri.UserInfo.Length > 0 || uri.Fragment.Length > 1;
        return uri.GetLeftPart(UriPartial.Authority).Replace(uri.UserInfo + "@", string.Empty, StringComparison.Ordinal)
               + uri.AbsolutePath + (hidden ? "?…" : string.Empty);
    }
}
