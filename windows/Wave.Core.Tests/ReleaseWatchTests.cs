using Wave.Core.Music;
using Xunit;

namespace Wave.Core.Tests;

public class ReleaseWatchTests
{
    private static ArtistPage Page(string[] albums, string[]? videos = null) =>
        new("A", null, [], null,
            [new AlbumShelf("Albums", albums.Select(id => new AlbumSummary(id, "title-" + id, id.EndsWith("new") ? "Single • 2026" : "Album • 2019", null)).ToList())],
            VideoShelf: (videos ?? []).Select(v => new Song(v, "video-" + v, [], null, null, null)).ToList());

    [Fact]
    public void TheFirstLookOnlyRemembersAndSeedsThisYear()
    {
        var (fresh, state) = ReleaseWatchState.Empty.Observe("UC1", Page(["MPold", "MPnew"], ["v1"]), 1, 2026);
        Assert.Empty(fresh);
        Assert.Equal(new[] { "a:MPold", "a:MPnew", "v:v1" }.ToHashSet(), state.Seen["UC1"]);
        Assert.True(Assert.Single(state.Recent).Seeded);
    }

    [Fact]
    public void OnlyNewAlbumsOfThisYearAreReportedNewestFirst()
    {
        var (_, first) = ReleaseWatchState.Empty.Observe("UC1", Page(["MPold", "MPnew"], ["v1"]), 1, 2026);
        // 古い作品（MP2）が棚に入り直した・動画が増えただけでは知らせない
        var (fresh, second) = first.Observe("UC1", Page(["MP3new", "MP2", "MPold", "MPnew"], ["v2", "v1"]), 2, 2026);
        Assert.Equal(new[] { "MP3new" }, fresh.Select(r => r.AlbumBrowseId).OfType<string>());
        Assert.Empty(fresh.Select(r => r.VideoId).OfType<string>());
        Assert.Contains("a:MP2", second.Seen["UC1"]);
        Assert.Contains("v:v2", second.Seen["UC1"]);
        Assert.Equal(new[] { "MP3new", "MPnew" }, second.Recent.Select(r => r.Key));
        // 文字にして戻しても同じ
        var decoded = ReleaseWatchState.Decode(second.Encode())!;
        Assert.Equal(second.Recent, decoded.Recent);
        Assert.Equal(second.Seen["UC1"], decoded.Seen["UC1"]);
    }

    [Fact]
    public void AnEmptyPageDoesNotForget()
    {
        var (_, first) = ReleaseWatchState.Empty.Observe("UC1", Page(["MP1"]), 1, null);
        var (fresh, second) = first.Observe("UC1", Page([]), 2, null);
        Assert.Empty(fresh);
        Assert.Same(first, second);
    }

    [Fact]
    public void DiversifiedTakesTwoPerArtistInTurns()
    {
        var releases = new[] { "a1", "a2", "a3" }.Select(t => new NewRelease("A", "A", t, t, null, null)).Concat(new[] { new NewRelease("B", "B", "b1", "b1", null, null) });
        Assert.Equal(new[] { "a1", "b1", "a2" }, ReleaseWatchState.Diversified(releases).Select(r => r.Title));
    }

    [Fact]
    public void ReleasesThatLeaveTheArtistPageAreDropped()
    {
        // 同じ名前の別人の作品がまとめられ、後でページから外された（Android と同じ）
        var (_, first) = ReleaseWatchState.Empty.Observe("UC1", Page(["MPold"]), 1, 2026);
        var (_, second) = first.Observe("UC1", Page(["MPothernew", "MPold"]), 2, 2026);
        Assert.Equal(["MPothernew"], second.Recent.Select(r => r.AlbumBrowseId));
        var (_, third) = second.Observe("UC1", Page(["MPold"]), 3, 2026);
        Assert.Empty(third.Recent);
        // 今年の作品だけの棚からは、押し出されただけかもしれないので外さない。ほかのアーティストの分は残す
        Assert.Equal(second.Recent, ReleaseWatchState.Listed(second.Recent, "UC1", Page(["MP4new", "MP5new"]), 2026));
        Assert.Equal(second.Recent, ReleaseWatchState.Listed(second.Recent, "UC2", Page(["MPold"]), 2026));
    }
}
