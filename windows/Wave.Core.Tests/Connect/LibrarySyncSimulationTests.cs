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
    /// <summary>版の CAS だけをする中継（library のところだけ）。LibrarySync だけをつないで確かめる。</summary>
    private sealed class CasRelay(LibraryState? stored)
    {
        public LibraryState? Stored { get; private set; } = stored;
        public Queue<RelayV2> Inbox { get; } = new();
        public int Puts { get; private set; }

        public void Handle(RelayV2 message)
        {
            switch (message)
            {
                case RelayV2.LibraryGet:
                    Inbox.Enqueue(new RelayV2.Library { Stored = Stored });
                    break;
                case RelayV2.LibraryPut put:
                    Puts++;
                    var current = Stored?.Version ?? 0;
                    if (current != put.ExpectVersion)
                    {
                        Inbox.Enqueue(new RelayV2.LibraryConflict { RequestId = put.RequestId, Latest = Stored });
                        return;
                    }
                    Stored = new LibraryState { Version = current + 1, Doc = put.Doc, StoreId = "store-1" };
                    Inbox.Enqueue(new RelayV2.LibraryOk { RequestId = put.RequestId, Version = current + 1 });
                    break;
            }
        }
    }

    /// <summary>device が chain で中継につながる。返す pump は、送ったもの・届いたものが無くなるまで回す。</summary>
    private static (LibrarySync Sync, Action Pump) ConnectAlone(ConnectTestSupport.TestDevice device, VerifiedChain chain, CasRelay cas)
    {
        var outbox = new Queue<RelayV2>();
        var ids = 0;
        var sync = new LibrarySync(device, device, m => { outbox.Enqueue(m); return true; }, () => chain, () => "store-1", () => 1, () => $"req-{++ids}", _ => { });
        void Pump()
        {
            while (outbox.Count > 0 || cas.Inbox.Count > 0)
            {
                while (outbox.Count > 0) cas.Handle(outbox.Dequeue());
                if (cas.Inbox.Count > 0) sync.OnMessage(cas.Inbox.Dequeue());
            }
        }
        sync.OnConnected();
        Pump();
        return (sync, Pump);
    }

    [Fact]
    public void AnUnreadableRemoteDocumentIsNeverOverwritten()
    {
        // 中継の最新の文書を、この端末が開けない（別の一覧で書かれた・宛先にいない）
        var other = new ConnectTestSupport.TestDevice();
        var otherChain = new[] { MemberChain.Genesis(other, other.Keys) }.Verified();
        var doc = DocSealer.Seal(other, otherChain, DocKind.Library, 5, null, 1, ConnectJson.EncodeToUtf8(Feeds(("https://feeds.example/x", Feed("X", 5)))));
        var cas = new CasRelay(new LibraryState { Version = 5, Doc = doc, StoreId = "store-1" });
        var me = new ConnectTestSupport.TestDevice();
        var (sync, pump) = ConnectAlone(me, new[] { MemberChain.Genesis(me, me.Keys) }.Verified(), cas);
        sync.Publish(Feeds(("https://feeds.example/a", Feed("A", 10))));
        pump();
        // 書かない（書けば開けなかった登録を消し、食い違いが返り続ける）
        Assert.Equal(0, cas.Puts);
        Assert.NotNull(sync.Unreadable);
        Assert.Equal(5L, cas.Stored?.Version);
    }

    [Fact]
    public void ADocumentMissingAMemberIsRewrappedEvenWithTheSameContent()
    {
        var me = new ConnectTestSupport.TestDevice();
        var later = new ConnectTestSupport.TestDevice(Role.Controller);
        var genesis = MemberChain.Genesis(me, me.Keys);
        var chain = new[] { genesis, MemberChain.Add(me, new[] { genesis }.Verified(), later.Keys) }.Verified();
        // 後の端末が加わる前に書かれた（宛先が自分だけ）
        var same = Feeds(("https://feeds.example/x", Feed("X", 5)));
        var doc = DocSealer.Seal(me, chain, DocKind.Library, 3, null, 1, ConnectJson.EncodeToUtf8(same), new HashSet<string> { me.DeviceId });
        var cas = new CasRelay(new LibraryState { Version = 3, Doc = doc, StoreId = "store-1" });
        var (sync, pump) = ConnectAlone(me, chain, cas);
        Assert.Null(sync.Unreadable);
        // 中身が同じでも、全員に包み直して1回だけ書く
        Assert.Equal(1, cas.Puts);
        Assert.Equal(4L, cas.Stored?.Version);
        Assert.Equal(new[] { me.DeviceId, later.DeviceId }.Order(StringComparer.Ordinal), cas.Stored!.Doc.Recipients.Select(r => r.DeviceId).Order(StringComparer.Ordinal));
        sync.Publish(same);
        pump();
        Assert.Equal(1, cas.Puts);
    }
}
