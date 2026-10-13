using Wave.Core.Connect;
using Wave.Core.Music;

namespace Wave.Core.Tests;

/// <summary>Android 版の FeedParserTest と同じ fixture・同じ期待値（RSS のポッドキャスト、ADR-0012）。</summary>
public class FeedParserTests
{
    private static string Fixture(string name) => File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "fixtures", name));

    [Fact]
    public void ParsesShowAndEpisodesNewestFirst()
    {
        var show = FeedParser.Parse("https://example.com/feed.xml", Fixture("feed_rss.xml"))!;
        Assert.Equal("テストの番組 & 友だち", show.Title);
        Assert.Equal("wave", show.Author);
        Assert.Equal("説明の一行目\n二行目", show.Description);
        Assert.Equal("https://example.com/show.jpg", show.ThumbnailUrl);
        Assert.Equal(["第2回", "第1回"], show.AllEpisodes.Select(e => e.Title));
        var newest = show.AllEpisodes[0];
        Assert.Equal("https://example.com/ep2.mp3?x=1&y=2", newest.AudioUrl);
        Assert.Equal(2525, newest.DurationSeconds);
        Assert.Equal("https://example.com/ep2.jpg", newest.ThumbnailUrl);
        // 説明はエスケープされた HTML で来る（タグを落とし、文の中の実体は戻す）
        Assert.Equal("エピソードの説明 1 < 2", newest.Description);
        Assert.Equal(3723, show.AllEpisodes[1].DurationSeconds);
    }

    [Fact]
    public void EpisodesBecomeExternalSongs()
    {
        var show = FeedParser.Parse("https://example.com/feed.xml", Fixture("feed_rss.xml"))!;
        var page = show.ToPage(saved: true, positions: url => url.EndsWith("ep1.mp3", StringComparison.Ordinal) ? EpisodePosition.Of(600, 3723, 0) : null);
        Assert.Equal("rss:https://example.com/feed.xml", page.BrowseId);
        var first = page.Episodes[0];
        Assert.Equal("ext:https://example.com/ep2.mp3?x=1&y=2", first.Song.VideoId);
        Assert.True(Feed.IsExternal(first.Song.VideoId));
        Assert.Equal("https://example.com/ep2.mp3?x=1&y=2", Feed.AudioUrlOf(first.Song.VideoId));
        Assert.Equal("2026/10/13", first.PublishedText);
        Assert.Equal("42:05", first.DurationText);
        Assert.Equal("テストの番組 & 友だち", Assert.Single(first.Song.Artists).Name);
        Assert.Equal("2026/10/05", page.Episodes[1].PublishedText);
        Assert.Equal("1:02:03", page.Episodes[1].DurationText);
        Assert.Equal(16, page.Episodes[1].ProgressPercent);
        Assert.Null(first.ProgressPercent);
    }

    [Fact]
    public void ParsesRfc822WithOffsets()
    {
        Assert.Equal(1_791_853_200L, FeedParser.ParseRfc822("Tue, 13 Oct 2026 01:00:00 GMT"));
        Assert.Equal(1_791_853_200L, FeedParser.ParseRfc822("Tue, 13 Oct 2026 10:00:00 +0900"));
        Assert.Equal(1_791_853_200L, FeedParser.ParseRfc822("13 Oct 2026 01:00 GMT"));
        Assert.Null(FeedParser.ParseRfc822("not a date"));
    }

    [Fact]
    public void NormalizesFeedUrls()
    {
        Assert.Equal("https://example.com/feed", Feed.NormalizeFeedUrl(" feed://example.com/feed "));
        Assert.Equal("https://example.com/feed", Feed.NormalizeFeedUrl("http://example.com/feed"));
        Assert.Equal("https://example.com/feed", Feed.NormalizeFeedUrl("example.com/feed"));
        Assert.Null(Feed.NormalizeFeedUrl("ftp://example.com/feed"));
        Assert.Null(Feed.NormalizeFeedUrl("ポッドキャスト"));
    }

    [Fact]
    public void FinishedNearTheEnd()
    {
        Assert.True(EpisodePosition.Of(3700, 3723, 0).Finished);
        Assert.Equal(0, EpisodePosition.Of(3700, 3723, 0).ResumeSeconds);
        Assert.Equal(100, EpisodePosition.Of(3700, 3723, 0).Percent);
        Assert.Equal(600, EpisodePosition.Of(600, 3723, 0).ResumeSeconds);
    }

    [Fact]
    public void ReadsDirectoryResults()
    {
        var body = """{"resultCount":2,"results":[{"collectionName":"番組A","artistName":"A","feedUrl":"https://a.example/feed","artworkUrl600":"https://a.example/a.jpg"},{"collectionName":"フィード無し"}]}""";
        var results = PodcastFeedClient.DirectoryResults(body);
        var only = Assert.Single(results);
        Assert.Equal("rss:https://a.example/feed", only.BrowseId);
        Assert.Equal("番組A", only.Title);
    }

    [Fact]
    public void ToleratesBrokenXml()
    {
        var show = FeedParser.Parse("https://x.example/f", "<rss><channel><title>T</title><item><title>a<enclosure url='https://x.example/a.mp3'/></item>");
        Assert.Equal("T", show?.Title);
    }

    [Fact]
    public void LibraryKeepsNewestFirstAndRoundTrips()
    {
        var a = new FeedSubscription("https://a.example/f", "A");
        var b = new FeedSubscription("https://b.example/f", "B");
        var state = FeedLibraryState.Empty.Subscribe(a).Subscribe(b).Subscribe(a).Remember("https://a.example/1.mp3", EpisodePosition.Of(10, 100, 5));
        Assert.Equal(["A", "B"], state.Subscriptions.Select(s => s.Title));
        var decoded = FeedLibraryState.Decode(FeedLibraryState.Encode(state));
        Assert.NotNull(decoded);
        Assert.True(state.SameAs(decoded!));
        Assert.Equal(["B"], state.Unsubscribe(a.FeedUrl).Subscriptions.Select(s => s.Title));
        Assert.Equal("A2", state.Refresh(a with { Title = "A2" }).Subscriptions[0].Title);
    }

    [Fact]
    public void PagesThroughLongFeedsAndReversesOldestFirst()
    {
        var items = string.Concat(Enumerable.Range(1, 70).Select(n => $"<item><title>回{n}</title><pubDate>{Math.Min(n, 28)} Oct 2026 00:00:00 GMT</pubDate><enclosure url=\"https://x.example/{n}.mp3\"/></item>"));
        var show = FeedParser.Parse("https://x.example/f", $"<rss><channel><title>連続</title>{items}</channel></rss>")!;
        var first = show.ToPage(saved: false);
        Assert.Equal(30, first.Episodes.Count);
        // 古い順のフィードは新しい順に並べ直す
        Assert.Equal("回70", first.Episodes[0].Song.Title);
        var second = show.More(first.Continuation!);
        Assert.Equal("回40", second.Items[0].Song.Title);
        var third = show.More(second.Continuation!);
        Assert.Equal(10, third.Items.Count);
        Assert.Null(third.Continuation);
    }

    // ---- ここから Windows で足した確かめ ----

    [Fact]
    public void LibraryJsonHasAndroidShape()
    {
        var state = FeedLibraryState.Empty.Subscribe(new FeedSubscription("https://a.example/f", "A", AddedAtMs: 7)).Remember("https://a.example/1.mp3", EpisodePosition.Of(95, 100, 9));
        var json = FeedLibraryState.Encode(state);
        Assert.Equal("""{"subscriptions":[{"feedUrl":"https://a.example/f","title":"A","addedAtMs":7}],"positions":{"https://a.example/1.mp3":{"positionSeconds":95,"durationSeconds":100,"finished":true,"updatedAtMs":9}}}""", json);
        // Android が書いたもの（既定の値を省いた形）も読める
        var android = FeedLibraryState.Decode("""{"subscriptions":[{"feedUrl":"https://b.example/f","title":"B"}],"positions":{"https://b.example/2.mp3":{"positionSeconds":12}},"unknown":1}""")!;
        Assert.Equal("B", android.Subscriptions[0].Title);
        Assert.Equal(12, android.Positions["https://b.example/2.mp3"].ResumeSeconds);
        Assert.Null(FeedLibraryState.Decode("not json"));
    }

    [Fact]
    public void ForgetsOldestPositionsBeyondTheLimit()
    {
        var state = FeedLibraryState.Empty;
        for (var i = 0; i <= FeedLibraryState.MaxPositions; i++) state = state.Remember($"https://x.example/{i}.mp3", EpisodePosition.Of(10, 100, i + 1));
        Assert.Equal(FeedLibraryState.MaxPositions, state.Positions.Count);
        Assert.False(state.Positions.ContainsKey("https://x.example/0.mp3"));
    }

    [Fact]
    public void HandoffAcceptsEpisodeIds()
    {
        Assert.True(HandoffCodec.IsItemVideoId("dQw4w9WgXcQ"));
        Assert.True(HandoffCodec.IsItemVideoId("ext:https://example.com/ep2.mp3?x=1&y=2"));
        Assert.False(HandoffCodec.IsItemVideoId("ext:http://example.com/ep2.mp3"));
        Assert.False(HandoffCodec.IsItemVideoId("ext:https://example.com/a b.mp3"));
        Assert.False(HandoffCodec.IsItemVideoId("rss:https://example.com/feed"));
    }
}
