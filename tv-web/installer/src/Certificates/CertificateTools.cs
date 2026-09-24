using System.Formats.Asn1;
using System.Reflection;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace Tally.SamsungInstaller.Certificates;

/// <summary>PEM, CSR and chain helpers shared by the Tizen and Samsung certificate paths.</summary>
public static class CertificateTools
{
    /// <summary>
    /// A certificate signing request as Samsung's certificate service expects it (what Tizen Studio's Samsung
    /// Certificate Extension sends): RSA, SHA-512, the subject, and for a distributor a subjectAltName with
    /// <c>URN:tizen:packageid=</c> (empty) and one <c>URN:tizen:deviceid=&lt;DUID&gt;</c> per TV.
    /// </summary>
    public static string CreateCsrPem(RSA key, X500DistinguishedName subject, IReadOnlyList<string>? duids = null)
    {
        var request = new CertificateRequest(subject, key, HashAlgorithmName.SHA512, RSASignaturePadding.Pkcs1);
        if (duids is not null)
        {
            var uris = new List<string> { "URN:tizen:packageid=" };
            uris.AddRange(duids.Select(d => "URN:tizen:deviceid=" + d));
            request.CertificateExtensions.Add(UriSubjectAltName(uris));
        }

        return request.CreateSigningRequestPem();
    }

    /// <summary>subjectAltName of URI names ([6] IA5String), written by hand so the URNs keep their exact case.</summary>
    internal static X509Extension UriSubjectAltName(IEnumerable<string> uris)
    {
        var writer = new AsnWriter(AsnEncodingRules.DER);
        using (writer.PushSequence())
        {
            foreach (var uri in uris)
            {
                writer.WriteCharacterString(UniversalTagNumber.IA5String, uri, new Asn1Tag(TagClass.ContextSpecific, 6));
            }
        }

        return new X509Extension("2.5.29.17", writer.Encode(), critical: false);
    }

    /// <summary>The URI names in a certificate's subjectAltName (a Samsung distributor certificate lists DUIDs).</summary>
    public static IReadOnlyList<string> SubjectAltNameUris(X509Certificate2 certificate)
    {
        var result = new List<string>();
        var ext = certificate.Extensions["2.5.29.17"];
        if (ext is null)
        {
            return result;
        }

        var reader = new AsnReader(ext.RawData, AsnEncodingRules.DER);
        var seq = reader.ReadSequence();
        while (seq.HasData)
        {
            var tag = seq.PeekTag();
            if (tag.TagClass == TagClass.ContextSpecific && tag.TagValue == 6)
            {
                result.Add(seq.ReadCharacterString(UniversalTagNumber.IA5String, tag));
            }
            else
            {
                seq.ReadEncodedValue();
            }
        }

        return result;
    }

    /// <summary>The DUIDs a distributor certificate is valid for (empty for Tizen's public distributor).</summary>
    public static IReadOnlyList<string> DeviceIds(X509Certificate2 certificate) =>
        SubjectAltNameUris(certificate)
            .Where(u => u.StartsWith("URN:tizen:deviceid=", StringComparison.OrdinalIgnoreCase))
            .Select(u => u["URN:tizen:deviceid=".Length..])
            .ToList();

    /// <summary>Every certificate in a PEM (or DER) blob, in order.</summary>
    public static List<X509Certificate2> LoadCertificates(string pemOrDer)
    {
        var list = new List<X509Certificate2>();
        var text = pemOrDer.Trim();
        if (!text.Contains("-----BEGIN", StringComparison.Ordinal))
        {
            // a bare base64 DER body
            list.Add(X509CertificateLoader.LoadCertificate(Convert.FromBase64String(text)));
            return list;
        }

        var collection = new X509Certificate2Collection();
        collection.ImportFromPem(text);
        list.AddRange(collection);
        return list;
    }

    public static string ToPem(IEnumerable<X509Certificate2> certificates) =>
        string.Concat(certificates.Select(c => c.ExportCertificatePem() + "\n"));

    /// <summary>A certificate shipped in the program (certificates/…).</summary>
    public static X509Certificate2 Embedded(string name)
    {
        var text = EmbeddedText("certificates/" + name);
        return LoadCertificates(text)[0];
    }

    public static string EmbeddedText(string name)
    {
        using var stream = typeof(CertificateTools).Assembly.GetManifestResourceStream(name)
            ?? throw new InvalidOperationException("missing resource " + name);
        using var reader = new StreamReader(stream, Encoding.UTF8);
        return reader.ReadToEnd();
    }

    public static byte[] EmbeddedBytes(Assembly assembly, string name)
    {
        using var stream = assembly.GetManifestResourceStream(name)
            ?? throw new InvalidOperationException("missing resource " + name);
        using var copy = new MemoryStream();
        stream.CopyTo(copy);
        return copy.ToArray();
    }

    /// <summary>Samsung's CA certificates, to complete the chain of a certificate the service returns.</summary>
    public static IReadOnlyList<X509Certificate2> SamsungCaCertificates() =>
    [
        Embedded("samsung/vd_tizen_dev_author_ca.pem"),
        Embedded("samsung/vd_tizen_dev_public2.pem"),
        Embedded("samsung/vd_tizen_dev_partner2.pem"),
    ];

    /// <summary>The CA certificate among <paramref name="candidates"/> that issued <paramref name="leaf"/>.</summary>
    public static X509Certificate2? FindIssuer(X509Certificate2 leaf, IEnumerable<X509Certificate2> candidates)
    {
        foreach (var ca in candidates)
        {
            if (ca.SubjectName.RawData.AsSpan().SequenceEqual(leaf.IssuerName.RawData))
            {
                if (IsSignedBy(leaf, ca))
                {
                    return ca;
                }
            }
        }

        return null;
    }

    /// <summary>Whether <paramref name="issuer"/>'s key verifies <paramref name="certificate"/>'s signature.</summary>
    public static bool IsSignedBy(X509Certificate2 certificate, X509Certificate2 issuer)
    {
        using var key = issuer.GetRSAPublicKey();
        if (key is null)
        {
            return false;
        }

        try
        {
            var reader = new AsnReader(certificate.RawData, AsnEncodingRules.DER).ReadSequence();
            var tbs = reader.ReadEncodedValue();
            var algorithm = reader.ReadSequence().ReadObjectIdentifier();
            var signature = reader.ReadBitString(out _);
            HashAlgorithmName? hash = algorithm switch
            {
                "1.2.840.113549.1.1.5" => HashAlgorithmName.SHA1,
                "1.2.840.113549.1.1.11" => HashAlgorithmName.SHA256,
                "1.2.840.113549.1.1.12" => HashAlgorithmName.SHA384,
                "1.2.840.113549.1.1.13" => HashAlgorithmName.SHA512,
                _ => null,
            };
            return hash is { } h && key.VerifyData(tbs.Span, signature, h, RSASignaturePadding.Pkcs1);
        }
        catch (Exception ex) when (ex is AsnContentException or CryptographicException)
        {
            return false;
        }
    }
}
