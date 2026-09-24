using Jellyfin.Plugin.Tally.Services;
using MediaBrowser.Model.Updates;
using Xunit;

namespace Tally.Tests;

public class PluginRepositoryTests
{
    private static RepositoryInfo Stable => new() { Name = "Jellyfin Stable", Url = "https://repo.jellyfin.org/files/plugin/manifest.json" };

    [Fact]
    public void Tally_Is_Appended_After_The_Existing_Repositories()
    {
        var (repos, added) = PluginRepositoryService.WithTallyRepository(new[] { Stable });

        Assert.True(added);
        Assert.Equal(2, repos.Length);
        Assert.Equal("Jellyfin Stable", repos[0].Name);
        Assert.Equal(PluginRepositoryService.ManifestUrl, repos[1].Url);
        Assert.True(repos[1].Enabled);
    }

    [Theory]
    [InlineData("https://raw.githubusercontent.com/Scdouglas1999/Tally/main/server/manifest.json")]
    [InlineData(" HTTPS://raw.githubusercontent.com/scdouglas1999/tally/main/server/manifest.json/ ")]
    public void An_Existing_Entry_Is_Not_Duplicated_Even_When_Disabled(string url)
    {
        var mine = new RepositoryInfo { Name = "my name for it", Url = url, Enabled = false };

        var (repos, added) = PluginRepositoryService.WithTallyRepository(new[] { Stable, mine });

        Assert.False(added);
        Assert.Equal(2, repos.Length);
        Assert.False(repos[1].Enabled);
    }

    [Fact]
    public void An_Empty_List_Gets_Tally()
    {
        var (repos, added) = PluginRepositoryService.WithTallyRepository(System.Array.Empty<RepositoryInfo>());

        Assert.True(added);
        Assert.Single(repos);
    }
}
