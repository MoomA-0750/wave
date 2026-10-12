using System.Text.Json.Nodes;
using Wave.Core.Music;

namespace Wave.Core.Tests;

internal static class PodcastTestText
{
    public static string ReplacingFirst(this string text, string from, string to)
    {
        var at = text.IndexOf(from, StringComparison.Ordinal);
        if (at < 0) throw new InvalidOperationException("fixture の形が変わった: " + from);
        return text[..at] + to + text[(at + from.Length)..];
    }
}

/// <summary>Android 版の PodcastParsersTest と同じ確かめ方（匿名で取得した公開データ）。</summary>
public class PodcastParsersTests
{
    [Fact]
    public void PodcastSearchReadsShows()
    {
        var page = MusicParsers.SearchPodcasts(Fixtures.Json("search_podcasts"));
        Assert.Equal(8, page.Items.Count);
        var first = page.Items[0];
        Assert.Equal("MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv", first.BrowseId);
        Assert.Equal("PLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv", first.PlaylistId);
        Assert.Equal("Rebuild", first.Title);
        Assert.Equal("Rebuild Podcast", first.Author);
        Assert.StartsWith("https://", first.ThumbnailUrl);
        Assert.All(page.Items, p => Assert.StartsWith("MPSP", p.BrowseId));
        Assert.NotNull(page.Continuation);
    }

    [Fact]
    public void EpisodeSearchTakesDateAndShowNotArtist()
    {
        var page = MusicParsers.SearchEpisodes(Fixtures.Json("search_episodes"));
        Assert.Equal(8, page.Items.Count);
        var first = page.Items[0];
        Assert.Equal("ZVIAE__9jhY", first.Song.VideoId);
        Assert.StartsWith("How To Rebuild Your Character", first.Song.Title);
        // 日付をアーティストと取り違えず、番組をアーティストの代わりにする
        Assert.Equal([new ArtistRef("Munroe Global Available Teachings", null)], first.Song.Artists);
        Assert.Equal("2018/04/28", first.PublishedText);
        Assert.Equal("MPSPPLiSxXTy3ZWc52InDNx3QUGeewlp3jNmRW", first.ShowBrowseId);
        Assert.Equal("MUSIC_VIDEO_TYPE_PODCAST_EPISODE", first.Song.Kind);
        Assert.Equal("Munroe Global Available Teachings • 2018/04/28", first.Subtitle);
        Assert.All(page.Items, e => Assert.True(e.ShowBrowseId?.StartsWith("MPSP") == true && e.PublishedText is not null));
        Assert.NotNull(page.Continuation);
    }

    [Fact]
    public void PodcastPageReadsHeaderAndEpisodes()
    {
        var page = MusicParsers.PodcastPage(Fixtures.Json("browse_podcast"), "MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv");
        Assert.NotNull(page);
        Assert.Equal("Rebuild", page.Title);
        Assert.Equal("Rebuild Podcast", page.Author);
        Assert.StartsWith("ウェブ開発、プログラミング", page.Description);
        Assert.StartsWith("https://", page.ThumbnailUrl);
        Assert.Equal("PLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv", page.PlaylistId);
        // ログインしていない応答では、保存しているかは分からない
        Assert.Null(page.Saved);
        Assert.Equal(8, page.Episodes.Count);
        var first = page.Episodes[0];
        Assert.Equal("mLtB1Fr7o64", first.Song.VideoId);
        Assert.Equal("433: That Meeting Was So Jev (らんぶる)", first.Song.Title);
        Assert.Equal([new ArtistRef("Rebuild", null)], first.Song.Artists);
        Assert.Equal("6 日前", first.PublishedText);
        Assert.Equal("1 時間 37 分", first.DurationText);
        Assert.StartsWith("らんぶるさんをゲストに迎えて", first.Description);
        Assert.Null(first.ProgressPercent);
        Assert.StartsWith("https://", first.Song.ThumbnailUrl);
        Assert.NotNull(page.Continuation);
    }

    [Fact]
    public void NewEpisodesTakeShowFromEachRowAndAreNotSavable()
    {
        // 「新しいエピソード」（VLRDPN）は番組のページと同じ形で、行ごとに番組が付く（ログインが要るので番組の行を差し込んで確かめる）
        var showRun = """{"text":"Other Show","navigationEndpoint":{"browseEndpoint":{"browseId":"MPSPPLother","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"}}}}}""";
        var text = Fixtures.Text("browse_podcast").ReplacingFirst("""{"text":"2294回視聴"}""", showRun);
        var page = MusicParsers.PodcastPage(Fixtures.Parse(text), MusicRepository.NewEpisodesBrowse);
        Assert.NotNull(page);
        Assert.Equal("RDPN", page.PlaylistId);
        Assert.Null(page.Saved);
        var first = page.Episodes[0];
        Assert.Equal([new ArtistRef("Other Show", null)], first.Song.Artists);
        Assert.Equal("MPSPPLother", first.ShowBrowseId);
        Assert.Equal("6 日前", first.PublishedText);
        Assert.Equal([new ArtistRef(page.Title, null)], page.Episodes[1].Song.Artists);
    }

    [Fact]
    public void PodcastContinuationReadsEpisodes()
    {
        var show = new ArtistRef("Rebuild", null);
        var page = MusicParsers.PodcastEpisodes(Fixtures.Json("browse_podcast_continuation"), show, "MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv");
        Assert.Equal(4, page.Items.Count);
        var first = page.Items[0];
        Assert.Equal("h61yHZbIQvk", first.Song.VideoId);
        Assert.Equal("413: Always Fully Hyped (yuka)", first.Song.Title);
        Assert.Equal([show], first.Song.Artists);
        Assert.Equal("2025/09/08", first.PublishedText);
        Assert.Equal("1 時間 48 分", first.DurationText);
        Assert.Null(page.Continuation);
    }

    [Fact]
    public void LibraryPodcastsReadCardsAndRows()
    {
        // ログインが要るので、カードの形で確かめる（番組以外のカードは除く）
        const string card = """
            {"contents":{"gridRenderer":{"items":[
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"New episodes"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDPN","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}}},
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Rebuild"}]},"subtitle":{"runs":[{"text":"Rebuild Podcast"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"}}}}}}
            ]}}}
            """;
        var shows = MusicParsers.LibraryPodcasts(Fixtures.Parse(card));
        Assert.Equal([new PodcastSummary("MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv", "Rebuild", "Rebuild Podcast", null)], shows);
        Assert.Equal(8, MusicParsers.LibraryPodcasts(Fixtures.Json("search_podcasts")).Count);
    }

    [Fact]
    public void UnknownShapesGiveEmptyResults()
    {
        var empty = new JsonObject();
        Assert.Empty(MusicParsers.SearchPodcasts(empty).Items);
        Assert.Empty(MusicParsers.SearchEpisodes(empty).Items);
        Assert.Null(MusicParsers.PodcastPage(empty, "MPSPx"));
        Assert.Empty(MusicParsers.LibraryPodcasts(empty));
    }
}
