using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Wave.Core.Music;
using Wave.Core.Stream;

namespace Wave.Core.Downloads;

/// <summary>
/// 取った曲の形式（<c>&lt;名前&gt;.json</c>）。AudioCache の形式に、取り終えた印と、通信なしで一覧を出すための曲の情報を足したもの。
/// 署名付き URL は書かない。RSS のエピソードは Itag が 0 で Dash が無い。
/// </summary>
public sealed record DownloadedFile(string VideoId, string MimeType, long Length, int Itag = 0, DashInfo? Dash = null, bool Complete = false, Song? Song = null, long DownloadedAtMs = 0);

/// <summary>
/// 取った曲を置く、消えない置き場（ADR-0016 の 4）。速く鳴らすための AudioCache とは別で、上限で消さない。
/// 曲は <c>&lt;videoId&gt;.bin</c> と <c>&lt;videoId&gt;.json</c>、RSS のエピソードは音声の URL の SHA-256 を名前にする。目録は downloads.json。
/// 取っている途中の曲は Complete が false のまま、頭から取れた分だけを .bin に持つ（続きから取り直せる）。
/// </summary>
public sealed class DownloadStore(string directory, Func<long> nowMs)
{
    public const string ManifestName = "downloads.json";

    private readonly object gate = new();
    private readonly Dictionary<string, DownloadedFile> files = [];
    private bool loaded;

    public string Directory => directory;

    /// <summary>ファイルの名前（videoId か、RSS のエピソードなら音声の URL の SHA-256）。使えない ID なら null。</summary>
    public static string? KeyOf(string videoId)
    {
        if (Feed.AudioUrlOf(videoId) is { } url) return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(url))).ToLowerInvariant();
        return AudioCache.IsSafeId(videoId) ? videoId : null;
    }

    public DownloadedFile? Entry(string videoId)
    {
        lock (gate)
        {
            LoadIfNeeded();
            return KeyOf(videoId) is { } key && files.TryGetValue(key, out var file) ? file : null;
        }
    }

    /// <summary>取り終えていて、ファイルの大きさも合っている。</summary>
    public bool IsComplete(string videoId)
    {
        lock (gate)
        {
            LoadIfNeeded();
            return KeyOf(videoId) is { } key && files.TryGetValue(key, out var file) && file.Complete && Size(key) == file.Length;
        }
    }

    /// <summary>取り終えた曲の形式（鳴らすとき。取っていない・途中なら null）。</summary>
    public DownloadedFile? Completed(string videoId) => IsComplete(videoId) ? Entry(videoId) : null;

    /// <summary>音のファイルの場所。</summary>
    public string BytesPath(string videoId) => Path.Combine(directory, (KeyOf(videoId) ?? "_") + ".bin");

    /// <summary>取れているバイト数。</summary>
    public long Downloaded(string videoId)
    {
        lock (gate) return KeyOf(videoId) is { } key ? Size(key) : 0;
    }

    /// <summary>
    /// これから取る曲の形式を書き、続きを取り始める位置を返す。前に取った分が同じ形式（itag・長さ）なら続きから、違えば捨てて頭から。
    /// </summary>
    public long Begin(DownloadedFile format)
    {
        lock (gate)
        {
            LoadIfNeeded();
            if (KeyOf(format.VideoId) is not { } key) throw new ArgumentException("unsafe id");
            if (files.TryGetValue(key, out var old) && old.Itag == format.Itag && old.Length == format.Length && old.Length > 0)
            {
                var have = Size(key);
                if (have <= old.Length) return have;
            }
            System.IO.Directory.CreateDirectory(directory);
            var fresh = format with { Complete = false, DownloadedAtMs = 0 };
            files[key] = fresh;
            File.WriteAllBytes(BytesPathOf(key), []);
            WriteFormat(key, fresh);
            return 0;
        }
    }

    /// <summary>offset から取ったバイトを足す（取れている分の続きのときだけ。消された曲には足さない）。足せたか。</summary>
    public bool Append(string videoId, long offset, ReadOnlySpan<byte> data)
    {
        lock (gate)
        {
            if (KeyOf(videoId) is not { } key || !files.TryGetValue(key, out var format) || format.Complete) return false;
            if (Size(key) != offset) return false;
            using var file = new FileStream(BytesPathOf(key), FileMode.Append, FileAccess.Write, FileShare.Read);
            file.Write(data);
            return true;
        }
    }

    /// <summary>取り終えた印を付ける。length を渡すと長さもそれにする（長さの分からない RSS の配信元）。大きさが合わなければ付けない。</summary>
    public bool Complete(string videoId, long? length = null)
    {
        lock (gate)
        {
            if (KeyOf(videoId) is not { } key || !files.TryGetValue(key, out var format)) return false;
            var size = Size(key);
            var done = format with { Complete = true, Length = length ?? format.Length, DownloadedAtMs = nowMs() };
            if (size != done.Length) return false;
            files[key] = done;
            WriteFormat(key, done);
            return true;
        }
    }

    public void Remove(string videoId)
    {
        lock (gate)
        {
            LoadIfNeeded();
            if (KeyOf(videoId) is { } key) RemoveKey(key);
        }
    }

    /// <summary>持ち主のいない曲のファイルを消す（目録を変えたあと）。消した数を返す。</summary>
    public int Purge(IEnumerable<string> wantedIds)
    {
        lock (gate)
        {
            LoadIfNeeded();
            var keep = wantedIds.Select(KeyOf).OfType<string>().ToHashSet();
            var removed = 0;
            foreach (var key in files.Keys.Where(k => !keep.Contains(k)).ToList())
            {
                RemoveKey(key);
                removed++;
            }
            return removed;
        }
    }

    /// <summary>取ったファイルの大きさの合計（途中の分も含む）。</summary>
    public long TotalBytes
    {
        get
        {
            lock (gate)
            {
                LoadIfNeeded();
                return files.Keys.Sum(Size);
            }
        }
    }

    public DownloadManifest ReadManifest()
    {
        try
        {
            var path = Path.Combine(directory, ManifestName);
            return File.Exists(path) ? DownloadManifest.Decode(File.ReadAllText(path)) ?? DownloadManifest.Empty : DownloadManifest.Empty;
        }
        catch (IOException)
        {
            return DownloadManifest.Empty;
        }
    }

    public void WriteManifest(DownloadManifest manifest)
    {
        lock (gate)
        {
            System.IO.Directory.CreateDirectory(directory);
            var path = Path.Combine(directory, ManifestName);
            // 書いている途中で落ちても前の目録が残るよう、別の名前に書いてから置き換える
            File.WriteAllText(path + ".tmp", manifest.Encode());
            File.Move(path + ".tmp", path, overwrite: true);
        }
    }

    /// <summary>全部消す（ログアウト・「ダウンロードをすべて削除」）。</summary>
    public void Clear()
    {
        lock (gate)
        {
            try
            {
                if (System.IO.Directory.Exists(directory)) System.IO.Directory.Delete(directory, recursive: true);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                // 鳴らしている曲などで消せなかったファイルは、次に目録を変えたときに消える
                foreach (var path in System.IO.Directory.EnumerateFiles(directory))
                {
                    try
                    {
                        File.Delete(path);
                    }
                    catch (Exception inner) when (inner is IOException or UnauthorizedAccessException)
                    {
                    }
                }
            }
            files.Clear();
            loaded = true;
        }
    }

    private void LoadIfNeeded()
    {
        if (loaded) return;
        loaded = true;
        if (!System.IO.Directory.Exists(directory)) return;
        foreach (var path in System.IO.Directory.EnumerateFiles(directory, "*.json"))
        {
            var key = Path.GetFileNameWithoutExtension(path);
            if (Path.GetFileName(path) == ManifestName) continue;
            try
            {
                if (JsonSerializer.Deserialize<DownloadedFile>(File.ReadAllText(path)) is { VideoId: not null } file && KeyOf(file.VideoId) == key) files[key] = file;
            }
            catch (Exception e) when (e is IOException or JsonException)
            {
            }
        }
    }

    private void WriteFormat(string key, DownloadedFile format) => File.WriteAllText(Path.Combine(directory, key + ".json"), JsonSerializer.Serialize(format));

    private void RemoveKey(string key)
    {
        files.Remove(key);
        foreach (var path in new[] { BytesPathOf(key), Path.Combine(directory, key + ".json") })
        {
            try
            {
                File.Delete(path);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
            }
        }
    }

    private string BytesPathOf(string key) => Path.Combine(directory, key + ".bin");

    private long Size(string key)
    {
        var info = new FileInfo(BytesPathOf(key));
        return info.Exists ? info.Length : 0;
    }
}
