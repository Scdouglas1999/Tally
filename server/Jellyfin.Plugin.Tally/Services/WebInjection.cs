using System;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Puts <see cref="IndexInjectionMiddleware"/> at the very front of Jellyfin's HTTP pipeline.
/// Plugin services are registered into the same container the web host is built from, so
/// ASP.NET Core picks this filter up like any other.
/// </summary>
public sealed class WebInjectionStartupFilter : IStartupFilter
{
    public Action<IApplicationBuilder> Configure(Action<IApplicationBuilder> next)
        => app =>
        {
            app.UseMiddleware<IndexInjectionMiddleware>();
            next(app);
        };
}

/// <summary>
/// Adds one script tag to jellyfin-web's index.html as it is served, so JellyTV can take over
/// the Live TV route for every user (Web/inject.js). Nothing on disk is modified — uninstall
/// the plugin, or switch the option off, and the web client is exactly as shipped.
/// </summary>
public sealed class IndexInjectionMiddleware
{
    public const string Marker = "data-jellytv-inject";

    // Relative on purpose: index.html lives at {baseUrl}/web/, the plugin at {baseUrl}/JellyTV/.
    private const string ScriptTag = "<script " + Marker + " defer src=\"../JellyTV/Assets/inject.js\"></script>";

    private readonly RequestDelegate _next;
    private readonly ILogger<IndexInjectionMiddleware> _logger;

    public IndexInjectionMiddleware(RequestDelegate next, ILogger<IndexInjectionMiddleware> logger)
    {
        _next = next;
        _logger = logger;
    }

    public static bool IsIndexRequest(string method, string? path)
        => HttpMethods.IsGet(method) && path != null
            && (path.EndsWith("/web/", StringComparison.OrdinalIgnoreCase)
                || path.EndsWith("/web/index.html", StringComparison.OrdinalIgnoreCase));

    /// <summary>Inserts the tag before &lt;/body&gt; (or appends it); idempotent.</summary>
    public static string Inject(string html)
    {
        if (html.Contains(Marker, StringComparison.Ordinal))
        {
            return html;
        }

        var at = html.LastIndexOf("</body>", StringComparison.OrdinalIgnoreCase);
        return at < 0 ? html + ScriptTag : html.Insert(at, ScriptTag);
    }

    public async Task InvokeAsync(HttpContext context)
    {
        var enabled = Plugin.Instance?.Configuration.ReplaceLiveTv ?? false;
        if (!enabled || !IsIndexRequest(context.Request.Method, context.Request.Path.Value))
        {
            await _next(context).ConfigureAwait(false);
            return;
        }

        // We need the plain, complete document: no compressed body, no 304 that would leave the
        // browser on a cached copy from before the plugin was installed.
        context.Request.Headers.Remove("Accept-Encoding");
        context.Request.Headers.Remove("If-None-Match");
        context.Request.Headers.Remove("If-Modified-Since");

        var original = context.Response.Body;
        using var buffer = new MemoryStream();
        context.Response.Body = buffer;
        try
        {
            await _next(context).ConfigureAwait(false);
        }
        finally
        {
            context.Response.Body = original;
        }

        buffer.Position = 0;
        var isHtml = context.Response.StatusCode == StatusCodes.Status200OK
            && (context.Response.ContentType?.StartsWith("text/html", StringComparison.OrdinalIgnoreCase) ?? false)
            && !context.Response.Headers.ContainsKey("Content-Encoding");
        if (!isHtml)
        {
            await buffer.CopyToAsync(original).ConfigureAwait(false);
            return;
        }

        try
        {
            var html = Inject(Encoding.UTF8.GetString(buffer.GetBuffer(), 0, (int)buffer.Length));
            var bytes = Encoding.UTF8.GetBytes(html);
            context.Response.Headers.Remove("ETag");
            context.Response.Headers.Remove("Last-Modified");
            context.Response.Headers.CacheControl = "no-cache";
            context.Response.ContentLength = bytes.Length;
            await original.WriteAsync(bytes).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            // never let the takeover break the web client itself
            _logger.LogWarning(ex, "JellyTV: could not inject into index.html — serving it untouched");
            buffer.Position = 0;
            await buffer.CopyToAsync(original).ConfigureAwait(false);
        }
    }
}
