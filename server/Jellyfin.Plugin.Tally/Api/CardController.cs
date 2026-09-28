using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Services;
using Jellyfin.Plugin.Tally.Sources;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Tally.Api;

/// <summary>Channel card artwork (see <see cref="CardArtService"/>). Anonymous on purpose: Jellyfin's
/// image fetcher and native clients request images without credentials, and a card reveals nothing a
/// channel name doesn't.</summary>
[ApiController]
[Route("JellyTV/Card")]
public class CardController : ControllerBase
{
    private readonly SourceManager _sourceManager;
    private readonly CardArtService _cards;

    /// <summary>The key of cards drawn under the channel's old name (<see cref="Live.RedZoneService.LegacyChannelName"/>):
    /// Jellyfin and the apps hold on to those addresses, and they now draw the channel as it is named today.</summary>
    private static readonly string LegacyWhipAroundKey = CardArtService.StableKey(Live.RedZoneService.LegacyChannelName);

    public CardController(SourceManager sourceManager, CardArtService cards)
    {
        _sourceManager = sourceManager;
        _cards = cards;
    }

    /// <summary>The channel a card address names: by its name's key (see <see cref="CardArtService.StableKey"/>), a raw
    /// channel id, or the Whip-Around channel's key from before it was renamed.</summary>
    public static SourceChannel? FindChannel(SourceManager sources, string key)
        => sources.GetChannels().FirstOrDefault(c => CardArtService.StableKey(c.Name) == key)
            ?? sources.GetChannel(key)
            ?? (key == LegacyWhipAroundKey ? sources.GetChannel(Live.RedZoneService.ChannelId) : null);

    /// <param name="key">Stable name key (see <see cref="CardArtService.StableKey"/>); a raw channel id is still accepted.</param>
    /// <param name="n">Channel name, used only when the channel no longer exists.</param>
    /// <param name="w">Width the app draws the card at; snapped up to 320/480/640/960/1280 (see <see cref="ArtRequest"/>).</param>
    /// <param name="tz">The viewer's IANA time zone for the kickoff and "as of" times; the server's zone when missing or unknown.</param>
    [HttpGet("{key}.png")]
    public async Task<IActionResult> Get(string key, [FromQuery] string? n, [FromQuery] string? w, [FromQuery] string? tz, [FromQuery] string? v, CancellationToken cancellationToken)
    {
        var width = ArtRequest.SnapWidth(w, CardArtService.Width);
        var channel = FindChannel(_sourceManager, key);

        // Never 404: Jellyfin and the apps hold on to card URLs long after a game's stream has gone, and a
        // missing image is an error in the server log plus a broken tile on the TV. Draw a plain card instead.
        var png = channel != null
            ? await _cards.RenderAsync(channel, width, ArtRequest.Zone(tz), cancellationToken, v).ConfigureAwait(false)
            : CardArtService.RenderTitle(string.IsNullOrWhiteSpace(n) ? "Tally" : (n.Length > 80 ? n[..80] : n), null);
        if (channel == null && width is { } gone)
        {
            png = ArtRequest.Scale(png, gone);
        }

        Response.Headers.CacheControl = "public, max-age=120";
        return File(png, "image/png");
    }
}
