using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Models;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>Owns the merged, cached view of all configured sources.</summary>
public class SourceManager
{
    private readonly IHttpClientFactory _httpClientFactory;
    private readonly ILogger<SourceManager> _logger;
    private readonly Scores.ScoreboardService? _scoreboard;
    private readonly Services.BrowserRuntime? _browser;

    // Grouping memory: which ids represented a group, and which entries were tied to a game by team names —
    // so a merged channel keeps its id and stays merged after the game leaves the scoreboard.
    private HashSet<string> _representatives = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, (string Game, DateTimeOffset Until)> _stickyTeams = new(StringComparer.OrdinalIgnoreCase);
    private readonly SemaphoreSlim _refreshLock = new(1, 1);

    // Last channel list per source — ephemeral Web streams shouldn't vanish
    // from the guide just because one scan ran during an anti-bot check.
    private readonly Dictionary<string, List<SourceChannel>> _lastGood = new(StringComparer.OrdinalIgnoreCase);

    private volatile Snapshot _snapshot = new();

    public SourceManager(IHttpClientFactory httpClientFactory, ILogger<SourceManager> logger, Scores.ScoreboardService? scoreboard = null, Services.BrowserRuntime? browser = null)
    {
        _httpClientFactory = httpClientFactory;
        _logger = logger;
        _scoreboard = scoreboard;
        _browser = browser;
        if (browser != null)
        {
            // a web page source waited for its first-time browser download: scan again now that it is there
            browser.BecameReady += () => _ = RefreshAsync(CancellationToken.None);
        }

        if (Plugin.Instance != null)
        {
            Plugin.Instance.ConfigurationChanged += (_, _) =>
            {
                _ = RefreshAsync(CancellationToken.None);
            };
        }
    }

    public string DataVersion => _snapshot.LoadedAt.Ticks.ToString();

    public DateTimeOffset LoadedAt => _snapshot.LoadedAt;

    public IReadOnlyDictionary<string, string> SourceErrors => _snapshot.Errors;

    /// <summary>Raised after every refresh with the new channel list.</summary>
    public event Action<IReadOnlyList<SourceChannel>>? Refreshed;

    public IReadOnlyList<SourceChannel> GetChannels() => _snapshot.Channels;

    /// <summary>A channel by id — also by the id of an entry that was merged into it.</summary>
    public SourceChannel? GetChannel(string id)
        => _snapshot.ById.TryGetValue(id, out var ch) ? ch
            : _snapshot.Aliases.TryGetValue(id, out var to) && _snapshot.ById.TryGetValue(to, out ch) ? ch : null;

    /// <summary>Current id for an id stored by an older build (URL-derived) or of a channel since merged into
    /// another, or null if unrecognized.</summary>
    public string? ResolveId(string storedId)
        => _snapshot.ById.ContainsKey(storedId) ? storedId
            : _snapshot.Aliases.TryGetValue(storedId, out var to) ? to
            : _snapshot.ByLegacyId.TryGetValue(storedId, out var ch) ? ch.Id : null;

    /// <summary>Programmes for a channel overlapping [start, end).</summary>
    public IReadOnlyList<Programme> GetProgrammes(string channelId, DateTimeOffset start, DateTimeOffset end)
    {
        if (!_snapshot.ById.TryGetValue(channelId, out var ch))
        {
            return Array.Empty<Programme>();
        }

        var key = ProgrammeKey(ch);
        if (key == null || !_snapshot.Programmes.TryGetValue(key, out var list))
        {
            return Array.Empty<Programme>();
        }

        return list.Where(p => p.End > start && p.Start < end).ToList();
    }

    public (Programme? Now, Programme? Next) GetNowNext(string channelId)
    {
        var now = DateTimeOffset.UtcNow;
        if (!_snapshot.ById.TryGetValue(channelId, out var ch))
        {
            return (null, null);
        }

        var key = ProgrammeKey(ch);
        if (key == null || !_snapshot.Programmes.TryGetValue(key, out var list))
        {
            return (null, null);
        }

        Programme? current = null;
        Programme? next = null;
        foreach (var p in list)
        {
            if (p.Start <= now && p.End > now)
            {
                current = p;
            }
            else if (p.Start > now)
            {
                next = p;
                break;
            }
        }

        return (current, next);
    }

    public async Task RefreshAsync(CancellationToken cancellationToken)
    {
        if (!await _refreshLock.WaitAsync(0, cancellationToken).ConfigureAwait(false))
        {
            return; // a refresh is already running
        }

        try
        {
            var config = Plugin.Instance?.Configuration;
            var next = new Snapshot { LoadedAt = DateTimeOffset.UtcNow };
            var channels = new List<SourceChannel>();
            var programmes = new Dictionary<string, List<Programme>>(StringComparer.OrdinalIgnoreCase);

            var defs = (config?.Sources ?? new List<SourceDefinition>())
                .Where(s => s.Enabled)
                .ToList();
            var tasks = defs.Select(s => BuildAdapter(s).RefreshAsync(cancellationToken)).ToList();

            for (var i = 0; i < tasks.Count; i++)
            {
                SourceSnapshot result;
                try
                {
                    result = await tasks[i].ConfigureAwait(false);
                }
                catch (Exception ex)
                {
                    _logger.LogWarning(ex, "JellyTV: source '{Name}' refresh failed", defs[i].Name);
                    next.Errors[defs[i].Name] = ex.Message;
                    continue;
                }

                if (result.Error != null)
                {
                    next.Errors[defs[i].Name] = result.Error;
                }

                // Failed scans keep the previous channel list for that source —
                // a flaky upstream shouldn't blank out the guide.
                if (result.Channels.Count == 0 && result.Error != null
                    && _lastGood.TryGetValue(defs[i].Name, out var stale))
                {
                    _logger.LogInformation("JellyTV: keeping {Count} last-known channels for '{Name}'", stale.Count, defs[i].Name);
                    channels.AddRange(stale);
                }
                else
                {
                    channels.AddRange(result.Channels);
                    if (result.Channels.Count > 0)
                    {
                        _lastGood[defs[i].Name] = new List<SourceChannel>(result.Channels);
                    }
                }
                foreach (var kv in result.ProgrammesByTvgId)
                {
                    if (!programmes.TryGetValue(kv.Key, out var list))
                    {
                        list = new List<Programme>();
                        programmes[kv.Key] = list;
                    }

                    list.AddRange(kv.Value);
                }
            }

            var games = await GamesAsync(channels, cancellationToken).ConfigureAwait(false);
            if (channels.Any(c => c.NameFromTitle))
            {
                var (kept, dropped) = ChannelNaming.Resolve(channels, games);
                if (dropped > 0)
                {
                    _logger.LogInformation("JellyTV: dropped {Count} web streams named only by their page's title (no game on the scoreboard matches it)", dropped);
                }

                channels = kept;
            }

            ChannelIdentity.Assign(channels);
            var grouped = Group(channels, games);
            channels = grouped.Channels;
            next.Aliases = grouped.Aliases;
            channels.Sort((a, b) => string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase));
            next.Channels = channels;
            foreach (var c in channels)
            {
                next.ById.TryAdd(c.Id, c);
                next.ByLegacyId.TryAdd(c.LegacyId, c);
            }

            next.Programmes = programmes;
            _snapshot = next;

            var merged = channels.Where(c => c.Candidates.Count > 1).ToList();
            _logger.LogInformation("JellyTV: loaded {Count} channels, {Epg} EPG feeds", channels.Count, programmes.Count);
            if (merged.Count > 0)
            {
                _logger.LogInformation("JellyTV: {Count} channels have several streams: {List}", merged.Count,
                    string.Join("; ", merged.Take(12).Select(c => $"{c.Name} ×{c.Candidates.Count}")));
            }

            try
            {
                Refreshed?.Invoke(channels);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "JellyTV: refresh listener failed");
            }
        }
        finally
        {
            _refreshLock.Release();
        }
    }

    /// <summary>Today's games, for naming and grouping; null without a scoreboard.</summary>
    private async Task<IReadOnlyList<Scores.GameInfo>?> GamesAsync(List<SourceChannel> channels, CancellationToken ct)
    {
        if (_scoreboard == null || !(Plugin.Instance?.Configuration.ScoresEnabled ?? true) || channels.Count == 0)
        {
            return null;
        }

        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(15));
            return await _scoreboard.GetGamesAsync(cts.Token).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !ct.IsCancellationRequested)
        {
            _logger.LogDebug(ex, "JellyTV: no scoreboard for grouping");
            return null;
        }
    }

    /// <summary>Folds duplicates into multi-stream channels (see <see cref="ChannelGrouper"/>).</summary>
    private GroupResult Group(List<SourceChannel> channels, IReadOnlyList<Scores.GameInfo>? games)
    {
        var now = DateTimeOffset.UtcNow;
        foreach (var dead in _stickyTeams.Where(kv => kv.Value.Until < now).Select(kv => kv.Key).ToList())
        {
            _stickyTeams.Remove(dead);
        }

        var sticky = _stickyTeams.ToDictionary(kv => kv.Key, kv => kv.Value.Game, StringComparer.OrdinalIgnoreCase);
        var matched = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var result = ChannelGrouper.Group(channels, games, _representatives, sticky, matched);
        foreach (var (member, game) in matched)
        {
            _stickyTeams[member] = (game, now.AddHours(12));
        }

        _representatives = result.Channels.Where(c => c.Candidates.Count > 1).Select(c => c.Id).ToHashSet(StringComparer.OrdinalIgnoreCase);
        return result;
    }

    public List<ISourceAdapter> BuildAdapters()
    {
        return (Plugin.Instance?.Configuration.Sources ?? new List<SourceDefinition>())
            .Where(s => s.Enabled)
            .Select(BuildAdapter)
            .ToList();
    }

    private ISourceAdapter BuildAdapter(SourceDefinition def)
    {
        return def.Kind switch
        {
            SourceKind.Direct => new DirectSourceAdapter(def, _httpClientFactory, _logger),
            SourceKind.Web => new WebSourceAdapter(def, _httpClientFactory, _logger, _browser),
            _ => new M3uSourceAdapter(def, _httpClientFactory, _logger)
        };
    }

    /// <summary>Programmes are keyed by tvg-id; channels without one fall back to their source channel id.</summary>
    private string? ProgrammeKey(SourceChannel ch)
        => !string.IsNullOrEmpty(ch.TvgId) ? ch.TvgId : null;

    private class Snapshot
    {
        public IReadOnlyList<SourceChannel> Channels { get; set; } = new List<SourceChannel>();

        public Dictionary<string, SourceChannel> ById { get; set; } = new(StringComparer.OrdinalIgnoreCase);

        public Dictionary<string, SourceChannel> ByLegacyId { get; } = new(StringComparer.OrdinalIgnoreCase);

        public Dictionary<string, string> Aliases { get; set; } = new(StringComparer.OrdinalIgnoreCase);

        public Dictionary<string, List<Programme>> Programmes { get; set; } = new(StringComparer.OrdinalIgnoreCase);

        public Dictionary<string, string> Errors { get; } = new(StringComparer.OrdinalIgnoreCase);

        public DateTimeOffset LoadedAt { get; set; } = DateTimeOffset.MinValue;
    }
}
