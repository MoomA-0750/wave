using System.Net;
using System.Net.Http.Headers;

namespace Wave.Core.Stream;

/// <summary>
/// 音声のバイトを HTTP の Range で少しずつ読む、シークできるストリーム（Android 版の DataSource と同じく、cookie を持たない
/// HttpClient で googlevideo から取る）。再生の部品にはこのストリームを渡し、署名付き URL は渡さない。
/// 拒否（403 など）は <see cref="StreamRejectedException"/> で知らせる（呼び出し側が解決し直す）。
/// </summary>
public sealed class RangeStream : System.IO.Stream
{
    public const int ChunkSize = 512 * 1024;

    /// <summary>動作の記録（位置・状態・バイト数だけ。URL は渡さない）。</summary>
    public static Action<string>? Diagnostics { get; set; }

    private readonly HttpClient http;
    private readonly string url;
    private readonly long length;
    private readonly string userAgent;
    private long position;
    private byte[]? chunk;
    private long chunkStart;

    private RangeStream(HttpClient http, string url, long length, string? userAgent = null)
    {
        this.http = http;
        this.url = url;
        this.length = length;
        this.userAgent = userAgent ?? Net.InnerTubeClient.UserAgent;
    }

    /// <summary>
    /// RSS のエピソードの音声（ADR-0012）。先頭の1バイトを取り、リダイレクトの行き先（https のものだけ）と長さを知る。
    /// 2回目からは行き先へ直接取りに行く（計測用の転送を何度も通らない）。Range に応じない配信元なら Stream は null。
    /// http には cookie を持たず、https の間だけリダイレクトを追うクライアントを渡す。
    /// </summary>
    public static async Task<(RangeStream? Stream, string Url, string? ContentType)> OpenEpisodeAsync(HttpClient http, string url, string userAgent, CancellationToken cancel = default)
    {
        if (!url.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) throw new StreamRejectedException(0);
        var probe = new RangeStream(http, url, 0, userAgent);
        using var response = await probe.SendAsync(0, 0, cancel);
        var final = response.RequestMessage?.RequestUri;
        if (final is null || final.Scheme != Uri.UriSchemeHttps) throw new StreamRejectedException(0);
        var type = response.Content.Headers.ContentType?.MediaType;
        if (response.StatusCode == HttpStatusCode.PartialContent && response.Content.Headers.ContentRange?.Length is { } length and > 0)
            return (new RangeStream(http, final.AbsoluteUri, length, userAgent), final.AbsoluteUri, type);
        return (null, final.AbsoluteUri, type);
    }

    /// <summary>長さが分からなければ、先頭の1バイトを取って Content-Range から知る。</summary>
    public static async Task<RangeStream> OpenAsync(HttpClient http, ResolvedStream stream, CancellationToken cancel = default)
    {
        var url = stream.RevealUrl();
        if (!StreamResolver.IsAllowedMediaUrl(url)) throw new StreamRejectedException(0);
        var length = stream.ContentLength ?? 0;
        var opened = new RangeStream(http, url, length);
        if (length <= 0)
        {
            using var response = await opened.SendAsync(0, 0, cancel);
            opened = new RangeStream(http, url, response.Content.Headers.ContentRange?.Length ?? throw new StreamRejectedException((int)response.StatusCode));
        }
        return opened;
    }

    public override bool CanRead => true;
    public override bool CanSeek => true;
    public override bool CanWrite => false;
    public override long Length => length;

    public override long Position
    {
        get => position;
        set => position = Math.Clamp(value, 0, length);
    }

    public override long Seek(long offset, SeekOrigin origin)
    {
        Position = origin switch
        {
            SeekOrigin.Begin => offset,
            SeekOrigin.Current => position + offset,
            _ => length + offset,
        };
        return position;
    }

    public override int Read(byte[] buffer, int offset, int count) => ReadAsync(buffer.AsMemory(offset, count)).AsTask().GetAwaiter().GetResult();

    /// <summary>
    /// 求められた分を、終わりに着くまで埋めて返す（区切りの途中で短く返すと、Windows の再生部品が終わりと受け取るため）。
    /// </summary>
    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancel = default)
    {
        var total = 0;
        while (total < buffer.Length && position < length)
        {
            if (chunk is null || position < chunkStart || position >= chunkStart + chunk.Length)
            {
                chunkStart = position;
                var end = Math.Min(length - 1, position + ChunkSize - 1);
                chunk = await FetchAsync(position, end, cancel);
                if (chunk.Length == 0) break;
            }
            var from = (int)(position - chunkStart);
            var n = Math.Min(buffer.Length - total, chunk.Length - from);
            chunk.AsMemory(from, n).CopyTo(buffer[total..]);
            position += n;
            total += n;
        }
        return total;
    }

    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancel) =>
        ReadAsync(buffer.AsMemory(offset, count), cancel).AsTask();

    /// <summary>start から end（含む）までのバイトを取る（DASH の再生部品の求めに応じて使う）。</summary>
    public Task<byte[]> ReadRangeAsync(long start, long end, CancellationToken cancel = default) =>
        FetchAsync(Math.Clamp(start, 0, length - 1), Math.Clamp(end, start, length - 1), cancel);

    private async Task<byte[]> FetchAsync(long start, long end, CancellationToken cancel)
    {
        // 一時的な失敗は2回まで送り直す。拒否はそのまま知らせる
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                using var response = await SendAsync(start, end, cancel);
                var bytes = await response.Content.ReadAsByteArrayAsync(cancel);
                Diagnostics?.Invoke($"range {start}-{end} status={(int)response.StatusCode} bytes={bytes.Length}");
                return bytes;
            }
            catch (HttpRequestException) when (attempt < 2)
            {
                await Task.Delay(300 * (attempt + 1), cancel);
            }
            catch (IOException) when (attempt < 2)
            {
                await Task.Delay(300 * (attempt + 1), cancel);
            }
        }
    }

    private async Task<HttpResponseMessage> SendAsync(long start, long end, CancellationToken cancel)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Range = new RangeHeaderValue(start, end);
        request.Headers.TryAddWithoutValidation("User-Agent", userAgent);
        var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
        if (response.StatusCode is HttpStatusCode.PartialContent or HttpStatusCode.OK) return response;
        var status = (int)response.StatusCode;
        Diagnostics?.Invoke($"range {start}-{end} rejected status={status}");
        response.Dispose();
        throw new StreamRejectedException(status);
    }

    public override void Flush()
    {
    }

    public override void SetLength(long value) => throw new NotSupportedException();

    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
}

/// <summary>音声の URL が拒否された（期限切れ・403 など）。URL は持たない。</summary>
public sealed class StreamRejectedException(int status) : IOException($"stream rejected status={status}")
{
    public int Status { get; } = status;
}
