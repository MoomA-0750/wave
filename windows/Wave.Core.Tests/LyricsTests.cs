using Wave.Core.Lyrics;

namespace Wave.Core.Tests;

public class LyricsTests
{
    [Fact]
    public void LrcLinesAreSortedAndOffsetApplied()
    {
        var lines = Lrc.Parse("[ar:x]\n[offset:+500]\n[00:02.00][00:10.5]サビ\n[00:01.905]はじめ\nno time");
        Assert.Equal([1405L, 1500L, 10000L], lines.Select(l => l.StartMs));
        Assert.Equal("はじめ", lines[0].Text);
    }

    [Fact]
    public void CurrentIndexLeadsSlightly()
    {
        var lines = new List<LyricLine> { new(1000, "a"), new(2000, "b") };
        Assert.Equal(-1, LyricsTiming.CurrentIndex(lines, 500));
        Assert.Equal(0, LyricsTiming.CurrentIndex(lines, 900));
        Assert.Equal(1, LyricsTiming.CurrentIndex(lines, 5000));
    }

    [Fact]
    public void SearchTitlesDropDecorations()
    {
        Assert.Equal(["夜に駆ける (From \"THE FIRST TAKE\")", "夜に駆ける"], LrclibClient.SearchTitles("夜に駆ける (From \"THE FIRST TAKE\")"));
        Assert.Equal(["Song - Orion", "Song"], LrclibClient.SearchTitles("Song - Orion"));
    }
}
