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

    // Each enabled source's current list (what its last crawl gave, merged with what game-driven searches added since).
    private readonly Dictionary<string, SourceState> _latest = new(StringComparer.OrdinalIgnoreCase);

    // Source → stream URL → a web stream of a game that is not over (see KeepPinned).
    private readonly Dictionary<string, Dictionary<string, Pinned>> _pins = new(StringComparer.OrdinalIgnoreCase);

    private volatile Snapshot _snapshot = new();
    private volatile HashSet<string> _crawling = new(StringComparer.Ordinal);
    private int _generalWaiting;
    private int _webRequested;
    private DateTimeOffset? _lastWebScan;

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

    /// <summary>Raised after every crawl, regular or game-driven, once its channels are in place.</summary>
    public event Action<CrawlReport>? Crawled;

    /// <summary>A kept stream (see <see cref="KeepPinned"/>) has its playlist checked at most this often.</summary>
    public static readonly TimeSpan PinCheckInterval = TimeSpan.FromMinutes(15);

    /// <summary>Spacing for game-driven search passes (and what answered before, for telling the site pushing back).</summary>
    internal SearchPacer Pacer { get; set; } = SearchPacer.Default();

    /// <summary>Tests: the clock for kept streams and the full-site scan's timing.</summary>
    internal TimeProvider Clock { get; set; } = TimeProvider.System;

    /// <summary>How often web page sources are read whole: the "Full site scan" setting, 60 to 720 minutes.</summary>
    public static TimeSpan FullScanInterval
        => TimeSpan.FromMinutes(Math.Clamp(Plugin.Instance?.Configuration.WebFullScanMinutes ?? 180, 60, 720));

    /// <summary>When web page sources are next due for their full-site scan (now when they have never had one).</summary>
    public DateTimeOffset NextWebScanAt => _lastWebScan is { } at ? at + FullScanInterval : Clock.GetUtcNow();

    /// <summary>Tests: the source list in place of the plugin's configuration.</summary>
    internal Func<IReadOnlyList<SourceDefinition>>? DefinitionsOverride { get; set; }

    /// <summary>Tests: the scoreboard's games in place of the scoreboard.</summary>
    internal Func<IReadOnlyList<Scores.GameInfo>>? GamesOverride { get; set; }

    /// <summary>Ids of the games the crawl running now looks for (empty when none runs).</summary>
    public IReadOnlyCollection<string> CrawlingGameIds => _crawling;

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

    /// <summary>
    /// A full refresh: every enabled source, web page sources' full-site scan included (on startup, on a configuration
    /// change, on a Refresh request). Each source's list is replaced by what its scan found (a failed scan keeps the
    /// last good list; streams of a game that is not over survive while their playlist answers, see
    /// <see cref="KeepPinned"/>). At most one crawl of any kind runs at a time; a call made while one runs waits for it
    /// and then runs, and calls made while one is already waiting join that one.
    /// </summary>
    public Task RefreshAsync(CancellationToken cancellationToken) => RefreshCoreAsync(web: true, cancellationToken);

    /// <summary>
    /// The regular refresh: M3U and direct sources every time; web page sources only when their full-site scan is due
    /// (<see cref="FullScanInterval"/> after the last one). Games without a stream are searched on their own schedule
    /// (<see cref="SearchAsync"/>), not here.
    /// </summary>
    public Task RefreshScheduledAsync(CancellationToken cancellationToken)
        => RefreshCoreAsync(web: Clock.GetUtcNow() >= NextWebScanAt, cancellationToken);

    private async Task RefreshCoreAsync(bool web, CancellationToken cancellationToken)
    {
        if (web)
        {
            Interlocked.Exchange(ref _webRequested, 1);
        }

        if (Interlocked.CompareExchange(ref _generalWaiting, 1, 0) != 0)
        {
            return; // one is already waiting to run after the current crawl: it covers this call
        }

        try
        {
            await _refreshLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            Interlocked.Exchange(ref _generalWaiting, 0);
        }

        try
        {
            var withWeb = Interlocked.Exchange(ref _webRequested, 0) == 1 || _lastWebScan == null;
            await CrawlLockedAsync(withWeb ? CrawlMode.Full : CrawlMode.Regular, Array.Empty<WantedGame>(), cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _refreshLock.Release();
        }
    }

    /// <summary>
    /// A game-driven search pass: web page sources only, each reading its listing page once and a few pages per game of
    /// <paramref name="wanted"/> (see <see cref="WebExtractor.SearchGamesAsync"/>), paced by <see cref="Pacer"/>. What it
    /// finds is added to the source's channels; it removes nothing, and its errors do not replace the source's. Waits
    /// for a crawl that is running.
    /// </summary>
    public async Task<CrawlReport> SearchAsync(IReadOnlyList<WantedGame> wanted, CancellationToken cancellationToken)
    {
        await _refreshLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            return await CrawlLockedAsync(CrawlMode.Pass, wanted, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _refreshLock.Release();
        }
    }

    private enum CrawlMode
    {
        /// <summary>Every source, web page sources' full-site scan included.</summary>
        Full,

        /// <summary>M3U and direct sources; web page sources keep what they have.</summary>
        Regular,

        /// <summary>A game-driven search pass on web page sources, adding to what they have.</summary>
        Pass
    }

    private async Task<CrawlReport> CrawlLockedAsync(CrawlMode mode, IReadOnlyList<WantedGame> wanted, CancellationToken cancellationToken)
    {
        var gameDriven = mode == CrawlMode.Pass;
        var report = new CrawlReport { GameDriven = gameDriven, FullSiteScan = mode == CrawlMode.Full, Wanted = wanted, StartedAt = DateTimeOffset.UtcNow };
        _crawling = wanted.Select(w => w.GameId).ToHashSet(StringComparer.Ordinal);
        try
        {
            var config = Plugin.Instance?.Configuration;
            var next = new Snapshot { LoadedAt = DateTimeOffset.UtcNow };

            var defs = (DefinitionsOverride?.Invoke() ?? config?.Sources ?? new List<SourceDefinition>())
                .Where(s => s.Enabled)
                .ToList();
            var crawled = defs.Where(d => mode switch
            {
                CrawlMode.Pass => d.Kind == SourceKind.Web,
                CrawlMode.Regular => d.Kind != SourceKind.Web,
                _ => true
            }).ToList();
            if (mode == CrawlMode.Regular && crawled.Count == 0 && defs.Count > 0)
            {
                return report; // only web page sources, and their full-site scan is not due: nothing to do
            }

            if (mode == CrawlMode.Full)
            {
                _lastWebScan = Clock.GetUtcNow();
            }

            var stats = crawled.Select(_ => new GamePassStats()).ToList();
            var tasks = crawled.Select((s, i) => gameDriven
                ? new WebSourceAdapter(s, _httpClientFactory, _logger, _browser).SearchGamesAsync(wanted, Pacer, stats[i], cancellationToken)
                : BuildAdapter(s).RefreshAsync(cancellationToken)).ToList();
            var results = new Dictionary<string, SourceSnapshot?>(StringComparer.OrdinalIgnoreCase);

            for (var i = 0; i < tasks.Count; i++)
            {
                var def = crawled[i];
                SourceSnapshot? result;
                try
                {
                    result = await tasks[i].ConfigureAwait(false);
                    report.Pages += result.PagesVisited;
                }
                catch (Exception ex) when (ex is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
                {
                    _logger.LogWarning(ex, "JellyTV: source '{Name}' refresh failed", def.Name);
                    result = null;
                    if (!gameDriven)
                    {
                        result = new SourceSnapshot { SourceName = def.Name, Error = ex.Message };
                    }
                }

                results[def.Name] = result;
            }

            if (gameDriven)
            {
                report.Add(stats);
            }

            foreach (var def in crawled)
            {
                var result = results[def.Name];
                _latest.TryGetValue(def.Name, out var prev);
                if (gameDriven)
                {
                    if (result == null)
                    {
                        continue;
                    }

                    // add what the search found; keep everything the source already had
                    var urls = result.Channels.Select(c => c.StreamUrl).ToHashSet(StringComparer.Ordinal);
                    var merged = result.Channels.Concat((prev?.Channels ?? new List<SourceChannel>()).Where(c => !urls.Contains(c.StreamUrl))).ToList();
                    _latest[def.Name] = new SourceState(merged, prev?.Programmes ?? new Dictionary<string, List<Programme>>(), prev != null ? prev.Error : result.Error);
                    if (result.Channels.Count > 0)
                    {
                        _lastGood[def.Name] = new List<SourceChannel>(merged);
                    }

                    continue;
                }

                result ??= new SourceSnapshot { SourceName = def.Name };
                List<SourceChannel> list;

                // Failed scans keep the previous channel list for that source —
                // a flaky upstream shouldn't blank out the guide.
                if (result.Channels.Count == 0 && result.Error != null
                    && _lastGood.TryGetValue(def.Name, out var stale))
                {
                    _logger.LogInformation("JellyTV: keeping {Count} last-known channels for '{Name}'", stale.Count, def.Name);
                    list = new List<SourceChannel>(stale);
                }
                else
                {
                    list = new List<SourceChannel>(result.Channels);
                    if (result.Channels.Count > 0)
                    {
                        _lastGood[def.Name] = new List<SourceChannel>(result.Channels);
                    }
                }

                var programmes = new Dictionary<string, List<Programme>>(StringComparer.OrdinalIgnoreCase);
                foreach (var kv in result.ProgrammesByTvgId)
                {
                    programmes[kv.Key] = new List<Programme>(kv.Value);
                }

                _latest[def.Name] = new SourceState(list, programmes, result.Error);
            }

            foreach (var gone in _latest.Keys.Where(k => !defs.Any(d => string.Equals(d.Name, k, StringComparison.OrdinalIgnoreCase))).ToList())
            {
                _latest.Remove(gone);
                _pins.Remove(gone);
            }

            var channels = new List<SourceChannel>();
            var programmesAll = new Dictionary<string, List<Programme>>(StringComparer.OrdinalIgnoreCase);
            foreach (var def in defs)
            {
                if (!_latest.TryGetValue(def.Name, out var state))
                {
                    continue;
                }

                channels.AddRange(state.Channels);
                if (state.Error != null)
                {
                    next.Errors[def.Name] = state.Error;
                }

                foreach (var kv in state.Programmes)
                {
                    if (!programmesAll.TryGetValue(kv.Key, out var list))
                    {
                        list = new List<Programme>();
                        programmesAll[kv.Key] = list;
                    }

                    list.AddRange(kv.Value);
                }
            }

            var games = await GamesAsync(channels, cancellationToken).ConfigureAwait(false);
            if (!gameDriven)
            {
                channels = await KeepPinned(channels, crawled, games, cancellationToken).ConfigureAwait(false);
            }

            if (channels.Any(c => c.NameFromTitle))
            {
                var (kept, dropped) = ChannelNaming.Resolve(channels, games);
                if (dropped > 0)
                {
                    _logger.LogInformation("JellyTV: dropped {Count} web streams named only by their page's title (no game on the scoreboard matches it)", dropped);
                }

                channels = kept;
            }

            // one channel per language: Spanish ones get their own name and group, other languages are left out
            var (spoken, unspoken) = StreamLanguage.Apply(channels);
            if (unspoken > 0)
            {
                _logger.LogInformation("JellyTV: left out {Count} channels in languages other than English and Spanish", unspoken);
            }

            channels = spoken;

            var fresh = results.Values.Where(r => r != null).SelectMany(r => r!.Channels).Select(c => c.StreamUrl).ToHashSet(StringComparer.Ordinal);
            Pin(channels, defs, games, next.LoadedAt, fresh);

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

            next.Programmes = programmesAll;
            _snapshot = next;

            var merged2 = channels.Where(c => c.Candidates.Count > 1).ToList();
            _logger.LogInformation("JellyTV: loaded {Count} channels, {Epg} EPG feeds", channels.Count, programmesAll.Count);
            if (merged2.Count > 0)
            {
                _logger.LogInformation("JellyTV: {Count} channels have several streams: {List}", merged2.Count,
                    string.Join("; ", merged2.Take(12).Select(c => $"{c.Name} ×{c.Candidates.Count}")));
            }

            try
            {
                Refreshed?.Invoke(channels);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "JellyTV: refresh listener failed");
            }

            report.FinishedAt = DateTimeOffset.UtcNow;
            try
            {
                Crawled?.Invoke(report);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "JellyTV: crawl listener failed");
            }

            return report;
        }
        finally
        {
            _crawling = new HashSet<string>(StringComparer.Ordinal);
        }
    }

    /// <summary>
    /// Web streams of a game that is not over, found by any crawl, survive the full-site scans that no longer reach
    /// their page, until the game ends (or 3 hours after its expected end when it leaves the scoreboard). A kept stream's
    /// playlist is checked at most every <see cref="PinCheckInterval"/> (the proxy checks it again when someone plays
    /// it); one that no longer answers is let go.
    /// </summary>
    private async Task<List<SourceChannel>> KeepPinned(List<SourceChannel> channels, IReadOnlyList<SourceDefinition> crawled, IReadOnlyList<Scores.GameInfo>? games, CancellationToken ct)
    {
        var now = Clock.GetUtcNow();
        var byId = games?.ToDictionary(g => g.Id, StringComparer.Ordinal);
        var present = channels.Select(c => c.StreamUrl).ToHashSet(StringComparer.Ordinal);
        var keep = new List<(string Source, Pinned Pin)>();
        var check = new List<(string Source, Pinned Pin)>();
        foreach (var def in crawled)
        {
            if (!_pins.TryGetValue(def.Name, out var pins))
            {
                continue;
            }

            foreach (var (url, pin) in pins.ToList())
            {
                var game = byId != null && byId.TryGetValue(pin.GameId, out var g) ? g : null;
                if ((game != null && game.State == "post") || (game == null && now > pin.Until))
                {
                    pins.Remove(url);
                }
                else if (!present.Contains(url))
                {
                    (now - pin.CheckedAt < PinCheckInterval ? keep : check).Add((def.Name, pin));
                }
            }
        }

        if (check.Count == 0 && keep.Count == 0)
        {
            return channels;
        }

        var http = _httpClientFactory.CreateClient("jellytv");
        using var gate = new SemaphoreSlim(2);
        var alive = await Task.WhenAll(check.Select(async x =>
        {
            await gate.WaitAsync(ct).ConfigureAwait(false);
            try
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                timeout.CancelAfter(TimeSpan.FromSeconds(10));
                return await WebExtractor.IsLiveAsync(http, x.Pin.Channel.StreamUrl, x.Pin.Channel.Headers, timeout.Token).ConfigureAwait(false);
            }
            finally
            {
                gate.Release();
            }
        })).ConfigureAwait(false);

        var result = new List<SourceChannel>(channels);
        var kept = 0;
        for (var i = 0; i < check.Count; i++)
        {
            var (source, pin) = check[i];
            if (!alive[i])
            {
                _pins[source].Remove(pin.Channel.StreamUrl);
                continue;
            }

            pin.CheckedAt = now;
            keep.Add((source, pin));
            kept++;
        }

        foreach (var (source, pin) in keep)
        {
            var copy = pin.Channel.ShallowCopy();
            copy.Candidates = new List<StreamCandidate>();
            copy.MergedIds = new List<string>();
            result.Add(copy);
            if (_latest.TryGetValue(source, out var state))
            {
                state.Channels.Add(copy);
            }
        }

        _logger.LogInformation(
            "JellyTV: kept {Kept} streams of games that are not over although the crawl did not reach them again ({Checked} playlists checked, {Dropped} no longer answered; the others were checked less than {Minutes} min ago)",
            keep.Count, check.Count, check.Count - kept, (int)PinCheckInterval.TotalMinutes);
        return result;
    }

    /// <summary>Remembers the web streams that name a game that is not over (see <see cref="KeepPinned"/>); <paramref name="fresh"/>:
    /// the streams this crawl found (their playlists just answered).</summary>
    private void Pin(List<SourceChannel> channels, IReadOnlyList<SourceDefinition> defs, IReadOnlyList<Scores.GameInfo>? games, DateTimeOffset now, HashSet<string> fresh)
    {
        if (games == null || games.Count == 0)
        {
            return;
        }

        var web = defs.Where(d => d.Kind == SourceKind.Web).Select(d => d.Name).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var candidates = channels.Where(c => web.Contains(c.SourceName) && !c.NameFromTitle).ToList();
        if (candidates.Count == 0)
        {
            return;
        }

        var open = games.Where(g => g.State != "post").Select(g => g.Clone()).ToList();
        var probes = candidates.Select((c, i) => new Scores.ChannelProbe(i.ToString(System.Globalization.CultureInfo.InvariantCulture), c.Name, null)).ToList();
        Scores.GameChannelMatcher.Match(open, probes);
        foreach (var g in open)
        {
            foreach (var gc in g.Channels.Where(x => x.Kind == "teams"))
            {
                var c = candidates[int.Parse(gc.Id, System.Globalization.CultureInfo.InvariantCulture)];
                if (!_pins.TryGetValue(c.SourceName, out var pins))
                {
                    pins = new Dictionary<string, Pinned>(StringComparer.Ordinal);
                    _pins[c.SourceName] = pins;
                }

                var until = Scores.GameSchedule.ExpectedEnd(g, now).AddHours(3);
                var copy = c.ShallowCopy();
                copy.Candidates = new List<StreamCandidate>();
                copy.MergedIds = new List<string>();
                // found by this crawl, so its playlist answered just now (a crawl keeps only live ones)
                var checkedAt = !fresh.Contains(c.StreamUrl) && pins.TryGetValue(c.StreamUrl, out var old) ? old.CheckedAt : Clock.GetUtcNow();
                pins[c.StreamUrl] = new Pinned(copy, g.Id, until) { CheckedAt = checkedAt };
            }
        }
    }

    /// <summary>Today's games, for naming and grouping; null without a scoreboard.</summary>
    private async Task<IReadOnlyList<Scores.GameInfo>?> GamesAsync(List<SourceChannel> channels, CancellationToken ct)
    {
        if (GamesOverride != null)
        {
            return GamesOverride().Select(g => g.Clone()).ToList();
        }

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

    private sealed record SourceState(List<SourceChannel> Channels, Dictionary<string, List<Programme>> Programmes, string? Error);

    private sealed record Pinned(SourceChannel Channel, string GameId, DateTimeOffset Until)
    {
        /// <summary>When its playlist last answered (a crawl found it, or <see cref="KeepPinned"/> checked it).</summary>
        public DateTimeOffset CheckedAt { get; set; }
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

/// <summary>What one crawl did: for the game-driven search's log line and its schedule.</summary>
public sealed class CrawlReport
{
    public bool GameDriven { get; set; }

    /// <summary>A full refresh: web page sources were read whole.</summary>
    public bool FullSiteScan { get; set; }

    public IReadOnlyList<WantedGame> Wanted { get; set; } = Array.Empty<WantedGame>();

    public DateTimeOffset StartedAt { get; set; }

    public DateTimeOffset FinishedAt { get; set; }

    /// <summary>Pages web page sources read.</summary>
    public int Pages { get; set; }

    /// <summary>Search passes: listing pages read (one per web page source).</summary>
    public int ListingReads { get; set; }

    /// <summary>Search passes: pages read per game (by game id), listings not included.</summary>
    public Dictionary<string, int> PagesPerGame { get; } = new(StringComparer.Ordinal);

    /// <summary>Search passes: every request made, pages and playlist checks alike.</summary>
    public int Requests { get; set; }

    /// <summary>Search passes: requests that timed out or could not connect.</summary>
    public int Failures { get; set; }

    /// <summary>Search passes: the most requests under way at once on one source.</summary>
    public int MaxInFlight { get; set; }

    /// <summary>Search passes: why a source's site pushed back (the pass stopped there); null when none did.</summary>
    public string? PushedBack { get; set; }

    internal void Add(IEnumerable<GamePassStats> sources)
    {
        foreach (var s in sources)
        {
            ListingReads += s.ListingReads;
            Requests += s.Requests;
            Failures += s.Failures;
            MaxInFlight = Math.Max(MaxInFlight, s.MaxInFlight);
            PushedBack ??= s.PushedBack;
            foreach (var (id, n) in s.PagesPerGame)
            {
                PagesPerGame[id] = PagesPerGame.GetValueOrDefault(id) + n;
            }
        }
    }
}
