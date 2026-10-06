using System.Text.Json;
using System.Text.Json.Serialization;

namespace Wave.Core.Connect;

/// <summary>状態の種類（サーバーが版を持つもの）。</summary>
public enum StateKind
{
    [JsonStringEnumMemberName("cursor")] Cursor,
    [JsonStringEnumMemberName("queue")] Queue,
}

public static class StateKindExtensions
{
    /// <summary>状態の種類に対応する doc の種類。</summary>
    public static DocKind DocKind(this StateKind kind) => kind == StateKind.Cursor ? Connect.DocKind.Cursor : Connect.DocKind.Queue;
}

/// <summary>サーバーが持つ最新の状態（配るときの形）。</summary>
public sealed record StoredState
{
    public required StateKind Kind { get; init; }
    public required long Version { get; init; }
    public required SealedDoc Doc { get; init; }
    public required string StoreId { get; init; }
    public required long ReceivedAtServerMs { get; init; }
    public required long ServerNowMs { get; init; }
    public string? ActiveDeviceId { get; init; }
}

/// <summary>
/// 中継 v2 のメッセージ（<c>/v2/connect</c>、<c>/v2/pair</c>。判別子は <c>op</c>）。Android 版の RelayV2 と同じ JSON。
/// サーバーは SealedDoc の中身を読まない。
/// </summary>
[JsonPolymorphic(TypeDiscriminatorPropertyName = "op")]
[JsonDerivedType(typeof(Challenge), "challenge")]
[JsonDerivedType(typeof(Auth), "auth")]
[JsonDerivedType(typeof(Welcome), "welcome")]
[JsonDerivedType(typeof(HouseholdCreate), "household_create")]
[JsonDerivedType(typeof(ChainGet), "chain_get")]
[JsonDerivedType(typeof(Chain), "chain")]
[JsonDerivedType(typeof(ChainAppend), "chain_append")]
[JsonDerivedType(typeof(ChainOk), "chain_ok")]
[JsonDerivedType(typeof(ChainConflict), "chain_conflict")]
[JsonDerivedType(typeof(ChainChanged), "chain_changed")]
[JsonDerivedType(typeof(StateGet), "state_get")]
[JsonDerivedType(typeof(StatePut), "state_put")]
[JsonDerivedType(typeof(StateOk), "state_ok")]
[JsonDerivedType(typeof(StateConflict), "state_conflict")]
[JsonDerivedType(typeof(State), "state")]
[JsonDerivedType(typeof(Claim), "claim")]
[JsonDerivedType(typeof(Transfer), "transfer")]
[JsonDerivedType(typeof(YieldDelivery), "yield")]
[JsonDerivedType(typeof(Yielded), "yielded")]
[JsonDerivedType(typeof(Release), "release")]
[JsonDerivedType(typeof(ActiveChanged), "active_changed")]
[JsonDerivedType(typeof(PendingExpired), "pending_expired")]
[JsonDerivedType(typeof(Presence), "presence")]
[JsonDerivedType(typeof(PairingOpen), "pairing_open")]
[JsonDerivedType(typeof(PairHelloIn), "pair_hello_in")]
[JsonDerivedType(typeof(PairHello), "pair_hello")]
[JsonDerivedType(typeof(PairConfirm), "pair_confirm")]
[JsonDerivedType(typeof(PairConfirmed), "pair_confirmed")]
[JsonDerivedType(typeof(Error), "error")]
public abstract record RelayV2
{
    public const string AuthLabel = "wave-relay-v2-auth";
    public const int MaxFrameChars = 1_200_000;
    public const int MaxPairFrameChars = 8 * 1024;

    public static string Encode(RelayV2 message) => JsonSerializer.Serialize(message, ConnectJson.Options);

    /// <summary>読めない・知らない op なら null。</summary>
    public static RelayV2? Decode(string text) => ConnectJson.Decode<RelayV2>(text);

    // --- 接続 ---

    public sealed record Challenge : RelayV2
    {
        public required string Host { get; init; }
        public required string Nonce { get; init; }
    }

    public sealed record Auth : RelayV2
    {
        public required string PublicKey { get; init; }
        public required string Signature { get; init; }
    }

    public sealed record Welcome : RelayV2
    {
        public required string DeviceId { get; init; }
        public required string StoreId { get; init; }
        public string? HouseholdId { get; init; }
        public long? HeadSeq { get; init; }
        public string? HeadHash { get; init; }
        public string? ActiveDeviceId { get; init; }
        public IReadOnlyList<string> Online { get; init; } = [];

        /// <summary>最新の状態（welcome と一緒に渡し、端末が「最新を見た上で」つなぎ直しの判断をできるようにする）。</summary>
        public IReadOnlyList<StoredState> States { get; init; } = [];

        /// <summary>端末ごとの最新の claim の順番（同時に始めたときの判断、D-8）。</summary>
        public IReadOnlyDictionary<string, long> ClaimOrders { get; init; } = new Dictionary<string, long>();
    }

    // --- 端末一覧の鎖 ---

    public sealed record HouseholdCreate : RelayV2
    {
        public required ChainRecord Genesis { get; init; }
    }

    public sealed record ChainGet : RelayV2
    {
        public long FromSeq { get; init; }
    }

    public sealed record Chain : RelayV2
    {
        public required IReadOnlyList<ChainRecord> Records { get; init; }
    }

    public sealed record ChainAppend : RelayV2
    {
        public required ChainRecord Record { get; init; }
    }

    public sealed record ChainOk : RelayV2
    {
        public required long HeadSeq { get; init; }
        public required string HeadHash { get; init; }
    }

    public sealed record ChainConflict : RelayV2
    {
        public required long HeadSeq { get; init; }
        public required string HeadHash { get; init; }
    }

    public sealed record ChainChanged : RelayV2
    {
        public required long HeadSeq { get; init; }
        public required string HeadHash { get; init; }
    }

    // --- 状態 ---

    public sealed record StateGet : RelayV2
    {
        public static readonly StateGet Instance = new();
    }

    public sealed record StatePut : RelayV2
    {
        public required string RequestId { get; init; }
        public required StateKind Kind { get; init; }
        public required long ExpectVersion { get; init; }
        public required SealedDoc Doc { get; init; }
    }

    public sealed record StateOk : RelayV2
    {
        public required string RequestId { get; init; }
        public required StateKind Kind { get; init; }
        public required long Version { get; init; }
    }

    public sealed record StateConflict : RelayV2
    {
        public required string RequestId { get; init; }
        public required StateKind Kind { get; init; }
        public StoredState? Latest { get; init; }
    }

    public sealed record State : RelayV2
    {
        [JsonPropertyName("state")]
        public required StoredState Stored { get; init; }
    }

    // --- 再生を始める・引き取る ---

    public sealed record Claim : RelayV2
    {
        public required string ClaimId { get; init; }
        public required SealedDoc YieldDoc { get; init; }
    }

    public sealed record Transfer : RelayV2
    {
        public required string ClaimId { get; init; }
        public required long BasedOnVersion { get; init; }
        public required SealedDoc YieldDoc { get; init; }
    }

    public sealed record YieldDelivery : RelayV2
    {
        public required string ClaimId { get; init; }
        public required SealedDoc YieldDoc { get; init; }
    }

    public sealed record Yielded : RelayV2
    {
        public required string ClaimId { get; init; }
        public required long ExpectVersion { get; init; }
        public required SealedDoc Doc { get; init; }
    }

    public sealed record Release : RelayV2
    {
        public required string ClaimId { get; init; }
    }

    public sealed record ActiveChanged : RelayV2
    {
        /// <summary>Kotlin では既定値の無い null 可の項目。欠けていても null として読む。</summary>
        public string? ActiveDeviceId { get; init; }

        public string? ClaimId { get; init; }
        public required long ClaimOrder { get; init; }

        /// <summary>transfer の途中（新しい端末の最初の書き込みを待っている）。</summary>
        public string? PendingOwner { get; init; }
    }

    public sealed record PendingExpired : RelayV2
    {
        public required string ClaimId { get; init; }
    }

    public sealed record Presence : RelayV2
    {
        public required IReadOnlyList<string> Online { get; init; }
    }

    // --- 加入（Hello・Confirm は不透明な JSON） ---

    public sealed record PairingOpen : RelayV2
    {
        public required string TokenHash { get; init; }
    }

    public sealed record PairHelloIn : RelayV2
    {
        public required string Token { get; init; }
        public required JsonElement Hello { get; init; }
    }

    public sealed record PairHello : RelayV2
    {
        public required string TokenHash { get; init; }
        public required JsonElement Hello { get; init; }
    }

    public sealed record PairConfirm : RelayV2
    {
        public required string TokenHash { get; init; }
        public required JsonElement Confirm { get; init; }
    }

    public sealed record PairConfirmed : RelayV2
    {
        public required JsonElement Confirm { get; init; }
    }

    public sealed record Error : RelayV2
    {
        public required string Code { get; init; }
        public string? RequestId { get; init; }
    }
}
