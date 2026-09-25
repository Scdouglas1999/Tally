using System.IO.Compression;
using System.Security.Cryptography;
using Tally.SamsungInstaller.Certificates;

namespace Tally.SamsungInstaller.Tests;

/// <summary>Tizen's public signing material from the test fixtures (the program downloads it instead).</summary>
internal static class TestTizen
{
    private static string Fixture(string name) => Path.Combine(AppContext.BaseDirectory, "fixtures", "tizen-sdk", name);

    public static byte[] Ca => File.ReadAllBytes(Fixture("tizen-developer-ca.cer"));
    public static byte[] CaKey => File.ReadAllBytes(Fixture("tizen-developer-ca-privatekey.pem"));
    public static byte[] Distributor => File.ReadAllBytes(Fixture("tizen-distributor-signer.p12"));

    public static TizenDefaults Defaults { get; } = TizenDefaults.FromSdkFiles(Ca, CaKey, Distributor);

    /// <summary>A package laid out as Tizen's certificate generator zip (only the three files the program reads).</summary>
    public static byte[] Package(bool withKey = true)
    {
        using var buffer = new MemoryStream();
        using (var zip = new ZipArchive(buffer, ZipArchiveMode.Create, leaveOpen: true))
        {
            void Add(string name, byte[] data)
            {
                using var s = zip.CreateEntry(name).Open();
                s.Write(data);
            }

            Add(TizenSdkDownload.DeveloperCaEntry, Ca);
            if (withKey)
            {
                Add(TizenSdkDownload.DeveloperCaKeyEntry, CaKey);
            }

            Add(TizenSdkDownload.DistributorEntry, Distributor);
        }

        return buffer.ToArray();
    }

    public static TizenSdkSource SourceFor(byte[] package) =>
        new("https://download.tizen.org/test/certificate-generator.zip", Convert.ToHexStringLower(SHA256.HashData(package)), "certificate-generator-test.zip");
}
