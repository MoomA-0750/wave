using System.Numerics;
using System.Security.Cryptography;

namespace Wave.Core.Connect;

/// <summary>
/// RFC 9180 HPKE の base モード、<c>DHKEM(P-256, HKDF-SHA256)</c>・<c>HKDF-SHA256</c>・<c>AES-128-GCM</c>（Android 版の Hpke と同じ）。
/// 付録 A.3 のテストベクタで確かめる。書き込みごとの内容鍵を、家の端末それぞれの受け取りの鍵（kx）へ包むのに使う。
/// 受け手の DH は関数で受ける。公開鍵の送る形は非圧縮 SEC1（65バイト）。
/// </summary>
public static class Hpke
{
    private const int KemId = 0x0010;
    private const int KdfId = 0x0001;
    private const int AeadId = 0x0001;
    private const byte ModeBase = 0x00;
    private const int NSecret = 32;
    private const int NK = 16;
    private const int NN = 12;
    public const int NEnc = 65;

    private static readonly byte[] VersionLabel = "HPKE-v1"u8.ToArray();
    private static readonly byte[] KemSuite = [.. "KEM"u8, .. I2osp(KemId, 2)];
    private static readonly byte[] HpkeSuite = [.. "HPKE"u8, .. I2osp(KemId, 2), .. I2osp(KdfId, 2), .. I2osp(AeadId, 2)];
    private static readonly BigInteger N = BigInteger.Parse("0ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", System.Globalization.NumberStyles.HexNumber);

    /// <summary>封じた結果。Enc は使い捨ての鍵の公開鍵（SEC1）。</summary>
    public sealed record Sealed(byte[] Enc, byte[] Ciphertext);

    /// <summary>受け手の公開鍵へ、1回だけの暗号化（シーケンス番号 0）。</summary>
    public static Sealed Seal(EcPublicKey recipient, byte[] info, byte[] aad, byte[] plaintext)
    {
        using var ephemeral = WaveCrypto.GenerateEphemeralKeyPair();
        return Seal(recipient, info, aad, plaintext, ephemeral);
    }

    /// <summary>テスト用: 使い捨ての鍵を外から与える。</summary>
    internal static Sealed Seal(EcPublicKey recipient, byte[] info, byte[] aad, byte[] plaintext, ECDiffieHellman ephemeral)
    {
        var (sharedSecret, enc) = Encap(recipient, ephemeral);
        return new Sealed(enc, KeySchedule(sharedSecret, info).Seal(0, aad, plaintext));
    }

    /// <summary>
    /// 開く。認証に失敗したら null。
    /// </summary>
    /// <param name="recipientPublic">受け手自身の公開鍵（kem_context に入る）</param>
    /// <param name="dh">受け手の秘密鍵で、渡された公開鍵との ECDH の x 座標（32バイト）を返す</param>
    public static byte[]? Open(byte[] enc, EcPublicKey recipientPublic, Func<EcPublicKey, byte[]> dh, byte[] info, byte[] aad, byte[] ciphertext)
    {
        var sharedSecret = Decap(enc, recipientPublic, dh);
        return sharedSecret is null ? null : KeySchedule(sharedSecret, info).Open(0, aad, ciphertext);
    }

    /// <summary>非圧縮 SEC1（0x04 ‖ X ‖ Y）。</summary>
    public static byte[] SerializePublicKey(EcPublicKey key) => [0x04, .. key.X, .. key.Y];

    /// <summary>非圧縮 SEC1 を読む。長さ・形・曲線上の点を確かめる。</summary>
    public static EcPublicKey? DeserializePublicKey(byte[] encoded)
    {
        if (encoded.Length != NEnc || encoded[0] != 0x04) return null;
        var x = encoded[1..33];
        var y = encoded[33..65];
        if (!WaveCrypto.OnCurve(x, y)) return null;
        try
        {
            using var key = ECDiffieHellman.Create(new ECParameters { Curve = ECCurve.NamedCurves.nistP256, Q = new ECPoint { X = x, Y = y } });
            return WaveCrypto.DecodePublicKey(key.ExportSubjectPublicKeyInfo());
        }
        catch (CryptographicException)
        {
            return null;
        }
    }

    /// <summary>テスト用: 付録 A.3 の ikm から秘密鍵のスカラーを導く（DeriveKeyPair、P-256 のビットマスクは 0xff）。</summary>
    internal static BigInteger DeriveSecretScalar(byte[] ikm)
    {
        var prk = LabeledExtract(KemSuite, [], "dkp_prk", ikm);
        for (var counter = 0; counter <= 255; counter++)
        {
            var bytes = LabeledExpand(KemSuite, prk, "candidate", [(byte)counter], NSecret);
            var candidate = new BigInteger(bytes, isUnsigned: true, isBigEndian: true);
            if (candidate.Sign > 0 && candidate < N) return candidate;
        }
        throw new CryptographicException("DeriveKeyPair failed");
    }

    /// <summary>テスト用: 秘密のスカラーと公開鍵から ECDH の鍵の組を作る。</summary>
    internal static ECDiffieHellman KeyPairOf(byte[] secret, EcPublicKey publicKey) =>
        ECDiffieHellman.Create(new ECParameters { Curve = ECCurve.NamedCurves.nistP256, D = secret, Q = new ECPoint { X = publicKey.X, Y = publicKey.Y } });

    private static (byte[] SharedSecret, byte[] Enc) Encap(EcPublicKey recipient, ECDiffieHellman ephemeral)
    {
        var validated = WaveCrypto.DecodePublicKey(recipient.Spki) ?? throw new ArgumentException("invalid recipient key");
        var dh = WaveCrypto.Ecdh(ephemeral, validated);
        var enc = SerializePublicKey(WaveCrypto.PublicKeyOf(ephemeral));
        return (ExtractAndExpand(dh, [.. enc, .. SerializePublicKey(validated)]), enc);
    }

    /// <summary>テスト用: 付録 A.3 の shared_secret の確認。</summary>
    internal static (byte[] SharedSecret, byte[] Enc) EncapForTest(EcPublicKey recipient, ECDiffieHellman ephemeral) => Encap(recipient, ephemeral);

    private static byte[]? Decap(byte[] enc, EcPublicKey recipientPublic, Func<EcPublicKey, byte[]> dh)
    {
        var ephemeral = DeserializePublicKey(enc);
        if (ephemeral is null) return null;
        byte[] shared;
        try
        {
            shared = dh(ephemeral);
        }
        catch (Exception)
        {
            return null;
        }
        if (shared is not { Length: NSecret }) return null;
        return ExtractAndExpand(shared, [.. enc, .. SerializePublicKey(recipientPublic)]);
    }

    private static byte[] ExtractAndExpand(byte[] dh, byte[] kemContext)
    {
        var prk = LabeledExtract(KemSuite, [], "eae_prk", dh);
        return LabeledExpand(KemSuite, prk, "shared_secret", kemContext, NSecret);
    }

    internal sealed class Context(byte[] key, byte[] baseNonce, byte[] exporterSecret)
    {
        public byte[] Key { get; } = key;
        public byte[] BaseNonce { get; } = baseNonce;
        public byte[] ExporterSecret { get; } = exporterSecret;

        public byte[] Seal(long sequence, byte[] aad, byte[] plaintext) => WaveCrypto.AesGcmSeal(Key, NonceFor(sequence), aad, plaintext);

        public byte[]? Open(long sequence, byte[] aad, byte[] ciphertext) => WaveCrypto.AesGcmOpen(Key, NonceFor(sequence), aad, ciphertext);

        private byte[] NonceFor(long sequence)
        {
            var nonce = BaseNonce.ToArray();
            var seq = I2osp(sequence, NN);
            for (var i = 0; i < nonce.Length; i++) nonce[i] ^= seq[i];
            return nonce;
        }
    }

    internal static Context KeySchedule(byte[] sharedSecret, byte[] info)
    {
        var pskIdHash = LabeledExtract(HpkeSuite, [], "psk_id_hash", []);
        var infoHash = LabeledExtract(HpkeSuite, [], "info_hash", info);
        byte[] keyScheduleContext = [ModeBase, .. pskIdHash, .. infoHash];
        var secret = LabeledExtract(HpkeSuite, sharedSecret, "secret", []);
        return new Context(
            LabeledExpand(HpkeSuite, secret, "key", keyScheduleContext, NK),
            LabeledExpand(HpkeSuite, secret, "base_nonce", keyScheduleContext, NN),
            LabeledExpand(HpkeSuite, secret, "exp", keyScheduleContext, NSecret));
    }

    private static byte[] LabeledExtract(byte[] suite, byte[] salt, string label, byte[] ikm) =>
        Hkdf.Extract(salt, [.. VersionLabel, .. suite, .. WaveCrypto.Utf8(label), .. ikm]);

    private static byte[] LabeledExpand(byte[] suite, byte[] prk, string label, byte[] info, int length) =>
        Hkdf.Expand(prk, [.. I2osp(length, 2), .. VersionLabel, .. suite, .. WaveCrypto.Utf8(label), .. info], length);

    private static byte[] I2osp(long value, int length)
    {
        var output = new byte[length];
        for (var i = 0; i < length; i++)
        {
            var shift = length - 1 - i;
            output[i] = shift >= 8 ? (byte)0 : (byte)((ulong)value >> (8 * shift));
        }
        return output;
    }
}
