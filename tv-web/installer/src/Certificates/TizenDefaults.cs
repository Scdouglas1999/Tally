using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using Tally.SamsungInstaller.Signing;

namespace Tally.SamsungInstaller.Certificates;

/// <summary>
/// Tizen's public signing material, the same for every Tizen developer (Tizen Studio's certificate generator,
/// tools/certificate-generator/certificates/): the <b>Tizen Developers CA</b> (certificate + its private key, which
/// Tizen Studio uses to issue author certificates on the developer's PC) and Tizen's <b>public distributor</b>
/// ("Tizen Public Distributor Signer", Tizen Studio's default distributor for the public privilege level). Samsung
/// TVs on Tizen 5.5-6.5 accept a package signed by an author certificate from that CA plus that distributor.
/// </summary>
public sealed class TizenDefaults
{
    /// <summary>The password Tizen Studio's conf.ini gives for the CA key (PASSWD_OF_ISSUER_FOR_DEV).</summary>
    internal const string DeveloperCaPassword = "tizencertificatefordevelopercaroqkfwk";

    private TizenDefaults(X509Certificate2 developerCa, RSA developerCaKey, SigningIdentity distributor)
    {
        DeveloperCa = developerCa;
        DeveloperCaKey = developerCaKey;
        Distributor = distributor;
    }

    public X509Certificate2 DeveloperCa { get; }
    public RSA DeveloperCaKey { get; }
    public SigningIdentity Distributor { get; }

    /// <summary>The distributor .p12's password (Tizen Studio's, the same for everyone).</summary>
    internal const string DistributorPassword = "tizenpkcs12passfordsigner";

    /// <summary>
    /// From Tizen Studio's own files (the certificate generator's certificates/developer/tizen-developer-ca.cer and
    /// tizen-developer-ca-privatekey.pem, certificates/distributor/tizen-distributor-signer.p12), which this program
    /// downloads instead of carrying (<see cref="TizenSdkDownload"/>).
    /// </summary>
    public static TizenDefaults FromSdkFiles(byte[] developerCaCer, byte[] developerCaKeyPem, byte[] distributorP12)
    {
        var ca = CertificateTools.LoadCertificates(Encoding.UTF8.GetString(developerCaCer))[0];
        var caKey = LegacyPem.DecryptRsa(Encoding.UTF8.GetString(developerCaKeyPem), DeveloperCaPassword);
        var p12 = X509CertificateLoader.LoadPkcs12Collection(distributorP12, DistributorPassword, X509KeyStorageFlags.Exportable);
        var signer = p12.FirstOrDefault(c => c.HasPrivateKey)
            ?? throw new CryptographicException("the distributor file holds no key");
        var signerKey = signer.GetRSAPrivateKey() ?? throw new CryptographicException("the distributor key is not RSA");
        // a key of our own (the loaded one belongs to the certificate object)
        var key = RSA.Create();
        key.ImportPkcs8PrivateKey(signerKey.ExportPkcs8PrivateKey(), out _);
        var chain = p12.Where(c => !c.HasPrivateKey)
            .Select(c => X509CertificateLoader.LoadCertificate(c.RawData))
            .ToList();
        return new TizenDefaults(ca, caKey,
            new SigningIdentity(key, X509CertificateLoader.LoadCertificate(signer.RawData), chain));
    }

    /// <summary>
    /// A Tizen author certificate as Tizen Studio's Certificate Manager makes one (KeyCertGeneratorApi.jar,
    /// TizenKeyCertificateGenerator): issued by the Tizen Developers CA, SHA-512 with RSA, CA:FALSE (critical), key
    /// usage digitalSignature, extended key usage codeSigning, no key identifiers, and an end no later than the CA's
    /// (2027-01-01). Differences on purpose: a 2048-bit key (Tizen Studio: 1024), a random serial (Tizen Studio: the
    /// time in ms), a start one day back (a TV clock that is a little slow).
    /// <para>
    /// Why the end must not pass the CA's: Tizen's validator (cert-svc BaseValidator::preStep) checks only the
    /// signing certificate's dates against the clock; when that certificate is out of its dates it checks the whole
    /// chain at the middle of the certificate's validity instead (this is why TVs still take Tizen's public
    /// distributor signer, which expired in 2012). So a certificate that ends with the CA keeps working after the CA
    /// has expired, and one that ends later would fail from 2027 on (still in its dates, so the chain is checked
    /// now, and the CA is expired). After 2027-01-01 new certificates are therefore made for the CA's last year.
    /// A TV compares only the author's public key when Tally is updated (app-installers IsSameAuthor), so the key,
    /// not the certificate, is what must be kept.
    /// </para>
    /// </summary>
    public (RSA Key, X509Certificate2 Certificate) CreateAuthorCertificate(string name, DateTimeOffset now, RSA? existingKey = null)
    {
        var key = existingKey ?? RSA.Create(2048);
        var subject = new X500DistinguishedNameBuilder();
        // encoded O then CN, as Tizen Studio's (the builder encodes in reverse order of the calls)
        subject.AddCommonName(name);
        subject.AddOrganizationName(name);
        var request = new CertificateRequest(subject.Build(), key, HashAlgorithmName.SHA512, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, critical: true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, critical: false));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            [new Oid("1.3.6.1.5.5.7.3.3")], critical: false));

        var caEnd = new DateTimeOffset(DeveloperCa.NotAfter.ToUniversalTime(), TimeSpan.Zero);
        var notAfter = caEnd;
        var notBefore = now.AddDays(-1) < caEnd.AddDays(-2) ? now.AddDays(-1) : caEnd.AddDays(-365);
        var serial = RandomNumberGenerator.GetBytes(8);
        serial[0] &= 0x7f; // a positive INTEGER
        serial[0] |= 0x01;
        var generator = X509SignatureGenerator.CreateForRSA(DeveloperCaKey, RSASignaturePadding.Pkcs1);
        using var cert = request.Create(DeveloperCa.SubjectName, generator, notBefore, notAfter, serial);
        return (key, X509CertificateLoader.LoadCertificate(cert.RawData));
    }
}

/// <summary>OpenSSL's traditional encrypted PEM ("Proc-Type: 4,ENCRYPTED", DEK-Info AES-*-CBC or DES-EDE3-CBC).</summary>
internal static class LegacyPem
{
    public static RSA DecryptRsa(string pem, string password)
    {
        var lines = pem.Replace("\r", "", StringComparison.Ordinal).Split('\n');
        var dek = lines.FirstOrDefault(l => l.StartsWith("DEK-Info:", StringComparison.Ordinal));
        var body = string.Concat(lines.Where(l => l.Length > 0 && !l.StartsWith("-----", StringComparison.Ordinal)
            && !l.Contains(':', StringComparison.Ordinal)));
        var der = Convert.FromBase64String(body);
        var rsa = RSA.Create();
        if (dek is null)
        {
            rsa.ImportRSAPrivateKey(der, out _);
            return rsa;
        }

        var parts = dek["DEK-Info:".Length..].Trim().Split(',');
        var algorithm = parts[0].Trim().ToUpperInvariant();
        var iv = Convert.FromHexString(parts[1].Trim());
        var (keyLength, create) = algorithm switch
        {
            "AES-128-CBC" => (16, (Func<SymmetricAlgorithm>)Aes.Create),
            "AES-192-CBC" => (24, Aes.Create),
            "AES-256-CBC" => (32, Aes.Create),
            "DES-EDE3-CBC" => (24, TripleDES.Create),
            _ => throw new CryptographicException("unsupported PEM encryption " + algorithm),
        };
        var key = BytesToKey(Encoding.UTF8.GetBytes(password), iv.AsSpan(0, 8), keyLength);
        using var cipher = create();
        cipher.Key = key;
        var plain = cipher.DecryptCbc(der, iv, PaddingMode.PKCS7);
        rsa.ImportRSAPrivateKey(plain, out _);
        return rsa;
    }

    /// <summary>EVP_BytesToKey with MD5 and one round, as OpenSSL's PEM encryption uses it.</summary>
    private static byte[] BytesToKey(byte[] password, ReadOnlySpan<byte> salt, int length)
    {
        var result = new List<byte>();
        var previous = Array.Empty<byte>();
        while (result.Count < length)
        {
            var input = previous.Concat(password).Concat(salt.ToArray()).ToArray();
            previous = MD5.HashData(input);
            result.AddRange(previous);
        }

        return result.Take(length).ToArray();
    }
}
