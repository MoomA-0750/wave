using Wave.Core.Connect;

namespace Wave.Core.Tests.Connect;

/// <summary>端末なしの多端末テスト（Kotlin の ConnectSimulationTest の主要な筋）。偽の中継と ConnectClient を、決まった順の配送でつなぐ。</summary>
public class ConnectSimulationTests
{
    private long now = 1_000_000L;
    private readonly FakeRelay relay;
    private readonly SimDevice a;
    private readonly SimDevice b;
    private readonly string snapshot = HandoffCodec.Encode(InteropTests.SampleSnapshot());

    public ConnectSimulationTests()
    {
        relay = new FakeRelay(() => now);
        a = new SimDevice("A", () => relay, () => now);
        b = new SimDevice("B", () => relay, () => now);
    }

    /// <summary>A が家を作り、B がペアリング（拡張で鍵の記述と床を渡す）で加わる。</summary>
    private void SetUp()
    {
        a.Client.BeginCreateHousehold();
        a.Connect();
        relay.Run();
        Assert.True(relay.FromClient(a.DeviceId, new RelayV2.HouseholdCreate { Genesis = MemberChain.Genesis(a.Keys, a.MemberKeys) }));
        relay.Run();
        Assert.Contains(a.Events, e => e is ClientEvent.Joined);
        Assert.NotNull(a.Client.HouseholdId);

        var responder = new Pairing.Responder(a.Keys, "A", "account-1", () => now, ConnectJson.Encode(a.Client.CurrentJoinInfo()!));
        var initiator = new Pairing.Initiator(Pairing.DecodeOffer(Pairing.EncodeOffer(responder.Offer))!, b.Keys, "B", "account-1", ConnectJson.Encode(b.MemberKeys));
        var confirm = responder.OnHello(initiator.Hello()).GetOrThrow();
        var confirmed = initiator.OnConfirm(confirm).GetOrThrow();
        var onA = responder.OnCodeEntered(confirmed.Code).GetOrThrow();
        // A: B を家に加える
        var memberKeys = ConnectJson.Decode<MemberKeys>(onA.PeerExtension!)!;
        Assert.True(relay.FromClient(a.DeviceId, new RelayV2.ChainAppend { Record = MemberChain.Add(a.Keys, a.Client.VerifiedChain()!, memberKeys) }));
        relay.Run();
        // B: Confirm の床で加入を待ち、つなぐ
        b.Client.ExpectJoin(ConnectJson.Decode<ConnectClient.JoinInfo>(confirmed.Pair.PeerExtension!)!, a.DeviceId);
        b.Connect();
        relay.Run();
        Assert.Contains(b.Events, e => e is ClientEvent.Joined);
        Assert.Equal(a.Client.HouseholdId, b.Client.HouseholdId);
        Assert.Equal(2, a.Client.Members.Count);
    }

    private void Advance(long ms)
    {
        now += ms;
        relay.Tick();
        a.Client.Tick();
        b.Client.Tick();
        relay.Run();
    }

    [Fact]
    public void CreateJoinPlayTakeOverAndStop()
    {
        SetUp();
        a.StartPlaying(snapshot, item: "00000000-0000-4000-8000-000000000001", position: 42_000);
        relay.Run();
        Assert.True(a.Playing);
        Assert.True(a.Client.IsWriter);
        Assert.Contains(b.Events, e => e is ClientEvent.RemoteChanged r && r.Writer == a.DeviceId && r.Cursor.Playing && r.AccountMatches);
        Assert.Equal(a.DeviceId, b.Client.ActiveDeviceId);
        Assert.True(b.Client.RemotePlayingNow());
        // 表示用: B は A のキューを開ける
        var (writer, queue) = b.Client.LatestQueueBody()!.Value;
        Assert.Equal(a.DeviceId, writer);
        Assert.IsType<HandoffDecodeResult.Ok>(HandoffCodec.Decode(queue.Snapshot, InteropTests.SampleSnapshot().HandoffId));
        Assert.Equal("00000000-0000-4000-8000-000000000001", b.Client.LatestCursorBody()!.Value.Cursor.CurrentItemId);

        // 少し進めてから B が引き取る
        a.PositionMs = 50_000;
        a.PushCursor();
        relay.Run();
        Assert.True(b.Client.RequestTransfer());
        relay.Run();
        Assert.False(a.Playing, "引き取られた A が止まっていない");
        Assert.True(a.StoppedBy(b));
        Assert.True(b.Playing);
        Assert.Equal(snapshot, b.Queue);
        Assert.Equal("00000000-0000-4000-8000-000000000001", b.CurrentItemId);
        Assert.Equal(50_000L, b.PositionMs);
        Assert.Contains(b.Events, e => e is ClientEvent.TransferCompleted t && t.From == a.DeviceId);
        Assert.Equal(b.DeviceId, a.Client.ActiveDeviceId);
        Assert.True(b.Client.IsWriter);
        Assert.False(a.Client.IsWriter);

        // A でまた始めると B が止まる
        a.StartPlaying(snapshot);
        relay.Run();
        Assert.True(a.Playing);
        Assert.False(b.Playing);
        Assert.True(b.StoppedBy(a));
        Assert.Equal(a.DeviceId, relay.ActiveDeviceId);
    }

    [Fact]
    public void TakeOverFromAnOfflineDeviceUsesTheLastStateAfterThreeSeconds()
    {
        SetUp();
        a.StartPlaying(snapshot, position: 10_000);
        relay.Run();
        // A が通信を失う（鳴り続けている）
        a.Disconnect();
        now += 20_000;
        Assert.True(b.Client.RequestTransfer());
        relay.Run();
        Assert.False(b.Playing);
        Advance(ConnectClient.TransferWaitMs);
        Assert.True(b.Playing);
        // 最後の状態は再生中だったので、サーバーの時計で経過を足す
        Assert.True(b.PositionMs >= 10_000 + 20_000, $"位置を外挿していない: {b.PositionMs}");
        Assert.Equal(b.DeviceId, relay.ActiveDeviceId);
        // A がつながり直すと、B のカーソルを見て止まる
        Assert.True(a.Playing);
        a.Connect();
        relay.Run();
        Assert.False(a.Playing, "つながり直した A が止まっていない");
        Assert.True(a.StoppedBy(b));
        Assert.True(b.Playing);
    }

    [Fact]
    public void ConcurrentStartsLeaveOnlyTheLaterClaimPlaying()
    {
        SetUp();
        a.StartPlaying(snapshot);
        relay.Run();
        a.StartPlaying(snapshot + " ");
        b.StartPlaying(snapshot);
        relay.Run();
        Assert.True(b.Playing, "claim が後に届いた B が止まった");
        Assert.False(a.Playing);
        Assert.True(b.Client.IsWriter);
        Assert.False(a.Client.IsWriter);
    }

    [Fact]
    public void AnotherAccountCannotStopTheHousehold()
    {
        SetUp();
        b.Tag = "tag-account-2";
        a.StartPlaying(snapshot);
        relay.Run();
        b.StartPlaying(snapshot);
        relay.Run();
        Assert.False(b.Client.IsWriter);
        Assert.True(a.Playing, "別のアカウントの端末の再生で止まった");
        Assert.Contains(b.Events, e => e is ClientEvent.RemoteChanged r && r.Writer == a.DeviceId && !r.AccountMatches);
        Assert.False(b.Client.RequestTransfer());
    }

    [Fact]
    public void RestartFromPersistedJsonKeepsTheHousehold()
    {
        SetUp();
        a.StartPlaying(snapshot, position: 5_000);
        relay.Run();
        // B のプロセスが落ちて、保存（JSON）から起き直す
        b.Disconnect();
        relay.Run();
        b.Restart();
        Assert.Equal(a.Client.HouseholdId, b.Client.HouseholdId);
        b.Connect();
        relay.Run();
        Assert.True(b.Client.StateSettled);
        Assert.DoesNotContain(b.Events, e => e is ClientEvent.StoreReset or ClientEvent.ChainForked or ClientEvent.ChainInvalid);
        Assert.True(b.Client.RequestTransfer());
        relay.Run();
        Assert.True(b.Playing);
        Assert.False(a.Playing);
    }

    [Fact]
    public void DeviceThatWasNotExpectingAHouseholdRejectsIt()
    {
        SetUp();
        // 加入も家を作るのも待っていない端末は、中継が見せた家を信じない
        var stranger = new SimDevice("C", () => relay, () => now);
        stranger.Connect();
        relay.Run();
        Assert.Null(stranger.Client.HouseholdId);
        Assert.Contains(stranger.Events, e => e is ClientEvent.ChainInvalid { Reason: "unexpected_household" });
    }

    [Fact]
    public void RemoteControlReachesThePlayingDeviceOnce()
    {
        SetUp();
        a.StartPlaying(snapshot);
        relay.Run();
        Assert.True(b.Client.SendControl(ControlAction.Pause));
        relay.Run();
        Assert.False(a.Playing, "遠隔の一時停止に従っていない");
        Assert.True(a.Client.IsWriter, "一時停止しても書き手のまま");
        a.PushCursor();
        relay.Run();
        Assert.True(b.Client.SendControl(ControlAction.Seek, 90_000));
        relay.Run();
        Assert.Equal(90_000L, a.PositionMs);
        Assert.Equal(2, a.Controls.Count);
        Assert.False(a.Client.SendControl(ControlAction.Play));
        Assert.NotNull(b.Client.RemoteNowPosition());
    }

    private static HandoffItem QueueItem(string id) => new()
    {
        ItemId = id,
        VideoId = $"video-{id}",
        Title = string.Concat(Enumerable.Repeat("とても長い曲の名前 ", 8)) + id,
        Artists = [new HandoffRef { Name = "アーティスト", BrowseId = $"UC{id}" }],
        Album = new HandoffRef { Name = "アルバム", BrowseId = $"MPRE{id}" },
        DurationSeconds = 200,
        ThumbnailUrl = "https://lh3.googleusercontent.com/" + new string('x', 120),
        Origin = HandoffOrigin.UserAdded,
    };

    [Fact]
    public void QueueAddFromAnotherDeviceReachesThePlayingDevice()
    {
        SetUp();
        a.StartPlaying(snapshot);
        relay.Run();
        Assert.True(b.Client.SendQueueAdd([QueueItem("q1")], next: false));
        relay.Run();
        var add = Assert.Single(a.QueueAdds);
        Assert.Equal(["q1"], add.Ids);
        Assert.False(add.Next);
        Assert.Null(add.After);
        // 鳴らしている A 自身は送らない
        Assert.False(a.Client.SendQueueAdd([QueueItem("q2")], next: true));
    }

    [Fact]
    public void ManySongsAreSplitAndChainedInOrder()
    {
        SetUp();
        a.StartPlaying(snapshot);
        relay.Run();
        var songs = Enumerable.Range(1, 30).Select(i => QueueItem($"s{i}")).ToList();
        Assert.True(b.Client.SendQueueAdd(songs, next: true));
        relay.Run();
        Assert.True(a.QueueAdds.Count > 1, "1件の命令に収まらない曲数なのに分けていない");
        Assert.Equal(songs.Select(s => s.ItemId), a.QueueAdds.SelectMany(q => q.Ids));
        Assert.Null(a.QueueAdds[0].After);
        for (var i = 1; i < a.QueueAdds.Count; i++) Assert.Equal(a.QueueAdds[i - 1].Ids[^1], a.QueueAdds[i].After);
        Assert.All(a.QueueAdds, q => Assert.True(q.Next));
    }

    [Fact]
    public void QueueEditFromAnotherDeviceReachesThePlayingDevice()
    {
        SetUp();
        a.StartPlaying(snapshot);
        relay.Run();
        // 鳴っていない B のキューの画面で並べ替え・外す: 鳴らしている A が従う
        Assert.True(b.Client.SendQueueMove("q2", "q5"));
        Assert.True(b.Client.SendQueueMove("q3", null));
        Assert.True(b.Client.SendQueueRemove(["q4", "q6"]));
        relay.Run();
        Assert.Equal(new List<(string, string?)> { ("q2", "q5"), ("q3", null) }, a.QueueMoves);
        Assert.Equal(["q4", "q6"], Assert.Single(a.QueueRemoves));
        // 鳴らしている A 自身は送らない
        Assert.False(a.Client.SendQueueRemove(["q1"]));
    }

    [Fact]
    public void QueueEditFromAnotherAccountOrReplayedIsIgnored()
    {
        SetUp();
        a.StartPlaying(snapshot);
        relay.Run();
        b.Tag = "tag-account-2";
        Assert.False(b.Client.SendQueueRemove(["q1"]));
        b.Tag = "tag-account-1";
        RelayV2.Control? captured = null;
        relay.OnFromClient = (from, message) =>
        {
            if (from == b.DeviceId && message is RelayV2.Control c) captured = c;
        };
        Assert.True(b.Client.SendQueueRemove(["q1"]));
        relay.Run();
        Assert.Single(a.QueueRemoves);
        relay.Inject(a.DeviceId, captured!);
        relay.Run();
        Assert.Single(a.QueueRemoves);
    }

    [Fact]
    public void VolumeIsSharedAndCanBeChangedFromAnotherDevice()
    {
        SetUp();
        a.VolumePercent = 40;
        a.StartPlaying(snapshot);
        relay.Run();
        Assert.Equal(40, b.Client.RemoteNowPosition()?.Cursor.VolumePercent);
        Assert.True(b.Client.SendVolume(75));
        relay.Run();
        Assert.Equal(75, a.VolumePercent);
        a.PushCursor();
        relay.Run();
        Assert.Equal(75, b.Client.RemoteNowPosition()?.Cursor.VolumePercent);
        Assert.True(b.Client.SendVolume(150));
        relay.Run();
        Assert.Equal(100, a.VolumePercent);
        Assert.False(a.Client.SendVolume(10));
    }

    [Fact]
    public void VolumeJsonMatchesKotlin()
    {
        const string kotlin = """{"type":"control","commandId":"c1","from":"dev-b","to":"dev-a","action":"volume","positionMs":null,"basedOnVersion":7,"accountTag":"tag","onItemId":null,"volumePercent":35}""";
        var control = Assert.IsType<CommandBody.Control>(ConnectJson.Decode<CommandBody>(kotlin));
        Assert.Equal(ControlAction.Volume, control.Action);
        Assert.Equal(35, control.VolumePercent);
    }

    [Fact]
    public void QueueAddJsonMatchesKotlin()
    {
        // Android 版（kotlinx.serialization）が書く形
        const string kotlin = """{"type":"queue_add","commandId":"c1","from":"dev-b","to":"dev-a","items":[{"itemId":"i1","videoId":"v1","title":"曲","artists":[{"name":"人","browseId":"UC1"}],"album":null,"durationSeconds":200,"thumbnailUrl":null,"origin":"user"}],"next":true,"afterItemId":null,"basedOnVersion":7,"accountTag":"tag"}""";
        var add = Assert.IsType<CommandBody.QueueAdd>(ConnectJson.Decode<CommandBody>(kotlin));
        Assert.Equal("i1", Assert.Single(add.Items).ItemId);
        Assert.Equal(HandoffOrigin.UserAdded, add.Items[0].Origin);
        Assert.True(add.Next);
        Assert.Null(add.AfterItemId);
        Assert.Equal(7, add.BasedOnVersion);
        // 書き戻しても同じ形（判別子が先頭）
        Assert.StartsWith("""{"type":"queue_add",""", ConnectJson.Encode<CommandBody>(add));
    }

    [Fact]
    public void QueueEditJsonMatchesKotlin()
    {
        // Android 版（kotlinx.serialization）が書く形
        const string kotlin = """{"type":"queue_edit","commandId":"c1","from":"dev-b","to":"dev-a","op":"move","itemIds":["i2"],"afterItemId":"i5","basedOnVersion":7,"accountTag":"tag"}""";
        var edit = Assert.IsType<CommandBody.QueueEdit>(ConnectJson.Decode<CommandBody>(kotlin));
        Assert.Equal(CommandBody.QueueEdit.Move, edit.Op);
        Assert.Equal(["i2"], edit.ItemIds);
        Assert.Equal("i5", edit.AfterItemId);
        Assert.Equal(7, edit.BasedOnVersion);
        // afterItemId を省いた形も読める
        const string remove = """{"type":"queue_edit","commandId":"c2","from":"dev-b","to":"dev-a","op":"remove","itemIds":["i3","i4"],"basedOnVersion":7,"accountTag":"tag"}""";
        var removed = Assert.IsType<CommandBody.QueueEdit>(ConnectJson.Decode<CommandBody>(remove));
        Assert.Equal(CommandBody.QueueEdit.Remove, removed.Op);
        Assert.Null(removed.AfterItemId);
        // 書き戻しても同じ形（判別子が先頭）
        Assert.StartsWith("""{"type":"queue_edit",""", ConnectJson.Encode<CommandBody>(edit));
    }

    [Fact]
    public void NextThatArrivesAfterTheSongAlreadyChangedIsIgnored()
    {
        SetUp();
        a.StartPlaying(snapshot);
        relay.Run();
        // 曲の終わりぎわに「次へ」を押した。届く前に、鳴らしている端末は自分で次の曲へ進んだ
        Assert.True(b.Client.SendControl(ControlAction.Next));
        a.CurrentItemId = "item-1";
        a.PushCursor();
        relay.Run();
        Assert.Empty(a.Controls);
        // 今の曲を見て押した「次へ」には従う
        Assert.True(b.Client.SendControl(ControlAction.Next));
        relay.Run();
        Assert.Single(a.Controls);
    }

    [Fact]
    public void RemoteControlFromAnotherAccountOrReplayedIsIgnored()
    {
        SetUp();
        a.StartPlaying(snapshot);
        relay.Run();
        b.Tag = "tag-account-2";
        Assert.False(b.Client.SendControl(ControlAction.Pause));
        b.Tag = "tag-account-1";
        RelayV2.Control? captured = null;
        relay.OnFromClient = (from, message) =>
        {
            if (from == b.DeviceId && message is RelayV2.Control c) captured = c;
        };
        Assert.True(b.Client.SendControl(ControlAction.Pause));
        relay.Run();
        Assert.Single(a.Controls);
        relay.Inject(a.DeviceId, captured!);
        relay.Run();
        Assert.Single(a.Controls);
    }

    [Fact]
    public void RemoteControlHeldBackForMoreThanFiveMinutesIsIgnored()
    {
        SetUp();
        a.StartPlaying(snapshot);
        relay.Run();
        RelayV2.Control? held = null;
        relay.OnFromClient = (from, message) =>
        {
            if (from == b.DeviceId && message is RelayV2.Control c) held = c;
        };
        a.Playing = false;
        a.PushCursor();
        relay.Run();
        Assert.True(b.Client.SendControl(ControlAction.Play));
        // 中継が届けるのを6分遅らせる（送った分も差し込んだ分も、届くのは6分後）
        now += 6 * 60 * 1000L;
        relay.Inject(a.DeviceId, held!);
        relay.Run();
        Assert.Empty(a.Controls);
        Assert.False(a.Playing);
    }

    [Fact]
    public void ChoosingAnotherDeviceMovesPlaybackThere()
    {
        SetUp();
        a.StartPlaying(snapshot);
        relay.Run();
        Assert.True(a.Client.RequestRemoteTransfer(b.DeviceId));
        relay.Run();
        Assert.True(b.Playing, "選んだ B で鳴っていない");
        Assert.False(a.Playing);
        Assert.True(b.Client.IsWriter);
    }

    [Fact]
    public void ChoosingASongWhileAnotherDevicePlaysPlaysItThere()
    {
        SetUp();
        a.StartPlaying(snapshot);
        relay.Run();
        var other = HandoffCodec.Encode(InteropTests.SampleSnapshot() with { CurrentItemId = InteropTests.SampleSnapshot().Items[^1].ItemId });
        b.Queue = other;
        b.CurrentItemId = InteropTests.SampleSnapshot().Items[^1].ItemId;
        b.PositionMs = 0;
        b.Client.SetLocalQueue(other);
        b.PushCursor();
        Assert.True(b.Client.PlayOn(a.DeviceId));
        relay.Run();
        Assert.True(a.Playing, "A で鳴っていない");
        Assert.Equal(other, a.Queue);
        Assert.False(b.Playing);
        Assert.True(a.Client.IsWriter);
    }

    [Fact]
    public void DeviceNamesReachTheWholeHousehold()
    {
        SetUp();
        Assert.True(b.Client.SendProfile("Bee"));
        relay.Run();
        Assert.Contains(a.Events, e => e is ClientEvent.Profile p && p.DeviceId == b.DeviceId && p.Name == "Bee");
    }
}
