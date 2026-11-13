using System.Text.Json.Nodes;
using Wave.Core.Music;

namespace Wave.Core.Tests;

/// <summary>自分のプレイリストの「おすすめの曲」と、キューをプレイリストとして保存するときの曲の選び方。</summary>
public class PlaylistSuggestionsTests
{
    [Fact]
    public void SuggestionsShelfAndReloadAreRead()
    {
        var first = MusicParsers.PlaylistSuggestions(Fixtures.Json("browse_playlist_suggestions"));
        Assert.NotNull(first);
        Assert.Equal(7, first!.Songs.Count);
        Assert.All(first.Songs, s => Assert.False(string.IsNullOrEmpty(s.VideoId)));
        Assert.All(first.Songs, s => Assert.False(string.IsNullOrEmpty(s.Title)));
        Assert.NotNull(first.ReloadToken);

        // 「更新」の応答は musicShelfContinuation で、別の曲と次の更新のトークンが来る
        var again = MusicParsers.PlaylistSuggestions(Fixtures.Json("browse_playlist_suggestions_reload"));
        Assert.NotNull(again);
        Assert.NotEmpty(again!.Songs);
        Assert.NotNull(again.ReloadToken);
        Assert.NotEqual(first.Songs[0].VideoId, again.Songs[0].VideoId);

        Assert.Null(MusicParsers.PlaylistSuggestions(Fixtures.Json("browse_playlist")));
    }

    [Fact]
    public void PlaylistPageKeepsTheSuggestionsToken()
    {
        // 最初のページの sectionListRenderer の続き（自分のプレイリストでは「候補」の棚が来る）
        Assert.False(string.IsNullOrEmpty(MusicParsers.PlaylistPage(Fixtures.Json("browse_playlist")).SuggestionsToken));
        // 続きのページ（曲の続き）には無い
        var appended = new JsonObject { ["onResponseReceivedActions"] = new JsonArray(new JsonObject { ["appendContinuationItemsAction"] = new JsonObject { ["continuationItems"] = new JsonArray() } }) };
        Assert.Null(MusicParsers.PlaylistPage(appended).SuggestionsToken);
    }

    [Fact]
    public void QueueSaveKeepsOrderAndDropsEpisodesLivesAndRepeats()
    {
        static Song S(string id, string? kind = null, bool live = false) => new(id, "t" + id, [], null, 200, null, kind, live);
        var queue = new[]
        {
            S("a"), S("b"), S("ep", "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"), S("live", live: true), S(Feed.AudioPrefix + "x"), S("a"), S("c", Song.KindMusicVideo),
        };
        Assert.Equal(["a", "b", "c"], QueueSave.Songs(queue).Select(s => s.VideoId));
        Assert.Equal("taのラジオ", QueueSave.DefaultTitle(QueueSave.Songs(queue), japanese: true));
        Assert.Equal("ta radio", QueueSave.DefaultTitle(QueueSave.Songs(queue), japanese: false));
    }
}
