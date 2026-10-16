using System.Text.Json;
using System.Text.RegularExpressions;

namespace Wave.Core.Stream;

/// <summary>キャッシュに残した曲の形式（URL は持たない）。これがあれば、URL を調べ直さずに残した分から鳴らし始められる。</summary>
public sealed record CachedFormat(string VideoId, int Itag, string MimeType, long Length, DashInfo? Dash, long LastUsedMs);

/// <summary>
/// 曲の音のバイトを端末に残す（Mac の AudioCache・Android の SimpleCache と同じ役目）。曲ごとに、頭から途切れずに取れた分だけを
/// 1つのファイルに持つ。曲送り・戻しでは残した分から鳴らし、続きは鳴らしながら取る。上限を超えたら、最後に使ったのが古い曲から消す。
/// ファイルには音のバイトと形式だけを書き、署名付き URL は書かない。
/// </summary>
public sealed partial class AudioCache(string directory, Func<long> nowMs, long maxBytes = AudioCache.DefaultMaxBytes)
{
    public const long DefaultMaxBytes = 300L * 1024 * 1024;

    private readonly object gate = new();
    private readonly Dictionary<string, CachedFormat> formats = [];
    private bool loaded;

    /// <summary>残してある曲の形式と、頭から残っているバイト数。無ければ null。</summary>
    public (CachedFormat Format, long Cached)? Entry(string videoId)
    {
        lock (gate)
        {
            LoadIfNeeded();
            return formats.TryGetValue(videoId, out var format) ? (format, Size(videoId)) : null;
        }
    }

    /// <summary>これから取る曲の形式を書く。前に残した分が別の形式（itag・長さが違う）なら捨てる。</summary>
    public void Begin(ResolvedStream stream)
    {
        lock (gate)
        {
            LoadIfNeeded();
            if (formats.TryGetValue(stream.VideoId, out var old) && old.Itag == stream.Itag && old.Length == stream.ContentLength)
            {
                formats[stream.VideoId] = old with { LastUsedMs = nowMs() };
                return;
            }
            if (stream.ContentLength is not { } length || length <= 0 || !IsSafeId(stream.VideoId)) return;
            RemoveFiles(stream.VideoId);
            var format = new CachedFormat(stream.VideoId, stream.Itag, stream.MimeType, length, stream.Dash, nowMs());
            formats[stream.VideoId] = format;
            File.WriteAllText(FormatPath(stream.VideoId), JsonSerializer.Serialize(format));
            File.WriteAllBytes(BytesPath(stream.VideoId), []);
            Trim(stream.VideoId);
        }
    }

    /// <summary>start から end（含む）までが残っていれば返す。</summary>
    public byte[]? Read(string videoId, long start, long end)
    {
        lock (gate)
        {
            if (!formats.TryGetValue(videoId, out var format) || start < 0 || end < start || end >= Size(videoId)) return null;
            try
            {
                using var file = new FileStream(BytesPath(videoId), FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                file.Position = start;
                var data = new byte[end - start + 1];
                file.ReadExactly(data);
                formats[videoId] = format with { LastUsedMs = nowMs() };
                return data;
            }
            catch (IOException)
            {
                return null;
            }
        }
    }

    /// <summary>offset から取ったバイトを足す。残している分の続き（か、それに重なる分）だけを足し、間が空くものは足さない。</summary>
    public void Append(string videoId, long offset, byte[] data)
    {
        lock (gate)
        {
            if (!formats.TryGetValue(videoId, out var format)) return;
            var have = Size(videoId);
            if (offset > have || offset + data.Length <= have || have >= format.Length) return;
            var from = (int)(have - offset);
            var count = (int)Math.Min(data.Length - from, format.Length - have);
            try
            {
                using var file = new FileStream(BytesPath(videoId), FileMode.Append, FileAccess.Write, FileShare.Read);
                file.Write(data, from, count);
                formats[videoId] = format with { LastUsedMs = nowMs() };
            }
            catch (IOException)
            {
            }
        }
    }

    /// <summary>ログアウトのとき（聴いた曲を残さない）。</summary>
    public void Clear()
    {
        lock (gate)
        {
            try
            {
                if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
            }
            catch (IOException)
            {
            }
            formats.Clear();
            loaded = true;
        }
    }

    /// <summary>残しているバイトの合計。</summary>
    public long TotalBytes
    {
        get
        {
            lock (gate)
            {
                LoadIfNeeded();
                return formats.Keys.Sum(Size);
            }
        }
    }

    private void LoadIfNeeded()
    {
        if (loaded) return;
        loaded = true;
        Directory.CreateDirectory(directory);
        foreach (var path in Directory.EnumerateFiles(directory, "*.json"))
        {
            try
            {
                if (JsonSerializer.Deserialize<CachedFormat>(File.ReadAllText(path)) is { } format && IsSafeId(format.VideoId)) formats[format.VideoId] = format;
            }
            catch (Exception e) when (e is IOException or JsonException)
            {
            }
        }
    }

    private void Trim(string keep)
    {
        var total = formats.Keys.Sum(Size);
        // 今から取る曲の分も見込む
        if (formats.TryGetValue(keep, out var kept)) total += kept.Length - Size(keep);
        foreach (var format in formats.Values.OrderBy(f => f.LastUsedMs).ToList())
        {
            if (total <= maxBytes) break;
            if (format.VideoId == keep) continue;
            total -= Size(format.VideoId);
            RemoveFiles(format.VideoId);
            formats.Remove(format.VideoId);
        }
    }

    private void RemoveFiles(string videoId)
    {
        File.Delete(BytesPath(videoId));
        File.Delete(FormatPath(videoId));
    }

    private string BytesPath(string videoId) => Path.Combine(directory, videoId + ".bin");
    private string FormatPath(string videoId) => Path.Combine(directory, videoId + ".json");

    private long Size(string videoId)
    {
        var info = new FileInfo(BytesPath(videoId));
        return info.Exists ? info.Length : 0;
    }

    /// <summary>ファイル名に使ってよい videoId（YouTube の 11 文字）。</summary>
    internal static bool IsSafeId(string videoId) => VideoIdPattern().IsMatch(videoId);

    [GeneratedRegex("^[A-Za-z0-9_-]{11}$")]
    private static partial Regex VideoIdPattern();
}
