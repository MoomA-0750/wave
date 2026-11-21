using Wave.Core.Transfer;
using Xunit;

namespace Wave.Core.Tests;

public class TransferAiTests
{
    private static TransferItem Item() => new()
    {
        Key = "t:風景|motohirohata|ひとみみぼれ",
        Kind = TransferKind.Track,
        Title = "風景",
        Artists = ["Motohiro Hata"],
        Album = "ひとみみぼれ",
        DurationMs = 328_000,
        Isrc = "JPAB01234567",
        Status = MatchStatus.Review,
        Candidates = [new MatchCandidate("cover", "風景 (カラオケ)", "カラオケサウンド", null, 0.29)],
        ChosenId = "cover",
    };

    [Fact]
    public void ThePromptCarriesTheItemAndOnlyTheTransferTools()
    {
        var body = TransferAi.Body(Item(), "日本語", NameAliases.Of(("Motohiro Hata", "秦 基博")));
        var prompt = body["prompt"]!.GetValue<string>();
        Assert.Contains("題: 風景", prompt);
        Assert.Contains("アーティスト: Motohiro Hata", prompt);
        Assert.Contains("「秦 基博」", prompt);
        Assert.Contains("長さ: 328 秒", prompt);
        Assert.Contains("ISRC: JPAB01234567", prompt);
        Assert.Contains("id=cover", prompt);
        Assert.Contains("日本語", prompt);
        var names = body["tools"]!.AsArray().Select(t => t!["name"]!.GetValue<string>()).ToList();
        Assert.Equal(["search_songs", "search_videos", "search_albums", "search_artists", "album_songs", "choose", "not_found"], names);
        Assert.All(body["tools"]!.AsArray(), t => Assert.NotNull(t!["inputSchema"]));
    }

    [Fact]
    public void ASureChoiceMatchesAndAnUnsureOneStaysToCheck()
    {
        var found = new MatchCandidate("atv", "風景", "秦 基博 • ひとみみぼれ", null, 0.7, Wave.Core.Music.Song.KindSong, 328);
        var sure = Item();
        Assert.True(TransferAi.ApplyChoice(sure, found, true, "同じアルバムの公式の音源"));
        Assert.Equal(MatchStatus.Matched, sure.Status);
        Assert.Equal("atv", sure.ChosenId);
        Assert.Equal("atv", sure.Candidates[0].Id);
        Assert.True(sure.AiPicked);
        Assert.Equal("同じアルバムの公式の音源", sure.AiNote);
        Assert.False(sure.Manual);

        var unsure = Item();
        TransferAi.ApplyChoice(unsure, found, false, "長さは合うが版が分からない");
        Assert.Equal(MatchStatus.Review, unsure.Status);
        Assert.Equal("atv", unsure.ChosenId);
    }

    [Fact]
    public void ClaudeDoesNotOverrideTheUsersChoiceAndTheUserOverridesClaude()
    {
        var found = new MatchCandidate("atv", "風景", "秦 基博", null, 0.7);
        var chosen = Item();
        chosen.Choose(chosen.Candidates[0]);
        Assert.False(TransferAi.ApplyChoice(chosen, found, true, "x"));
        Assert.Equal("cover", chosen.ChosenId);

        var picked = Item();
        TransferAi.ApplyChoice(picked, found, true, "x");
        picked.Choose(picked.Candidates[1]);
        Assert.False(picked.AiPicked);
        Assert.True(picked.Manual);

        // 見つからないと答えたら、状態と候補はそのままで、理由だけ残す
        var none = Item();
        TransferAi.ApplyNotFound(none, "YouTube Music には無いアルバム");
        Assert.Equal(MatchStatus.Review, none.Status);
        Assert.Equal("cover", none.ChosenId);
        Assert.True(none.AiTried);
        Assert.Equal("YouTube Music には無いアルバム", none.AiNote);
    }
}
