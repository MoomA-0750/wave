using System.Text;
using System.Text.Json.Nodes;
using Wave.Core.Music;

namespace Wave.Core.Ai;

/// <summary>
/// 中継（ai-bridge の /v1/run）へ送る頼み（Android の AiAgentSpec・Mac/iOS の AiAgentSpec と同じ）。
/// システムプロンプトと道具の定義は ai/agent.json（Android・Mac/iOS と同じファイル。Wave.App に埋め込む）。
/// 中継はアプリのことを知らないので、今の曲・歌詞・それまでのやりとりは頼みの文に入れる。
/// </summary>
public sealed class AiAgentSpec
{
    private readonly string system;
    private readonly JsonArray tools;
    private readonly int maxLyricsChars;

    private AiAgentSpec(string system, JsonArray tools, int maxLyricsChars)
    {
        this.system = system;
        this.tools = tools;
        this.maxLyricsChars = maxLyricsChars;
    }

    public static AiAgentSpec Parse(string json)
    {
        var root = JsonNode.Parse(json) as JsonObject ?? throw new FormatException("agent.json");
        return new AiAgentSpec(
            root["system"]?.GetValue<string>() ?? throw new FormatException("agent.json: system"),
            root["tools"] as JsonArray ?? throw new FormatException("agent.json: tools"),
            root["max_lyrics_chars"]?.GetValue<int>() ?? 20_000);
    }

    /// <summary>/v1/run の本文。history は (role, content) で、role は "user" か "assistant"。</summary>
    public JsonObject Body(string task, IReadOnlyList<(string Role, string Content)> history, Song? song, string? lyrics) => new()
    {
        ["system"] = system,
        ["prompt"] = Prompt(task, history, song, lyrics),
        ["tools"] = tools.DeepClone(),
    };

    public string Prompt(string task, IReadOnlyList<(string Role, string Content)> history, Song? song, string? lyrics)
    {
        var out_ = new StringBuilder();
        if (song is not null)
        {
            out_.Append("<now_playing>\n");
            out_.Append("題名: ").Append(song.Title).Append('\n');
            out_.Append("アーティスト: ").Append(song.ArtistsText).Append('\n');
            out_.Append("アルバム: ").Append(song.Album?.Title ?? "").Append('\n');
            out_.Append("video_id: ").Append(song.VideoId).Append('\n');
            out_.Append("</now_playing>\n\n");
        }
        if (!string.IsNullOrEmpty(lyrics)) out_.Append("<lyrics>\n").Append(lyrics.Length > maxLyricsChars ? lyrics[..maxLyricsChars] : lyrics).Append("\n</lyrics>\n\n");
        if (history.Count > 0)
        {
            out_.Append("<conversation>\n");
            foreach (var (role, content) in history) out_.Append(role == "user" ? "利用者: " : "アシスタント: ").Append(content).Append('\n');
            out_.Append("</conversation>\n\n");
        }
        return out_.Append(task).ToString();
    }
}
