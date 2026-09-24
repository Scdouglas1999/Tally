using System;
using System.Text;
using System.Text.RegularExpressions;

namespace Jellyfin.Plugin.Tally.Services;

public static partial class HlsPlaylistRewriter
{
    private static readonly Regex UriAttrRegex = UriAttrPattern();

    /// <summary>Detects HLS playlists by extension, content-type, or magic bytes.</summary>
    public static bool LooksLikePlaylist(string url, string? contentType, ReadOnlySpan<byte> prefix)
    {
        if (!string.IsNullOrEmpty(contentType)
            && (contentType.Contains("mpegurl", StringComparison.OrdinalIgnoreCase)
                || contentType.Contains("vnd.apple", StringComparison.OrdinalIgnoreCase)))
        {
            return true;
        }

        var path = url.Split('?', '#')[0];
        if (path.EndsWith(".m3u8", StringComparison.OrdinalIgnoreCase)
            || path.EndsWith(".m3u", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        // Tolerate UTF-8 BOM / leading whitespace before #EXTM3U.
        var p = prefix;
        if (p.StartsWith("\uFEFF"u8))
        {
            p = p[3..];
        }

        while (p.Length > 0 && (p[0] == (byte)' ' || p[0] == (byte)'\t' || p[0] == (byte)'\r' || p[0] == (byte)'\n'))
        {
            p = p[1..];
        }

        return p.StartsWith("#EXTM3U"u8);
    }

    /// <summary>
    /// Rewrites every resolvable URI in an HLS playlist (segment lines plus URI="..." attributes
    /// in EXT-X-KEY/MAP/MEDIA/I-FRAME-STREAM-INF etc.) through <paramref name="makeProxyUrl"/>.
    /// </summary>
    public static string Rewrite(string content, Uri baseUri, Func<Uri, string> makeProxyUrl)
    {
        var sb = new StringBuilder(content.Length + 256);
        var reader = new System.IO.StringReader(content);
        string? line;

        while ((line = reader.ReadLine()) != null)
        {
            if (line.StartsWith('#'))
            {
                sb.AppendLine(UriAttrRegex.Replace(line, m =>
                {
                    if (Uri.TryCreate(baseUri, m.Groups[1].Value, out var resolved))
                    {
                        return $"URI=\"{makeProxyUrl(resolved)}\"";
                    }

                    return m.Value;
                }));
            }
            else if (line.Trim().Length > 0)
            {
                var trimmed = line.Trim();
                if (Uri.TryCreate(baseUri, trimmed, out var resolved))
                {
                    sb.AppendLine(makeProxyUrl(resolved));
                }
                else
                {
                    sb.AppendLine(line);
                }
            }
            else
            {
                sb.AppendLine(line);
            }
        }

        return sb.ToString();
    }

    [GeneratedRegex("URI=\"([^\"]+)\"")]
    private static partial Regex UriAttrPattern();
}
