using Jellyfin.Plugin.Tally.Services;
using Xunit;

namespace Tally.Tests;

public class HostKnowledgeTests
{
    [Fact]
    public void What_Was_Learned_Survives_A_Restart()
    {
        var path = Path.Combine(Path.GetTempPath(), "jtv-hosts-" + Guid.NewGuid().ToString("N"), "hosts.json");
        try
        {
            var before = new HostKnowledge();
            Assert.True(before.LearnStripsReferer("cdn.example.com"));
            Assert.False(before.LearnStripsReferer("CDN.example.com")); // already known: no re-log, no re-save
            Assert.True(before.LearnNeedsBrowser("fingerprinted.example.net"));
            before.Save(path);                                           // creates the folder too

            var after = new HostKnowledge();
            Assert.False(after.AnyNeedsBrowser);
            after.Load(path);

            Assert.True(after.StripsReferer("cdn.example.com"));
            Assert.True(after.NeedsBrowser("FINGERPRINTED.example.net"));
            Assert.False(after.NeedsBrowser("cdn.example.com"));
            Assert.True(after.AnyNeedsBrowser);
        }
        finally
        {
            Directory.Delete(Path.GetDirectoryName(path)!, recursive: true);
        }
    }

    [Fact]
    public void Missing_Or_Corrupt_File_Just_Means_Starting_Cold()
    {
        var path = Path.Combine(Path.GetTempPath(), "jtv-hosts-" + Guid.NewGuid().ToString("N") + ".json");
        var k = new HostKnowledge();
        k.Load(path);                       // missing
        File.WriteAllText(path, "{ not json");
        try
        {
            k.Load(path);                   // corrupt
            Assert.False(k.AnyNeedsBrowser);
            Assert.False(k.StripsReferer("anything"));
        }
        finally
        {
            File.Delete(path);
        }
    }
}
