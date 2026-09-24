using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace Tally.SamsungInstaller.Signing;

/// <summary>A signing key with its certificate and the CA certificates above it (not the root).</summary>
public sealed class SigningIdentity(RSA key, X509Certificate2 certificate, IReadOnlyList<X509Certificate2> chain)
{
    public RSA Key { get; } = key;
    public X509Certificate2 Certificate { get; } = certificate;
    public IReadOnlyList<X509Certificate2> Chain { get; } = chain;
}

/// <summary>
/// Tizen widget signing without Tizen Studio: <c>author-signature.xml</c> and <c>signature1.xml</c> as the W3C
/// "XML Digital Signatures for Widgets" profile with Tizen's choices, written byte for byte as <c>tizen package</c>
/// (Tizen Studio 6.1) writes them (golden files in tests/fixtures/golden):
/// <list type="bullet">
/// <item>RSA-SHA512 over the exclusive-C14N form of SignedInfo, SHA-512 digests of every file;</item>
/// <item>references sorted by path (ordinal), the URI percent-encoded as Tizen Studio's URIEscapeUtil (it keeps
/// A-Z a-z 0-9 - _ . , ( ) ' and encodes the rest, UTF-8 for non-ASCII; the TV decodes any %XX);</item>
/// <item>a <c>#prop</c> reference (C14N 1.1 transform) to the SignatureProperties object: profile, role, empty
/// identifier;</item>
/// <item>the author signature covers every file but the signatures; the distributor signature (signature1.xml)
/// also covers author-signature.xml;</item>
/// <item>KeyInfo: the signer's certificate, then its CA certificates; base64 lines of 76 characters, LF line ends,
/// no newline at the end of the file.</item>
/// </list>
/// Since the document's structure is fixed, the canonical forms are written directly (and checked against the
/// golden files): SignedInfo gets the dsig default namespace, the Object gets it too (inclusive C14N 1.1).
/// </summary>
public static class WidgetSigner
{
    public const string AuthorSignatureFile = "author-signature.xml";
    public const string DistributorSignatureFile = "signature1.xml";

    private const string DsigNs = "http://www.w3.org/2000/09/xmldsig#";

    /// <summary>The package's files (relative paths with '/') plus the two signature files.</summary>
    public static SortedDictionary<string, byte[]> Sign(IReadOnlyDictionary<string, byte[]> files, SigningIdentity author,
        SigningIdentity distributor)
    {
        var content = new SortedDictionary<string, byte[]>(StringComparer.Ordinal);
        foreach (var (path, bytes) in files)
        {
            if (IsSignatureFile(path))
            {
                continue; // re-signing a signed package: its old signatures go
            }

            content[path] = bytes;
        }

        var authorXml = BuildSignature("AuthorSignature", "role-author", content, author);
        content[AuthorSignatureFile] = authorXml;
        var distributorXml = BuildSignature("DistributorSignature", "role-distributor", content, distributor);
        content[DistributorSignatureFile] = distributorXml;
        return content;
    }

    /// <summary>author-signature.xml and signature*.xml at the package root.</summary>
    public static bool IsSignatureFile(string path) =>
        path == AuthorSignatureFile
        || (path.StartsWith("signature", StringComparison.Ordinal) && path.EndsWith(".xml", StringComparison.Ordinal)
            && !path.Contains('/'));

    internal static byte[] BuildSignature(string id, string role, SortedDictionary<string, byte[]> files,
        SigningIdentity identity)
    {
        var signedInfo = new StringBuilder();
        signedInfo.Append("\n<CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"></CanonicalizationMethod>\n");
        signedInfo.Append("<SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#rsa-sha512\"></SignatureMethod>\n");
        foreach (var (path, bytes) in files)
        {
            signedInfo.Append("<Reference URI=\"").Append(EncodeUri(path)).Append("\">\n");
            signedInfo.Append("<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha512\"></DigestMethod>\n");
            signedInfo.Append("<DigestValue>").Append(Base64Lines(SHA512.HashData(bytes))).Append("</DigestValue>\n");
            signedInfo.Append("</Reference>\n");
        }

        var properties = PropertiesObject(id, role);
        signedInfo.Append("<Reference URI=\"#prop\">\n<Transforms>\n");
        signedInfo.Append("<Transform Algorithm=\"http://www.w3.org/2006/12/xml-c14n11\"></Transform>\n</Transforms>\n");
        signedInfo.Append("<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha512\"></DigestMethod>\n");
        var propertiesCanonical = "<Object xmlns=\"" + DsigNs + "\" Id=\"prop\">" + properties + "</Object>";
        signedInfo.Append("<DigestValue>").Append(Base64Lines(SHA512.HashData(Encoding.UTF8.GetBytes(propertiesCanonical))))
            .Append("</DigestValue>\n");
        signedInfo.Append("</Reference>\n");

        var inner = signedInfo.ToString();
        var canonical = "<SignedInfo xmlns=\"" + DsigNs + "\">" + inner + "</SignedInfo>";
        var signature = identity.Key.SignData(Encoding.UTF8.GetBytes(canonical), HashAlgorithmName.SHA512,
            RSASignaturePadding.Pkcs1);

        var xml = new StringBuilder();
        xml.Append("<Signature xmlns=\"").Append(DsigNs).Append("\" Id=\"").Append(id).Append("\">\n");
        xml.Append("<SignedInfo>").Append(inner).Append("</SignedInfo>\n");
        xml.Append("<SignatureValue>\n").Append(Base64Lines(signature)).Append("\n</SignatureValue>\n");
        xml.Append("<KeyInfo>\n<X509Data>\n");
        foreach (var cert in new[] { identity.Certificate }.Concat(identity.Chain))
        {
            xml.Append("<X509Certificate>\n").Append(Base64Lines(cert.RawData)).Append("\n</X509Certificate>\n");
        }

        xml.Append("</X509Data>\n</KeyInfo>\n");
        xml.Append("<Object Id=\"prop\">").Append(properties).Append("</Object>\n");
        xml.Append("</Signature>");
        return Encoding.UTF8.GetBytes(xml.ToString());
    }

    private static string PropertiesObject(string id, string role) =>
        "<SignatureProperties xmlns:dsp=\"http://www.w3.org/2009/xmldsig-properties\">"
        + $"<SignatureProperty Id=\"profile\" Target=\"#{id}\"><dsp:Profile URI=\"http://www.w3.org/ns/widgets-digsig#profile\"></dsp:Profile></SignatureProperty>"
        + $"<SignatureProperty Id=\"role\" Target=\"#{id}\"><dsp:Role URI=\"http://www.w3.org/ns/widgets-digsig#{role}\"></dsp:Role></SignatureProperty>"
        + $"<SignatureProperty Id=\"identifier\" Target=\"#{id}\"><dsp:Identifier></dsp:Identifier></SignatureProperty>"
        + "</SignatureProperties>";

    /// <summary>Percent-encoding as tizen package writes Reference URIs ("fonts/a b.txt" → "fonts%2Fa%20b.txt").</summary>
    public static string EncodeUri(string path)
    {
        var sb = new StringBuilder();
        foreach (var b in Encoding.UTF8.GetBytes(path))
        {
            var c = (char)b;
            if (c is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '-' or '_' or '.' or ',' or '(' or ')' or '\'')
            {
                sb.Append(c);
            }
            else
            {
                sb.Append('%').Append(b.ToString("X2", System.Globalization.CultureInfo.InvariantCulture));
            }
        }

        return sb.ToString();
    }

    /// <summary>Base64 in lines of 76 characters joined by LF (Java's MIME encoder with LF).</summary>
    internal static string Base64Lines(byte[] data)
    {
        var text = Convert.ToBase64String(data);
        if (text.Length <= 76)
        {
            return text;
        }

        var sb = new StringBuilder(text.Length + text.Length / 76);
        for (var i = 0; i < text.Length; i += 76)
        {
            if (i > 0)
            {
                sb.Append('\n');
            }

            sb.Append(text, i, Math.Min(76, text.Length - i));
        }

        return sb.ToString();
    }
}
