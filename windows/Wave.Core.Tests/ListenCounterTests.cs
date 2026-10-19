using Wave.Core.Stream;

namespace Wave.Core.Tests;

/// <summary>再生履歴を送る時（SPEC「1回の再生につき1回、30秒（短い曲は半分）聴いたら送る」。Android の ListenCounterTest と同じ）。</summary>
public class ListenCounterTests
{
    [Fact]
    public void ThresholdIsThirtySecondsOrHalfOfAShortSong()
    {
        Assert.Equal(30_000, ListenCounter.ThresholdFor(null));
        Assert.Equal(30_000, ListenCounter.ThresholdFor(0));
        Assert.Equal(30_000, ListenCounter.ThresholdFor(240_000));
        Assert.Equal(30_000, ListenCounter.ThresholdFor(30_000));
        Assert.Equal(10_000, ListenCounter.ThresholdFor(20_000));
        // 短すぎる曲でも 5 秒は聴く
        Assert.Equal(5_000, ListenCounter.ThresholdFor(6_000));
    }

    [Fact]
    public void OnlyPlayingIntervalsAreCounted()
    {
        var counter = new ListenCounter();
        counter.Start(1_000);
        counter.Stop(11_000);
        // 一時停止の間は数えない
        counter.Start(50_000);
        Assert.Equal(15_000, counter.ListenedMs(55_000));
        Assert.False(counter.Reached(55_000, 240_000));
        Assert.True(counter.Reached(70_000, 240_000));
    }

    [Fact]
    public void StartingTwiceDoesNotRestartTheInterval()
    {
        var counter = new ListenCounter();
        counter.Start(0);
        counter.Start(20_000);
        Assert.Equal(30_000, counter.ListenedMs(30_000));
    }

    [Fact]
    public void SeekingAloneNeverReachesTheThreshold()
    {
        // シークは位置を動かすだけで、聴いた時間は時計で数える（数秒聴いて終わり近くへ飛ばしても送らない）
        var counter = new ListenCounter();
        counter.Start(0);
        Assert.False(counter.Reached(3_000, 240_000));
    }

    [Fact]
    public void HandedOffTimeContinuesAndResetStartsOver()
    {
        var counter = new ListenCounter(25_000);
        counter.Start(0);
        Assert.True(counter.Reached(5_000, 240_000));
        counter.Reset(0, null);
        Assert.Equal(0, counter.ListenedMs(100_000));
        Assert.False(counter.Running);
        counter.Reset(-5, 10);
        Assert.Equal(90, counter.ListenedMs(100));
    }

    [Fact]
    public void LargeCarriedValuesSaturate()
    {
        var counter = new ListenCounter(long.MaxValue - 1);
        counter.Start(0);
        counter.Stop(1_000);
        Assert.Equal(long.MaxValue, counter.ListenedMs(2_000));
    }
}
