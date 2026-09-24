using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Tally.SamsungInstaller.Samsung;

/// <summary>What Samsung's sign-in page hands back (the JSON in the callback's <c>code</c> field).</summary>
public sealed class SamsungToken
{
    [JsonPropertyName("access_token")] public string AccessToken { get; set; } = "";
    [JsonPropertyName("userId")] public string UserId { get; set; } = "";
    [JsonPropertyName("inputEmailID")] public string? Email { get; set; }
    [JsonPropertyName("access_token_expires_in")] public JsonElement ExpiresIn { get; set; }
    [JsonPropertyName("state")] public string? State { get; set; }
}

[JsonSerializable(typeof(SamsungToken))]
internal sealed partial class SamsungJson : JsonSerializerContext;

/// <summary>
/// The Samsung account sign-in Tizen Studio's Samsung Certificate Extension uses: the browser opens Samsung's page
/// with client id <c>v285zxnl3h</c>; after sign-in the page POSTs a form to <c>http://localhost:4794/signin/callback</c>
/// whose <c>code</c> field is JSON (access_token, userId, inputEmailID, …). Only that exact redirect address is
/// registered with Samsung, so the port cannot change.
/// Reimplemented from the published behavior of Tizen Studio's extension and of Apps2Samsung (MIT) and Samsung's
/// tizen-agent-skills (samsung-auth.js); not tested against Samsung (no account yet).
/// </summary>
public static class SamsungLogin
{
    public const int CallbackPort = 4794;
    public const string CallbackPath = "/signin/callback";
    public const string ClientId = "v285zxnl3h";
    public const string State = "accountcheckdogeneratedstatetext";

    public static string SignInUrl =>
        "https://account.samsung.com/accounts/be1dce529476c1a6d407c4c7578c31bd/signInGate?locale=&clientId=" + ClientId
        + "&redirect_uri=" + Uri.EscapeDataString($"http://localhost:{CallbackPort}{CallbackPath}")
        + "&state=" + State + "&tokenType=TOKEN";

    /// <summary>
    /// Listens on localhost:4794 (IPv4 and IPv6), calls <paramref name="openBrowser"/> with the sign-in address,
    /// and returns the token from the first callback that carries one.
    /// </summary>
    public static async Task<SamsungToken> SignInAsync(Action<string> openBrowser, TimeSpan timeout, CancellationToken ct,
        int port = CallbackPort)
    {
        var listeners = new List<TcpListener>();
        foreach (var address in new[] { IPAddress.Loopback, IPAddress.IPv6Loopback })
        {
            try
            {
                var l = new TcpListener(address, port);
                l.Start();
                listeners.Add(l);
            }
            catch (SocketException)
            {
                // no IPv6, or the port is taken on one family
            }
        }

        if (listeners.Count == 0)
        {
            throw new SamsungException(
                $"Another program on this PC is using port {port}, which Samsung's sign-in needs. Close Tizen Studio or any other certificate tool and try again.");
        }

        using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limit.CancelAfter(timeout);
        try
        {
            openBrowser(SignInUrl);
            var result = new TaskCompletionSource<SamsungToken>(TaskCreationOptions.RunContinuationsAsynchronously);
            var loops = listeners.Select(l => AcceptLoopAsync(l, result, limit.Token)).ToList();
            await using (limit.Token.Register(() => result.TrySetCanceled()))
            {
                try
                {
                    return await result.Task.ConfigureAwait(false);
                }
                catch (TaskCanceledException) when (!ct.IsCancellationRequested)
                {
                    throw new SamsungException("The Samsung sign-in was not finished in time. Run this step again.");
                }
            }
        }
        finally
        {
            foreach (var l in listeners)
            {
                l.Stop();
            }
        }
    }

    private static async Task AcceptLoopAsync(TcpListener listener, TaskCompletionSource<SamsungToken> result,
        CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && !result.Task.IsCompleted)
        {
            TcpClient client;
            try
            {
                client = await listener.AcceptTcpClientAsync(ct).ConfigureAwait(false);
            }
            catch (Exception)
            {
                return;
            }

            _ = Task.Run(async () =>
            {
                using (client)
                {
                    try
                    {
                        var token = await HandleAsync(client.GetStream(), ct).ConfigureAwait(false);
                        if (token is not null)
                        {
                            result.TrySetResult(token);
                        }
                    }
                    catch (Exception)
                    {
                        // a broken request (a browser probing /favicon.ico, a closed tab): wait for the next one
                    }
                }
            }, ct);
        }
    }

    /// <summary>One HTTP request on the callback port: the token when it is the callback, else null.</summary>
    internal static async Task<SamsungToken?> HandleAsync(Stream stream, CancellationToken ct)
    {
        var request = await ReadRequestAsync(stream, ct).ConfigureAwait(false);
        SamsungToken? token = null;
        string? problem = null;
        if (request.Path.Split('?')[0] == CallbackPath)
        {
            try
            {
                token = ParseCallback(request.Method, request.Path, request.Body);
            }
            catch (SamsungException ex)
            {
                problem = ex.Message;
            }
        }

        var html = token is not null
            ? Page("Signed in", "You are signed in to your Samsung account. Go back to the Tally for Samsung window; you can close this tab.")
            : problem is not null
                ? Page("Sign-in did not work", WebUtility.HtmlEncode(problem))
                : Page("Tally for Samsung", "This page is only used for the Samsung sign-in.");
        var body = Encoding.UTF8.GetBytes(html);
        var head = $"HTTP/1.1 {(token is not null || problem is not null ? "200 OK" : "404 Not Found")}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {body.Length}\r\nConnection: close\r\n\r\n";
        await stream.WriteAsync(Encoding.ASCII.GetBytes(head), ct).ConfigureAwait(false);
        await stream.WriteAsync(body, ct).ConfigureAwait(false);
        await stream.FlushAsync(ct).ConfigureAwait(false);
        return token;
    }

    /// <summary>
    /// The token from a callback: a POSTed form (<c>code=&lt;url-encoded JSON&gt;&amp;state=…</c>) or, as some browsers
    /// deliver it, the same fields in the query. The raw body is split on '&amp;' and '=' before decoding, because the
    /// token itself can contain '+' and '='.
    /// </summary>
    public static SamsungToken ParseCallback(string method, string pathAndQuery, string body)
    {
        var fields = ParseForm(body);
        var q = pathAndQuery.IndexOf('?');
        if (!fields.ContainsKey("code") && q >= 0)
        {
            fields = ParseForm(pathAndQuery[(q + 1)..]);
        }

        if (!fields.TryGetValue("code", out var code) || string.IsNullOrWhiteSpace(code))
        {
            throw new SamsungException("Samsung's sign-in page did not send a sign-in code (" + method + ").");
        }

        if (fields.TryGetValue("state", out var state) && state.Length > 0 && state != State)
        {
            throw new SamsungException("The sign-in answer did not come from this sign-in (state mismatch).");
        }

        SamsungToken? token;
        try
        {
            token = JsonSerializer.Deserialize(code, SamsungJson.Default.SamsungToken);
        }
        catch (JsonException)
        {
            throw new SamsungException("Samsung's sign-in answer could not be read.");
        }

        if (token is null || string.IsNullOrEmpty(token.AccessToken) || string.IsNullOrEmpty(token.UserId))
        {
            throw new SamsungException("Samsung's sign-in answer has no access token.");
        }

        return token;
    }

    internal static Dictionary<string, string> ParseForm(string raw)
    {
        var fields = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var pair in raw.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var eq = pair.IndexOf('=');
            var key = eq < 0 ? pair : pair[..eq];
            var value = eq < 0 ? "" : pair[(eq + 1)..];
            fields[WebUtility.UrlDecode(key)] = WebUtility.UrlDecode(value);
        }

        return fields;
    }

    private static string Page(string title, string text) =>
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + title + "</title>"
        + "<style>body{background:#12110f;color:#ece6da;font:18px/1.5 system-ui,sans-serif;margin:0;display:flex;"
        + "min-height:100vh;align-items:center;justify-content:center}main{max-width:32em;padding:24px;"
        + "border-left:4px solid #f2a93b}h1{font-size:22px;letter-spacing:.08em;text-transform:uppercase}</style>"
        + "</head><body><main><h1>" + title + "</h1><p>" + text + "</p></main></body></html>";

    private sealed record HttpRequest(string Method, string Path, string Body);

    private static async Task<HttpRequest> ReadRequestAsync(Stream stream, CancellationToken ct)
    {
        var buffer = new MemoryStream();
        var chunk = new byte[8192];
        int headerEnd;
        while (true)
        {
            var n = await stream.ReadAsync(chunk, ct).ConfigureAwait(false);
            if (n == 0)
            {
                throw new IOException("closed");
            }

            buffer.Write(chunk, 0, n);
            headerEnd = IndexOf(buffer.GetBuffer().AsSpan(0, (int)buffer.Length), "\r\n\r\n"u8);
            if (headerEnd >= 0)
            {
                break;
            }

            if (buffer.Length > 64 * 1024)
            {
                throw new IOException("header too large");
            }
        }

        var all = buffer.ToArray();
        var headers = Encoding.ASCII.GetString(all, 0, headerEnd).Split("\r\n");
        var requestLine = headers[0].Split(' ');
        var length = 0;
        foreach (var h in headers.Skip(1))
        {
            var colon = h.IndexOf(':');
            if (colon > 0 && h[..colon].Trim().Equals("Content-Length", StringComparison.OrdinalIgnoreCase))
            {
                _ = int.TryParse(h[(colon + 1)..].Trim(), out length);
            }
        }

        length = Math.Clamp(length, 0, 1 << 20);
        var body = new MemoryStream();
        body.Write(all, headerEnd + 4, all.Length - headerEnd - 4);
        while (body.Length < length)
        {
            var n = await stream.ReadAsync(chunk, ct).ConfigureAwait(false);
            if (n == 0)
            {
                break;
            }

            body.Write(chunk, 0, n);
        }

        return new HttpRequest(requestLine[0], requestLine.Length > 1 ? requestLine[1] : "/",
            Encoding.UTF8.GetString(body.ToArray()));
    }

    private static int IndexOf(ReadOnlySpan<byte> haystack, ReadOnlySpan<byte> needle) => haystack.IndexOf(needle);
}

public sealed class SamsungException(string message) : Exception(message);
