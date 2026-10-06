using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Wave.Core.Connect;

namespace Wave.Core.Tests.Connect;

/// <summary>
/// Android（Kotlin）との相互運用。Kotlin の CSharpInteropTest が固定のテスト鍵で作った成果物（Fixtures/connect/kotlin.json）を C# で検証・開く。
/// 環境変数 WAVE_WRITE_CONNECT_FIXTURES=1 のときは、C# で作った成果物を Fixtures/connect/csharp.json に書き出す（Kotlin のテストが検証する）。
/// </summary>
public class InteropTests
{
    private const string HandoffId = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private readonly JsonObject fixture = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", "connect", "kotlin.json")))!.AsObject();

    private string Str(JsonNode node, string key) => node[key]!.GetValue<string>();

    private SoftwareDevice Device(string name) => SoftwareDevice.FromPkcs8(B64.Decode(Str(fixture["keys"]![name]!, "identity"))!, B64.Decode(Str(fixture["keys"]![name]!, "kx"))!);

    private static T Decode<T>(JsonNode node) where T : class => node.Deserialize<T>(ConnectJson.Options)!;

    private static ECDiffieHellman EphemeralFromPkcs8(string b64)
    {
        var key = ECDiffieHellman.Create();
        key.ImportPkcs8PrivateKey(B64.Decode(b64)!, out _);
        return key;
    }

    [Fact]
    public void DeviceIdsAndMemberKeysMatchKotlin()
    {
        using var a = Device("a");
        Assert.Equal(Str(fixture["deviceIds"]!, "a"), a.DeviceId);
        Assert.Equal(Str(fixture["deviceIds"]!, "pc"), Device("pc").DeviceId);
        var keys = Decode<MemberKeys>(fixture["memberKeys"]!["a"]!);
        Assert.Equal(B64.Encode(a.IdentityPub), keys.IdentityPub);
        Assert.Equal(B64.Encode(Hpke.SerializePublicKey(a.KxPub)), keys.KxPub);
    }

    [Fact]
    public void VerifiesKotlinChain()
    {
        var records = fixture["chain"]!.AsArray().Select(n => Decode<ChainRecord>(n!)).ToList();
        var chain = Assert.IsType<ChainVerification.Ok>(MemberChain.Verify(records)).Chain;
        Assert.Equal(Str(fixture, "householdId"), chain.HouseholdId);
        Assert.Equal(fixture["chainHashes"]!.AsArray().Select(n => n!.GetValue<string>()), records.Select(MemberChain.Hash));
        Assert.Equal(new[] { Str(fixture["deviceIds"]!, "a"), Str(fixture["deviceIds"]!, "b"), Str(fixture["deviceIds"]!, "pc") }, chain.Members.Keys);
        Assert.Equal(Role.Controller, chain.Members[Str(fixture["deviceIds"]!, "pc")].Keys.Role);
        // JSON の形も同じ（読んで書き直すと Kotlin と同じ文字列）
        foreach (var node in fixture["chain"]!.AsArray()) Assert.Equal(node!.ToJsonString(), ConnectJson.Encode(Decode<ChainRecord>(node)));
        var tag = fixture["accountTag"]!;
        Assert.Equal(Str(tag, "tag"), AccountTag.Of(Str(tag, "householdId"), Str(tag, "accountId")));
    }

    [Fact]
    public void OpensKotlinDocs()
    {
        var chain = fixture["chain"]!.AsArray().Select(n => Decode<ChainRecord>(n!)).ToList().Verified();
        var devices = new[] { "a", "b", "pc" }.Select(Device).ToList();
        foreach (var entry in fixture["docs"]!.AsArray())
        {
            var doc = Decode<SealedDoc>(entry!["doc"]!);
            Assert.Equal(Str(entry, "hash"), DocSealer.Hash(doc));
            Assert.Equal(entry["doc"]!.ToJsonString(), ConnectJson.Encode(doc));
            foreach (var device in devices)
            {
                var opened = Assert.IsType<DocOpen.Ok>(DocSealer.Open(doc, chain, device));
                Assert.Equal(Str(entry, "plaintext"), Encoding.UTF8.GetString(opened.Plaintext));
            }
            var plaintext = Str(entry, "plaintext");
            switch (Str(entry, "name"))
            {
                case "queue":
                    var queue = ConnectJson.Decode<QueueBody>(plaintext)!;
                    Assert.Equal(plaintext, ConnectJson.Encode(queue));
                    Assert.IsType<HandoffDecodeResult.Ok>(HandoffCodec.Decode(queue.Snapshot, HandoffId));
                    break;
                case "cursor":
                    var cursor = ConnectJson.Decode<CursorBody>(plaintext)!;
                    Assert.Equal(HandoffRepeat.One, cursor.Repeat);
                    Assert.Null(cursor.SessionStartVersion);
                    Assert.Equal(plaintext, ConnectJson.Encode(cursor));
                    break;
                case "command":
                    var command = Assert.IsType<CommandBody.Yield>(ConnectJson.Decode<CommandBody>(plaintext));
                    Assert.Equal(plaintext, ConnectJson.Encode<CommandBody>(command));
                    break;
                case "profile":
                    Assert.Equal("Windows PC", ConnectJson.Decode<ProfileBody>(plaintext)!.Name);
                    break;
            }
        }
    }

    [Fact]
    public void RelayMessagesRoundTripToTheSameText()
    {
        var texts = fixture["relay"]!.AsArray().Select(n => n!.GetValue<string>()).ToList();
        Assert.True(texts.Count > 30);
        foreach (var text in texts)
        {
            var message = RelayV2.Decode(text);
            Assert.NotNull(message);
            Assert.Equal(text, RelayV2.Encode(message));
        }
        Assert.IsType<RelayV2.StateGet>(RelayV2.Decode("""{"op":"state_get"}"""));
        // 判別子が先頭でなくても、知らない項目があっても読む。知らない op は null
        Assert.Equal(new RelayV2.ChainGet { FromSeq = 3 }, RelayV2.Decode("""{"fromSeq":3,"x":1,"op":"chain_get"}"""));
        Assert.Null(RelayV2.Decode("""{"op":"nope"}"""));
        Assert.Null(RelayV2.Decode("""{"op":"chain_ok","headSeq":1}"""));
        // null 可の項目は欠けていても null
        var active = Assert.IsType<RelayV2.ActiveChanged>(RelayV2.Decode("""{"op":"active_changed","claimOrder":2}"""));
        Assert.Null(active.ActiveDeviceId);
    }

    [Fact]
    public void HandoffCodecMatchesKotlin()
    {
        var text = Str(fixture["handoff"]!, "text");
        var snapshot = Assert.IsType<HandoffDecodeResult.Ok>(HandoffCodec.Decode(text, HandoffId)).Snapshot;
        Assert.Equal("夜に駆ける \"quoted\" 🎵", snapshot.Items[0].Title);
        Assert.Equal(new HandoffSource.Radio { SeedVideoId = "abcdefghijA" }, snapshot.Source);
        Assert.Equal(HandoffOrigin.UserAdded, snapshot.Items[1].Origin);
        Assert.Equal(text, HandoffCodec.Encode(snapshot));
    }

    [Fact]
    public void PairsWithKotlinOnBothSides()
    {
        var p = fixture["pairing"]!;
        var offer = Pairing.DecodeOffer(Str(p, "offer"));
        Assert.NotNull(offer);
        var hello = Pairing.DecodeHello(JsonSerializer.SerializeToElement(p["hello"]))!;
        var confirm = Pairing.DecodeConfirm(JsonSerializer.SerializeToElement(p["confirm"]))!;

        // C# の Responder（Kotlin の A と同じ使い捨ての鍵・token）が Kotlin の Hello を受ける
        using var a = Device("a");
        var responder = new Pairing.Responder(a, Str(p, "responderName"), Str(p, "accountId"), () => 0, Str(p, "accountId"), Str(p, "confirmExtension"), EphemeralFromPkcs8(Str(p, "responderEphemeral")), B64.Decode(offer.Token)!);
        Assert.Equal(offer, responder.Offer);
        Assert.True(responder.OnHello(hello).IsSuccess);
        Assert.Equal(Pairing.Failure.WrongCode, responder.OnCodeEntered(Str(p, "code") == "000000" ? "111111" : "000000").Error);
        var pairOnA = responder.OnCodeEntered(Str(p, "code")).GetOrThrow();
        Assert.Equal(Str(p, "aSendKey"), B64.Encode(pairOnA.SendKey));
        Assert.Equal(Str(p, "aReceiveKey"), B64.Encode(pairOnA.ReceiveKey));
        Assert.Equal(Str(p, "accountKey"), B64.Encode(pairOnA.AccountKey));
        Assert.Equal(Str(p, "helloExtension"), pairOnA.PeerExtension);
        Assert.Equal("Windows test", pairOnA.PeerDeviceName);
        // 拡張は Kotlin の ConnectJson の MemberKeys
        Assert.Equal(Str(fixture["deviceIds"]!, "b"), ConnectJson.Decode<MemberKeys>(pairOnA.PeerExtension!)!.DeviceId);

        // C# の Initiator（Kotlin の B と同じ使い捨ての鍵）が Kotlin の Confirm を受け、同じ6桁を出す
        using var b = Device("b");
        var initiator = new Pairing.Initiator(offer, b, Str(p, "initiatorName"), Str(p, "accountId"), Str(p, "helloExtension"), EphemeralFromPkcs8(Str(p, "initiatorEphemeral")));
        var confirmed = initiator.OnConfirm(confirm).GetOrThrow();
        Assert.Equal(Str(p, "code"), confirmed.Code);
        Assert.Equal(Str(p, "bSendKey"), B64.Encode(confirmed.Pair.SendKey));
        Assert.Equal(pairOnA.SendKey, confirmed.Pair.ReceiveKey);
        Assert.Equal("Pixel 3", confirmed.Pair.PeerDeviceName);
        var joinInfo = ConnectJson.Decode<ConnectClient.JoinInfo>(confirmed.Pair.PeerExtension!)!;
        Assert.Equal(Str(fixture, "householdId"), joinInfo.HouseholdId);
        Assert.Equal(confirmed.Pair.PeerExtension, ConnectJson.Encode(joinInfo));
    }

    /// <summary>C# で作った成果物を書き出す（Kotlin の CSharpInteropTest.verifyCSharpFixtures が検証する）。</summary>
    [Fact]
    public void WriteCSharpFixtures()
    {
        if (Environment.GetEnvironmentVariable("WAVE_WRITE_CONNECT_FIXTURES") != "1") return;
        using var a = Device("a");
        using var b = Device("b");
        using var pc = Device("pc");
        var genesis = MemberChain.Genesis(a, a.MemberKeys(Role.Player));
        var addB = MemberChain.Add(a, new[] { genesis }.Verified(), b.MemberKeys(Role.Player));
        var addPc = MemberChain.Add(b, new[] { genesis, addB }.Verified(), pc.MemberKeys(Role.Controller));
        var records = new List<ChainRecord> { genesis, addB, addPc };
        var chain = records.Verified();
        var tag = AccountTag.Of(chain.HouseholdId, "account-1");

        var snapshotText = HandoffCodec.Encode(SampleSnapshot());
        var queueBody = new QueueBody { AccountTag = tag, Snapshot = snapshotText };
        var queueDoc = DocSealer.Seal(a, chain, DocKind.Queue, 1, null, 1_700_000_000_001, ConnectJson.EncodeToUtf8(queueBody));
        var cursorBody = new CursorBody
        {
            QueueRef = DocSealer.Hash(queueDoc), CurrentItemId = "00000000-0000-4000-8000-000000000001", PositionMs = 42_000, Playing = true, Repeat = HandoffRepeat.One,
            SessionBase = 3, SessionStartVersion = 4, DeviceSeq = 7, ListenedMs = 30_000, HistorySent = false, AccountTag = tag, StartedAtEpochMs = 1_700_000_000_002,
        };
        var cursorDoc = DocSealer.Seal(a, chain, DocKind.Cursor, 4, "prev-hash", 1_700_000_000_003, ConnectJson.EncodeToUtf8(cursorBody));
        CommandBody command = new CommandBody.Yield { ClaimId = "claim-0000000000000001", From = b.DeviceId, To = a.DeviceId, SessionBase = 4, AccountTag = tag };
        var commandDoc = DocSealer.Seal(b, chain, DocKind.Command, 1, null, 1_700_000_000_004, ConnectJson.EncodeToUtf8(command));
        var profile = new ProfileBody { Name = "Windows PC" };
        var profileDoc = DocSealer.Seal(pc, chain, DocKind.Profile, 1, null, 1_700_000_000_005, ConnectJson.EncodeToUtf8(profile));

        // ペアリング（どちらも C#）。使い捨ての鍵を書き出し、Kotlin が同じ鍵で相手の側を再現する
        var joinInfo = new ConnectClient.JoinInfo { HouseholdId = chain.HouseholdId, HeadSeq = chain.HeadSeq, HeadHash = chain.HeadHash, StoreId = "store-1", CursorFloor = 4, QueueFloor = 1 };
        var joinInfoJson = ConnectJson.Encode(joinInfo);
        var memberKeysJson = ConnectJson.Encode(b.MemberKeys(Role.Player));
        var eA = WaveCrypto.GenerateEphemeralKeyPair();
        var eB = WaveCrypto.GenerateEphemeralKeyPair();
        var responder = new Pairing.Responder(a, "Pixel 3", "account-1", () => 0, "account-1", joinInfoJson, eA, WaveCrypto.RandomBytes(Pairing.TokenBytes));
        var offerText = Pairing.EncodeOffer(responder.Offer);
        var initiator = new Pairing.Initiator(Pairing.DecodeOffer(offerText)!, b, "Windows ‮test", "account-1", memberKeysJson, eB);
        var hello = initiator.Hello();
        var confirm = responder.OnHello(hello).GetOrThrow();
        var confirmed = initiator.OnConfirm(confirm).GetOrThrow();
        var pairOnA = responder.OnCodeEntered(confirmed.Code).GetOrThrow();

        var stored = new StoredState { Kind = StateKind.Cursor, Version = 4, Doc = cursorDoc, StoreId = "store-1", ReceivedAtServerMs = 1, ServerNowMs = 2, ActiveDeviceId = a.DeviceId };
        var tokenHash = B64.Encode(responder.TokenHash);
        RelayV2[] relay =
        [
            new RelayV2.Challenge { Host = "relay.example", Nonce = "bm9uY2U" },
            new RelayV2.Auth { PublicKey = B64.Encode(a.IdentityPub), Signature = "c2ln" },
            new RelayV2.Welcome { DeviceId = a.DeviceId, StoreId = "store-1" },
            new RelayV2.Welcome
            {
                DeviceId = a.DeviceId, StoreId = "store-1", HouseholdId = chain.HouseholdId, HeadSeq = chain.HeadSeq, HeadHash = chain.HeadHash, ActiveDeviceId = a.DeviceId,
                Online = [a.DeviceId], States = [stored], ClaimOrders = new Dictionary<string, long> { [a.DeviceId] = 3 },
            },
            new RelayV2.HouseholdCreate { Genesis = genesis },
            new RelayV2.ChainGet(),
            new RelayV2.Chain { Records = records },
            new RelayV2.ChainAppend { Record = addPc },
            new RelayV2.ChainOk { HeadSeq = 2, HeadHash = chain.HeadHash },
            RelayV2.StateGet.Instance,
            new RelayV2.StatePut { RequestId = "req-1", Kind = StateKind.Queue, ExpectVersion = 0, Doc = queueDoc },
            new RelayV2.StateOk { RequestId = "req-1", Kind = StateKind.Queue, Version = 1 },
            new RelayV2.StateConflict { RequestId = "req-2", Kind = StateKind.Cursor },
            new RelayV2.StateConflict { RequestId = "req-2", Kind = StateKind.Cursor, Latest = stored },
            new RelayV2.State { Stored = stored },
            new RelayV2.Claim { ClaimId = "claim-0000000000000001", YieldDoc = commandDoc },
            new RelayV2.Transfer { ClaimId = "claim-0000000000000001", BasedOnVersion = 4, YieldDoc = commandDoc },
            new RelayV2.YieldDelivery { ClaimId = "claim-0000000000000001", YieldDoc = commandDoc },
            new RelayV2.Yielded { ClaimId = "claim-0000000000000001", ExpectVersion = 4, Doc = cursorDoc },
            new RelayV2.Release { ClaimId = "claim-0000000000000001" },
            new RelayV2.ActiveChanged { ClaimOrder = 5 },
            new RelayV2.ActiveChanged { ActiveDeviceId = a.DeviceId, ClaimId = "c", ClaimOrder = 6, PendingOwner = b.DeviceId },
            new RelayV2.PendingExpired { ClaimId = "c" },
            new RelayV2.Presence { Online = [a.DeviceId, b.DeviceId] },
            new RelayV2.PairingOpen { TokenHash = tokenHash },
            new RelayV2.PairHelloIn { Token = responder.Offer.Token, Hello = Pairing.ToJson(hello) },
            new RelayV2.PairHello { TokenHash = tokenHash, Hello = Pairing.ToJson(hello) },
            new RelayV2.PairConfirm { TokenHash = tokenHash, Confirm = Pairing.ToJson(confirm) },
            new RelayV2.PairConfirmed { Confirm = Pairing.ToJson(confirm) },
            new RelayV2.Error { Code = "not_active" },
            new RelayV2.Error { Code = "stale_head", RequestId = "req-1" },
        ];

        JsonObject DocEntry(string name, SealedDoc doc, string plaintext) => new()
        {
            ["name"] = name,
            ["doc"] = JsonSerializer.SerializeToNode(doc, ConnectJson.Options),
            ["plaintext"] = plaintext,
            ["hash"] = DocSealer.Hash(doc),
        };

        var output = new JsonObject
        {
            ["chain"] = new JsonArray(records.Select(r => JsonSerializer.SerializeToNode(r, ConnectJson.Options)).ToArray()),
            ["chainHashes"] = new JsonArray(records.Select(r => (JsonNode)MemberChain.Hash(r)).ToArray()),
            ["householdId"] = chain.HouseholdId,
            ["memberKeysJson"] = memberKeysJson,
            ["accountTag"] = new JsonObject { ["householdId"] = chain.HouseholdId, ["accountId"] = "account-1", ["tag"] = tag },
            ["docs"] = new JsonArray(
                DocEntry("queue", queueDoc, ConnectJson.Encode(queueBody)),
                DocEntry("cursor", cursorDoc, ConnectJson.Encode(cursorBody)),
                DocEntry("command", commandDoc, ConnectJson.Encode(command)),
                DocEntry("profile", profileDoc, ConnectJson.Encode(profile))),
            ["handoff"] = new JsonObject { ["handoffId"] = HandoffId, ["text"] = snapshotText },
            ["relay"] = new JsonArray(relay.Select(m => (JsonNode)RelayV2.Encode(m)).ToArray()),
            ["pairing"] = new JsonObject
            {
                ["offer"] = offerText,
                ["responderEphemeral"] = B64.Encode(eA.ExportPkcs8PrivateKey()),
                ["initiatorEphemeral"] = B64.Encode(eB.ExportPkcs8PrivateKey()),
                ["accountId"] = "account-1",
                ["initiatorName"] = "Windows ‮test",
                ["hello"] = JsonSerializer.SerializeToNode(Pairing.ToJson(hello)),
                ["confirm"] = JsonSerializer.SerializeToNode(Pairing.ToJson(confirm)),
                ["code"] = confirmed.Code,
                ["helloExtension"] = memberKeysJson,
                ["confirmExtension"] = joinInfoJson,
                ["aSendKey"] = B64.Encode(pairOnA.SendKey),
            },
        };
        var directory = SourceFixtureDirectory();
        File.WriteAllText(Path.Combine(directory, "csharp.json"), output.ToJsonString(new JsonSerializerOptions { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }) + "\n");
    }

    /// <summary>Kotlin の CSharpInteropTest の sampleSnapshot と同じもの。</summary>
    internal static HandoffSnapshot SampleSnapshot() => new()
    {
        SchemaVersion = HandoffCodec.SchemaVersion,
        HandoffId = HandoffId,
        Items =
        [
            new HandoffItem
            {
                ItemId = "00000000-0000-4000-8000-000000000001", VideoId = "abcdefghijA", Title = "夜に駆ける \"quoted\" 🎵",
                Artists = [new HandoffRef { Name = "YOASOBI", BrowseId = "UCsynthetic000000000000000" }], Album = new HandoffRef { Name = "THE BOOK" },
                DurationSeconds = 261, ThumbnailUrl = "https://lh3.googleusercontent.com/x=w120-h120", Origin = HandoffOrigin.Source,
            },
            new HandoffItem { ItemId = "00000000-0000-4000-8000-000000000002", VideoId = "abcdefghijB", Title = "second", Artists = [], Origin = HandoffOrigin.UserAdded },
        ],
        CurrentItemId = "00000000-0000-4000-8000-000000000001",
        PositionMs = 42_000,
        Playing = true,
        Repeat = HandoffRepeat.All,
        Source = new HandoffSource.Radio { SeedVideoId = "abcdefghijA" },
        RadioPlaylistId = "RDsynthetic",
        Exhausted = false,
        ListenedMs = 30_000,
        HistorySent = true,
        CapturedAtEpochMs = 1_700_000_000_000,
    };

    private static string SourceFixtureDirectory()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "Wave.Core.Tests.csproj"))) dir = dir.Parent;
        if (dir is null) throw new InvalidOperationException("Wave.Core.Tests.csproj が見つからない");
        return Path.Combine(dir.FullName, "Fixtures", "connect");
    }
}
