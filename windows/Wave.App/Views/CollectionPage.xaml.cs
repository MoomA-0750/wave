using System.Collections.ObjectModel;
using System.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Controls;
using Wave.App.Services;
using Wave.Core.Music;

namespace Wave.App.Views;

public sealed partial class CollectionPage : Page
{
    private readonly ObservableCollection<TrackRow> rows = [];
    private CollectionRequest request = null!;
    private bool editable;
    private string? playlistIdForPlayback;
    private CancellationTokenSource? cancel;

    public CollectionPage()
    {
        InitializeComponent();
        Tracks.ItemsSource = rows;
    }

    private PlayerService Player => AppServices.Player;

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        request = (CollectionRequest)e.Parameter;
        Player.PropertyChanged += OnPlayerChanged;
        ShuffleButton.IsChecked = Player.Shuffle;
        ShowHeader(request.Title, request.ThumbnailUrl, request.Subtitle);
        _ = LoadAsync();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        Player.PropertyChanged -= OnPlayerChanged;
        cancel?.Cancel();
    }

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PlayerService.Current)) TrackRow.MarkCurrent(rows, Player.Current?.VideoId);
        if (e.PropertyName is nameof(PlayerService.IsPlaying) or nameof(PlayerService.SourceId)) UpdatePlayIcon();
        if (e.PropertyName == nameof(PlayerService.Shuffle)) ShuffleButton.IsChecked = Player.Shuffle;
    }

    private bool IsPlayingHere => Player.SourceId == request.Id && Player.Current is not null;

    private void UpdatePlayIcon() => PlayIcon.Glyph = IsPlayingHere && Player.IsPlaying ? "" : "";

    private void ShowHeader(string? title, string? thumbnail, string? detail)
    {
        var liked = request.Id == MusicRepository.LikedSongs;
        KindText.Text = request.IsAlbum ? "アルバム" : "プレイリスト";
        TitleText.Text = title ?? "";
        DetailText.Text = detail ?? "";
        LikedIcon.Visibility = Ui.Show(liked);
        if (liked)
        {
            CoverBorder.Background = new LinearGradientBrush(
                [new GradientStop { Color = Windows.UI.Color.FromArgb(255, 0x45, 0x0A, 0xF5), Offset = 0 }, new GradientStop { Color = Windows.UI.Color.FromArgb(255, 0xC4, 0xEF, 0xD9), Offset = 1 }], 45);
            SetGradient(Windows.UI.Color.FromArgb(255, 0x50, 0x38, 0xA0));
        }
        else
        {
            Cover.Source = Ui.Large(thumbnail);
            _ = ColorAsync(thumbnail);
        }
        AlbumHeaderColumn.Width = request.IsAlbum ? new GridLength(0) : new GridLength(2, GridUnitType.Star);
        AlbumHeader.Visibility = Ui.Hide(request.IsAlbum);
        UpdatePlayIcon();
    }

    private async Task ColorAsync(string? url)
    {
        if (await Artwork.DominantColorAsync(url) is { } color) SetGradient(color);
    }

    private void SetGradient(Windows.UI.Color color)
    {
        var top = color;
        top.A = 150;
        var bottom = color;
        bottom.A = 0;
        Gradient.Fill = new LinearGradientBrush([new GradientStop { Color = top, Offset = 0 }, new GradientStop { Color = bottom, Offset = 1 }], 90);
    }

    private async Task LoadAsync()
    {
        cancel?.Cancel();
        var token = (cancel = new CancellationTokenSource()).Token;
        rows.Clear();
        LoadingRing.IsActive = true;
        ErrorText.Visibility = Visibility.Collapsed;
        try
        {
            if (request.IsAlbum) await LoadAlbumAsync(token);
            else await LoadPlaylistAsync(token);
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            if (!token.IsCancellationRequested) LoadingRing.IsActive = false;
        }
    }

    private async Task LoadAlbumAsync(CancellationToken token)
    {
        var result = await AppServices.Music.AlbumAsync(request.Id, token);
        if (!result.IsOk)
        {
            Fail("アルバムを読み込めませんでした");
            return;
        }
        var album = result.Value!;
        playlistIdForPlayback = album.PlaylistId;
        var total = album.Songs.Sum(s => s.DurationSeconds ?? 0);
        ShowHeader(album.Title, album.ThumbnailUrl, string.Join(" • ", new[] { string.Join(", ", album.Artists.Select(a => a.Name)), album.Subtitle, $"{album.Songs.Count} 曲, {Length(total)}" }.Where(s => !string.IsNullOrEmpty(s))));
        var n = 1;
        foreach (var s in album.Songs) rows.Add(new TrackRow(n++, s) { ShowAlbum = false, ShowArtwork = false, Menu = r => SongMenu.Build(r.Song) });
        Done();
    }

    private async Task LoadPlaylistAsync(CancellationToken token)
    {
        var first = true;
        var result = await AppServices.Music.FullPlaylistAsync(request.Id, page =>
        {
            if (token.IsCancellationRequested) return;
            if (first)
            {
                first = false;
                editable = page.Editable;
                ShowHeader(page.Title ?? request.Title, request.ThumbnailUrl ?? page.ThumbnailUrl, page.Subtitle ?? request.Subtitle);
            }
            for (var i = rows.Count; i < page.Songs.Count; i++)
            {
                var setId = i < page.SetVideoIds.Count ? page.SetVideoIds[i] : null;
                rows.Add(new TrackRow(i + 1, page.Songs[i], setId) { Menu = MenuFor });
            }
            PlayButton.IsEnabled = rows.Count > 0;
            TrackRow.MarkCurrent(rows, Player.Current?.VideoId);
        }, token);
        if (!result.IsOk)
        {
            Fail(result.Failure == Core.Net.FailureKind.NotLoggedIn ? "このプレイリストを見るにはログインしてください" : "プレイリストを読み込めませんでした");
            return;
        }
        var total = rows.Sum(r => r.Song.DurationSeconds ?? 0);
        // サーバーの補足（曲数・時間を含む）があればそれを出す。無ければ読めた曲から数える
        var subtitle = result.Value!.Subtitle ?? request.Subtitle;
        DetailText.Text = subtitle ?? $"{rows.Count} 曲, {Length(total)}";
        Done();
    }

    private MenuFlyout MenuFor(TrackRow row)
    {
        Action? remove = editable && row.SetVideoId is { } setId ? () => _ = RemoveAsync(row, setId) : null;
        return SongMenu.Build(row.Song, remove);
    }

    private async Task RemoveAsync(TrackRow row, string setVideoId)
    {
        if (await AppServices.Music.RemoveFromPlaylistAsync(request.Id, row.Song.VideoId, setVideoId))
        {
            rows.Remove(row);
            Player.Message = "プレイリストから削除しました";
        }
        else Player.Message = "削除できませんでした";
    }

    private void Done()
    {
        PlayButton.IsEnabled = rows.Count > 0;
        TrackRow.MarkCurrent(rows, Player.Current?.VideoId);
        if (rows.Count == 0) Fail("曲がありません");
    }

    private void Fail(string text)
    {
        ErrorText.Text = text;
        ErrorText.Visibility = Visibility.Visible;
    }

    private static string Length(int seconds)
    {
        var t = TimeSpan.FromSeconds(seconds);
        return t.TotalHours >= 1 ? $"{(int)t.TotalHours} 時間 {t.Minutes} 分" : $"{t.Minutes} 分 {t.Seconds} 秒";
    }

    private List<Song> Songs => rows.Select(r => r.Song).ToList();

    private void OnPlay(object sender, RoutedEventArgs e)
    {
        if (IsPlayingHere)
        {
            Player.TogglePlay();
            return;
        }
        Player.PlayList(Songs, 0, request.Id, Player.Shuffle ? true : null);
    }

    private void OnShuffle(object sender, RoutedEventArgs e) => Player.ToggleShuffle();

    private void OnDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if ((e.OriginalSource as FrameworkElement)?.DataContext is TrackRow row) Player.PlayList(Songs, rows.IndexOf(row), request.Id);
    }

    private void OnKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == Windows.System.VirtualKey.Enter && Tracks.SelectedItem is TrackRow row) Player.PlayList(Songs, rows.IndexOf(row), request.Id);
    }

    private void OnMore(object sender, RoutedEventArgs e)
    {
        var menu = new MenuFlyout();
        var queue = new MenuFlyoutItem { Text = "キューに追加", Icon = new FontIcon { Glyph = "" } };
        queue.Click += (_, _) =>
        {
            foreach (var s in Songs) Player.AddToQueue(s);
            Player.Message = $"{rows.Count} 曲をキューに追加しました";
        };
        menu.Items.Add(queue);
        menu.ShowAt((FrameworkElement)sender);
    }
}
