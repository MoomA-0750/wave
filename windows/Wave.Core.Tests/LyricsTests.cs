using System.Net;
using Wave.Core.Lyrics;
using Wave.Core.Music;
using Wave.Core.Net;

namespace Wave.Core.Tests;

public class LyricsTests
{
    [Fact]
    public void LrcLinesAreSortedAndOffsetApplied()
    {
        var lines = Lrc.Parse("[ar:x]\n[offset:+500]\n[00:02.00][00:10.5]サビ\n[00:01.905]はじめ\nno time");
        Assert.Equal([1405L, 1500L, 10000L], lines.Select(l => l.StartMs));
        Assert.Equal("はじめ", lines[0].Text);
    }

    [Fact]
    public void CurrentIndexLeadsSlightly()
    {
        var lines = new List<LyricLine> { new(1000, "a"), new(2000, "b") };
        Assert.Equal(-1, LyricsTiming.CurrentIndex(lines, 500));
        Assert.Equal(0, LyricsTiming.CurrentIndex(lines, 900));
        Assert.Equal(1, LyricsTiming.CurrentIndex(lines, 5000));
    }

    [Fact]
    public void SearchTitlesDropDecorations()
    {
        Assert.Equal(["夜に駆ける (From \"THE FIRST TAKE\")", "夜に駆ける"], LrclibClient.SearchTitles("夜に駆ける (From \"THE FIRST TAKE\")"));
        Assert.Equal(["Song - Orion", "Song"], LrclibClient.SearchTitles("Song - Orion"));
    }

    // YouTube Music の時刻付き（Android 版の LyricsRepositoryTest と同じ確かめ方）

    [Fact]
    public void TimedYouTubeLyricsAreReadAndUntimedLinesAreNot()
    {
        var synced = YouTubeTimedLyricsClient.Parse(Fixtures.Json("browse_lyrics_timed"));
        Assert.NotNull(synced);
        Assert.Equal([new LyricLine(1530, "一行目"), new LyricLine(9040, "二行目"), new LyricLine(17000, "")], synced.Lines);
        Assert.Equal("提供元: Musixmatch", synced.Source);
        // MV の歌詞（時刻の無い行だけ）と、古い形（時刻なしの棚）は時刻付きにしない
        Assert.Null(YouTubeTimedLyricsClient.Parse(Fixtures.Parse(Fixtures.Text("browse_lyrics_timed").Replace("\"cueRange\"", "\"noCueRange\""))));
        Assert.Null(YouTubeTimedLyricsClient.Parse(Fixtures.Json("browse_lyrics")));
    }

    /// <summary>決めた応答を返し、要求を覚える。</summary>
    private sealed class Server(Func<HttpRequestMessage, string, HttpResponseMessage> respond) : HttpMessageHandler
    {
        public List<(string Path, string Body)> Requests { get; } = [];

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancel)
        {
            var body = request.Content is null ? "" : await request.Content.ReadAsStringAsync(cancel);
            lock (Requests) Requests.Add((request.RequestUri!.AbsolutePath, body));
            return respond(request, body);
        }
    }

    private static HttpResponseMessage JsonResponse(string body) => new(HttpStatusCode.OK) { Content = new StringContent(body) };

    /// <summary>YouTube Music（next で歌詞のタブ、browse で時刻なしの歌詞）。</summary>
    private static MusicRepository Music() =>
        new(new InnerTubeClient(new HttpClient(new Server((request, _) => request.RequestUri!.AbsolutePath switch
        {
            var p when p.EndsWith("/next") => JsonResponse(Fixtures.Text("next_radio")),
            var p when p.EndsWith("/browse") => JsonResponse(Fixtures.Text("browse_lyrics")),
            _ => new HttpResponseMessage(HttpStatusCode.NotFound),
        })), "ja", "JP"));

    /// <summary>LRCLIB は、1件を引いて無ければ 404、検索で無ければ空の一覧を返す。</summary>
    private static Server Lrclib() =>
        new((request, _) => request.RequestUri!.AbsolutePath.EndsWith("/search") ? JsonResponse("[]") : new HttpResponseMessage(HttpStatusCode.NotFound));

    private static Server Timed(HttpStatusCode status) =>
        new((_, _) => status == HttpStatusCode.OK ? JsonResponse(Fixtures.Text("browse_lyrics_timed")) : new HttpResponseMessage(status));

    private static readonly Song TimedSong = new("vid", "t", [new ArtistRef("a", null)], null, 262, null);

    [Fact]
    public async Task TimedYouTubeLyricsComeFirstWithoutAskingLrclib()
    {
        var lrclib = Lrclib();
        var timed = Timed(HttpStatusCode.OK);
        var service = new LyricsService(Music(), new LrclibClient(new HttpClient(lrclib), "wave-test/1"), new YouTubeTimedLyricsClient(new HttpClient(timed), "ja", "JP"));

        var result = Assert.IsType<LyricsService.Result.Found>(await service.LyricsAsync(TimedSong));
        Assert.Equal("提供元: Musixmatch", Assert.IsType<SongLyrics.Synced>(result.Lyrics).Source);
        // 歌詞のタブの ID だけを ANDROID_MUSIC として送る。LRCLIB には聞かない
        var request = Assert.Single(timed.Requests);
        Assert.Contains("MPLYt_LfJd6hctedK-1", request.Body);
        Assert.Contains("ANDROID_MUSIC", request.Body);
        Assert.Empty(lrclib.Requests);
    }

    [Fact]
    public async Task PlainLyricsShownAfterAFailureAreNotRemembered()
    {
        var timed = Timed(HttpStatusCode.ServiceUnavailable);
        var service = new LyricsService(Music(), new LrclibClient(new HttpClient(Lrclib()), "wave-test/1"), new YouTubeTimedLyricsClient(new HttpClient(timed), "ja", "JP"));

        var result = Assert.IsType<LyricsService.Result.Found>(await service.LyricsAsync(TimedSong));
        Assert.IsType<SongLyrics.Plain>(result.Lyrics);
        await service.LyricsAsync(TimedSong);
        // 時刻付きの失敗のあとの時刻なしを覚えていれば、2回目は尋ねない
        Assert.Equal(2, timed.Requests.Count);
    }
}
