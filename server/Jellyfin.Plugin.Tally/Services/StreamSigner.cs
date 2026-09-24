using System;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Signs upstream stream URLs so the proxy endpoint only serves URLs this server minted.
/// This keeps /JellyTV/Proxy from being an open relay even though it is reachable without auth.
/// </summary>
public class StreamSigner
{
    private byte[]? _key;
    private string _keySource = string.Empty;

    private byte[] Key
    {
        get
        {
            var secret = Plugin.Instance?.Configuration.ProxySecret ?? string.Empty;
            if (_key == null || _keySource != secret)
            {
                _key = Convert.FromHexString(secret.Length >= 64 ? secret[..64] : secret.PadRight(64, '0'));
                _keySource = secret;
            }

            return _key;
        }
    }

    public string Sign(string url, string headersB64)
    {
        var payload = url + "\n" + headersB64;
        var sig = HMACSHA256.HashData(Key, Encoding.UTF8.GetBytes(payload));
        return Convert.ToHexString(sig).ToLowerInvariant();
    }

    public bool Validate(string url, string headersB64, string? signature)
    {
        if (string.IsNullOrEmpty(signature) || string.IsNullOrEmpty(url))
        {
            return false;
        }

        var expected = Sign(url, headersB64);
        return CryptographicOperations.FixedTimeEquals(
            Encoding.ASCII.GetBytes(expected),
            Encoding.ASCII.GetBytes(signature));
    }

    /// <summary>Encodes per-request upstream headers for transport in the proxied URL.</summary>
    public static string EncodeHeaders(Dictionary<string, string>? headers)
    {
        if (headers == null || headers.Count == 0)
        {
            return string.Empty;
        }

        var json = JsonSerializer.Serialize(headers);
        return Convert.ToBase64String(Encoding.UTF8.GetBytes(json))
            .Replace('+', '-').Replace('/', '_').TrimEnd('=');
    }

    public static Dictionary<string, string> DecodeHeaders(string headersB64)
    {
        if (string.IsNullOrEmpty(headersB64))
        {
            return new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        }

        try
        {
            var b64 = headersB64.Replace('-', '+').Replace('_', '/');
            b64 = b64.PadRight(b64.Length + ((4 - b64.Length % 4) % 4), '=');
            var json = Encoding.UTF8.GetString(Convert.FromBase64String(b64));
            return JsonSerializer.Deserialize<Dictionary<string, string>>(json)
                   ?? new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        }
        catch
        {
            return new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        }
    }
}
