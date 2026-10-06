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
    /// <summary>画面に出す行（絞り込み・並べ替えの後）。</summary>
    private readonly ObservableCollection<TrackRow> rows = [];
    /// <summary>読み込んだ全部の行（元の並び）。</summary>
    private readonly List<TrackRow> all = [];
    private string filter = "";
    private SortKey sort = SortKey.Custom;
    private bool descending;

    private enum SortKey { Custom, Title, Artist, Album, Duration }
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
        KindText.Text = request.IsAlbum ? L.T("アルバム", "Album") : L.T("プレイリスト", "Playlist");
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
        all.Clear();
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
            Fail(L.T("アルバムを読み込めませんでした", "Couldn't load the album"));
            return;
        }
        var album = result.Value!;
        playlistIdForPlayback = album.PlaylistId;
        var total = album.Songs.Sum(s => s.DurationSeconds ?? 0);
        ShowHeader(album.Title, album.ThumbnailUrl, string.Join(" • ", new[] { string.Join(", ", album.Artists.Select(a => a.Name)), album.Subtitle, $"{L.Songs(album.Songs.Count)}, {L.Length(total)}" }.Where(s => !string.IsNullOrEmpty(s))));
        var n = 1;
        foreach (var s in album.Songs) AddRow(new TrackRow(n++, s) { ShowAlbum = false, ShowArtwork = false, Menu = r => SongMenu.Build(r.Song) });
        Done();
    }

    private async Task LoadPlaylistAsync(CancellationToken token)
    {
        if (request.Artist is { } artist)
        {
            await LoadLikedByArtistAsync(artist, token);
            return;
        }
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
            for (var i = all.Count; i < page.Songs.Count; i++)
            {
                var setId = i < page.SetVideoIds.Count ? page.SetVideoIds[i] : null;
                AddRow(new TrackRow(i + 1, page.Songs[i], setId) { Menu = MenuFor });
            }
            PlayButton.IsEnabled = rows.Count > 0;
            TrackRow.MarkCurrent(rows, Player.Current?.VideoId);
        }, token);
        if (!result.IsOk)
        {
            Fail(result.Failure == Core.Net.FailureKind.NotLoggedIn ? L.T("このプレイリストを見るにはログインしてください", "Log in to see this playlist") : L.T("プレイリストを読み込めませんでした", "Couldn't load the playlist"));
            return;
        }
        var total = all.Sum(r => r.Song.DurationSeconds ?? 0);
        // サーバーの補足（曲数・時間を含む）があればそれを出す。無ければ読めた曲から数える
        var subtitle = result.Value!.Subtitle ?? request.Subtitle;
        DetailText.Text = subtitle ?? $"{L.Songs(all.Count)}, {L.Length(total)}";
        Done();
    }

    /// <summary>高く評価した曲のうち、そのアーティストのもの（ライブラリで覚えた一覧から絞る）。</summary>
    private async Task LoadLikedByArtistAsync(ArtistFilter artist, CancellationToken token)
    {
        var (songs, complete) = await AppServices.Library.LikedSongsAsync();
        token.ThrowIfCancellationRequested();
        var n = 1;
        foreach (var song in LibraryState.ByArtist(songs, artist.BrowseId, artist.Name)) AddRow(new TrackRow(n++, song) { Menu = MenuFor });
        var total = all.Sum(r => r.Song.DurationSeconds ?? 0);
        DetailText.Text = $"{artist.Name} • {L.Songs(all.Count)}{(complete ? "" : "+")}, {L.Length(total)}";
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
            all.Remove(row);
            Player.Message = L.T("プレイリストから削除しました", "Removed from playlist");
        }
        else Player.Message = L.T("削除できませんでした", "Couldn't remove it");
    }

    private void Done()
    {
        PlayButton.IsEnabled = rows.Count > 0;
        TrackRow.MarkCurrent(rows, Player.Current?.VideoId);
        if (all.Count == 0) Fail(L.T("曲がありません", "No songs"));
        ApplyView();
    }

    private void Fail(string text)
    {
        ErrorText.Text = text;
        ErrorText.Visibility = Visibility.Visible;
    }

    private List<Song> Songs => rows.Select(r => r.Song).ToList();

    // --- 絞り込みと並べ替え（Spotify のプレイリストの🔍と「カスタム順」と同じ。全部読んだ曲を手元で） ---

    private bool IsDefaultView => filter.Length == 0 && sort == SortKey.Custom && !descending;

    private void AddRow(TrackRow row)
    {
        all.Add(row);
        if (IsDefaultView) rows.Add(row);
    }

    /// <summary>絞り込みと並べ替えを当てて、出す行を作り直す。再生は出ている並びで行う。</summary>
    private void ApplyView()
    {
        var terms = filter.Split(' ', '\u3000', StringSplitOptions.RemoveEmptyEntries);
        var compare = System.Globalization.CultureInfo.CurrentCulture.CompareInfo;
        const System.Globalization.CompareOptions options = System.Globalization.CompareOptions.IgnoreCase | System.Globalization.CompareOptions.IgnoreWidth | System.Globalization.CompareOptions.IgnoreKanaType;
        bool Matches(Song s)
        {
            var text = string.Join(' ', new[] { s.Title, s.ArtistsText, s.Album?.Title ?? "" });
            return terms.All(t => compare.IndexOf(text, t, options) >= 0);
        }
        IEnumerable<TrackRow> view = all.Where(r => Matches(r.Song));
        Func<TrackRow, IComparable?>? key = sort switch
        {
            SortKey.Title => r => r.Song.Title,
            SortKey.Artist => r => r.Song.ArtistsText,
            SortKey.Album => r => r.Song.Album?.Title ?? "",
            SortKey.Duration => r => r.Song.DurationSeconds ?? 0,
            _ => null,
        };
        if (key is not null) view = descending ? view.OrderByDescending(key) : view.OrderBy(key);
        else if (descending) view = view.Reverse();
        var list = view.ToList();
        if (list.SequenceEqual(rows)) return;
        rows.Clear();
        foreach (var r in list) rows.Add(r);
        TrackRow.MarkCurrent(rows, Player.Current?.VideoId);
        NoMatchText.Visibility = Ui.Show(all.Count > 0 && list.Count == 0);
    }

    private void OnSearchToggle(object sender, RoutedEventArgs e)
    {
        var open = FilterBox.Visibility != Visibility.Visible;
        FilterBox.Visibility = Ui.Show(open);
        if (open) FilterBox.Focus(FocusState.Programmatic);
        else ClearFilter();
    }

    /// <summary>閉じたら絞り込みを外す（隠れた入力欄の TextChanged は当てにしない）。</summary>
    private void ClearFilter()
    {
        FilterBox.Text = "";
        filter = "";
        ApplyView();
    }

    private void OnFilterChanged(object sender, TextChangedEventArgs e)
    {
        filter = FilterBox.Text.Trim();
        ApplyView();
    }

    private void OnFilterKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != Windows.System.VirtualKey.Escape) return;
        ClearFilter();
        FilterBox.Visibility = Visibility.Collapsed;
        e.Handled = true;
    }

    private void OnSortOpening(object sender, object e)
    {
        SortMenu.Items.Clear();
        foreach (var (k, label) in SortLabels())
        {
            var item = new ToggleMenuFlyoutItem { Text = label, IsChecked = k == sort };
            if (k == sort && k != SortKey.Custom) item.Icon = new FontIcon { Glyph = descending ? "\uE74B" : "\uE74A" };
            item.Click += (_, _) =>
            {
                // 同じ並べ方をもう一度選ぶと向きを変える
                if (k == sort) descending = !descending;
                else (sort, descending) = (k, false);
                SortText.Text = SortLabels().First(x => x.Key == sort).Label;
                ApplyView();
            };
            SortMenu.Items.Add(item);
        }
    }

    private static IEnumerable<(SortKey Key, string Label)> SortLabels() =>
    [
        (SortKey.Custom, L.T("カスタム順", "Custom order")),
        (SortKey.Title, L.T("タイトル", "Title")),
        (SortKey.Artist, L.T("アーティスト", "Artist")),
        (SortKey.Album, L.T("アルバム", "Album")),
        (SortKey.Duration, L.T("再生時間", "Duration")),
    ];

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
        var queue = new MenuFlyoutItem { Text = L.T("キューに追加", "Add to queue"), Icon = new FontIcon { Glyph = "" } };
        queue.Click += (_, _) =>
        {
            foreach (var s in Songs) Player.AddToQueue(s);
            Player.Message = L.T($"{rows.Count} 曲をキューに追加しました", $"Added {L.Songs(rows.Count)} to queue");
        };
        menu.Items.Add(queue);
        menu.ShowAt((FrameworkElement)sender);
    }
}
