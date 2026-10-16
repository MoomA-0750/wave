using System.Net;
using System.Net.Http.Headers;
using Wave.Core.Stream;

namespace Wave.Core.Tests;

public sealed class AudioCacheTests : IDisposable
{
    private readonly string directory = Path.Combine(Path.GetTempPath(), "wave-audio-" + Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
    }

    private static ResolvedStream Stream(string videoId, long length, int itag = 140, string url = "https://x.googlevideo.com/videoplayback?id=1") =>
        new(videoId, itag, "audio/mp4", length, url, 0, null, 0, new DashInfo(0, 9, 10, 19, 1, 44100, 1000, "mp4a.40.2"));

    /// <summary>Range の要求に、0..size-1 の各バイトが (i % 251) の本文で答える。</summary>
    private sealed class RangeServer(int size) : HttpMessageHandler
    {
        public int Count;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancel)
        {
            Interlocked.Increment(ref Count);
            var range = request.Headers.Range!.Ranges.First();
            var end = Math.Min(range.To!.Value, size - 1);
            var body = Expected(range.From!.Value, end);
            var response = new HttpResponseMessage(HttpStatusCode.PartialContent) { Content = new ByteArrayContent(body) };
            response.Content.Headers.ContentRange = new ContentRangeHeaderValue(range.From!.Value, end, size);
            return Task.FromResult(response);
        }
    }

    private static byte[] Expected(long start, long end) => Enumerable.Range((int)start, (int)(end - start + 1)).Select(i => (byte)(i % 251)).ToArray();

    [Fact]
    public async Task BytesAreKeptFromTheStartAndReadBackWithoutTheNetwork()
    {
        var cache = new AudioCache(directory, () => 0);
        var reader = await RangeStream.OpenAsync(new HttpClient(new RangeServer(400_000)), Stream("aaaaaaaaaaa", 400_000), cache: cache);
        // 目次を頭より先に読んでも、間を埋めて頭から続けて残す
        Assert.Equal(Expected(10, 19), await reader.ReadRangeAsync(10, 19));
        Assert.Equal(20, cache.Entry("aaaaaaaaaaa")?.Cached);
        await reader.ReadRangeAsync(20, 1999);
        Assert.Equal(2000, cache.Entry("aaaaaaaaaaa")?.Cached);
        // 間が大きく空くものは残さない
        await reader.ReadRangeAsync(300_000, 300_099);
        Assert.Equal(2000, cache.Entry("aaaaaaaaaaa")?.Cached);

        // 開き直すと、残した分は URL を調べずに読める
        var entry = cache.Entry("aaaaaaaaaaa")!.Value;
        Assert.Equal(140, entry.Format.Itag);
        Assert.Equal(19, entry.Format.Dash?.IndexEnd);
        var resolved = 0;
        var server = new RangeServer(400_000);
        var cached = RangeStream.OpenCached(new HttpClient(server), entry.Format, cache, () =>
        {
            Interlocked.Increment(ref resolved);
            return Task.FromResult(Stream("aaaaaaaaaaa", 400_000));
        });
        Assert.Equal(Expected(100, 199), await cached.ReadRangeAsync(100, 199));
        Assert.Equal(0, resolved);
        Assert.Equal(0, server.Count);
        // 残っていない続きは、そこで URL を調べて取る（残した分と合わせて返す）
        Assert.Equal(Expected(1990, 2009), await cached.ReadRangeAsync(1990, 2009));
        Assert.Equal(1, resolved);
        Assert.Equal(2010, cache.Entry("aaaaaaaaaaa")?.Cached);
    }

    [Fact]
    public async Task AnotherFormatOfTheSameSongIsNotMixed()
    {
        var cache = new AudioCache(directory, () => 0);
        var reader = await RangeStream.OpenAsync(new HttpClient(new RangeServer(3000)), Stream("bbbbbbbbbbb", 3000), cache: cache);
        await reader.ReadRangeAsync(0, 999);
        // 別の形式で開き直すと、前の分は捨てる
        cache.Begin(Stream("bbbbbbbbbbb", 4000, itag: 141));
        Assert.Equal(0, cache.Entry("bbbbbbbbbbb")?.Cached);
        Assert.Equal(141, cache.Entry("bbbbbbbbbbb")?.Format.Itag);
        // 調べた URL の形式が残した形式と違えば、使わない
        var cached = RangeStream.OpenCached(new HttpClient(new RangeServer(3000)), cache.Entry("bbbbbbbbbbb")!.Value.Format, cache,
            () => Task.FromResult(Stream("bbbbbbbbbbb", 3000)));
        await Assert.ThrowsAsync<StreamRejectedException>(() => cached.ReadRangeAsync(0, 9));
    }

    [Fact]
    public async Task OldestSongsAreRemovedOverTheLimitAndClearRemovesAll()
    {
        long clock = 0;
        var cache = new AudioCache(directory, () => clock, maxBytes: 2500);
        foreach (var (id, i) in new[] { "ccccccccccc", "ddddddddddd", "eeeeeeeeeee" }.Select((id, i) => (id, i)))
        {
            clock = i;
            var reader = await RangeStream.OpenAsync(new HttpClient(new RangeServer(1000)), Stream(id, 1000), cache: cache);
            await reader.ReadRangeAsync(0, 999);
        }
        // 3曲目を始めるとき、一番古い1曲目を消す
        Assert.Null(cache.Entry("ccccccccccc"));
        Assert.Equal(1000, cache.Entry("ddddddddddd")?.Cached);
        Assert.Equal(1000, cache.Entry("eeeeeeeeeee")?.Cached);
        // 作り直しても残している
        Assert.Equal(1000, new AudioCache(directory, () => 0).Entry("eeeeeeeeeee")?.Cached);
        cache.Clear();
        Assert.Null(cache.Entry("eeeeeeeeeee"));
        Assert.Equal(0, cache.TotalBytes);
    }

    [Fact]
    public async Task FilesHoldNoUrl()
    {
        var cache = new AudioCache(directory, () => 0);
        var reader = await RangeStream.OpenAsync(new HttpClient(new RangeServer(100)), Stream("fffffffffff", 100, url: "https://x.googlevideo.com/videoplayback?sig=SECRET"), cache: cache);
        await reader.ReadRangeAsync(0, 99);
        foreach (var path in Directory.EnumerateFiles(directory, "*.json"))
        {
            var text = await File.ReadAllTextAsync(path);
            Assert.DoesNotContain("SECRET", text);
            Assert.DoesNotContain("googlevideo", text);
        }
    }
}
