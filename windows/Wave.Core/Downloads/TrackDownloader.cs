using Wave.Core.Music;
using Wave.Core.Stream;

namespace Wave.Core.Downloads;

/// <summary>1曲を取った結果。</summary>
public enum DownloadOutcome
{
    Done,
    /// <summary>その曲だけ取れない（再生できない・音声の形式が無い・年齢の確認など）。この起動の間は取り直さない。</summary>
    Unavailable,
    /// <summary>ライブ配信だった（取らない）。</summary>
    Live,
    /// <summary>ログインしていない（ログインするまで取らない）。</summary>
    NotLoggedIn,
    /// <summary>通信などの失敗（あとで取り直す）。</summary>
    Failed,
    /// <summary>取っている途中で要らなくなった（消された）。</summary>
    Cancelled,
}

/// <summary>
/// 曲を丸ごと1つのファイルに取る（ADR-0016 の 5）。音質は再生と同じ（StreamResolver が選ぶ形式）。頭から区切りごとに取り、
/// 途中で止まっても続きから取れる。URL を拒否されたら（403 など）調べ直してやり直す。URL はログ・ファイルに書かない。
/// </summary>
public sealed class TrackDownloader(HttpClient http, DownloadStore store)
{
    public const int ChunkSize = 1024 * 1024;
    /// <summary>拒否されて調べ直す回数。</summary>
    private const int MaxResolves = 3;

    /// <summary>動作の記録（状態・バイト数だけ）。</summary>
    public Action<string>? Log { get; set; }

    /// <summary>YouTube の曲（ポッドキャストのエピソードも videoId で同じ）を取る。progress には取れたバイト数と全体の長さを渡す。</summary>
    public async Task<DownloadOutcome> DownloadSongAsync(Song song, Func<CancellationToken, Task<ResolveResult>> resolve, Action? onRejected, Func<bool> stillWanted,
        Action<long, long>? progress = null, CancellationToken cancel = default)
    {
        if (song.IsLive) return DownloadOutcome.Live;
        for (var attempt = 0; attempt < MaxResolves; attempt++)
        {
            ResolveResult result;
            try
            {
                result = await resolve(cancel);
            }
            catch (HttpRequestException)
            {
                return DownloadOutcome.Failed;
            }
            if (result.Stream is not { } stream)
            {
                Log?.Invoke($"download resolve failed reason={result.Failure}");
                return result.Failure switch
                {
                    StreamFailure.LiveStream => DownloadOutcome.Live,
                    StreamFailure.NotLoggedIn => DownloadOutcome.NotLoggedIn,
                    { } f when StreamFailures.IsThisSongOnly(f) => DownloadOutcome.Unavailable,
                    _ => DownloadOutcome.Failed,
                };
            }
            try
            {
                var bytes = await RangeStream.OpenAsync(http, stream, cancel);
                var length = bytes.Length;
                if (length <= 0) return DownloadOutcome.Failed;
                var offset = store.Begin(new DownloadedFile(song.VideoId, stream.MimeType, length, stream.Itag, stream.Dash, Song: song));
                var outcome = await CopyAsync(song.VideoId, bytes, offset, length, stillWanted, progress, cancel);
                if (outcome != DownloadOutcome.Done) return outcome;
                return store.Complete(song.VideoId) ? DownloadOutcome.Done : DownloadOutcome.Failed;
            }
            catch (StreamRejectedException e)
            {
                // 期限切れ・拒否: 調べ直してやり直す（取れた分はそのまま、続きから）
                Log?.Invoke($"download rejected status={e.Status} attempt={attempt}");
                onRejected?.Invoke();
            }
            catch (Exception e) when (e is HttpRequestException or IOException or TaskCanceledException && !cancel.IsCancellationRequested)
            {
                Log?.Invoke($"download failed type={e.GetType().Name}");
                return DownloadOutcome.Failed;
            }
        }
        return DownloadOutcome.Failed;
    }

    /// <summary>
    /// RSS のエピソード（ADR-0012）を取る。http は cookie を持たず https の間だけリダイレクトを追うクライアント。
    /// Range に応じる配信元は区切りごとに（続きから取れる）、応じない配信元は頭から1回で取る。
    /// </summary>
    public async Task<DownloadOutcome> DownloadEpisodeAsync(Song song, HttpClient feedHttp, string userAgent, Func<bool> stillWanted,
        Action<long, long>? progress = null, CancellationToken cancel = default)
    {
        if (Feed.AudioUrlOf(song.VideoId) is not { } url) return DownloadOutcome.Unavailable;
        try
        {
            var (bytes, final, type) = await RangeStream.OpenEpisodeAsync(feedHttp, url, userAgent, cancel);
            var mime = Feed.AudioMime(type, final);
            if (bytes is not null)
            {
                var offset = store.Begin(new DownloadedFile(song.VideoId, mime, bytes.Length, Song: song));
                var outcome = await CopyAsync(song.VideoId, bytes, offset, bytes.Length, stillWanted, progress, cancel);
                if (outcome != DownloadOutcome.Done) return outcome;
                return store.Complete(song.VideoId) ? DownloadOutcome.Done : DownloadOutcome.Failed;
            }
            // Range に応じない: 頭から全部（長さは取り終えて分かる）
            store.Begin(new DownloadedFile(song.VideoId, mime, 0, Song: song));
            using var request = new HttpRequestMessage(HttpMethod.Get, final);
            request.Headers.TryAddWithoutValidation("User-Agent", userAgent);
            using var response = await feedHttp.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
            if (!response.IsSuccessStatusCode || response.RequestMessage?.RequestUri?.Scheme != Uri.UriSchemeHttps) return DownloadOutcome.Failed;
            var total = response.Content.Headers.ContentLength ?? 0;
            await using var body = await response.Content.ReadAsStreamAsync(cancel);
            var buffer = new byte[256 * 1024];
            long have = 0;
            int read;
            while ((read = await body.ReadAsync(buffer, cancel)) > 0)
            {
                if (!stillWanted()) return DownloadOutcome.Cancelled;
                if (!store.Append(song.VideoId, have, buffer.AsSpan(0, read))) return DownloadOutcome.Cancelled;
                have += read;
                progress?.Invoke(have, total);
            }
            return have > 0 && store.Complete(song.VideoId, have) ? DownloadOutcome.Done : DownloadOutcome.Failed;
        }
        catch (StreamRejectedException e)
        {
            Log?.Invoke($"episode download rejected status={e.Status}");
            return DownloadOutcome.Failed;
        }
        catch (Exception e) when (e is HttpRequestException or IOException or UriFormatException or TaskCanceledException && !cancel.IsCancellationRequested)
        {
            Log?.Invoke($"episode download failed type={e.GetType().Name}");
            return DownloadOutcome.Failed;
        }
    }

    /// <summary>offset から終わりまで、区切りごとに取ってファイルに足す。</summary>
    private async Task<DownloadOutcome> CopyAsync(string videoId, RangeStream bytes, long offset, long length, Func<bool> stillWanted, Action<long, long>? progress, CancellationToken cancel)
    {
        while (offset < length)
        {
            cancel.ThrowIfCancellationRequested();
            if (!stillWanted()) return DownloadOutcome.Cancelled;
            var end = Math.Min(length - 1, offset + ChunkSize - 1);
            var data = await bytes.ReadRangeAsync(offset, end, cancel);
            if (data.Length == 0) return DownloadOutcome.Failed;
            var count = (int)Math.Min(data.Length, end - offset + 1);
            if (!store.Append(videoId, offset, data.AsSpan(0, count))) return DownloadOutcome.Cancelled;
            offset += count;
            progress?.Invoke(offset, length);
        }
        return DownloadOutcome.Done;
    }
}
