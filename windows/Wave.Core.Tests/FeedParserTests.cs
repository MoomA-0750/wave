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

/// <summary>Android の NewEpisodesTest と同じ期待値。</summary>
public class NewEpisodesTests
{
    private const long Now = 1_791_853_200L; // 2026-10-13 01:00 UTC

    private static PodcastEpisode YouTube(string id, string? published) => new(new Song(id, id, [], null, null, null), published);

    [Fact]
    public void ReadsYouTubeDates()
    {
        Assert.Equal(Now - 6 * 86_400, NewEpisodes.ApproximateEpochSeconds("6 日前", Now));
        Assert.Equal(Now - 2 * 3_600, NewEpisodes.ApproximateEpochSeconds("2 時間前", Now));
        Assert.Equal(FeedParser.ParseRfc822("8 Sep 2025 00:00:00 GMT"), NewEpisodes.ApproximateEpochSeconds("2025/09/08", Now));
        Assert.Null(NewEpisodes.ApproximateEpochSeconds("ライブ", Now));
    }

    [Fact]
    public void MergesNewestFirst()
    {
        string[] months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
        var rfc = string.Concat(new[] { 3, 10, 200 }.Select(days =>
        {
            var parts = FeedParser.DateText(Now - days * 86_400L).Split('/');
            return $"<item><title>r{days}</title><pubDate>{parts[2]} {months[int.Parse(parts[1]) - 1]} {parts[0]} 01:00:00 GMT</pubDate><enclosure url=\"https://x.example/{days}.mp3\"/></item>";
        }));
        var show = FeedParser.Parse("https://x.example/f", $"<rss><channel><title>R</title>{rfc}</channel></rss>")!;
        var merged = NewEpisodes.Merge([YouTube("y1", "1 日前"), YouTube("y6", "6 日前"), YouTube("y?", null), YouTube("y30", "2026/09/13")], [show], Now);
        // 200 日前の RSS の回は混ぜない。読めない表示の回は一つ前と同じ日時
        Assert.Equal(["y1", "r3", "y6", "y?", "r10", "y30"], merged.Select(e => e.Song.Title));
    }
}

/// <summary>Android の FeedLibrarySyncTest と同じ期待値（ADR-0013）。</summary>
public class FeedLibrarySyncTests
{
    [Fact]
    public void RoundTripsThroughTheSyncBodyAndRemovalWins()
    {
        var a = FeedLibraryState.Empty.Subscribe(new FeedSubscription("https://a.example/f", "A", AddedAtMs: 10)).Remember("https://a.example/1.mp3", EpisodePosition.Of(60, 600, 11));
        // もう1台: 何も持っていない → 受け取る
        var b = FeedLibraryState.Empty.Apply(a.ToBody());
        Assert.Equal(["A"], b.Subscriptions.Select(s => s.Title));
        Assert.Equal(60, b.Positions["https://a.example/1.mp3"].PositionSeconds);
        // b で外す（後の時刻）→ a に当てると外れる
        var removed = b.Unsubscribe("https://a.example/f", atMs: 20);
        var a2 = a.Apply(a.ToBody().Merge(removed.ToBody()));
        Assert.Empty(a2.Subscriptions);
        Assert.Equal(20L, a2.Removed["https://a.example/f"]);
        // 古い登録の知らせは、新しい「外した」を覆さない
        Assert.Empty(a2.Apply(a.ToBody()).Subscriptions);
        // 外した後にもう一度登録すると、印は消える
        var again = a2.Subscribe(new FeedSubscription("https://a.example/f", "A", AddedAtMs: 30));
        Assert.False(again.Removed.ContainsKey("https://a.example/f"));
        Assert.Equal(["A"], a2.Apply(again.ToBody()).Subscriptions.Select(s => s.Title));
    }

    // ---- ここから Windows で足した確かめ ----

    [Fact]
    public void RemovedMarksAreSavedInTheAndroidShape()
    {
        var state = FeedLibraryState.Empty.Subscribe(new FeedSubscription("https://a.example/f", "A", AddedAtMs: 7)).Unsubscribe("https://a.example/f", atMs: 9);
        var json = FeedLibraryState.Encode(state);
        Assert.Equal("""{"removed":{"https://a.example/f":9}}""", json);
        var decoded = FeedLibraryState.Decode(json)!;
        Assert.True(state.SameAs(decoded));
        Assert.False(state.SameAs(FeedLibraryState.Empty));
        // Android の FeedLibraryState.encode が書いた形（removed つき）を読める
        var android = FeedLibraryState.Decode("""{"subscriptions":[{"feedUrl":"https://b.example/f","title":"B","addedAtMs":3}],"removed":{"https://a.example/f":20}}""")!;
        Assert.Equal(20L, android.Removed["https://a.example/f"]);
    }

    [Fact]
    public void SyncBodyHasTheKotlinJsonShape()
    {
        var state = FeedLibraryState.Empty.Subscribe(new FeedSubscription("https://a.example/f", "A", AddedAtMs: 10)).Unsubscribe("https://b.example/f", atMs: 5).Remember("https://a.example/1.mp3", EpisodePosition.Of(60, null, 11));
        var json = ConnectJson.Encode(state.ToBody());
        // kotlinx.serialization（encodeDefaults = true）と同じ並び・同じ null の出し方
        Assert.Equal(
            """{"v":1,"feeds":{"https://b.example/f":{"title":"","author":null,"thumbnailUrl":null,"addedAtMs":0,"removed":true,"updatedAtMs":5},"https://a.example/f":{"title":"A","author":null,"thumbnailUrl":null,"addedAtMs":10,"removed":false,"updatedAtMs":10}},"positions":{"https://a.example/1.mp3":{"positionSeconds":60,"durationSeconds":null,"finished":false,"updatedAtMs":11}}}""",
            json);
        Assert.True(state.ToBody().SameAs(ConnectJson.Decode<LibraryBody>(json)!));
        // 項目が欠けていても既定の値で読む（必須は title と updatedAtMs）
        var minimal = ConnectJson.Decode<LibraryBody>("""{"feeds":{"https://c.example/f":{"title":"C","updatedAtMs":1}}}""")!;
        Assert.Equal(1, minimal.V);
        Assert.False(minimal.Feeds["https://c.example/f"].Removed);
        Assert.Null(ConnectJson.Decode<LibraryBody>("""{"feeds":{"https://c.example/f":{"title":"C"}}}"""));
    }

    [Fact]
    public void MergeKeepsTheNewerAndTheOtherOnTies()
    {
        static LibraryFeed F(string title, long at) => new() { Title = title, UpdatedAtMs = at };
        var mine = new LibraryBody { Feeds = new Dictionary<string, LibraryFeed> { ["x"] = F("mine", 5), ["y"] = F("mine", 9) } };
        var other = new LibraryBody { Feeds = new Dictionary<string, LibraryFeed> { ["x"] = F("other", 5), ["y"] = F("other", 3), ["z"] = F("other", 1) } };
        var merged = mine.Merge(other);
        Assert.Equal(["x", "y", "z"], merged.Feeds.Keys);
        Assert.Equal("other", merged.Feeds["x"].Title);
        Assert.Equal("mine", merged.Feeds["y"].Title);
        // 聴いたところは新しい順に 500 件まで
        var many = new LibraryBody { Positions = Enumerable.Range(0, 600).ToDictionary(i => $"p{i}", i => new LibraryPosition { PositionSeconds = 1, UpdatedAtMs = i }) };
        var trimmed = many.Merge(new LibraryBody());
        Assert.Equal(LibraryBody.MaxPositions, trimmed.Positions.Count);
        Assert.False(trimmed.Positions.ContainsKey("p99"));
        Assert.True(trimmed.Positions.ContainsKey("p100"));
    }
}
