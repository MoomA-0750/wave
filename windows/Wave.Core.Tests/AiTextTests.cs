using Wave.Core.Ai;
using Wave.Core.Lyrics;
using Wave.Core.Music;

namespace Wave.Core.Tests;

public class AiTextTests
{
    [Fact]
    public void SyncedLyricsGetTimestampsAndSkipBlankLines()
    {
        var lyrics = new SongLyrics.Synced([new(12_340, "first"), new(60_000, " "), new(83_999, " second ")], null);
        Assert.Equal("[00:12] first\n[01:23] second", AiText.LyricsText(lyrics));
        Assert.Equal("a\n\nb", AiText.LyricsText(new SongLyrics.Plain("a\n\nb", null)));
        Assert.Equal("61:05", AiText.Stamp(3_665_900));
    }

    [Fact]
    public void FindsTimestampsInAnswer()
    {
        var text = "In [01:23]「x」 and **[2:05]** but not [1:75] or [abc]";
        var found = AiText.Timestamps(text);
        Assert.Equal([83_000L, 125_000L], found.Select(t => t.PositionMs));
        Assert.Equal(["01:23", "2:05"], found.Select(t => t.Label));
        Assert.Equal("[01:23]", text.Substring(found[0].Index, found[0].Length));
    }

    [Fact]
    public void HistoryKeepsNewestFirstAndCaps()
    {
        var list = new List<AiConversation>();
        for (var i = 0; i < AiHistory.Max + 5; i++) list = AiHistory.Upsert(list, Conversation($"c{i}", i));
        Assert.Equal(AiHistory.Max, list.Count);
        Assert.Equal($"c{AiHistory.Max + 4}", list[0].Id);
        list = AiHistory.Upsert(list, Conversation("c10", 1_000));
        Assert.Equal("c10", list[0].Id);
        Assert.Equal(AiHistory.Max - 1, AiHistory.Remove(list, "c10").Count);
    }

    [Fact]
    public void HistoryRoundTripsAndTitles()
    {
        var song = new Song("vid1", "Title", [new ArtistRef("A", "UC1")], null, 200, "https://example.com/t.jpg");
        List<AiSavedMessage> messages =
        [
            new(AiSavedMessage.User, "  what\n does it   mean? "),
            new(AiSavedMessage.Assistant, "At [00:12]", VideoId: "vid1"),
            new(AiSavedMessage.SongsKind, Songs: [song]),
        ];
        Assert.Equal("what does it mean?", AiHistory.Title(messages));
        var decoded = AiHistory.Decode(AiHistory.Encode([new AiConversation("x", "t", 1, 2, messages)]));
        Assert.Single(decoded);
        Assert.Equal("vid1", decoded[0].Messages[1].VideoId);
        Assert.Equal("Title", decoded[0].Messages[2].Songs![0].Title);
        Assert.Empty(AiHistory.Decode("not json"));
        Assert.Empty(AiHistory.Decode("""{"Version":99,"Conversations":[]}"""));
    }

    private static AiConversation Conversation(string id, long updated) => new(id, id, 0, updated, [new(AiSavedMessage.User, id)]);
}
