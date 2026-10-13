using Wave.Core.Music;

namespace Wave.Core.Tests;

/// <summary>一覧を流し終える前に最後の曲のラジオへつなぐ（オートプレイ。Android の QueueSession の switchToRadio と同じ）。</summary>
public class AutoRadioTests
{
    private static Song S(string id, bool live = false) => new(id, "title-" + id, [], null, 200, null, IsLive: live);

    private static List<Song> Queue(params string[] ids) => ids.Select(id => S(id)).ToList();

    [Fact]
    public void StartsFromTheLastSongWhenFewAreLeft()
    {
        var queue = Queue("a", "b", "c", "d", "e");
        // 残り4曲（今の曲を含む）ではまだ
        Assert.Null(AutoRadio.SeedAtEnd(queue, 1, hasRadio: false, repeatAll: false, sourcePending: false));
        // 残り3曲で、最後の曲（シャッフルしていても今のキューの最後）を種にする
        Assert.Equal("e", AutoRadio.SeedAtEnd(queue, 2, hasRadio: false, repeatAll: false, sourcePending: false));
        Assert.Equal("e", AutoRadio.SeedAtEnd(queue, 4, hasRadio: false, repeatAll: false, sourcePending: false));
        Assert.Equal("RDAMVMe", AutoRadio.PlaylistIdOf("e"));
    }

    [Fact]
    public void DoesNotStartUnderRepeatAllOrWhileTheListIsStillLoadingOrWithARadio()
    {
        var queue = Queue("a", "b");
        Assert.Null(AutoRadio.SeedAtEnd(queue, 1, hasRadio: false, repeatAll: true, sourcePending: false));
        Assert.Null(AutoRadio.SeedAtEnd(queue, 1, hasRadio: false, repeatAll: false, sourcePending: true));
        Assert.Null(AutoRadio.SeedAtEnd(queue, 1, hasRadio: true, repeatAll: false, sourcePending: false));
    }

    [Fact]
    public void DoesNotStartFromAnRssEpisodeOrALiveStream()
    {
        var episode = new List<Song> { S("a"), S(Feed.AudioVideoId("https://example.com/ep1.mp3")) };
        Assert.Null(AutoRadio.SeedAtEnd(episode, 1, hasRadio: false, repeatAll: false, sourcePending: false));
        var live = new List<Song> { S("a"), S("b", live: true) };
        Assert.Null(AutoRadio.SeedAtEnd(live, 1, hasRadio: false, repeatAll: false, sourcePending: false));
    }

    [Fact]
    public void IgnoresAnIndexOutsideTheQueue()
    {
        Assert.Null(AutoRadio.SeedAtEnd([], -1, hasRadio: false, repeatAll: false, sourcePending: false));
        Assert.Null(AutoRadio.SeedAtEnd(Queue("a", "b"), 5, hasRadio: false, repeatAll: false, sourcePending: false));
        Assert.Null(AutoRadio.SeedAtEnd(Queue("a", "b"), -1, hasRadio: false, repeatAll: false, sourcePending: false));
    }

    [Fact]
    public void HandsOffAPlaylistAsNotExhaustedSoTheReceiverSwitchesToRadio()
    {
        // プレイリスト・アルバム: 出どころはプレイリストのまま、続きが無いとは書かない（受け手が終わりでラジオへつなぐ）
        Assert.Equal((null, null, false), AutoRadio.ForHandoff(Queue("a", "b"), "PL1", null, null));
        // 切り替えた後: 種の曲のラジオとして書く（Android の source が Radio に替わるのと同じ）
        Assert.Equal(("b", "RDAMVMb", false), AutoRadio.ForHandoff(Queue("a", "b"), "PL1", "b", null));
        Assert.Equal(("b", "RDAMVMbX", false), AutoRadio.ForHandoff(Queue("a", "b"), "PL1", "b", "RDAMVMbX"));
    }

    [Fact]
    public void HandsOffAListWithoutSourceAsTheLastSongsRadio()
    {
        // 検索の結果など出どころの無い一覧は、Android の playFrom と同じく最後の曲のラジオ
        Assert.Equal(("b", "RDAMVMb", false), AutoRadio.ForHandoff(Queue("a", "b"), null, null, null));
    }

    [Fact]
    public void HandsOffAsExhaustedWhenTheLastSongCannotSeedARadio()
    {
        var episodes = new List<Song> { S(Feed.AudioVideoId("https://example.com/ep1.mp3")) };
        Assert.Equal((null, null, true), AutoRadio.ForHandoff(episodes, null, null, null));
        Assert.Equal((null, null, true), AutoRadio.ForHandoff(new List<Song> { S("a", live: true) }, "PL1", null, null));
    }
}
