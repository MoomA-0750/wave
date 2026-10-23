using System.Net;
using System.Net.Http.Headers;
using Wave.Core.Downloads;
using Wave.Core.Music;
using Wave.Core.Stream;

namespace Wave.Core.Tests;

/// <summary>ダウンロード（ADR-0016）: 目録の持ち主、置き場、取り方、スマートダウンロードの曲選び。</summary>
public sealed class DownloadsTests : IDisposable
{
    private readonly string directory = Path.Combine(Path.GetTempPath(), "wave-downloads-" + Guid.NewGuid().ToString("N"));
    private long now = 1_000;

    public void Dispose()
    {
        if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
    }

    private DownloadStore Store() => new(directory, () => now);

    // 11 文字の videoId（YouTube と同じ形）
    private static string Id(string s) => s.PadRight(11, '_');

    private static Song S(string id, string? artist = null, bool live = false) =>
        new(Id(id), "title-" + id, artist is null ? [] : [new ArtistRef(artist, "UC" + artist)], null, 200, null, IsLive: live);

    private static DownloadCollection Playlist(string id, params Song[] songs) => new(DownloadKind.Playlist, id, "p-" + id, null, 0, songs);

    // ---- 目録の持ち主 ----

    [Fact]
    public void ASongStaysWhileAnyOwnerRemains()
    {
        var a = S("a");
        var b = S("b");
        var c = S("c");
        var manifest = DownloadManifest.Empty
            .WithCollection(Playlist("PL1", a, b))
            .WithCollection(Playlist("PL2", b, c))
            .WithSingle(c, 5);
        Assert.Equal([a.VideoId, b.VideoId, c.VideoId], manifest.WantedIds());

        // PL2 を外しても、b は PL1 が、c は1曲ずつ取ったものが持っている
        var removed = manifest.WithoutCollection("PL2");
        Assert.Equal([a.VideoId, b.VideoId, c.VideoId], removed.WantedIds());
        // c を1曲ずつのものから外すと、持ち主がいなくなる
        Assert.Equal([a.VideoId, b.VideoId], removed.WithoutSingle(c.VideoId).WantedIds());
        // 手で取った曲は、スマートダウンロードを選び直しても残る
        var smart = manifest.WithSmart([new SmartTrack(c, SmartCategory.Frequent)], 9).WithSmart([], 10);
        Assert.Contains(c.VideoId, smart.WantedIds());
    }

    [Fact]
    public void LiveStreamsAreNeverWanted()
    {
        var live = S("live", live: true);
        var manifest = DownloadManifest.Empty.WithCollection(Playlist("PL", S("a"), live)).WithSingle(live, 1);
        Assert.Equal([Id("a")], manifest.WantedIds());
        Assert.False(manifest.IsSingle(live.VideoId));
    }

    [Fact]
    public void ReloadingACollectionReportsAddedAndRemovedSongs()
    {
        var manifest = DownloadManifest.Empty.WithCollection(Playlist("PL", S("a"), S("b")) with { AddedAtMs = 42 });
        var (next, diff) = manifest.WithCollectionSongs("PL", [S("b"), S("c")]);
        Assert.Equal([Id("c")], diff.Added);
        Assert.Equal([Id("a")], diff.Removed);
        Assert.Equal([Id("b"), Id("c")], next.WantedIds());
        // 足した時刻は前のまま
        Assert.Equal(42, next.Collection("PL")!.AddedAtMs);
        // 覚えていないものは何もしない
        Assert.Same(manifest, manifest.WithCollectionSongs("PL9", [S("x")]).Manifest);
    }

    [Fact]
    public void ManifestRoundTripsThroughJson()
    {
        var manifest = DownloadManifest.Empty
            .WithCollection(new DownloadCollection(DownloadKind.Album, "MPRE1", "album", "https://i/x.jpg", 3, [S("a", "art")]))
            .WithSingle(S("b"), 4, episode: true)
            .WithSmart([new SmartTrack(S("c"), SmartCategory.Likely)], 7);
        var decoded = DownloadManifest.Decode(manifest.Encode())!;
        Assert.Equal(manifest.WantedIds(), decoded.WantedIds());
        Assert.Equal(SmartCategory.Likely, decoded.Smart[0].Category);
        Assert.True(decoded.Singles[0].Episode);
        Assert.Equal(7, decoded.SmartRefreshedAtMs);
        Assert.Equal("art", decoded.Collections[0].Songs[0].Artists[0].Name);
        Assert.Null(DownloadManifest.Decode("{not json"));
    }

    // ---- 置き場 ----

    [Fact]
    public void StoreResumesTheSameFormatAndPurgesUnownedFiles()
    {
        var store = Store();
        var id = Id("a");
        Assert.Equal(0, store.Begin(new DownloadedFile(id, "audio/mp4", 6, 140)));
        Assert.True(store.Append(id, 0, [1, 2, 3]));
        // 続きでない位置には足さない
        Assert.False(store.Append(id, 5, [9]));
        Assert.False(store.IsComplete(id));
        // この起動の中で同じ形式なら続きから
        Assert.Equal(3, store.Begin(new DownloadedFile(id, "audio/mp4", 6, 140)));
        // 起動し直したら（別の置き場の口）、途中の分は捨てて頭から
        var reopened = Store();
        Assert.Equal(0, reopened.Begin(new DownloadedFile(id, "audio/mp4", 6, 140)));
        Assert.True(reopened.Append(id, 0, [1, 2, 3]));
        Assert.True(reopened.Append(id, 3, [4, 5, 6]));
        Assert.True(reopened.Complete(id));
        Assert.True(Store().IsComplete(id));
        Assert.Equal([1, 2, 3, 4, 5, 6], File.ReadAllBytes(reopened.BytesPath(id)));
        Assert.Equal(0, Store().Begin(new DownloadedFile(id, "audio/webm", 8, 251)));

        var other = Id("b");
        store = Store();
        store.Begin(new DownloadedFile(other, "audio/mp4", 1, 140));
        store.Append(other, 0, [7]);
        store.Complete(other);
        Assert.Equal(1, store.Purge([other]));
        Assert.Null(store.Entry(id));
        Assert.True(Store().IsComplete(other));
    }

    [Fact]
    public void RssEpisodesAreNamedByTheHashOfTheAudioUrl()
    {
        var episode = Feed.AudioVideoId("https://example.com/ep1.mp3");
        var key = DownloadStore.KeyOf(episode)!;
        Assert.Equal(64, key.Length);
        Assert.DoesNotContain("example", key);
        Assert.Null(DownloadStore.KeyOf("../../etc"));
        var store = Store();
        store.Begin(new DownloadedFile(episode, "audio/mpeg", 0));
        store.Append(episode, 0, [1, 2]);
        Assert.True(store.Complete(episode, 2));
        Assert.True(File.Exists(Path.Combine(directory, key + ".bin")));
        Assert.True(Store().IsComplete(episode));
    }

    [Fact]
    public void ManifestIsWrittenNextToTheFilesAndNotReadAsATrack()
    {
        var store = Store();
        store.WriteManifest(DownloadManifest.Empty.WithSingle(S("a"), 1));
        Assert.Equal([Id("a")], Store().ReadManifest().WantedIds());
        Assert.Equal(0, Store().TotalBytes);
        store.Clear();
        Assert.Empty(Store().ReadManifest().WantedIds());
    }

    // ---- 取り方 ----

    /// <summary>Range の要求に、0..size-1 の各バイトが (i % 251) の本文で答える。rejectFrom 回目から rejectCount 回の要求は 403 で断る
    /// （RangeStream は一時的な失敗として2回まで送り直すので、3回続けて断ると拒否になる）。</summary>
    private sealed class RangeServer(int size, int rejectFrom = -1, int rejectCount = 0) : HttpMessageHandler
    {
        public int Count;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancel)
        {
            var n = Interlocked.Increment(ref Count);
            if (n >= rejectFrom && n < rejectFrom + rejectCount) return Task.FromResult(new HttpResponseMessage(HttpStatusCode.Forbidden));
            var range = request.Headers.Range!.Ranges.First();
            var end = Math.Min(range.To!.Value, size - 1);
            var body = Expected(range.From!.Value, end);
            var response = new HttpResponseMessage(HttpStatusCode.PartialContent) { Content = new ByteArrayContent(body), RequestMessage = request };
            response.Content.Headers.ContentRange = new ContentRangeHeaderValue(range.From!.Value, end, size);
            return Task.FromResult(response);
        }
    }

    private static byte[] Expected(long start, long end) => Enumerable.Range((int)start, (int)(end - start + 1)).Select(i => (byte)(i % 251)).ToArray();

    private static ResolvedStream Stream(string videoId, long length) =>
        new(videoId, 140, "audio/mp4", length, "https://x.googlevideo.com/videoplayback?id=1", 0, null, 0, new DashInfo(0, 9, 10, 19, 1, 44100, 1000, "mp4a.40.2"));

    [Fact]
    public async Task ASongIsDownloadedWholeAndReResolvedWhenRejected()
    {
        var size = TrackDownloader.ChunkSize * 2 + 100;
        var server = new RangeServer(size, rejectFrom: 2, rejectCount: 3);
        var store = Store();
        var song = S("a");
        var resolves = 0;
        var rejected = 0;
        var downloader = new TrackDownloader(new HttpClient(server), store);
        var outcome = await downloader.DownloadSongAsync(song, _ =>
        {
            resolves++;
            return Task.FromResult(ResolveResult.Ok(Stream(song.VideoId, size)));
        }, () => rejected++, () => true);
        Assert.Equal(DownloadOutcome.Done, outcome);
        Assert.Equal(2, resolves);
        Assert.Equal(1, rejected);
        Assert.True(store.IsComplete(song.VideoId));
        Assert.Equal(Expected(0, size - 1), File.ReadAllBytes(store.BytesPath(song.VideoId)));
        var file = Store().Completed(song.VideoId)!;
        Assert.Equal(140, file.Itag);
        Assert.Equal(song.Title, file.Song!.Title);

        // 取った曲はネットを使わずに読める
        var local = RangeStream.OpenLocal(store.BytesPath(song.VideoId), file.Length);
        Assert.Equal(Expected(10, 19), await local.ReadRangeAsync(10, 19));
    }

    [Fact]
    public async Task ASongThatIsNoLongerWantedStopsAndUnplayableSongsAreReported()
    {
        var size = TrackDownloader.ChunkSize * 3;
        var store = Store();
        var song = S("a");
        var downloader = new TrackDownloader(new HttpClient(new RangeServer(size)), store);
        var chunks = 0;
        var outcome = await downloader.DownloadSongAsync(song, _ => Task.FromResult(ResolveResult.Ok(Stream(song.VideoId, size))), null, () => chunks++ < 1);
        Assert.Equal(DownloadOutcome.Cancelled, outcome);
        Assert.False(store.IsComplete(song.VideoId));

        Assert.Equal(DownloadOutcome.Unavailable, await downloader.DownloadSongAsync(S("b"), _ => Task.FromResult(ResolveResult.Failed(StreamFailure.Unplayable)), null, () => true));
        Assert.Equal(DownloadOutcome.Live, await downloader.DownloadSongAsync(S("c"), _ => Task.FromResult(ResolveResult.Failed(StreamFailure.LiveStream)), null, () => true));
        Assert.Equal(DownloadOutcome.Failed, await downloader.DownloadSongAsync(S("d"), _ => Task.FromResult(ResolveResult.Failed(StreamFailure.Network)), null, () => true));
        Assert.Equal(DownloadOutcome.Live, await downloader.DownloadSongAsync(S("e", live: true), _ => throw new InvalidOperationException(), null, () => true));
    }

    [Fact]
    public async Task AnRssEpisodeIsDownloadedByRange()
    {
        var size = 3000;
        var store = Store();
        var episode = new Song(Feed.AudioVideoId("https://example.com/ep.mp3"), "ep", [], null, null, null);
        var downloader = new TrackDownloader(new HttpClient(), store);
        var outcome = await downloader.DownloadEpisodeAsync(episode, new HttpClient(new RangeServer(size)), "test", () => true);
        Assert.Equal(DownloadOutcome.Done, outcome);
        var file = store.Completed(episode.VideoId)!;
        Assert.Equal("audio/mpeg", file.MimeType);
        Assert.Equal(Expected(0, size - 1), File.ReadAllBytes(store.BytesPath(episode.VideoId)));
    }

    // ---- 通信 ----

    [Fact]
    public void NetworkPolicyWaitsForAnUnmeteredNetworkByDefault()
    {
        Assert.Equal(DownloadNetwork.Unmetered, DownloadPolicy.Parse(null));
        Assert.True(DownloadPolicy.Allows(DownloadNetwork.Unmetered, connected: true, metered: false));
        Assert.False(DownloadPolicy.Allows(DownloadNetwork.Unmetered, connected: true, metered: true));
        Assert.True(DownloadPolicy.Allows(DownloadNetwork.Any, connected: true, metered: true));
        Assert.False(DownloadPolicy.Allows(DownloadNetwork.Any, connected: false, metered: false));
        Assert.Equal(DownloadNetwork.Any, DownloadPolicy.Parse(DownloadPolicy.Format(DownloadNetwork.Any)));
    }

    // ---- 再生回数 ----

    [Fact]
    public void PlayCountsCountSongsAndForgetTheOldest()
    {
        var counts = PlayCounts.Empty.Record(S("a"), 1).Record(S("a"), 5).Record(S("b"), 3)
            .Record(S("live", live: true), 6).Record(new Song(Feed.AudioVideoId("https://e.com/x.mp3"), "x", [], null, null, null), 7);
        Assert.Equal(2, counts.Entries.Count);
        Assert.Equal((2, 5L), (counts.Entries[0].Count, counts.Entries[0].LastPlayedMs));
        Assert.Equal(2, PlayCounts.Decode(counts.Encode()).Entries.Count);

        var many = PlayCounts.Empty;
        for (var i = 0; i <= PlayCounts.MaxSongs; i++) many = many.Record(S("s" + i), i);
        Assert.Equal(PlayCounts.MaxSongs, many.Entries.Count);
        Assert.DoesNotContain(many.Entries, e => e.Song.VideoId == Id("s0"));
    }

    // ---- スマートダウンロード ----

    [Fact]
    public void FrequentOrdersByCountThenRecency()
    {
        var counts = new[] { new PlayCount(S("a"), 3, 10), new PlayCount(S("b"), 3, 20), new PlayCount(S("c"), 1, 30) };
        // 履歴に出た d・e は1回と数え、c は 1+1 回になる
        var frequent = SmartDownloads.Frequent(counts, [S("d"), S("c"), S("e")]);
        Assert.Equal([Id("b"), Id("a"), Id("c"), Id("d"), Id("e")], frequent.Select(s => s.VideoId));
    }

    [Fact]
    public void LikelyTakesUnheardSongsFromEachSeedInTurn()
    {
        var radios = new List<IReadOnlyList<Song>> { new[] { S("seed1"), S("x1"), S("x2") }, new[] { S("y1"), S("x1"), S("y2") } };
        var likely = SmartDownloads.Likely(radios, new HashSet<string> { Id("seed1") });
        Assert.Equal([Id("x1"), Id("y1"), Id("x2"), Id("y2")], likely.Select(s => s.VideoId));
    }

    [Fact]
    public void RecommendedComesFromSongShelvesAndTheFirstMix()
    {
        var song = new HomeItem.SongItem(S("r1"));
        var shelves = new[]
        {
            new HomeShelf("albums", null, [new HomeItem.AlbumItem(new AlbumSummary("MP1", "a", null, null)), song]),
            new HomeShelf("quick picks", null, [song, new HomeItem.SongItem(S("r2"))]),
            new HomeShelf("mixes", null, [new HomeItem.PlaylistItem(new PlaylistSummary("PLx", "p", null, null)), new HomeItem.PlaylistItem(new PlaylistSummary("RDTMAK1", "mix", null, null))]),
        };
        Assert.Equal([Id("r1"), Id("r2")], SmartDownloads.Recommended(shelves).Select(s => s.VideoId));
        Assert.Equal("RDTMAK1", SmartDownloads.FirstMix(shelves)!.PlaylistId);
    }

    [Fact]
    public void SelectionTakesOneFromEachCategoryInTurn()
    {
        var picked = SmartDownloads.Select([S("f1", "A"), S("f2", "B"), S("f3", "C")], [S("l1", "D"), S("l2", "E")], [S("r1", "F"), S("r2", "G")], 5);
        Assert.Equal([Id("f1"), Id("l1"), Id("r1"), Id("f2"), Id("l2")], picked.Select(t => t.Song.VideoId));
        Assert.Equal([SmartCategory.Frequent, SmartCategory.Likely, SmartCategory.Recommended, SmartCategory.Frequent, SmartCategory.Likely], picked.Select(t => t.Category));
    }

    [Fact]
    public void SelectionCapsArtistsDeduplicatesAndFillsShortCategories()
    {
        // おすすめに「いつも聴いている」と同じ曲があれば、先に入った分類に入れる。アーティスト A は全体で2曲まで
        var frequent = new[] { S("f1", "A"), S("f2", "A"), S("f3", "A"), S("f4", "B") };
        var likely = Array.Empty<Song>();
        var recommended = new[] { S("f1", "A"), S("r1", "C"), S("r2", "D"), S("live", "E", live: true) };
        var picked = SmartDownloads.Select(frequent, likely, recommended, 10);
        Assert.Equal([Id("f1"), Id("r1"), Id("f2"), Id("r2"), Id("f4")], picked.Select(t => t.Song.VideoId));
        Assert.Equal(SmartCategory.Frequent, picked[0].Category);
        Assert.Equal(2, picked.Count(t => t.Song.Artists[0].Name == "A"));
    }

    [Fact]
    public void SongsLongerThanFifteenMinutesAreNotPicked()
    {
        var hour = S("hour") with { DurationSeconds = 3600 };
        var unknown = S("unknown") with { DurationSeconds = null };
        var limit = S("limit") with { DurationSeconds = SmartDownloads.MaxDurationSeconds };
        var picked = SmartDownloads.Select([hour, unknown, limit], [], [], 10);
        Assert.Equal([Id("unknown"), Id("limit")], picked.Select(t => t.Song.VideoId));
        Assert.Empty(SmartDownloads.Frequent([], [hour]));
    }

    [Fact]
    public void RefreshIsDueOnceADay()
    {
        Assert.True(SmartDownloads.IsDue(0, 100));
        Assert.False(SmartDownloads.IsDue(100, 100 + SmartDownloads.RefreshIntervalMs - 1));
        Assert.True(SmartDownloads.IsDue(100, 100 + SmartDownloads.RefreshIntervalMs));
        Assert.Equal(SmartDownloads.DefaultCount, SmartDownloads.NormalizeCount(7));
        Assert.Equal(200, SmartDownloads.NormalizeCount(200));
    }
}
