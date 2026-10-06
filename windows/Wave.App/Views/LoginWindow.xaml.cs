using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.Web.WebView2.Core;
using Wave.App.Services;
using Wave.Core.Auth;

namespace Wave.App.Views;

/// <summary>
/// Google アカウントへのログイン（Android 版の WebView と同じやり方を WebView2 で）。
/// music.youtube.com に着いたら cookie と yt.config_ の VISITOR_DATA・DATASYNC_ID を読み、暗号化して保存する。
/// 開いてよいのはログインに要る Google・YouTube のホストだけで、ほかは止める。
/// </summary>
public sealed partial class LoginWindow : Window
{
    private const string StartUrl = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F";
    private const string LandingHost = "music.youtube.com";

    /// <summary>メインフレームで開いてよいホスト（利用者がページを置ける sites・docs などは含めない）。</summary>
    private static readonly HashSet<string> LoginHosts =
    [
        "accounts.google.com", "accounts.google.co.jp", "accounts.youtube.com", "myaccount.google.com", "gds.google.com",
        "consent.google.com", "consent.youtube.com", "google.com", "www.google.com", "www.google.co.jp",
        "youtube.com", "www.youtube.com", "m.youtube.com", LandingHost,
    ];

    private bool finishing;

    public event Action<bool>? Finished;

    public LoginWindow()
    {
        InitializeComponent();
        AppWindow.Resize(new Windows.Graphics.SizeInt32(520, 760));
        if (AppWindow.Presenter is OverlappedPresenter presenter) presenter.IsMinimizable = false;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "wave.ico"));
        Closed += (_, _) =>
        {
            if (!finishing) Finished?.Invoke(false);
        };
        _ = InitializeAsync();
    }

    internal static bool IsAllowed(string url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var uri) && uri.Scheme == "https" && uri.IsDefaultPort && LoginHosts.Contains(uri.Host);

    private async Task InitializeAsync()
    {
        try
        {
            var environment = await CoreWebView2Environment.CreateWithOptionsAsync(null, AppPaths.WebView, new CoreWebView2EnvironmentOptions());
            await Browser.EnsureCoreWebView2Async(environment);
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or FileNotFoundException)
        {
            AppPaths.Log($"login webview unavailable type={e.GetType().Name}");
            StatusText.Text = "ログイン画面（WebView2）を開けませんでした";
            Status.Visibility = Visibility.Visible;
            return;
        }
        var core = Browser.CoreWebView2;
        // 実アカウントでログイン中は開発者ツールを使わせない（Android 版の決定7と同じ）
        core.Settings.AreDevToolsEnabled = false;
        core.Settings.AreHostObjectsAllowed = false;
        core.Settings.IsWebMessageEnabled = false;
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.IsPasswordAutosaveEnabled = false;
        core.Settings.IsGeneralAutofillEnabled = false;
        core.NavigationStarting += (sender, e) =>
        {
            if (IsAllowed(e.Uri)) return;
            e.Cancel = true;
            AppPaths.Log($"login blocked host={HostOf(e.Uri)} user={e.IsUserInitiated}");
            // 利用者が自分で押したリンクだけ、ふだんのブラウザで開く
            if (e.IsUserInitiated && Uri.TryCreate(e.Uri, UriKind.Absolute, out var uri) && uri.Scheme == "https") _ = Windows.System.Launcher.LaunchUriAsync(uri);
        };
        core.FrameNavigationStarting += (_, e) =>
        {
            // 埋め込みの枠は Google のログインの部品だけを通す
            if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out var uri) || uri.Scheme != "https") e.Cancel = true;
        };
        core.NewWindowRequested += (_, e) =>
        {
            e.Handled = true;
            if (IsAllowed(e.Uri)) core.Navigate(e.Uri);
        };
        core.SourceChanged += (_, _) => HostText.Text = HostOf(core.Source);
        core.NavigationCompleted += async (_, _) => await TryFinishAsync();
        core.ProcessFailed += (_, e) =>
        {
            AppPaths.Log($"login webview process failed kind={e.ProcessFailedKind}");
            StatusText.Text = "ログイン画面が異常終了しました。閉じてやり直してください";
            Status.Visibility = Visibility.Visible;
        };
        core.Navigate(StartUrl);
    }

    private static string HostOf(string url) => Uri.TryCreate(url, UriKind.Absolute, out var uri) ? uri.Host : "";

    private async Task TryFinishAsync()
    {
        var core = Browser.CoreWebView2;
        if (finishing || core is null || HostOf(core.Source) != LandingHost) return;
        var cookie = await CookieAsync(core);
        if (!HasSapisid(cookie)) return;
        finishing = true;
        Status.Visibility = Visibility.Visible;
        Browser.Visibility = Visibility.Collapsed;
        // ページの yt.config_ から値を読む（読めるまで少し待つ）
        string? visitor = null, dataSync = null;
        for (var attempt = 0; attempt < 20 && (visitor is null || dataSync is null); attempt++)
        {
            if (attempt > 0) await Task.Delay(500);
            if (HostOf(core.Source) != LandingHost) break;
            visitor = await ReadConfigAsync(core, "VISITOR_DATA");
            dataSync = await ReadConfigAsync(core, "DATASYNC_ID");
        }
        cookie = await CookieAsync(core);
        var credentials = cookie is null ? null : Credentials.FromLogin(cookie, visitor, dataSync, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        AppPaths.Log($"login landed sapisid={HasSapisid(cookie)} visitor={visitor is not null} dataSync={dataSync is not null} ok={credentials is not null}");
        var ok = credentials is not null && AppServices.Account.LogIn(credentials);
        if (!ok)
        {
            StatusText.Text = credentials is null ? "ログイン情報を取得できませんでした" : "ログイン情報を保存できませんでした";
            finishing = false;
            return;
        }
        Finished?.Invoke(true);
        Close();
    }

    private static readonly Regex SapisidCookie = new(@"(?:^|;\s*)(?:SAPISID|__Secure-3PAPISID|__Secure-1PAPISID)=[^;]+");

    private static bool HasSapisid(string? cookie) => cookie is not null && SapisidCookie.IsMatch(cookie);

    private static async Task<string?> CookieAsync(CoreWebView2 core)
    {
        var cookies = await core.CookieManager.GetCookiesAsync("https://" + LandingHost);
        return cookies.Count == 0 ? null : string.Join("; ", cookies.Select(c => c.Name + "=" + c.Value));
    }

    private static async Task<string?> ReadConfigAsync(CoreWebView2 core, string key)
    {
        try
        {
            var raw = await core.ExecuteScriptAsync($"String(window.yt?.config_?.{key} ?? '')");
            var value = JsonSerializer.Deserialize<string>(raw);
            return string.IsNullOrEmpty(value) ? null : value;
        }
        catch (Exception e) when (e is JsonException or ArgumentException or System.Runtime.InteropServices.COMException)
        {
            return null;
        }
    }
}
