using System.Numerics;
using System.Security.Cryptography;
using Wave.Core.Connect;

namespace Wave.Core.Tests.Connect;

internal static class ConnectTestSupport
{
    public static byte[] Hex(string s) => Convert.FromHexString(s.Replace(" ", ""));

    public static VerifiedChain Verified(this IReadOnlyList<ChainRecord> records) => Assert.IsType<ChainVerification.Ok>(MemberChain.Verify(records)).Chain;

    /// <summary>テスト用の端末（役割を持つ）。</summary>
    public sealed class TestDevice(Role role = Role.Player) : IDeviceSigner, IKxOpener
    {
        private readonly SoftwareDevice inner = SoftwareDevice.Generate();
        public Role Role { get; } = role;
        public string DeviceId => inner.DeviceId;
        public byte[] IdentityPub => inner.IdentityPub;
        public EcPublicKey KxPub => inner.KxPub;
        public byte[] Sign(string label, byte[] message) => inner.Sign(label, message);
        public byte[] Dh(EcPublicKey peer) => inner.Dh(peer);
        private MemberKeys? keys;
        public MemberKeys Keys => keys ??= inner.MemberKeys(Role);
    }
}

public class ConnectCryptoTests
{
    private static byte[] Hex(string s) => ConnectTestSupport.Hex(s);

    [Fact]
    public void HkdfMatchesRfc5869Case1()
    {
        var ikm = Hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        var salt = Hex("000102030405060708090a0b0c");
        var info = Hex("f0f1f2f3f4f5f6f7f8f9");
        var prk = Hkdf.Extract(salt, ikm);
        Assert.Equal(Hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk);
        Assert.Equal(Hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), Hkdf.Expand(prk, info, 42));
    }

    [Fact]
    public void Base64UrlIsUnpaddedAndStrict()
    {
        Assert.Equal("-_8", B64.Encode([0xfb, 0xff]));
        Assert.Equal(new byte[] { 0xfb, 0xff }, B64.Decode("-_8"));
        Assert.Equal(new byte[] { 0xfb, 0xff }, B64.Decode("-_8="));
        Assert.Null(B64.Decode("+/8"));
        Assert.Null(B64.Decode("a b"));
        Assert.Null(B64.Decode("A"));
        Assert.Empty(B64.Decode("")!);
    }

    [Fact]
    public void SignaturesAreDerAndLabeled()
    {
        using var key = WaveCrypto.GenerateSigningKey();
        var pub = WaveCrypto.PublicKeyOf(key);
        var signature = WaveCrypto.Sign(key, "label", [1, 2, 3]);
        // DER の SEQUENCE（JCA の SHA256withECDSA と同じ形）
        Assert.Equal(0x30, signature[0]);
        Assert.True(WaveCrypto.Verify(pub, "label", [1, 2, 3], signature));
        Assert.False(WaveCrypto.Verify(pub, "other", [1, 2, 3], signature));
        Assert.False(WaveCrypto.Verify(pub, "label", [1, 2, 4], signature));
    }

    [Fact]
    public void EcdhIsTheRawXCoordinate()
    {
        using var a = WaveCrypto.GenerateEphemeralKeyPair();
        using var b = WaveCrypto.GenerateEphemeralKeyPair();
        var ab = WaveCrypto.Ecdh(a, WaveCrypto.PublicKeyOf(b));
        Assert.Equal(32, ab.Length);
        Assert.Equal(ab, WaveCrypto.Ecdh(b, WaveCrypto.PublicKeyOf(a)));
        // 共有点の x 座標そのもの（a の秘密 × b の公開点）
        var shared = MultiplyX(a.ExportParameters(true).D!, WaveCrypto.PublicKeyOf(b));
        Assert.Equal(shared, ab);
    }

    /// <summary>素朴な楕円曲線の掛け算で x 座標を求める（DeriveRawSecretAgreement がハッシュしていないことの確認）。</summary>
    private static byte[] MultiplyX(byte[] scalar, EcPublicKey point)
    {
        var p = BigInteger.Parse("0ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", System.Globalization.NumberStyles.HexNumber);
        var a = p - 3;
        BigInteger Mod(BigInteger v) => (v % p + p) % p;
        BigInteger Inv(BigInteger v) => BigInteger.ModPow(Mod(v), p - 2, p);
        (BigInteger, BigInteger)? Add((BigInteger X, BigInteger Y)? s, (BigInteger X, BigInteger Y)? t)
        {
            if (s is null) return t;
            if (t is null) return s;
            var (x1, y1) = s.Value;
            var (x2, y2) = t.Value;
            BigInteger l;
            if (x1 == x2)
            {
                if (Mod(y1 + y2) == 0) return null;
                l = Mod(3 * x1 * x1 + a) * Inv(2 * y1);
            }
            else
            {
                l = Mod(y2 - y1) * Inv(x2 - x1);
            }
            l = Mod(l);
            var x3 = Mod(l * l - x1 - x2);
            return (x3, Mod(l * (x1 - x3) - y1));
        }
        var k = new BigInteger(scalar, isUnsigned: true, isBigEndian: true);
        (BigInteger, BigInteger)? result = null;
        (BigInteger, BigInteger)? addend = (new BigInteger(point.X, true, true), new BigInteger(point.Y, true, true));
        while (k > 0)
        {
            if (!k.IsEven) result = Add(result, addend);
            addend = Add(addend, addend);
            k >>= 1;
        }
        var bytes = result!.Value.Item1.ToByteArray(isUnsigned: true, isBigEndian: true);
        return [.. new byte[32 - bytes.Length], .. bytes];
    }

    [Fact]
    public void PublicKeysMustBeCanonicalP256()
    {
        using var key = WaveCrypto.GenerateSigningKey();
        var spki = key.ExportSubjectPublicKeyInfo();
        Assert.NotNull(WaveCrypto.DecodePublicKey(spki));
        // P-384 は受けない
        using var p384 = ECDsa.Create(ECCurve.NamedCurves.nistP384);
        Assert.Null(WaveCrypto.DecodePublicKey(p384.ExportSubjectPublicKeyInfo()));
        // 末尾にごみ
        Assert.Null(WaveCrypto.DecodePublicKey([.. spki, 0]));
        // 曲線外の点
        var bad = spki.ToArray();
        bad[^1] ^= 1;
        Assert.Null(WaveCrypto.DecodePublicKey(bad));
        Assert.Null(WaveCrypto.DecodePublicKey(new byte[201]));
    }

    [Fact]
    public void AesGcmRoundTripsAndRejectsTampering()
    {
        var key = WaveCrypto.RandomBytes(32);
        var (nonce, ciphertext) = WaveCrypto.Seal(key, [9], [1, 2, 3]);
        Assert.Equal(3 + 16, ciphertext.Length);
        Assert.Equal(new byte[] { 1, 2, 3 }, WaveCrypto.Open(key, nonce, [9], ciphertext));
        Assert.Null(WaveCrypto.Open(key, nonce, [8], ciphertext));
        Assert.Null(WaveCrypto.Open(key, nonce[..11], [9], ciphertext));
    }

    [Fact]
    public void LengthPrefixedIsBigEndian()
    {
        Assert.Equal(Hex("00000002 0102 00000000"), WaveCrypto.LengthPrefixed([1, 2], []));
    }
}

/// <summary>RFC 9180 付録 A.3（DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM）の base モードのテストベクタ。</summary>
public class HpkeTests
{
    private static byte[] Hex(string s) => ConnectTestSupport.Hex(s);

    private readonly byte[] info = Hex("4f6465206f6e2061204772656369616e2055726e");
    private readonly byte[] ikmE = Hex("4270e54ffd08d79d5928020af4686d8f6b7d35dbe470265f1f5aa22816ce860e");
    private readonly byte[] pkEm = Hex("04a92719c6195d5085104f469a8b9814d5838ff72b60501e2c4466e5e67b325ac98536d7b61a1af4b78e5b7f951c0900be863c403ce65c9bfcb9382657222d18c4");
    private readonly byte[] skEm = Hex("4995788ef4b9d6132b249ce59a77281493eb39af373d236a1fe415cb0c2d7beb");
    private readonly byte[] ikmR = Hex("668b37171f1072f3cf12ea8a236a45df23fc13b82af3609ad1e354f6ef817550");
    private readonly byte[] pkRm = Hex("04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0");
    private readonly byte[] skRm = Hex("f3ce7fdae57e1a310d87f1ebbde6f328be0a99cdbcadf4d6589cf29de4b8ffd2");
    private readonly byte[] sharedSecret = Hex("c0d26aeab536609a572b07695d933b589dcf363ff9d93c93adea537aeabb8cb8");
    private readonly byte[] key = Hex("868c066ef58aae6dc589b6cfdd18f97e");
    private readonly byte[] baseNonce = Hex("4e0bc5018beba4bf004cca59");
    private readonly byte[] exporterSecret = Hex("14ad94af484a7ad3ef40e9f3be99ecc6fa9036df9d4920548424df127ee0d99f");
    private readonly byte[] pt = Hex("4265617574792069732074727574682c20747275746820626561757479");

    private readonly (long Seq, string Aad, string Ct)[] encryptions =
    [
        (0L, "436f756e742d30", "5ad590bb8baa577f8619db35a36311226a896e7342a6d836d8b7bcd2f20b6c7f9076ac232e3ab2523f39513434"),
        (1L, "436f756e742d31", "fa6f037b47fc21826b610172ca9637e82d6e5801eb31cbd3748271affd4ecb06646e0329cbdf3c3cd655b28e82"),
        (2L, "436f756e742d32", "895cabfac50ce6c6eb02ffe6c048bf53b7f7be9a91fc559402cbc5b8dcaeb52b2ccc93e466c28fb55fed7a7fec"),
        (4L, "436f756e742d34", "8787491ee8df99bc99a246c4b3216d3d57ab5076e18fa27133f520703bc70ec999dd36ce042e44f0c3169a6a8f"),
        (255L, "436f756e742d323535", "2ad71c85bf3f45c6eca301426289854b31448bcf8a8ccb1deef3ebd87f60848aa53c538c30a4dac71d619ee2cd"),
        (256L, "436f756e742d323536", "10f179686aa2caec1758c8e554513f16472bd0a11e2a907dde0b212cbe87d74f367f8ffe5e41cd3e9962a6afb2"),
    ];

    [Fact]
    public void DeriveKeyPairMatchesVectors()
    {
        Assert.Equal(new BigInteger(skEm, true, true), Hpke.DeriveSecretScalar(ikmE));
        Assert.Equal(new BigInteger(skRm, true, true), Hpke.DeriveSecretScalar(ikmR));
    }

    [Fact]
    public void EncapAndKeyScheduleMatchVectors()
    {
        var pkE = Hpke.DeserializePublicKey(pkEm)!;
        var pkR = Hpke.DeserializePublicKey(pkRm)!;
        Assert.Equal(pkEm, Hpke.SerializePublicKey(pkE));
        using var ephemeral = Hpke.KeyPairOf(skEm, pkE);
        var (shared, enc) = Hpke.EncapForTest(pkR, ephemeral);
        Assert.Equal(pkEm, enc);
        Assert.Equal(sharedSecret, shared);
        var context = Hpke.KeySchedule(shared, info);
        Assert.Equal(key, context.Key);
        Assert.Equal(baseNonce, context.BaseNonce);
        Assert.Equal(exporterSecret, context.ExporterSecret);
        foreach (var (seq, aad, ct) in encryptions)
        {
            Assert.Equal(Hex(ct), context.Seal(seq, Hex(aad), pt));
            Assert.Equal(pt, context.Open(seq, Hex(aad), Hex(ct)));
        }
    }

    [Fact]
    public void OpenWithRecipientDhRoundTripsAndRejectsTampering()
    {
        var pkR = Hpke.DeserializePublicKey(pkRm)!;
        using var recipient = Hpke.KeyPairOf(skRm, pkR);
        Func<EcPublicKey, byte[]> dh = peer => WaveCrypto.Ecdh(recipient, peer);
        var sealedBox = Hpke.Seal(pkR, info, [1], pt);
        Assert.Equal(pt, Hpke.Open(sealedBox.Enc, pkR, dh, info, [1], sealedBox.Ciphertext));
        Assert.Null(Hpke.Open(sealedBox.Enc, pkR, dh, info, [2], sealedBox.Ciphertext));
        Assert.Null(Hpke.Open(sealedBox.Enc, pkR, dh, [0], [1], sealedBox.Ciphertext));
        var flipped = sealedBox.Ciphertext.ToArray();
        flipped[0] ^= 1;
        Assert.Null(Hpke.Open(sealedBox.Enc, pkR, dh, info, [1], flipped));
        using var other = WaveCrypto.GenerateEphemeralKeyPair();
        Assert.Null(Hpke.Open(Hpke.SerializePublicKey(WaveCrypto.PublicKeyOf(other)), pkR, dh, info, [1], sealedBox.Ciphertext));
        var otherPub = WaveCrypto.PublicKeyOf(other);
        Assert.Null(Hpke.Open(sealedBox.Enc, otherPub, p => WaveCrypto.Ecdh(other, p), info, [1], sealedBox.Ciphertext));
    }

    [Fact]
    public void RejectsMalformedEncodedPoints()
    {
        Assert.Null(Hpke.DeserializePublicKey(pkEm[..64]));
        Assert.Null(Hpke.DeserializePublicKey([0x02, .. pkEm[1..33]]));
        Assert.Null(Hpke.DeserializePublicKey([0x00]));
        Assert.Null(Hpke.DeserializePublicKey([0x04, .. new byte[64]]));
        var offCurve = pkEm.ToArray();
        offCurve[64] ^= 1;
        Assert.Null(Hpke.DeserializePublicKey(offCurve));
        var prefixed = pkEm.ToArray();
        prefixed[0] = 0x03;
        Assert.Null(Hpke.DeserializePublicKey(prefixed));
    }
}
