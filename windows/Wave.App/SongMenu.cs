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
        menu.Items.Add(Item("次に再生", "", () => AppServices.Player.PlayNext(song)));
        menu.Items.Add(Item("キューに追加", "", () => AppServices.Player.AddToQueue(song)));
        if (queueIndex is { } qi) menu.Items.Add(Item("キューから削除", "", () => AppServices.Player.RemoveAt(qi)));
        menu.Items.Add(new MenuFlyoutSeparator());
        var add = new MenuFlyoutSubItem { Text = "プレイリストに追加", Icon = new FontIcon { Glyph = "" } };
        add.Items.Add(Item("新しいプレイリスト", "", () => _ = Dialogs.CreatePlaylistAsync(song)));
        var editable = AppServices.Library.Editable.ToList();
        if (editable.Count > 0) add.Items.Add(new MenuFlyoutSeparator());
        foreach (var playlist in editable) add.Items.Add(Item(playlist.Title, null, () => _ = AddAsync(playlist, song)));
        if (!AppServices.Client.IsLoggedIn) add.IsEnabled = false;
        menu.Items.Add(add);
        if (removeFromPlaylist is not null) menu.Items.Add(Item("このプレイリストから削除", "", removeFromPlaylist));
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(Item("曲のラジオを再生", "", () => AppServices.Player.PlaySong(song)));
        if (song.Album?.BrowseId is { } albumId) menu.Items.Add(Item("アルバムへ移動", "", () => Nav.Album(albumId)));
        foreach (var artist in song.Artists.Where(a => a.BrowseId is not null))
            menu.Items.Add(Item(song.Artists.Count > 1 ? $"アーティストへ移動: {artist.Name}" : "アーティストへ移動", "", () => Nav.Artist(artist.BrowseId!)));
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
            AddToPlaylistResult.Added => $"「{playlist.Title}」に追加しました",
            AddToPlaylistResult.AlreadyInPlaylist => $"「{playlist.Title}」にはもう入っています",
            AddToPlaylistResult.NotChecked => "重複を確かめられなかったので、追加しませんでした",
            _ => "追加できませんでした",
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
        var box = new TextBox { PlaceholderText = "プレイリスト名", Text = song is null ? "" : "マイプレイリスト" };
        var dialog = new ContentDialog
        {
            Title = "プレイリストを作成",
            Content = box,
            PrimaryButtonText = "作成",
            CloseButtonText = "キャンセル",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = App.Window.Content.XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary || string.IsNullOrWhiteSpace(box.Text)) return;
        var title = box.Text.Trim();
        var created = await AppServices.Music.CreatePlaylistAsync(title);
        if (!created.IsOk)
        {
            AppServices.Player.Message = "プレイリストを作成できませんでした";
            return;
        }
        var playlist = new PlaylistSummary(created.Value!, title, "非公開", null, Editable: true);
        AppServices.Library.AddCreated(playlist);
        if (song is not null) await SongMenu.AddAsync(playlist, song, knownEmpty: true);
        else AppServices.Player.Message = $"「{title}」を作成しました";
    }
}
