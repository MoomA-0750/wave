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
        VersionText.Text = $"wave for Windows {typeof(SettingsPage).Assembly.GetName().Version?.ToString(3)}";
        DataPath.Text = "データの場所: " + AppPaths.Data;
        Render();
    }

    private void Render()
    {
        var account = AppServices.Account;
        NameText.Text = account.IsLoggedIn ? account.Name ?? "ログイン中" : "ログインしていません";
        StateText.Text = account.IsLoggedIn ? "YouTube Music のライブラリ・おすすめ・高評価が使えます" : "ログインすると、ライブラリやおすすめが使えます";
        Avatar.DisplayName = account.Name ?? "";
        Avatar.ProfilePicture = Ui.Small(account.PhotoUrl);
        AccountButton.Content = account.IsLoggedIn ? "ログアウト" : "ログイン";
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
            Title = "ログアウトしますか？",
            Content = "この PC に保存したログイン情報を消します。",
            PrimaryButtonText = "ログアウト",
            CloseButtonText = "キャンセル",
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary) AppServices.Account.LogOut();
    }

    private void OnHistoryToggled(object sender, RoutedEventArgs e)
    {
        AppServices.Settings.SendHistory = HistorySwitch.IsOn;
        AppServices.Settings.Save();
    }
}
