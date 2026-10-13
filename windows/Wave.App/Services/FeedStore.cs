using Wave.Core.Music;

namespace Wave.App.Services;

/// <summary>
/// RSS で登録した番組と聴いたところを、この端末のファイル（%LOCALAPPDATA%\wave\podcast-feeds.json）に置く（ADR-0012。Android の FeedStore と同じ形）。
/// アカウントには結びつけない（ログアウトでも消さない）。書くときは一時ファイルに書いてから置き換える。
/// </summary>
public static class FeedStore
{
    private static readonly string FilePath = Path.Combine(AppPaths.Data, "podcast-feeds.json");
    private static readonly object WriteLock = new();

    public static FeedLibrary Create()
    {
        FeedLibraryState? initial = null;
        try
        {
            if (File.Exists(FilePath)) initial = FeedLibraryState.Decode(File.ReadAllText(FilePath));
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
        var state = initial ?? FeedLibraryState.Empty;
        AppPaths.Log($"feeds loaded subscriptions={state.Subscriptions.Count} positions={state.Positions.Count}");
        return new FeedLibrary(state, () => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), Save);
    }

    private static void Save(FeedLibraryState state)
    {
        var text = FeedLibraryState.Encode(state);
        lock (WriteLock)
        {
            try
            {
                var temp = FilePath + ".tmp";
                File.WriteAllText(temp, text);
                File.Move(temp, FilePath, overwrite: true);
            }
            catch (IOException e)
            {
                AppPaths.Log($"feeds save failed type={e.GetType().Name}");
            }
            catch (UnauthorizedAccessException)
            {
                AppPaths.Log("feeds save failed type=UnauthorizedAccess");
            }
        }
    }
}
