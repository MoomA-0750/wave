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
        menu.Items.Add(new MenuFlyoutSeparator());
        var add = new MenuFlyoutSubItem { Text = L.T("プレイリストに追加", "Add to playlist"), Icon = new FontIcon { Glyph = "" } };
        add.Items.Add(Item(L.T("新しいプレイリスト", "New playlist"), "", () => _ = Dialogs.CreatePlaylistAsync(song)));
        var editable = AppServices.Library.Editable.ToList();
        if (editable.Count > 0) add.Items.Add(new MenuFlyoutSeparator());
        foreach (var playlist in editable) add.Items.Add(Item(playlist.Title, null, () => _ = AddAsync(playlist, song)));
        if (!AppServices.Client.IsLoggedIn) add.IsEnabled = false;
        menu.Items.Add(add);
        if (removeFromPlaylist is not null) menu.Items.Add(Item(L.T("このプレイリストから削除", "Remove from this playlist"), "", removeFromPlaylist));
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(Item(L.T("曲のラジオを再生", "Go to song radio"), "", () => AppServices.Player.PlaySong(song)));
        if (song.Album?.BrowseId is { } albumId) menu.Items.Add(Item(L.T("アルバムへ移動", "Go to album"), "", () => Nav.Album(albumId)));
        foreach (var artist in song.Artists.Where(a => a.BrowseId is not null))
            menu.Items.Add(Item(song.Artists.Count > 1 ? L.T($"アーティストへ移動: {artist.Name}", $"Go to artist: {artist.Name}") : L.T("アーティストへ移動", "Go to artist"), "", () => Nav.Artist(artist.BrowseId!)));
        return menu;
    }

    private static MenuFlyoutItem Item(string text, string? glyph, Action action)
    {
        var item = new MenuFlyoutItem { Text = text };
        if (glyph is not null) item.Icon = new FontIcon { Glyph = glyph };
        item.Click += (_, _) => action();
        return item;
    }

    public static async Task AddAsync(PlaylistSummary playlist, Song song, bool knownEmpty = false)
    {
        var result = await AppServices.Music.AddToPlaylistAsync(playlist.PlaylistId, song.VideoId, knownEmpty);
        AppServices.Player.Message = result switch
        {
            AddToPlaylistResult.Added => L.T($"「{playlist.Title}」に追加しました", $"Added to {playlist.Title}"),
            AddToPlaylistResult.AlreadyInPlaylist => L.T($"「{playlist.Title}」にはもう入っています", $"Already in {playlist.Title}"),
            AddToPlaylistResult.NotChecked => L.T("重複を確かめられなかったので、追加しませんでした", "Couldn't check for duplicates, so it wasn't added"),
            _ => L.T("追加できませんでした", "Couldn't add it"),
        };
    }
}

/// <summary>小さな入力のダイアログ。</summary>
public static class Dialogs
{
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
