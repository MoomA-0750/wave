using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Dispatching;
using Wave.Core.Auth;
using Wave.Core.Net;

namespace Wave.App.Services;

/// <summary>ログインの状態とアカウントの名前・画像。</summary>
public sealed partial class AccountState : ObservableObject
{
    [ObservableProperty] private bool isLoggedIn;
    [ObservableProperty] private string? name;
    [ObservableProperty] private string? photoUrl;
    /// <summary>保存したログイン情報が読めなかった（別のユーザーで暗号化された・壊れた）。</summary>
    [ObservableProperty] private bool savedLoginUnreadable;
    /// <summary>Premium 音質を使えるか（null は確かめている・分からない）。</summary>
    [ObservableProperty] private bool? premiumAudio;
    /// <summary>アカウントを読めなかった（通信の失敗も含む。Premium 音質を「分かりません」と出すだけで、切れたことにはしない）。</summary>
    [ObservableProperty] private bool accountCheckFailed;
    /// <summary>
    /// ログインの状態（Android と同じ分け方。ADR-0004）。Expired は切れたと確かめられた（再ログインが必要）、
    /// Suspect は疑い（確かめている）。通信の失敗だけではどちらにもしない。
    /// </summary>
    [ObservableProperty] private SessionStatus session;
    /// <summary>アカウントを確かめ終えた（確かめている間は「確かめています」と出す）。</summary>
    [ObservableProperty] private bool accountChecked;

    public event Action? Changed;

    /// <summary>今のログインの取得時刻（ログインしていなければ 0）。保存したキューを、同じログインのときだけ戻すのに使う。</summary>
    public long ObtainedAtEpochMs { get; private set; }

    public AccountState(DispatcherQueue ui)
    {
        // 状態は通信の途中から変わるので、画面の糸へ移して持つ
        AppServices.Client.Session.Changed += status => ui.TryEnqueue(() =>
        {
            Session = AppServices.Client.Session.Status;
            AppPaths.Log($"session status={Session}");
        });
        Session = AppServices.Client.Session.Status;
    }

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
        ObtainedAtEpochMs = credentials.ObtainedAtEpochMs;
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
        AccountCheckFailed = !result.IsOk;
        AccountChecked = true;
        PremiumAudio = result.IsOk ? await AppServices.Resolver.PremiumAudioAsync() : null;
        AppPaths.Log($"account check ok={result.IsOk} failure={result.Failure}");
    }

    public void LogOut()
    {
        AppServices.Player.Stop();
        AppServices.Client.SetCredentials(null);
        AppServices.Credentials.Clear();
        AppServices.Releases.Clear();
        // 前のアカウントの Claude との会話を残さない（履歴も消す。Android と同じ）
        AppServices.Ai.Wipe();
        // 前のアカウントの検索の履歴とプレイリストの並べ方も残さない（Android と同じ）
        AppServices.Settings.SearchHistory.Clear();
        AppServices.Settings.PlaylistOrders.Clear();
        AppServices.Settings.Save();
        ObtainedAtEpochMs = 0;
        IsLoggedIn = false;
        PremiumAudio = null;
        AccountCheckFailed = false;
        AccountChecked = false;
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
