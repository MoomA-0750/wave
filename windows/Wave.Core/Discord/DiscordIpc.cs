using System.Buffers.Binary;
using System.IO.Pipes;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Wave.Core.Discord;

/// <summary>
/// Discord に出す「再生中の曲」。LargeText は画像に重ねて出す文字（アルバム名・ほかの端末で鳴っていればその名前）。
/// Playing が false のときは出さない（Spotify と同じく、一時停止中は消す）。
/// </summary>
public sealed record PresenceTrack(
    string VideoId,
    string Title,
    string Artists,
    string? LargeText,
    string? ThumbnailUrl,
    double PositionSeconds,
    double DurationSeconds,
    bool Playing,
    string ButtonLabel = "YouTube Music で開く");

/// <summary>
/// Discord のデスクトップ版のローカル IPC（Rich Presence）の枠と中身。
/// 枠は [op: int32 LE][長さ: int32 LE][JSON（UTF-8）]。最初に op 0 で {v:1, client_id} を送り、READY が返ったら op 1 で SET_ACTIVITY を送る。
/// </summary>
public static partial class DiscordIpc
{
    public const int OpHandshake = 0;
    public const int OpFrame = 1;
    public const int OpClose = 2;
    public const int OpPing = 3;
    public const int OpPong = 4;

    /// <summary>1つの枠の中身の上限（壊れた長さで大きな配列を取らないため）。</summary>
    public const int MaxPayload = 64 * 1024;

    public static byte[] Encode(int op, string json)
    {
        var body = Encoding.UTF8.GetBytes(json);
        var frame = new byte[8 + body.Length];
        BinaryPrimitives.WriteInt32LittleEndian(frame.AsSpan(0, 4), op);
        BinaryPrimitives.WriteInt32LittleEndian(frame.AsSpan(4, 4), body.Length);
        body.CopyTo(frame, 8);
        return frame;
    }

    /// <summary>先頭の1枠を読む。まだ足りなければ false（consumed は 0）。</summary>
    public static bool TryDecode(ReadOnlySpan<byte> buffer, out int op, out string json, out int consumed)
    {
        op = 0;
        json = "";
        consumed = 0;
        if (buffer.Length < 8) return false;
        op = BinaryPrimitives.ReadInt32LittleEndian(buffer[..4]);
        var length = BinaryPrimitives.ReadInt32LittleEndian(buffer.Slice(4, 4));
        if (length < 0 || length > MaxPayload) throw new InvalidDataException($"discord frame length {length}");
        if (buffer.Length < 8 + length) return false;
        json = Encoding.UTF8.GetString(buffer.Slice(8, length));
        consumed = 8 + length;
        return true;
    }

    public static async Task WriteFrameAsync(System.IO.Stream stream, int op, string json, CancellationToken cancel)
    {
        await stream.WriteAsync(Encode(op, json), cancel).ConfigureAwait(false);
        await stream.FlushAsync(cancel).ConfigureAwait(false);
    }

    /// <summary>1枠を読む。相手が閉じたら null。</summary>
    public static async Task<(int Op, string Json)?> ReadFrameAsync(System.IO.Stream stream, CancellationToken cancel)
    {
        var header = new byte[8];
        if (!await ReadExactlyOrEndAsync(stream, header, cancel).ConfigureAwait(false)) return null;
        var op = BinaryPrimitives.ReadInt32LittleEndian(header.AsSpan(0, 4));
        var length = BinaryPrimitives.ReadInt32LittleEndian(header.AsSpan(4, 4));
        if (length < 0 || length > MaxPayload) throw new InvalidDataException($"discord frame length {length}");
        var body = new byte[length];
        if (!await ReadExactlyOrEndAsync(stream, body, cancel).ConfigureAwait(false)) return null;
        return (op, Encoding.UTF8.GetString(body));
    }

    private static async Task<bool> ReadExactlyOrEndAsync(System.IO.Stream stream, byte[] buffer, CancellationToken cancel)
    {
        var read = 0;
        while (read < buffer.Length)
        {
            var n = await stream.ReadAsync(buffer.AsMemory(read), cancel).ConfigureAwait(false);
            if (n == 0) return false;
            read += n;
        }
        return true;
    }

    public static string Handshake(string clientId) =>
        new JsonObject { ["v"] = 1, ["client_id"] = clientId }.ToJsonString();

    /// <summary>
    /// SET_ACTIVITY の命令。track が null（または一時停止中）なら、出している状態を消す。
    /// 種類は 2（Listening）。再生中は始まりと終わりの時刻を付け、Discord が残り時間を数える。
    /// </summary>
    public static string SetActivity(int pid, PresenceTrack? track, long nowUnixMs, string nonce)
    {
        var args = new JsonObject { ["pid"] = pid };
        if (track is { Playing: true }) args["activity"] = Activity(track, nowUnixMs);
        return new JsonObject { ["cmd"] = "SET_ACTIVITY", ["args"] = args, ["nonce"] = nonce }.ToJsonString();
    }

    public static JsonObject Activity(PresenceTrack track, long nowUnixMs)
    {
        var activity = new JsonObject
        {
            ["type"] = 2,
            ["details"] = Text(track.Title),
            ["state"] = Text(string.IsNullOrWhiteSpace(track.Artists) ? "wave" : track.Artists),
        };
        var start = StartMs(track, nowUnixMs);
        var timestamps = new JsonObject { ["start"] = start };
        if (track.DurationSeconds > 0) timestamps["end"] = start + (long)Math.Round(track.DurationSeconds * 1000);
        activity["timestamps"] = timestamps;
        var assets = new JsonObject();
        if (ImageUrl(track.ThumbnailUrl) is { } image) assets["large_image"] = image;
        if (!string.IsNullOrWhiteSpace(track.LargeText)) assets["large_text"] = Text(track.LargeText);
        if (assets.Count > 0) activity["assets"] = assets;
        if (IsVideoId(track.VideoId))
        {
            activity["buttons"] = new JsonArray(new JsonObject
            {
                ["label"] = Clip(track.ButtonLabel, 32),
                ["url"] = "https://music.youtube.com/watch?v=" + track.VideoId,
            });
        }
        return activity;
    }

    /// <summary>曲の始まりの時刻（今 − 位置。秒に丸めて、少しの揺れで送り直さないようにする）。</summary>
    public static long StartMs(PresenceTrack track, long nowUnixMs) =>
        (nowUnixMs - (long)Math.Round(Math.Max(0, track.PositionSeconds) * 1000)) / 1000 * 1000;

    /// <summary>
    /// 大きな画像に使う URL。Discord は https の外の画像をそのまま使える（256 文字まで）。
    /// googleusercontent の小さな画像（=w120-h120…）は 512 に広げる。
    /// </summary>
    public static string? ImageUrl(string? thumbnail)
    {
        if (string.IsNullOrEmpty(thumbnail)) return null;
        var url = thumbnail.StartsWith("//", StringComparison.Ordinal) ? "https:" + thumbnail : thumbnail;
        if (!url.StartsWith("https://", StringComparison.Ordinal)) return null;
        url = SizedGoogle().Replace(url, "=w512-h512$1");
        return url.Length <= 256 ? url : null;
    }

    [GeneratedRegex(@"=w\d+-h\d+((?:-[a-z0-9]+)*)$")]
    private static partial Regex SizedGoogle();

    private static bool IsVideoId(string id) => id.Length is > 0 and <= 64 && id.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_');

    /// <summary>Discord の文字の欄は 2〜128 文字（1文字なら空白を足す）。</summary>
    private static string Text(string value)
    {
        var text = Clip(value.Trim(), 128);
        return text.Length switch { 0 => "wave", 1 => text + " ", _ => text };
    }

    private static string Clip(string value, int max)
    {
        if (value.Length <= max) return value;
        // サロゲートの途中で切らない
        var cut = max - 1;
        if (char.IsHighSurrogate(value[cut - 1])) cut--;
        return value[..cut] + "…";
    }

    /// <summary>返事が ERROR なら、その文。</summary>
    public static string? ErrorOf(string json)
    {
        try
        {
            var node = JsonNode.Parse(json);
            if (node?["evt"]?.GetValue<string>() == "ERROR") return node["data"]?["message"]?.GetValue<string>() ?? "error";
        }
        catch (Exception e) when (e is JsonException or InvalidOperationException)
        {
        }
        return null;
    }

    public static bool IsReady(string json)
    {
        try
        {
            return JsonNode.Parse(json)?["evt"]?.GetValue<string>() == "READY";
        }
        catch (Exception e) when (e is JsonException or InvalidOperationException)
        {
            return false;
        }
    }

    /// <summary>
    /// Discord につなぐ（discord-ipc-0〜9 を順に試す）。Windows は名前付きパイプ、ほかは $XDG_RUNTIME_DIR・$TMPDIR などの unix ソケット。
    /// 見つからなければ null。
    /// </summary>
    public static async Task<System.IO.Stream?> ConnectAsync(string baseName = "discord-ipc-", CancellationToken cancel = default)
    {
        for (var i = 0; i < 10; i++)
        {
            var name = baseName + i;
            if (OperatingSystem.IsWindows())
            {
                var pipe = new NamedPipeClientStream(".", name, PipeDirection.InOut, PipeOptions.Asynchronous);
                try
                {
                    await pipe.ConnectAsync(300, cancel).ConfigureAwait(false);
                    return pipe;
                }
                catch (Exception e) when (e is TimeoutException or IOException or UnauthorizedAccessException)
                {
                    await pipe.DisposeAsync().ConfigureAwait(false);
                }
                continue;
            }
            foreach (var dir in UnixDirs())
            {
                var path = Path.Combine(dir, name);
                if (!File.Exists(path)) continue;
                var socket = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
                try
                {
                    await socket.ConnectAsync(new UnixDomainSocketEndPoint(path), cancel).ConfigureAwait(false);
                    return new NetworkStream(socket, ownsSocket: true);
                }
                catch (SocketException)
                {
                    socket.Dispose();
                }
            }
        }
        return null;
    }

    private static IEnumerable<string> UnixDirs()
    {
        foreach (var key in new[] { "XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP" })
        {
            if (Environment.GetEnvironmentVariable(key) is { Length: > 0 } dir) yield return dir;
        }
        yield return "/tmp";
    }
}

/// <summary>
/// いつ SET_ACTIVITY を送るか（Discord は 20 秒に 5 回までなので、送りすぎない）。
/// 曲・再生中か・画像の文字が変わったらすぐ（前に送ってから MinGapMs は空ける）、シークで始まりの時刻がずれたら SeekGapMs おきに。
/// </summary>
public sealed class PresencePlanner
{
    public const long MinGapMs = 2_000;
    public const long SeekGapMs = 15_000;
    private const long SeekToleranceMs = 3_000;

    private bool sentAny;
    private string? sentKey;
    private long sentStart;
    private long sentAtMs = long.MinValue / 2;

    /// <summary>送ったことを忘れる（つなぎ直したとき。次は必ず送る）。</summary>
    public void Reset()
    {
        sentAny = false;
        sentKey = null;
    }

    public static string KeyOf(PresenceTrack? track) =>
        track is { Playing: true } t ? $"{t.VideoId}\n{t.Title}\n{t.Artists}\n{t.LargeText}\n{t.ThumbnailUrl}\n{(long)t.DurationSeconds}" : "";

    /// <summary>今送るべきか（nowMs は単調に増える時計、nowUnixMs は Unix 時刻）。</summary>
    public bool ShouldSend(PresenceTrack? track, long nowMs, long nowUnixMs)
    {
        var key = KeyOf(track);
        if (!sentAny || key != sentKey) return nowMs - sentAtMs >= MinGapMs;
        if (track is not { Playing: true }) return false;
        var start = DiscordIpc.StartMs(track, nowUnixMs);
        return Math.Abs(start - sentStart) > SeekToleranceMs && nowMs - sentAtMs >= SeekGapMs;
    }

    public void Sent(PresenceTrack? track, long nowMs, long nowUnixMs)
    {
        sentAny = true;
        sentKey = KeyOf(track);
        sentStart = track is { Playing: true } ? DiscordIpc.StartMs(track, nowUnixMs) : 0;
        sentAtMs = nowMs;
    }
}

/// <summary>
/// Discord の Rich Presence を出し続ける。Update で今の曲を渡すと、裏の繰り返しがつなぎ・送り・つなぎ直しをする。
/// Discord が後から起動しても、15 秒おきにつなぎ直しを試す。clientId が null なら消してから切る。
/// </summary>
public sealed class DiscordPresence : IDisposable
{
    public const long RetryMs = 15_000;

    private readonly Func<CancellationToken, Task<System.IO.Stream?>> connect;
    private readonly Action<string>? log;
    private readonly Func<long> nowMs;
    private readonly Func<long> nowUnixMs;
    private readonly int pid;
    private readonly PresencePlanner planner = new();
    private readonly SemaphoreSlim wake = new(0);
    private readonly CancellationTokenSource stop = new();
    private readonly object gate = new();
    private string? wantedClientId;
    private PresenceTrack? wantedTrack;
    private System.IO.Stream? stream;
    private string? connectedClientId;
    private CancellationTokenSource? readerCancel;
    private long nextAttemptMs = long.MinValue / 2;
    private int nonce;

    /// <summary>今の状態（設定の画面に出す）。</summary>
    public string Status { get; private set; } = "off";

    public DiscordPresence(Func<CancellationToken, Task<System.IO.Stream?>>? connect = null, Action<string>? log = null, Func<long>? nowMs = null, Func<long>? nowUnixMs = null, int? pid = null)
    {
        this.connect = connect ?? (c => DiscordIpc.ConnectAsync(cancel: c));
        this.log = log;
        var clock = System.Diagnostics.Stopwatch.StartNew();
        this.nowMs = nowMs ?? (() => clock.ElapsedMilliseconds);
        this.nowUnixMs = nowUnixMs ?? (() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        this.pid = pid ?? Environment.ProcessId;
        _ = Task.Run(LoopAsync);
    }

    /// <summary>出したい曲（clientId が null なら Discord から離れる）。どのスレッドから呼んでもよい。</summary>
    public void Update(string? clientId, PresenceTrack? track)
    {
        lock (gate)
        {
            var changed = clientId != wantedClientId || PresencePlanner.KeyOf(track) != PresencePlanner.KeyOf(wantedTrack);
            wantedClientId = string.IsNullOrWhiteSpace(clientId) ? null : clientId.Trim();
            wantedTrack = track;
            if (!changed) return;
        }
        if (wake.CurrentCount == 0) wake.Release();
    }

    private async Task LoopAsync()
    {
        while (!stop.IsCancellationRequested)
        {
            try
            {
                await StepAsync().ConfigureAwait(false);
            }
            catch (Exception e) when (e is not OperationCanceledException || !stop.IsCancellationRequested)
            {
                log?.Invoke("discord: " + e.GetType().Name + " " + e.Message);
                Disconnect();
            }
            try
            {
                await wake.WaitAsync(1000, stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
        Disconnect();
    }

    private async Task StepAsync()
    {
        string? clientId;
        PresenceTrack? track;
        lock (gate)
        {
            clientId = wantedClientId;
            track = wantedTrack;
        }
        if (closedByPeer)
        {
            // Discord が閉じた（終了した・名前が違った）: 少し待ってからつなぎ直す
            Disconnect();
            nextAttemptMs = nowMs() + RetryMs;
        }
        if (stream is not null && (clientId is null || clientId != connectedClientId))
        {
            // 切る前に消す（Discord はプロセスが切れたら消すが、すぐ消えるように）
            try
            {
                await DiscordIpc.WriteFrameAsync(stream, DiscordIpc.OpFrame, DiscordIpc.SetActivity(pid, null, nowUnixMs(), NextNonce()), stop.Token).ConfigureAwait(false);
            }
            catch (IOException)
            {
            }
            Disconnect();
            nextAttemptMs = long.MinValue / 2;
        }
        if (clientId is null)
        {
            Status = "off";
            return;
        }
        if (stream is null)
        {
            // 何も出していない（一時停止中・曲が無い）ときは、つなぎに行かない
            if (track is not { Playing: true } || nowMs() < nextAttemptMs) return;
            nextAttemptMs = nowMs() + RetryMs;
            if (!await OpenAsync(clientId).ConfigureAwait(false)) return;
        }
        var now = nowMs();
        var unix = nowUnixMs();
        if (!planner.ShouldSend(track, now, unix)) return;
        await DiscordIpc.WriteFrameAsync(stream!, DiscordIpc.OpFrame, DiscordIpc.SetActivity(pid, track, unix, NextNonce()), stop.Token).ConfigureAwait(false);
        planner.Sent(track, now, unix);
    }

    private async Task<bool> OpenAsync(string clientId)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(stop.Token);
        timeout.CancelAfter(5000);
        System.IO.Stream? opened;
        try
        {
            opened = await connect(timeout.Token).ConfigureAwait(false);
        }
        catch (Exception e) when (e is IOException or OperationCanceledException or UnauthorizedAccessException)
        {
            opened = null;
        }
        if (opened is null)
        {
            Status = "notFound";
            return false;
        }
        try
        {
            await DiscordIpc.WriteFrameAsync(opened, DiscordIpc.OpHandshake, DiscordIpc.Handshake(clientId), timeout.Token).ConfigureAwait(false);
            var reply = await DiscordIpc.ReadFrameAsync(opened, timeout.Token).ConfigureAwait(false);
            if (reply is not { Op: DiscordIpc.OpFrame } r || !DiscordIpc.IsReady(r.Json))
            {
                log?.Invoke("discord: handshake refused " + (reply?.Json ?? "(closed)"));
                Status = "refused";
                await opened.DisposeAsync().ConfigureAwait(false);
                return false;
            }
        }
        catch (Exception e) when (e is IOException or OperationCanceledException or InvalidDataException)
        {
            log?.Invoke("discord: handshake failed " + e.Message);
            Status = "notFound";
            await opened.DisposeAsync().ConfigureAwait(false);
            return false;
        }
        stream = opened;
        connectedClientId = clientId;
        planner.Reset();
        Status = "connected";
        log?.Invoke("discord: connected");
        var cancel = CancellationTokenSource.CreateLinkedTokenSource(stop.Token);
        readerCancel = cancel;
        _ = Task.Run(() => ReadLoopAsync(opened, cancel.Token));
        return true;
    }

    /// <summary>返事を読み捨てる（読まないとパイプが詰まる）。PING には PONG を返し、閉じられたらつなぎ直す。</summary>
    private async Task ReadLoopAsync(System.IO.Stream s, CancellationToken cancel)
    {
        try
        {
            while (!cancel.IsCancellationRequested)
            {
                var frame = await DiscordIpc.ReadFrameAsync(s, cancel).ConfigureAwait(false);
                if (frame is null || frame.Value.Op == DiscordIpc.OpClose) break;
                if (frame.Value.Op == DiscordIpc.OpPing)
                {
                    await DiscordIpc.WriteFrameAsync(s, DiscordIpc.OpPong, frame.Value.Json, cancel).ConfigureAwait(false);
                }
                else if (DiscordIpc.ErrorOf(frame.Value.Json) is { } error)
                {
                    log?.Invoke("discord: " + error);
                }
            }
        }
        catch (Exception e) when (e is IOException or OperationCanceledException or InvalidDataException or ObjectDisposedException)
        {
        }
        if (cancel.IsCancellationRequested) return;
        log?.Invoke("discord: disconnected");
        lock (gate)
        {
            if (ReferenceEquals(stream, s)) closedByPeer = true;
        }
        if (wake.CurrentCount == 0) wake.Release();
    }

    private volatile bool closedByPeer;

    private void Disconnect()
    {
        readerCancel?.Cancel();
        readerCancel = null;
        stream?.Dispose();
        stream = null;
        connectedClientId = null;
        closedByPeer = false;
        planner.Reset();
        if (Status == "connected") Status = "notFound";
    }

    private string NextNonce() => Interlocked.Increment(ref nonce).ToString(System.Globalization.CultureInfo.InvariantCulture);

    public void Dispose()
    {
        stop.Cancel();
        if (wake.CurrentCount == 0) wake.Release();
    }
}
