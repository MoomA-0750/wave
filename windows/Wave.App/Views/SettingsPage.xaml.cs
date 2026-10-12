using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Services;

namespace Wave.App.Views;

public sealed partial class SettingsPage : Page
{
    public SettingsPage() => InitializeComponent();

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        AppServices.Account.PropertyChanged += (_, _) => DispatcherQueue.TryEnqueue(Render);
        HistorySwitch.IsOn = AppServices.Settings.SendHistory;
        RenderAi();
        ReleaseSwitch.IsOn = AppServices.Settings.ReleaseNotices;
        DiscordSwitch.IsOn = AppServices.Settings.DiscordPresence;
        DiscordIdBox.Text = AppServices.Settings.DiscordClientId ?? "";
        DiscordStateText.Text = DiscordState();
        // Discord へのつなぎ具合は裏で変わるので、開いている間は時々書き直す
        if (discordTimer is null)
        {
            discordTimer = DispatcherQueue.CreateTimer();
            discordTimer.Interval = TimeSpan.FromSeconds(2);
            discordTimer.Tick += (_, _) => DiscordStateText.Text = DiscordState();
        }
        discordTimer.Start();
        LanguageBox.Items.Clear();
        foreach (var (key, label) in Languages) LanguageBox.Items.Add(new ComboBoxItem { Content = label, Tag = key });
        LanguageBox.SelectedIndex = Math.Max(0, Array.FindIndex(Languages, l => l.Key == AppServices.Settings.Language));
        VersionText.Text = $"wave for Windows {typeof(SettingsPage).Assembly.GetName().Version?.ToString(3)}";
        DataPath.Text = L.T("データの場所: ", "Data folder: ") + AppPaths.Data;
        Render();
    }

    private void Render()
    {
        var account = AppServices.Account;
        NameText.Text = account.IsLoggedIn ? account.Name ?? L.T("ログイン中", "Logged in") : L.T("ログインしていません", "Not logged in");
        StateText.Text = !account.IsLoggedIn ? L.T("ログインすると、ライブラリやおすすめが使えます", "Log in to use your library and recommendations")
            : !account.AccountChecked ? L.T("アカウントを確かめています…", "Checking account…")
            : L.T("Premium 音質: ", "Premium audio: ") + account.PremiumAudio switch
            {
                true => L.T("使えます", "Available"),
                false => L.T("使えません", "Unavailable"),
                null => account.AccountCheckFailed ? L.T("分かりません", "Unknown") : L.T("確かめています…", "Checking…"),
            };
        // ログインを確かめられなかった（期限切れなど）: Android と同じく、もう一度ログインを勧める
        ReloginBar.IsOpen = account.IsLoggedIn && account.AccountCheckFailed;
        Avatar.DisplayName = account.Name ?? "";
        Avatar.ProfilePicture = Ui.Small(account.PhotoUrl);
        AccountButton.Content = account.IsLoggedIn ? L.T("ログアウト", "Log out") : L.T("ログイン", "Log in");
        UnreadableBar.IsOpen = account.SavedLoginUnreadable && !account.IsLoggedIn;
    }

    private void OnRelogin(object sender, RoutedEventArgs e) => App.Window?.ShowLogin();

    private async void OnAccount(object sender, RoutedEventArgs e)
    {
        if (!AppServices.Account.IsLoggedIn)
        {
            App.Window?.ShowLogin();
            return;
        }
        var dialog = new ContentDialog
        {
            Title = L.T("ログアウトしますか？", "Log out?"),
            Content = L.T("この PC に保存したログイン情報を消します。", "This removes your login saved on this PC."),
            PrimaryButtonText = L.T("ログアウト", "Log out"),
            CloseButtonText = L.T("キャンセル", "Cancel"),
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary) AppServices.Account.LogOut();
    }

    private static readonly (string Key, string Label)[] Languages =
    [
        ("system", L.T("Windows に合わせる", "Use Windows language")),
        ("ja", "日本語"),
        ("en", "English"),
    ];

    private void OnLanguageChanged(object sender, SelectionChangedEventArgs e)
    {
        if (LanguageBox.SelectedItem is not ComboBoxItem { Tag: string key } || key == AppServices.Settings.Language) return;
        AppServices.Settings.Language = key;
        AppServices.Settings.Save();
        App.Window?.ShowToast(L.T("次の起動から反映されます", "Takes effect the next time you open wave"));
    }

    private void OnReleaseToggled(object sender, RoutedEventArgs e)
    {
        if (AppServices.Settings.ReleaseNotices == ReleaseSwitch.IsOn) return;
        AppServices.Settings.ReleaseNotices = ReleaseSwitch.IsOn;
        AppServices.Settings.Save();
    }

    private Microsoft.UI.Dispatching.DispatcherQueueTimer? discordTimer;

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        discordTimer?.Stop();
        base.OnNavigatedFrom(e);
    }

    private void OnDiscordToggled(object sender, RoutedEventArgs e)
    {
        if (AppServices.Settings.DiscordPresence == DiscordSwitch.IsOn) return;
        AppServices.Settings.DiscordPresence = DiscordSwitch.IsOn;
        SaveDiscordId();
    }

    private void OnDiscordIdChanged(object sender, RoutedEventArgs e) => SaveDiscordId();

    private void SaveDiscordId()
    {
        // ID は数字だけ（貼るときに混ざった空白などは落とす）
        var id = new string(DiscordIdBox.Text.Where(char.IsAsciiDigit).ToArray());
        AppServices.Settings.DiscordClientId = id.Length > 0 ? id : null;
        AppServices.Settings.Save();
        AppServices.Discord.Push();
        DiscordStateText.Text = DiscordState();
    }

    private static string DiscordState() =>
        !AppServices.Settings.DiscordPresence ? ""
        : AppServices.Settings.DiscordClientId is null ? L.T("Application ID を入れると出せます", "Enter the Application ID to turn this on")
        : AppServices.Discord.StatusText;

    private void RenderAi()
    {
        var settings = AppServices.Settings;
        AiStateText.Text = AppServices.Ai.Configured
            ? L.T($"{settings.AiName ?? settings.AiUrl} の中継につないでいます", $"Connected to {settings.AiName ?? settings.AiUrl}")
            : L.T("家の PC の Claude Code で、歌詞の意味・似た曲・プレイリスト作りを頼めます", "Ask about lyrics, find similar songs and make playlists with Claude Code on your PC");
        AiUnpairButton.Visibility = Ui.Show(AppServices.Ai.Configured);
    }

    private void OnAiPair(object sender, RoutedEventArgs e)
    {
        if (!AiChat.Pair(AiPairBox.Password))
        {
            AppServices.Player.Message = L.T("wave の Claude の中継の接続の文字列ではありません", "That isn't a wave Claude bridge pairing text");
            return;
        }
        AiPairBox.Password = "";
        RenderAi();
    }

    private void OnAiUnpair(object sender, RoutedEventArgs e)
    {
        AiChat.Unpair();
        RenderAi();
    }

    private void OnHistoryToggled(object sender, RoutedEventArgs e)
    {
        AppServices.Settings.SendHistory = HistorySwitch.IsOn;
        AppServices.Settings.Save();
    }
}
