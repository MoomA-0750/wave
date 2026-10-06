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

    [Fact]
    public void VideoCandidatesAreH264UpTo720pHighestFirst()
    {
        JsonObject F(int itag, string mime, int height) =>
            JsonNode.Parse($$"""{"itag":{{itag}},"mimeType":"{{mime}}","height":{{height}},"url":"https://x.googlevideo.com/videoplayback"}""")!.AsObject();
        var picked = StreamResolver.VideoCandidates([
            F(137, "video/mp4; codecs=avc1.640028", 1080),
            F(136, "video/mp4; codecs=avc1.4d401f", 720),
            F(135, "video/mp4; codecs=avc1.4d401e", 480),
            F(247, "video/webm; codecs=vp9", 720),
            F(398, "video/mp4; codecs=av01.0.05M.08", 720),
            F(140, "audio/mp4; codecs=mp4a.40.2", 0),
        ]);
        Assert.Equal([136L, 135L], picked.Select(f => (long)f["itag"]!));
    }

    [Fact]
    public void VideoManifestCarriesTheVideoSize()
    {
        var mpd = DashManifest.Build(new DashInfo(0, 700, 701, 1500, 900000, 0, 200000, "avc1.4d401f", 1280, 720, 30), "video/mp4; codecs=avc1.4d401f", "https://wave.invalid/video/x");
        Assert.Contains("mimeType=\"video/mp4\"", mpd);
        Assert.Contains("width=\"1280\" height=\"720\" frameRate=\"30\"", mpd);
        Assert.DoesNotContain("audioSamplingRate", mpd);
    }

    [Fact]
    public void MusicVideoMapsSongTimeToVideoTime()
    {
        var video = new Wave.Core.Music.MusicVideo("mv", [new(0, 5000, 10000), new(10000, 17000, 20000)]);
        Assert.Equal(5000, video.VideoPositionOf(0));
        Assert.Equal(18000, video.VideoPositionOf(11000));
        Assert.Null(video.VideoPositionOf(40000));
        Assert.Equal(1234, new Wave.Core.Music.MusicVideo("self", []).VideoPositionOf(1234));
    }

    [Fact]
    public void VideoSyncSeeksWhenFarAndNudgesSpeedWhenNear()
    {
        Assert.IsType<VideoSync.Step.Hide>(VideoSync.Decide(null, 0, true, true));
        Assert.Equal(new VideoSync.Step.Seek(5000, true), VideoSync.Decide(5000, 3000, true, true));
        Assert.Equal(new VideoSync.Step.Run(1, true), VideoSync.Decide(5000, 5020, true, true));
        // 映像が 200ms 遅れていれば、少し速く
        Assert.Equal(1.1, ((VideoSync.Step.Run)VideoSync.Decide(5000, 4800, true, true)).Speed, 3);
        Assert.Equal(new VideoSync.Step.Run(1, true), VideoSync.Decide(5000, 3000, true, false));
        Assert.Equal(new VideoSync.Step.Seek(5000, false), VideoSync.Decide(5000, 4000, false, true));
    }
}
