using Wave.Core.Connect;

namespace Wave.Core.Tests.Connect;

/// <summary>キューの項目の「ライブ配信か」（isLive）。ライブのときだけ書き、無ければ false として読む（Kotlin・Swift と同じ形）。</summary>
public class HandoffLiveTests
{
    private static HandoffSnapshot WithCurrentLive(bool live)
    {
        var sample = InteropTests.SampleSnapshot();
        return sample with { Items = sample.Items.Select((item, i) => i == 0 ? item with { IsLive = live, DurationSeconds = live ? null : item.DurationSeconds } : item).ToList() };
    }

    [Fact]
    public void LiveItemIsWrittenAsTrueAndReadBack()
    {
        var text = HandoffCodec.Encode(WithCurrentLive(true));
        Assert.Equal(1, CountOf(text, "\"isLive\":true"));
        Assert.DoesNotContain("\"isLive\":false", text);
        var decoded = Assert.IsType<HandoffDecodeResult.Ok>(HandoffCodec.Decode(text, "0f8fad5b-d9cb-469f-a165-70867728950e")).Snapshot;
        Assert.Equal([true, false], decoded.Items.Select(i => i.IsLive));
        Assert.Equal(text, HandoffCodec.Encode(decoded));
    }

    [Fact]
    public void NonLiveItemDoesNotWriteIsLive()
    {
        var text = HandoffCodec.Encode(WithCurrentLive(false));
        Assert.DoesNotContain("isLive", text);
    }

    [Fact]
    public void MissingIsLiveIsReadAsFalse()
    {
        const string item = """{"itemId":"00000000-0000-4000-8000-000000000001","videoId":"abcdefghijA","title":"t","artists":[],"album":null,"durationSeconds":null,"thumbnailUrl":null,"origin":"source"}""";
        var decoded = ConnectJson.Decode<HandoffItem>(item);
        Assert.NotNull(decoded);
        Assert.False(decoded.IsLive);
        var live = ConnectJson.Decode<HandoffItem>(item.Replace("\"origin\":\"source\"", "\"origin\":\"source\",\"isLive\":true"));
        Assert.True(live!.IsLive);
    }

    private static int CountOf(string text, string part) => (text.Length - text.Replace(part, "").Length) / part.Length;
}
