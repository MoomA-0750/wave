using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Services;
using Wave.App.Services.Connect;

namespace Wave.App.Views;

/// <summary>端末の連携: 家の作成・加入（QR と6桁）・家の端末の一覧・ほかの端末の再生の引き継ぎ。</summary>
public sealed partial class DevicesPage : Page
{
    private static ConnectService Devices => AppServices.Connect;
    private string? shownOffer;

    public DevicesPage() => InitializeComponent();

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        Devices.Changed += Render;
        Render();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        Devices.Changed -= Render;
        // 画面を離れたら、途中の加入を取り下げる（相手に数字を見せている間は残す）
        if (Devices.PairingState.Kind is not (PairingKind.ShowCode or PairingKind.Idle or PairingKind.Done or PairingKind.Failed)) Devices.CancelPairing();
    }

    private void Render()
    {
        var info = Devices.Info;
        StatusBar.IsOpen = false;
        if (!info.Configured)
        {
            Show(InfoBarSeverity.Informational, L.T("中継の場所が設定されていません", "The relay isn’t configured"),
                Devices.Unreadable ? L.T("この PC の連携の鍵を読めませんでした", "Couldn’t read this PC’s device keys") : L.T("設定ファイルに中継の場所を書くと使えます", "Add the relay address to the settings file to use this"));
        }
        else if (info.Unreadable)
        {
            // Android の「リセット」と同じ: 読めない保存を消して、加わり直せるようにする
            Show(InfoBarSeverity.Error, L.T("連携の保存を読めません", "Couldn’t read the saved device group"),
                L.T("リセットすると、この PC はもう一度ほかの端末から加わり直します。", "Reset it to join your devices again from another device."),
                L.T("リセット", "Reset"), Devices.ForgetHousehold);
        }
        else if (info.InHousehold && info.Relay == RelayStatus.NotMember)
        {
            Show(InfoBarSeverity.Error, L.T("中継がこの PC を連携した端末として受け付けません", "The relay no longer accepts this PC"),
                L.T("ほかの端末から外された可能性があります。", "It may have been removed by another device."), L.T("この PC の連携の記録を消す", "Forget the group on this PC"), Devices.ForgetHousehold);
        }
        else if (info.StoreResetPending)
        {
            Show(InfoBarSeverity.Warning, L.T("中継の状態が作り直されました", "The relay’s state was reset"), null, L.T("この状態から始める", "Start from this state"), Devices.AcceptStoreReset);
        }

        var remote = Devices.Remote;
        RemoteCard.Visibility = Ui.Show(info.InHousehold && info.Relay != RelayStatus.NotMember && remote is { AccountMatches: true } && !info.IsWriter);
        if (remote is not null && RemoteCard.Visibility == Visibility.Visible)
        {
            RemoteDevice.Text = remote.Playing ? L.T($"{remote.DeviceLabel}で再生中", $"Playing on {remote.DeviceLabel}") : L.T($"{remote.DeviceLabel}で一時停止中", $"Paused on {remote.DeviceLabel}");
            RemoteTitle.Text = remote.Title ?? L.T("曲", "Song");
            RemoteArtist.Text = $"{remote.Artist} · {Ui.Time(remote.PositionMs / 1000.0)}";
            RemoteImage.Source = Ui.Small(remote.ThumbnailUrl);
        }

        var pairing = Devices.PairingState;
        JoinPanel.Visibility = Ui.Show(info.Loaded && !info.InHousehold && pairing.Kind is PairingKind.Idle or PairingKind.Failed);
        HouseholdPanel.Visibility = Ui.Show(info.InHousehold);
        RenderMembers();
        RenderPairing(pairing);
        RelayText.Text = L.T("中継: ", "Relay: ") + info.Relay switch
        {
            RelayStatus.Connected => L.T("接続中", "Connected"),
            RelayStatus.Connecting => L.T("接続しています…", "Connecting…"),
            RelayStatus.TailnetUnreachable => L.T("届きません（Tailscale につながっているか確かめてください）", "Unreachable (check that Tailscale is connected)"),
            RelayStatus.NotMember => info.InHousehold ? L.T("受け付けられません", "Not accepted") : L.T("まだ連携していません", "Not set up yet"),
            RelayStatus.Failed => L.T("つながりませんでした", "Couldn’t connect"),
            _ => L.T("切断", "Disconnected"),
        };
    }

    private void Show(InfoBarSeverity severity, string title, string? message, string? action = null, Action? onAction = null)
    {
        StatusBar.Severity = severity;
        StatusBar.Title = title;
        StatusBar.Message = message ?? "";
        if (action is not null && onAction is not null)
        {
            var button = new Button { Content = action };
            button.Click += (_, _) => onAction();
            StatusBar.ActionButton = button;
        }
        else StatusBar.ActionButton = null;
        StatusBar.IsOpen = true;
    }

    private void RenderMembers()
    {
        MemberList.Children.Clear();
        var members = Devices.Members;
        for (var i = 0; i < members.Count; i++)
        {
            var member = members[i];
            var row = new Grid { Padding = new Thickness(16, 12, 16, 12), ColumnSpacing = 16 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.Children.Add(new FontIcon { Glyph = member.IsSelf ? "" : "", FontSize = 20, VerticalAlignment = VerticalAlignment.Center });
            var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            text.Children.Add(new TextBlock { Text = member.IsController ? L.T($"{member.Label}（操作だけ）", $"{member.Label} (remote only)") : member.Label });
            var state = member.Active ? L.T("再生中の端末", "Active device") : member.Online ? L.T("オンライン", "Online") : L.T("オフライン", "Offline");
            text.Children.Add(new TextBlock { Text = state, Style = (Style)Application.Current.Resources["SubtleTextStyle"] });
            Grid.SetColumn(text, 1);
            row.Children.Add(text);
            if (!member.IsSelf)
            {
                var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
                // wave-api（ADR-0015）: 本人が押したときだけログインを渡す。ログインしていなければ出さない
                if (member.IsApi && AppServices.Client.IsLoggedIn)
                {
                    var grant = new Button { Content = L.T("ログインを渡す", "Give login") };
                    grant.Click += async (_, _) => await GrantLoginAsync(member);
                    actions.Children.Add(grant);
                }
                var remove = new Button { Content = L.T("外す", "Remove") };
                remove.Click += async (_, _) => await RemoveAsync(member);
                actions.Children.Add(remove);
                Grid.SetColumn(actions, 2);
                row.Children.Add(actions);
            }
            if (i > 0) MemberList.Children.Add(new Border { Height = 1, Background = (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"] });
            MemberList.Children.Add(row);
        }
    }

    private async Task GrantLoginAsync(MemberView member)
    {
        var dialog = new ContentDialog
        {
            Title = L.T("wave API にログインを渡しますか？", "Give your login to wave API?"),
            Content = new TextBlock
            {
                TextWrapping = TextWrapping.Wrap,
                Text = L.T($"「{member.Label}」に YouTube のログインを渡します。ほかのアプリ（home-dashboard など）で、あなたのプレイリストや高く評価した曲を出せるようになります。wave API を動かす機械に入れる人は、これらを読めます。",
                    $"Your YouTube login will be sent to “{member.Label}”, so other apps (such as home-dashboard) can show your playlists and liked songs. Anyone who can get into the computer running wave API can read them."),
            },
            PrimaryButtonText = L.T("渡す", "Give"),
            CloseButtonText = L.T("キャンセル", "Cancel"),
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        var sent = await Devices.GrantLoginAsync(member.DeviceId);
        App.Window?.ShowToast(sent ? L.T($"「{member.Label}」にログインを渡しました", $"Sent your login to {member.Label}") : L.T("ログインを渡せませんでした（中継への接続を確かめてください）", "Couldn’t send your login (check your connection to the relay)"));
    }

    private async Task RemoveAsync(MemberView member)
    {
        var dialog = new ContentDialog
        {
            Title = L.T("端末を外しますか？", "Remove this device?"),
            Content = L.T($"「{member.Label}」は、もう再生を引き継げなくなります。もう一度加えるには、QR と6桁の手順が要ります。", $"“{member.Label}” won’t be able to move playback anymore. To add it again, you’ll need the QR code and 6-digit steps."),
            PrimaryButtonText = L.T("外す", "Remove"),
            CloseButtonText = L.T("キャンセル", "Cancel"),
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        var removed = await Devices.RemoveMemberAsync(member.DeviceId);
        App.Window?.ShowToast(removed ? L.T($"「{member.Label}」を外しました", $"Removed {member.Label}") : L.T("外せませんでした", "Couldn’t remove the device"));
    }

    private void RenderPairing(PairingView pairing)
    {
        PairingPanel.Visibility = Ui.Hide(pairing.Kind == PairingKind.Idle);
        QrImage.Visibility = OfferTextPanel.Visibility = Ui.Show(pairing.Kind == PairingKind.ShowingOffer);
        CodeText.Visibility = Ui.Show(pairing.Kind == PairingKind.ShowCode);
        CodeEntry.Visibility = Ui.Show(pairing.Kind == PairingKind.EnterCode);
        ContinueButton.Visibility = Ui.Show(pairing.Kind == PairingKind.ShowCode);
        PairingRing.IsActive = pairing.Kind == PairingKind.Working;
        PairingRing.Visibility = Ui.Show(pairing.Kind == PairingKind.Working);
        PairingCloseButton.Content = pairing.Kind is PairingKind.Done or PairingKind.Failed ? L.T("閉じる", "Close") : L.T("キャンセル", "Cancel");
        PairingText.Foreground = pairing.Kind == PairingKind.Failed ? (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"] : (Brush)Application.Current.Resources["TextFillColorPrimaryBrush"];
        PairingText.Text = pairing.Kind switch
        {
            PairingKind.Working => pairing.Text ?? "",
            PairingKind.ShowingOffer => L.T("加える端末で wave の「端末の連携」を開き、この QR を読み取ってください（PC なら下の文字列をコピーして貼り付けます）。2分で無効になります。", "On the device you’re adding, open Devices in wave and scan this QR code (on a PC, copy the text below and paste it). Expires in 2 minutes."),
            PairingKind.EnterCode => (pairing.Text is { } error ? error + "\n" : "") + L.T("加える端末に出ている6桁の数字を入力してください", "Enter the 6-digit code shown on the device you’re adding"),
            PairingKind.ShowCode => L.T($"「{pairing.PeerName}」に、この6桁の数字を入力してください", $"Enter this 6-digit code on “{pairing.PeerName}”"),
            PairingKind.Done when pairing.GrantDeviceId is not null => L.T($"「{pairing.PeerName}」と連携しました。まだログインしていないので、下の端末の一覧の「ログインを渡す」から渡すと、あなたのプレイリストなどを出せるようになります。",
                $"Connected with “{pairing.PeerName}”. It isn’t signed in yet — use “Give login” in the device list below so it can show your playlists."),
            PairingKind.Done => L.T($"「{pairing.PeerName}」と連携しました", $"Connected with “{pairing.PeerName}”"),
            PairingKind.Failed => pairing.Text ?? "",
            _ => "",
        };
        if (pairing.Kind == PairingKind.ShowCode && pairing.Code is { Length: 6 } code) CodeText.Text = code[..3] + " " + code[3..];
        if (pairing.Kind == PairingKind.ShowingOffer && pairing.OfferText is { } offer && offer != shownOffer)
        {
            shownOffer = offer;
            _ = ShowQrAsync(offer);
        }
        if (pairing.Kind == PairingKind.Done && !Devices.Info.InHousehold) OfferBox.Text = "";
    }

    private async Task ShowQrAsync(string offer)
    {
        var png = new QRCoder.PngByteQRCode(new QRCoder.QRCodeGenerator().CreateQrCode(offer, QRCoder.QRCodeGenerator.ECCLevel.M)).GetGraphic(8);
        var stream = new Windows.Storage.Streams.InMemoryRandomAccessStream();
        await stream.WriteAsync(System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions.AsBuffer(png));
        stream.Seek(0);
        var bitmap = new BitmapImage();
        await bitmap.SetSourceAsync(stream);
        QrImage.Source = bitmap;
    }

    private void OnJoin(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(OfferBox.Text)) return;
        Devices.Accept(OfferBox.Text);
    }

    private void OnCreate(object sender, RoutedEventArgs e) => Devices.CreateHousehold();

    private void OnAddDevice(object sender, RoutedEventArgs e) => Devices.StartOffer();

    private void OnPlayHere(object sender, RoutedEventArgs e) => Devices.Transfer();

    private void OnEnterCode(object sender, RoutedEventArgs e)
    {
        var code = new string(CodeBox.Text.Where(char.IsAsciiDigit).ToArray());
        if (code.Length != 6) return;
        Devices.EnterCode(code);
        CodeBox.Text = "";
    }

    private void OnContinue(object sender, RoutedEventArgs e) => Devices.ConfirmOnB();

    private void OnPairingCancel(object sender, RoutedEventArgs e) => Devices.CancelPairing();

    private void OnCopyOffer(object sender, RoutedEventArgs e)
    {
        if (Devices.PairingState.OfferText is not { } offer) return;
        var package = new Windows.ApplicationModel.DataTransfer.DataPackage();
        package.SetText(offer);
        Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
        App.Window?.ShowToast(L.T("コピーしました", "Copied"));
    }
}
