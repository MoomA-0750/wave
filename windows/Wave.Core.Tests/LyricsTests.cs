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

    private static LrclibClient.Track Track(string title, string artist, double duration) =>
        new(title, artist, null, duration, false, "[00:01.00]a");

    [Fact]
    public void PickPrefersTheClosestDuration()
    {
        List<LrclibClient.Track> tracks = [Track("t", "a", 200), new("t", "a", null, 181, false, null), Track("t", "a", 184)];
        Assert.Equal(184, LrclibClient.Pick(tracks, "t", "a", 182)?.Duration);
        Assert.Null(LrclibClient.Pick(tracks, "t", "a", 100));
    }

    [Fact]
    public void PickSkipsADifferentSongOfTheSameLength()
    {
        // 同じ長さの別の曲が先に並んでも、曲名とアーティストの合う方を選ぶ
        List<LrclibClient.Track> tracks = [Track("別の曲", "別の人", 262), Track("群青", "YOASOBI", 263)];
        Assert.Equal("群青", LrclibClient.Pick(tracks, "群青", "YOASOBI", 262)?.TrackName);
        // 合うものが無ければ選ばない
        Assert.Null(LrclibClient.Pick([tracks[0]], "群青", "YOASOBI", 262));
        Assert.Null(LrclibClient.Pick([tracks[0]], "群青", "YOASOBI", null));
        // 曲名だけ合ってもアーティストが違えば選ばない
        Assert.Null(LrclibClient.Pick([Track("群青", "別の人", 262)], "群青", "YOASOBI", 262));
    }

    [Fact]
    public void PickMatchesDespiteDecorationsAndWidth()
    {
        List<LrclibClient.Track> tracks = [Track("Ｓｏｎｇ", "ＡＢＣ, Someone", 200)];
        Assert.NotNull(LrclibClient.Pick(tracks, "song (feat. X) [Remix]", "abc", 200));
        Assert.NotNull(LrclibClient.Pick(tracks, "Song - From \"THE FIRST TAKE\"", "abc", 200));
        Assert.NotNull(LrclibClient.Pick(tracks, "Song feat. Someone", "Someone", 200));
    }

    [Fact]
    public void PickAcceptsARomanizedTitleOnlyByArtistAndCloseLength()
    {
        List<LrclibClient.Track> tracks = [Track("Gunjo", "YOASOBI", 250)];
        Assert.NotNull(LrclibClient.Pick(tracks, "群青", "YOASOBI", 249));
        Assert.Null(LrclibClient.Pick(tracks, "群青", "YOASOBI", 246));
        Assert.Null(LrclibClient.Pick(tracks, "群青", "YOASOBI", null));
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

    // 言語で表記の違う曲（YouTube Music では「緑黄色社会」、LRCLIB では「Ryokuoushoku Shakai」）

    private static string TrackJson(string title, string artist, double duration, string? synced = "[00:01.00]合成の歌詞") =>
        $$"""{"trackName":"{{title}}","artistName":"{{artist}}","duration":{{duration}},"instrumental":false,"syncedLyrics":{{(synced is null ? "null" : $"\"{synced}\"")}}}""";

    /// <summary>LRCLIB。アーティスト名付きの検索は withArtist、曲名だけの検索は byTitle を返す。</summary>
    private static Server Lrclib(string withArtist, string byTitle) =>
        new((request, _) => request.RequestUri!.AbsolutePath.EndsWith("/search")
            ? JsonResponse(request.RequestUri.Query.Contains("artist_name=") ? withArtist : byTitle)
            : new HttpResponseMessage(HttpStatusCode.NotFound));

    private static readonly LrclibClient.OtherNames Yureru = new("Yureru", "Ryokuoushoku Shakai");

    [Fact]
    public async Task OtherLanguageNamesMatchWhenTheOriginalNamesDoNot()
    {
        // アーティスト名付きの検索では返らず、曲名だけの検索で返る
        var byTitle = $"[{TrackJson("揺れる", "ねごと", 205, "[00:01.00]別の人")},{TrackJson("揺れる", "Ryokuoushoku Shakai", 238, "[00:02.00]合う")}]";
        var calls = 0;
        var client = new LrclibClient(new HttpClient(Lrclib("[]", byTitle)), "wave-test/1");
        var result = await client.SyncedAsync("揺れる", "緑黄色社会", null, 238, otherNames: _ =>
        {
            calls++;
            return Task.FromResult<LrclibClient.OtherNames?>(Yureru);
        });
        Assert.Equal("合う", Assert.Single(Assert.IsType<LrclibClient.Result.Found>(result).Lines).Text);
        Assert.Equal(1, calls);
        // 曲名が別名（ローマ字）でだけ一致する
        var romanized = new LrclibClient(new HttpClient(Lrclib("[]", $"[{TrackJson("Yureru", "Ryokuoushoku Shakai", 234)}]")), "wave-test/1");
        Assert.IsType<LrclibClient.Result.Found>(await romanized.SyncedAsync("揺れる", "緑黄色社会", null, 238, otherNames: _ => Task.FromResult<LrclibClient.OtherNames?>(Yureru)));
    }

    [Fact]
    public async Task NothingIsPickedWhenTheOtherNamesDoNotMatchEither()
    {
        var calls = 0;
        var client = new LrclibClient(new HttpClient(Lrclib("[]", $"[{TrackJson("揺れる", "ねごと", 205)}]")), "wave-test/1");
        var result = await client.SyncedAsync("揺れる", "緑黄色社会", null, 238, otherNames: _ =>
        {
            calls++;
            return Task.FromResult<LrclibClient.OtherNames?>(Yureru);
        });
        Assert.IsType<LrclibClient.Result.NotFound>(result);
        Assert.Equal(1, calls);
        // 別名が取れなければ今までどおり選ばない
        var romaji = new LrclibClient(new HttpClient(Lrclib("[]", $"[{TrackJson("揺れる", "Ryokuoushoku Shakai", 238)}]")), "wave-test/1");
        Assert.IsType<LrclibClient.Result.NotFound>(await romaji.SyncedAsync("揺れる", "緑黄色社会", null, 238, otherNames: _ => Task.FromResult<LrclibClient.OtherNames?>(null)));
    }

    [Fact]
    public async Task OtherNamesAreNotFetchedWhenATrackMatchesOrNoneIsSynced()
    {
        var calls = 0;
        Task<LrclibClient.OtherNames?> Other(CancellationToken _)
        {
            calls++;
            return Task.FromResult<LrclibClient.OtherNames?>(Yureru);
        }
        var matching = Lrclib($"[{TrackJson("揺れる", "緑黄色社会", 238)}]", "[]");
        Assert.IsType<LrclibClient.Result.Found>(await new LrclibClient(new HttpClient(matching), "wave-test/1").SyncedAsync("揺れる", "緑黄色社会", null, 238, otherNames: Other));
        // 一致したら曲名だけの検索もしない
        Assert.Equal(["/api/get", "/api/search"], matching.Requests.Select(r => r.Path));
        // 時刻付きの候補が無ければ取りに行かない
        var untimed = new LrclibClient(new HttpClient(Lrclib("[]", $"[{TrackJson("揺れる", "Ryokuoushoku Shakai", 238, null)}]")), "wave-test/1");
        Assert.IsType<LrclibClient.Result.NotFound>(await untimed.SyncedAsync("揺れる", "緑黄色社会", null, 238, otherNames: Other));
        var empty = new LrclibClient(new HttpClient(Lrclib("[]", "[]")), "wave-test/1");
        Assert.IsType<LrclibClient.Result.NotFound>(await empty.SyncedAsync("揺れる", "緑黄色社会", null, 238, otherNames: Other));
        Assert.Equal(0, calls);
    }

    [Fact]
    public async Task OtherLanguageNamesAreAskedOncePerSongAndUsedToMatch()
    {
        var youtube = new Server((request, _) => request.RequestUri!.AbsolutePath switch
        {
            var p when p.EndsWith("/next") => JsonResponse(Fixtures.Text("next_radio")),
            var p when p.EndsWith("/browse") => JsonResponse(Fixtures.Text("browse_lyrics")),
            _ => new HttpResponseMessage(HttpStatusCode.NotFound),
        });
        var music = new MusicRepository(new InnerTubeClient(new HttpClient(youtube), "ja", "JP"));
        var byTitle = $"[{TrackJson("夜に駆ける", "YOASOBI", 262)}]";
        var lrclib = new Server((request, _) => request.RequestUri!.AbsolutePath.EndsWith("/search")
            ? JsonResponse(request.RequestUri.Query.Contains("artist_name=") ? "[]" : byTitle)
            : new HttpResponseMessage(HttpStatusCode.NotFound));
        var service = new LyricsService(music, new LrclibClient(new HttpClient(lrclib), "wave-test/1"), new YouTubeTimedLyricsClient(new HttpClient(Timed(HttpStatusCode.ServiceUnavailable)), "ja", "JP"));
        // 画面の言語では「Racing Into The Night」「Ayase」。もう1つの言語の next（合成の応答）では「夜に駆ける」「YOASOBI」
        var song = new Song("by4SYYWlhEs", "Racing Into The Night", [new ArtistRef("Ayase", null)], null, 262, null);
        var found = Assert.IsType<LyricsService.Result.Found>(await service.LyricsAsync(song));
        Assert.Equal(LyricsService.LrclibSource, Assert.IsType<SongLyrics.Synced>(found.Lyrics).Source);
        var nexts = youtube.Requests.Where(r => r.Path.EndsWith("/next")).Select(r => r.Body).ToList();
        Assert.Equal(2, nexts.Count);
        Assert.Contains(nexts, b => b.Contains("\"hl\":\"ja\""));
        Assert.Contains(nexts, b => b.Contains("\"hl\":\"en\""));

        // 別名でも一致しない（時刻なしを出し、時刻付きの失敗のあとなので覚えない）。同じ曲の別名は覚えていて頼み直さない
        byTitle = $"[{TrackJson("別の曲", "別の人", 100)}]";
        var other = new Song("other", "t", [new ArtistRef("a", null)], null, 262, null);
        Assert.IsType<SongLyrics.Plain>(Assert.IsType<LyricsService.Result.Found>(await service.LyricsAsync(other)).Lyrics);
        var before = youtube.Requests.Count(r => r.Path.EndsWith("/next"));
        Assert.IsType<SongLyrics.Plain>(Assert.IsType<LyricsService.Result.Found>(await service.LyricsAsync(other)).Lyrics);
        Assert.Equal(before + 1, youtube.Requests.Count(r => r.Path.EndsWith("/next")));
    }
}
