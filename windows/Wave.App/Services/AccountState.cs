using CommunityToolkit.Mvvm.ComponentModel;
using Wave.Core.Auth;

namespace Wave.App.Services;

/// <summary>ログインの状態とアカウントの名前・画像。</summary>
public sealed partial class AccountState : ObservableObject
{
    [ObservableProperty] private bool isLoggedIn;
    [ObservableProperty] private string? name;
    [ObservableProperty] private string? photoUrl;
    /// <summary>保存したログイン情報が読めなかった（別のユーザーで暗号化された・壊れた）。</summary>
    [ObservableProperty] private bool savedLoginUnreadable;

    public event Action? Changed;

    public void Restore()
    {
        var (state, credentials) = AppServices.Credentials.Load();
        SavedLoginUnreadable = state == CredentialStore.LoadState.Unreadable;
        if (credentials is not null) Apply(credentials);
        AppPaths.Log($"account restore state={state}");
    }

    /// <summary>ログインで得た値を使い始め、暗号化して保存する。</summary>
    public bool LogIn(Credentials credentials)
    {
        if (!AppServices.Credentials.Save(credentials)) return false;
        SavedLoginUnreadable = false;
        AppServices.Player.Stop();
        Apply(credentials);
        return true;
    }

    private void Apply(Credentials credentials)
    {
        AppServices.Client.SetCredentials(credentials);
        IsLoggedIn = true;
        Changed?.Invoke();
        _ = RefreshAccountAsync();
    }

    public async Task RefreshAccountAsync()
    {
        var result = await AppServices.Music.AccountAsync();
        if (result.IsOk)
        {
            Name = result.Value!.Name;
            PhotoUrl = result.Value.PhotoUrl;
        }
        AppPaths.Log($"account check ok={result.IsOk} failure={result.Failure}");
    }

    public void LogOut()
    {
        AppServices.Player.Stop();
        AppServices.Client.SetCredentials(null);
        AppServices.Credentials.Clear();
        IsLoggedIn = false;
        Name = null;
        PhotoUrl = null;
        // ログイン用のブラウザに残る Google の cookie も消す（次の起動で消す印を置く。開いている間は消せないため）
        try
        {
            File.WriteAllText(Path.Combine(AppPaths.Data, "webview-clear"), "1");
        }
        catch (IOException)
        {
        }
        Changed?.Invoke();
    }

    /// <summary>起動時に、前回のログアウトで残ったログイン用のブラウザのデータを消す。</summary>
    public static void CleanupWebViewIfRequested()
    {
        var marker = Path.Combine(AppPaths.Data, "webview-clear");
        if (!File.Exists(marker)) return;
        try
        {
            if (Directory.Exists(AppPaths.WebView)) Directory.Delete(AppPaths.WebView, recursive: true);
            File.Delete(marker);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}
