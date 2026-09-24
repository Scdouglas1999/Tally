using System;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Text;
using Jellyfin.Plugin.Tally.Models;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>
/// Gives channels ids that survive a rescan. Adapters mint an id from the stream URL, but scraped
/// streams carry rotating tokens, so that id changed every scan — silently dropping favorites, and
/// (because Jellyfin derives its own Live TV channel id from the URL we publish) making Jellyfin
/// re-create every channel every half hour. Identity now comes from what actually stays put:
/// the source and the channel's name.
/// </summary>
public static class ChannelIdentity
{
    /// <summary>Re-ids the channels in place. The adapter's URL-based id is kept as
    /// <see cref="SourceChannel.LegacyId"/> so stored favorites can be migrated.</summary>
    public static void Assign(IList<SourceChannel> channels)
    {
        var seen = new Dictionary<string, int>(StringComparer.Ordinal);
        foreach (var c in channels)
        {
            if (string.IsNullOrEmpty(c.LegacyId))
            {
                c.LegacyId = c.Id;
            }

            var key = c.SourceId + "|" + Normalize(c.Name);
            seen[key] = seen.TryGetValue(key, out var n) ? n + 1 : 1;

            // two streams with one name in one source ("ESPN", "ESPN" backup): the second is "#2"
            c.Id = Hash(seen[key] == 1 ? key : key + "#" + seen[key]);
        }
    }

    public static string Normalize(string name)
    {
        var sb = new StringBuilder(name.Length);
        var lastWasSpace = true;
        foreach (var ch in name)
        {
            if (char.IsLetterOrDigit(ch))
            {
                sb.Append(char.ToLowerInvariant(ch));
                lastWasSpace = false;
            }
            else if (!lastWasSpace)
            {
                sb.Append(' ');
                lastWasSpace = true;
            }
        }

        return sb.ToString().TrimEnd();
    }

    private static string Hash(string value)
        => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)))[..16].ToLowerInvariant();
}
