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
/// Adds a few tags to jellyfin-web's index.html as it is served. Two independent features share it:
/// the Live TV takeover (Web/inject.js mounts Tally over the Live TV route) and the Tally look for the whole
/// web client (Web/web-look.css + Web/web-look.js: theme, branding, the Sports drawer entry). Nothing on disk is
/// modified — uninstall the plugin, or switch both options off, and the web client is exactly as shipped.
/// </summary>
public sealed class IndexInjectionMiddleware
{
    public const string Marker = "data-jellytv-inject";

    // Relative on purpose: index.html lives at {baseUrl}/web/, the plugin at {baseUrl}/JellyTV/.
    private const string Assets = "../JellyTV/Assets/";
    private const string TakeoverTag = "<script " + Marker + " defer src=\"" + Assets + "inject.js\"></script>";

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

    /// <summary>The Live TV takeover only (the plugin's original injection).</summary>
    public static string Inject(string html) => Inject(html, liveTv: true, look: false);

    /// <summary>
    /// Adds what the enabled features need; idempotent. The takeover is one deferred script before
    /// &lt;/body&gt;. The look adds, in &lt;head&gt;, its stylesheet (early, so the first paint is already Tally) and
    /// the Tally icon, renames the document "Tally", and loads web-look.js before &lt;/body&gt;; its script tag says
    /// whether the takeover is on, because the Sports entry it adds opens the takeover. The &lt;html&gt; element gets
    /// data-tally-look, the attribute the stylesheet's rules are scoped to.
    /// </summary>
    public static string Inject(string html, bool liveTv, bool look)
    {
        if ((!liveTv && !look) || html.Contains(Marker, StringComparison.Ordinal))
        {
            return html;
        }

        var body = new StringBuilder();
        if (look)
        {
            html = InsertBeforeHeadEnd(
                html,
                "<link " + Marker + " data-tally-look rel=\"stylesheet\" href=\"" + Assets + "web-look.css\">"
                + "<link " + Marker + " data-tally-look rel=\"icon\" type=\"image/svg+xml\" href=\"" + Assets + "tally-icon.svg\">"
                + Preload("plex-sans.woff2") + Preload("plex-mono-500.woff2"));
            html = MarkHtmlElement(html);
            html = ReplaceFirst(html, "<title>Jellyfin</title>", "<title>Tally</title>");
            html = ReplaceFirst(html, "content=\"#202020\"", "content=\"#0e0f0e\"");   // theme-color: the browser chrome
            body.Append("<script ").Append(Marker).Append(" data-tally-look data-sports=\"").Append(liveTv ? "1" : "0")
                .Append("\" defer src=\"").Append(Assets).Append("web-look.js\"></script>");
        }

        if (liveTv)
        {
            body.Append(TakeoverTag);
        }

        var at = html.LastIndexOf("</body>", StringComparison.OrdinalIgnoreCase);
        return at < 0 ? html + body : html.Insert(at, body.ToString());
    }

    /// <summary>&lt;html data-tally-look&gt;: every rule of web-look.css hangs off this attribute, so the look is present
    /// from the first paint and outranks jellyfin-web's own theme files, which load after it.</summary>
    private static string MarkHtmlElement(string html)
    {
        var at = html.IndexOf("<html", StringComparison.OrdinalIgnoreCase);
        while (at >= 0 && at + 5 < html.Length && !(char.IsWhiteSpace(html[at + 5]) || html[at + 5] == '>'))
        {
            at = html.IndexOf("<html", at + 5, StringComparison.OrdinalIgnoreCase);
        }

        return at < 0 || at + 5 >= html.Length ? html : html.Insert(at + 5, " data-tally-look");
    }

    // the two faces nearly every screen uses, fetched with the stylesheet rather than after it
    private static string Preload(string font)
        => "<link " + Marker + " data-tally-look rel=\"preload\" as=\"font\" type=\"font/woff2\" crossorigin href=\"" + Assets + font + "\">";

    private static string InsertBeforeHeadEnd(string html, string tags)
    {
        var at = html.IndexOf("</head>", StringComparison.OrdinalIgnoreCase);
        return at < 0 ? tags + html : html.Insert(at, tags);
    }

    private static string ReplaceFirst(string html, string find, string with)
    {
        var at = html.IndexOf(find, StringComparison.Ordinal);
        return at < 0 ? html : string.Concat(html.AsSpan(0, at), with, html.AsSpan(at + find.Length));
    }

    public async Task InvokeAsync(HttpContext context)
    {
        var config = Plugin.Instance?.Configuration;
        var liveTv = config?.ReplaceLiveTv ?? false;
        var look = config?.WebLook ?? false;
        if (!(liveTv || look) || !IsIndexRequest(context.Request.Method, context.Request.Path.Value))
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
            var html = Inject(Encoding.UTF8.GetString(buffer.GetBuffer(), 0, (int)buffer.Length), liveTv, look);
            var bytes = Encoding.UTF8.GetBytes(html);
            context.Response.Headers.Remove("ETag");
            context.Response.Headers.Remove("Last-Modified");
            context.Response.Headers.CacheControl = "no-cache";
            context.Response.ContentLength = bytes.Length;
            await original.WriteAsync(bytes).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            // never let the injection break the web client itself
            _logger.LogWarning(ex, "Tally: could not inject into index.html — serving it untouched");
            buffer.Position = 0;
            await buffer.CopyToAsync(original).ConfigureAwait(false);
        }
    }
}
