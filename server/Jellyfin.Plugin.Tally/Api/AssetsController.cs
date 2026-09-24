using System.Collections.Generic;
using System.IO;
using System.Reflection;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Tally.Api;

/// <summary>Serves the embedded web UI assets. Deliberately anonymous:
/// these are static files needed by &lt;script&gt;/&lt;link&gt; tags that cannot send auth headers.</summary>
[ApiController]
[Route("JellyTV/Assets")]
public class AssetsController : ControllerBase
{
    private static readonly Dictionary<string, string> MimeTypes = new()
    {
        [".js"] = "application/javascript",
        [".css"] = "text/css",
        [".html"] = "text/html",
        [".png"] = "image/png",
        [".svg"] = "image/svg+xml",
        [".woff2"] = "font/woff2",
        [".json"] = "application/json"
    };

    [HttpGet("{file}")]
    public IActionResult Get(string file)
    {
        var name = Path.GetFileName(file); // strip any traversal
        var resourceName = "Jellyfin.Plugin.Tally.Web." + name;
        var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(resourceName);
        if (stream == null)
        {
            return NotFound();
        }

        var ext = Path.GetExtension(name).ToLowerInvariant();
        var mime = MimeTypes.TryGetValue(ext, out var m) ? m : "application/octet-stream";

        // no-cache: plugin updates change these assets; force revalidation so
        // browsers never serve a stale app.js/css after a redeploy.
        Response.Headers.CacheControl = "no-cache";
        return File(stream, mime);
    }
}
