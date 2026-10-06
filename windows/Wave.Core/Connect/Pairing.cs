using System.Buffers.Binary;
using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Security.Cryptography;

namespace Wave.Core.Connect;

/// <summary>
/// ペアリングの手順（Android 版の Pairing と同じ、ADR-0008）。通信に依らない純粋なロジック。A = QR を出す端末、B = QR を読む端末。
/// 1. A は使い捨ての鍵 eA を作り、QR <c>{v, pkA, eA, token}</c> を出す
/// 2. B は使い捨ての鍵 eB を作り、ECDH(eB, eA) から導いた鍵で <c>{pkB, sigB(transcript), 端末名, アカウントの HMAC}</c> を封じて、eB と一緒に A へ送る
/// 3. A は開いて sigB を確かめ、<c>{sigA(transcript), 端末名, アカウントの HMAC}</c> を封じて返す
/// 4. B は sigA を確かめ、確認用の6桁を表示する。A は本人がその6桁を入力し、一致したときだけペアを確定する
/// </summary>
public static class Pairing
{
    public const int Version = 1;
    public const int TokenBytes = 16;
    public const long LifetimeMs = 2 * 60 * 1000L;
    public const int MaxAttempts = 3;
    private const int MaxExtensionChars = 2048;
    private const string Label = "wave-pair-v1";
    private const string OfferPrefix = "wave-pair:";

    /// <summary>Kotlin の Pairing の Json（ignoreUnknownKeys のみ。既定値の項目は出さない）。</summary>
    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        RespectNullableAnnotations = true,
        Encoder = KotlinxJsonEncoder.Instance,
    };

    public sealed record Offer
    {
        [JsonPropertyName("v")] public required int V { get; init; }
        [JsonPropertyName("pkA")] public required string PkA { get; init; }
        [JsonPropertyName("eA")] public required string EA { get; init; }
        [JsonPropertyName("token")] public required string Token { get; init; }
    }

    /// <summary>B → A（中継の /pair 経由）。eB は平文（A が鍵を導くのに要る）、残りは封じる。</summary>
    public sealed record Hello
    {
        [JsonPropertyName("v")] public required int V { get; init; }
        [JsonPropertyName("eB")] public required string EB { get; init; }
        [JsonPropertyName("nonce")] public required string Nonce { get; init; }
        [JsonPropertyName("ciphertext")] public required string Ciphertext { get; init; }
    }

    /// <summary>A → B。</summary>
    public sealed record Confirm
    {
        [JsonPropertyName("nonce")] public required string Nonce { get; init; }
        [JsonPropertyName("ciphertext")] public required string Ciphertext { get; init; }
    }

    private sealed record HelloBody
    {
        [JsonPropertyName("pkB")] public required string PkB { get; init; }
        [JsonPropertyName("sigB")] public required string SigB { get; init; }
        [JsonPropertyName("deviceName")] public required string DeviceName { get; init; }
        [JsonPropertyName("accountHmac")] public required string AccountHmac { get; init; }

        /// <summary>Connect v2: B の家の鍵の記述（MemberKeys の JSON）。sigB の対象に入る。</summary>
        [JsonPropertyName("extension")]
        [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
        public string? Extension { get; init; }
    }

    private sealed record ConfirmBody
    {
        [JsonPropertyName("sigA")] public required string SigA { get; init; }
        [JsonPropertyName("deviceName")] public required string DeviceName { get; init; }
        [JsonPropertyName("accountHmac")] public required string AccountHmac { get; init; }

        /// <summary>Connect v2: 家の ID・一覧の頭・storeId・版の床（秘密を入れない）。sigA の対象に入る。</summary>
        [JsonPropertyName("extension")]
        [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
        public string? Extension { get; init; }
    }

    /// <summary>確定したペア。</summary>
    /// <param name="PeerExtension">相手の拡張（A から見た B の Hello、B から見た A の Confirm）。署名で確かめた値。</param>
    public sealed record Established(byte[] PeerPublicKey, string PeerDeviceName, byte[] SendKey, byte[] ReceiveKey, byte[] AccountKey, string? PeerExtension = null);

    /// <summary>B 側の結果: 表示する6桁と、確定したときに保存するペア。</summary>
    public sealed record Confirmed(string Code, Established Pair);

    public enum Failure
    {
        Malformed,
        BadSignature,
        Expired,
        WrongCode,
        TooManyAttempts,

        /// <summary>別のアカウントでログインした端末。</summary>
        AccountMismatch,

        AlreadyUsed,
    }

    /// <summary>成功なら Value、失敗なら Failure。</summary>
    public sealed record Result<T>(T? Value, Failure? Error) where T : class
    {
        public bool IsSuccess => Error is null;

        public T GetOrThrow() => Value ?? throw new InvalidOperationException($"pairing failed: {Error}");

        internal static Result<T> Ok(T value) => new(value, null);

        internal static Result<T> Fail(Failure failure) => new(null, failure);
    }

    public static string EncodeOffer(Offer offer) => OfferPrefix + B64.Encode(JsonSerializer.SerializeToUtf8Bytes(offer, Json));

    public static Offer? DecodeOffer(string text)
    {
        if (!text.StartsWith(OfferPrefix, StringComparison.Ordinal) || text.Length > 1024) return null;
        var bytes = B64.Decode(text[OfferPrefix.Length..]);
        if (bytes is null) return null;
        var offer = Parse<Offer>(bytes);
        if (offer is null || offer.V != Version) return null;
        if (B64.Decode(offer.PkA) is not { } pkA || WaveCrypto.DecodePublicKey(pkA) is null) return null;
        if (B64.Decode(offer.EA) is not { } eA || WaveCrypto.DecodePublicKey(eA) is null) return null;
        if (B64.Decode(offer.Token)?.Length != TokenBytes) return null;
        return offer;
    }

    public static Hello? DecodeHello(JsonElement element) => Parse<Hello>(element);

    public static Confirm? DecodeConfirm(JsonElement element) => Parse<Confirm>(element);

    public static JsonElement ToJson(Hello hello) => JsonSerializer.SerializeToElement(hello, Json);

    public static JsonElement ToJson(Confirm confirm) => JsonSerializer.SerializeToElement(confirm, Json);

    /// <summary>A 側。QR を出してから、1回の B の Hello と、6桁の入力を扱う。スレッドを跨いで呼んでよい。</summary>
    public sealed class Responder
    {
        private readonly IDeviceSigner identity;
        private readonly string deviceName;
        private readonly string accountId;
        private readonly Func<long> nowMs;
        private readonly string confirmAccountId;
        private readonly string? confirmExtension;
        private readonly byte[] pkA;
        private readonly ECDiffieHellman ephemeral;
        private readonly byte[] eA;
        private readonly long createdAtMs;
        private readonly object gate = new();
        private bool used;
        private int attempts;
        private (string Sas, Established Established)? pending;

        /// <param name="identity">身元の署名鍵</param>
        /// <param name="accountId">アカウントの識別子（正規化した DATASYNC_ID）。HMAC にだけ使う</param>
        /// <param name="confirmExtension">Connect v2: Confirm に入れる拡張（秘密を入れない）</param>
        public Responder(IDeviceSigner identity, string deviceName, string accountId, Func<long> nowMs, string? confirmExtension = null)
            : this(identity, deviceName, accountId, nowMs, accountId, confirmExtension, WaveCrypto.GenerateEphemeralKeyPair(), WaveCrypto.RandomBytes(TokenBytes))
        {
        }

        /// <summary>テスト用: 使い捨ての鍵と token を外から与える・Confirm に入れるアカウントを変える。</summary>
        internal Responder(IDeviceSigner identity, string deviceName, string accountId, Func<long> nowMs, string confirmAccountId, string? confirmExtension, ECDiffieHellman ephemeral, byte[] token)
        {
            this.identity = identity;
            this.deviceName = deviceName;
            this.accountId = accountId;
            this.nowMs = nowMs;
            this.confirmAccountId = confirmAccountId;
            this.confirmExtension = confirmExtension;
            this.ephemeral = ephemeral;
            pkA = identity.IdentityPub;
            eA = WaveCrypto.EncodePublicKey(ephemeral);
            Token = token;
            createdAtMs = nowMs();
            Offer = new Offer { V = Version, PkA = B64.Encode(pkA), EA = B64.Encode(eA), Token = B64.Encode(token) };
            TokenHash = WaveCrypto.Sha256(token);
        }

        public byte[] Token { get; }

        public Offer Offer { get; }

        /// <summary>中継に預ける値（token そのものは渡さない）。</summary>
        public byte[] TokenHash { get; }

        /// <summary>B の Hello を受けて Confirm を返す。token は最初の正しい Hello で消費する。</summary>
        public Result<Confirm> OnHello(Hello hello)
        {
            lock (gate)
            {
                if (Expired()) return Result<Confirm>.Fail(Failure.Expired);
                if (used) return Result<Confirm>.Fail(Failure.AlreadyUsed);
                if (hello.V != Version) return Result<Confirm>.Fail(Failure.Malformed);
                var eBBytes = B64.Decode(hello.EB);
                var eB = eBBytes is null ? null : WaveCrypto.DecodePublicKey(eBBytes);
                if (eBBytes is null || eB is null) return Result<Confirm>.Fail(Failure.Malformed);
                var shared = WaveCrypto.Ecdh(ephemeral, eB);
                var transcript0 = WaveCrypto.LengthPrefixed(WaveCrypto.Utf8(Label), pkA, eA, Token, eBBytes);
                var prk = Hkdf.Extract(Token, shared);
                var nonce = B64.Decode(hello.Nonce);
                var ciphertext = B64.Decode(hello.Ciphertext);
                if (nonce is null || ciphertext is null) return Result<Confirm>.Fail(Failure.Malformed);
                var plain = WaveCrypto.Open(HandshakeKey(prk, "b->a", transcript0), nonce, transcript0, ciphertext);
                var body = plain is null ? null : Parse<HelloBody>(plain);
                if (body is null) return Result<Confirm>.Fail(Failure.Malformed);
                var pkBBytes = B64.Decode(body.PkB);
                var pkB = pkBBytes is null ? null : WaveCrypto.DecodePublicKey(pkBBytes);
                if (pkBBytes is null || pkB is null) return Result<Confirm>.Fail(Failure.Malformed);
                // 自分自身とのペアは受けない（方向の鍵が崩れる）
                if (pkBBytes.AsSpan().SequenceEqual(pkA)) return Result<Confirm>.Fail(Failure.Malformed);
                var transcript = WaveCrypto.LengthPrefixed(transcript0, pkBBytes);
                if (body.Extension is not null && body.Extension.Length > MaxExtensionChars) return Result<Confirm>.Fail(Failure.Malformed);
                var sigB = B64.Decode(body.SigB);
                if (sigB is null) return Result<Confirm>.Fail(Failure.Malformed);
                if (!WaveCrypto.Verify(pkB, Label + "-b", SignedTranscript(transcript, body.Extension), sigB)) return Result<Confirm>.Fail(Failure.BadSignature);
                var keys = Schedule(prk, transcript, self: pkA, peer: pkBBytes);
                if (!WaveCrypto.ConstantTimeEquals(AccountHmac(keys.AccountKey, accountId), B64.Decode(body.AccountHmac) ?? [])) return Result<Confirm>.Fail(Failure.AccountMismatch);
                used = true;
                pending = (keys.Sas, new Established(pkBBytes, SanitizeName(body.DeviceName), keys.SendKey, keys.ReceiveKey, keys.AccountKey, body.Extension));
                var confirm = new ConfirmBody
                {
                    SigA = B64.Encode(identity.Sign(Label + "-a", SignedTranscript(transcript, confirmExtension))),
                    DeviceName = deviceName,
                    AccountHmac = B64.Encode(AccountHmac(keys.AccountKey, confirmAccountId)),
                    Extension = confirmExtension,
                };
                var (sealNonce, sealed_) = WaveCrypto.Seal(HandshakeKey(prk, "a->b", transcript0), transcript0, JsonSerializer.SerializeToUtf8Bytes(confirm, Json));
                return Result<Confirm>.Ok(new Confirm { Nonce = B64.Encode(sealNonce), Ciphertext = B64.Encode(sealed_) });
            }
        }

        /// <summary>本人が入力した6桁（B の画面の数字）。一致したらペアを確定する。3回まで。</summary>
        public Result<Established> OnCodeEntered(string code)
        {
            lock (gate)
            {
                if (Expired()) return Result<Established>.Fail(Failure.Expired);
                if (attempts >= MaxAttempts) return Result<Established>.Fail(Failure.TooManyAttempts);
                if (pending is not { } current) return Result<Established>.Fail(Failure.Malformed);
                attempts++;
                if (!WaveCrypto.ConstantTimeEquals(WaveCrypto.Utf8(code.Trim()), WaveCrypto.Utf8(current.Sas)))
                {
                    if (attempts >= MaxAttempts) pending = null;
                    return Result<Established>.Fail(attempts >= MaxAttempts ? Failure.TooManyAttempts : Failure.WrongCode);
                }
                pending = null;
                return Result<Established>.Ok(current.Established);
            }
        }

        private bool Expired() => nowMs() - createdAtMs > LifetimeMs;
    }

    /// <summary>B 側。QR を読んで Hello を作り、A の Confirm で6桁とペアを得る。</summary>
    public sealed class Initiator
    {
        private readonly IDeviceSigner identity;
        private readonly string deviceName;
        private readonly string accountId;
        private readonly string? helloExtension;
        private readonly byte[] pkB;
        private readonly byte[] eB;
        private readonly byte[] pkA;
        private readonly byte[] transcript0;
        private readonly byte[] transcript;
        private readonly byte[] prk;
        private readonly Keys keys;

        /// <param name="offer">DecodeOffer を通したもの</param>
        /// <param name="helloExtension">Connect v2: Hello に入れる拡張（B の家の鍵の記述）</param>
        public Initiator(Offer offer, IDeviceSigner identity, string deviceName, string accountId, string? helloExtension = null)
            : this(offer, identity, deviceName, accountId, helloExtension, WaveCrypto.GenerateEphemeralKeyPair())
        {
        }

        /// <summary>テスト用: 使い捨ての鍵を外から与える。</summary>
        internal Initiator(Offer offer, IDeviceSigner identity, string deviceName, string accountId, string? helloExtension, ECDiffieHellman ephemeral)
        {
            this.identity = identity;
            this.deviceName = deviceName;
            this.accountId = accountId;
            this.helloExtension = helloExtension;
            pkB = identity.IdentityPub;
            eB = WaveCrypto.EncodePublicKey(ephemeral);
            pkA = B64.Decode(offer.PkA) is { } a && WaveCrypto.DecodePublicKey(a) is not null ? a : throw new ArgumentException("offer は DecodeOffer を通すこと");
            var eABytes = B64.Decode(offer.EA);
            var eAKey = eABytes is null ? null : WaveCrypto.DecodePublicKey(eABytes);
            if (eABytes is null || eAKey is null) throw new ArgumentException("offer は DecodeOffer を通すこと");
            var token = B64.Decode(offer.Token) is { Length: TokenBytes } t ? t : throw new ArgumentException("offer は DecodeOffer を通すこと");
            transcript0 = WaveCrypto.LengthPrefixed(WaveCrypto.Utf8(Label), pkA, eABytes, token, eB);
            transcript = WaveCrypto.LengthPrefixed(transcript0, pkB);
            prk = Hkdf.Extract(token, WaveCrypto.Ecdh(ephemeral, eAKey));
            keys = Schedule(prk, transcript, self: pkB, peer: pkA);
        }

        public Hello Hello()
        {
            var body = new HelloBody
            {
                PkB = B64.Encode(pkB),
                SigB = B64.Encode(identity.Sign(Label + "-b", SignedTranscript(transcript, helloExtension))),
                DeviceName = deviceName,
                AccountHmac = B64.Encode(AccountHmac(keys.AccountKey, accountId)),
                Extension = helloExtension,
            };
            var (nonce, ciphertext) = WaveCrypto.Seal(HandshakeKey(prk, "b->a", transcript0), transcript0, JsonSerializer.SerializeToUtf8Bytes(body, Json));
            return new Hello { V = Version, EB = B64.Encode(eB), Nonce = B64.Encode(nonce), Ciphertext = B64.Encode(ciphertext) };
        }

        /// <summary>A の Confirm を確かめ、（表示する6桁, 確定したときに保存するペア）を返す。</summary>
        public Result<Confirmed> OnConfirm(Confirm confirm)
        {
            var nonce = B64.Decode(confirm.Nonce);
            var ciphertext = B64.Decode(confirm.Ciphertext);
            if (nonce is null || ciphertext is null) return Result<Confirmed>.Fail(Failure.Malformed);
            var plain = WaveCrypto.Open(HandshakeKey(prk, "a->b", transcript0), nonce, transcript0, ciphertext);
            var body = plain is null ? null : Parse<ConfirmBody>(plain);
            if (body is null) return Result<Confirmed>.Fail(Failure.Malformed);
            var pkAKey = WaveCrypto.DecodePublicKey(pkA);
            if (pkAKey is null) return Result<Confirmed>.Fail(Failure.Malformed);
            if (body.Extension is not null && body.Extension.Length > MaxExtensionChars) return Result<Confirmed>.Fail(Failure.Malformed);
            var sigA = B64.Decode(body.SigA);
            if (sigA is null) return Result<Confirmed>.Fail(Failure.Malformed);
            if (!WaveCrypto.Verify(pkAKey, Label + "-a", SignedTranscript(transcript, body.Extension), sigA)) return Result<Confirmed>.Fail(Failure.BadSignature);
            if (!WaveCrypto.ConstantTimeEquals(AccountHmac(keys.AccountKey, accountId), B64.Decode(body.AccountHmac) ?? [])) return Result<Confirmed>.Fail(Failure.AccountMismatch);
            return Result<Confirmed>.Ok(new Confirmed(keys.Sas, new Established(pkA, SanitizeName(body.DeviceName), keys.SendKey, keys.ReceiveKey, keys.AccountKey, body.Extension)));
        }
    }

    private sealed record Keys(string Sas, byte[] SendKey, byte[] ReceiveKey, byte[] AccountKey);

    /// <summary>拡張があれば、署名の対象に拡張のハッシュを足す（無ければ v1 と同じ）。</summary>
    private static byte[] SignedTranscript(byte[] transcript, string? extension) =>
        extension is null ? transcript : WaveCrypto.LengthPrefixed(transcript, WaveCrypto.Sha256(WaveCrypto.Utf8(extension)));

    private static byte[] HandshakeKey(byte[] prk, string direction, byte[] transcript0) =>
        Hkdf.Expand(prk, [.. WaveCrypto.Utf8($"{Label} handshake {direction}"), .. WaveCrypto.Sha256(transcript0)], 32);

    /// <summary>確定したペアの鍵。方向は端末 ID（公開鍵の SHA-256）の大小（符号なし）で決める。</summary>
    private static Keys Schedule(byte[] prk, byte[] transcript, byte[] self, byte[] peer)
    {
        var hash = WaveCrypto.Sha256(transcript);
        var sasBytes = Hkdf.Expand(prk, [.. WaveCrypto.Utf8($"{Label} sas"), .. hash], 8);
        // Long.remainderUnsigned と同じ（8バイトを符号なしとして読む）
        var sasNumber = BinaryPrimitives.ReadUInt64BigEndian(sasBytes) % 1_000_000UL;
        var lowToHigh = Hkdf.Expand(prk, [.. WaveCrypto.Utf8($"{Label} key low->high"), .. hash], 32);
        var highToLow = Hkdf.Expand(prk, [.. WaveCrypto.Utf8($"{Label} key high->low"), .. hash], 32);
        var accountKey = Hkdf.Expand(prk, [.. WaveCrypto.Utf8($"{Label} account"), .. hash], 32);
        var selfIsLow = WaveCrypto.Fingerprint(self).AsSpan().SequenceCompareTo(WaveCrypto.Fingerprint(peer)) < 0;
        return new Keys(
            sasNumber.ToString("D6", CultureInfo.InvariantCulture),
            selfIsLow ? lowToHigh : highToLow,
            selfIsLow ? highToLow : lowToHigh,
            accountKey);
    }

    /// <summary>同じアカウントかを確かめる HMAC（誤操作の防止で、防御ではない）。</summary>
    public static byte[] AccountHmac(byte[] accountKey, string accountId) =>
        WaveCrypto.HmacSha256(accountKey, "wave-account-v1"u8.ToArray(), WaveCrypto.Utf8(accountId));

    /// <summary>端末名から制御文字・書字方向の上書き文字を落とし、64文字までにする。</summary>
    public static string SanitizeName(string name)
    {
        var builder = new StringBuilder();
        foreach (var c in name)
        {
            if (HandoffCodec.IsIsoControl(c) || c is >= '‪' and <= '‮' || c is >= '⁦' and <= '⁩' || c == '‎' || c == '‏') continue;
            builder.Append(c);
        }
        var trimmed = builder.ToString().Trim();
        if (trimmed.Length > 64) trimmed = trimmed[..64];
        return trimmed.Length == 0 ? "端末" : trimmed;
    }

    private static T? Parse<T>(byte[] utf8) where T : class
    {
        try
        {
            return JsonSerializer.Deserialize<T>(utf8, Json);
        }
        catch (Exception e) when (e is JsonException or InvalidOperationException or NotSupportedException)
        {
            return null;
        }
    }

    private static T? Parse<T>(JsonElement element) where T : class
    {
        try
        {
            return element.Deserialize<T>(Json);
        }
        catch (Exception e) when (e is JsonException or InvalidOperationException or NotSupportedException)
        {
            return null;
        }
    }
}
