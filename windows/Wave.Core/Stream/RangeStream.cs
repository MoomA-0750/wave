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

    private readonly HttpClient http;
    private readonly string url;
    private readonly long length;
    private long position;
    private byte[]? chunk;
    private long chunkStart;

    private RangeStream(HttpClient http, string url, long length)
    {
        this.http = http;
        this.url = url;
        this.length = length;
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

    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancel = default)
    {
        if (position >= length || buffer.Length == 0) return 0;
        if (chunk is null || position < chunkStart || position >= chunkStart + chunk.Length)
        {
            chunkStart = position;
            var end = Math.Min(length - 1, position + ChunkSize - 1);
            chunk = await FetchAsync(position, end, cancel);
            if (chunk.Length == 0) return 0;
        }
        var from = (int)(position - chunkStart);
        var n = Math.Min(buffer.Length, chunk.Length - from);
        chunk.AsMemory(from, n).CopyTo(buffer);
        position += n;
        return n;
    }

    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancel) =>
        ReadAsync(buffer.AsMemory(offset, count), cancel).AsTask();

    private async Task<byte[]> FetchAsync(long start, long end, CancellationToken cancel)
    {
        // 一時的な失敗は2回まで送り直す。拒否はそのまま知らせる
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                using var response = await SendAsync(start, end, cancel);
                return await response.Content.ReadAsByteArrayAsync(cancel);
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
        request.Headers.TryAddWithoutValidation("User-Agent", Net.InnerTubeClient.UserAgent);
        var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
        if (response.StatusCode is HttpStatusCode.PartialContent or HttpStatusCode.OK) return response;
        var status = (int)response.StatusCode;
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
