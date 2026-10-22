using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;
using Wave.Core.LocalApi;
using Wave.Core.Music;
using Wave.Core.Net;
using Xunit;

namespace Wave.Core.Tests;

/// <summary>同じ PC のほかのアプリ向けの口（docs/local-api.md）。形は wave-api と同じ。</summary>
public class LocalApiTests : IDisposable
{
    private sealed class FakeBackend : ILocalApiBackend
    {
        public readonly List<string> Calls = [];
        public PlaybackView Now = Playback(true, 30_000);
        public bool LoggedIn = true;

        public Task<PlaybackView> PlaybackAsync() => Task.FromResult(Now);
        public Task<IReadOnlyList<TrackView>> QueueAsync() => Task.FromResult<IReadOnlyList<TrackView>>([Track("b"), Track("c")]);
        public Task<IReadOnlyList<DeviceView>> DevicesAsync() => Task.FromResult<IReadOnlyList<DeviceView>>([new("pc1", "TO-FU", "pc", true, true, true), new("ph", "Pixel", "phone", true, false)]);

        public Task<Reply> ControlAsync(string action, long? positionMs, int? volumePercent)
        {
            Calls.Add($"control {action} {positionMs} {volumePercent}".TrimEnd());
            return Task.FromResult(Reply.Ok);
        }

        public Task<Reply> TransferAsync(string deviceId)
        {
            Calls.Add("transfer " + deviceId);
            return Task.FromResult(deviceId == "ph" ? new Reply(DeviceId: "ph") : Reply.NotFound);
        }

        public Task<Reply> PlayAsync(PlayRequest request)
        {
            Calls.Add($"play {request.PlaylistId ?? request.AlbumBrowseId ?? request.VideoId} start={request.StartVideoId} shuffle={request.Shuffle}");
            return Task.FromResult(new Reply(DeviceId: "pc1"));
        }

        public Task<Reply> QueueAddAsync(IReadOnlyList<string> videoIds, bool next)
        {
            Calls.Add($"queue {string.Join(",", videoIds)} next={next}");
            return Task.FromResult(Reply.Ok);
        }

        public Task<Reply> JumpAsync(int? index, string? videoId)
        {
            Calls.Add($"jump {index} {videoId}".TrimEnd());
            return Task.FromResult(new Reply(DeviceId: "pc1"));
        }

        public Task<ApiResult<IReadOnlyList<TrackView>>> SearchAsync(string query)
        {
            Calls.Add("search " + query);
            return Task.FromResult(ApiResult<IReadOnlyList<TrackView>>.Ok([Track("s")]));
        }

        public Task<ApiResult<IReadOnlyList<PlaylistView>>> PlaylistsAsync() =>
            Task.FromResult(LoggedIn ? ApiResult<IReadOnlyList<PlaylistView>>.Ok([new("PL1", "お気に入り", null, null)]) : ApiResult<IReadOnlyList<PlaylistView>>.Failed(FailureKind.NotLoggedIn));

        public Task<ApiResult<IReadOnlyList<TrackView>>> PlaylistTracksAsync(string id) => Task.FromResult(ApiResult<IReadOnlyList<TrackView>>.Ok([Track(id + "1")]));
        public Task<ApiResult<IReadOnlyList<ShelfView>>> SuggestionsAsync() => Task.FromResult(ApiResult<IReadOnlyList<ShelfView>>.Failed(FailureKind.Network));
    }

    private static TrackView Track(string id) => new(id, "曲" + id, "歌手", null, null, 200_000, false);

    private static PlaybackView Playback(bool playing, long progress, string video = "a", DateTimeOffset? at = null, int? volume = 50) =>
        new(true, true, playing, "off", Track(video), progress, (at ?? DateTimeOffset.UtcNow).ToString("O"), new("pc1", "TO-FU", "pc", true, true, true), volume);

    private readonly FakeBackend backend = new();
    private readonly LocalApiServer server;
    private readonly HttpClient http = new();

    public LocalApiTests()
    {
        // テストが並んで走っても重ならないよう、番号をずらす
        server = LocalApiServer.Start(backend, 18794 + Random.Shared.Next(0, 500))!;
        http.BaseAddress = new Uri(server.Url);
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", server.Token);
    }

    public void Dispose()
    {
        server.Dispose();
        http.Dispose();
    }

    private async Task<(HttpStatusCode Status, JsonNode? Body)> Get(string path)
    {
        using var response = await http.GetAsync(path);
        return (response.StatusCode, JsonNode.Parse(await response.Content.ReadAsStringAsync()));
    }

    private async Task<(HttpStatusCode Status, JsonNode? Body)> Post(string path, string json)
    {
        using var response = await http.PostAsync(path, new StringContent(json, Encoding.UTF8, "application/json"));
        return (response.StatusCode, JsonNode.Parse(await response.Content.ReadAsStringAsync()));
    }

    [Fact]
    public async Task TokenIsRequiredExceptHealth()
    {
        using var bare = new HttpClient { BaseAddress = new Uri(server.Url) };
        Assert.Equal(HttpStatusCode.OK, (await bare.GetAsync("/v1/health")).StatusCode);
        using var none = await bare.GetAsync("/v1/playback");
        Assert.Equal(HttpStatusCode.Unauthorized, none.StatusCode);
        Assert.Equal("token_required", JsonNode.Parse(await none.Content.ReadAsStringAsync())!["error"]!.GetValue<string>());
        bare.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", server.Token + "x");
        Assert.Equal(HttpStatusCode.Unauthorized, (await bare.GetAsync("/v1/playback")).StatusCode);
    }

    [Fact]
    public async Task BrowserPagesAreRefused()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, "/v1/playback");
        request.Headers.Add("Origin", "https://example.com");
        using var response = await http.SendAsync(request);
        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task PlaybackHasTheSameShapeAsWaveApi()
    {
        var (status, body) = await Get("/v1/playback");
        Assert.Equal(HttpStatusCode.OK, status);
        var o = body!.AsObject();
        foreach (var key in new[] { "joined", "loggedIn", "isPlaying", "repeat", "track", "progressMs", "observedAt", "device", "volumePercent" })
            Assert.True(o.ContainsKey(key), key);
        Assert.Equal("a", o["track"]!["videoId"]!.GetValue<string>());
        Assert.Null(o["track"]!["album"]);
        Assert.True(o["track"]!.AsObject().ContainsKey("album"));
        Assert.True(o["device"]!["self"]!.GetValue<bool>());
        Assert.Equal(2, (await Get("/v1/queue")).Body!.AsArray().Count);
        Assert.Equal("Pixel", (await Get("/v1/devices")).Body![1]!["name"]!.GetValue<string>());
    }

    [Fact]
    public async Task ControlsAreCheckedAndPassedOn()
    {
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/control", """{"action":"pause"}""")).Status);
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/control", """{"action":"seek","positionMs":61000}""")).Status);
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/control", """{"action":"volume","volumePercent":30}""")).Status);
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/control", """{"action":"volume","volumePercent":130}""")).Status);
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/control", """{"action":"seek"}""")).Status);
        var shuffle = await Post("/v1/control", """{"action":"shuffle"}""");
        Assert.Equal("unsupported", shuffle.Body!["error"]!.GetValue<string>());
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/control", "not json")).Status);
        Assert.Equal(["control pause", "control seek 61000", "control volume  30"], backend.Calls);
    }

    [Fact]
    public async Task PlayQueueJumpAndTransfer()
    {
        var play = await Post("/v1/play", """{"playlistId":"PLabc","startVideoId":"x1","shuffle":true}""");
        Assert.Equal(HttpStatusCode.OK, play.Status);
        Assert.Equal("pc1", play.Body!["deviceId"]!.GetValue<string>());
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/play", "{}")).Status);
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/play", """{"videoId":"../x"}""")).Status);
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/queue", """{"videoIds":["v1","v2"],"next":true}""")).Status);
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/queue", """{"videoIds":[]}""")).Status);
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/queue/jump", """{"index":1,"videoId":"c"}""")).Status);
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/queue/jump", """{"index":-1}""")).Status);
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/transfer", """{"deviceId":"ph"}""")).Status);
        Assert.Equal(HttpStatusCode.NotFound, (await Post("/v1/transfer", """{"deviceId":"zz"}""")).Status);
        Assert.Equal(["play PLabc start=x1 shuffle=True", "queue v1,v2 next=True", "jump 1 c", "transfer ph", "transfer zz"], backend.Calls);
    }

    [Fact]
    public async Task LibraryFailuresBecomeNamedErrors()
    {
        Assert.Equal("s", (await Get("/v1/search?q=%E7%B1%B3%E6%B4%A5")).Body![0]!["videoId"]!.GetValue<string>());
        Assert.Equal("search 米津", backend.Calls.Single());
        Assert.Equal(HttpStatusCode.BadRequest, (await Get("/v1/search?q=")).Status);
        Assert.Equal("LM1", (await Get("/v1/playlists/LM/tracks")).Body![0]!["videoId"]!.GetValue<string>());
        backend.LoggedIn = false;
        var playlists = await Get("/v1/playlists");
        Assert.Equal((HttpStatusCode.Unauthorized, "login_required"), (playlists.Status, playlists.Body!["error"]!.GetValue<string>()));
        var suggestions = await Get("/v1/suggestions");
        Assert.Equal((HttpStatusCode.BadGateway, "network"), (suggestions.Status, suggestions.Body!["error"]!.GetValue<string>()));
    }

    [Fact]
    public async Task EventsStreamPlaybackWhenItChanges()
    {
        using var stream = await http.GetStreamAsync("/v1/events");
        using var reader = new StreamReader(stream);
        Assert.Equal("event: playback", await reader.ReadLineAsync());
        Assert.Contains("\"videoId\":\"a\"", await reader.ReadLineAsync());
        await reader.ReadLineAsync();
        backend.Now = Playback(false, 31_000, "b");
        var next = reader.ReadLineAsync();
        Assert.Same(next, await Task.WhenAny(next, Task.Delay(5000)));
        Assert.Equal("event: playback", await next);
        Assert.Contains("\"videoId\":\"b\"", await reader.ReadLineAsync());
    }

    [Fact]
    public void OnlySeeksAndStateChangesAreWorthStreaming()
    {
        var t0 = DateTimeOffset.UtcNow;
        var before = Playback(true, 10_000, at: t0);
        // 再生中に時間どおり進んだだけなら流さない
        Assert.False(LocalApiServer.Changed(before, Playback(true, 12_000, at: t0.AddSeconds(2))));
        // シーク
        Assert.True(LocalApiServer.Changed(before, Playback(true, 60_000, at: t0.AddSeconds(2))));
        // 止まっている間は位置が動かない
        Assert.False(LocalApiServer.Changed(Playback(false, 10_000, at: t0), Playback(false, 10_000, at: t0.AddSeconds(5))));
        Assert.True(LocalApiServer.Changed(before, Playback(false, 10_000, at: t0)));
        Assert.True(LocalApiServer.Changed(before, Playback(true, 10_000, "z", t0)));
        Assert.True(LocalApiServer.Changed(before, Playback(true, 10_000, at: t0, volume: 51)));
    }

    [Fact]
    public void TheFileTellsWhereToConnectAndKeepsTheExeAfterStopping()
    {
        var dir = Directory.CreateTempSubdirectory().FullName;
        LocalApiFile.Write(dir, new(server.Url, server.Token, 1234, @"C:\wave\Wave.App.exe"));
        var read = LocalApiFile.Read(dir)!;
        Assert.Equal((server.Url, server.Token, 1234), (read.Url, read.Token, read.Pid));
        Assert.Contains("\"token\"", File.ReadAllText(Path.Combine(dir, LocalApiFile.Name)));
        LocalApiFile.Clear(dir, @"C:\wave\Wave.App.exe");
        read = LocalApiFile.Read(dir)!;
        Assert.Equal(("", null, 0, @"C:\wave\Wave.App.exe"), (read.Url, read.Token, read.Pid, read.Exe));
        Directory.Delete(dir, true);
    }
}
