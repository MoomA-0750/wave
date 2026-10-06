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
        StateText.Text = account.IsLoggedIn ? L.T("YouTube Music のライブラリ・おすすめ・高評価が使えます", "Your YouTube Music library, recommendations and likes are available") : L.T("ログインすると、ライブラリやおすすめが使えます", "Log in to use your library and recommendations");
        Avatar.DisplayName = account.Name ?? "";
        Avatar.ProfilePicture = Ui.Small(account.PhotoUrl);
        AccountButton.Content = account.IsLoggedIn ? L.T("ログアウト", "Log out") : L.T("ログイン", "Log in");
        UnreadableBar.IsOpen = account.SavedLoginUnreadable && !account.IsLoggedIn;
    }

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

    private void OnHistoryToggled(object sender, RoutedEventArgs e)
    {
        AppServices.Settings.SendHistory = HistorySwitch.IsOn;
        AppServices.Settings.Save();
    }
}
