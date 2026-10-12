using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using Wave.Core.Lyrics;
using Wave.Core.Music;

namespace Wave.Core.Ai;

/// <summary>
/// Claude に送る歌詞と、Claude の答えの中の時刻（Android の core.ai.AiText と同じ）。
/// 時刻付きの歌詞は行ごとに「[01:23] 本文」の形で送り、Claude には歌詞を引くときに同じ形で時刻を添えてもらう
/// （tools/ai-bridge の指示）。答えの中の [mm:ss] は、押すとその位置へ飛べるようにする。
/// </summary>
public static partial class AiText
{
    /// <summary>中継へ送る歌詞。時刻付きなら行ごとに [mm:ss] 本文（空の行は飛ばす）、時刻が無ければ本文のまま。</summary>
    public static string LyricsText(SongLyrics lyrics) => lyrics switch
    {
        SongLyrics.Synced s => string.Join("\n", s.Lines.Where(l => !string.IsNullOrWhiteSpace(l.Text)).Select(l => $"[{Stamp(l.StartMs)}] {l.Text.Trim()}")),
        SongLyrics.Plain p => p.Text,
        _ => "",
    };

    /// <summary>曲の頭からの時刻を mm:ss にする（秒より下は切り捨て。1時間を超えても分で数える）。</summary>
    public static string Stamp(long positionMs)
    {
        var seconds = Math.Max(0, positionMs / 1000);
        return $"{seconds / 60:00}:{seconds % 60:00}";
    }

    /// <summary>答えの中の時刻 [mm:ss] の1つ。Index・Length は括弧を含む位置、Label は括弧の中身。</summary>
    public sealed record Timestamp(int Index, int Length, long PositionMs, string Label);

    [GeneratedRegex(@"\[(\d{1,3}):([0-5]\d)\]")]
    private static partial Regex TimestampPattern();

    /// <summary>答えの中の時刻を、前から順に返す。</summary>
    public static List<Timestamp> Timestamps(string text) =>
        TimestampPattern().Matches(text).Select(m => new Timestamp(m.Index, m.Length,
            (long.Parse(m.Groups[1].Value) * 60 + long.Parse(m.Groups[2].Value)) * 1000, $"{m.Groups[1].Value}:{m.Groups[2].Value}")).ToList();
}

/// <summary>
/// 保存した会話の1つ分の発言。Kind は user・assistant・action・songs・failure。
/// VideoId は assistant だけ: 時刻付きの歌詞を添えて聞いたときの曲（答えの中の時刻は、この曲が鳴っているときだけ押せる）。
/// </summary>
public sealed record AiSavedMessage(string Kind, string Text = "", string? VideoId = null, List<Song>? Songs = null)
{
    public const string User = "user";
    public const string Assistant = "assistant";
    public const string Action = "action";
    public const string SongsKind = "songs";
    public const string Failure = "failure";
}

/// <summary>保存した会話。題名は最初の自分の発言。時刻は Unix ミリ秒。</summary>
public sealed record AiConversation(string Id, string Title, long CreatedAtMs, long UpdatedAtMs, List<AiSavedMessage> Messages);

/// <summary>
/// Claude との会話の履歴（端末の中だけに置き、同期しない。中継は会話を持たない）。新しく話した順に、最大 Max 件。
/// Android の core.ai.AiHistory と同じ決まり。ログインの情報は入れない（発言と曲の情報だけ）。
/// </summary>
public static partial class AiHistory
{
    public const int Version = 1;
    public const int Max = 50;
    public const int TitleMax = 80;

    private sealed record HistoryFile(int Version, List<AiConversation> Conversations);

    private static readonly JsonSerializerOptions Options = new() { DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull };

    [GeneratedRegex(@"\s+")]
    private static partial Regex Spaces();

    /// <summary>題名（最初の自分の発言を1行にして縮める）。自分の発言が無ければ空。</summary>
    public static string Title(IEnumerable<AiSavedMessage> messages)
    {
        var text = messages.FirstOrDefault(m => m.Kind == AiSavedMessage.User)?.Text;
        if (text is null) return "";
        text = Spaces().Replace(text, " ").Trim();
        return text.Length > TitleMax ? text[..(TitleMax - 1)] + "…" : text;
    }

    /// <summary>会話を入れる（同じ id があれば置き換える）。新しく話した順に並べ、Max 件を超えた古いものは落とす。</summary>
    public static List<AiConversation> Upsert(IEnumerable<AiConversation> list, AiConversation conversation) =>
        list.Where(c => c.Id != conversation.Id).Append(conversation).OrderByDescending(c => c.UpdatedAtMs).Take(Max).ToList();

    public static List<AiConversation> Remove(IEnumerable<AiConversation> list, string id) => list.Where(c => c.Id != id).ToList();

    public static string Encode(List<AiConversation> list) => JsonSerializer.Serialize(new HistoryFile(Version, list), Options);

    /// <summary>読めない・形の違うファイルは空にする。</summary>
    public static List<AiConversation> Decode(string text)
    {
        try
        {
            var file = JsonSerializer.Deserialize<HistoryFile>(text, Options);
            return file?.Version == Version && file.Conversations is { } list ? list.OrderByDescending(c => c.UpdatedAtMs).Take(Max).ToList() : [];
        }
        catch (Exception e) when (e is JsonException or NotSupportedException)
        {
            return [];
        }
    }
}
