using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Services;
using Wave.Core.Music;

namespace Wave.App.Views;

/// <summary>
/// 曲の画面（カードの曲を押したときに開く。Android の SongScreen と同じ）。押してもすぐには再生せず、
/// 再生ボタン（その曲から始まるラジオ）と曲のメニューと同じ操作を並べる。
/// </summary>
public sealed partial class SongPage : Page
{
    private Song song = null!;

    public SongPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        song = (Song)e.Parameter;
        TitleText.Text = song.Title;
        DetailText.Text = string.Join(" • ", new[] { song.ArtistsText, song.Album?.Title, Ui.Duration(song.DurationSeconds) }.Where(t => !string.IsNullOrEmpty(t)));
        Cover.Source = Ui.Large(song.ThumbnailUrl);
        _ = ColorAsync(song.ThumbnailUrl);
        // 並びは Android の曲の画面と同じ（曲のラジオは再生ボタンと同じなので出さない）
        Actions.Children.Clear();
        if (AppServices.Client.IsLoggedIn) Row(L.T("プレイリストに追加", "Add to playlist"), "", () => _ = Dialogs.AddToPlaylistAsync(song));
        Row(L.T("次に再生", "Play next"), "", () => AppServices.Player.PlayNext(song));
        Row(L.T("キューに追加", "Add to queue"), "", () => AppServices.Player.AddToQueue(song));
        if (song.Album?.BrowseId is { } albumId) Row(L.T("アルバムへ移動", "Go to album"), "", () => Nav.Album(albumId));
        foreach (var artist in song.Artists.Where(a => a.BrowseId is not null))
            Row(song.Artists.Count > 1 ? L.T($"アーティストへ移動: {artist.Name}", $"Go to artist: {artist.Name}") : L.T("アーティストへ移動", "Go to artist"), "", () => Nav.Artist(artist.BrowseId!));
        Row(L.T("曲のリンクをコピー", "Copy song link"), "", () => SongMenu.CopyLink(song));
        Row(L.T("ほかのアプリで共有…", "Share with…"), "", () => SongMenu.Share(song));
    }

    private void Row(string text, string glyph, Action action)
    {
        var content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16 };
        content.Children.Add(new FontIcon { Glyph = glyph, FontSize = 16 });
        content.Children.Add(new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center });
        var button = new Button
        {
            Content = content,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Left,
            Padding = new Thickness(12, 10, 12, 10),
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
            BorderThickness = new Thickness(0),
        };
        button.Click += (_, _) => action();
        Actions.Children.Add(button);
    }

    private void OnPlay(object sender, RoutedEventArgs e) => AppServices.Player.PlaySong(song);

    private async Task ColorAsync(string? url)
    {
        if (await Artwork.DominantColorAsync(url) is { } color) Gradient.Fill = Ui.Fade(color, 150, 0);
    }
}
