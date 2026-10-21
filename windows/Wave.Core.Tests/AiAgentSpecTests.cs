using System.Runtime.CompilerServices;
using System.Text.Json.Nodes;
using Wave.Core.Ai;

namespace Wave.Core.Tests;

/// <summary>Android の AiAgentSpecTest・Mac の AiAgentSpecTests と同じ。</summary>
public class AiAgentSpecTests
{
    private static string Here([CallerFilePath] string path = "") => Path.GetDirectoryName(path)!;

    private readonly AiAgentSpec spec = AiAgentSpec.Parse(File.ReadAllText(Path.Combine(Here(), "..", "..", "ai", "agent.json")));

    [Fact]
    public void BodyCarriesTheSystemPromptAndOnlyWavesTools()
    {
        var body = spec.Body("似た曲を探して", [], null, null);
        Assert.StartsWith("あなたは音楽プレイヤー wave", body["system"]!.GetValue<string>());
        Assert.Equal(
            ["now_playing", "get_queue", "search_songs", "liked_songs", "show_songs", "play_songs", "add_to_queue", "list_playlists", "create_playlist", "add_to_playlist"],
            body["tools"]!.AsArray().Select(t => t!["name"]!.GetValue<string>()));
        Assert.Equal("似た曲を探して", body["prompt"]!.GetValue<string>());
    }

    [Fact]
    public void PromptPutsLyricsAndTheConversationBeforeTheTask()
    {
        var prompt = spec.Prompt("続けて", [("user", "意味は？"), ("assistant", "別れの歌です")], null, "[00:12] 行");
        Assert.Equal("<lyrics>\n[00:12] 行\n</lyrics>\n\n<conversation>\n利用者: 意味は？\nアシスタント: 別れの歌です\n</conversation>\n\n続けて", prompt);
    }

    [Fact]
    public void LongLyricsAreCut()
    {
        var prompt = spec.Prompt("x", [], null, new string('あ', 30_000));
        Assert.Equal(20_000, prompt.Count(c => c == 'あ'));
    }
}
