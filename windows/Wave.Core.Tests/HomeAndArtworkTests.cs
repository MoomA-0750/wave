using Wave.Core.Music;

namespace Wave.Core.Tests;

/// <summary>ホームの新作の棚（SPEC「ホームに YouTube Music の新作を出す」。Android の HomeShelves と同じ）と、画像の大きさの直し方（Android の largeArtwork と同じ形）。</summary>
public class HomeAndArtworkTests
{
    private static AlbumSummary Album(string id) => new(id, id, null, null);

    [Fact]
    public void NewReleasesShelfTakesTwentyAlbums()
    {
        var releases = Enumerable.Range(0, 25).Select(i => Album("MPRE" + i)).ToList();
        var shelf = HomeShelves.NewReleasesShelf("新作", releases)!;
        Assert.Equal("新作", shelf.Title);
        Assert.Null(shelf.Strapline);
        Assert.Equal(HomeShelves.NewReleasesCount, shelf.Items.Count);
        Assert.All(shelf.Items, i => Assert.IsType<HomeItem.AlbumItem>(i));
        Assert.Null(HomeShelves.NewReleasesShelf("新作", []));
    }

    [Fact]
    public void NewReleasesAreDroppedWhenHomeAlreadyHasThem()
    {
        var releases = new[] { Album("a"), Album("b"), Album("c"), Album("d") };
        HomeShelf Shelf(params string[] ids) => new("x", null, ids.Select(id => (HomeItem)new HomeItem.AlbumItem(Album(id))).ToList());
        Assert.False(HomeShelves.DuplicatesNewReleases(releases, [Shelf("a", "b", "z")]));
        Assert.True(HomeShelves.DuplicatesNewReleases(releases, [Shelf("z"), Shelf("a", "c", "d")]));
        Assert.False(HomeShelves.DuplicatesNewReleases([], [Shelf("a", "b", "c")]));
    }

    [Fact]
    public void GoogleImagesAreResizedInBothForms()
    {
        Assert.Equal("https://lh3.googleusercontent.com/x=w544-h544-l90-rj", ArtworkUrl.Sized("https://lh3.googleusercontent.com/x=w120-h120-l90-rj", 544));
        // =s… の形（アーティスト・チャンネルの画像）
        Assert.Equal("https://yt3.ggpht.com/x=s544-c-k-c0x00ffffff-no-rj", ArtworkUrl.Sized("https://yt3.ggpht.com/x=s88-c-k-c0x00ffffff-no-rj", 544));
        Assert.Equal("https://yt3.googleusercontent.com/x=s544", ArtworkUrl.Sized("https://yt3.googleusercontent.com/x=s120", 544));
        // 大きさの指定が無いものには付ける
        Assert.Equal("https://lh3.googleusercontent.com/x=w544-h544", ArtworkUrl.Sized("https://lh3.googleusercontent.com/x", 544));
        // ほかの宛先はそのまま
        Assert.Equal("https://example.com/a=s88", ArtworkUrl.Sized("https://example.com/a=s88", 544));
    }

    [Fact]
    public void YtimgThumbnailsBecomeHqWhenShownLarge()
    {
        const string url = "https://i.ytimg.com/vi/abc/sddefault.jpg?sqp=xyz&rs=sig";
        Assert.Equal("https://i.ytimg.com/vi/abc/hqdefault.jpg", ArtworkUrl.Sized(url, 1088));
        Assert.Equal("https://i.ytimg.com/vi/abc/hqdefault.jpg", ArtworkUrl.Sized("https://i.ytimg.com/vi/abc/default.jpg", 1088));
        // 小さく出すときはそのまま
        Assert.Equal(url, ArtworkUrl.Sized(url, 192));
        // webp などの形は触らない
        Assert.Equal("https://i.ytimg.com/vi_webp/abc/maxresdefault.webp", ArtworkUrl.Sized("https://i.ytimg.com/vi_webp/abc/maxresdefault.webp", 1088));
    }
}
