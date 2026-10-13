using Wave.Core.Lyrics;
using Wave.Core.Music;
using Wave.Core.Net;

namespace Wave.Core.Tests;

/// <summary>歌詞から曲を探す（Android 版の LyricsSearchTest と同じ確かめ方）。</summary>
public class LyricsSearchTests
{
    private static Song Track(string id, string title, string artist = "A", string? kind = Song.KindSong) =>
        new(id, title, [new ArtistRef(artist, null)], null, 200, null, kind);

    [Fact]
    public void UnfilteredSearchPutsTheTopCardFirstAndSkipsArtists()
    {
        // 歌詞の一節「沈むように溶けてゆくように」で匿名に取った応答（棚は itemSectionRenderer）
        var songs = MusicParsers.SearchTopSongs(Fixtures.Json("search_all_lyrics"));
        Assert.Equal("x8VYWazR5mE", songs[0].VideoId);
        Assert.Equal("夜に駆ける", songs[0].Title);
        Assert.Equal(Song.KindMusicVideo, songs[0].Kind);
        Assert.Equal(["YOASOBI"], songs[0].Artists.Select(a => a.Name));
        Assert.Equal(4 * 60 + 36, songs[0].DurationSeconds);
        Assert.Equal("by4SYYWlhEs", songs[1].VideoId);
        Assert.True(songs.Count >= 4);
        Assert.Equal(songs.Count, songs.Select(s => s.VideoId).Distinct().Count());
    }

    [Fact]
    public void NormalizeIgnoresCaseWidthKanaAndPunctuation()
    {
        Assert.Equal(LyricsMatcher.Normalize("I'm in love with the shape of you"), LyricsMatcher.Normalize("im in LOVE with　the shape of you!"));
        Assert.Equal(LyricsMatcher.Normalize("ＡＢＣ　カタカナ、です。"), LyricsMatcher.Normalize("abc かたかなです"));
        Assert.Equal("", LyricsMatcher.Normalize(" 、。!? "));
    }

    [Fact]
    public void FindsTheLineEvenWhenThePhraseSpansTwoLines()
    {
        var lyrics = new SongLyrics.Synced([new LyricLine(1_000, "沈むように"), new LyricLine(3_000, "溶けてゆくように"), new LyricLine(6_000, "二人だけの空が")], null);
        Assert.Equal(new LyricsMatch("沈むように", 1_000, Exact: true), LyricsMatcher.Find(lyrics, "沈むように溶けてゆく"));
        Assert.Equal(new LyricsMatch("二人だけの空が", 6_000, Exact: true), LyricsMatcher.Find(lyrics, "二人だけの"));
        var plain = new SongLyrics.Plain("Hello, it's me\n\nI was wondering if after all these years", null);
        Assert.Equal(new LyricsMatch("I was wondering if after all these years", null, Exact: true), LyricsMatcher.Find(plain, "wondering if after"));
    }

    [Fact]
    public void ASlightlyWrongPhraseIsAnApproximateMatchAndUnrelatedTextIsNone()
    {
        var lyrics = new SongLyrics.Plain("to tell you I'm sorry for everything that I've done", null);
        var near = LyricsMatcher.Find(lyrics, "to tell you sorry for everything i've done");
        Assert.False(near?.Exact ?? true);
        Assert.Null(LyricsMatcher.Find(lyrics, "never gonna give you up"));
        // 短すぎる言葉は大体の一致を取らない（何にでも合ってしまう）
        Assert.Null(LyricsMatcher.Find(lyrics, "toz"));
    }

    [Fact]
    public async Task SongsWithTheLyricsComeFirstAndTheSongReplacesItsVideo()
    {
        var video = Track("v1", "夜に駆ける", "YOASOBI", Song.KindMusicVideo);
        var other = Track("o1", "別の曲", "B");
        var atv = Track("a1", "夜に駆ける", "YOASOBI");
        var search = new LyricsSearch(
            (_, _) => Task.FromResult(ApiResult<List<Song>>.Ok([video, other, atv])),
            (song, _) => Task.FromResult<LyricsService.Result>(song.VideoId == "a1"
                ? new LyricsService.Result.Found(new SongLyrics.Plain("沈むように溶けてゆくように", null))
                : new LyricsService.Result.None()));
        var hits = (await search.SearchAsync("溶けてゆくように")).Value!;
        Assert.Equal(["a1", "o1"], hits.Select(h => h.Song.VideoId));
        Assert.Equal("沈むように溶けてゆくように", hits[0].Match?.Line);
        Assert.Null(hits[1].Match);
    }

    [Fact]
    public void UnmatchedUserVideosGoBelowUnmatchedSongs()
    {
        var cover = new LyricsHit(Track("u1", "歌ってみた", kind: Song.KindUserVideo), null);
        var plain = new LyricsHit(Track("a1", "曲"), null);
        var matched = new LyricsHit(Track("u2", "歌詞動画", kind: Song.KindUserVideo), new LyricsMatch("行", null, Exact: false));
        Assert.Equal([matched, plain, cover], LyricsSearch.Rank([cover, plain, matched]));
    }

    [Fact]
    public async Task FailedSearchIsPassedOnAndBlankQueryIsEmpty()
    {
        var asked = false;
        var search = new LyricsSearch(
            (_, _) =>
            {
                asked = true;
                return Task.FromResult(ApiResult<List<Song>>.Failed(FailureKind.Network));
            },
            (_, _) => Task.FromResult<LyricsService.Result>(new LyricsService.Result.None()));
        var blank = await search.SearchAsync(" 。");
        Assert.True(blank.IsOk);
        Assert.Empty(blank.Value!);
        Assert.False(asked);
        Assert.False((await search.SearchAsync("夜に駆ける")).IsOk);
    }
}
