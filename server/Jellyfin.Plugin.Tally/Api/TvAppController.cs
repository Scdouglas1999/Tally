using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Tally.Api;

/// <summary>
/// Serves the Tally TV web app (tv-web/, built into the plugin by build.sh) at /JellyTV/TV/. The Samsung and LG apps
/// are small installed shells that load this bundle from their server, so updating the plugin updates every TV.
/// Anonymous on purpose: the shell loads it before anyone has signed in, and it holds nothing but the app's code.
/// manifest.json (what to load) and index.html (the same app in a desktop browser) are never cached; everything
/// else has a content hash or version in its name and is cached for a year.
/// </summary>
[ApiController]
[AllowAnonymous]
public class TvAppController : ControllerBase
{
    /// <summary>Prefix of the embedded bundle's resource names (csproj: LogicalName).</summary>
    public const string ResourcePrefix = "TallyTvWeb/";

    private static readonly Lazy<Dictionary<string, string>> Resources = new(() =>
        typeof(TvAppController).Assembly.GetManifestResourceNames()
            .Where(n => n.StartsWith(ResourcePrefix, StringComparison.Ordinal))
            .ToDictionary(n => n.Substring(ResourcePrefix.Length).Replace('\\', '/'), n => n, StringComparer.Ordinal));

    private static readonly Dictionary<string, string> MimeTypes = new(StringComparer.OrdinalIgnoreCase)
    {
        [".js"] = "application/javascript",
        [".css"] = "text/css",
        [".html"] = "text/html",
        [".json"] = "application/json",
        [".ttf"] = "font/ttf",
        [".woff2"] = "font/woff2",
        [".png"] = "image/png",
        [".svg"] = "image/svg+xml",
        [".txt"] = "text/plain",
    };

    /// <summary>The content type and Cache-Control for a bundle file.</summary>
    public static (string ContentType, string CacheControl) Describe(string path)
    {
        var name = Path.GetFileName(path);
        var mime = MimeTypes.TryGetValue(Path.GetExtension(name), out var m) ? m : "application/octet-stream";
        var cache = name is "manifest.json" or "index.html" || name.EndsWith(".txt", StringComparison.Ordinal)
            ? "no-cache"
            : "public, max-age=31536000, immutable";
        return (mime, cache);
    }

    /// <summary>The bundle path a request names, or null for anything outside the bundle (traversal, absolute).</summary>
    public static string? Normalize(string? path)
    {
        if (string.IsNullOrEmpty(path))
        {
            return "index.html";
        }

        var parts = path.Replace('\\', '/').Split('/', StringSplitOptions.RemoveEmptyEntries);
        return parts.Length == 0 || parts.Any(p => p is "." or "..") ? null : string.Join('/', parts);
    }

    [HttpGet("JellyTV/TV")]
    public IActionResult Root() => Redirect(Request.PathBase + "/JellyTV/TV/index.html");

    [HttpGet("JellyTV/TV/{**path}")]
    public IActionResult Get(string? path)
    {
        var name = Normalize(path);
        if (name == null || !Resources.Value.TryGetValue(name, out var resource))
        {
            return NotFound();
        }

        var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(resource);
        if (stream == null)
        {
            return NotFound();
        }

        var (mime, cache) = Describe(name);
        Response.Headers.CacheControl = cache;
        // fonts are fetched cross-origin by the TV shells (a file:// page): allow it explicitly, whatever the
        // server's CORS settings
        Response.Headers.AccessControlAllowOrigin = "*";
        return File(stream, mime);
    }
}
