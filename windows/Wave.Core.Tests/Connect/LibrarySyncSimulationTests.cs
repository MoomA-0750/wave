using Wave.Core.Connect;

namespace Wave.Core.Tests.Connect;

/// <summary>ライブラリの同期（ADR-0013）を、偽の中継と ConnectClient でつないで確かめる（Kotlin の LibrarySyncSimulationTest の筋）。</summary>
public class LibrarySyncSimulationTests
{
    private long now = 1_000_000L;
    private readonly FakeRelay relay;
    private readonly SimDevice a;
    private readonly SimDevice b;
    private readonly SimDevice pc;

    public LibrarySyncSimulationTests()
    {
        relay = new FakeRelay(() => now);
        a = new SimDevice("A", () => relay, () => now);
        b = new SimDevice("B", () => relay, () => now);
        pc = new SimDevice("PC", () => relay, () => now, Role.Controller);
    }

    /// <summary>A が家を作り、B と PC（操作だけの端末）を加える。A と B はつないでおく。</summary>
    private void SetUp(bool connectPc = false)
    {
        a.Client.BeginCreateHousehold();
        a.Connect();
        relay.Run();
        Assert.True(relay.FromClient(a.DeviceId, new RelayV2.HouseholdCreate { Genesis = MemberChain.Genesis(a.Keys, a.MemberKeys) }));
        relay.Run();
        Join(b);
        Join(pc, connectPc);
        relay.Run();
    }

    private void Join(SimDevice device, bool connect = true)
    {
        var responder = new Pairing.Responder(a.Keys, "A", "account-1", () => now, ConnectJson.Encode(a.Client.CurrentJoinInfo()!));
        var initiator = new Pairing.Initiator(Pairing.DecodeOffer(Pairing.EncodeOffer(responder.Offer))!, device.Keys, device.Name, "account-1", ConnectJson.Encode(device.MemberKeys));
        var confirmed = initiator.OnConfirm(responder.OnHello(initiator.Hello()).GetOrThrow()).GetOrThrow();
        var onA = responder.OnCodeEntered(confirmed.Code).GetOrThrow();
        Assert.True(relay.FromClient(a.DeviceId, new RelayV2.ChainAppend { Record = MemberChain.Add(a.Keys, a.Client.VerifiedChain()!, ConnectJson.Decode<MemberKeys>(onA.PeerExtension!)!) }));
        relay.Run();
        device.Client.ExpectJoin(ConnectJson.Decode<ConnectClient.JoinInfo>(confirmed.Pair.PeerExtension!)!, a.DeviceId);
        if (!connect) return;
        device.Connect();
        relay.Run();
        Assert.Contains(device.Events, e => e is ClientEvent.Joined);
    }

    private static LibraryFeed Feed(string title, long at, bool removed = false) => new() { Title = title, UpdatedAtMs = at, Removed = removed };

    private static LibraryBody Feeds(params (string Url, LibraryFeed Feed)[] feeds) => new() { Feeds = feeds.ToDictionary(f => f.Url, f => f.Feed) };

    [Fact]
    public void SubscriptionReachesTheOtherDeviceAndRemovalWins()
    {
        SetUp();
        a.Client.Library.Publish(Feeds(("https://x/f", Feed("X", 10))));
        relay.Run();
        Assert.Equal("X", b.Library?.Feeds["https://x/f"].Title);
        // B が外す（後の時刻）→ A でも外れる
        b.Client.Library.Publish(Feeds(("https://x/f", Feed("X", 20, removed: true))));
        relay.Run();
        Assert.True(a.Library?.Feeds["https://x/f"].Removed);
        Assert.Equal(2L, relay.LibraryVersion);
    }

    [Fact]
    public void ConcurrentWritesAreMergedAfterConflict()
    {
        SetUp();
        // 両方が同じ版の上に書こうとする（届く前に）
        a.Client.Library.Publish(Feeds(("https://a/f", Feed("A", 10))));
        b.Client.Library.Publish(new LibraryBody
        {
            Feeds = new Dictionary<string, LibraryFeed> { ["https://b/f"] = Feed("B", 11) },
            Positions = new Dictionary<string, LibraryPosition> { ["https://b/1.mp3"] = new() { PositionSeconds = 60, DurationSeconds = 600, UpdatedAtMs = 11 } },
        });
        relay.Run();
        Assert.Equal(2L, relay.LibraryVersion);
        Assert.Equal(["https://a/f", "https://b/f"], a.Library!.Feeds.Keys.Order());
        Assert.Equal(["https://a/f", "https://b/f"], b.Library!.Feeds.Keys.Order());
        Assert.Equal(60, a.Library.Positions["https://b/1.mp3"].PositionSeconds);
    }

    [Fact]
    public void ADeviceThatWasOfflineCatchesUpAndControllersCanWrite()
    {
        SetUp(connectPc: false);
        a.Client.Library.Publish(Feeds(("https://x/f", Feed("X", 10))));
        relay.Run();
        // PC（操作だけの端末）は後からつながる。持っていた登録は合わせて書く
        pc.Client.Library.Publish(Feeds(("https://p/f", Feed("P", 5))));
        pc.Connect();
        relay.Run();
        Assert.Equal(["https://p/f", "https://x/f"], pc.Library!.Feeds.Keys.Order());
        Assert.Equal(["https://p/f", "https://x/f"], a.Library!.Feeds.Keys.Order());
        Assert.Equal(2L, relay.LibraryVersion);
    }

    [Fact]
    public void SameContentIsNotWrittenAgain()
    {
        SetUp();
        var body = Feeds(("https://x/f", Feed("X", 10)));
        a.Client.Library.Publish(body);
        relay.Run();
        b.Client.Library.Publish(body);
        a.Client.Library.Publish(body);
        relay.Run();
        Assert.Equal(1L, relay.LibraryVersion);
    }

    [Fact]
    public void ReconnectingResendsWhatWasNotAnswered()
    {
        SetUp();
        // 返事の前に切れた書き込みは、つながり直した後に最新と合わせて書き直す
        relay.Hold = (to, message) => to == a.DeviceId && message is RelayV2.LibraryOk;
        a.Client.Library.Publish(Feeds(("https://x/f", Feed("X", 10))));
        relay.Run();
        a.Disconnect();
        relay.Hold = (_, _) => false;
        a.Client.Library.Publish(Feeds(("https://x/f", Feed("X", 10)), ("https://y/f", Feed("Y", 12))));
        a.Connect();
        relay.Run();
        Assert.Equal(2L, relay.LibraryVersion);
        Assert.Equal(["https://x/f", "https://y/f"], b.Library!.Feeds.Keys.Order());
    }

    [Fact]
    public void ARelayThatLostTheLibraryIsWrittenFromTheStartWithoutLooping()
    {
        SetUp();
        a.Client.Library.Publish(Feeds(("https://x/f", Feed("X", 10))));
        relay.Run();
        Assert.Equal(1L, relay.LibraryVersion);
        relay.DropLibrary();
        var puts = 0;
        relay.OnFromClient = (_, message) => puts += message is RelayV2.LibraryPut ? 1 : 0;
        // 覚えた版（1）のまま書くと食い違いが返る。中継に無いと分かったら 0 から書き直す
        a.Client.Library.Publish(Feeds(("https://x/f", Feed("X", 10)), ("https://y/f", Feed("Y", 11))));
        relay.Run();
        Assert.Equal(1L, relay.LibraryVersion);
        Assert.Equal(2, puts);
        Assert.Equal(["https://x/f", "https://y/f"], b.Library!.Feeds.Keys.Order());
    }

    [Fact]
    public void AnOldRelayOnlyAnswersWithAnErrorAndPlaybackKeepsWorking()
    {
        relay.SupportsLibrary = false;
        SetUp();
        var sent = new List<RelayV2>();
        relay.OnFromClient = (_, message) => sent.Add(message);
        a.Client.Library.Publish(Feeds(("https://x/f", Feed("X", 10))));
        relay.Run();
        // library_get に返事が無い（unsupported）ので書かない
        Assert.DoesNotContain(sent, m => m is RelayV2.LibraryPut);
        Assert.Null(relay.LibraryVersion);
        Assert.Contains(a.Events, e => e is ClientEvent.ServerError { Code: "unsupported" });
        Assert.Null(b.Library);
        // 再生の連携は今までどおり
        a.StartPlaying(HandoffCodec.Encode(InteropTests.SampleSnapshot()));
        relay.Run();
        Assert.True(a.Client.IsWriter);
        Assert.True(b.Client.RemotePlayingNow());
        Assert.True(b.Client.RequestTransfer());
        relay.Run();
        Assert.True(b.Playing);
        Assert.False(a.Playing);
    }
}
