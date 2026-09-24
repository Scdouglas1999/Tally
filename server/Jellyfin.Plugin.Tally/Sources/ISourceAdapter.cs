using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Models;

namespace Jellyfin.Plugin.Tally.Sources;

public class SourceSnapshot
{
    public string SourceName { get; set; } = string.Empty;

    public IReadOnlyList<SourceChannel> Channels { get; set; } = new List<SourceChannel>();

    /// <summary>Programmes keyed by tvg-id (as declared by the EPG).</summary>
    public IReadOnlyDictionary<string, List<Programme>> ProgrammesByTvgId { get; set; }
        = new Dictionary<string, List<Programme>>();

    public string? Error { get; set; }
}

/// <summary>A pluggable provider of live channels + optional EPG data.</summary>
public interface ISourceAdapter
{
    SourceDefinition Definition { get; }

    Task<SourceSnapshot> RefreshAsync(CancellationToken cancellationToken);
}
