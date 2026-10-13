using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace Wave.Core.Connect;

/// <summary>
/// 引き取りのときに送るキューのスナップショット（Android 版の HandoffSnapshot と同じ JSON）。
/// 端末の中の状態と認証情報は入れない。端末の外に出るので、プラットフォームに依らない形に凍結する。
/// </summary>
public sealed record HandoffSnapshot
{
    public required int SchemaVersion { get; init; }

    /// <summary>引き取りの ID（UUID）。</summary>
    public required string HandoffId { get; init; }

    public required IReadOnlyList<HandoffItem> Items { get; init; }
    public required string CurrentItemId { get; init; }
    public required long PositionMs { get; init; }
    public required bool Playing { get; init; }
    public required HandoffRepeat Repeat { get; init; }
    public HandoffSource? Source { get; init; }
    public string? Continuation { get; init; }
    public string? RadioPlaylistId { get; init; }
    public required bool Exhausted { get; init; }

    /// <summary>今の曲を聴いた時間（送る側の単調時計で数えた値）。</summary>
    public required long ListenedMs { get; init; }

    /// <summary>今の曲の再生履歴を送り済みか。</summary>
    public required bool HistorySent { get; init; }

    /// <summary>表示とログのためだけ（端末間の時計はずれるので判定に使わない）。</summary>
    public required long CapturedAtEpochMs { get; init; }
}

public sealed record HandoffItem
{
    /// <summary>項目ごとの ID（UUID）。</summary>
    public required string ItemId { get; init; }

    public required string VideoId { get; init; }
    public required string Title { get; init; }
    public required IReadOnlyList<HandoffRef> Artists { get; init; }
    public HandoffRef? Album { get; init; }
    public int? DurationSeconds { get; init; }
    public string? ThumbnailUrl { get; init; }
    public required HandoffOrigin Origin { get; init; }
}

public sealed record HandoffRef
{
    public required string Name { get; init; }
    public string? BrowseId { get; init; }
}

public enum HandoffOrigin
{
    [JsonStringEnumMemberName("source")] Source,
    [JsonStringEnumMemberName("suggestion")] Suggestion,
    [JsonStringEnumMemberName("user")] UserAdded,
}

public enum HandoffRepeat
{
    [JsonStringEnumMemberName("off")] Off,
    [JsonStringEnumMemberName("one")] One,
    [JsonStringEnumMemberName("all")] All,
}

[JsonPolymorphic(TypeDiscriminatorPropertyName = "type")]
[JsonDerivedType(typeof(Radio), "radio")]
[JsonDerivedType(typeof(Playlist), "playlist")]
public abstract record HandoffSource
{
    public sealed record Radio : HandoffSource
    {
        public required string SeedVideoId { get; init; }
    }

    public sealed record Playlist : HandoffSource
    {
        public required string PlaylistId { get; init; }
    }
}

/// <summary>受け取ったスナップショットの検証の結果。</summary>
public abstract record HandoffDecodeResult
{
    public sealed record Ok(HandoffSnapshot Snapshot) : HandoffDecodeResult;

    /// <summary>形式のバージョンが違う（相手を更新する必要がある）。</summary>
    public sealed record UnsupportedVersion(int? Version) : HandoffDecodeResult;

    /// <summary>読めない・上限を超える・矛盾している。理由は種類だけ（値を含めない）。</summary>
    public sealed record Rejected(string Reason) : HandoffDecodeResult;
}

/// <summary>スナップショットの文字列の形（Android 版の HandoffCodec と同じ）。</summary>
public static class HandoffCodec
{
    public const int SchemaVersion = 1;

    /// <summary>暗号化する前のバイト数の上限。</summary>
    public const int MaxBytes = 512 * 1024;

    public const int MaxPlayedItems = 20;
    public const int MaxUpcomingItems = 300;
    private const int MaxText = 500;
    private const int MaxContinuation = 4096;
    private const int MaxArtists = 20;
    private const long MaxTimeMs = 24 * 3600 * 1000L;

    private static readonly Regex VideoId = new(@"\A[A-Za-z0-9_-]{11}\z");

    /// <summary>キューの項目の曲の ID: YouTube の動画 ID か、RSS のエピソード（ext: + https の音声の URL。ADR-0012）。</summary>
    public static bool IsItemVideoId(string id) =>
        VideoId.IsMatch(id) || Music.Feed.AudioUrlOf(id) is { } url && !url.Any(IsIsoControl);
    private static readonly Regex BrowseId = new(@"\A[A-Za-z0-9_-]{2,64}\z");
    private static readonly Regex PlaylistId = new(@"\A[A-Za-z0-9_-]{2,128}\z");
    private static readonly Regex Uuid = new(@"\A[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\z");
    private static readonly Regex Host = new(@"\A[a-z0-9.-]{1,253}\z");
    private static readonly string[] ThumbnailHosts = [".googleusercontent.com", ".ggpht.com", ".ytimg.com"];

    public static string Encode(HandoffSnapshot snapshot) => ConnectJson.Encode(snapshot);

    /// <summary>
    /// 受け取った文字列を読み、上限と形を検証する。サムネイルのホストが合わないものは、スナップショットを拒否せずに null にする。
    /// </summary>
    /// <param name="expectedHandoffId">発行して待っている ID。違えば拒否</param>
    public static HandoffDecodeResult Decode(string text, string expectedHandoffId)
    {
        if (Encoding.UTF8.GetByteCount(text) > MaxBytes) return new HandoffDecodeResult.Rejected("too_large");
        int? version;
        try
        {
            using var document = JsonDocument.Parse(text);
            if (document.RootElement.ValueKind != JsonValueKind.Object) return new HandoffDecodeResult.Rejected("unreadable");
            version = document.RootElement.TryGetProperty("schemaVersion", out var v) && v.ValueKind != JsonValueKind.Null ? v.GetInt32() : null;
        }
        catch (Exception e) when (e is JsonException or FormatException or InvalidOperationException)
        {
            // 読めない JSON は「相手を更新して」ではなく拒否
            return new HandoffDecodeResult.Rejected("unreadable");
        }
        if (version != SchemaVersion) return new HandoffDecodeResult.UnsupportedVersion(version);
        var snapshot = ConnectJson.Decode<HandoffSnapshot>(text);
        if (snapshot is null) return new HandoffDecodeResult.Rejected("unreadable");
        return Validate(snapshot, expectedHandoffId);
    }

    public static HandoffDecodeResult Validate(HandoffSnapshot snapshot, string expectedHandoffId)
    {
        static HandoffDecodeResult Reject(string reason) => new HandoffDecodeResult.Rejected(reason);
        if (snapshot.HandoffId != expectedHandoffId || !Uuid.IsMatch(snapshot.HandoffId)) return Reject("handoff_id");
        if (snapshot.Items.Count == 0 || snapshot.Items.Count > MaxPlayedItems + 1 + MaxUpcomingItems) return Reject("item_count");
        var ids = snapshot.Items.Select(i => i.ItemId).ToList();
        if (ids.Distinct().Count() != ids.Count || ids.Any(id => !Uuid.IsMatch(id))) return Reject("item_id");
        var currentIndex = ids.IndexOf(snapshot.CurrentItemId);
        if (currentIndex < 0) return Reject("current_item");
        if (currentIndex > MaxPlayedItems || snapshot.Items.Count - 1 - currentIndex > MaxUpcomingItems) return Reject("item_window");
        if (snapshot.ListenedMs < 0 || snapshot.PositionMs < 0) return Reject("negative_time");
        foreach (var item in snapshot.Items)
        {
            if (!IsItemVideoId(item.VideoId)) return Reject("video_id");
            if (item.Title.Length == 0 || item.Title.Length > MaxText) return Reject("title");
            if (item.Artists.Count > MaxArtists || item.Artists.Any(a => !ValidRef(a))) return Reject("artist");
            if (item.Album is not null && !ValidRef(item.Album)) return Reject("album");
            if (item.DurationSeconds is { } d && (d < 0 || d > 24 * 3600)) return Reject("duration");
        }
        switch (snapshot.Source)
        {
            case HandoffSource.Radio radio when !VideoId.IsMatch(radio.SeedVideoId):
            case HandoffSource.Playlist playlist when !PlaylistId.IsMatch(playlist.PlaylistId):
                return Reject("source");
        }
        if (snapshot.RadioPlaylistId is not null && !PlaylistId.IsMatch(snapshot.RadioPlaylistId)) return Reject("radio_playlist");
        if (snapshot.Continuation is not null && (snapshot.Continuation.Length > MaxContinuation || snapshot.Continuation.Any(IsIsoControl))) return Reject("continuation");
        var current = snapshot.Items[currentIndex];
        var limit = current.DurationSeconds is { } seconds ? seconds * 1000L : MaxTimeMs;
        return new HandoffDecodeResult.Ok(
            snapshot with
            {
                Items = snapshot.Items.Select(i => i with { ThumbnailUrl = i.ThumbnailUrl is { } url && AllowedThumbnail(url) ? url : null }).ToList(),
                // 位置と聴いた時間を曲の長さ以下（長さが無ければ24時間以下）に切り詰める（拒否はしない）
                PositionMs = Math.Min(snapshot.PositionMs, limit),
                ListenedMs = Math.Min(snapshot.ListenedMs, limit),
            });
    }

    /// <summary>
    /// 送る側の切り詰め: 今の曲より前は MaxPlayedItems 件、後ろは MaxUpcomingItems 件まで。
    /// </summary>
    /// <returns>（切り詰めた項目, 今の項目の位置, continuation を捨てたか）</returns>
    public static (IReadOnlyList<T> Items, int CurrentIndex, bool DroppedTail) Window<T>(IReadOnlyList<T> items, int currentIndex)
    {
        var start = Math.Max(currentIndex - MaxPlayedItems, 0);
        var end = Math.Min(currentIndex + 1 + MaxUpcomingItems, items.Count);
        return (items.Skip(start).Take(end - start).ToList(), currentIndex - start, end < items.Count);
    }

    private static bool ValidRef(HandoffRef reference) =>
        reference.Name.Length > 0 && reference.Name.Length <= MaxText && (reference.BrowseId is null || BrowseId.IsMatch(reference.BrowseId));

    /// <summary>https・既知の画像ホストだけ（ポート・userinfo の無いもの）。</summary>
    private static bool AllowedThumbnail(string url)
    {
        if (!url.StartsWith("https://", StringComparison.Ordinal) || url.Length > 2048) return false;
        // バックスラッシュ・空白・制御文字は、URL パーサによって区切りや別のホストとして解釈されるので、どこにあっても拒否する
        if (url.Any(c => c == '\\' || char.IsWhiteSpace(c) || IsIsoControl(c))) return false;
        var authority = url["https://".Length..];
        foreach (var separator in new[] { '/', '?', '#' })
        {
            var index = authority.IndexOf(separator);
            if (index >= 0) authority = authority[..index];
        }
        if (!Host.IsMatch(authority)) return false;
        return ThumbnailHosts.Any(h => authority.EndsWith(h, StringComparison.Ordinal));
    }

    /// <summary>Java の Character.isISOControl と同じ（U+0000〜U+001F・U+007F〜U+009F）。</summary>
    internal static bool IsIsoControl(char c) => c <= '\u001f' || (c >= '\u007f' && c <= '\u009f');
}
