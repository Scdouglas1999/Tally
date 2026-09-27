using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Sources;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Scores;

/// <summary>
/// Keeps the scoreboard's leagues in step with the sources: when channels are named after a game of a league the
/// scoreboard does not cover ("Mississippi State Bulldogs Missouri Tigers" while it covers the NFL and MLB), that
/// league is added, so the channel gets its game's card, guide entry and stream searches like any other. A league is
/// added only when a channel names both teams of one of its games by their full names; it goes again after
/// <see cref="Expiry"/> without such a channel, or when the admin removes it (Settings → Live scores). The scoreboard
/// is pull-through, so an added league costs nothing until someone looks. Checks run after a source refresh, at most
/// every <see cref="MinInterval"/> (or <see cref="QuietInterval"/> when no new channel names appeared: a game can reach its board after its channel appeared).
/// </summary>
public sealed class LeagueDetector : IHostedService
{
    public static readonly TimeSpan MinInterval = TimeSpan.FromMinutes(10);
    public static readonly TimeSpan QuietInterval = TimeSpan.FromMinutes(30);
    public static readonly TimeSpan Expiry = TimeSpan.FromDays(3);

    private readonly SourceManager _sources;
    private readonly ScoreboardService _scoreboard;
    private readonly ILogger<LeagueDetector> _logger;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly object _stateLock = new();
    private Dictionary<string, Found> _found = new(StringComparer.OrdinalIgnoreCase);
    private HashSet<string> _seenNames = new(StringComparer.OrdinalIgnoreCase);
    private DateTimeOffset _lastCheck = DateTimeOffset.MinValue;
    private CancellationTokenSource? _cts;

    public LeagueDetector(SourceManager sources, ScoreboardService scoreboard, ILogger<LeagueDetector> logger)
    {
        _sources = sources;
        _scoreboard = scoreboard;
        _logger = logger;
    }

    /// <summary>Tests: where the leagues found are kept (null: the plugin's data folder).</summary>
    internal string? StatePath { get; set; }

    /// <summary>Tests: the clock.</summary>
    internal TimeProvider Clock { get; set; } = TimeProvider.System;

    /// <summary>The leagues added because of the sources, with the channels that named their games.</summary>
    public IReadOnlyList<(string League, IReadOnlyList<string> Channels)> FromSources
    {
        get
        {
            lock (_stateLock)
            {
                return _found.Select(kv => (kv.Key, (IReadOnlyList<string>)kv.Value.Channels)).ToList();
            }
        }
    }

    private string? Path => StatePath ?? (Plugin.Instance == null ? null : System.IO.Path.Combine(Plugin.Instance.DataFolderPath, "leagues-from-sources.json"));

    public Task StartAsync(CancellationToken cancellationToken)
    {
        _cts = new CancellationTokenSource();
        Load();
        _sources.Refreshed += OnRefreshed;
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        _sources.Refreshed -= OnRefreshed;
        _cts?.Cancel();
        return Task.CompletedTask;
    }

    private void OnRefreshed(IReadOnlyList<SourceChannel> channels)
    {
        var ct = _cts?.Token ?? CancellationToken.None;
        _ = Task.Run(async () =>
        {
            try
            {
                await CheckAsync(channels, force: false, ct).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _logger.LogWarning(ex, "JellyTV scores: could not check which leagues the sources carry");
            }
        }, ct);
    }

    /// <summary>
    /// Looks at the channels no game on the scoreboard claims, and adds the known leagues (see
    /// <see cref="LeagueCatalog"/>) whose games they name. Returns the leagues added.
    /// </summary>
    public async Task<IReadOnlyList<string>> CheckAsync(IReadOnlyList<SourceChannel> channels, bool force, CancellationToken ct)
    {
        var config = Plugin.Instance?.Configuration;
        if (!(config?.ScoresEnabled ?? true) || channels.Count == 0)
        {
            return Array.Empty<string>();
        }

        if (!await _gate.WaitAsync(0, ct).ConfigureAwait(false))
        {
            return Array.Empty<string>(); // one is running: it covers this refresh
        }

        try
        {
            var now = Clock.GetUtcNow();
            var active = _scoreboard.ActiveLeagues;
            var games = await _scoreboard.GetGamesAsync(active, ct, TimeSpan.FromMinutes(5)).ConfigureAwait(false);
            var probes = channels.Select(c => new ChannelProbe(c.Id, c.Name, null)).ToList();
            GameChannelMatcher.Match(games, probes);
            var claimed = games.SelectMany(g => g.Channels.Where(x => x.Kind == "teams").Select(x => x.Id)).ToHashSet(StringComparer.OrdinalIgnoreCase);

            // the leagues already added: still carried?
            RefreshFound(games, channels, now);

            var unclaimed = channels.Where(c => !claimed.Contains(c.Id) && GameChannelMatcher.Tokens(c.Name).Count >= 2)
                .Select(c => c.Name).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
            var fresh = unclaimed.Where(n => !_seenNames.Contains(n)).ToList();
            var due = force || now - _lastCheck >= QuietInterval || (fresh.Count > 0 && now - _lastCheck >= MinInterval);
            if (unclaimed.Count == 0 || !due)
            {
                return Array.Empty<string>();
            }

            _lastCheck = now;
            _seenNames = unclaimed.ToHashSet(StringComparer.OrdinalIgnoreCase);
            var excluded = ScoreboardService.ParseList(config?.ScoreLeaguesExcluded);
            var candidates = LeagueCatalog.Known.Select(k => k.Path)
                .Where(l => !active.Contains(l, StringComparer.OrdinalIgnoreCase) && !excluded.Contains(l))
                .ToList();

            var added = new List<string>();
            foreach (var league in candidates)
            {
                List<GameInfo> board;
                try
                {
                    board = await _scoreboard.GetGamesAsync(new[] { league }, ct, TimeSpan.FromMinutes(30)).ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is not OperationCanceledException || !ct.IsCancellationRequested)
                {
                    _logger.LogDebug(ex, "JellyTV scores: {League} board unavailable", league);
                    continue;
                }

                var named = unclaimed.Where(n => board.Any(g => g.State != "post" && NamesBothByFullName(n, g))).ToList();
                if (named.Count == 0)
                {
                    continue;
                }

                lock (_stateLock)
                {
                    _found[league] = new Found { LastSeen = now, Channels = named.Take(5).ToList() };
                }

                added.Add(league);
                _logger.LogInformation("JellyTV scores: added {League} to the scoreboard: the sources carry its games ({Channels})",
                    LeagueCatalog.Label(league), string.Join("; ", named.Take(5)));
            }

            if (added.Count > 0)
            {
                Publish();
            }

            return added;
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>Keeps the leagues added before up to date: when their games are still named by a channel, and drops
    /// those that no channel has named for <see cref="Expiry"/> or that the admin removed.</summary>
    private void RefreshFound(List<GameInfo> activeGames, IReadOnlyList<SourceChannel> channels, DateTimeOffset now)
    {
        var excluded = ScoreboardService.ParseList(Plugin.Instance?.Configuration.ScoreLeaguesExcluded);
        var changed = false;
        lock (_stateLock)
        {
            foreach (var (league, found) in _found.ToList())
            {
                var named = channels.Select(c => c.Name)
                    .Where(n => activeGames.Any(g => string.Equals(g.LeaguePath, league, StringComparison.OrdinalIgnoreCase) && g.State != "post" && NamesBothByFullName(n, g)))
                    .Distinct(StringComparer.OrdinalIgnoreCase).ToList();
                if (named.Count > 0)
                {
                    found.LastSeen = now;
                    found.Channels = named.Take(5).ToList();
                    changed = true;
                }
                else if (now - found.LastSeen > Expiry || excluded.Contains(league))
                {
                    _found.Remove(league);
                    changed = true;
                    _logger.LogInformation("JellyTV scores: {League} is no longer on the scoreboard by itself: {Why}", LeagueCatalog.Label(league),
                        excluded.Contains(league) ? "removed in Settings" : "no channel has named its games for 3 days");
                }
            }
        }

        if (changed)
        {
            Publish();
        }
    }

    /// <summary>True when <paramref name="channelName"/> names both teams of <paramref name="g"/> by their full
    /// names ("Missouri Tigers", "Arsenal"): a nickname or a city alone could be another league's team.</summary>
    public static bool NamesBothByFullName(string channelName, GameInfo g)
    {
        var padded = " " + string.Join(' ', GameChannelMatcher.Tokens(channelName)) + " ";
        return NamedIn(padded, g.Away) && NamedIn(padded, g.Home);
    }

    private static bool NamedIn(string padded, GameTeam t)
    {
        var needle = " " + string.Join(' ', GameChannelMatcher.Tokens(t.Name)) + " ";
        return needle.Trim().Length >= 4 && padded.Contains(needle, StringComparison.Ordinal);
    }

    private void Publish()
    {
        lock (_stateLock)
        {
            _scoreboard.LeaguesFromSources = _found.Keys.ToList();
        }

        Save();
    }

    private void Load()
    {
        try
        {
            if (Path is { } p && File.Exists(p))
            {
                var saved = JsonSerializer.Deserialize<Dictionary<string, Found>>(File.ReadAllText(p));
                if (saved != null)
                {
                    lock (_stateLock)
                    {
                        _found = new Dictionary<string, Found>(saved.Where(kv => LeagueCatalog.Known.Any(k => k.Path == kv.Key)), StringComparer.OrdinalIgnoreCase);
                    }

                    Publish();
                }
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            _logger.LogDebug(ex, "JellyTV scores: could not read the leagues found in the sources");
        }
    }

    private void Save()
    {
        try
        {
            if (Path is { } p)
            {
                string json;
                lock (_stateLock)
                {
                    json = JsonSerializer.Serialize(_found);
                }

                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(p)!);
                File.WriteAllText(p, json);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _logger.LogDebug(ex, "JellyTV scores: could not save the leagues found in the sources");
        }
    }

    public sealed class Found
    {
        public DateTimeOffset LastSeen { get; set; }

        public List<string> Channels { get; set; } = new();
    }
}
