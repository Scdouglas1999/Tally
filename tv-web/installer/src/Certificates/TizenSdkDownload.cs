using System.IO.Compression;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace Tally.SamsungInstaller.Certificates;

/// <summary>Where Tizen's public signing material comes from: a package and the SHA-256 it must have.</summary>
public sealed record TizenSdkSource(string Url, string Sha256, string FileName)
{
    /// <summary>
    /// Tizen Studio's certificate generator 0.1.4 (Apache-2.0, the files every Tizen Studio carries), from Tizen's
    /// own download server, as Samsung's @tizentv/tools fetches it. The Windows, macOS and Linux packages hold the
    /// same certificate files; the Linux one is used everywhere. Pinned: a changed file is refused.
    /// </summary>
    public static TizenSdkSource Official { get; } = new(
        "https://download.tizen.org/sdk/tizenstudio/official/binary/certificate-generator_0.1.4_ubuntu-64.zip",
        "446aa52f249205f4ad573173b02c9c38aa5de0316028b77dd189e44fd5f75964",
        "certificate-generator_0.1.4_ubuntu-64.zip");
}

/// <summary>The download failed or brought the wrong file; the message is for the person.</summary>
public sealed class TizenSdkDownloadException(string message, Exception? inner = null) : Exception(message, inner);

/// <summary>
/// Tizen's public signing material (the Tizen Developers CA with its key, and the public distributor) is not carried
/// in the program: it is downloaded once from download.tizen.org, checked against a pinned SHA-256, and kept next to
/// this PC's certificates (the app-data folder) for later runs, which then need no internet for it.
/// </summary>
public static class TizenSdkDownload
{
    private const string Root = "data/tools/certificate-generator/certificates/";
    internal const string DeveloperCaEntry = Root + "developer/tizen-developer-ca.cer";
    internal const string DeveloperCaKeyEntry = Root + "developer/tizen-developer-ca-privatekey.pem";
    internal const string DistributorEntry = Root + "distributor/tizen-distributor-signer.p12";

    /// <summary>The material from <paramref name="directory"/>, downloading it first when it is not there (or damaged).</summary>
    public static async Task<TizenDefaults> LoadAsync(string directory, HttpClient http, TizenSdkSource source,
        Action<string>? progress, CancellationToken ct)
    {
        var path = Path.Combine(directory, source.FileName);
        if (File.Exists(path))
        {
            var cached = await File.ReadAllBytesAsync(path, ct).ConfigureAwait(false);
            if (HashMatches(cached, source.Sha256))
            {
                return FromPackage(cached);
            }

            // damaged or from another version: fetch it again
            File.Delete(path);
        }

        progress?.Invoke("Downloading Tizen's public signing certificates (4 MB, once, from download.tizen.org)…");
        byte[] bytes;
        try
        {
            using var response = await http.GetAsync(source.Url, HttpCompletionOption.ResponseContentRead, ct).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                throw new TizenSdkDownloadException(
                    $"download.tizen.org answered {(int)response.StatusCode} {response.ReasonPhrase} for Tizen's signing certificates " +
                    $"({source.Url}). Try again later; if it keeps failing, Tally for Samsung needs an update.");
            }

            bytes = await response.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is HttpRequestException or SocketException or IOException
                                       || (ex is TaskCanceledException && !ct.IsCancellationRequested))
        {
            throw new TizenSdkDownloadException(
                "This PC could not download Tizen's signing certificates from download.tizen.org (" + Reason(ex) + "). " +
                "Tally for Samsung needs the internet once, for this download (4 MB); later runs use the saved copy. " +
                "Connect this PC to the internet and run the program again.", ex);
        }

        if (!HashMatches(bytes, source.Sha256))
        {
            throw new TizenSdkDownloadException(
                "The file from download.tizen.org is not the one this program expects (its SHA-256 differs), so it was not " +
                "used. Something between this PC and Tizen's server changed it, or Tizen replaced it. Try again on another " +
                "network; if it keeps happening, Tally for Samsung needs an update.");
        }

        var defaults = FromPackage(bytes);
        Directory.CreateDirectory(directory);
        await File.WriteAllBytesAsync(path, bytes, ct).ConfigureAwait(false);
        return defaults;
    }

    /// <summary>The CA, its key and the distributor out of the certificate generator's package.</summary>
    internal static TizenDefaults FromPackage(byte[] zip)
    {
        try
        {
            using var archive = new ZipArchive(new MemoryStream(zip), ZipArchiveMode.Read);
            byte[] Entry(string name)
            {
                var entry = archive.GetEntry(name) ?? throw new InvalidDataException("missing " + name);
                using var stream = entry.Open();
                using var copy = new MemoryStream();
                stream.CopyTo(copy);
                return copy.ToArray();
            }

            return TizenDefaults.FromSdkFiles(Entry(DeveloperCaEntry), Entry(DeveloperCaKeyEntry), Entry(DistributorEntry));
        }
        catch (Exception ex) when (ex is InvalidDataException or CryptographicException or FormatException)
        {
            throw new TizenSdkDownloadException("Tizen's certificate package could not be read (" + ex.Message + ").", ex);
        }
    }

    private static bool HashMatches(byte[] bytes, string sha256) =>
        string.Equals(Convert.ToHexStringLower(SHA256.HashData(bytes)), sha256, StringComparison.OrdinalIgnoreCase);

    private static string Reason(Exception ex) => ex switch
    {
        HttpRequestException { InnerException: SocketException s } => SocketReason(s),
        SocketException s => SocketReason(s),
        TaskCanceledException => "no answer in time",
        HttpRequestException { HttpRequestError: HttpRequestError.NameResolutionError } => "the name download.tizen.org could not be looked up",
        _ => ex.Message,
    };

    private static string SocketReason(SocketException s) => s.SocketErrorCode switch
    {
        SocketError.HostNotFound or SocketError.TryAgain or SocketError.NoData => "the name download.tizen.org could not be looked up",
        SocketError.NetworkUnreachable or SocketError.HostUnreachable or SocketError.NetworkDown => "no network",
        SocketError.ConnectionRefused => "the connection was refused",
        SocketError.TimedOut => "no answer in time",
        _ => s.Message,
    };
}
