using System.Text.Json.Nodes;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Services;
using Jellyfin.Plugin.Tally.Sources;
using Xunit;

namespace Tally.Tests;

public class ChannelIdentityTests
{
    private static SourceChannel Ch(string source, string name, string url)
        => new() { SourceId = source, Name = name, StreamUrl = url, Id = SourceChannel.MakeId(source, url) };

    [Fact]
    public void Id_Survives_A_Rescan_That_Rotates_The_Stream_Token()
    {
        var scan1 = new List<SourceChannel> { Ch("web", "Denver Broncos Jacksonville Jaguars", "https://cdn/x.m3u8?token=AAA") };
        var scan2 = new List<SourceChannel> { Ch("web", "Denver  Broncos – Jacksonville Jaguars ", "https://cdn/x.m3u8?token=BBB") };
        Assert.NotEqual(scan1[0].Id, scan2[0].Id);           // the old scheme: a new id every scan

        ChannelIdentity.Assign(scan1);
        ChannelIdentity.Assign(scan2);

        Assert.Equal(scan1[0].Id, scan2[0].Id);              // punctuation/spacing differences don't matter either
        Assert.Equal(SourceChannel.MakeId("web", "https://cdn/x.m3u8?token=AAA"), scan1[0].LegacyId);
    }

    [Fact]
    public void Same_Name_In_Another_Source_Or_Twice_In_One_Source_Stays_Distinct()
    {
        var channels = new List<SourceChannel>
        {
            Ch("a", "ESPN", "https://a/1"), Ch("a", "ESPN", "https://a/2"), Ch("b", "ESPN", "https://b/1")
        };
        ChannelIdentity.Assign(channels);
        Assert.Equal(3, channels.Select(c => c.Id).Distinct().Count());
    }

    [Fact]
    public void Assigning_Twice_Is_Idempotent_And_Keeps_The_Original_Legacy_Id()
    {
        var channels = new List<SourceChannel> { Ch("a", "ESPN", "https://a/1") };
        ChannelIdentity.Assign(channels);
        var (id, legacy) = (channels[0].Id, channels[0].LegacyId);
        ChannelIdentity.Assign(channels);                     // e.g. a "last good" list re-used after a failed scan
        Assert.Equal(id, channels[0].Id);
        Assert.Equal(legacy, channels[0].LegacyId);
    }

    [Fact]
    public void Stored_Favorites_Move_To_The_New_Ids_And_Unknown_Ones_Are_Left_Alone()
    {
        var settings = JsonNode.Parse("""{ "favorites": ["old-espn", "gone", "new-fs1"], "lastChannel": "old-espn", "hideScores": true }""")!.AsObject();
        string? Resolve(string id) => id switch { "old-espn" => "new-espn", "new-fs1" => "new-fs1", _ => null };

        Assert.True(UserSettingsMigrator.MigrateChannelIds(settings, Resolve));
        Assert.Equal(new[] { "new-espn", "gone", "new-fs1" }, settings["favorites"]!.AsArray().Select(n => n!.GetValue<string>()));
        Assert.Equal("new-espn", settings["lastChannel"]!.GetValue<string>());
        Assert.True(settings["hideScores"]!.GetValue<bool>());

        Assert.False(UserSettingsMigrator.MigrateChannelIds(settings, Resolve)); // second pass: nothing left to do
    }
}
