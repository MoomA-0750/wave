using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Wave.Core.Music;
using Wave.Core.Net;

namespace Wave.Core.LocalApi;

// 同じ PC のほかのアプリ向けの口（docs/local-api.md）。形は wave-api（docs/wave-api.md）と同じにし、
// どちらにもつなげるようにする。違いは、ここでは「この PC」も鳴らす先に選べることと、ログインは PC の wave のものを使うこと。

public sealed record TrackView(string VideoId, string Title, string Artist, string? Album, string? ArtUrl, long? DurationMs, bool IsLive)
{
    public static TrackView Of(Song song, int? artPx = null) =>
        new(song.VideoId, song.Title, song.ArtistsText, song.Album?.Title,
            song.ThumbnailUrl is { } url && artPx is { } px ? ArtworkUrl.Sized(url, px) : song.ThumbnailUrl,
            song.IsLive ? null : song.DurationSeconds * 1000L, song.IsLive);
}

/// <param name="Self">この PC の wave（鳴らす先に選べる。wave-api の self と違う）。</param>
public sealed record DeviceView(string Id, string Name, string? Kind, bool Online, bool Active, bool Self = false);

public sealed record PlaybackView(bool Joined, bool LoggedIn, bool IsPlaying, string Repeat, TrackView? Track, long? ProgressMs, string ObservedAt, DeviceView? Device, int? VolumePercent);

public sealed record PlaylistView(string Id, string Name, string? Subtitle, string? ArtUrl);

/// <param name="Albums">wave-api には無い（PC の wave だけ）。id はアルバムの browseId。</param>
public sealed record ShelfView(string Title, IReadOnlyList<TrackView> Tracks, IReadOnlyList<PlaylistView> Playlists, IReadOnlyList<PlaylistView> Albums);

public sealed record PlayRequest(string? VideoId = null, string? PlaylistId = null, string? AlbumBrowseId = null, string? StartVideoId = null, bool Shuffle = false, string? DeviceId = null);

/// <summary>操作の答え。Error が null なら 200 で {"ok":true,"deviceId"}。</summary>
public sealed record Reply(int Status = 200, string? Error = null, string? DeviceId = null)
{
    public static readonly Reply Ok = new();
    public static Reply Fail(int status, string error) => new(status, error);
    public static readonly Reply NotSent = Fail(409, "not_sent");
    public static readonly Reply NotFound = Fail(404, "not_found");
    public static readonly Reply BadRequest = Fail(400, "bad_request");
    public static readonly Reply LoginRequired = Fail(401, "login_required");
    public static Reply Failed(FailureKind kind) => kind == FailureKind.NotLoggedIn ? LoginRequired : Fail(502, kind.ToString().ToLowerInvariant());
}

/// <summary>口の中身（アプリが再生とライブラリにつなぐ）。どのスレッドから呼んでもよい。</summary>
public interface ILocalApiBackend
{
    Task<PlaybackView> PlaybackAsync();
    Task<IReadOnlyList<TrackView>> QueueAsync();
    Task<IReadOnlyList<DeviceView>> DevicesAsync();
    /// <param name="action">play・pause・next・previous・seek・volume（シャッフル・繰り返しは来ない）。</param>
    Task<Reply> ControlAsync(string action, long? positionMs, int? volumePercent);
    Task<Reply> TransferAsync(string deviceId);
    Task<Reply> PlayAsync(PlayRequest request);
    Task<Reply> QueueAddAsync(IReadOnlyList<string> videoIds, bool next);
    Task<Reply> JumpAsync(int? index, string? videoId);
    Task<ApiResult<IReadOnlyList<TrackView>>> SearchAsync(string query);
    Task<ApiResult<IReadOnlyList<PlaylistView>>> PlaylistsAsync();
    Task<ApiResult<IReadOnlyList<TrackView>>> PlaylistTracksAsync(string id);
    Task<ApiResult<IReadOnlyList<ShelfView>>> SuggestionsAsync();
}

/// <summary>
/// localhost の HTTP の口。health 以外は `Authorization: Bearer &lt;トークン&gt;` が要る。トークンは起動ごとに作り直し、
/// 場所と一緒に置き場のファイル（今のユーザーだけが読める %LOCALAPPDATA%\wave）に書く。
/// </summary>
public sealed class LocalApiServer : IDisposable
{
    /// <summary>まず試す番号（wave-api の 8793 の次）。使われていたら順に次を試す。</summary>
    public const int FirstPort = 8794;
    private const int Tries = 10;

    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    private readonly ILocalApiBackend backend;
    private readonly HttpListener listener = new();
    private readonly CancellationTokenSource stop = new();
    private readonly Action<string>? log;

    public string Token { get; }
    public string Url { get; }

    private LocalApiServer(ILocalApiBackend backend, HttpListener started, string url, string token, Action<string>? log)
    {
        this.backend = backend;
        listener = started;
        Url = url;
        Token = token;
        this.log = log;
    }

    /// <summary>空いている番号で待ち受けを始める。どれも使えなければ null。</summary>
    public static LocalApiServer? Start(ILocalApiBackend backend, int firstPort = FirstPort, Action<string>? log = null)
    {
        for (var port = firstPort; port < firstPort + Tries; port++)
        {
            var listener = new HttpListener();
            // 127.0.0.1 は管理者でないと予約が要るので localhost で待つ（http.sys は Host が localhost のものだけ渡す）
            var url = $"http://localhost:{port}";
            listener.Prefixes.Add(url + "/");
            try
            {
                listener.Start();
            }
            catch (HttpListenerException)
            {
                listener.Close();
                continue;
            }
            var server = new LocalApiServer(backend, listener, url, NewToken(), log);
            _ = server.AcceptLoopAsync();
            return server;
        }
        log?.Invoke("local-api no free port");
        return null;
    }

    private static string NewToken() => Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private async Task AcceptLoopAsync()
    {
        while (!stop.IsCancellationRequested)
        {
            HttpListenerContext context;
            try
            {
                context = await listener.GetContextAsync();
            }
            catch (Exception e) when (e is HttpListenerException or ObjectDisposedException or InvalidOperationException)
            {
                return;
            }
            _ = HandleAsync(context);
        }
    }

    private async Task HandleAsync(HttpListenerContext context)
    {
        var response = context.Response;
        try
        {
            await RouteAsync(context.Request, response);
        }
        catch (Exception e) when (e is HttpListenerException or IOException or ObjectDisposedException)
        {
            // 相手が切った
        }
        catch (Exception e)
        {
            log?.Invoke($"local-api failed type={e.GetType().Name}");
            try
            {
                await WriteAsync(response, 500, new { error = "internal" });
            }
            catch (Exception)
            {
            }
        }
        finally
        {
            try
            {
                response.Close();
            }
            catch (Exception)
            {
            }
        }
    }

    private async Task RouteAsync(HttpListenerRequest request, HttpListenerResponse response)
    {
        var path = request.Url?.AbsolutePath ?? "/";
        var method = request.HttpMethod;
        // ブラウザのページからは呼ばせない（Origin が付くのはブラウザだけ。トークンが漏れていなくても、ページから叩かれないように）
        if (request.Headers["Origin"] is not null)
        {
            await WriteAsync(response, 403, new { error = "forbidden" });
            return;
        }
        if (path == "/v1/health")
        {
            await WriteAsync(response, 200, new { ok = true });
            return;
        }
        if (!TokenMatches(request.Headers["Authorization"], Token))
        {
            await WriteAsync(response, 401, new { error = "token_required" });
            return;
        }
        switch (method, path)
        {
            case ("GET", "/v1/playback"):
                await WriteAsync(response, 200, await backend.PlaybackAsync());
                return;
            case ("GET", "/v1/queue"):
                await WriteAsync(response, 200, await backend.QueueAsync());
                return;
            case ("GET", "/v1/devices"):
                await WriteAsync(response, 200, await backend.DevicesAsync());
                return;
            case ("GET", "/v1/events"):
                await EventsAsync(response);
                return;
            case ("GET", "/v1/search"):
            {
                var q = request.QueryString["q"]?.Trim();
                if (string.IsNullOrEmpty(q) || q.Length > 200) await ReplyAsync(response, Reply.BadRequest);
                else await ResultAsync(response, await backend.SearchAsync(q));
                return;
            }
            case ("GET", "/v1/playlists"):
                await ResultAsync(response, await backend.PlaylistsAsync());
                return;
            case ("GET", "/v1/suggestions"):
                await ResultAsync(response, await backend.SuggestionsAsync());
                return;
            case ("GET", _) when path.StartsWith("/v1/playlists/") && path.EndsWith("/tracks"):
            {
                var id = path["/v1/playlists/".Length..^"/tracks".Length];
                if (!IsId(id)) await ReplyAsync(response, Reply.BadRequest);
                else await ResultAsync(response, await backend.PlaylistTracksAsync(id));
                return;
            }
            case ("POST", "/v1/control"):
                await ReplyAsync(response, await ControlAsync(await BodyAsync(request)));
                return;
            case ("POST", "/v1/transfer"):
            {
                var body = await BodyAsync(request);
                var id = Str(body, "deviceId");
                await ReplyAsync(response, id is null ? Reply.BadRequest : await backend.TransferAsync(id));
                return;
            }
            case ("POST", "/v1/play"):
            {
                var body = await BodyAsync(request);
                var play = new PlayRequest(Str(body, "videoId"), Str(body, "playlistId"), Str(body, "albumBrowseId"), Str(body, "startVideoId"),
                    body is { } b && b.TryGetProperty("shuffle", out var s) && s.ValueKind == JsonValueKind.True, Str(body, "deviceId"));
                var valid = (play.VideoId ?? play.PlaylistId ?? play.AlbumBrowseId) is not null
                    && new[] { play.VideoId, play.PlaylistId, play.AlbumBrowseId, play.StartVideoId }.All(x => x is null || IsId(x));
                await ReplyAsync(response, valid ? await backend.PlayAsync(play) : Reply.BadRequest);
                return;
            }
            case ("POST", "/v1/queue"):
            {
                var body = await BodyAsync(request);
                var ids = body is { } b && b.TryGetProperty("videoIds", out var arr) && arr.ValueKind == JsonValueKind.Array
                    ? arr.EnumerateArray().Where(x => x.ValueKind == JsonValueKind.String).Select(x => x.GetString()!).ToList()
                    : [];
                var next = body is { } n && n.TryGetProperty("next", out var nx) && nx.ValueKind == JsonValueKind.True;
                var valid = ids.Count is > 0 and <= 200 && ids.All(IsId);
                await ReplyAsync(response, valid ? await backend.QueueAddAsync(ids, next) : Reply.BadRequest);
                return;
            }
            case ("POST", "/v1/queue/jump"):
            {
                var body = await BodyAsync(request);
                int? index = body is { } b && b.TryGetProperty("index", out var i) && i.ValueKind == JsonValueKind.Number && i.TryGetInt32(out var v) ? v : null;
                var videoId = Str(body, "videoId");
                var valid = (index ?? 0) >= 0 && (index is not null || videoId is not null);
                await ReplyAsync(response, valid ? await backend.JumpAsync(index, videoId) : Reply.BadRequest);
                return;
            }
            default:
                await WriteAsync(response, 404, new { error = "not_found" });
                return;
        }
    }

    private async Task<Reply> ControlAsync(JsonElement? body)
    {
        var action = Str(body, "action");
        long? position = body is { } b && b.TryGetProperty("positionMs", out var p) && p.TryGetInt64(out var pv) ? pv : null;
        int? volume = body is { } c && c.TryGetProperty("volumePercent", out var vol) && vol.TryGetInt32(out var vv) ? vv : null;
        return action switch
        {
            "play" or "pause" or "next" or "previous" => await backend.ControlAsync(action, null, null),
            "seek" when position >= 0 => await backend.ControlAsync(action, position, null),
            "volume" when volume is >= 0 and <= 100 => await backend.ControlAsync(action, null, volume),
            // シャッフル・繰り返しは wave-api と同じく受けない（連携の取り決めに無い）
            "shuffle" or "repeat" => Reply.Fail(400, "unsupported"),
            _ => Reply.BadRequest,
        };
    }

    /// <summary>家の再生が変わるたびに playback を流す（0.5 秒おきに見て、変わったときだけ。15 秒ごとに空の行）。</summary>
    private async Task EventsAsync(HttpListenerResponse response)
    {
        response.StatusCode = 200;
        response.ContentType = "text/event-stream; charset=utf-8";
        response.Headers["Cache-Control"] = "no-cache";
        response.SendChunked = true;
        var output = response.OutputStream;
        PlaybackView? last = null;
        var lastSentAt = DateTimeOffset.MinValue;
        while (!stop.IsCancellationRequested)
        {
            var now = await backend.PlaybackAsync();
            var at = DateTimeOffset.UtcNow;
            string? chunk = null;
            if (last is null || Changed(last, now))
            {
                chunk = $"event: playback\ndata: {JsonSerializer.Serialize(now, Json)}\n\n";
                last = now;
            }
            else if (at - lastSentAt > TimeSpan.FromSeconds(15))
            {
                chunk = ": keepalive\n\n";
            }
            if (chunk is not null)
            {
                var bytes = Encoding.UTF8.GetBytes(chunk);
                await output.WriteAsync(bytes, stop.Token);
                await output.FlushAsync(stop.Token);
                lastSentAt = at;
            }
            await Task.Delay(500, stop.Token);
        }
    }

    /// <summary>
    /// 流すに値する変化か: 曲・再生中か・繰り返し・音量・鳴らしている端末・ログインが変わった、または位置が
    /// 前の値から進めた位置と 1.5 秒より大きくずれた（シーク）。位置が進むだけでは流さない（受け手が observedAt から進める）。
    /// </summary>
    internal static bool Changed(PlaybackView before, PlaybackView after)
    {
        if (before.Track?.VideoId != after.Track?.VideoId || before.IsPlaying != after.IsPlaying || before.Repeat != after.Repeat
            || before.VolumePercent != after.VolumePercent || before.Device?.Id != after.Device?.Id || before.LoggedIn != after.LoggedIn
            || before.Joined != after.Joined) return true;
        if (before.ProgressMs is not { } p0 || after.ProgressMs is not { } p1) return before.ProgressMs != after.ProgressMs;
        var elapsed = before.IsPlaying && DateTimeOffset.TryParse(before.ObservedAt, out var t0) && DateTimeOffset.TryParse(after.ObservedAt, out var t1)
            ? (long)(t1 - t0).TotalMilliseconds
            : 0;
        return Math.Abs(p0 + elapsed - p1) > 1500;
    }

    internal static bool TokenMatches(string? header, string token)
    {
        if (header is null || !header.StartsWith("Bearer ", StringComparison.Ordinal)) return false;
        return CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(header["Bearer ".Length..]), Encoding.UTF8.GetBytes(token));
    }

    private static bool IsId(string id) => id.Length is >= 2 and <= 128 && id.All(c => char.IsAsciiLetterOrDigit(c) || c is '_' or '-');

    private static string? Str(JsonElement? body, string name) =>
        body is { ValueKind: JsonValueKind.Object } b && b.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static async Task<JsonElement?> BodyAsync(HttpListenerRequest request)
    {
        if (request.ContentLength64 > 64 * 1024) return null;
        try
        {
            using var reader = new StreamReader(request.InputStream, Encoding.UTF8);
            var text = await reader.ReadToEndAsync();
            using var doc = JsonDocument.Parse(text);
            return doc.RootElement.ValueKind == JsonValueKind.Object ? doc.RootElement.Clone() : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static Task ReplyAsync(HttpListenerResponse response, Reply reply) =>
        reply.Error is { } error ? WriteAsync(response, reply.Status, new { error }) : WriteAsync(response, 200, new { ok = true, deviceId = reply.DeviceId });

    private static Task ResultAsync<T>(HttpListenerResponse response, ApiResult<T> result) =>
        result.IsOk ? WriteAsync(response, 200, result.Value) : ReplyAsync(response, Reply.Failed(result.Failure!.Value));

    private static async Task WriteAsync(HttpListenerResponse response, int status, object? value)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(value, Json);
        response.StatusCode = status;
        response.ContentType = "application/json; charset=utf-8";
        response.ContentLength64 = bytes.Length;
        await response.OutputStream.WriteAsync(bytes);
    }

    public void Dispose()
    {
        stop.Cancel();
        try
        {
            listener.Close();
        }
        catch (ObjectDisposedException)
        {
        }
    }
}

/// <summary>
/// 口の場所とトークンを置くファイル（%LOCALAPPDATA%\wave\local-api.json）。ほかのアプリはこれを読んでつなぐ。
/// pid の wave が動いていなければ古いもの。exe は wave を起こすのに使える（wave が止まっていても残す）。
/// </summary>
public static class LocalApiFile
{
    public const string Name = "local-api.json";

    public sealed record Content(string Url, string? Token, int Pid, string? Exe);

    public static void Write(string dir, Content content)
    {
        var path = Path.Combine(dir, Name);
        var temp = path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(content, LocalApiServer.Json));
        File.Move(temp, path, overwrite: true);
    }

    /// <summary>止めるとき: トークンと場所を消し、wave の場所だけ残す。</summary>
    public static void Clear(string dir, string? exe)
    {
        try
        {
            Write(dir, new Content("", null, 0, exe));
        }
        catch (IOException)
        {
        }
    }

    public static Content? Read(string dir)
    {
        try
        {
            return JsonSerializer.Deserialize<Content>(File.ReadAllText(Path.Combine(dir, Name)), LocalApiServer.Json);
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException)
        {
            return null;
        }
    }
}
