using Wave.Core.Connect;
using static Wave.Core.Tests.Connect.ConnectTestSupport;

namespace Wave.Core.Tests.Connect;

/// <summary>署名とハッシュの対象のバイト列を、Kotlin の CanonicalBytesTest と同じ既知の値で固定する。</summary>
public class CanonicalBytesTests
{
    private static MemberKeys Keys(string id, string pub, string kx, string sig) => new() { DeviceId = id, IdentityPub = pub, KxPub = kx, Role = Role.Player, KxSignature = sig };

    [Fact]
    public void ChainRecordHashesMatchKotlin()
    {
        var genesis = new ChainRecord { Seq = 0, Type = ChainType.Genesis, Target = Keys("dev-a", "pub-a", "kx-a", "kxsig-a"), SignerId = "dev-a", Signature = "sig" };
        var add = new ChainRecord { HouseholdId = "house", Seq = 1, PrevHash = "prev", Type = ChainType.Add, Target = Keys("dev-b", "pub-b", "kx-b", "kxsig-b"), SignerId = "dev-a", Signature = "sig" };
        var remove = new ChainRecord { HouseholdId = "house", Seq = 2, PrevHash = "prev2", Type = ChainType.Remove, RemovedDeviceId = "dev-b", SignerId = "dev-a", Signature = "sig" };
        Assert.Equal("07McxbsrW_iq4fxMp1hn5SQy6I5gpkAGc1OzMU1ZcZI", MemberChain.Hash(genesis));
        Assert.Equal("Bc9cJdgfyeNXxPZ5HM4SisXRhTNBchPH6MbaOb57ATI", MemberChain.Hash(add));
        Assert.Equal("A6e-7u5LQFx11vmK5Zj6n7QgeG01bctfiCnA9VnjvaU", MemberChain.Hash(remove));
        Assert.Equal(MemberChain.Hash(add), MemberChain.Hash(add with { Signature = "other" }));
    }

    [Fact]
    public void SealedDocHashesMatchKotlin()
    {
        var header = new DocHeader { Kind = DocKind.Cursor, HouseholdId = "house", WriterId = "dev-a", Version = 7, PrevHash = "p", HeadSeq = 3, HeadHash = "h", IssuedAtEpochMs = 1_700_000_000_000 };
        var doc = new SealedDoc
        {
            Header = header,
            Nonce = "nonce",
            Ciphertext = "cipher",
            Recipients = [new DocRecipient { DeviceId = "dev-a", Enc = "enc-a", WrappedKey = "wrap-a" }, new DocRecipient { DeviceId = "dev-b", Enc = "enc-b", WrappedKey = "wrap-b" }],
            Signature = "sig",
        };
        Assert.Equal("OIhuUfwQMOoU568qY4i4GiaVIZqPkDqmg_gOeXLnaFI", DocSealer.Hash(doc));
        Assert.Equal("6ajZYk-MILk4axIoVv59uvEvaxL1XWV1ijvh2K9c6jQ", DocSealer.Hash(doc with { Header = header with { Kind = DocKind.Queue, PrevHash = null } }));
        Assert.Equal(DocSealer.Hash(doc), DocSealer.Hash(doc with { Signature = "other" }));
    }
}

public class MemberChainTests
{
    private readonly TestDevice a = new();
    private readonly TestDevice b = new();
    private readonly TestDevice pc = new(Role.Controller);

    private List<ChainRecord> GenesisAndB()
    {
        var genesis = MemberChain.Genesis(a, a.Keys);
        return [genesis, MemberChain.Add(a, new[] { genesis }.Verified(), b.Keys)];
    }

    private static string InvalidReason(IReadOnlyList<ChainRecord> records) => Assert.IsType<ChainVerification.Invalid>(MemberChain.Verify(records)).Reason;

    [Fact]
    public void GenesisAddAndRemoveVerify()
    {
        var records = GenesisAndB();
        var chain = records.Verified();
        Assert.Equal(new[] { a.DeviceId, b.DeviceId }, chain.Members.Keys);
        Assert.Equal(MemberChain.Hash(records[0]), chain.HouseholdId);
        List<ChainRecord> withPc = [.. records, MemberChain.Add(b, chain, pc.Keys)];
        List<ChainRecord> removed = [.. withPc, MemberChain.Remove(a, withPc.Verified(), b.DeviceId)];
        var finalChain = removed.Verified();
        Assert.Equal(new[] { a.DeviceId, pc.DeviceId }, finalChain.Members.Keys);
        Assert.Equal(new[] { b.DeviceId }, finalChain.Removed);
        Assert.Equal(3L, finalChain.HeadSeq);
    }

    [Fact]
    public void RejectsForgedOrUnauthorizedRecords()
    {
        var records = GenesisAndB();
        var chain = records.Verified();
        var outsider = new TestDevice();
        Assert.Equal("signer_not_member", InvalidReason([.. records, MemberChain.Add(outsider, chain, new TestDevice().Keys)]));
        var forged = MemberChain.Add(outsider, chain, new TestDevice().Keys) with { SignerId = a.DeviceId };
        Assert.Equal("signature", InvalidReason([.. records, forged]));
        List<ChainRecord> withPc = [.. records, MemberChain.Add(a, chain, pc.Keys)];
        Assert.Equal("add_by_controller", InvalidReason([.. withPc, MemberChain.Add(pc, withPc.Verified(), new TestDevice().Keys)]));
        Assert.Equal("remove_by_controller", InvalidReason([.. withPc, MemberChain.Remove(pc, withPc.Verified(), b.DeviceId)]));
        Assert.IsType<ChainVerification.Ok>(MemberChain.Verify([.. withPc, MemberChain.Remove(pc, withPc.Verified(), pc.DeviceId)]));
        List<ChainRecord> onlyA = [.. records, MemberChain.Remove(a, chain, b.DeviceId)];
        Assert.Equal("last_player", InvalidReason([.. onlyA, MemberChain.Remove(a, onlyA.Verified(), a.DeviceId)]));
        Assert.Equal("signer_not_member", InvalidReason([.. onlyA, MemberChain.Add(b, onlyA.Verified(), new TestDevice().Keys)]));
    }

    [Fact]
    public void RejectsBrokenLinksAndKeys()
    {
        var records = GenesisAndB();
        Assert.Equal("link", InvalidReason([records[0], records[1] with { PrevHash = "x" }]));
        Assert.Equal("link", InvalidReason([MemberChain.Genesis(b, b.Keys), records[1]]));
        var genesisOnly = new[] { records[0] }.Verified();
        Assert.Equal("add_keys", InvalidReason([records[0], MemberChain.Add(a, genesisOnly, b.Keys with { KxPub = new TestDevice().Keys.KxPub })]));
        Assert.Equal("add_keys", InvalidReason([records[0], MemberChain.Add(a, genesisOnly, b.Keys with { DeviceId = new TestDevice().DeviceId })]));
        Assert.Equal("add_existing", InvalidReason([.. records, MemberChain.Add(a, records.Verified(), b.Keys)]));
    }

    [Fact]
    public void DetectsRollbackAndForkAgainstTrustedHead()
    {
        var records = GenesisAndB();
        var chain = records.Verified();
        var head = new MemberChain.Head(chain.HeadSeq, chain.HeadHash);
        Assert.IsType<ChainVerification.Rollback>(MemberChain.Verify(records.Take(1).ToList(), head));
        var branch = new List<ChainRecord> { records[0], MemberChain.Add(a, new[] { records[0] }.Verified(), new TestDevice().Keys) };
        Assert.IsType<ChainVerification.Fork>(MemberChain.Verify(branch, head));
        Assert.IsType<ChainVerification.Ok>(MemberChain.Verify([.. records, MemberChain.Add(b, chain, pc.Keys)], head));
    }
}

public class DocSealerTests
{
    private readonly TestDevice a = new();
    private readonly TestDevice b = new();
    private readonly TestDevice pc = new(Role.Controller);
    private readonly List<ChainRecord> records;
    private readonly List<ChainRecord> withPc;
    private readonly VerifiedChain chain;
    private readonly byte[] body = "cursor-body"u8.ToArray();

    public DocSealerTests()
    {
        var genesis = MemberChain.Genesis(a, a.Keys);
        records = [genesis, MemberChain.Add(a, new[] { genesis }.Verified(), b.Keys)];
        withPc = [.. records, MemberChain.Add(a, records.Verified(), pc.Keys)];
        chain = withPc.Verified();
    }

    private SealedDoc Seal(IDeviceSigner? signer = null, DocKind kind = DocKind.Cursor, VerifiedChain? onChain = null) =>
        DocSealer.Seal(signer ?? a, onChain ?? chain, kind, 7, null, 1, body);

    [Fact]
    public void EveryMemberOpensAndOthersCannot()
    {
        var doc = Seal();
        foreach (var device in new[] { a, b, pc })
        {
            var opened = Assert.IsType<DocOpen.Ok>(DocSealer.Open(doc, chain, device));
            Assert.Equal(body, opened.Plaintext);
            Assert.False(opened.StaleRecipients);
        }
        Assert.Equal(new DocOpen.Rejected("not_for_me"), DocSealer.Open(doc, chain, new TestDevice()));
        Assert.Equal(0, (B64.Decode(doc.Ciphertext)!.Length - 16) % 4096);
    }

    [Fact]
    public void RejectsTamperingSubstitutionAndWrongRoles()
    {
        var doc = Seal();
        Assert.Equal(new DocOpen.Rejected("signature"), DocSealer.Open(doc with { Header = doc.Header with { Version = 8 } }, chain, b));
        Assert.Equal(new DocOpen.Rejected("signature"), DocSealer.Open(doc with { Ciphertext = Seal().Ciphertext }, chain, b));
        Assert.Equal(new DocOpen.Rejected("signature"), DocSealer.Open(doc with { Recipients = doc.Recipients.Where(r => r.DeviceId != pc.DeviceId).ToList() }, chain, b));
        var other = Seal();
        var swapped = doc.Recipients.Select(r => r.DeviceId == b.DeviceId ? other.Recipients.First(o => o.DeviceId == b.DeviceId) : r).ToList();
        Assert.Equal(new DocOpen.Rejected("signature"), DocSealer.Open(doc with { Recipients = swapped }, chain, b));
        Assert.Throws<ArgumentException>(() => Seal(signer: new TestDevice()));
        Assert.Equal(new DocOpen.Rejected("signature"), DocSealer.Open(doc with { Signature = Seal(signer: b).Signature }, chain, b));
        Assert.Equal(new DocOpen.Rejected("role"), DocSealer.Open(Seal(signer: pc), chain, a));
        Assert.IsType<DocOpen.Ok>(DocSealer.Open(Seal(signer: pc, kind: DocKind.Command), chain, a));
        var otherHousehold = new[] { MemberChain.Genesis(b, b.Keys) }.Verified();
        Assert.Equal(new DocOpen.Rejected("household"), DocSealer.Open(doc, otherHousehold, b));
    }

    [Fact]
    public void HeadOfTheWritersListIsChecked()
    {
        var doc = Seal();
        Assert.Equal(new DocOpen.NeedChain(chain.HeadSeq), DocSealer.Open(doc, records.Verified(), b));
        var branch = new List<ChainRecord>([.. records, MemberChain.Add(b, records.Verified(), new TestDevice().Keys)]).Verified();
        Assert.IsType<DocOpen.Fork>(DocSealer.Open(doc, branch, b));
        var afterRemove = new List<ChainRecord>([.. withPc, MemberChain.Remove(b, chain, pc.DeviceId)]).Verified();
        Assert.True(Assert.IsType<DocOpen.Ok>(DocSealer.Open(doc, afterRemove, b)).StaleRecipients);
        var removedWriterChain = new List<ChainRecord>([.. withPc, MemberChain.Remove(a, chain, b.DeviceId)]).Verified();
        Assert.Equal(new DocOpen.Rejected("writer_not_member"), DocSealer.Open(Seal(signer: b), removedWriterChain, a));
        Assert.DoesNotContain(Seal(onChain: afterRemove).Recipients, r => r.DeviceId == pc.DeviceId);
    }
}

public class PlaybackArbiterTests
{
    private static PlaybackArbiter.Result Decide(PlaybackArbiter.Session mine, PlaybackArbiter.Evidence evidence, long? myOrder = null, long? otherOrder = null) =>
        PlaybackArbiter.Decide("A", mine, evidence, myOrder, otherOrder);

    [Fact]
    public void LaterSessionWinsRegardlessOfClaimOrders()
    {
        var a = new PlaybackArbiter.Session(9, 10);
        Assert.Equal(new PlaybackArbiter.Result(PlaybackArbiter.Decision.Stop, PlaybackArbiter.Reason.OtherStartedAfterMine), Decide(a, new PlaybackArbiter.Evidence.Yield("B", 12), 9, 1));
        Assert.Equal(PlaybackArbiter.Decision.Stop, Decide(a, new PlaybackArbiter.Evidence.PlayingCursor("B", 10, 13)).Decision);
        Assert.Equal(new PlaybackArbiter.Result(PlaybackArbiter.Decision.Keep, PlaybackArbiter.Reason.MineStartedAfterOther), Decide(a, new PlaybackArbiter.Evidence.PlayingCursor("B", 4, 5), 1, 9));
    }

    [Fact]
    public void ConcurrentStartsAreSettledByClaimOrderAndDeferredUntilKnown()
    {
        var a = new PlaybackArbiter.Session(20, 21);
        var bCursor = new PlaybackArbiter.Evidence.PlayingCursor("B", 20, 22);
        Assert.Equal(new PlaybackArbiter.Result(PlaybackArbiter.Decision.Stop, PlaybackArbiter.Reason.Concurrent), Decide(a, bCursor, 5, 6));
        Assert.Equal(new PlaybackArbiter.Result(PlaybackArbiter.Decision.Keep, PlaybackArbiter.Reason.Concurrent), Decide(a, bCursor, 6, 5));
        Assert.Equal(PlaybackArbiter.Decision.Defer, Decide(a, bCursor, null, 6).Decision);
        Assert.Equal(PlaybackArbiter.Decision.Defer, Decide(a, bCursor, 5, null).Decision);
        var offlineStart = new PlaybackArbiter.Session(20, null);
        Assert.Equal(PlaybackArbiter.Decision.Stop, Decide(offlineStart, new PlaybackArbiter.Evidence.Yield("B", 20), 3, 4).Decision);
        Assert.Equal(PlaybackArbiter.Decision.Keep, Decide(offlineStart, new PlaybackArbiter.Evidence.Yield("B", 20), 4, 3).Decision);
        Assert.Equal(PlaybackArbiter.Reason.Self, PlaybackArbiter.Decide("B", new PlaybackArbiter.Session(30, 31), new PlaybackArbiter.Evidence.PlayingCursor("B", 30, 31), null, null).Reason);
    }
}
