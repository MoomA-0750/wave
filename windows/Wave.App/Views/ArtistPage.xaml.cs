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
        ShuffleButton.IsChecked = Player.Shuffle;
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
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e) => Player.PropertyChanged -= OnPlayerChanged;

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PlayerService.Current)) TrackRow.MarkCurrent(rows, Player.Current?.VideoId);
        if (e.PropertyName is nameof(PlayerService.IsPlaying) or nameof(PlayerService.SourceId)) UpdatePlayIcon();
        if (e.PropertyName == nameof(PlayerService.Shuffle)) ShuffleButton.IsChecked = Player.Shuffle;
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
        Player.PlayList(rows.Select(r => r.Song).ToList(), 0, browseId, Player.Shuffle ? true : null);
    }

    private void OnShuffle(object sender, RoutedEventArgs e) => Player.ToggleShuffle();

    private void OnDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if ((e.OriginalSource as FrameworkElement)?.DataContext is TrackRow row) Player.PlayList(rows.Select(r => r.Song).ToList(), rows.IndexOf(row), browseId);
    }

    private void OnAllSongs(object sender, RoutedEventArgs e)
    {
        if (artist?.AllSongsPlaylistId is { } id) Nav.Playlist(new PlaylistSummary(id, artist.Name + " のすべての曲", null, artist.ThumbnailUrl));
    }
}
