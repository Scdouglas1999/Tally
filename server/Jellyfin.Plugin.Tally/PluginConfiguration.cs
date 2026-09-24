using System;
using System.Collections.Generic;
using MediaBrowser.Model.Plugins;

namespace Jellyfin.Plugin.Tally;

/// <summary>XmlSerializer-friendly header entry (Dictionary can't be XML-serialized).</summary>
public class HeaderEntry
{
    public string Key { get; set; } = string.Empty;

    public string Value { get; set; } = string.Empty;
}

public static class HeaderListExtensions
{
    public static Dictionary<string, string> ToDictionary(this List<HeaderEntry>? list)
    {
        var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (list == null)
        {
            return d;
        }

        foreach (var h in list)
        {
            if (!string.IsNullOrWhiteSpace(h.Key))
            {
                d[h.Key.Trim()] = h.Value;
            }
        }

        return d;
    }
}

public enum SourceKind
{
    M3u,
    Direct,
    Web
}

public class SourceDefinition
{
    public Guid Id { get; set; } = Guid.NewGuid();

    public string Name { get; set; } = string.Empty;

    public SourceKind Kind { get; set; } = SourceKind.M3u;

    /// <summary>M3U playlist URL (M3u sources).</summary>
    public string PlaylistUrl { get; set; } = string.Empty;

    /// <summary>Optional XMLTV EPG URL (M3u sources). If empty, tvg-id/url-tvg from the playlist is used.</summary>
    public string EpgUrl { get; set; } = string.Empty;

    /// <summary>Default headers applied to every stream request for this source (e.g. Referer, User-Agent).</summary>
    public List<HeaderEntry> Headers { get; set; } = new();

    /// <summary>Manually defined streams (Direct sources).</summary>
    public List<DirectStream> Streams { get; set; } = new();

    /// <summary>Web page to scan for streams (Web sources).</summary>
    public string PageUrl { get; set; } = string.Empty;

    /// <summary>Web sources: fall back to a headless browser when plain HTTP extraction finds nothing.</summary>
    public bool UseBrowserFallback { get; set; } = true;

    /// <summary>Web sources: max pages to crawl per refresh.</summary>
    public int MaxPages { get; set; } = 12;

    /// <summary>Web sources: comma-separated leagues/groups to include
    /// ("NFL, MLB", "American Football", "Basketball"...). Empty = everything found.</summary>
    public string Include { get; set; } = string.Empty;

    public bool Enabled { get; set; } = true;
}

public class DirectStream
{
    public string Name { get; set; } = string.Empty;

    public string Url { get; set; } = string.Empty;

    public string LogoUrl { get; set; } = string.Empty;

    public string Group { get; set; } = "Live";

    /// <summary>Optional tvg-id so EPG data can attach to a direct stream.</summary>
    public string TvgId { get; set; } = string.Empty;

    public List<HeaderEntry> Headers { get; set; } = new();
}

public class PluginConfiguration : BasePluginConfiguration
{
    public List<SourceDefinition> Sources { get; set; } = new();

    /// <summary>How often playlists/EPG are refreshed, in minutes.</summary>
    public int RefreshIntervalMinutes { get; set; } = 30;

    /// <summary>Default User-Agent for upstream playlist/segment requests.</summary>
    public string UserAgent { get; set; } =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    /// <summary>Secret used to sign proxied stream URLs. Auto-generated on first run.</summary>
    public string ProxySecret { get; set; } = string.Empty;

    /// <summary>Allow non-admin users to access the JellyTV page.</summary>
    public bool AllowNonAdminUsers { get; set; } = true;

    /// <summary>Open JellyTV instead of Jellyfin's own Live TV page in the web client, for every
    /// user. Done by adding one script tag to index.html as it is served — no files are modified.</summary>
    public bool ReplaceLiveTv { get; set; } = true;

    /// <summary>The Tally look for the Jellyfin web client, for every user: theme, Tally branding and a Sports entry in
    /// the side menu. Added to index.html as it is served, like <see cref="ReplaceLiveTv"/>; off gives the stock client.</summary>
    public bool WebLook { get; set; } = true;

    /// <summary>Fetch live scores / last play from ESPN's public scoreboard feed. The only
    /// third-party request the plugin makes on its own; off means the Games board is hidden.</summary>
    public bool ScoresEnabled { get; set; } = true;

    /// <summary>Keep native apps' channel cards live: redraw them with the current score every couple
    /// of minutes while games are on, and re-run Jellyfin's guide refresh so channel order follows the heat.</summary>
    public bool LiveCardsEnabled { get; set; } = true;

    /// <summary>Comma-separated ESPN league paths ("football/nfl,baseball/mlb"). Empty = built-in defaults.
    /// A string, not a list: XmlSerializer appends to list defaults on every load.</summary>
    public string ScoreLeagues { get; set; } = string.Empty;

    /// <summary>Development only, never shown in the settings page: a base URL that replaces ESPN's hosts for the
    /// scoreboard feed ("http://172.17.0.1:8765"), so a simulator can serve an ESPN-shaped payload and bump scores
    /// on demand (Tally's tally/dev/score-sim.py). Empty, the default, means ESPN.</summary>
    public string ScoreboardSourceOverride { get; set; } = string.Empty;

    /// <summary>Live ladder: channels with several streams (merged duplicates, or renditions of one master) get one
    /// continuous playlist that starts on the best stream that plays smoothly and moves between streams without the
    /// viewer noticing. Off = every channel is proxied exactly as its first stream.</summary>
    public bool LiveLadderEnabled { get; set; } = true;

    /// <summary>Development only, never shown in the settings page: how a switch is presented to players.
    /// Empty (the default) splices the new stream's timestamps onto the old one's; "discontinuity" only marks the
    /// switch with #EXT-X-DISCONTINUITY (kept to compare the two).</summary>
    public string LiveSwitchMode { get; set; } = string.Empty;

    /// <summary>Where /JellyTV/app sends a TV to download the Android TV app. The default always resolves to
    /// the newest release of the fork, so the address people type never changes.</summary>
    public string TvAppUrl { get; set; } = "https://github.com/Scdouglas1999/Tally/releases/latest/download/Tally.apk";

    /// <summary>Optional AFTVnews Downloader short code that points at <see cref="TvAppUrl"/>. When set, the
    /// install page offers it instead of an address: five digits are easier on a TV remote.</summary>
    public string DownloaderCode { get; set; } = string.Empty;

    /// <summary>The address people outside the house use to reach this server ("http://203.0.113.7:8096"). Used for
    /// the install link and QR code; empty = whatever address the page was opened on.</summary>
    public string PublicUrl { get; set; } = string.Empty;

    /// <summary>True once the plugin has added Tally's plugin repository to the server's list (see
    /// Services/PluginRepositoryService.cs). Kept so a repository the admin removed is not added back.</summary>
    public bool PluginRepositoryAdded { get; set; }
}
