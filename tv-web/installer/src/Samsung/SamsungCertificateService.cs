using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;

namespace Tally.SamsungInstaller.Samsung;

/// <summary>
/// Samsung's certificate service for TV developers (<c>https://svdca.samsungqbe.com</c>, the service Tizen Studio's
/// Samsung Certificate Extension 2.0.71+ uses): an author certificate for the account, and a distributor certificate
/// that lists the TVs (DUIDs) the package may be installed on. Requests are multipart forms with the account's access
/// token and user id and the CSR as a file; answers are the signed certificate as PEM, or
/// <c>{"error":{"status","code","description"}}</c>.
/// Reimplemented from Apps2Samsung's TizenCertificateService (MIT) and Samsung's tizen-agent-skills samsung-api.js
/// (read as a specification); not tested against Samsung (no account yet).
/// </summary>
public sealed class SamsungCertificateService(HttpClient http, Uri? baseAddress = null)
{
    public static readonly Uri DefaultBase = new("https://svdca.samsungqbe.com/");

    private readonly Uri _base = baseAddress ?? DefaultBase;

    /// <summary>POST apis/v3/authors: access_token, user_id, platform=VD, csr (author.csr).</summary>
    public async Task<X509Certificate2> RequestAuthorAsync(SamsungToken token, string csrPem, CancellationToken ct)
    {
        using var form = new MultipartFormDataContent();
        form.Add(Text(token.AccessToken), "access_token");
        form.Add(Text(token.UserId), "user_id");
        form.Add(Text("VD"), "platform");
        form.Add(File(csrPem), "csr", "author.csr");
        var pem = await PostAsync("apis/v3/authors", form, ct).ConfigureAwait(false);
        return ParseCertificate(pem);
    }

    /// <summary>
    /// The distributor certificate for the DUIDs in the CSR: first POST apis/v1/distributors (the device profile, as
    /// Tizen Studio does; only older TVs use it), then POST apis/v3/distributors for the certificate. Fields:
    /// access_token, user_id, privilege_level (Public), developer_type (Individual), platform (VD), csr.
    /// </summary>
    public async Task<X509Certificate2> RequestDistributorAsync(SamsungToken token, string csrPem, CancellationToken ct,
        string privilegeLevel = "Public")
    {
        MultipartFormDataContent Form()
        {
            var form = new MultipartFormDataContent();
            form.Add(Text(token.AccessToken), "access_token");
            form.Add(Text(token.UserId), "user_id");
            form.Add(Text(privilegeLevel), "privilege_level");
            form.Add(Text("Individual"), "developer_type");
            form.Add(Text("VD"), "platform");
            form.Add(File(csrPem), "csr", "distributor.csr");
            return form;
        }

        using (var profile = Form())
        {
            try
            {
                await PostAsync("apis/v1/distributors", profile, ct).ConfigureAwait(false);
            }
            catch (SamsungServiceException ex) when (ex.Status is 404 or 405)
            {
                // the v1 step only returns device-profile.xml (for Tizen 4 and older); its absence is not fatal
            }
        }

        using var form = Form();
        var pem = await PostAsync("apis/v3/distributors", form, ct).ConfigureAwait(false);
        return ParseCertificate(pem);
    }

    private static StringContent Text(string value) => new(value, Encoding.UTF8);

    private static ByteArrayContent File(string pem)
    {
        var content = new ByteArrayContent(Encoding.ASCII.GetBytes(pem));
        content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        return content;
    }

    private async Task<string> PostAsync(string path, HttpContent content, CancellationToken ct)
    {
        HttpResponseMessage response;
        try
        {
            response = await http.PostAsync(new Uri(_base, path), content, ct).ConfigureAwait(false);
        }
        catch (HttpRequestException ex)
        {
            throw new SamsungException("Samsung's certificate service could not be reached (" + ex.Message + "). Check this PC's internet connection and try again.");
        }

        using (response)
        {
            var body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            if (response.StatusCode == HttpStatusCode.OK)
            {
                return body;
            }

            throw SamsungServiceException.From((int)response.StatusCode, body);
        }
    }

    /// <summary>The certificate in an answer: PEM (what the service sends), or bare base64 DER.</summary>
    public static X509Certificate2 ParseCertificate(string body)
    {
        try
        {
            var certs = Certificates.CertificateTools.LoadCertificates(body);
            if (certs.Count > 0)
            {
                return certs[0];
            }
        }
        catch (Exception ex) when (ex is FormatException or System.Security.Cryptography.CryptographicException)
        {
            // fall through
        }

        throw new SamsungException("Samsung's certificate service answered with something that is not a certificate.");
    }
}

/// <summary>An error answer from the certificate service, with what to do about it.</summary>
public sealed class SamsungServiceException(int status, string code, string description, string advice)
    : Exception(advice)
{
    public int Status { get; } = status;
    public string Code { get; } = code;
    public string Description { get; } = description;

    public static SamsungServiceException From(int status, string body)
    {
        string code = "", description = "";
        try
        {
            using var doc = JsonDocument.Parse(body);
            var error = doc.RootElement.TryGetProperty("error", out var e) ? e : doc.RootElement;
            code = error.TryGetProperty("code", out var c) ? c.ToString() : "";
            description = error.TryGetProperty("description", out var d) ? d.GetString() ?? "" : "";
        }
        catch (JsonException)
        {
            description = body.Length > 200 ? body[..200] : body;
        }

        var advice = (status, code) switch
        {
            (401, "303") => "Your Samsung account needs an email address before Samsung issues certificates. Add one at https://account.samsung.com, then run this again.",
            (401, _) => "Samsung did not accept the sign-in (it may have expired). Sign in again.",
            (403, _) => "Samsung says too many certificate requests were made. Wait an hour and try again.",
            (400, _) => "Samsung's certificate service refused the request: " + (description.Length > 0 ? description : "no reason given") + ".",
            _ => $"Samsung's certificate service answered {status}" + (description.Length > 0 ? ": " + description : "") + ". Try again later.",
        };
        return new SamsungServiceException(status, code, description, advice);
    }
}
