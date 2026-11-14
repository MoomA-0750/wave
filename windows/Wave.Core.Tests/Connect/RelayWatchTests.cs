using Wave.Core.Connect;

namespace Wave.Core.Tests.Connect;

/// <summary>中継の接続の見張り（Android の RelayWatchTest と同じ場面）。</summary>
public class RelayWatchTests
{
    [Fact]
    public void ASocketWithoutWelcomeIsGivenUpAfterFifteenSeconds()
    {
        var watch = new RelayWatch();
        watch.Opened(0);
        Assert.Equal(RelayWatch.Action.None, watch.Tick(14_999));
        // challenge だけ届いても welcome でなければ待ち続けない
        watch.Received(5_000);
        Assert.Equal(RelayWatch.Action.Dead, watch.Tick(15_000));
    }

    [Fact]
    public void AnIdleSocketIsProbedAndDroppedWhenNothingComesBack()
    {
        var watch = new RelayWatch();
        watch.Opened(0);
        watch.Received(1_000, welcome: true);
        Assert.Equal(RelayWatch.Action.None, watch.Tick(20_999));
        Assert.Equal(RelayWatch.Action.Probe, watch.Tick(21_000));
        Assert.Equal(RelayWatch.Action.None, watch.Tick(28_999));
        Assert.Equal(RelayWatch.Action.Dead, watch.Tick(29_000));
    }

    [Fact]
    public void AnyReplyKeepsTheSocket()
    {
        var watch = new RelayWatch();
        watch.Opened(0);
        watch.Received(0, welcome: true);
        Assert.Equal(RelayWatch.Action.Probe, watch.Tick(20_000));
        watch.Received(20_300);
        Assert.Equal(RelayWatch.Action.None, watch.Tick(29_000));
        Assert.Equal(RelayWatch.Action.Probe, watch.Tick(40_300));
    }

    [Fact]
    public void WakingProbesAtOnceOnlyWhenConnected()
    {
        var watch = new RelayWatch();
        watch.Opened(0);
        Assert.Equal(RelayWatch.Action.None, watch.Wake(100));
        watch.Received(200, welcome: true);
        Assert.Equal(RelayWatch.Action.Probe, watch.Wake(300));
        Assert.Equal(RelayWatch.Action.None, watch.Wake(400));
        Assert.Equal(RelayWatch.Action.Dead, watch.Tick(8_300));
    }

    [Fact]
    public void ReconnectWaitsStartAtOneSecondAndStopAtThirty()
    {
        Assert.Equal(new long[] { 1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000 }, Enumerable.Range(0, 7).Select(i => (long)RelayWatch.ReconnectDelay(i).TotalMilliseconds).ToArray());
    }

    [Fact]
    public void TheProbeIsAChainRequestTheRelayAnswersWithNothing()
    {
        Assert.Equal("{\"op\":\"chain_get\",\"fromSeq\":2147483647}", RelayWatch.Probe);
    }
}
