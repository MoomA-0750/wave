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
    public void NewAlbumsAndVideosAreReportedNewestFirst()
    {
        var (_, first) = ReleaseWatchState.Empty.Observe("UC1", Page(["MPold", "MPnew"], ["v1"]), 1, 2026);
        var (fresh, second) = first.Observe("UC1", Page(["MP3", "MPold", "MPnew"], ["v2", "v1"]), 2, 2026);
        Assert.Equal(new[] { "MP3" }, fresh.Select(r => r.AlbumBrowseId).OfType<string>());
        Assert.Equal(new[] { "v2" }, fresh.Select(r => r.VideoId).OfType<string>());
        Assert.Equal(new[] { "MP3", "v2", "MPnew" }, second.Recent.Select(r => r.Key));
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
}
