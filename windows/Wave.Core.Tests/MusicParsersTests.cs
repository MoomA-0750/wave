using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Wave.Core.Music;

namespace Wave.Core.Tests;

/// <summary>Android 版の MusicParsersTest・SearchParsersTest・HomeParsersTest・ArtistAlbumParsersTest と同じ確かめ方。</summary>
public class MusicParsersTests
{
    [Fact]
    public void SearchSongsFirstPage()
    {
        var page = MusicParsers.SearchSongs(Fixtures.Json("search_songs"));
        Assert.Equal(3, page.Songs.Count);
        var song = page.Songs[0];
        Assert.False(string.IsNullOrWhiteSpace(song.VideoId));
        Assert.NotEmpty(song.Artists);
        Assert.NotNull(song.Album);
        Assert.NotNull(song.DurationSeconds);
        Assert.StartsWith("https://", song.ThumbnailUrl);
        Assert.NotNull(page.Continuation);
    }

    [Fact]
    public void SearchSongsContinuationPage()
    {
        var page = MusicParsers.SearchSongs(Fixtures.Json("search_songs_continuation"));
        Assert.Equal(3, page.Songs.Count);
        Assert.All(page.Songs, s => Assert.True(s.Artists.Count > 0 && s.DurationSeconds is not null));
        Assert.NotNull(page.Continuation);
    }

    [Fact]
    public void UpNextPages()
    {
        var first = MusicParsers.UpNext(Fixtures.Json("next_radio"));
        Assert.Equal(4, first.Songs.Count);
        Assert.All(first.Songs, s => Assert.True(s.Artists.Count > 0 && s.DurationSeconds is not null));
        Assert.StartsWith("RDAMVM", first.PlaylistId);
        Assert.NotNull(first.Continuation);
        var next = MusicParsers.UpNext(Fixtures.Json("next_radio_continuation"));
        Assert.Equal(4, next.Songs.Count);
        Assert.NotEqual(first.Songs.Select(s => s.VideoId), next.Songs.Select(s => s.VideoId));
    }

    [Fact]
    public void UnknownShapesReturnEmpty()
    {
        var empty = new JsonObject();
        Assert.Empty(MusicParsers.SearchSongs(empty).Songs);
        Assert.Empty(MusicParsers.UpNext(empty).Songs);
        Assert.True(MusicParsers.SearchSuggestions(empty).IsEmpty);
        Assert.Empty(MusicParsers.HomePage(empty).Shelves);
        var broken = Fixtures.Parse("""{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"no id"}]}}}]}""").AsObject();
        Assert.Null(MusicParsers.ResponsiveListItemSong(broken));
    }

    [Fact]
    public void Durations()
    {
        Assert.Equal(262, MusicParsers.ParseDuration("4:22"));
        Assert.Equal(3723, MusicParsers.ParseDuration("1:02:03"));
        Assert.Null(MusicParsers.ParseDuration("1.1B plays"));
        Assert.Null(MusicParsers.ParseDuration("2021"));
    }

    [Fact]
    public void SuggestionsSplitQueriesAndRowsByKind()
    {
        var s = MusicParsers.SearchSuggestions(Fixtures.Json("search_suggestions"));
        Assert.Equal("yoasobi", s.Queries[0]);
        Assert.Equal(6, s.Queries.Count);
        Assert.Equal(["UCI6B8NkZKqlFWoiC_xE-hzA"], s.Artists.Select(a => a.BrowseId));
        Assert.Equal(4, s.Songs.Count);
        Assert.Equal("夜に駆ける", s.Songs[0].Title);
        Assert.Empty(s.Albums);
    }

    [Fact]
    public void AlbumAndArtistSearch()
    {
        var albums = MusicParsers.SearchAlbums(Fixtures.Json("search_albums"));
        Assert.True(albums.Items.Count >= 10);
        Assert.Equal("MPREb_hqiB0KumHYT", albums.Items[0].BrowseId);
        Assert.Equal("EP • YOASOBI • 2021", albums.Items[0].Subtitle);
        Assert.NotNull(albums.Continuation);
        var artists = MusicParsers.SearchArtists(Fixtures.Json("search_artists"));
        Assert.Equal(["YOASOBI", "Lilas", "Ayase"], artists.Items.Take(3).Select(a => a.Name));
        Assert.Null(artists.Continuation);
    }

    [Fact]
    public void HomeShelvesAndChips()
    {
        var page = MusicParsers.HomePage(Fixtures.Json("browse_home"));
        Assert.Equal(2, page.Shelves.Count);
        Assert.All(page.Shelves, shelf =>
        {
            Assert.Equal(10, shelf.Items.Count);
            Assert.All(shelf.Items, i => Assert.IsType<HomeItem.PlaylistItem>(i));
        });
        Assert.DoesNotContain("VL", ((HomeItem.PlaylistItem)page.Shelves[0].Items[0]).Playlist.PlaylistId[..2]);
        Assert.NotNull(page.Continuation);
        Assert.Equal(10, page.Chips.Count);
        Assert.Equal("Sleep", page.Chips[0].Title);
    }

    [Fact]
    public void NewReleasesMoodsAndMoodCategory()
    {
        var albums = MusicParsers.NewReleases(Fixtures.Json("browse_new_releases"));
        Assert.Equal(6, albums.Count);
        Assert.Equal("MPREb_zjXWoD4kzS1", albums[0].BrowseId);
        var moods = MusicParsers.Moods(Fixtures.Json("browse_moods"));
        Assert.Equal(["Moods & moments", "Genres"], moods.Select(m => m.Title));
        Assert.Equal([11, 27], moods.Select(m => m.Categories.Count));
        Assert.Equal(4288988671L, moods[0].Categories[0].Color);
        var category = MusicParsers.HomePage(Fixtures.Json("browse_mood_category"));
        Assert.Equal(2, category.Shelves.Count);
        Assert.All(category.Shelves, s => Assert.Equal(4, s.Items.Count));
    }

    [Fact]
    public void LibraryArtistRowsOpenTheChannelAndContinuationsAreRead()
    {
        var shelf = Fixtures.Parse("""{"continuationContents":{"musicShelfContinuation":{"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"n"}]}}}],"navigationEndpoint":{"browseEndpoint":{"browseId":"MPLAUCy","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_LIBRARY_ARTIST"}}}}}}],"continuations":[{"nextContinuationData":{"continuation":"t2"}}]}}}""");
        Assert.Equal("UCy", Assert.IsType<HomeItem.ArtistItem>(Assert.Single(MusicParsers.LibraryCards(shelf))).Artist.BrowseId);
        Assert.Equal("t2", MusicParsers.LibraryContinuation(shelf));
    }

    [Fact]
    public void AlbumRowsAreFilledFromTheHeader()
    {
        var page = MusicParsers.AlbumPage(Fixtures.Json("browse_album"), "MPREb_test")!;
        Assert.Equal(4, page.Songs.Count);
        Assert.StartsWith("OLAK5uy_", page.PlaylistId);
        Assert.StartsWith("UC", Assert.Single(page.Artists).BrowseId);
        Assert.All(page.Songs, s =>
        {
            Assert.NotNull(s.DurationSeconds);
            Assert.Equal(page.Artists, s.Artists);
            Assert.Equal(new AlbumRef(page.Title, "MPREb_test"), s.Album);
            Assert.Equal(page.ThumbnailUrl, s.ThumbnailUrl);
        });
        var greyed = Fixtures.Text("browse_album").ReplaceFirst("\"musicResponsiveListItemRenderer\":{", "\"musicResponsiveListItemRenderer\":{\"musicItemRendererDisplayPolicy\":\"MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT\",");
        Assert.Equal(3, MusicParsers.AlbumPage(Fixtures.Parse(greyed), "MPREb_test")!.Songs.Count);
    }

    [Fact]
    public void ArtistPage()
    {
        var page = MusicParsers.ArtistPage(Fixtures.Json("browse_artist"))!;
        Assert.Equal(5, page.TopSongs.Count);
        Assert.StartsWith("OLAK5uy_", page.AllSongsPlaylistId);
        Assert.Equal(2, page.AlbumShelves.Count);
        Assert.All(page.AlbumShelves.SelectMany(s => s.Albums), a => Assert.StartsWith("MPREb_", a.BrowseId));
        Assert.Null(MusicParsers.ArtistPage(Fixtures.Json("browse_album")));
        Assert.Null(MusicParsers.AlbumPage(Fixtures.Json("search_songs"), "MPREb_x"));
    }

    [Fact]
    public void LikeStatusIsReadOnlyForTheRequestedSong()
    {
        var text = Fixtures.Text("next_radio");
        var videoId = Regex.Match(text.Replace(" ", ""), "\"target\":\\{\"videoId\":\"([^\"]+)\"").Groups[1].Value;
        Assert.Equal(LikeStatus.Indifferent, MusicParsers.SongInfo(Fixtures.Json("next_radio"), videoId).Like);
        Assert.Null(MusicParsers.SongInfo(Fixtures.Json("next_radio"), "other").Like);
        Assert.Equal(LikeStatus.Like, MusicParsers.SongInfo(Fixtures.Parse(text.Replace("\"INDIFFERENT\"", "\"LIKE\"")), videoId).Like);
    }

    [Fact]
    public void PlaylistPagesAndSignInPrompt()
    {
        var page = MusicParsers.PlaylistPage(Fixtures.Json("browse_playlist"));
        Assert.NotEmpty(page.Songs);
        Assert.NotNull(page.Title);
        Assert.Equal(page.Songs.Count, page.SetVideoIds.Count);
        var next = MusicParsers.PlaylistPage(Fixtures.Json("browse_playlist_continuation"));
        Assert.NotEmpty(next.Songs);
        Assert.True(MusicParsers.IsSignInPrompt(Fixtures.Json("browse_library_logged_out")));
        Assert.False(MusicParsers.IsSignInPrompt(Fixtures.Json("browse_playlist")));
    }

    [Fact]
    public void PlainLyrics()
    {
        var lyrics = MusicParsers.PlainLyrics(Fixtures.Json("browse_lyrics"));
        Assert.NotNull(lyrics);
        Assert.False(string.IsNullOrWhiteSpace(lyrics!.Text));
    }

    [Fact]
    public void ArtistPostsAreReadAndLiveAnnouncementsAreKept()
    {
        var root = JsonNode.Parse("""
            {"contents":{"tabs":[{"tabRenderer":{"content":{"items":[
            {"backstagePostThreadRenderer":{"post":{"backstagePostRenderer":{"postId":"Ugkx1","contentText":{"runs":[{"text":"台北ドーム2DAYS開催決定！\n"},{"text":"チケット販売は9月16日から"}]},
              "publishedTimeText":{"runs":[{"text":"2 日前"}]},
              "backstageAttachment":{"backstageImageRenderer":{"image":{"thumbnails":[{"url":"//yt3.ggpht.com/small"},{"url":"//yt3.ggpht.com/large"}]}}}}}}},
            {"backstagePostThreadRenderer":{"post":{"backstagePostRenderer":{"postId":"Ugkx2","contentText":{"runs":[{"text":"新曲 10/14 配信リリース決定！"}]}}}}}
            ]}}}]}}
            """)!;
        var posts = MusicParsers.ArtistPosts(root);
        Assert.Equal(["Ugkx1", "Ugkx2"], posts.Select(p => p.PostId));
        Assert.Equal("台北ドーム2DAYS開催決定！\nチケット販売は9月16日から", posts[0].Text);
        Assert.Equal("2 日前", posts[0].PublishedText);
        Assert.Equal("https://yt3.ggpht.com/large", posts[0].ImageUrl);
        Assert.Equal("https://www.youtube.com/post/Ugkx1", posts[0].Url);
        Assert.Equal(["Ugkx1"], LiveNews.Of(posts).Select(p => p.PostId));
        Assert.True(LiveNews.IsLiveRelated("ASIA DOME TOUR 2026"));
        // 1年以上前の告知は出さない
        Assert.Empty(LiveNews.Of([posts[0] with { PublishedText = "2 年前" }]));
        Assert.True(LiveNews.IsRecent("11 か月前"));
        Assert.False(LiveNews.IsRecent("1 year ago (edited)"));
        Assert.True(LiveNews.IsRecent(null));
    }
}

internal static class StringExtensions
{
    public static string ReplaceFirst(this string text, string from, string to)
    {
        var at = text.IndexOf(from, StringComparison.Ordinal);
        return at < 0 ? text : text[..at] + to + text[(at + from.Length)..];
    }
}
