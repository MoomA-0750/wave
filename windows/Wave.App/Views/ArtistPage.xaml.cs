using System.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Controls;
using Wave.App.Services;
using Wave.Core.Music;

namespace Wave.App.Views;

public sealed partial class ArtistPage : Page
{
    private string browseId = "";
    private Core.Music.ArtistPage? artist;
    private List<TrackRow> rows = [];

    public ArtistPage() => InitializeComponent();

    private PlayerService Player => AppServices.Player;

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        browseId = (string)e.Parameter;
        Player.PropertyChanged += OnPlayerChanged;
        shufflePlay = false;
        UpdateShuffleButton();
        var result = await AppServices.Music.ArtistAsync(browseId);
        LoadingRing.IsActive = false;
        if (!result.IsOk)
        {
            ErrorText.Visibility = Visibility.Visible;
            return;
        }
        artist = result.Value!;
        NameText.Text = artist.Name;
        Hero.Source = Ui.Img(artist.ThumbnailUrl, 1080);
        var n = 1;
        rows = artist.TopSongs.Select(s => new TrackRow(n++, s) { ShowAlbum = true, Menu = r => SongMenu.Build(r.Song) }).ToList();
        TrackRow.MarkCurrent(rows, Player.Current?.VideoId);
        TopSongs.ItemsSource = rows;
        PlayButton.IsEnabled = rows.Count > 0;
        AllSongs.Visibility = Ui.Show(artist.AllSongsPlaylistId is not null);
        Shelves.ItemsSource = artist.AlbumShelves.Select(s => new HomeShelf(s.Title, null, s.Albums.Select(a => (HomeItem)new HomeItem.AlbumItem(a)).ToList())).ToList();
        UpdatePlayIcon();
        UpdateSubscribe();
        LiveButton.Visibility = Visibility.Visible;
        await ShowLikedAsync(artist);
    }

    private bool subscribing;

    private void UpdateSubscribe()
    {
        SubscribeButton.Visibility = Ui.Show(artist?.ChannelId is not null && AppServices.Client.IsLoggedIn);
        SubscribeButton.Content = artist?.Subscribed == true ? L.T("登録済み", "Subscribed") : L.T("登録", "Subscribe");
        SubscribeButton.IsEnabled = !subscribing && artist?.Subscribed is not null;
    }

    /// <summary>登録の切り替え。書けたら表示を変える（Android と同じく楽観的に変えない）。</summary>
    private async void OnSubscribe(object sender, RoutedEventArgs e)
    {
        if (artist is not { ChannelId: { } channelId } page || subscribing) return;
        var target = page.Subscribed != true;
        subscribing = true;
        UpdateSubscribe();
        var result = await AppServices.Music.SetSubscribedAsync(channelId, target);
        subscribing = false;
        if (artist == page && result.IsOk) artist = page with { Subscribed = target };
        else if (!result.IsOk) Player.Message = L.T("登録を変更できませんでした", "Couldn't change your subscription");
        UpdateSubscribe();
    }

    private async void OnLive(object sender, RoutedEventArgs e)
    {
        if (artist is null) return;
        var query = Uri.EscapeDataString(L.T($"{artist.Name} ライブ チケット", $"{artist.Name} live tickets"));
        await Windows.System.Launcher.LaunchUriAsync(new Uri($"https://www.google.com/search?q={query}"));
    }

    /// <summary>このアーティストの、自分が高く評価した曲の数（ログインしていなければ出さない）。</summary>
    private async Task ShowLikedAsync(Core.Music.ArtistPage page)
    {
        if (!AppServices.Client.IsLoggedIn) return;
        LikedText.Text = L.T("確かめています…", "Checking…");
        LikedRow.Visibility = Visibility.Visible;
        LikedRow.IsEnabled = false;
        var (songs, complete) = await AppServices.Library.LikedSongsAsync();
        if (artist != page) return;
        var count = LibraryState.ByArtist(songs, browseId, page.Name).Count;
        LikedRow.Visibility = Ui.Show(count > 0);
        LikedRow.IsEnabled = true;
        LikedText.Text = complete ? L.T($"{page.Name} の {count} 曲", $"{count} {(count == 1 ? "song" : "songs")} by {page.Name}")
            : L.T($"{page.Name} の {count} 曲以上", $"{count}+ songs by {page.Name}");
    }

    private void OnLiked(object sender, RoutedEventArgs e)
    {
        if (artist is { } page) Nav.LikedSongsBy(browseId, page.Name);
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e) => Player.PropertyChanged -= OnPlayerChanged;

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PlayerService.Current)) TrackRow.MarkCurrent(rows, Player.Current?.VideoId);
        if (e.PropertyName is nameof(PlayerService.IsPlaying) or nameof(PlayerService.SourceId)) UpdatePlayIcon();
        if (e.PropertyName is nameof(PlayerService.Shuffle) or nameof(PlayerService.SourceId)) UpdateShuffleButton();
    }

    private bool IsPlayingHere => Player.SourceId == browseId && Player.Current is not null;

    private void UpdatePlayIcon() => PlayIcon.Glyph = IsPlayingHere && Player.IsPlaying ? "" : "";

    private void OnPlay(object sender, RoutedEventArgs e)
    {
        if (IsPlayingHere)
        {
            Player.TogglePlay();
            return;
        }
        Player.PlayList(rows.Select(r => r.Song).ToList(), 0, browseId, shufflePlay ? true : null);
    }

    /// <summary>
    /// 一覧の「シャッフル再生」の指定（Android と同じ）。この一覧を再生しているときは、キューのシャッフルの入り切りになる。
    /// </summary>
    private bool shufflePlay;

    private void OnShuffle(object sender, RoutedEventArgs e)
    {
        if (IsPlayingHere) Player.ToggleShuffle();
        else shufflePlay = !shufflePlay;
        UpdateShuffleButton();
    }

    private void UpdateShuffleButton() => ShuffleButton.IsChecked = IsPlayingHere ? Player.Shuffle : shufflePlay;

    private void OnDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if ((e.OriginalSource as FrameworkElement)?.DataContext is TrackRow row) Player.PlayList(rows.Select(r => r.Song).ToList(), rows.IndexOf(row), browseId);
    }

    private void OnAllSongs(object sender, RoutedEventArgs e)
    {
        if (artist?.AllSongsPlaylistId is { } id) Nav.Playlist(new PlaylistSummary(id, artist.Name + L.T(" のすべての曲", " — all songs"), null, artist.ThumbnailUrl));
    }
}
