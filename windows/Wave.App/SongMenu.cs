using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Wave.App.Services;
using Wave.Core.Music;

namespace Wave.App;

/// <summary>曲の右クリック・「…」のメニュー（Spotify と同じ並び）。</summary>
public static class SongMenu
{
    /// <summary>removeFromPlaylist を渡すと「このプレイリストから削除」を出す。</summary>
    public static MenuFlyout Build(Song song, Action? removeFromPlaylist = null, int? queueIndex = null)
    {
        var menu = new MenuFlyout();
        menu.Items.Add(Item(L.T("次に再生", "Play next"), "", () => AppServices.Player.PlayNext(song)));
        menu.Items.Add(Item(L.T("キューに追加", "Add to queue"), "", () => AppServices.Player.AddToQueue(song)));
        if (queueIndex is { } qi) menu.Items.Add(Item(L.T("キューから削除", "Remove from queue"), "", () => AppServices.Player.RemoveAt(qi)));
        // RSS のエピソードは YouTube の曲ではない（プレイリスト・ラジオ・共有のリンクが無い。ADR-0012。Android と同じ）
        if (Feed.IsExternal(song.VideoId)) return menu;
        menu.Items.Add(new MenuFlyoutSeparator());
        // ログインしていなければ出さない（押せないものは出さない。Mac と同じ）
        if (AppServices.Client.IsLoggedIn)
        {
            var add = new MenuFlyoutSubItem { Text = L.T("プレイリストに追加", "Add to playlist"), Icon = new FontIcon { Glyph = "" } };
            add.Items.Add(Item(L.T("新しいプレイリスト", "New playlist"), "", () => _ = Dialogs.CreatePlaylistAsync(song)));
            var editable = AppServices.Library.Editable.ToList();
            if (editable.Count > 0) add.Items.Add(new MenuFlyoutSeparator());
            foreach (var playlist in editable) add.Items.Add(Item(playlist.Title, null, () => _ = AddAsync(playlist, song)));
            menu.Items.Add(add);
        }
        if (removeFromPlaylist is not null) menu.Items.Add(Item(L.T("このプレイリストから削除", "Remove from this playlist"), "", removeFromPlaylist));
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(Item(L.T("曲のラジオを再生", "Go to song radio"), "", () => AppServices.Player.PlaySong(song)));
        // 曲の画面（Android・Mac・iPhone と同じ。ライブ配信には曲の画面が無い）
        if (!song.IsLive && Nav.Frame?.Content is not Views.SongPage) menu.Items.Add(Item(L.T("曲の画面を開く", "Go to song"), "\uE8D6", () => Nav.Song(song)));
        if (song.Album?.BrowseId is { } albumId) menu.Items.Add(Item(L.T("アルバムへ移動", "Go to album"), "", () => Nav.Album(albumId)));
        foreach (var artist in song.Artists.Where(a => a.BrowseId is not null))
            menu.Items.Add(Item(song.Artists.Count > 1 ? L.T($"アーティストへ移動: {artist.Name}", $"Go to artist: {artist.Name}") : L.T("アーティストへ移動", "Go to artist"), "", () => Nav.Artist(artist.BrowseId!)));
        menu.Items.Add(new MenuFlyoutSeparator());
        var share = new MenuFlyoutSubItem { Text = L.T("共有", "Share"), Icon = new FontIcon { Glyph = "\uE72D" } };
        share.Items.Add(Item(L.T("曲のリンクをコピー", "Copy song link"), "\uE71B", () => CopyLink(song)));
        share.Items.Add(Item(L.T("ほかのアプリで共有…", "Share with…"), "\uE72D", () => Share(song)));
        menu.Items.Add(share);
        return menu;
    }

    /// <summary>YouTube Music の曲の URL（Android と同じ）。</summary>
    public static string Url(Song song) => $"https://music.youtube.com/watch?v={song.VideoId}";

    private static string ShareText(Song song) => song.Artists.Count > 0 ? $"{song.Title} - {song.ArtistsText}" : song.Title;

    public static void CopyLink(Song song)
    {
        var data = new Windows.ApplicationModel.DataTransfer.DataPackage();
        data.SetText(Url(song));
        Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(data);
        AppServices.Player.Message = L.T("リンクをコピーしました", "Link copied");
    }

    /// <summary>Windows の共有の画面を出す（曲名とリンク）。</summary>
    public static void Share(Song song)
    {
        if (App.Window is null) return;
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(App.Window);
        var manager = Windows.ApplicationModel.DataTransfer.DataTransferManagerInterop.GetForWindow(hwnd);
        void OnRequested(Windows.ApplicationModel.DataTransfer.DataTransferManager sender, Windows.ApplicationModel.DataTransfer.DataRequestedEventArgs args)
        {
            sender.DataRequested -= OnRequested;
            args.Request.Data.Properties.Title = song.Title;
            args.Request.Data.SetText($"{ShareText(song)}\n{Url(song)}");
            args.Request.Data.SetWebLink(new Uri(Url(song)));
        }
        manager.DataRequested += OnRequested;
        Windows.ApplicationModel.DataTransfer.DataTransferManagerInterop.ShowShareUIForWindow(hwnd);
    }

    private static MenuFlyoutItem Item(string text, string? glyph, Action action)
    {
        var item = new MenuFlyoutItem { Text = text };
        if (glyph is not null) item.Icon = new FontIcon { Glyph = glyph };
        item.Click += (_, _) => action();
        return item;
    }

    /// <summary>プレイリストから曲を外す（同じ曲が2回入っていれば両方。追加の画面で、入っているプレイリストを押したとき。Android と同じ）。外せたか。</summary>
    public static async Task<bool> RemoveAsync(PlaylistSummary playlist, Song song)
    {
        var membership = AppServices.Library.Membership;
        var setIds = await membership.SetVideoIdsAsync(playlist.PlaylistId, song.VideoId);
        var removed = setIds is { Count: > 0 };
        if (removed)
        {
            foreach (var setId in setIds!) removed &= await AppServices.Music.RemoveFromPlaylistAsync(playlist.PlaylistId, song.VideoId, setId);
            if (removed) membership.NoteRemoved(playlist.PlaylistId, song.VideoId);
            else membership.Invalidate(playlist.PlaylistId);
        }
        AppPaths.Log($"playlist remove-song known={setIds is not null} removed={removed}");
        AppServices.Player.Message = removed ? L.T($"「{playlist.Title}」から外しました", $"Removed from {playlist.Title}") : L.T("外せませんでした", "Couldn't remove it");
        return removed;
    }

    public static async Task<AddToPlaylistResult> AddAsync(PlaylistSummary playlist, Song song, bool knownEmpty = false)
    {
        var result = await AppServices.Music.AddToPlaylistAsync(playlist.PlaylistId, song.VideoId, knownEmpty);
        if (result is AddToPlaylistResult.Added or AddToPlaylistResult.AlreadyInPlaylist) AppServices.Library.Membership.NoteAdded(playlist.PlaylistId, song.VideoId);
        AppServices.Player.Message = result switch
        {
            AddToPlaylistResult.Added => L.T($"「{playlist.Title}」に追加しました", $"Added to {playlist.Title}"),
            AddToPlaylistResult.AlreadyInPlaylist => L.T($"「{playlist.Title}」にはもう入っています", $"Already in {playlist.Title}"),
            AddToPlaylistResult.NotChecked => L.T("重複を確かめられなかったので、追加しませんでした", "Couldn't check for duplicates, so it wasn't added"),
            _ => L.T("追加できませんでした", "Couldn't add it"),
        };
        return result;
    }
}

/// <summary>小さな入力のダイアログ。</summary>
public static class Dialogs
{
    /// <summary>
    /// プレイリストに追加する画面（Android・Spotify と同じ）。先頭は「高く評価した曲」で、押すと評価を外す（評価済みのライクボタンから開く）。
    /// プレイリストを押しても閉じず、続けてほかのプレイリストにも足せる（閉じるのは「完了」）。入っているプレイリストには右に✓を出し、押すと外す（Android と同じ）。
    /// </summary>
    public static async Task AddToPlaylistAsync(Song song)
    {
        if (App.Window is null) return;
        ContentDialog? dialog = null;
        var cancel = new CancellationTokenSource();
        var accent = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["AccentTextFillColorPrimaryBrush"];
        var list = new StackPanel { Spacing = 2, MinWidth = 360 };
        (Button Button, Grid Trailing) Row(string text, string glyph, bool accentIcon = false)
        {
            var grid = new Grid { ColumnSpacing = 12 };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var icon = new FontIcon { Glyph = glyph, FontSize = 16 };
            if (accentIcon) icon.Foreground = accent;
            grid.Children.Add(icon);
            var label = new TextBlock { Text = text, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(label, 1);
            grid.Children.Add(label);
            var trailing = new Grid { Width = 20, Height = 20 };
            Grid.SetColumn(trailing, 2);
            grid.Children.Add(trailing);
            var button = new Button { Content = grid, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch, Padding = new Thickness(12, 10, 12, 10), Background = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Transparent), BorderThickness = new Thickness(0) };
            list.Children.Add(button);
            return (button, trailing);
        }
        void Mark(Grid trailing, PlaylistMembership.Status? status, bool busy = false)
        {
            trailing.Children.Clear();
            if (busy || status == PlaylistMembership.Status.Checking) trailing.Children.Add(new ProgressRing { IsActive = true, Width = 16, Height = 16 });
            else if (status == PlaylistMembership.Status.In) trailing.Children.Add(new FontIcon { Glyph = "\uEC61", FontSize = 18, Foreground = accent });
            else if (status == PlaylistMembership.Status.Unknown)
                ToolTipService.SetToolTip(trailing, L.T("入っているか確かめられませんでした", "Couldn't check"));
        }
        var create = Row(L.T("新しいプレイリスト", "New playlist"), "\uE710", accentIcon: true);
        create.Button.Click += (_, _) =>
        {
            dialog?.Hide();
            _ = CreatePlaylistAsync(song);
        };
        var likedRow = Row(L.T("高く評価した曲", "Liked Songs"), "\uEB52");
        var liked = AppServices.Player.Current?.VideoId == song.VideoId ? AppServices.Player.Like == LikeStatus.Like : AppServices.Library.IsLiked(song.VideoId);
        likedRow.Trailing.Children.Add(new FontIcon { Glyph = liked ? "\uEC61" : "\uECC8", FontSize = 18, Foreground = accent });
        likedRow.Button.Click += (_, _) =>
        {
            dialog?.Hide();
            // 評価済みなら外す（今の曲のときだけ評価を変えられる）
            if (AppServices.Player.Current?.VideoId == song.VideoId) AppServices.Player.ToggleLike();
        };
        var membership = AppServices.Library.Membership;
        foreach (var playlist in AppServices.Library.Editable.ToList())
        {
            var row = Row(playlist.Title, "\uE8FD");
            PlaylistMembership.Status? status = PlaylistMembership.Status.Checking;
            var busy = false;
            Mark(row.Trailing, status);
            row.Button.Click += async (_, _) =>
            {
                if (busy || status == PlaylistMembership.Status.Checking) return;
                busy = true;
                Mark(row.Trailing, status, busy: true);
                if (status == PlaylistMembership.Status.In)
                {
                    if (await SongMenu.RemoveAsync(playlist, song)) status = PlaylistMembership.Status.NotIn;
                }
                else
                {
                    var result = await SongMenu.AddAsync(playlist, song);
                    if (result is AddToPlaylistResult.Added or AddToPlaylistResult.AlreadyInPlaylist) status = PlaylistMembership.Status.In;
                }
                busy = false;
                Mark(row.Trailing, status);
            };
            _ = CheckAsync();

            async Task CheckAsync()
            {
                try
                {
                    status = await membership.CheckAsync(playlist.PlaylistId, song.VideoId, cancel.Token);
                    if (!busy) Mark(row.Trailing, status);
                }
                catch (OperationCanceledException)
                {
                }
            }
        }
        dialog = new ContentDialog
        {
            Title = L.T("プレイリストに追加", "Add to playlist"),
            Content = new ScrollViewer { Content = list, MaxHeight = 480 },
            CloseButtonText = L.T("完了", "Done"),
            XamlRoot = App.Window.Content.XamlRoot,
        };
        await dialog.ShowAsync();
        cancel.Cancel();
    }

    /// <summary>新しいプレイリストを作る（非公開）。song を渡したら作ったあとに入れる。</summary>
    public static async Task CreatePlaylistAsync(Song? song = null)
    {
        if (App.Window is null) return;
        var box = new TextBox { PlaceholderText = L.T("プレイリスト名", "Playlist name"), Text = song is null ? "" : L.T("マイプレイリスト", "My playlist") };
        var dialog = new ContentDialog
        {
            Title = L.T("プレイリストを作成", "Create playlist"),
            Content = box,
            PrimaryButtonText = L.T("作成", "Create"),
            CloseButtonText = L.T("キャンセル", "Cancel"),
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = App.Window.Content.XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary || string.IsNullOrWhiteSpace(box.Text)) return;
        var title = box.Text.Trim();
        var created = await AppServices.Music.CreatePlaylistAsync(title);
        if (!created.IsOk)
        {
            AppServices.Player.Message = L.T("プレイリストを作成できませんでした", "Couldn't create the playlist");
            return;
        }
        var playlist = new PlaylistSummary(created.Value!, title, L.T("非公開", "Private"), null, Editable: true);
        AppServices.Library.AddCreated(playlist);
        if (song is not null) await SongMenu.AddAsync(playlist, song, knownEmpty: true);
        else AppServices.Player.Message = L.T($"「{title}」を作成しました", $"Created {title}");
    }
}
