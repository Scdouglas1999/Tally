using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Tally.SamsungInstaller.Signing;

namespace Tally.SamsungInstaller.Certificates;

/// <summary>
/// The certificates this PC signs Tally with, kept in the user's app-data folder (Windows: %APPDATA%\Tally\Samsung;
/// Linux: ~/.config/Tally/Samsung; macOS: the .NET ApplicationData folder) and reused for every later install and
/// update. A TV only takes an update signed by the same author certificate as the installed Tally, so these files
/// matter: losing them means uninstalling Tally from the TV before the next install.
/// <list type="bullet">
/// <item><c>tizen-author.key.pem</c> / <c>tizen-author.cert.pem</c>: the Tizen author certificate (2020-2022 TVs), made
/// here on first use and signed by the Tizen Developers CA (as Tizen Studio's Certificate Manager does);</item>
/// <item><c>samsung-author.*</c>, <c>samsung-distributor.*</c>: the Samsung certificates (2023+ TVs), issued for the
/// signed-in Samsung account; the distributor one lists the TVs (DUIDs) it covers.</item>
/// </list>
/// </summary>
public sealed class CertificateStore
{
    private TizenDefaults? defaults;

    /// <param name="directory">Where this PC's certificates are kept.</param>
    /// <param name="defaults">Tizen's public signing material when it is already at hand (tests); otherwise
    /// <see cref="LoadTizenDefaultsAsync"/> downloads it (once) before a Tizen signature.</param>
    public CertificateStore(string directory, TizenDefaults? defaults = null)
    {
        Directory = directory;
        this.defaults = defaults;
    }

    public string Directory { get; }

    /// <summary>Where Tizen's public signing material is downloaded from (tests point it elsewhere).</summary>
    public TizenSdkSource TizenSource { get; init; } = TizenSdkSource.Official;

    /// <summary>Tizen's public signing material; <see cref="LoadTizenDefaultsAsync"/> must have run.</summary>
    public TizenDefaults Defaults => defaults
        ?? throw new InvalidOperationException("Tizen's signing material is not loaded (LoadTizenDefaultsAsync)");

    /// <summary>
    /// Tizen's public signing material (the Tizen Developers CA and the public distributor), from the saved copy in
    /// <see cref="Directory"/> or downloaded from download.tizen.org the first time. Throws
    /// <see cref="TizenSdkDownloadException"/> with a message for the person when it cannot be had.
    /// </summary>
    public async Task<TizenDefaults> LoadTizenDefaultsAsync(HttpClient http, Action<string>? progress, CancellationToken ct)
    {
        defaults ??= await TizenSdkDownload.LoadAsync(Directory, http, TizenSource, progress, ct).ConfigureAwait(false);
        return defaults;
    }

    public static string DefaultDirectory =>
        Environment.GetEnvironmentVariable("TALLY_SAMSUNG_DATA") is { Length: > 0 } dir
            ? dir
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData,
                Environment.SpecialFolderOption.Create), "Tally", "Samsung");

    private string PathOf(string name) => Path.Combine(Directory, name);

    // --- Tizen author certificate (Tizen 5.5-6.5) ---

    public bool HasTizenAuthor => File.Exists(PathOf("tizen-author.key.pem")) && File.Exists(PathOf("tizen-author.cert.pem"));

    /// <summary>The Tizen author certificate, created (and saved) when this PC has none.</summary>
    public SigningIdentity TizenAuthor(Func<DateTimeOffset>? clock = null)
    {
        if (!HasTizenAuthor)
        {
            var (key, cert) = Defaults.CreateAuthorCertificate("Tally", (clock ?? (() => DateTimeOffset.UtcNow))());
            SaveIdentity("tizen-author", key, [cert, Defaults.DeveloperCa]);
        }

        return LoadIdentity("tizen-author");
    }

    /// <summary>Tizen's public distributor (the same for everyone; Tizen Studio's default).</summary>
    public SigningIdentity TizenDistributor() => Defaults.Distributor;

    // --- Samsung certificates (Tizen 7+) ---

    public SigningIdentity? SamsungAuthor => Exists("samsung-author") ? LoadIdentity("samsung-author") : null;
    public SigningIdentity? SamsungDistributor => Exists("samsung-distributor") ? LoadIdentity("samsung-distributor") : null;

    /// <summary>The Samsung author key: kept across renewals (Samsung renews an author certificate for the same key).</summary>
    public RSA SamsungAuthorKey()
    {
        if (File.Exists(PathOf("samsung-author.key.pem")))
        {
            var existing = RSA.Create();
            existing.ImportFromPem(File.ReadAllText(PathOf("samsung-author.key.pem")));
            return existing;
        }

        var key = RSA.Create(2048);
        WritePrivate(PathOf("samsung-author.key.pem"), key.ExportPkcs8PrivateKeyPem());
        return key;
    }

    public void SaveSamsungAuthor(RSA key, X509Certificate2 certificate, X509Certificate2 ca) =>
        SaveIdentity("samsung-author", key, [certificate, ca]);

    public void SaveSamsungDistributor(RSA key, X509Certificate2 certificate, X509Certificate2 ca) =>
        SaveIdentity("samsung-distributor", key, [certificate, ca]);

    /// <summary>
    /// Samsung certificates that can sign for <paramref name="duid"/> now (both present, not expired, the
    /// distributor lists the DUID); null when a sign-in is needed.
    /// </summary>
    public (SigningIdentity Author, SigningIdentity Distributor)? SamsungFor(string duid, DateTimeOffset now)
    {
        var author = SamsungAuthor;
        var distributor = SamsungDistributor;
        if (author is null || distributor is null)
        {
            return null;
        }

        bool Valid(X509Certificate2 c) => c.NotBefore <= now.UtcDateTime.AddDays(1) && c.NotAfter > now.UtcDateTime.AddDays(1);
        if (!Valid(author.Certificate) || !Valid(distributor.Certificate))
        {
            return null;
        }

        return CertificateTools.DeviceIds(distributor.Certificate).Contains(duid, StringComparer.OrdinalIgnoreCase)
            ? (author, distributor)
            : null;
    }

    /// <summary>The TVs the current Samsung distributor certificate covers (kept when a new TV is added).</summary>
    public IReadOnlyList<string> SamsungDuids() =>
        SamsungDistributor is { } d ? CertificateTools.DeviceIds(d.Certificate) : [];

    // --- files ---

    private bool Exists(string name) => File.Exists(PathOf(name + ".key.pem")) && File.Exists(PathOf(name + ".cert.pem"));

    private SigningIdentity LoadIdentity(string name)
    {
        var key = RSA.Create();
        key.ImportFromPem(File.ReadAllText(PathOf(name + ".key.pem")));
        var certs = CertificateTools.LoadCertificates(File.ReadAllText(PathOf(name + ".cert.pem")));
        return new SigningIdentity(key, certs[0], certs.Skip(1).ToList());
    }

    private void SaveIdentity(string name, RSA key, IReadOnlyList<X509Certificate2> certificates)
    {
        System.IO.Directory.CreateDirectory(Directory);
        WritePrivate(PathOf(name + ".key.pem"), key.ExportPkcs8PrivateKeyPem());
        File.WriteAllText(PathOf(name + ".cert.pem"), CertificateTools.ToPem(certificates));
    }

    private void WritePrivate(string path, string text)
    {
        System.IO.Directory.CreateDirectory(Directory);
        if (!OperatingSystem.IsWindows())
        {
            File.WriteAllText(path, "");
            File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        }

        File.WriteAllText(path, text + "\n");
    }
}
