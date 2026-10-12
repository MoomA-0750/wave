using System.Text.Json.Nodes;
using Wave.Core.Music;

namespace Wave.Core.Tests;

/// <summary>ライブ配信の検索・HLS の解決・Lo-Fi のタイル（Android の LiveParsersTest と同じ確かめ方。匿名で取得した公開データ）。</summary>
public class LiveParsersTests
{
    private static string MasterText() => File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "fixtures", "hls_master.m3u8"));

    [Fact]
    public void WebLiveSearchKeepsOnlyLiveVideos()
    {
        var page = LiveParsers.WebSearch(Fixtures.Json("search_live_web"));
        Assert.Equal(["a_1ezVmZzig", "2BG9FPatcEs", "Jo5fOHMS9WQ"], page.Songs.Select(s => s.VideoId));
        var first = page.Songs[0];
        Assert.True(first.IsLive);
        Assert.Null(first.DurationSeconds);
        Assert.Equal(["The Japanese Town"], first.Artists.Select(a => a.Name));
        Assert.StartsWith("1980s & 90s", first.Title);
        Assert.StartsWith("https://i.ytimg.com/", first.ThumbnailUrl);
        Assert.NotNull(page.Continuation);
    }

    [Fact]
    public void MusicSearchMarksLiveBadgeRows()
    {
        var songs = MusicParsers.SearchSongs(Fixtures.Json("search_videos_live")).Songs;
        Assert.Equal(["rFZHOHl-L8A", "JD-kMIpDfnY", "ffGvaOKK4eM"], songs.Select(s => s.VideoId));
        Assert.Equal([true, true, false], songs.Select(s => s.IsLive));
        Assert.Null(songs[0].DurationSeconds);
        Assert.Equal("Lofi Girl", Assert.Single(songs[0].Artists).Name);
        Assert.Equal(67 * 60 + 10, songs[2].DurationSeconds);
    }

    [Fact]
    public void PlayerResponseGivesManifestOnlyWhenPlayable()
    {
        var player = Fixtures.Json("player_live_android");
        Assert.True(LiveParsers.IsLive(player));
        var url = LiveParsers.HlsManifestUrl(player);
        Assert.NotNull(url);
        Assert.StartsWith("https://manifest.googlevideo.com/api/manifest/hls_variant/", url);
        Assert.Equal(21540L, LiveParsers.ExpiresInSeconds(player));
        var unplayable = player.DeepClone();
        unplayable["playabilityStatus"] = new JsonObject { ["status"] = "UNPLAYABLE" };
        Assert.Null(LiveParsers.HlsManifestUrl(unplayable));
        Assert.Null(LiveParsers.HlsManifestUrl(new JsonObject()));
        Assert.False(LiveParsers.IsLive(new JsonObject()));
    }

    [Fact]
    public void MasterPlaylistChoosesSmallestAacLcVariant()
    {
        const string masterUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1/id/x/file/index.m3u8";
        var variants = LiveParsers.Variants(MasterText(), masterUrl);
        Assert.Equal([290288L, 546239L, 1209862L, 1568726L, 2969452L, 5420722L], variants.Select(v => v.Bandwidth));
        var chosen = LiveParsers.ChooseAudioVariant(variants);
        Assert.NotNull(chosen);
        Assert.Equal(1209862L, chosen.Bandwidth);
        Assert.Contains("/itag/93/", chosen.Url);
        Assert.True(LiveUrls.IsAllowedManifest(chosen.Url));
    }

    [Fact]
    public void VariantsResolveRelativeUrisAndFallBackToSmallest()
    {
        const string master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=900,CODECS="mp4a.40.5,avc1.42C00B"
            low/index.m3u8
            #EXT-X-STREAM-INF:CODECS="mp4a.40.5,avc1.4D4015",BANDWIDTH=500
            /abs/index.m3u8
            """;
        var variants = LiveParsers.Variants(master, "https://manifest.googlevideo.com/api/manifest/hls_variant/master.m3u8?x=1");
        Assert.Equal(
            ["https://manifest.googlevideo.com/api/manifest/hls_variant/low/index.m3u8", "https://manifest.googlevideo.com/abs/index.m3u8"],
            variants.Select(v => v.Url));
        Assert.Equal(500L, LiveParsers.ChooseAudioVariant(variants)?.Bandwidth);
        Assert.Null(LiveParsers.ChooseAudioVariant([]));
    }

    [Fact]
    public void LiveUrlPolicies()
    {
        Assert.True(LiveUrls.IsAllowedManifest("https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1/id/x"));
        Assert.False(LiveUrls.IsAllowedManifest("http://manifest.googlevideo.com/api/manifest/hls_playlist/x"));
        Assert.False(LiveUrls.IsAllowedManifest("https://evil.example.com/api/manifest/hls_playlist/x"));
        Assert.False(LiveUrls.IsAllowedManifest("https://manifest.googlevideo.com/videoplayback"));
        Assert.True(LiveUrls.IsAllowedSegment("https://rr2---sn-x.googlevideo.com/videoplayback/id/x/itag/93/sq/1/file/seg.ts"));
        Assert.True(LiveUrls.IsAllowedSegment("https://rr2---sn-x.googlevideo.com/videoplayback?id=1"));
        Assert.False(LiveUrls.IsAllowedSegment("https://rr2---sn-x.googlevideo.com/other"));
        Assert.False(LiveUrls.IsAllowedSegment("https://googlevideo.com.evil.example/videoplayback/x"));
    }

    [Fact]
    public void FeaturedPlaylistsReadAndFilterLofi()
    {
        var page = MusicParsers.SearchPlaylists(Fixtures.Json("search_playlists_lofi"));
        Assert.Equal(["Lofi Loft", "Cozy Jazz", "J-Lofi", "Living Room Lofi"], page.Items.Select(p => p.Title));
        Assert.All(page.Items, p => Assert.StartsWith("RDCLAK5uy_", p.PlaylistId));
        Assert.Equal(["Lofi Loft", "J-Lofi", "Living Room Lofi"], LofiGenre.Featured(page.Items).Select(p => p.Title));
    }

    [Fact]
    public void LofiTileGoesFirstInGenres()
    {
        var sections = MusicParsers.Moods(Fixtures.Json("browse_moods"));
        var added = LofiGenre.AddTo(sections);
        var genres = added.Single(s => s.Title == "Genres");
        Assert.Equal(LofiGenre.Category, genres.Categories[0]);
        Assert.Equal(sections.Single(s => s.Title == "Genres").Categories, genres.Categories.Skip(1));
        Assert.Same(sections[0], added[0]);
        // 二度足さない
        Assert.Equal(added.Select(s => s.Categories.Count), LofiGenre.AddTo(added).Select(s => s.Categories.Count));
        // 見出しが分からなければ最後の格子に足す
        var unknown = sections.Select(s => s with { Title = "x" }).ToList();
        Assert.Equal(LofiGenre.Params, LofiGenre.AddTo(unknown)[^1].Categories[0].Params);
        Assert.Empty(LofiGenre.AddTo([]));
    }
}
