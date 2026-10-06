using System.Text.Json.Nodes;
using Wave.Core.Stream;

namespace Wave.Core.Tests;

public class StreamTests
{
    [Fact]
    public void OnlyGooglevideoPlaybackUrlsAreAllowed()
    {
        Assert.True(StreamResolver.IsAllowedMediaUrl("https://rr1---sn-x.googlevideo.com/videoplayback?a=1"));
        Assert.False(StreamResolver.IsAllowedMediaUrl("http://rr1---sn-x.googlevideo.com/videoplayback"));
        Assert.False(StreamResolver.IsAllowedMediaUrl("https://evil.com/videoplayback"));
        Assert.False(StreamResolver.IsAllowedMediaUrl("https://x.googlevideo.com.evil.com/videoplayback"));
        Assert.False(StreamResolver.IsAllowedMediaUrl("https://x.googlevideo.com:8443/videoplayback"));
        Assert.False(StreamResolver.IsAllowedMediaUrl("https://x.googlevideo.com/other"));
    }

    [Fact]
    public void UnwantedParametersAreRemovedKeepingOrder()
    {
        Assert.Equal("https://h/videoplayback?a=1&b=%2F", StreamResolver.RemoveQueryParameters("https://h/videoplayback?pot=x&a=1&ump=1&b=%2F", new HashSet<string> { "pot", "ump" }));
        Assert.Equal("https://h/v", StreamResolver.RemoveQueryParameters("https://h/v?pot=1", new HashSet<string> { "pot" }));
    }

    [Fact]
    public void AudioCandidatesPreferAacAndDefaultTrack()
    {
        JsonObject F(int itag, string mime, string? extra = null) =>
            JsonNode.Parse($$"""{"itag":{{itag}},"mimeType":"{{mime}}","url":"https://x.googlevideo.com/videoplayback"{{extra}}}""")!.AsObject();
        var formats = new[]
        {
            F(251, "audio/webm"), F(140, "audio/mp4"), F(141, "audio/mp4", ",\"isDrc\":true"), F(137, "video/mp4"),
            F(250, "audio/webm", ",\"audioTrack\":{\"audioIsDefault\":false}"),
        };
        Assert.Equal([140L, 251L], StreamResolver.AudioCandidates(formats).Select(f => f["itag"]!.GetValue<long>()));
    }

    [Fact]
    public void FaradayRowsAreReadByHashOrAliasAndValidated()
    {
        var text = """{"schemaVersion":1,"players":{"aaaaaaaa":{"sig":"jL(5,5551,INPUT)","nClass":"RJ","sts":20726,"aliases":["bbbbbbbb"]},"cccccccc":{"sig":"alert(1)","nClass":"RJ","sts":1}}}""";
        Assert.Equal(new PlayerCipherConfig("jL(5,5551,INPUT)", "RJ", 20726), PlayerCipherProvider.ParseFaraday(text, "aaaaaaaa"));
        Assert.Equal(20726, PlayerCipherProvider.ParseFaraday(text, "bbbbbbbb")!.SignatureTimestamp);
        Assert.Null(PlayerCipherProvider.ParseFaraday(text, "cccccccc"));
        Assert.Null(PlayerCipherProvider.ParseFaraday(text.Replace("\"schemaVersion\":1", "\"schemaVersion\":2"), "aaaaaaaa"));
    }

    [Fact]
    public void StatsUrlIsRewrittenToMusicHost()
    {
        var target = PlaybackStats.Target("https://s.youtube.com/api/stats/playback?ns=yt&docid=x&list=PL1&cpn=old", "CPN", 12.5)!;
        Assert.Equal("https://music.youtube.com/api/stats/playback?ns=yt&docid=x&prettyPrint=false&ver=2&c=WEB_REMIX&cpn=CPN&cmt=12.5", target);
        Assert.Null(PlaybackStats.Target("https://evil.com/api/stats/playback", "C", 0));
        Assert.Null(PlaybackStats.Target("https://s.youtube.com/other", "C", 0));
    }
}
