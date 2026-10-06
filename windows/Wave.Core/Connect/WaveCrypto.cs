using System.Buffers.Binary;
using System.Numerics;
using System.Security.Cryptography;
using System.Text;

namespace Wave.Core.Connect;

/// <summary>base64url（パディングなし）。読むときは Java の URL デコーダと同じく、パディングの有無どちらも受ける。</summary>
public static class B64
{
    public static string Encode(ReadOnlySpan<byte> data) => Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    /// <summary>読めなければ null（URL 用でない文字・空白・長さの崩れたもの）。</summary>
    public static byte[]? Decode(string? text)
    {
        if (text is null) return null;
        var body = text;
        var padding = 0;
        while (body.EndsWith('=') && padding < 3)
        {
            body = body[..^1];
            padding++;
        }
        if (padding > 0 && (padding > 2 || text.Length % 4 != 0)) return null;
        if (body.Length % 4 == 1) return null;
        foreach (var c in body)
        {
            if (!(c is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '-' or '_')) return null;
        }
        var standard = body.Replace('-', '+').Replace('_', '/');
        standard += new string('=', (4 - standard.Length % 4) % 4);
        try
        {
            return Convert.FromBase64String(standard);
        }
        catch (FormatException)
        {
            return null;
        }
    }
}

/// <summary>
/// 検証済みの P-256 の公開鍵。送る形（X.509 SubjectPublicKeyInfo、正規形）と座標を持つ。
/// 作るのは <see cref="WaveCrypto.DecodePublicKey"/> と <see cref="Hpke.DeserializePublicKey"/> だけ（曲線上の点を確かめたもの）。
/// </summary>
public sealed class EcPublicKey
{
    internal EcPublicKey(byte[] spki, byte[] x, byte[] y)
    {
        Spki = spki;
        X = x;
        Y = y;
    }

    /// <summary>送る形（SPKI）。</summary>
    public byte[] Spki { get; }

    public byte[] X { get; }
    public byte[] Y { get; }

    internal ECParameters Parameters => new() { Curve = ECCurve.NamedCurves.nistP256, Q = new ECPoint { X = X, Y = Y } };

    internal ECDsa CreateECDsa() => ECDsa.Create(Parameters);

    internal ECDiffieHellman CreateEcdh() => ECDiffieHellman.Create(Parameters);
}

/// <summary>
/// Phase 6 の暗号の部品（Android 版の WaveCrypto と同じ形）。.NET の ECDH・ECDSA・AES-GCM・HMAC だけを使い、暗号そのものは書かない。
/// 署名は JCA と同じ DER 形式、ECDH の共有秘密は x 座標そのもの（ハッシュしない）。曲線は P-256。
/// </summary>
public static class WaveCrypto
{
    private const string P256Oid = "1.2.840.10045.3.1.7";

    private static readonly BigInteger P = Hex("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff");
    private static readonly BigInteger A = Hex("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc");
    private static readonly BigInteger B = Hex("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b");

    private static BigInteger Hex(string hex) => BigInteger.Parse("0" + hex, System.Globalization.NumberStyles.HexNumber);

    /// <summary>使い捨ての鍵（ペアリング・HPKE）。メモリだけに置く。</summary>
    public static ECDiffieHellman GenerateEphemeralKeyPair() => ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);

    /// <summary>身元の署名鍵（ソフトウェアで持つ場合）。</summary>
    public static ECDsa GenerateSigningKey() => ECDsa.Create(ECCurve.NamedCurves.nistP256);

    /// <summary>公開鍵の送る形（X.509 SubjectPublicKeyInfo）。</summary>
    public static byte[] EncodePublicKey(AsymmetricAlgorithm key) => key.ExportSubjectPublicKeyInfo();

    /// <summary>鍵の組の公開鍵を、検証済みの形で取り出す。</summary>
    public static EcPublicKey PublicKeyOf(AsymmetricAlgorithm key) => DecodePublicKey(key.ExportSubjectPublicKeyInfo()) ?? throw new CryptographicException("P-256 の鍵ではありません");

    /// <summary>受け取った公開鍵を読む。P-256 で、正規形で、曲線上の点のときだけ（不正な曲線の点を使った攻撃を防ぐ）。</summary>
    public static EcPublicKey? DecodePublicKey(byte[] encoded)
    {
        if (encoded.Length > 200) return null;
        try
        {
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(encoded, out var read);
            if (read != encoded.Length) return null;
            // 送る形を正規化したものだけ（圧縮点など、読み直すと別のバイト列になる形を受けると、端末と中継で端末 ID が食い違う）
            if (!key.ExportSubjectPublicKeyInfo().AsSpan().SequenceEqual(encoded)) return null;
            var p = key.ExportParameters(false);
            if (!p.Curve.IsNamed || p.Curve.Oid.Value != P256Oid) return null;
            if (p.Q.X is not { Length: 32 } x || p.Q.Y is not { Length: 32 } y) return null;
            if (!OnCurve(x, y)) return null;
            return new EcPublicKey(encoded.ToArray(), x, y);
        }
        // Windows（CNG）は曲線の外の点を読み込むと PlatformNotSupportedException を投げる。相手から届いた鍵で処理を止めない
        catch (Exception e) when (e is CryptographicException or PlatformNotSupportedException)
        {
            return null;
        }
    }

    /// <summary>座標（32バイトずつ、ビッグエンディアン）が P-256 の曲線上の点か。</summary>
    internal static bool OnCurve(byte[] xBytes, byte[] yBytes)
    {
        var x = new BigInteger(xBytes, isUnsigned: true, isBigEndian: true);
        var y = new BigInteger(yBytes, isUnsigned: true, isBigEndian: true);
        if (x >= P || y >= P) return false;
        var lhs = y * y % P;
        var rhs = ((BigInteger.ModPow(x, 3, P) + A * x + B) % P + P) % P;
        return lhs == rhs;
    }

    /// <summary>端末 ID・指紋: 署名公開鍵の送る形の SHA-256。</summary>
    public static byte[] Fingerprint(byte[] encodedPublicKey) => Sha256(encodedPublicKey);

    /// <summary>端末 ID（指紋の base64url）。</summary>
    public static string DeviceId(byte[] encodedPublicKey) => B64.Encode(Fingerprint(encodedPublicKey));

    public static byte[] Sha256(params byte[][] parts)
    {
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (var part in parts) hash.AppendData(part);
        return hash.GetHashAndReset();
    }

    /// <summary>ECDH の共有秘密（x 座標そのもの、32バイト）。</summary>
    public static byte[] Ecdh(ECDiffieHellman privateKey, EcPublicKey publicKey)
    {
        using var peer = publicKey.CreateEcdh();
        return privateKey.DeriveRawSecretAgreement(peer.PublicKey);
    }

    /// <summary>用途ラベルを前置して署名する（DER 形式）。</summary>
    public static byte[] Sign(ECDsa privateKey, string label, byte[] message) =>
        privateKey.SignData(Labeled(label, message), HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

    public static bool Verify(EcPublicKey publicKey, string label, byte[] message, byte[] signature)
    {
        try
        {
            using var key = publicKey.CreateECDsa();
            return key.VerifyData(Labeled(label, message), signature, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (CryptographicException)
        {
            return false;
        }
    }

    public static byte[] HmacSha256(byte[] key, params byte[][] parts)
    {
        using var mac = IncrementalHash.CreateHMAC(HashAlgorithmName.SHA256, key);
        foreach (var part in parts) mac.AppendData(part);
        return mac.GetHashAndReset();
    }

    /// <summary>AES-256-GCM で封じる。nonce は毎回 96 ビットの乱数。暗号文は JCA と同じく末尾に 16 バイトのタグを付ける。</summary>
    public static (byte[] Nonce, byte[] Ciphertext) Seal(byte[] key, byte[] aad, byte[] plaintext)
    {
        if (key.Length != 32) throw new ArgumentException("key は 32 バイト", nameof(key));
        var nonce = RandomBytes(12);
        return (nonce, AesGcmSeal(key, nonce, aad, plaintext));
    }

    /// <summary>認証に失敗したら null。</summary>
    public static byte[]? Open(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext)
    {
        if (key.Length != 32 || nonce.Length != 12) return null;
        return AesGcmOpen(key, nonce, aad, ciphertext);
    }

    internal static byte[] AesGcmSeal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext)
    {
        using var gcm = new AesGcm(key, 16);
        var output = new byte[plaintext.Length + 16];
        gcm.Encrypt(nonce, plaintext, output.AsSpan(0, plaintext.Length), output.AsSpan(plaintext.Length), aad);
        return output;
    }

    internal static byte[]? AesGcmOpen(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext)
    {
        if (ciphertext.Length < 16) return null;
        try
        {
            using var gcm = new AesGcm(key, 16);
            var plain = new byte[ciphertext.Length - 16];
            gcm.Decrypt(nonce, ciphertext.AsSpan(0, plain.Length), ciphertext.AsSpan(plain.Length), plain, aad);
            return plain;
        }
        catch (CryptographicException)
        {
            return null;
        }
    }

    public static byte[] RandomBytes(int size) => RandomNumberGenerator.GetBytes(size);

    /// <summary>定数時間の比較。</summary>
    public static bool ConstantTimeEquals(byte[] a, byte[] b) => CryptographicOperations.FixedTimeEquals(a, b);

    /// <summary>長さ付きで連結する（ビッグエンディアンの4バイト長 ‖ 中身 を並べる）。</summary>
    public static byte[] LengthPrefixed(params byte[][] parts)
    {
        var output = new byte[parts.Sum(p => 4 + p.Length)];
        var offset = 0;
        foreach (var part in parts)
        {
            BinaryPrimitives.WriteInt32BigEndian(output.AsSpan(offset), part.Length);
            part.CopyTo(output, offset + 4);
            offset += 4 + part.Length;
        }
        return output;
    }

    /// <summary>Kotlin の encodeToByteArray と同じ UTF-8。</summary>
    internal static byte[] Utf8(string text) => Encoding.UTF8.GetBytes(text);

    private static byte[] Labeled(string label, byte[] message) => LengthPrefixed(Utf8(label), message);
}

/// <summary>HKDF（RFC 5869）、HMAC-SHA256。</summary>
public static class Hkdf
{
    private const int HashLen = 32;

    public static byte[] Extract(byte[] salt, byte[] ikm) => WaveCrypto.HmacSha256(salt.Length == 0 ? new byte[HashLen] : salt, ikm);

    public static byte[] Expand(byte[] prk, byte[] info, int length)
    {
        if (length < 1 || length > 255 * HashLen) throw new ArgumentOutOfRangeException(nameof(length));
        var output = new List<byte>();
        var previous = Array.Empty<byte>();
        var counter = 1;
        while (output.Count < length)
        {
            previous = WaveCrypto.HmacSha256(prk, previous, info, [(byte)counter]);
            output.AddRange(previous);
            counter++;
        }
        return output.Take(length).ToArray();
    }
}
