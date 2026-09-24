using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Services;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Tally.Api;

/// <summary>Game backdrop art (see <see cref="GameArtService"/>). Anonymous like channel cards: image loaders
/// request without credentials, and the art shows only two team logos.</summary>
[ApiController]
[Route("JellyTV/Backdrop")]
public class BackdropController : ControllerBase
{
    private readonly GameArtService _art;

    public BackdropController(GameArtService art)
    {
        _art = art;
    }

    [HttpGet("{gameId}.png")]
    public async Task<IActionResult> Get(string gameId, CancellationToken cancellationToken)
    {
        // Never 404: apps hold on to backdrop URLs after a game leaves the board. Draw a plain frame instead.
        var png = await _art.RenderAsync(gameId, cancellationToken).ConfigureAwait(false);
        Response.Headers.CacheControl = "public, max-age=86400";
        return File(png, "image/png");
    }
}
