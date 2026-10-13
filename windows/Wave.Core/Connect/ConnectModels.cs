using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Wave.Core.Connect;

// Connect v2 の形式（Android 版の ConnectModels.kt と同じ JSON）。端末一覧は署名付きの記録の鎖で、状態・命令は書いた端末の署名つきの SealedDoc。
// バイナリは base64url（パディングなし）の文字列で運ぶ。
// JSON のプロパティは Kotlin の宣言の順に並べる（kotlinx.serialization と同じ順で出す）。
// enum の JSON の名前は SerialName（"player" など）、署名・ハッシュの対象には enum の名前（"Player" など、ToString()）を使う。

public enum Role
{
    /// <summary>再生する端末（アカウントの照合あり）。</summary>
    [JsonStringEnumMemberName("player")] Player,

    /// <summary>表示と操作だけ（PC）。再生しない、アカウントを持たない。</summary>
    [JsonStringEnumMemberName("controller")] Controller,
}

public enum ChainType
{
    [JsonStringEnumMemberName("genesis")] Genesis,
    [JsonStringEnumMemberName("add")] Add,
    [JsonStringEnumMemberName("remove")] Remove,
}

/// <summary>家に加わる端末の鍵。KxSignature は、その端末の身元鍵での <c>wave-v2-kx</c>（deviceId・kxPub・role）の署名。</summary>
public sealed record MemberKeys
{
    public required string DeviceId { get; init; }

    /// <summary>身元の署名公開鍵（SPKI）。DeviceId はこの SHA-256。</summary>
    public required string IdentityPub { get; init; }

    /// <summary>受け取りの鍵（P-256、非圧縮 SEC1）。</summary>
    public required string KxPub { get; init; }

    public required Role Role { get; init; }
    public required string KxSignature { get; init; }
}

/// <summary>
/// 端末一覧の記録。genesis は householdId を持たない（householdId = genesis のハッシュ）。
/// add・genesis は target、remove は removedDeviceId を持つ。
/// </summary>
public sealed record ChainRecord
{
    public const int FormatVersion = 1;

    public int V { get; init; } = FormatVersion;
    public string? HouseholdId { get; init; }
    public required long Seq { get; init; }
    public string? PrevHash { get; init; }
    public required ChainType Type { get; init; }
    public MemberKeys? Target { get; init; }
    public string? RemovedDeviceId { get; init; }
    public required string SignerId { get; init; }
    public required string Signature { get; init; }
}

public enum DocKind
{
    [JsonStringEnumMemberName("cursor")] Cursor,
    [JsonStringEnumMemberName("queue")] Queue,
    [JsonStringEnumMemberName("command")] Command,
    [JsonStringEnumMemberName("profile")] Profile,

    /// <summary>ライブラリ（RSS で登録した番組・聴いたところ、ADR-0013）。家のどの端末も書ける。</summary>
    [JsonStringEnumMemberName("library")] Library,
}

/// <summary>SealedDoc の見出し（平文。署名と、内容・包みの AAD に入る）。</summary>
public sealed record DocHeader
{
    public const int FormatVersion = 1;

    public int V { get; init; } = FormatVersion;
    public required DocKind Kind { get; init; }
    public required string HouseholdId { get; init; }
    public required string WriterId { get; init; }

    public required long Version { get; init; }

    /// <summary>同じ書き手の、同じ kind の直前の doc のハッシュ（差し替えの検出だけに使う）。</summary>
    public string? PrevHash { get; init; }

    public required long HeadSeq { get; init; }
    public required string HeadHash { get; init; }
    public required long IssuedAtEpochMs { get; init; }
}

/// <summary>宛先ごとの包み: 内容鍵を、その端末の kx へ HPKE で包んだもの。</summary>
public sealed record DocRecipient
{
    public required string DeviceId { get; init; }
    public required string Enc { get; init; }
    public required string WrappedKey { get; init; }
}

public sealed record SealedDoc
{
    public required DocHeader Header { get; init; }
    public required string Nonce { get; init; }
    public required string Ciphertext { get; init; }
    public required IReadOnlyList<DocRecipient> Recipients { get; init; }
    public required string Signature { get; init; }
}

/// <summary>カーソル（D-5）。小さく、再生・一時停止・シーク・遷移のたびに書く。</summary>
public sealed record CursorBody
{
    /// <summary>このカーソルの曲が載っているキューの doc のハッシュ（DocSealer.Hash）。</summary>
    public required string QueueRef { get; init; }

    public required string CurrentItemId { get; init; }
    public required long PositionMs { get; init; }
    public required bool Playing { get; init; }
    public required HandoffRepeat Repeat { get; init; }

    /// <summary>このセッションを始めたときに見ていた最新のカーソルの版（L-10）。</summary>
    public required long SessionBase { get; init; }

    /// <summary>このセッションで自分が最初に書けたカーソルの版。書けるまでは null。</summary>
    public long? SessionStartVersion { get; init; }

    public required long DeviceSeq { get; init; }
    public required long ListenedMs { get; init; }
    public required bool HistorySent { get; init; }
    public required string AccountTag { get; init; }

    /// <summary>表示だけ（順序の判断に使わない）。</summary>
    public required long StartedAtEpochMs { get; init; }
}

/// <summary>キュー（D-5）。Snapshot は HandoffCodec.Encode の文字列（取り込む側が HandoffCodec.Decode で検証する）。</summary>
public sealed record QueueBody
{
    public required string AccountTag { get; init; }
    public required string Snapshot { get; init; }
}

/// <summary>命令（判別子は "type"）。</summary>
[JsonPolymorphic(TypeDiscriminatorPropertyName = "type")]
[JsonDerivedType(typeof(Yield), "yield")]
[JsonDerivedType(typeof(Control), "control")]
[JsonDerivedType(typeof(Profile), "profile")]
public abstract record CommandBody
{
    /// <summary>再生を始めた端末から、今の再生中の端末へ「止まって」（D-7）。</summary>
    public sealed record Yield : CommandBody
    {
        public required string ClaimId { get; init; }
        public required string From { get; init; }
        public required string To { get; init; }
        public required long SessionBase { get; init; }
        public required string AccountTag { get; init; }
    }

    /// <summary>
    /// 遠隔操作: 鳴っていない端末から、今の再生中の端末へ「再生・一時停止・次へ・前へ・シーク」。
    /// 受け手は、自分宛て・自分が書き手・同じアカウント・処理していない ID・見ていた版が近いときだけ従う。
    /// </summary>
    public sealed record Control : CommandBody
    {
        public required string CommandId { get; init; }
        public required string From { get; init; }
        public required string To { get; init; }
        public required ControlAction Action { get; init; }

        /// <summary>シークの位置（seek のときだけ）。</summary>
        public long? PositionMs { get; init; }

        /// <summary>送り手が見ていた、受け手のカーソルの版。</summary>
        public required long BasedOnVersion { get; init; }

        public required string AccountTag { get; init; }

        /// <summary>
        /// 送り手が見ていた、受け手の今の曲（次へ・前へ・シーク）。受け手の今の曲と違えば従わない（Android と同じ）。前の版の送り手は付けない（null）。
        /// </summary>
        public string? OnItemId { get; init; }
    }

    /// <summary>端末の表示名を家のほかの端末に知らせる（名前は鎖に入れないので、加入した2台以外にも伝える）。</summary>
    public sealed record Profile : CommandBody
    {
        public required string From { get; init; }
        public required string Name { get; init; }

        /// <summary>端末の種類（"phone"・"pc" など。表示の絵だけに使う）。</summary>
        public string? Kind { get; init; }
    }
}

public enum ControlAction
{
    [JsonStringEnumMemberName("play")] Play,
    [JsonStringEnumMemberName("pause")] Pause,
    [JsonStringEnumMemberName("next")] Next,
    [JsonStringEnumMemberName("previous")] Previous,
    [JsonStringEnumMemberName("seek")] Seek,

    /// <summary>宛先の端末に「ここで再生（引き取り）」を頼む。宛先は書き手でない端末。</summary>
    [JsonStringEnumMemberName("transfer")] Transfer,
}

public sealed record ProfileBody
{
    public required string Name { get; init; }
}

/// <summary>
/// ライブラリの文書の中身（ADR-0013。Kotlin の LibraryBody と同じ JSON）。RSS で登録した番組と、エピソードの聴いたところ。
/// 番組・エピソードごとに、UpdatedAtMs の新しい方を残して合わせる（Merge）。外した番組も印（Removed）として残す。
/// </summary>
public sealed record LibraryBody
{
    public const int CurrentVersion = 1;

    /// <summary>聴いたところは新しい順にこの数まで（文書を数百 KB に収める）。</summary>
    public const int MaxPositions = 500;

    /// <summary>外した番組の印は、この日数を過ぎたら捨ててよい（全端末に行き渡った後）。</summary>
    public const int RemovedKeepDays = 180;

    public int V { get; init; } = CurrentVersion;

    /// <summary>フィードの URL ごと。</summary>
    public IReadOnlyDictionary<string, LibraryFeed> Feeds { get; init; } = new Dictionary<string, LibraryFeed>();

    /// <summary>音声の URL ごと。</summary>
    public IReadOnlyDictionary<string, LibraryPosition> Positions { get; init; } = new Dictionary<string, LibraryPosition>();

    /// <summary>
    /// 2つを合わせる。同じ時刻なら other を残す（自分の書き込みが後から届いた相手の書き込みを消さないよう、呼ぶ側は相手を other に渡す）。
    /// 並びは Kotlin と同じ（自分の項目の順、その後に相手だけの項目）。
    /// </summary>
    public LibraryBody Merge(LibraryBody other)
    {
        var feeds = new Dictionary<string, LibraryFeed>();
        foreach (var key in Feeds.Keys.Concat(other.Feeds.Keys))
        {
            if (!feeds.ContainsKey(key)) feeds[key] = Newer(Feeds.GetValueOrDefault(key), other.Feeds.GetValueOrDefault(key), f => f.UpdatedAtMs);
        }
        var positions = new Dictionary<string, LibraryPosition>();
        foreach (var key in Positions.Keys.Concat(other.Positions.Keys))
        {
            if (!positions.ContainsKey(key)) positions[key] = Newer(Positions.GetValueOrDefault(key), other.Positions.GetValueOrDefault(key), p => p.UpdatedAtMs);
        }
        return new LibraryBody { V = CurrentVersion, Feeds = feeds, Positions = Trim(positions) };
    }

    /// <summary>中身が同じか（Kotlin の data class の == と同じく、辞書は並びを問わない）。</summary>
    public bool SameAs(LibraryBody other) =>
        V == other.V && SameMap(Feeds, other.Feeds) && SameMap(Positions, other.Positions);

    private static bool SameMap<T>(IReadOnlyDictionary<string, T> a, IReadOnlyDictionary<string, T> b) =>
        a.Count == b.Count && a.All(kv => b.TryGetValue(kv.Key, out var o) && EqualityComparer<T>.Default.Equals(kv.Value, o));

    private static T Newer<T>(T? mine, T? other, Func<T, long> at) where T : class =>
        mine is null ? other! : other is null ? mine : at(mine) > at(other) ? mine : other;

    private static IReadOnlyDictionary<string, LibraryPosition> Trim(Dictionary<string, LibraryPosition> positions) =>
        positions.Count <= MaxPositions ? positions : positions.OrderByDescending(p => p.Value.UpdatedAtMs).Take(MaxPositions).ToDictionary(p => p.Key, p => p.Value);
}

public sealed record LibraryFeed
{
    public required string Title { get; init; }
    public string? Author { get; init; }
    public string? ThumbnailUrl { get; init; }
    public long AddedAtMs { get; init; }

    /// <summary>外した（この印のほうが新しければ、ほかの端末でも外す）。</summary>
    public bool Removed { get; init; }

    public required long UpdatedAtMs { get; init; }
}

public sealed record LibraryPosition
{
    public required int PositionSeconds { get; init; }
    public int? DurationSeconds { get; init; }
    public bool Finished { get; init; }
    public required long UpdatedAtMs { get; init; }
}

/// <summary>
/// kotlinx.serialization の設定（ignoreUnknownKeys・encodeDefaults・null を "x":null と出す）に合わせた JSON。
/// 判別子は型ごとの属性（ConnectJson・HandoffCodec は "type"、RelayV2 は "op"）。判別子はオブジェクトの先頭に出し、読むときは位置を問わない。
/// Kotlin で既定値の無い、null にならない項目は必須（欠けていれば読めない）。null になりうる項目は、欠けていても null として読む。
/// </summary>
public static class ConnectJson
{
    public static readonly JsonSerializerOptions Options = Create();

    private static JsonSerializerOptions Create()
    {
        var options = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            DefaultIgnoreCondition = JsonIgnoreCondition.Never,
            AllowOutOfOrderMetadataProperties = true,
            RespectNullableAnnotations = true,
            Encoder = KotlinxJsonEncoder.Instance,
        };
        options.Converters.Add(new JsonStringEnumConverter(namingPolicy: null, allowIntegerValues: false));
        options.MakeReadOnly(populateMissingResolver: true);
        return options;
    }

    public static string Encode<T>(T value) => JsonSerializer.Serialize(value, Options);

    /// <summary>読めなければ null（例外を出さない）。</summary>
    public static T? Decode<T>(string text) where T : class
    {
        try
        {
            return JsonSerializer.Deserialize<T>(text, Options);
        }
        catch (Exception e) when (e is JsonException or NotSupportedException or InvalidOperationException or ArgumentException)
        {
            return null;
        }
    }

    public static T? Decode<T>(byte[] utf8) where T : class
    {
        try
        {
            return JsonSerializer.Deserialize<T>(utf8, Options);
        }
        catch (Exception e) when (e is JsonException or NotSupportedException or InvalidOperationException or ArgumentException)
        {
            return null;
        }
    }

    public static byte[] EncodeToUtf8<T>(T value) => JsonSerializer.SerializeToUtf8Bytes(value, Options);
}

/// <summary>家ごとのアカウントタグ（D-3、Android 版の AccountTag と同じ）: <c>SHA-256(ラベル ‖ householdId ‖ DATASYNC_ID)</c> を base64url で。値はログに出さない。</summary>
public static class AccountTag
{
    public const string Label = "wave-v2-account-tag";

    public static string Of(string householdId, string accountId) =>
        B64.Encode(WaveCrypto.Sha256(WaveCrypto.LengthPrefixed(WaveCrypto.Utf8(Label), WaveCrypto.Utf8(householdId), WaveCrypto.Utf8(accountId))));
}

/// <summary>
/// kotlinx.serialization と同じく、JSON に必要な文字（<c>"</c>・<c>\</c>・U+0000〜U+001F）だけを逃がす。
/// 日本語・絵文字はそのまま出す（同じ値から Kotlin と同じ文字列ができる）。
/// </summary>
public sealed class KotlinxJsonEncoder : JavaScriptEncoder
{
    public static readonly KotlinxJsonEncoder Instance = new();

    private KotlinxJsonEncoder()
    {
    }

    public override int MaxOutputCharactersPerInputCharacter => 6;

    public override bool WillEncode(int unicodeScalar) => unicodeScalar < 0x20 || unicodeScalar == '"' || unicodeScalar == '\\';

    public override unsafe int FindFirstCharacterToEncode(char* text, int textLength)
    {
        for (var i = 0; i < textLength; i++)
        {
            if (WillEncode(text[i])) return i;
        }
        return -1;
    }

    public override unsafe bool TryEncodeUnicodeScalar(int unicodeScalar, char* buffer, int bufferLength, out int numberOfCharactersWritten)
    {
        var escaped = unicodeScalar switch
        {
            '"' => "\\\"",
            '\\' => "\\\\",
            '\b' => "\\b",
            '\t' => "\\t",
            '\n' => "\\n",
            '\f' => "\\f",
            '\r' => "\\r",
            < 0x20 => $"\\u{unicodeScalar:x4}",
            _ => char.ConvertFromUtf32(unicodeScalar),
        };
        if (escaped.Length > bufferLength)
        {
            numberOfCharactersWritten = 0;
            return false;
        }
        for (var i = 0; i < escaped.Length; i++) buffer[i] = escaped[i];
        numberOfCharactersWritten = escaped.Length;
        return true;
    }
}
