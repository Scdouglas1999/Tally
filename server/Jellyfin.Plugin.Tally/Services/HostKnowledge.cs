using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// What the proxy has learned about upstream hosts the hard way — which ones 404 any media request
/// carrying a Referer, which ones reject HttpClient outright and need the browser relay.
/// Persisted, because relearning costs the first viewer after every server restart several
/// seconds, and Jellyfin's native Live TV gives a stream one short ffprobe to prove itself:
/// a cold proxy means "Unable to load this item" on TV clients.
/// </summary>
public sealed class HostKnowledge
{
    private readonly ConcurrentDictionary<string, byte> _noReferer = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, byte> _browserOnly = new(StringComparer.OrdinalIgnoreCase);
    private readonly object _ioLock = new();

    public bool StripsReferer(string host) => _noReferer.ContainsKey(host);

    public bool NeedsBrowser(string host) => _browserOnly.ContainsKey(host);

    public bool AnyNeedsBrowser => !_browserOnly.IsEmpty;

    /// <summary>Returns true when this is news (so the caller knows to log and save).</summary>
    public bool LearnStripsReferer(string host) => _noReferer.TryAdd(host, 0);

    public bool LearnNeedsBrowser(string host) => _browserOnly.TryAdd(host, 0);

    public void Load(string path)
    {
        lock (_ioLock)
        {
            if (!File.Exists(path))
            {
                return;
            }

            try
            {
                var file = JsonSerializer.Deserialize<KnowledgeFile>(File.ReadAllText(path));
                foreach (var h in file?.NoReferer ?? Enumerable.Empty<string>())
                {
                    _noReferer.TryAdd(h, 0);
                }

                foreach (var h in file?.BrowserOnly ?? Enumerable.Empty<string>())
                {
                    _browserOnly.TryAdd(h, 0);
                }
            }
            catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
            {
                // a corrupt or unreadable file only costs us the head start
            }
        }
    }

    public void Save(string path)
    {
        lock (_ioLock)
        {
            var file = new KnowledgeFile
            {
                NoReferer = _noReferer.Keys.OrderBy(h => h, StringComparer.OrdinalIgnoreCase).ToList(),
                BrowserOnly = _browserOnly.Keys.OrderBy(h => h, StringComparer.OrdinalIgnoreCase).ToList()
            };
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var tmp = path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(file, new JsonSerializerOptions { WriteIndented = true }));
            File.Move(tmp, path, overwrite: true);
        }
    }

    private sealed class KnowledgeFile
    {
        public List<string> NoReferer { get; set; } = new();

        public List<string> BrowserOnly { get; set; } = new();
    }
}
