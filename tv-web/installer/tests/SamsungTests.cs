using System.Formats.Asn1;
using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using Tally.SamsungInstaller.Certificates;
using Tally.SamsungInstaller.Samsung;
using Xunit;

namespace Tally.SamsungInstaller.Tests;

/// <summary>
/// The Samsung account and certificate requests, against what the community tools and Samsung's own scripts send
/// and receive (Apps2Samsung TizenCertificateService.cs / SamsungLoginService.cs, MIT; Samsung tizen-agent-skills
/// samsung-auth.js / samsung-api.js / samsung-cert.js; Tizen Studio's Samsung Certificate Extension 2.0.75), since no
/// Samsung account was available to try them for real.
/// </summary>
public class SamsungTests
{
    [Fact]
    public void SignInAddressIsSamsungsRegisteredOne()
    {
        var url = new Uri(SamsungLogin.SignInUrl);
        Assert.Equal("account.samsung.com", url.Host);
        Assert.Equal("/accounts/be1dce529476c1a6d407c4c7578c31bd/signInGate", url.AbsolutePath);
        var q = SamsungLogin.ParseForm(url.Query.TrimStart('?'));
        Assert.Equal("v285zxnl3h", q["clientId"]);
        Assert.Equal("http://localhost:4794/signin/callback", q["redirect_uri"]);
        Assert.Equal("TOKEN", q["tokenType"]);
        Assert.Equal("accountcheckdogeneratedstatetext", q["state"]);
    }

    private const string TokenJson =
        "{\"access_token\":\"AbC+dEf/gh==\",\"token_type\":\"bearer\",\"access_token_expires_in\":\"7200\"," +
        "\"refresh_token\":\"r\",\"refresh_token_expires_in\":1209600,\"userId\":\"xyz123\",\"client_id\":\"v285zxnl3h\"," +
        "\"inputEmailID\":\"someone@example.com\",\"close\":true,\"closedAction\":\"signInSuccess\",\"state\":\"accountcheckdogeneratedstatetext\"}";

    [Fact]
    public void ReadsThePostedCallback()
    {
        // the form Samsung's page posts: code = URL-encoded JSON; the token holds '+', '/' and '='
        var body = "code=" + WebUtility.UrlEncode(TokenJson) + "&state=accountcheckdogeneratedstatetext";
        var token = SamsungLogin.ParseCallback("POST", "/signin/callback", body);
        Assert.Equal("AbC+dEf/gh==", token.AccessToken);
        Assert.Equal("xyz123", token.UserId);
        Assert.Equal("someone@example.com", token.Email);
    }

    [Fact]
    public void ReadsTheCallbackFromTheQuery()
    {
        var token = SamsungLogin.ParseCallback("GET", "/signin/callback?code=" + Uri.EscapeDataString(TokenJson), "");
        Assert.Equal("xyz123", token.UserId);
    }

    [Fact]
    public void RefusesAnotherState()
    {
        var body = "code=" + WebUtility.UrlEncode(TokenJson) + "&state=other";
        Assert.Throws<SamsungException>(() => SamsungLogin.ParseCallback("POST", "/signin/callback", body));
        Assert.Throws<SamsungException>(() => SamsungLogin.ParseCallback("POST", "/signin/callback", "code=%7B%7D"));
        Assert.Throws<SamsungException>(() => SamsungLogin.ParseCallback("POST", "/signin/callback", "nothing=1"));
    }

    [Fact]
    public async Task ServesTheCallbackOverHttp()
    {
        var port = FreePort();
        var opened = new List<string>();
        var signIn = SamsungLogin.SignInAsync(opened.Add, TimeSpan.FromSeconds(20), CancellationToken.None, port);
        using var http = new HttpClient();
        // a browser asking for something else first gets a page, not a sign-in
        var other = await http.GetAsync($"http://localhost:{port}/favicon.ico");
        Assert.Equal(HttpStatusCode.NotFound, other.StatusCode);
        var response = await http.PostAsync($"http://localhost:{port}/signin/callback",
            new StringContent("code=" + WebUtility.UrlEncode(TokenJson) + "&state=accountcheckdogeneratedstatetext", Encoding.UTF8,
                "application/x-www-form-urlencoded"));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Contains("Signed in", await response.Content.ReadAsStringAsync(), StringComparison.Ordinal);
        var token = await signIn;
        Assert.Equal("xyz123", token.UserId);
        Assert.Equal(SamsungLogin.SignInUrl, Assert.Single(opened));
    }

    [Fact]
    public void DistributorCsrListsTheTvs()
    {
        using var key = RSA.Create(2048);
        var subject = new X500DistinguishedNameBuilder();
        subject.AddEmailAddress("someone@example.com");
        subject.AddCommonName("TizenSDK");
        var pem = CertificateTools.CreateCsrPem(key, subject.Build(), ["DUID0000000001", "DUID0000000002"]);
        var request = CertificateRequest.LoadSigningRequestPem(pem, HashAlgorithmName.SHA512,
            CertificateRequestLoadOptions.UnsafeLoadCertificateExtensions);
        Assert.Contains("CN=TizenSDK", request.SubjectName.Name, StringComparison.Ordinal);
        var san = Assert.Single(request.CertificateExtensions, e => e.Oid!.Value == "2.5.29.17");
        Assert.False(san.Critical);
        var reader = new AsnReader(san.RawData, AsnEncodingRules.DER).ReadSequence();
        var names = new List<string>();
        while (reader.HasData)
        {
            names.Add(reader.ReadCharacterString(UniversalTagNumber.IA5String, new Asn1Tag(TagClass.ContextSpecific, 6)));
        }

        Assert.Equal(["URN:tizen:packageid=", "URN:tizen:deviceid=DUID0000000001", "URN:tizen:deviceid=DUID0000000002"], names);
        // SHA-512 with RSA, as Tizen Studio's CSRGeneratorForTizen
        var der = Convert.FromBase64String(string.Concat(pem.Split('\n').Where(l => !l.StartsWith("-----", StringComparison.Ordinal))));
        var outer = new AsnReader(der, AsnEncodingRules.DER).ReadSequence();
        outer.ReadEncodedValue();
        Assert.Equal("1.2.840.113549.1.1.13", outer.ReadSequence().ReadObjectIdentifier());
    }

    [Fact]
    public async Task RequestsTheAuthorCertificate()
    {
        var (ca, caKey) = FakeCa("Samsung VD Author CA");
        var handler = new RecordingHandler(req => IssueFrom(req, ca, caKey));
        var service = new SamsungCertificateService(new HttpClient(handler));
        using var key = RSA.Create(2048);
        var csr = CertificateTools.CreateCsrPem(key, new X500DistinguishedName("CN=Tally"));
        var cert = await service.RequestAuthorAsync(Token(), csr, CancellationToken.None);
        Assert.Equal("CN=Tally", cert.Subject);

        var sent = Assert.Single(handler.Requests);
        Assert.Equal("https://svdca.samsungqbe.com/apis/v3/authors", sent.Url);
        Assert.Equal(["access_token", "user_id", "platform", "csr"], sent.Fields.Select(f => f.Name));
        Assert.Equal("AbC+dEf/gh==", sent.Field("access_token"));
        Assert.Equal("xyz123", sent.Field("user_id"));
        Assert.Equal("VD", sent.Field("platform"));
        Assert.Equal("author.csr", sent.Fields.Single(f => f.Name == "csr").FileName);
        Assert.StartsWith("-----BEGIN CERTIFICATE REQUEST-----", sent.Field("csr"), StringComparison.Ordinal);
    }

    [Fact]
    public async Task RequestsTheDistributorCertificate()
    {
        var (ca, caKey) = FakeCa("VD DEVELOPER Public CA Class");
        var handler = new RecordingHandler(req => req.Url.EndsWith("/v1/distributors", StringComparison.Ordinal)
            ? new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("<profile/>") }
            : IssueFrom(req, ca, caKey));
        var service = new SamsungCertificateService(new HttpClient(handler));
        using var key = RSA.Create(2048);
        var csr = CertificateTools.CreateCsrPem(key, new X500DistinguishedName("CN=TizenSDK"), ["DUID0000000001"]);
        var cert = await service.RequestDistributorAsync(Token(), csr, CancellationToken.None);
        Assert.Equal(["DUID0000000001"], CertificateTools.DeviceIds(cert));

        Assert.Equal(["https://svdca.samsungqbe.com/apis/v1/distributors", "https://svdca.samsungqbe.com/apis/v3/distributors"],
            handler.Requests.Select(r => r.Url));
        foreach (var r in handler.Requests)
        {
            Assert.Equal(["access_token", "user_id", "privilege_level", "developer_type", "platform", "csr"], r.Fields.Select(f => f.Name));
            Assert.Equal("Public", r.Field("privilege_level"));
            Assert.Equal("Individual", r.Field("developer_type"));
            Assert.Equal("distributor.csr", r.Fields.Single(f => f.Name == "csr").FileName);
        }
    }

    [Theory]
    [InlineData(401, "{\"error\":{\"status\":401,\"code\":\"303\",\"description\":\"email address is required\"}}", "needs an email address")]
    [InlineData(401, "{\"error\":{\"status\":401,\"code\":\"301\",\"description\":\"Either userid or accesstoken is incorrect.\"}}", "Sign in again")]
    [InlineData(403, "", "too many certificate requests")]
    [InlineData(400, "{\"error\":{\"status\":400,\"code\":\"201\",\"description\":\"Userid or accesstoken token is required.\"}}", "Userid or accesstoken token is required.")]
    [InlineData(500, "oops", "answered 500")]
    public async Task ExplainsServiceErrors(int status, string body, string advice)
    {
        var handler = new RecordingHandler(_ => new HttpResponseMessage((HttpStatusCode)status) { Content = new StringContent(body) });
        var service = new SamsungCertificateService(new HttpClient(handler));
        var ex = await Assert.ThrowsAsync<SamsungServiceException>(() => service.RequestAuthorAsync(Token(), "csr", CancellationToken.None));
        Assert.Contains(advice, ex.Message, StringComparison.Ordinal);
        Assert.Equal(status, ex.Status);
    }

    [Fact]
    public void RefusesAnAnswerThatIsNotACertificate()
    {
        Assert.Throws<SamsungException>(() => SamsungCertificateService.ParseCertificate("<html>maintenance</html>"));
    }

    [Fact]
    public void FindsSamsungsCaForAnIssuedCertificate()
    {
        // the bundled CA certificates are Samsung's (subjects as in Tizen Studio's extension); a certificate that
        // names one as issuer but was not signed by it is not matched
        var cas = CertificateTools.SamsungCaCertificates();
        Assert.Equal(["Samsung VD Author CA", "VD DEVELOPER Public CA Class", "VD DEVELOPER Partner CA Class"],
            cas.Select(c => c.GetNameInfo(X509NameType.SimpleName, false)));
        var (fake, fakeKey) = FakeCa(cas[0].SubjectName);
        using var key = RSA.Create(2048);
        var request = new CertificateRequest("CN=Tally", key, HashAlgorithmName.SHA512, RSASignaturePadding.Pkcs1);
        using var forged = request.Create(fake.SubjectName, X509SignatureGenerator.CreateForRSA(fakeKey, RSASignaturePadding.Pkcs1),
            DateTimeOffset.UtcNow, DateTimeOffset.UtcNow.AddYears(1), [1, 2, 3]);
        Assert.Null(CertificateTools.FindIssuer(forged, cas));
        Assert.Same(fake, CertificateTools.FindIssuer(forged, [.. cas, fake]));
    }

    [Fact]
    public void StoreReusesSamsungCertificatesForTheirTvs()
    {
        var store = new CertificateStore(TestDirs.New(), TestTizen.Defaults);
        Assert.Null(store.SamsungFor("DUID0000000001", DateTimeOffset.UtcNow));
        var (ca, caKey) = FakeCa("Samsung VD Author CA");
        var authorKey = store.SamsungAuthorKey();
        Assert.Equal(authorKey.ExportSubjectPublicKeyInfo(), store.SamsungAuthorKey().ExportSubjectPublicKeyInfo()); // kept
        store.SaveSamsungAuthor(authorKey, Issue(ca, caKey, authorKey, "CN=Tally", null), ca);
        using var dKey = RSA.Create(2048);
        store.SaveSamsungDistributor(dKey, Issue(ca, caKey, dKey, "CN=TizenSDK", ["DUID0000000001"]), ca);
        var found = store.SamsungFor("DUID0000000001", DateTimeOffset.UtcNow);
        Assert.NotNull(found);
        Assert.Equal(["DUID0000000001"], store.SamsungDuids());
        Assert.Null(store.SamsungFor("OTHERTV0000001", DateTimeOffset.UtcNow));
        Assert.Null(store.SamsungFor("DUID0000000001", DateTimeOffset.UtcNow.AddYears(2))); // expired by then
    }

    // ---- helpers ----

    internal static SamsungToken Token() => new() { AccessToken = "AbC+dEf/gh==", UserId = "xyz123", Email = "someone@example.com" };

    private static int FreePort()
    {
        var l = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }

    internal static (X509Certificate2 Ca, RSA Key) FakeCa(string cn) => FakeCa(new X500DistinguishedName("CN=" + cn));

    internal static (X509Certificate2 Ca, RSA Key) FakeCa(X500DistinguishedName name)
    {
        var key = RSA.Create(2048);
        var req = new CertificateRequest(name, key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(true, false, 0, true));
        var cert = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(5));
        return (X509CertificateLoader.LoadCertificate(cert.RawData), key);
    }

    private static X509Certificate2 Issue(X509Certificate2 ca, RSA caKey, RSA key, string subject, IReadOnlyList<string>? duids)
    {
        var req = new CertificateRequest(subject, key, HashAlgorithmName.SHA512, RSASignaturePadding.Pkcs1);
        if (duids is not null)
        {
            req.CertificateExtensions.Add(CertificateTools.UriSubjectAltName(new[] { "URN:tizen:packageid=" }.Concat(duids.Select(d => "URN:tizen:deviceid=" + d))));
        }

        using var cert = req.Create(ca.SubjectName, X509SignatureGenerator.CreateForRSA(caKey, RSASignaturePadding.Pkcs1),
            DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(1), RandomNumberGenerator.GetBytes(8));
        return X509CertificateLoader.LoadCertificate(cert.RawData);
    }

    /// <summary>The fake service: signs the posted CSR (keeping its extensions) and answers PEM.</summary>
    private static HttpResponseMessage IssueFrom(RecordedRequest request, X509Certificate2 ca, RSA caKey)
    {
        var csr = CertificateRequest.LoadSigningRequestPem(request.Field("csr"), HashAlgorithmName.SHA512,
            CertificateRequestLoadOptions.UnsafeLoadCertificateExtensions);
        using var cert = csr.Create(ca.SubjectName, X509SignatureGenerator.CreateForRSA(caKey, RSASignaturePadding.Pkcs1),
            DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(1), RandomNumberGenerator.GetBytes(8));
        return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(cert.ExportCertificatePem()) };
    }
}

public sealed record RecordedField(string Name, string? FileName, string Value);

public sealed record RecordedRequest(string Method, string Url, IReadOnlyList<RecordedField> Fields)
{
    public string Field(string name) => Fields.Single(f => f.Name == name).Value;
}

/// <summary>An HttpMessageHandler that records multipart requests and answers with <paramref name="answer"/>.</summary>
public sealed class RecordingHandler(Func<RecordedRequest, HttpResponseMessage> answer) : HttpMessageHandler
{
    public List<RecordedRequest> Requests { get; } = [];

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var fields = new List<RecordedField>();
        if (request.Content is MultipartFormDataContent multipart)
        {
            foreach (var part in multipart)
            {
                var disposition = part.Headers.ContentDisposition!;
                fields.Add(new RecordedField(disposition.Name!.Trim('"'), disposition.FileName?.Trim('"'),
                    await part.ReadAsStringAsync(cancellationToken)));
            }
        }

        var recorded = new RecordedRequest(request.Method.Method, request.RequestUri!.ToString(), fields);
        Requests.Add(recorded);
        return answer(recorded);
    }
}
