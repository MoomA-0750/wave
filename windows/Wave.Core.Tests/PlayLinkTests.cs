using Wave.Core.Music;
using Wave.Core.Transfer;
using Xunit;

namespace Wave.Core.Tests;

public class PlayLinkTests
{
    [Theory]
    [InlineData("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=abc", "dQw4w9WgXcQ")]
    [InlineData("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ", "dQw4w9WgXcQ")]
    [InlineData("https://youtu.be/dQw4w9WgXcQ?si=x", "dQw4w9WgXcQ")]
    [InlineData("youtube.com/shorts/dQw4w9WgXcQ", "dQw4w9WgXcQ")]
    [InlineData("  dQw4w9WgXcQ ", "dQw4w9WgXcQ")]
    public void SongLinksAndIdsAreVideos(string text, string id) =>
        Assert.Equal(new PlayLink.Video(id), PlayLink.Parse(text));

    [Fact]
    public void AlbumArtistAndPlaylistLinksAreRecognized()
    {
        Assert.Equal(new PlayLink.Album("MPREb_hqiB0KumHYT"), PlayLink.Parse("https://music.youtube.com/browse/MPREb_hqiB0KumHYT"));
        Assert.Equal(new PlayLink.Album("MPREb_hqiB0KumHYT"), PlayLink.Parse("MPREb_hqiB0KumHYT"));
        // アルバムの「共有」のリンクはアルバムのプレイリスト
        Assert.Equal(new PlayLink.Playlist("OLAK5uy_kmz1M8fCXQyAbcDefGhiJkl"), PlayLink.Parse("https://music.youtube.com/playlist?list=OLAK5uy_kmz1M8fCXQyAbcDefGhiJkl&si=x"));
        Assert.Equal(new PlayLink.Artist("UCvpredjG93ifbCP1Y77JyFA"), PlayLink.Parse("https://music.youtube.com/channel/UCvpredjG93ifbCP1Y77JyFA?si=x"));
        Assert.Equal(new PlayLink.Artist("UCvpredjG93ifbCP1Y77JyFA"), PlayLink.Parse("UCvpredjG93ifbCP1Y77JyFA"));
    }

    [Theory]
    [InlineData("緑黄色社会 Mela!")]
    [InlineData("ryokuoushoku")]
    [InlineData("https://open.spotify.com/track/abc")]
    [InlineData("https://example.com/watch?v=dQw4w9WgXcQ")]
    [InlineData("")]
    public void SearchWordsAndOtherSitesAreNotLinks(string text) => Assert.Null(PlayLink.Parse(text));

    [Fact]
    public void ManualSearchKeepsYouTubesOrderAndAllResults()
    {
        var track = new SpotifyTrack("Mela!", ["緑黄色社会"], "Mela!", 200_000);
        var songs = Enumerable.Range(0, 12)
            .Select(i => new Song($"v{i:D10}", i == 9 ? "Mela!" : $"Other {i}", [new ArtistRef("緑黄色社会", null)], null, 200, null))
            .ToList();
        var ranked = TransferMatcher.RankSongs(track, songs);
        Assert.Equal(TransferMatcher.MaxCandidates, ranked.Count);
        Assert.Equal("v0000000009", ranked[0].Id);

        var manual = TransferMatcher.RankSongs(track, songs, take: int.MaxValue, sort: false);
        Assert.Equal(12, manual.Count);
        Assert.Equal(songs.Select(s => s.VideoId), manual.Select(c => c.Id));
    }
}
