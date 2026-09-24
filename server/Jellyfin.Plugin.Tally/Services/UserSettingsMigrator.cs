using System;
using System.Linq;
using System.Text.Json.Nodes;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>Rewrites channel ids stored in a user's settings (favorites, last channel) from the old
/// URL-derived form to the stable form, for whichever of them can still be recognized.</summary>
public static class UserSettingsMigrator
{
    /// <param name="settings">Mutated in place.</param>
    /// <param name="resolve">Returns the current id for a stored id, or null when it is unknown.</param>
    /// <returns>True when something changed and the settings should be saved.</returns>
    public static bool MigrateChannelIds(JsonObject settings, Func<string, string?> resolve)
    {
        var changed = false;

        if (settings["favorites"] is JsonArray favorites)
        {
            var mapped = favorites
                .Select(n => n?.GetValue<string>())
                .Where(id => !string.IsNullOrEmpty(id))
                .Select(id => resolve(id!) ?? id!)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();
            var original = favorites.Select(n => n?.GetValue<string>()).ToList();
            if (!mapped.SequenceEqual(original, StringComparer.OrdinalIgnoreCase))
            {
                settings["favorites"] = new JsonArray(mapped.Select(id => (JsonNode)JsonValue.Create(id)!).ToArray());
                changed = true;
            }
        }

        if (settings["lastChannel"] is JsonValue last && last.TryGetValue<string>(out var lastId)
            && resolve(lastId) is { } current && !string.Equals(current, lastId, StringComparison.OrdinalIgnoreCase))
        {
            settings["lastChannel"] = current;
            changed = true;
        }

        return changed;
    }
}
