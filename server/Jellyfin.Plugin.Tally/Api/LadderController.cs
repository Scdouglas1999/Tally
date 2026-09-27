using System;
using System.Linq;
using Jellyfin.Plugin.Tally.Live;
using Jellyfin.Plugin.Tally.Sources;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Tally.Api;

/// <summary>Admin view of the live ladder: every multi-stream channel's rungs and probes, what each watched
/// channel is playing and why it last switched, and the RedZone channel's session with its last cuts.</summary>
[ApiController]
[Route("JellyTV/Ladder")]
[Authorize(Policy = "RequiresElevation")]
public class LadderController : ControllerBase
{
    private readonly SourceManager _sources;
    private readonly LiveLadderService _ladder;
    private readonly RedZoneService _redZone;

    public LadderController(SourceManager sources, LiveLadderService ladder, RedZoneService redZone)
    {
        _redZone = redZone;
        _sources = sources;
        _ladder = ladder;
    }

    [HttpGet]
    public IActionResult Get([FromQuery] bool all = false)
    {
        var now = DateTimeOffset.UtcNow;
        var channels = _sources.GetChannels()
            .Where(c => !c.IsSynthetic)
            .Where(c => all || c.Candidates.Count > 1 || _ladder.FindSession(c.Id) != null)
            .Select(c =>
            {
                var session = _ladder.FindSession(c.Id);
                var ranked = _ladder.RankedTiers(c);
                return new
                {
                    id = c.Id,
                    name = c.Name,
                    merged = c.MergedIds,
                    stream = _ladder.Describe(c),
                    candidates = LiveLadderService.Candidates(c).Select((x, i) => new
                    {
                        index = i + 1,
                        name = x.Name,
                        host = Uri.TryCreate(x.Url, UriKind.Absolute, out var u) ? u.Host : string.Empty,
                        probe = _ladder.Probe(x.Url) is { } p ? new
                        {
                            ageSeconds = (int)(now - p.At).TotalSeconds,
                            summary = LiveLadderService.Summary(p),
                            cadence = p.Cadence is { } cad ? LiveSession.CadenceJson(cad) : null
                        } : null
                    }),
                    rungs = ranked.Select(t => new
                    {
                        key = t.Key,
                        label = t.Label,
                        candidate = t.Candidate + 1,
                        mbps = Math.Round(t.Bitrate / 1e6, 2),
                        healthy = LadderRanking.Healthy(t, _ladder.Probe(t.CandidateKey), now, SwitchPolicy.ProbeMaxAgeForSwitch)
                    }),
                    session = session == null ? null : new
                    {
                        running = session.Running,
                        passthrough = session.Passthrough,
                        startedAt = session.StartedAt,
                        lastRequest = session.LastTouched,
                        current = session.CurrentTier?.Key,
                        throughputMbps = session.Throughput is { } bps ? Math.Round(bps / 1e6, 2) : (double?)null,
                        nextSequence = session.Window.NextSequence,
                        switches = session.History,
                        diagnostics = session.Running && !session.Passthrough ? session.Diagnostics() : null
                    }
                };
            });

        return Ok(new { serverTime = now, enabled = LiveLadderService.Enabled, continuous = _ladder.ContinuousMode, channels, redZone = _redZone.Diagnostics() });
    }
}
