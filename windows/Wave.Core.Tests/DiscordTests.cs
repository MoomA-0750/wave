using System.IO.Pipes;
using System.Text.Json.Nodes;
using Wave.Core.Discord;
using Xunit;

namespace Wave.Core.Tests;

public class DiscordTests
{
    private static PresenceTrack Track(double position = 30, bool playing = true, string id = "dQw4w9WgXcQ") =>
        new(id, "曲名", "歌手A, 歌手B", "アルバム", "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj", position, 200, playing);

    [Fact]
    public void FramesAreOpLengthAndUtf8Json()
    {
        var frame = DiscordIpc.Encode(DiscordIpc.OpFrame, "{\"a\":\"あ\"}");
        Assert.Equal(new byte[] { 1, 0, 0, 0, 11, 0, 0, 0 }, frame[..8]);
        Assert.Equal("{\"a\":\"あ\"}", System.Text.Encoding.UTF8.GetString(frame, 8, frame.Length - 8));

        var two = frame.Concat(DiscordIpc.Encode(DiscordIpc.OpHandshake, "{}")).ToArray();
        Assert.True(DiscordIpc.TryDecode(two, out var op, out var json, out var used));
        Assert.Equal((1, "{\"a\":\"あ\"}", 19), (op, json, used));
        Assert.True(DiscordIpc.TryDecode(two.AsSpan(used), out op, out json, out _));
        Assert.Equal((0, "{}"), (op, json));
        // 足りなければ待つ
        Assert.False(DiscordIpc.TryDecode(frame.AsSpan(0, 10), out _, out _, out var none));
        Assert.Equal(0, none);
        Assert.Throws<InvalidDataException>(() => DiscordIpc.TryDecode(new byte[] { 1, 0, 0, 0, 255, 255, 255, 127 }, out _, out _, out _));
    }

    [Fact]
    public void HandshakeCarriesVersionAndClientId()
    {
        var node = JsonNode.Parse(DiscordIpc.Handshake("1234567890"))!;
        Assert.Equal(1, node["v"]!.GetValue<int>());
        Assert.Equal("1234567890", node["client_id"]!.GetValue<string>());
    }

    [Fact]
    public void PlayingSongIsAListeningActivityWithTimesImageAndButton()
    {
        var node = JsonNode.Parse(DiscordIpc.SetActivity(42, Track(), 1_000_000_500, "7"))!;
        Assert.Equal("SET_ACTIVITY", node["cmd"]!.GetValue<string>());
        Assert.Equal("7", node["nonce"]!.GetValue<string>());
        Assert.Equal(42, node["args"]!["pid"]!.GetValue<int>());
        var a = node["args"]!["activity"]!;
        Assert.Equal(2, a["type"]!.GetValue<int>());
        Assert.Equal("曲名", a["details"]!.GetValue<string>());
        Assert.Equal("歌手A, 歌手B", a["state"]!.GetValue<string>());
        // 始まり = 今 − 30 秒（秒に丸める）、終わり = 始まり + 200 秒
        Assert.Equal(999_970_000, a["timestamps"]!["start"]!.GetValue<long>());
        Assert.Equal(1_000_170_000, a["timestamps"]!["end"]!.GetValue<long>());
        Assert.Equal("https://lh3.googleusercontent.com/abc=w512-h512-l90-rj", a["assets"]!["large_image"]!.GetValue<string>());
        Assert.Equal("アルバム", a["assets"]!["large_text"]!.GetValue<string>());
        var button = a["buttons"]!.AsArray().Single()!;
        Assert.Equal("YouTube Music で開く", button["label"]!.GetValue<string>());
        Assert.Equal("https://music.youtube.com/watch?v=dQw4w9WgXcQ", button["url"]!.GetValue<string>());
    }

    [Fact]
    public void PausedOrNothingClearsTheActivity()
    {
        Assert.Null(JsonNode.Parse(DiscordIpc.SetActivity(1, Track(playing: false), 0, "1"))!["args"]!["activity"]);
        Assert.Null(JsonNode.Parse(DiscordIpc.SetActivity(1, null, 0, "1"))!["args"]!["activity"]);
    }

    [Fact]
    public void ImagesMustBeShortHttps()
    {
        Assert.Equal("https://i.ytimg.com/vi/x/hqdefault.jpg", DiscordIpc.ImageUrl("https://i.ytimg.com/vi/x/hqdefault.jpg"));
        Assert.Equal("https://x/y.jpg", DiscordIpc.ImageUrl("//x/y.jpg"));
        Assert.Null(DiscordIpc.ImageUrl("http://x/y.jpg"));
        Assert.Null(DiscordIpc.ImageUrl("https://x/" + new string('a', 300)));
        Assert.Null(DiscordIpc.ImageUrl(null));
    }

    [Fact]
    public void ShortAndLongTextsFitDiscordLimits()
    {
        var a = DiscordIpc.Activity(Track() with { Title = "A", Artists = new string('x', 200) }, 0);
        Assert.Equal("A ", a["details"]!.GetValue<string>());
        Assert.Equal(128, a["state"]!.GetValue<string>().Length);
    }

    [Fact]
    public void PlannerSendsOnChangesAndThrottlesSeeks()
    {
        var p = new PresencePlanner();
        const long unix = 1_000_000_000;
        Assert.True(p.ShouldSend(Track(), 0, unix));
        p.Sent(Track(), 0, unix);
        // 同じ曲が進んでいるだけなら送らない
        Assert.False(p.ShouldSend(Track(40), 10_000, unix + 10_000));
        // シーク: 15 秒たつまで待つ
        Assert.False(p.ShouldSend(Track(100), 5_000, unix + 5_000));
        Assert.True(p.ShouldSend(Track(100), 15_000, unix + 15_000));
        // 一時停止・曲の変更はすぐ（ただし前から 2 秒は空ける）
        Assert.False(p.ShouldSend(Track(playing: false), 1_000, unix + 1_000));
        Assert.True(p.ShouldSend(Track(playing: false), 2_000, unix + 2_000));
        Assert.True(p.ShouldSend(Track(id: "other"), 2_000, unix + 2_000));
        p.Sent(Track(playing: false), 2_000, unix + 2_000);
        Assert.False(p.ShouldSend(null, 60_000, unix + 60_000));
        p.Reset();
        Assert.True(p.ShouldSend(null, 60_000, unix + 60_000));
    }

    /// <summary>Discord の代わりの名前付きパイプに、手合わせと SET_ACTIVITY が届く。</summary>
    [Fact]
    public async Task TalksToAFakeDiscordPipe()
    {
        if (!OperatingSystem.IsWindows()) return;
        var baseName = "wave-test-discord-" + Guid.NewGuid().ToString("N") + "-";
        await using var server = new NamedPipeServerStream(baseName + "0", PipeDirection.InOut, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
        using var presence = new DiscordPresence(c => DiscordIpc.ConnectAsync(baseName, c), pid: 4321);
        presence.Update("1111", Track());

        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        await server.WaitForConnectionAsync(timeout.Token);
        var hello = await DiscordIpc.ReadFrameAsync(server, timeout.Token);
        Assert.Equal(DiscordIpc.OpHandshake, hello!.Value.Op);
        Assert.Equal("1111", JsonNode.Parse(hello.Value.Json)!["client_id"]!.GetValue<string>());
        await DiscordIpc.WriteFrameAsync(server, DiscordIpc.OpFrame, "{\"cmd\":\"DISPATCH\",\"evt\":\"READY\",\"data\":{\"v\":1}}", timeout.Token);

        var set = await DiscordIpc.ReadFrameAsync(server, timeout.Token);
        Assert.Equal(DiscordIpc.OpFrame, set!.Value.Op);
        var node = JsonNode.Parse(set.Value.Json)!;
        Assert.Equal(4321, node["args"]!["pid"]!.GetValue<int>());
        Assert.Equal("曲名", node["args"]!["activity"]!["details"]!.GetValue<string>());
        Assert.Equal("connected", presence.Status);

        // 一時停止で消す
        presence.Update("1111", Track(playing: false));
        var clear = await DiscordIpc.ReadFrameAsync(server, timeout.Token);
        Assert.Null(JsonNode.Parse(clear!.Value.Json)!["args"]!["activity"]);

        // 切ると、消してから閉じる
        presence.Update(null, null);
        var last = await DiscordIpc.ReadFrameAsync(server, timeout.Token);
        Assert.Null(JsonNode.Parse(last!.Value.Json)!["args"]!["activity"]);
        Assert.Null(await DiscordIpc.ReadFrameAsync(server, timeout.Token));
    }
}
