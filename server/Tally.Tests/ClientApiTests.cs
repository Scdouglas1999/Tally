using Jellyfin.Plugin.Tally.Client;
using Jellyfin.Plugin.Tally.Scores;
using Xunit;

namespace Tally.Tests;

public class WatchResolverTests
{
    private static GameInfo Game(string id, string state, params (string Channel, string Kind)[] channels) => new()
    {
        Id = id, State = state,
        Channels = channels.Select(c => new GameChannel { Id = c.Channel, Kind = c.Kind }).ToList()
    };

    [Fact]
    public void A_Channel_Named_After_The_Game_Beats_The_Broadcaster()
    {
        var g = Game("1", "in", ("named", "teams"), ("fox", "network"));
        Assert.Equal("named", WatchResolver.Resolve(g, new[] { g }, _ => true)!.Id);
    }

    [Fact]
    public void A_Broadcaster_Shared_By_Two_Live_Games_Is_Not_Guessed_At()
    {
        var a = Game("1", "in", ("fox", "network"));
        var b = Game("2", "in", ("fox", "network"));
        var later = Game("3", "pre", ("fox", "network"));
        var all = new[] { a, b, later };

        Assert.Null(WatchResolver.Resolve(a, all, _ => true));
        Assert.Equal("fox", WatchResolver.Resolve(later, all, _ => true)!.Id); // not live yet: nothing to confuse it with

        var only = Game("4", "in", ("cbs", "network"));
        Assert.Equal("cbs", WatchResolver.Resolve(only, new[] { only, a }, _ => true)!.Id);
    }

    [Fact]
    public void Channels_That_No_Longer_Exist_Are_Skipped()
    {
        var g = Game("1", "in", ("gone", "teams"), ("here", "epg"));
        Assert.Equal("here", WatchResolver.Resolve(g, new[] { g }, id => id == "here")!.Id);
    }
}

public class EventFeedTests
{
    private static readonly DateTimeOffset T = new(2026, 9, 20, 20, 0, 0, TimeSpan.Zero);

    private static GameInfo Game(int away, int home, string state = "in") => new()
    {
        Id = "g1", State = state, Detail = "1:32 - 4th", LastPlayType = "Touchdown",
        Away = new GameTeam { Abbr = "NO", ShortName = "Saints", Score = away },
        Home = new GameTeam { Abbr = "BAL", ShortName = "Ravens", Score = home }
    };

    [Fact]
    public void First_Sighting_Is_Silent_Then_Scores_And_The_Final_Are_Recorded_Once()
    {
        var feed = new EventFeed();
        feed.ObserveScores(new[] { Game(17, 17) }, T);
        Assert.Empty(feed.Since(0));                                  // opening the app is not news

        feed.ObserveScores(new[] { Game(24, 17) }, T.AddSeconds(15));
        feed.ObserveScores(new[] { Game(24, 17) }, T.AddSeconds(30)); // same board again: nothing new
        var score = Assert.Single(feed.Since(0));
        Assert.Equal(("scores", "score", "g1"), (score.Source, score.Kind, score.GameId));
        Assert.Equal("Saints score — Touchdown", score.Title);
        Assert.Equal("NO 24 · BAL 17 · 1:32 - 4th", score.Text);

        feed.ObserveScores(new[] { Game(24, 17, "post") }, T.AddMinutes(5));
        var all = feed.Since(0);
        Assert.Equal(new[] { "score", "final" }, all.Select(e => e.Kind));
        Assert.True(all[1].Id > all[0].Id);

        Assert.Equal("final", Assert.Single(feed.Since(score.Id)).Kind); // "everything after the last one I showed"
        Assert.Equal(all[1].Id, feed.LatestId);
    }

    [Fact]
    public void Other_Modules_Can_Publish_Under_Their_Own_Source_And_The_Feed_Is_Bounded()
    {
        var feed = new EventFeed();
        for (var i = 0; i < 400; i++)
        {
            feed.Publish(new BoardEvent { Source = "fantasy", Kind = "points", Title = "t" + i });
        }

        var recent = feed.Since(0, max: 1000);
        Assert.Equal(300, recent.Count);
        Assert.Equal("t399", recent[^1].Title);
        Assert.All(recent, e => Assert.Equal("fantasy", e.Source));
    }
}
