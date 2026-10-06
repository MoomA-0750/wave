using System.Text.Json;
using Wave.Core.Music;

namespace Wave.App.Services;

/// <summary>保存したキューの1項目。</summary>
public sealed record SavedQueueItem(Song Song, string ItemId, QueueOrigin Origin);

/// <summary>
/// 次の起動に持ち越すキュー（Android の SavedQueue と同じ考え）。ObtainedAtEpochMs は保存したときのログインの取得時刻で、
/// 今のログインと違えば戻さない（別のアカウントのキューを出さない）。
/// </summary>
public sealed record SavedQueue(
    int Version,
    long ObtainedAtEpochMs,
    List<SavedQueueItem> Items,
    string CurrentItemId,
    long PositionMs,
    int Repeat,
    string? SourceId,
    string? RadioSeed,
    string? Continuation,
    string? RadioPlaylistId)
{
    public const int CurrentVersion = 1;
}

/// <summary>
/// キューの保存ファイル。データの置き場に置き、暗号化はしない（曲の情報だけで、ログイン情報は入れない。Android と同じ）。
/// 書き込みは一時ファイルに書いてから置き換える（途中で落ちても前の内容か新しい内容のどちらかが残る）。
/// </summary>
public sealed class QueueStore
{
    private readonly string path = Path.Combine(AppPaths.Data, "queue.json");
    private readonly object gate = new();

    public SavedQueue? Load()
    {
        try
        {
            if (!File.Exists(path)) return null;
            var saved = JsonSerializer.Deserialize<SavedQueue>(File.ReadAllText(path));
            return saved?.Version == SavedQueue.CurrentVersion && saved.Items is { Count: > 0 } ? saved : null;
        }
        catch (Exception e) when (e is JsonException or IOException or NotSupportedException)
        {
            AppPaths.Log($"queue load failed type={e.GetType().Name}");
            return null;
        }
    }

    public void Save(SavedQueue queue)
    {
        var text = JsonSerializer.Serialize(queue);
        lock (gate)
        {
            try
            {
                File.WriteAllText(path + ".tmp", text);
                File.Move(path + ".tmp", path, overwrite: true);
            }
            catch (IOException e)
            {
                AppPaths.Log($"queue save failed type={e.GetType().Name}");
            }
        }
    }

    public void Delete()
    {
        lock (gate)
        {
            try
            {
                if (File.Exists(path)) File.Delete(path);
            }
            catch (IOException)
            {
            }
        }
    }
}
