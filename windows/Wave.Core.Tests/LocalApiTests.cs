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
        public IReadOnlyList<TrackView> Upcoming = [Track("b"), Track("c")];
        public bool LoggedIn = true;
        public int PlaybackReads;

        public Task<PlaybackView> PlaybackAsync()
        {
            Interlocked.Increment(ref PlaybackReads);
            return Task.FromResult(Now);
        }

        public Task<IReadOnlyList<TrackView>> QueueAsync() => Task.FromResult(Upcoming);
        public Task<IReadOnlyList<DeviceView>> DevicesAsync() => Task.FromResult<IReadOnlyList<DeviceView>>([new("pc1", "TO-FU", "pc", true, true, true), new("ph", "Pixel", "phone", true, false)]);

        public Task<Reply> ControlAsync(ControlRequest request)
        {
            Calls.Add($"control {request.Action} {request.PositionMs} {request.VolumePercent} {request.Shuffle} {request.Repeat}".TrimEnd());
            return Task.FromResult(Reply.Ok);
        }

        public Task<Reply> TransferAsync(string deviceId)
        {
            Calls.Add("transfer " + deviceId);
            return Task.FromResult(deviceId == "ph" ? new Reply(DeviceId: "ph") : Reply.NotFound);
        }

        public Task<Reply> PlayAsync(PlayRequest request)
        {
            Calls.Add($"play {request.PlaylistId ?? request.AlbumBrowseId ?? request.ArtistBrowseId ?? request.VideoId} start={request.StartVideoId} shuffle={request.Shuffle}");
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

        public Task<Reply> RateAsync(string? videoId, bool liked)
        {
            Calls.Add($"rate {videoId ?? "now"} {liked}");
            return Task.FromResult(Reply.Ok);
        }

        public Task<LyricsView> LyricsAsync() => Task.FromResult(new LyricsView("a", "found", true, "LRCLIB", [new(1000, "一行目"), new(4000, "二行目")], null));
        public Task<ThemeView> ThemeAsync() => Task.FromResult(new ThemeView(true, "#F7630C", "#F99A5B", "#FAB487", "#FCCEB0", "#C75000", "#963C00", "#642800"));

        public Task<ApiResult<IReadOnlyList<object>>> SearchAsync(string query, SearchKind kind)
        {
            Calls.Add($"search {query} {kind} {InnerTubeClient.HlOverride}".TrimEnd());
            IReadOnlyList<object> found = kind switch
            {
                SearchKind.Album => [new PlaylistView("MPREb_1", "アルバム", null, null)],
                SearchKind.Artist => [new ArtistView("UC1", "歌手", null, null)],
                _ => [Track("s")],
            };
            return Task.FromResult(ApiResult<IReadOnlyList<object>>.Ok(found));
        }

        public Task<ApiResult<IReadOnlyList<PlaylistView>>> PlaylistsAsync() =>
            Task.FromResult(LoggedIn ? ApiResult<IReadOnlyList<PlaylistView>>.Ok([new("PL1", "お気に入り", null, null)]) : ApiResult<IReadOnlyList<PlaylistView>>.Failed(FailureKind.NotLoggedIn));

        public Task<ApiResult<IReadOnlyList<PlaylistView>>> LibraryAlbumsAsync() => Task.FromResult(ApiResult<IReadOnlyList<PlaylistView>>.Ok([new("MPREb_2", "アルバム", null, null)]));
        public Task<ApiResult<IReadOnlyList<ArtistView>>> LibraryArtistsAsync() => Task.FromResult(ApiResult<IReadOnlyList<ArtistView>>.Ok([new("UC2", "歌手", null, null)]));
        public Task<ApiResult<IReadOnlyList<TrackView>>> PlaylistTracksAsync(string id) => Task.FromResult(ApiResult<IReadOnlyList<TrackView>>.Ok([Track(id + "1")]));
        public Task<ApiResult<AlbumView>> AlbumAsync(string browseId) => Task.FromResult(ApiResult<AlbumView>.Ok(new AlbumView(browseId, "アルバム", "歌手", null, "OLAK5uy_1", [Track("t1"), Track("t2")])));

        public Task<ApiResult<ArtistPageView>> ArtistAsync(string browseId) =>
            Task.FromResult(ApiResult<ArtistPageView>.Ok(new ArtistPageView(browseId, "歌手", null, [Track("top")], "PLall", [new("Albums", [new("MPREb_3", "アルバム", "2024", null)])])));

        public Task<ApiResult<IReadOnlyList<ShelfView>>> SuggestionsAsync(string? mood)
        {
            if (mood is null) return Task.FromResult(ApiResult<IReadOnlyList<ShelfView>>.Failed(FailureKind.Network));
            Calls.Add("suggestions " + mood);
            IReadOnlyList<ShelfView> shelves =
            [
                new("Quick picks", [Track("q")], [new("PLq", "まとめ", null, null)], [],
                    [new("artist", "UC1", "歌手", null, null), new("track", "q", "曲q", "歌手", null), new("playlist", "PLq", "まとめ", null, null)]),
            ];
            return Task.FromResult(ApiResult<IReadOnlyList<ShelfView>>.Ok(shelves));
        }

        public Task<ApiResult<IReadOnlyList<ItemView>>> RecentAsync() =>
            Task.FromResult(ApiResult<IReadOnlyList<ItemView>>.Ok([new("playlist", "LM", "Liked Music", null, null), new("artist", "UC1", "歌手", null, null)]));

        public Task<ApiResult<IReadOnlyList<MoodView>>> MoodsAsync() => Task.FromResult(ApiResult<IReadOnlyList<MoodView>>.Ok([new("Relax", "ggMPOg1uX1JO"), new("Workout", "ggMPOg1uX1Jw")]));
    }

    private static TrackView Track(string id) => new(id, "曲" + id, "歌手", null, null, 200_000, false);

    private static PlaybackView Playback(bool playing, long progress, string video = "a", DateTimeOffset? at = null, int? volume = 50, bool shuffle = false, string? like = null, string? title = null) =>
        new(true, true, playing, "off", Track(video) with { LikeStatus = like, Title = title ?? "曲" + video }, progress, (at ?? DateTimeOffset.UtcNow).ToString("O"), new("pc1", "TO-FU", "pc", true, true, true), volume, shuffle);

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
        foreach (var key in new[] { "joined", "loggedIn", "isPlaying", "repeat", "track", "progressMs", "observedAt", "device", "volumePercent", "shuffle" })
            Assert.True(o.ContainsKey(key), key);
        Assert.Equal("a", o["track"]!["videoId"]!.GetValue<string>());
        Assert.Null(o["track"]!["album"]);
        Assert.True(o["track"]!.AsObject().ContainsKey("album"));
        Assert.True(o["device"]!["self"]!.GetValue<bool>());
        Assert.True(o["track"]!.AsObject().ContainsKey("likeStatus"));
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
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/control", """{"action":"shuffle","shuffle":true}""")).Status);
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/control", """{"action":"shuffle"}""")).Status);
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/control", """{"action":"repeat","repeat":"track"}""")).Status);
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/control", """{"action":"repeat","repeat":"one"}""")).Status);
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/control", "not json")).Status);
        Assert.Equal(["control pause", "control seek 61000", "control volume  30", "control shuffle   True", "control repeat    track"], backend.Calls);
    }

    [Fact]
    public async Task PlayQueueJumpAndTransfer()
    {
        var play = await Post("/v1/play", """{"playlistId":"PLabc","startVideoId":"x1","shuffle":true}""");
        Assert.Equal(HttpStatusCode.OK, play.Status);
        Assert.Equal("pc1", play.Body!["deviceId"]!.GetValue<string>());
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/play", "{}")).Status);
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/play", """{"artistBrowseId":"UCxyz","shuffle":true}""")).Status);
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/play", """{"videoId":"../x"}""")).Status);
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/queue", """{"videoIds":["v1","v2"],"next":true}""")).Status);
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/queue", """{"videoIds":[]}""")).Status);
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/queue/jump", """{"index":1,"videoId":"c"}""")).Status);
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/queue/jump", """{"index":-1}""")).Status);
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/transfer", """{"deviceId":"ph"}""")).Status);
        Assert.Equal(HttpStatusCode.NotFound, (await Post("/v1/transfer", """{"deviceId":"zz"}""")).Status);
        Assert.Equal(["play PLabc start=x1 shuffle=True", "play UCxyz start= shuffle=True", "queue v1,v2 next=True", "jump 1 c", "transfer ph", "transfer zz"], backend.Calls);
    }

    [Fact]
    public async Task LibraryFailuresBecomeNamedErrors()
    {
        Assert.Equal("s", (await Get("/v1/search?q=%E7%B1%B3%E6%B4%A5")).Body![0]!["videoId"]!.GetValue<string>());
        Assert.Equal("search 米津 Song", backend.Calls.Single());
        Assert.Equal(HttpStatusCode.BadRequest, (await Get("/v1/search?q=")).Status);
        Assert.Equal("LM1", (await Get("/v1/playlists/LM/tracks")).Body![0]!["videoId"]!.GetValue<string>());
        backend.LoggedIn = false;
        var playlists = await Get("/v1/playlists");
        Assert.Equal((HttpStatusCode.Unauthorized, "login_required"), (playlists.Status, playlists.Body!["error"]!.GetValue<string>()));
        var suggestions = await Get("/v1/suggestions");
        Assert.Equal((HttpStatusCode.BadGateway, "network"), (suggestions.Status, suggestions.Body!["error"]!.GetValue<string>()));
    }

    /// <summary>次の出来事（event: と data: の2行と空の行）を読む。keepalive は読み飛ばす。</summary>
    private static async Task<(string Name, string Data)> NextEvent(StreamReader reader)
    {
        while (true)
        {
            var read = reader.ReadLineAsync();
            Assert.Same(read, await Task.WhenAny(read, Task.Delay(5000)));
            var line = await read;
            if (line is null) throw new EndOfStreamException();
            if (!line.StartsWith("event: ")) continue;
            var data = (await reader.ReadLineAsync())!;
            await reader.ReadLineAsync();
            return (line["event: ".Length..], data["data: ".Length..]);
        }
    }

    [Fact]
    public async Task EventsStreamPlaybackAndQueueWhenTheyChange()
    {
        using var stream = await http.GetStreamAsync("/v1/events");
        using var reader = new StreamReader(stream);
        var first = await NextEvent(reader);
        Assert.Equal("playback", first.Name);
        Assert.Contains("\"videoId\":\"a\"", first.Data);
        var queue = await NextEvent(reader);
        Assert.Equal("queue", queue.Name);
        Assert.Contains("\"videoId\":\"c\"", queue.Data);
        backend.Now = Playback(false, 31_000, "b");
        var next = await NextEvent(reader);
        Assert.Equal("playback", next.Name);
        Assert.Contains("\"videoId\":\"b\"", next.Data);
        backend.Upcoming = [Track("d")];
        var changed = await NextEvent(reader);
        Assert.Equal("queue", changed.Name);
        Assert.Contains("\"videoId\":\"d\"", changed.Data);
    }

    [Fact]
    public async Task OneWatcherServesEveryListener()
    {
        using var one = await http.GetStreamAsync("/v1/events");
        using var two = await http.GetStreamAsync("/v1/events");
        using var readerOne = new StreamReader(one);
        using var readerTwo = new StreamReader(two);
        await NextEvent(readerOne);
        await NextEvent(readerTwo);
        // 見張りが1つなら、2つつないでいても読む回数は 0.5 秒に1回くらい（つないだときの1回ずつを除く）
        var before = backend.PlaybackReads;
        await Task.Delay(2000);
        Assert.InRange(backend.PlaybackReads - before, 2, 6);
        backend.Now = Playback(true, 30_000, "z");
        Assert.Contains("\"z\"", (await NextEvent(readerOne) is var e1 && e1.Name == "queue" ? await NextEvent(readerOne) : e1).Data);
        Assert.Contains("\"z\"", (await NextEvent(readerTwo) is var e2 && e2.Name == "queue" ? await NextEvent(readerTwo) : e2).Data);
    }

    [Fact]
    public async Task ClosingWaveEndsTheStreamQuietly()
    {
        var logs = new List<string>();
        using var own = LocalApiServer.Start(backend, 19400 + Random.Shared.Next(0, 500), logs.Add)!;
        using var client = new HttpClient { BaseAddress = new Uri(own.Url) };
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", own.Token);
        using var stream = await client.GetStreamAsync("/v1/events");
        using var reader = new StreamReader(stream);
        await NextEvent(reader);
        own.Dispose();
        await Task.Delay(1000);
        Assert.DoesNotContain(logs, l => l.Contains("failed"));
    }

    [Fact]
    public async Task RatingLyricsAndTheme()
    {
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/rate", """{"likeStatus":"like"}""")).Status);
        Assert.Equal(HttpStatusCode.OK, (await Post("/v1/rate", """{"videoId":"abcdefghijk","likeStatus":"indifferent"}""")).Status);
        Assert.Equal(HttpStatusCode.BadRequest, (await Post("/v1/rate", """{"likeStatus":"love"}""")).Status);
        Assert.Equal(["rate now True", "rate abcdefghijk False"], backend.Calls);
        var lyrics = (await Get("/v1/lyrics")).Body!;
        Assert.Equal(("found", true), (lyrics["state"]!.GetValue<string>(), lyrics["synced"]!.GetValue<bool>()));
        Assert.Equal(4000, lyrics["lines"]![1]!["timeMs"]!.GetValue<long>());
        var theme = (await Get("/v1/theme")).Body!;
        Assert.Matches("^#[0-9A-F]{6}$", theme["accent"]!.GetValue<string>());
        Assert.True(theme["dark"]!.GetValue<bool>());
    }

    [Fact]
    public async Task LibraryAlbumsArtistsAndMoods()
    {
        Assert.Equal("MPREb_1", (await Get("/v1/search?q=x&type=album")).Body![0]!["id"]!.GetValue<string>());
        Assert.Equal("UC1", (await Get("/v1/search?q=x&type=artist")).Body![0]!["id"]!.GetValue<string>());
        Assert.Equal(HttpStatusCode.OK, (await Get("/v1/search?q=x&type=live")).Status);
        Assert.Equal(HttpStatusCode.BadRequest, (await Get("/v1/search?q=x&type=podcast")).Status);
        Assert.Equal("MPREb_2", (await Get("/v1/library/albums")).Body![0]!["id"]!.GetValue<string>());
        Assert.Equal("UC2", (await Get("/v1/library/artists")).Body![0]!["id"]!.GetValue<string>());
        var album = (await Get("/v1/albums/MPREb_9")).Body!;
        Assert.Equal(("MPREb_9", "OLAK5uy_1", 2), (album["id"]!.GetValue<string>(), album["playlistId"]!.GetValue<string>(), album["tracks"]!.AsArray().Count));
        Assert.Equal("t2", (await Get("/v1/albums/MPREb_9/tracks")).Body![1]!["videoId"]!.GetValue<string>());
        var artist = (await Get("/v1/artists/UC9")).Body!;
        Assert.Equal(("PLall", "MPREb_3"), (artist["allSongsPlaylistId"]!.GetValue<string>(), artist["albumShelves"]![0]!["albums"]![0]!["id"]!.GetValue<string>()));
        Assert.Equal(HttpStatusCode.NotFound, (await Get("/v1/artists/UC9/x")).Status);
        Assert.Equal("Relax", (await Get("/v1/moods")).Body![0]!["title"]!.GetValue<string>());
        Assert.Equal("artist", (await Get("/v1/recent")).Body![1]!["type"]!.GetValue<string>());
        var home = (await Get("/v1/suggestions?mood=ggMPOg1uX1JO")).Body!;
        var items = home[0]!["items"]!.AsArray();
        Assert.Equal(["artist", "track", "playlist"], items.Select(i => i!["type"]!.GetValue<string>()));
        Assert.Equal(HttpStatusCode.BadRequest, (await Get("/v1/suggestions?mood=%3Cx%3E")).Status);
    }

    [Fact]
    public async Task LanguageIsOnlyForThatRequest()
    {
        await Get("/v1/search?q=x&hl=en");
        await Get("/v1/search?q=y");
        Assert.Equal(["search x Song en", "search y Song"], backend.Calls);
        Assert.Equal(HttpStatusCode.BadRequest, (await Get("/v1/search?q=x&hl=e%3Bn")).Status);
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
        // ライブの曲名が変わった・評価・シャッフル
        Assert.True(LocalApiServer.Changed(before, Playback(true, 10_000, at: t0, title: "次の曲")));
        Assert.True(LocalApiServer.Changed(before, Playback(true, 10_000, at: t0, like: "like")));
        Assert.True(LocalApiServer.Changed(before, Playback(true, 10_000, at: t0, shuffle: true)));
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
