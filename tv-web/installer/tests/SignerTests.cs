using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using Tally.SamsungInstaller.Certificates;
using Tally.SamsungInstaller.Signing;
using Tally.SamsungInstaller.Tv;
using Xunit;

namespace Tally.SamsungInstaller.Tests;

/// <summary>
/// The signer against golden packages written by Tizen Studio 6.1's <c>tizen package -t wgt</c> (September 24, 2026),
/// in fixtures/golden:
/// <list type="bullet">
/// <item>shell.wgt: the Tally shell as scripts/package-tizen.sh stages it (server http://example.invalid:8096);</item>
/// <item>names.wgt: names with spaces, + &amp; ~, a non-ASCII name, both cases, a hidden file, folders;</item>
/// <item>chars.wgt: one file per punctuation character (the Reference URI encoding).</item>
/// </list>
/// All three are signed with test-author.* (a throwaway author certificate made for these tests with
/// <c>tizen certificate</c>, issued by the public Tizen Developers CA, used for nothing else) and Tizen's public
/// distributor. Each package is unzipped, its files signed again, and both signature files compared byte for byte.
/// </summary>
public class SignerTests
{
    private static string Fixture(string name) => Path.Combine(AppContext.BaseDirectory, "fixtures", name);

    internal static SigningIdentity TestAuthor()
    {
        var key = RSA.Create();
        key.ImportFromPem(File.ReadAllText(Fixture("golden/test-author.key.pem")));
        var certs = CertificateTools.LoadCertificates(File.ReadAllText(Fixture("golden/test-author.cert.pem")));
        return new SigningIdentity(key, certs[0], certs.Skip(1).ToList());
    }

    [Theory]
    [InlineData("shell.wgt")]
    [InlineData("names.wgt")]
    [InlineData("chars.wgt")]
    public void SignsByteForByteAsTizenStudio(string golden)
    {
        var files = ShellPackage.Unzip(File.ReadAllBytes(Fixture("golden/" + golden)));
        var signed = WidgetSigner.Sign(files, TestAuthor(), TizenDefaults.Embedded().Distributor);

        Assert.Equal(Encoding.UTF8.GetString(files[WidgetSigner.AuthorSignatureFile]),
            Encoding.UTF8.GetString(signed[WidgetSigner.AuthorSignatureFile]));
        Assert.Equal(files[WidgetSigner.AuthorSignatureFile], signed[WidgetSigner.AuthorSignatureFile]);
        Assert.Equal(files[WidgetSigner.DistributorSignatureFile], signed[WidgetSigner.DistributorSignatureFile]);
        Assert.Equal(files.Keys, signed.Keys);
    }

    [Fact]
    public void EncodesUrisAsTizenStudio()
    {
        Assert.Equal("fonts%2Fplex-sans.woff2", WidgetSigner.EncodeUri("fonts/plex-sans.woff2"));
        Assert.Equal("sub%20dir%2F%C3%BC.txt", WidgetSigner.EncodeUri("sub dir/ü.txt"));
        Assert.Equal("tilde%7E(1).txt", WidgetSigner.EncodeUri("tilde~(1).txt"));
        Assert.Equal("e,%3B.txt", WidgetSigner.EncodeUri("e,;.txt"));
    }

    [Fact]
    public void RemovesOldSignaturesWhenResigning()
    {
        var files = new Dictionary<string, byte[]>
        {
            ["index.html"] = "<p>"u8.ToArray(),
            ["author-signature.xml"] = "old"u8.ToArray(),
            ["signature1.xml"] = "old"u8.ToArray(),
            ["signature2.xml"] = "old"u8.ToArray(),
            ["docs/signature1.xml"] = "kept: not at the root"u8.ToArray(),
        };
        var signed = WidgetSigner.Sign(files, TestAuthor(), TizenDefaults.Embedded().Distributor);
        Assert.Equal(["author-signature.xml", "docs/signature1.xml", "index.html", "signature1.xml"], signed.Keys);
        var author = Encoding.UTF8.GetString(signed["author-signature.xml"]);
        Assert.Contains("URI=\"docs%2Fsignature1.xml\"", author, StringComparison.Ordinal);
        Assert.DoesNotContain("URI=\"signature", author, StringComparison.Ordinal);
    }

    [Fact]
    public void TizenDefaultsLoad()
    {
        var d = TizenDefaults.Embedded();
        Assert.Equal("CN=Tizen Developers CA, OU=Tizen Association, O=Tizen Association", d.DeveloperCa.Subject);
        // the CA key is the CA certificate's key
        Assert.Equal(d.DeveloperCa.GetRSAPublicKey()!.ExportSubjectPublicKeyInfo(), d.DeveloperCaKey.ExportSubjectPublicKeyInfo());
        Assert.Contains("Tizen Public Distributor Signer", d.Distributor.Certificate.Subject, StringComparison.Ordinal);
        Assert.Contains("Tizen Public Distributor CA", Assert.Single(d.Distributor.Chain).Subject, StringComparison.Ordinal);
        Assert.Equal(d.Distributor.Certificate.GetRSAPublicKey()!.ExportSubjectPublicKeyInfo(), d.Distributor.Key.ExportSubjectPublicKeyInfo());
    }

    [Fact]
    public void AuthorCertificateAsTizenStudioMakesThem()
    {
        var d = TizenDefaults.Embedded();
        var now = new DateTimeOffset(2026, 9, 24, 12, 0, 0, TimeSpan.Zero);
        var (key, cert) = d.CreateAuthorCertificate("Tally", now);
        Assert.Equal("CN=Tally, O=Tally", cert.Subject);
        Assert.Equal(d.DeveloperCa.Subject, cert.Issuer);
        Assert.True(CertificateTools.IsSignedBy(cert, d.DeveloperCa));
        Assert.Equal("1.2.840.113549.1.1.13", cert.SignatureAlgorithm.Value); // sha512WithRSAEncryption
        Assert.Equal(new DateTime(2027, 1, 1, 0, 0, 0, DateTimeKind.Utc), cert.NotAfter.ToUniversalTime());
        Assert.Equal(now.AddDays(-1).UtcDateTime, cert.NotBefore.ToUniversalTime());
        var basic = Assert.Single(cert.Extensions.OfType<X509BasicConstraintsExtension>());
        Assert.False(basic.CertificateAuthority);
        Assert.True(basic.Critical);
        Assert.Equal(X509KeyUsageFlags.DigitalSignature, Assert.Single(cert.Extensions.OfType<X509KeyUsageExtension>()).KeyUsages);
        Assert.Equal("1.3.6.1.5.5.7.3.3", Assert.Single(Assert.Single(cert.Extensions.OfType<X509EnhancedKeyUsageExtension>()).EnhancedKeyUsages.Cast<Oid>()).Value);
        Assert.Equal(3, cert.Extensions.Count);
        Assert.Equal(2048, key.KeySize);
    }

    [Fact]
    public void AuthorCertificateAfterTheCaExpiredStaysInsideItsDates()
    {
        // cert-svc checks an out-of-date signing certificate's chain at the middle of its validity: made in 2028, it
        // must sit inside the CA's dates to keep working
        var d = TizenDefaults.Embedded();
        var (_, cert) = d.CreateAuthorCertificate("Tally", new DateTimeOffset(2028, 3, 1, 0, 0, 0, TimeSpan.Zero));
        Assert.Equal(d.DeveloperCa.NotAfter, cert.NotAfter);
        Assert.True(cert.NotBefore >= d.DeveloperCa.NotBefore);
        Assert.True(cert.NotBefore < cert.NotAfter);
    }

    [Fact]
    public void AuthorCertificateKeepsAGivenKey()
    {
        var d = TizenDefaults.Embedded();
        using var key = RSA.Create(2048);
        var (same, cert) = d.CreateAuthorCertificate("Tally", DateTimeOffset.UtcNow, key);
        Assert.Same(key, same);
        Assert.Equal(key.ExportSubjectPublicKeyInfo(), cert.GetRSAPublicKey()!.ExportSubjectPublicKeyInfo());
    }

    [Fact]
    public void SignedShellVerifiesWithOpenStandards()
    {
        // what a TV checks, redone independently: every digest, the #prop digest and both signature values
        var store = new CertificateStore(TestDirs.New());
        var wgt = ShellPackage.BuildSigned("http://192.0.2.10:8096", store.TizenAuthor(), store.TizenDistributor());
        var files = ShellPackage.Unzip(wgt);
        foreach (var sigFile in new[] { WidgetSigner.AuthorSignatureFile, WidgetSigner.DistributorSignatureFile })
        {
            var xml = Encoding.UTF8.GetString(files[sigFile]);
            var doc = new System.Xml.XmlDocument { PreserveWhitespace = true };
            doc.LoadXml(xml);
            var signed = new System.Security.Cryptography.Xml.SignedXml(doc);
            // SignedXml resolves only same-document references; check file digests by hand and the rest with it
            var ns = new System.Xml.XmlNamespaceManager(doc.NameTable);
            ns.AddNamespace("ds", "http://www.w3.org/2000/09/xmldsig#");
            foreach (System.Xml.XmlElement reference in doc.SelectNodes("//ds:Reference", ns)!)
            {
                var uri = reference.GetAttribute("URI");
                if (uri == "#prop")
                {
                    continue;
                }

                var path = Uri.UnescapeDataString(uri);
                var digest = reference.SelectSingleNode("ds:DigestValue", ns)!.InnerText.Replace("\n", "", StringComparison.Ordinal);
                Assert.Equal(Convert.ToBase64String(SHA512.HashData(files[path])), digest);
            }

            var info = doc.SelectSingleNode("//ds:SignedInfo", ns)!;
            var c14n = new System.Security.Cryptography.Xml.XmlDsigExcC14NTransform();
            var infoDoc = new System.Xml.XmlDocument { PreserveWhitespace = true };
            infoDoc.AppendChild(infoDoc.ImportNode(info, true));
            c14n.LoadInput(infoDoc);
            var canonical = ((Stream)c14n.GetOutput(typeof(Stream))).ReadAllBytes();
            var value = Convert.FromBase64String(doc.SelectSingleNode("//ds:SignatureValue", ns)!.InnerText);
            var certText = doc.SelectSingleNode("//ds:X509Certificate", ns)!.InnerText;
            using var cert = X509CertificateLoader.LoadCertificate(Convert.FromBase64String(certText));
            Assert.True(cert.GetRSAPublicKey()!.VerifyData(canonical, value, HashAlgorithmName.SHA512, RSASignaturePadding.Pkcs1));
            _ = signed;
        }

        // the stamped address and the webapis script, as package-tizen.sh writes them
        Assert.Equal("window.TALLY_SHELL_CONFIG = {\n  \"platform\": \"tizen\",\n  \"server\": \"http://192.0.2.10:8096\",\n  \"bundle\": \"\"\n};\n",
            Encoding.UTF8.GetString(files["config.js"]));
        Assert.Contains("<script src=\"$WEBAPIS/webapis/webapis.js\"></script>", Encoding.UTF8.GetString(files["index.html"]), StringComparison.Ordinal);
        Assert.Contains($"version=\"{ShellPackage.Version}\"", Encoding.UTF8.GetString(files["config.xml"]), StringComparison.Ordinal);
    }

    [Fact]
    public void ShellFilesMatchTheGoldenStage()
    {
        // the files the program carries are the ones package-tizen.sh packs (apart from the version and address)
        var golden = ShellPackage.Unzip(File.ReadAllBytes(Fixture("golden/shell.wgt")));
        var ours = ShellPackage.Files("http://example.invalid:8096");
        foreach (var name in new[] { "index.html", "fonts/OFL.txt", "fonts/plex-sans.woff2", "fonts/plex-mono-500.woff2", "icon.png" })
        {
            Assert.Equal(golden[name], ours[name]);
        }

        Assert.Equal(golden.Keys.Where(k => !WidgetSigner.IsSignatureFile(k)), ours.Keys);
        Assert.Equal(Encoding.UTF8.GetString(golden["config.xml"]).Replace("version=\"1.0.0\"", $"version=\"{ShellPackage.Version}\"", StringComparison.Ordinal),
            Encoding.UTF8.GetString(ours["config.xml"]));
    }

    [Fact]
    public void DecryptsTraditionalPem()
    {
        using var rsa = RSA.Create(1024);
        var pem = rsa.ExportEncryptedPkcs8PrivateKeyPem("x", new PbeParameters(PbeEncryptionAlgorithm.Aes128Cbc, HashAlgorithmName.SHA256, 1));
        Assert.StartsWith("-----BEGIN ENCRYPTED PRIVATE KEY", pem, StringComparison.Ordinal);
        // the traditional form (what Tizen Studio ships) is covered by TizenDefaultsLoad; an unencrypted one loads too
        var plain = rsa.ExportRSAPrivateKeyPem();
        using var loaded = LegacyPem.DecryptRsa(plain, "");
        Assert.Equal(rsa.ExportSubjectPublicKeyInfo(), loaded.ExportSubjectPublicKeyInfo());
    }
}

internal static class StreamExtensions
{
    public static byte[] ReadAllBytes(this Stream s)
    {
        using var m = new MemoryStream();
        s.CopyTo(m);
        return m.ToArray();
    }
}
