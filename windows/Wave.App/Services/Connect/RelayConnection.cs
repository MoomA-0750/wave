using System.Net.WebSockets;
using System.Text;
using System.Text.Json.Nodes;
using Wave.Core.Connect;

namespace Wave.App.Services.Connect;

public enum RelayStatus
{
    Disconnected,
    Connecting,
    Connected,

    /// <summary>名前を解決できない（Tailscale につながっていない）。</summary>
    TailnetUnreachable,

    /// <summary>中継に届くが、この端末は家に入っていない（認証で断られた・外された）。</summary>
    NotMember,
    Failed,
}

/// <summary>
/// 中継 v2 への接続（Android の RelayClientV2 と同じ規則）。使う側が参照を数え、0 になったら閉じる。切れたら間を空けてつなぎ直す。
/// 受けたメッセージは JSON の文字列のまま deliver に渡す（呼ぶ側が1本のスレッドへ移す）。ログは状態と種類だけ。
/// </summary>
public sealed class RelayConnection
{
    private static readonly TimeSpan ReconnectDelay = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan MaxReconnectDelay = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan PairTimeout = TimeSpan.FromMinutes(2);

    private readonly string baseUrl;
    private readonly string relayHost;
    private readonly byte[] identityPub;
    private readonly Func<string, byte[], byte[]> sign;
    private readonly Action<string> deliver;
    private readonly Action onConnectionLost;
    private readonly object gate = new();

    private ClientWebSocket? socket;
    private CancellationTokenSource? socketCancel;
    /// <summary>今のソケットへ送るもの（1本の送り口で、積んだ順に送る）。</summary>
    private System.Threading.Channels.Channel<byte[]>? outbox;
    private int users;
    private int failedAttempts;
    private CancellationTokenSource? reconnectCancel;
    private RelayStatus status = RelayStatus.Disconnected;

    public event Action<RelayStatus>? StatusChanged;

    public RelayStatus Status
    {
        get { lock (gate) return status; }
    }

    /// <param name="baseUrl">例: wss://&lt;中継のホスト&gt;:&lt;ポート&gt;</param>
    /// <param name="relayHost">署名の対象に入れるホスト名とポート（中継の設定と同じ値）</param>
    /// <param name="sign">身元鍵での、用途ラベル付きの署名（DER）</param>
    public RelayConnection(string baseUrl, string relayHost, byte[] identityPub, Func<string, byte[], byte[]> sign, Action<string> deliver, Action onConnectionLost)
    {
        this.baseUrl = baseUrl.TrimEnd('/');
        this.relayHost = relayHost;
        this.identityPub = identityPub;
        this.sign = sign;
        this.deliver = deliver;
        this.onConnectionLost = onConnectionLost;
    }

    public void Acquire()
    {
        lock (gate)
        {
            users++;
            if (users == 1 || (socket is null && status != RelayStatus.Connecting)) OpenLocked();
        }
    }

    public void Release()
    {
        lock (gate)
        {
            users = Math.Max(0, users - 1);
            if (users == 0) CloseLocked();
        }
    }

    /// <summary>断られた（家に入っていなかった）後に、つなぎ直す（加入の後）。</summary>
    public void Reconnect()
    {
        lock (gate)
        {
            if (users > 0 && socket is null) OpenLocked();
        }
    }

    /// <summary>つないでいてもつなぎ直す（家の記録を消した後に、新しい家の welcome を受け取る）。</summary>
    public void Restart()
    {
        lock (gate)
        {
            if (users == 0) return;
            CloseLocked();
            OpenLocked();
        }
    }

    public async Task<T> HoldingAsync<T>(Func<Task<T>> block)
    {
        Acquire();
        try
        {
            return await block();
        }
        finally
        {
            Release();
        }
    }

    /// <summary>つながっていれば送る（呼んだ順に送る）。送れなければ false。</summary>
    public bool Send(string json)
    {
        lock (gate)
        {
            if (status != RelayStatus.Connected || socket is null || outbox is null) return false;
            return outbox.Writer.TryWrite(Encoding.UTF8.GetBytes(json));
        }
    }

    private static async Task SendLoopAsync(ClientWebSocket ws, System.Threading.Channels.Channel<byte[]> queue, CancellationToken cancel)
    {
        try
        {
            await foreach (var bytes in queue.Reader.ReadAllAsync(cancel))
            {
                await ws.SendAsync(bytes, WebSocketMessageType.Text, true, cancel);
            }
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or ObjectDisposedException or InvalidOperationException)
        {
        }
    }

    /// <summary>家に入っていない端末から、加入の第1手を送って返事を待つ（/v2/pair、認証なし）。届かなければ null。</summary>
    public async Task<string?> PairAsync(string pairHelloInJson)
    {
        using var ws = new ClientWebSocket();
        using var timeout = new CancellationTokenSource(PairTimeout);
        try
        {
            await ws.ConnectAsync(new Uri(baseUrl + "/v2/pair"), timeout.Token);
            await ws.SendAsync(Encoding.UTF8.GetBytes(pairHelloInJson), WebSocketMessageType.Text, true, timeout.Token);
            var reply = await ReceiveTextAsync(ws, 64 * 1024, timeout.Token);
            try
            {
                await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None);
            }
            catch (Exception e) when (e is WebSocketException or OperationCanceledException)
            {
            }
            return reply;
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or HttpRequestException)
        {
            AppPaths.Log($"relay-v2 pair failed type={e.GetType().Name}");
            return null;
        }
    }

    private void SetStatus(RelayStatus next)
    {
        status = next;
        var handler = StatusChanged;
        if (handler is not null) Task.Run(() => handler(next));
    }

    private void OpenLocked()
    {
        reconnectCancel?.Cancel();
        SetStatus(RelayStatus.Connecting);
        var ws = new ClientWebSocket();
        ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(30);
        var cancel = new CancellationTokenSource();
        var queue = System.Threading.Channels.Channel.CreateUnbounded<byte[]>(new System.Threading.Channels.UnboundedChannelOptions { SingleReader = true });
        socket = ws;
        socketCancel = cancel;
        outbox = queue;
        _ = Task.Run(() => RunAsync(ws, queue, cancel.Token));
    }

    private async Task RunAsync(ClientWebSocket ws, System.Threading.Channels.Channel<byte[]> queue, CancellationToken cancel)
    {
        var next = RelayStatus.Disconnected;
        try
        {
            await ws.ConnectAsync(new Uri(baseUrl + "/v2/connect"), cancel);
            _ = SendLoopAsync(ws, queue, cancel);
            while (!cancel.IsCancellationRequested)
            {
                var text = await ReceiveTextAsync(ws, RelayV2.MaxFrameChars, cancel);
                if (text is null)
                {
                    var reason = ws.CloseStatusDescription;
                    lock (gate)
                    {
                        if (socket == ws && ((status == RelayStatus.Connecting && reason == "auth") || reason == "removed")) next = RelayStatus.NotMember;
                    }
                    break;
                }
                OnText(ws, queue, text);
            }
        }
        catch (WebSocketException e) when (e.InnerException is HttpRequestException { InnerException: System.Net.Sockets.SocketException { SocketErrorCode: System.Net.Sockets.SocketError.HostNotFound } })
        {
            next = RelayStatus.TailnetUnreachable;
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or HttpRequestException or IOException)
        {
            if (!cancel.IsCancellationRequested)
            {
                AppPaths.Log($"relay-v2 failure type={e.GetType().Name}");
                next = RelayStatus.Failed;
            }
        }
        OnDisconnected(ws, next);
    }

    private void OnText(ClientWebSocket ws, System.Threading.Channels.Channel<byte[]> queue, string text)
    {
        JsonNode? node;
        try
        {
            node = JsonNode.Parse(text);
        }
        catch (System.Text.Json.JsonException)
        {
            return;
        }
        var op = node?["op"]?.GetValue<string>();
        if (op == "challenge")
        {
            var nonceText = node!["nonce"]?.GetValue<string>();
            if (nonceText is null) return;
            if (B64.Decode(nonceText) is not { } nonce) return;
            // 中継が名乗るホストではなく、設定のホストで署名する
            var signature = sign(RelayV2.AuthLabel, WaveCrypto.LengthPrefixed(Encoding.UTF8.GetBytes(relayHost), nonce));
            var auth = RelayV2.Encode(new RelayV2.Auth { PublicKey = B64.Encode(identityPub), Signature = B64.Encode(signature) });
            queue.Writer.TryWrite(Encoding.UTF8.GetBytes(auth));
            return;
        }
        if (op == "welcome")
        {
            lock (gate)
            {
                if (socket != ws) return;
                failedAttempts = 0;
                SetStatus(RelayStatus.Connected);
            }
            AppPaths.Log("relay-v2 connected");
        }
        lock (gate)
        {
            if (socket != ws) return;
        }
        deliver(text);
    }

    private void OnDisconnected(ClientWebSocket ws, RelayStatus next)
    {
        lock (gate)
        {
            if (socket != ws) return;
            socket = null;
            socketCancel?.Cancel();
            socketCancel = null;
            outbox?.Writer.TryComplete();
            outbox = null;
            SetStatus(next);
            if (users > 0 && next != RelayStatus.NotMember)
            {
                var wait = TimeSpan.FromTicks(Math.Min(ReconnectDelay.Ticks << Math.Min(failedAttempts, 4), MaxReconnectDelay.Ticks));
                failedAttempts++;
                var cancel = new CancellationTokenSource();
                reconnectCancel = cancel;
                _ = Task.Run(async () =>
                {
                    try
                    {
                        await Task.Delay(wait, cancel.Token);
                    }
                    catch (OperationCanceledException)
                    {
                        return;
                    }
                    lock (gate)
                    {
                        if (users > 0 && socket is null) OpenLocked();
                    }
                });
            }
        }
        ws.Dispose();
        onConnectionLost();
    }

    private void CloseLocked()
    {
        reconnectCancel?.Cancel();
        var closing = socket;
        var cancel = socketCancel;
        outbox?.Writer.TryComplete();
        outbox = null;
        socket = null;
        socketCancel = null;
        SetStatus(RelayStatus.Disconnected);
        if (closing is null) return;
        _ = Task.Run(async () =>
        {
            try
            {
                await closing.CloseAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(3));
            }
            catch (Exception e) when (e is WebSocketException or OperationCanceledException or TimeoutException or ObjectDisposedException)
            {
            }
            cancel?.Cancel();
            closing.Dispose();
        });
        onConnectionLost();
    }

    /// <summary>テキストの1メッセージを受ける。閉じられたら null。上限を超えたら閉じる。</summary>
    private static async Task<string?> ReceiveTextAsync(ClientWebSocket ws, int maxChars, CancellationToken cancel)
    {
        var buffer = new byte[16 * 1024];
        using var message = new MemoryStream();
        while (true)
        {
            var result = await ws.ReceiveAsync(buffer, cancel);
            if (result.MessageType == WebSocketMessageType.Close) return null;
            message.Write(buffer, 0, result.Count);
            if (message.Length > maxChars * 4L)
            {
                await ws.CloseAsync(WebSocketCloseStatus.MessageTooBig, null, cancel);
                return null;
            }
            if (result.EndOfMessage) break;
        }
        return Encoding.UTF8.GetString(message.GetBuffer(), 0, (int)message.Length);
    }
}
