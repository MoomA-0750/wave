using System.Text.Json;
using Wave.Core.Connect;

namespace Wave.Core.Tests.Connect;

public class PairingTests
{
    private long now;
    private readonly SoftwareDevice idA = SoftwareDevice.Generate();
    private readonly SoftwareDevice idB = SoftwareDevice.Generate();

    private Pairing.Responder Responder(string account = "account-1", string? extension = null) => new(idA, "Pixel 3", account, () => now, extension);

    private static Pairing.Initiator Initiator(Pairing.Offer offer, IDeviceSigner identity, string account = "account-1", string? extension = null) => new(offer, identity, "Windows", account, extension);

    [Fact]
    public void HonestPairingAgreesOnTheCodeAndDirectionalKeys()
    {
        var a = Responder(extension: """{"householdId":"h"}""");
        var offer = Pairing.DecodeOffer(Pairing.EncodeOffer(a.Offer))!;
        var b = Initiator(offer, idB, extension: """{"deviceId":"d"}""");
        var confirmed = b.OnConfirm(a.OnHello(b.Hello()).GetOrThrow()).GetOrThrow();
        Assert.Matches("^[0-9]{6}$", confirmed.Code);
        var onA = a.OnCodeEntered(confirmed.Code).GetOrThrow();
        Assert.Equal(onA.SendKey, confirmed.Pair.ReceiveKey);
        Assert.Equal(onA.ReceiveKey, confirmed.Pair.SendKey);
        Assert.Equal(onA.AccountKey, confirmed.Pair.AccountKey);
        Assert.Equal(idB.IdentityPub, onA.PeerPublicKey);
        Assert.Equal(idA.IdentityPub, confirmed.Pair.PeerPublicKey);
        Assert.Equal("""{"deviceId":"d"}""", onA.PeerExtension);
        Assert.Equal("""{"householdId":"h"}""", confirmed.Pair.PeerExtension);
    }

    [Fact]
    public void RejectsWrongCodesAccountsExpiryAndReuse()
    {
        var a = Responder();
        a.OnHello(Initiator(a.Offer, idB).Hello()).GetOrThrow();
        Assert.Equal(Pairing.Failure.AlreadyUsed, a.OnHello(Initiator(a.Offer, idB).Hello()).Error);
        Assert.Equal(Pairing.Failure.WrongCode, a.OnCodeEntered("x").Error);
        Assert.Equal(Pairing.Failure.WrongCode, a.OnCodeEntered("x").Error);
        Assert.Equal(Pairing.Failure.TooManyAttempts, a.OnCodeEntered("x").Error);

        var other = Responder(account: "account-1");
        Assert.Equal(Pairing.Failure.AccountMismatch, other.OnHello(Initiator(other.Offer, idB, account: "account-2").Hello()).Error);

        var late = Responder();
        now += Pairing.LifetimeMs + 1;
        Assert.Equal(Pairing.Failure.Expired, late.OnHello(Initiator(late.Offer, idB).Hello()).Error);

        var self = Responder();
        Assert.Equal(Pairing.Failure.Malformed, self.OnHello(Initiator(self.Offer, idA).Hello()).Error);
    }

    [Fact]
    public void RelayCannotSwapKeysOrForgeSignatures()
    {
        var a = Responder();
        var hello = Initiator(a.Offer, idB).Hello();
        using var swap = WaveCrypto.GenerateEphemeralKeyPair();
        Assert.Equal(Pairing.Failure.Malformed, a.OnHello(hello with { EB = B64.Encode(WaveCrypto.EncodePublicKey(swap)) }).Error);
        // pkB に対応しない鍵で署名した Hello
        var forger = new ForgedSigner(idB.IdentityPub, SoftwareDevice.Generate());
        Assert.Equal(Pairing.Failure.BadSignature, a.OnHello(Initiator(a.Offer, forger).Hello()).Error);
    }

    [Fact]
    public void OffersAndNamesAreValidated()
    {
        Assert.Null(Pairing.DecodeOffer("https://example.com"));
        Assert.Null(Pairing.DecodeOffer("wave-pair:!!!"));
        var a = Responder();
        Assert.Null(Pairing.DecodeOffer(Pairing.EncodeOffer(a.Offer with { Token = "AAAA" })));
        Assert.Null(Pairing.DecodeOffer(Pairing.EncodeOffer(a.Offer with { V = 2 })));
        // QR の形は Kotlin と同じ JSON（{"v":1,"pkA":...,"eA":...,"token":...}）
        var json = JsonDocument.Parse(B64.Decode(Pairing.EncodeOffer(a.Offer)["wave-pair:".Length..])!).RootElement;
        Assert.Equal(["v", "pkA", "eA", "token"], json.EnumerateObject().Select(p => p.Name));
        Assert.Equal("abc", Pairing.SanitizeName("a‮b\u0000c"));
        Assert.Equal("端末", Pairing.SanitizeName("\u0007"));
    }

    private sealed class ForgedSigner(byte[] claimedPub, SoftwareDevice real) : IDeviceSigner
    {
        public string DeviceId => WaveCrypto.DeviceId(claimedPub);
        public byte[] IdentityPub => claimedPub;
        public byte[] Sign(string label, byte[] message) => real.Sign(label, message);
    }
}

public class HandoffCodecTests
{
    private static HandoffSnapshot Sample() => InteropTests.SampleSnapshot();

    [Fact]
    public void ValidatesShapeAndClampsTimes()
    {
        var text = HandoffCodec.Encode(Sample() with { PositionMs = 999_999_999 });
        var ok = Assert.IsType<HandoffDecodeResult.Ok>(HandoffCodec.Decode(text, Sample().HandoffId));
        Assert.Equal(261_000, ok.Snapshot.PositionMs);
        Assert.Equal(new HandoffDecodeResult.Rejected("handoff_id"), HandoffCodec.Decode(text, "00000000-0000-4000-8000-000000000009"));
        Assert.Equal(new HandoffDecodeResult.UnsupportedVersion(2), HandoffCodec.Decode(HandoffCodec.Encode(Sample() with { SchemaVersion = 2 }), Sample().HandoffId));
        Assert.Equal(new HandoffDecodeResult.UnsupportedVersion(null), HandoffCodec.Decode("{}", Sample().HandoffId));
        Assert.Equal(new HandoffDecodeResult.Rejected("unreadable"), HandoffCodec.Decode("[", Sample().HandoffId));
        Assert.Equal(new HandoffDecodeResult.Rejected("unreadable"), HandoffCodec.Decode("""{"schemaVersion":1}""", Sample().HandoffId));
        var badThumb = Sample() with { Items = [Sample().Items[0] with { ThumbnailUrl = "https://evil.example/x.jpg" }, Sample().Items[1]] };
        Assert.Null(Assert.IsType<HandoffDecodeResult.Ok>(HandoffCodec.Decode(HandoffCodec.Encode(badThumb), Sample().HandoffId)).Snapshot.Items[0].ThumbnailUrl);
        var badVideo = Sample() with { Items = [Sample().Items[0] with { VideoId = "short" }] };
        Assert.Equal(new HandoffDecodeResult.Rejected("video_id"), HandoffCodec.Decode(HandoffCodec.Encode(badVideo), Sample().HandoffId));
    }

    [Fact]
    public void EscapesLikeKotlinx()
    {
        // 必要な文字だけを逃がす（制御文字は短い形か \u00xx の小文字）
        Assert.Equal("""{"name":"a\"b\\c\n\u0001日本🎵<>&'"}""", ConnectJson.Encode(new ProfileBody { Name = "a\"b\\c\n\u0001日本🎵<>&'" }));
    }

    [Fact]
    public void WindowKeepsTwentyBeforeAndThreeHundredAfter()
    {
        var items = Enumerable.Range(0, 400).ToList();
        var (window, index, dropped) = HandoffCodec.Window(items, 50);
        Assert.Equal(30, window[0]);
        Assert.Equal(20, index);
        Assert.Equal(321, window.Count);
        Assert.True(dropped);
    }
}
