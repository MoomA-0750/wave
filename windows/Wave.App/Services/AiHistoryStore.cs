using Wave.Core.Ai;

namespace Wave.App.Services;

/// <summary>
/// Claude との会話の履歴のファイル（データの置き場の ai_history.json。Android の AiHistoryStore と同じ）。
/// 端末の中だけに置き、同期しない。発言と曲の情報だけで、ログインの情報は入れない。
/// 書き込みは一時ファイルに書いてから置き換える（途中で落ちても前の内容か新しい内容のどちらかが残る）。UI スレッドから使う。
/// </summary>
public sealed class AiHistoryStore
{
    private readonly string path = Path.Combine(AppPaths.Data, "ai_history.json");
    private List<AiConversation>? conversations;

    /// <summary>新しく話した順。</summary>
    public IReadOnlyList<AiConversation> Conversations => conversations ??= Load();

    public void Save(AiConversation conversation) => Write(AiHistory.Upsert(Conversations, conversation));

    public void Remove(string id) => Write(AiHistory.Remove(Conversations, id));

    /// <summary>ログアウトのとき: 全部消す。</summary>
    public void Clear() => Write([]);

    private List<AiConversation> Load()
    {
        try
        {
            return File.Exists(path) ? AiHistory.Decode(File.ReadAllText(path)) : [];
        }
        catch (IOException e)
        {
            AppPaths.Log($"ai history load failed type={e.GetType().Name}");
            return [];
        }
    }

    private void Write(List<AiConversation> list)
    {
        conversations = list;
        try
        {
            if (list.Count == 0)
            {
                if (File.Exists(path)) File.Delete(path);
                return;
            }
            File.WriteAllText(path + ".tmp", AiHistory.Encode(list));
            File.Move(path + ".tmp", path, overwrite: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            AppPaths.Log($"ai history save failed type={e.GetType().Name}");
        }
    }
}
