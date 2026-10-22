using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.Channels;
using Wave.Core.Music;
using Wave.Core.Net;

namespace Wave.Core.LocalApi;

// 同じ PC のほかのアプリ向けの口（docs/local-api.md）。形は wave-api（docs/wave-api.md）と同じにし、
// どちらにもつなげるようにする。違いは、ここでは「この PC」も鳴らす先に選べることと、ログインは PC の wave のものを使うこと。

public sealed record TrackView(string VideoId, string Title, string Artist, string? Album, string? ArtUrl, long? DurationMs, bool IsLive, string? LikeStatus = null)
{
    public static TrackView Of(Song song, int? artPx = null) =>
        new(song.VideoId, song.Title, song.ArtistsText, song.Album?.Title,
            song.ThumbnailUrl is { } url && artPx is { } px ? ArtworkUrl.Sized(url, px) : song.ThumbnailUrl,
            song.IsLive ? null : song.DurationSeconds * 1000L, song.IsLive);

    /// <summary>評価の名前（like・dislike・indifferent）。分からなければ null。</summary>
    public static string? LikeName(LikeStatus? like) => like?.ToString().ToLowerInvariant();
}

/// <param name="Self">この PC の wave（鳴らす先に選べる。wave-api の self と違う）。</param>
public sealed record DeviceView(string Id, string Name, string? Kind, bool Online, bool Active, bool Self = false);

/// <param name="Shuffle">鳴っている端末のシャッフル（前の版の端末を映していて分からなければ false）。</param>
public sealed record PlaybackView(bool Joined, bool LoggedIn, bool IsPlaying, string Repeat, TrackView? Track, long? ProgressMs, string ObservedAt, DeviceView? Device, int? VolumePercent, bool Shuffle = false);

public sealed record PlaylistView(string Id, string Name, string? Subtitle, string? ArtUrl);

public sealed record ArtistView(string Id, string Name, string? Subtitle, string? ArtUrl);

/// <summary>ホームの棚の1枚（元の並びのまま）。type は track・playlist・album・artist。id は videoId・playlistId・browseId。</summary>
public sealed record ItemView(string Type, string Id, string Title, string? Subtitle, string? ArtUrl, bool IsLive = false);

/// <param name="Albums">wave-api には無い（PC の wave だけ）。id はアルバムの browseId。</param>
/// <param name="Items">wave-api には無い。曲・プレイリスト・アルバム・アーティストを元の並びのまま。</param>
public sealed record ShelfView(string Title, IReadOnlyList<TrackView> Tracks, IReadOnlyList<PlaylistView> Playlists, IReadOnlyList<PlaylistView> Albums, IReadOnlyList<ItemView> Items, string? Strapline = null);

/// <summary>ホームの気分の札（Relax など）。params を `GET /v1/suggestions?mood=` に渡すと、その札を選んだホーム。</summary>
public sealed record MoodView(string Title, string Params);

public sealed record AlbumView(string Id, string Name, string? Subtitle, string? ArtUrl, string? PlaylistId, IReadOnlyList<TrackView> Tracks);

public sealed record AlbumShelfView(string Title, IReadOnlyList<PlaylistView> Albums);

/// <param name="AllSongsPlaylistId">「すべての曲」のプレイリスト（無ければ null）。</param>
public sealed record ArtistPageView(string Id, string Name, string? ArtUrl, IReadOnlyList<TrackView> Tracks, string? AllSongsPlaylistId, IReadOnlyList<AlbumShelfView> AlbumShelves);

public sealed record LyricLineView(long TimeMs, string Text);

/// <param name="State">found・none（歌詞が無い）・loading・failed・idle（曲が無い）。</param>
/// <param name="Lines">時刻つきの行（synced のときだけ）。</param>
/// <param name="Text">時刻の無い本文（synced でないときだけ）。</param>
public sealed record LyricsView(string? VideoId, string State, bool Synced, string? Source, IReadOnlyList<LyricLineView>? Lines, string? Text);

/// <summary>wave の色（Windows の強調色。#RRGGBB）と明暗。暗い地の文字には AccentLight2、明るい地には AccentDark1 を使う（WinUI と同じ）。</summary>
public sealed record ThemeView(bool Dark, string Accent, string AccentLight1, string AccentLight2, string AccentLight3, string AccentDark1, string AccentDark2, string AccentDark3);

public sealed record PlayRequest(string? VideoId = null, string? PlaylistId = null, string? AlbumBrowseId = null, string? StartVideoId = null, bool Shuffle = false, string? DeviceId = null, string? ArtistBrowseId = null);

/// <param name="Action">play・pause・next・previous・seek・volume・shuffle・repeat。</param>
/// <param name="Shuffle">shuffle のとき、入れるか切るか。</param>
/// <param name="Repeat">repeat のとき、off・track・context。</param>
public sealed record ControlRequest(string Action, long? PositionMs = null, int? VolumePercent = null, bool? Shuffle = null, string? Repeat = null);

/// <summary>検索の種類（`GET /v1/search?type=`）。</summary>
public enum SearchKind { Song, Live, Album, Playlist, Artist }

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
    Task<Reply> ControlAsync(ControlRequest request);
    Task<Reply> TransferAsync(string deviceId);
    Task<Reply> PlayAsync(PlayRequest request);
    Task<Reply> QueueAddAsync(IReadOnlyList<string> videoIds, bool next);
    Task<Reply> JumpAsync(int? index, string? videoId);
    /// <summary>曲を高く評価する・外す（videoId が null なら今の曲）。</summary>
    Task<Reply> RateAsync(string? videoId, bool liked);
    /// <summary>今の曲の歌詞。</summary>
    Task<LyricsView> LyricsAsync();
    Task<ThemeView> ThemeAsync();
    /// <param name="kind">Song・Live なら TrackView、Album・Playlist なら PlaylistView、Artist なら ArtistView の一覧。</param>
    Task<ApiResult<IReadOnlyList<object>>> SearchAsync(string query, SearchKind kind);
    Task<ApiResult<IReadOnlyList<PlaylistView>>> PlaylistsAsync();
    Task<ApiResult<IReadOnlyList<PlaylistView>>> LibraryAlbumsAsync();
    Task<ApiResult<IReadOnlyList<ArtistView>>> LibraryArtistsAsync();
    Task<ApiResult<IReadOnlyList<TrackView>>> PlaylistTracksAsync(string id);
    Task<ApiResult<AlbumView>> AlbumAsync(string browseId);
    Task<ApiResult<ArtistPageView>> ArtistAsync(string browseId);
    /// <param name="mood">気分の札の params（null ならふつうのホーム）。</param>
    Task<ApiResult<IReadOnlyList<ShelfView>>> SuggestionsAsync(string? mood);
    Task<ApiResult<IReadOnlyList<MoodView>>> MoodsAsync();
    /// <summary>ホームの上の2列のタイル（最近聴いたもの）。</summary>
    Task<ApiResult<IReadOnlyList<ItemView>>> RecentAsync();
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
        catch (Exception e) when (e is HttpListenerException or IOException or ObjectDisposedException or OperationCanceledException)
        {
            // 相手が切った・wave を閉じた
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
        // YouTube から届く文字の言語（この問い合わせだけ。画面の言語は変えない）
        if (request.QueryString["hl"] is { } hl)
        {
            if (!IsLanguage(hl))
            {
                await ReplyAsync(response, Reply.BadRequest);
                return;
            }
            InnerTubeClient.HlOverride = hl;
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
            case ("GET", "/v1/lyrics"):
                await WriteAsync(response, 200, await backend.LyricsAsync());
                return;
            case ("GET", "/v1/theme"):
                await WriteAsync(response, 200, await backend.ThemeAsync());
                return;
            case ("GET", "/v1/search"):
            {
                var q = request.QueryString["q"]?.Trim();
                var kind = SearchKindOf(request.QueryString["type"]);
                if (string.IsNullOrEmpty(q) || q.Length > 200 || kind is null) await ReplyAsync(response, Reply.BadRequest);
                else await ResultAsync(response, await backend.SearchAsync(q, kind.Value));
                return;
            }
            case ("GET", "/v1/playlists"):
                await ResultAsync(response, await backend.PlaylistsAsync());
                return;
            case ("GET", "/v1/library/albums"):
                await ResultAsync(response, await backend.LibraryAlbumsAsync());
                return;
            case ("GET", "/v1/library/artists"):
                await ResultAsync(response, await backend.LibraryArtistsAsync());
                return;
            case ("GET", "/v1/recent"):
                await ResultAsync(response, await backend.RecentAsync());
                return;
            case ("GET", "/v1/moods"):
                await ResultAsync(response, await backend.MoodsAsync());
                return;
            case ("GET", "/v1/suggestions"):
            {
                var mood = request.QueryString["mood"];
                if (mood is not null && !IsParams(mood)) await ReplyAsync(response, Reply.BadRequest);
                else await ResultAsync(response, await backend.SuggestionsAsync(mood));
                return;
            }
            case ("GET", _) when Segment(path, "/v1/playlists/", "/tracks") is { } id:
                if (!IsId(id)) await ReplyAsync(response, Reply.BadRequest);
                else await ResultAsync(response, await backend.PlaylistTracksAsync(id));
                return;
            case ("GET", _) when Segment(path, "/v1/albums/", "/tracks") is { } id:
                if (!IsId(id)) await ReplyAsync(response, Reply.BadRequest);
                else await ResultAsync(response, (await backend.AlbumAsync(id)).Map(a => a.Tracks));
                return;
            case ("GET", _) when Segment(path, "/v1/albums/", "") is { } id:
                if (!IsId(id)) await ReplyAsync(response, Reply.BadRequest);
                else await ResultAsync(response, await backend.AlbumAsync(id));
                return;
            case ("GET", _) when Segment(path, "/v1/artists/", "") is { } id:
                if (!IsId(id)) await ReplyAsync(response, Reply.BadRequest);
                else await ResultAsync(response, await backend.ArtistAsync(id));
                return;
            case ("POST", "/v1/control"):
                await ReplyAsync(response, await ControlAsync(await BodyAsync(request)));
                return;
            case ("POST", "/v1/rate"):
            {
                var body = await BodyAsync(request);
                var videoId = Str(body, "videoId");
                var like = Str(body, "likeStatus");
                var valid = like is "like" or "indifferent" && (videoId is null || IsId(videoId));
                await ReplyAsync(response, valid ? await backend.RateAsync(videoId, like == "like") : Reply.BadRequest);
                return;
            }
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
                    body is { } b && b.TryGetProperty("shuffle", out var s) && s.ValueKind == JsonValueKind.True, Str(body, "deviceId"), Str(body, "artistBrowseId"));
                var valid = (play.VideoId ?? play.PlaylistId ?? play.AlbumBrowseId ?? play.ArtistBrowseId) is not null
                    && new[] { play.VideoId, play.PlaylistId, play.AlbumBrowseId, play.StartVideoId, play.ArtistBrowseId }.All(x => x is null || IsId(x));
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

    /// <summary>`prefix{id}suffix` の id（suffix が空なら、id の後ろに何も無いものだけ）。合わなければ null。</summary>
    private static string? Segment(string path, string prefix, string suffix)
    {
        if (!path.StartsWith(prefix, StringComparison.Ordinal) || !path.EndsWith(suffix, StringComparison.Ordinal) || path.Length <= prefix.Length + suffix.Length) return null;
        var id = path[prefix.Length..^suffix.Length];
        return id.Contains('/') ? null : id;
    }

    private static SearchKind? SearchKindOf(string? type) => type switch
    {
        null or "" or "song" => SearchKind.Song,
        "live" => SearchKind.Live,
        "album" => SearchKind.Album,
        "playlist" => SearchKind.Playlist,
        "artist" => SearchKind.Artist,
        _ => null,
    };

    private async Task<Reply> ControlAsync(JsonElement? body)
    {
        var action = Str(body, "action");
        long? position = body is { } b && b.TryGetProperty("positionMs", out var p) && p.TryGetInt64(out var pv) ? pv : null;
        int? volume = body is { } c && c.TryGetProperty("volumePercent", out var vol) && vol.TryGetInt32(out var vv) ? vv : null;
        bool? shuffle = body is { } d && d.TryGetProperty("shuffle", out var sh) && sh.ValueKind is JsonValueKind.True or JsonValueKind.False ? sh.GetBoolean() : null;
        var repeat = Str(body, "repeat");
        return action switch
        {
            "play" or "pause" or "next" or "previous" => await backend.ControlAsync(new(action)),
            "seek" when position >= 0 => await backend.ControlAsync(new(action, PositionMs: position)),
            "volume" when volume is >= 0 and <= 100 => await backend.ControlAsync(new(action, VolumePercent: volume)),
            "shuffle" when shuffle is not null => await backend.ControlAsync(new(action, Shuffle: shuffle)),
            "repeat" when repeat is "off" or "track" or "context" => await backend.ControlAsync(new(action, Repeat: repeat)),
            _ => Reply.BadRequest,
        };
    }

    // --- /v1/events: 見張りは1つで、つないでいる相手みなに配る ---

    private readonly object watchLock = new();
    private readonly List<Channel<string>> watchers = [];
    private bool watching;

    /// <summary>
    /// 家の再生が変わるたびに playback を、キューが変わるたびに queue を流す。つないだときは今の両方を流す。
    /// 見張り（<see cref="WatchAsync"/>）は、つないでいる相手がいる間だけ1つ動く。
    /// </summary>
    private async Task EventsAsync(HttpListenerResponse response)
    {
        response.StatusCode = 200;
        response.ContentType = "text/event-stream; charset=utf-8";
        response.Headers["Cache-Control"] = "no-cache";
        response.SendChunked = true;
        var output = response.OutputStream;
        var channel = Channel.CreateBounded<string>(new BoundedChannelOptions(64) { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true });
        lock (watchLock)
        {
            watchers.Add(channel);
            if (!watching)
            {
                watching = true;
                _ = WatchAsync();
            }
        }
        try
        {
            await SendAsync(output, Event("playback", await backend.PlaybackAsync()));
            await SendAsync(output, Event("queue", await backend.QueueAsync()));
            await foreach (var chunk in channel.Reader.ReadAllAsync(stop.Token))
                await SendAsync(output, chunk);
        }
        finally
        {
            lock (watchLock) watchers.Remove(channel);
        }
    }

    private async Task SendAsync(System.IO.Stream output, string chunk)
    {
        var bytes = Encoding.UTF8.GetBytes(chunk);
        await output.WriteAsync(bytes, stop.Token);
        await output.FlushAsync(stop.Token);
    }

    private static string Event(string name, object value) => $"event: {name}\ndata: {JsonSerializer.Serialize(value, Json)}\n\n";

    /// <summary>0.5 秒おきに再生とキューを見て、変わったときだけ配る（位置が進むだけでは配らない）。15 秒ごとに空の行（切れた相手に気づくため）。</summary>
    private async Task WatchAsync()
    {
        PlaybackView? lastPlayback = null;
        IReadOnlyList<TrackView>? lastQueue = null;
        var lastSentAt = DateTimeOffset.UtcNow;
        try
        {
            while (!stop.IsCancellationRequested)
            {
                lock (watchLock)
                {
                    if (watchers.Count == 0)
                    {
                        watching = false;
                        return;
                    }
                }
                var now = await backend.PlaybackAsync();
                var queue = await backend.QueueAsync();
                var chunks = new List<string>();
                // 最初の回は覚えるだけ（つないだ相手には、つないだときに今の両方を流している）
                var playbackChanged = lastPlayback is not null && Changed(lastPlayback, now);
                if (playbackChanged) chunks.Add(Event("playback", now));
                if (lastQueue is not null && !lastQueue.SequenceEqual(queue)) chunks.Add(Event("queue", queue));
                if (playbackChanged || lastPlayback is null) lastPlayback = now;
                lastQueue = queue;
                var at = DateTimeOffset.UtcNow;
                if (chunks.Count == 0 && at - lastSentAt > TimeSpan.FromSeconds(15)) chunks.Add(": keepalive\n\n");
                if (chunks.Count > 0)
                {
                    lastSentAt = at;
                    lock (watchLock)
                        foreach (var watcher in watchers)
                            foreach (var chunk in chunks)
                                watcher.Writer.TryWrite(chunk);
                }
                await Task.Delay(500, stop.Token);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception e)
        {
            log?.Invoke($"local-api watch failed type={e.GetType().Name}");
        }
        lock (watchLock)
        {
            watching = false;
            foreach (var watcher in watchers) watcher.Writer.TryComplete();
        }
    }

    /// <summary>
    /// 流すに値する変化か: 位置と見た時刻を除いたどれか（曲の中身・評価・再生中か・シャッフル・繰り返し・音量・鳴らしている端末・ログイン）が変わった、
    /// または位置が前の値から進めた位置と 1.5 秒より大きくずれた（シーク）。位置が進むだけでは流さない（受け手が observedAt から進める）。
    /// </summary>
    internal static bool Changed(PlaybackView before, PlaybackView after)
    {
        if (before with { ProgressMs = null, ObservedAt = "" } != after with { ProgressMs = null, ObservedAt = "" }) return true;
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

    private static bool IsLanguage(string value) => value.Length is >= 2 and <= 10 && value.All(c => char.IsAsciiLetter(c) || c == '-');

    /// <summary>気分の札の params（base64 の文字と %）。</summary>
    private static bool IsParams(string value) => value.Length is >= 2 and <= 512 && value.All(c => char.IsAsciiLetterOrDigit(c) || c is '_' or '-' or '%' or '=' or '+' or '/');

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
