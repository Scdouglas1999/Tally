using System;
using System.IO;
using System.Net;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Services;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Tally.Api;

/// <summary>Getting the TV app onto a TV, for people who have never sideloaded anything. Deliberately
/// anonymous: the page is opened from a text message before anyone has signed in to anything, and
/// <c>/JellyTV/app</c> is typed into a TV with a remote. Nothing here reveals more than "a Jellyfin server
/// with JellyTV lives at this address", which the address itself already says.</summary>
[ApiController]
public class GetController : ControllerBase
{
    private readonly TvAppService _tvApp;

    public GetController(TvAppService tvApp) => _tvApp = tvApp;

    /// <summary>The install page.</summary>
    [HttpGet("/JellyTV/Get")]
    public IActionResult Page()
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("Jellyfin.Plugin.Tally.Web.get.html");
        if (stream == null)
        {
            return NotFound();
        }

        using var reader = new StreamReader(stream, Encoding.UTF8);
        var html = Render(reader.ReadToEnd(), ServerAddress(), Plugin.Instance?.Configuration.DownloaderCode);
        Response.Headers.CacheControl = "no-cache";
        return Content(html, "text/html", Encoding.UTF8);
    }

    /// <summary>The short address typed into Downloader on the TV: always the newest APK.</summary>
    [HttpGet("/JellyTV/app")]
    public async Task<IActionResult> App(CancellationToken cancellationToken)
    {
        if (!TryGetAppTarget(Plugin.Instance?.Configuration.TvAppUrl, out var target))
        {
            return NotFound();
        }

        // Hand over an APK that already knows this server, so nobody types an address with a remote.
        // If that is not possible for any reason, the plain download still installs fine.
        var stamped = await _tvApp.GetStampedAsync(target, ServerAddress(), cancellationToken).ConfigureAwait(false);
        if (stamped == null)
        {
            return Redirect(target);
        }

        Response.Headers.CacheControl = "no-store";
        return File(stamped, "application/vnd.android.package-archive", "Tally.apk");
    }

    /// <summary>Fills the page template. Everything substituted is HTML-encoded: the server address comes from
    /// the Host header, which the caller controls.</summary>
    public static string Render(string template, string server, string? downloaderCode)
    {
        var code = (downloaderCode ?? string.Empty).Trim();
        return template
            .Replace("{{SERVER}}", WebUtility.HtmlEncode(server), StringComparison.Ordinal)
            .Replace("{{APP_ADDRESS}}", WebUtility.HtmlEncode(Bare(server) + "/JellyTV/app"), StringComparison.Ordinal)
            .Replace("{{CODE}}", WebUtility.HtmlEncode(code), StringComparison.Ordinal)
            .Replace("{{HAS_CODE}}", code.Length > 0 ? "has-code" : "no-code", StringComparison.Ordinal);
    }

    /// <summary>Only ever redirect to an absolute http(s) address an admin configured.</summary>
    public static bool TryGetAppTarget(string? configured, out string target)
    {
        target = string.Empty;
        if (!Uri.TryCreate((configured ?? string.Empty).Trim(), UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttps && uri.Scheme != Uri.UriSchemeHttp))
        {
            return false;
        }

        target = uri.AbsoluteUri;
        return true;
    }

    /// <summary>QR code of the install page, for showing someone across the room.</summary>
    [HttpGet("/JellyTV/Get/qr.svg")]
    public IActionResult Qr()
    {
        Response.Headers.CacheControl = "no-cache";
        return Content(QrEncoder.ToSvg(QrEncoder.Encode(ServerAddress() + "/JellyTV/Get")), "image/svg+xml", Encoding.UTF8);
    }

    /// <summary>The address friends should use: the configured public one, else the one the request came in on.</summary>
    public static string ServerAddress(Microsoft.AspNetCore.Http.HttpRequest request)
    {
        var configured = (Plugin.Instance?.Configuration.PublicUrl ?? string.Empty).Trim().TrimEnd('/');
        return configured.Length > 0 ? configured : $"{request.Scheme}://{request.Host}{request.PathBase}".TrimEnd('/');
    }

    private string ServerAddress() => ServerAddress(Request);

    /// <summary>"http://" is what a TV assumes anyway; every character saved on a remote counts.</summary>
    public static string Bare(string address) =>
        address.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ? address["http://".Length..] : address;
}
