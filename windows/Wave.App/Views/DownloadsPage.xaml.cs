using System.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Controls;
using Wave.App.Services;
using Wave.Core.Downloads;
using Wave.Core.Music;

namespace Wave.App.Views;

/// <summary>ダウンロードの画面の、丸ごと取っているものの1行（取り終えた数を添える）。</summary>
public sealed class DownloadCollectionRow(DownloadCollection collection, CollectionDownloadState state)
{
    public DownloadCollection Collection { get; } = collection;
    public string Title { get; } = collection.Title;
    public string? ThumbnailUrl { get; } = collection.ThumbnailUrl;
    public bool IsLiked => Collection.Kind == DownloadKind.Liked;

    public string Subtitle { get; } = string.Join(" • ",
        collection.Kind switch
        {
            DownloadKind.Album => L.T("アルバム", "Album"),
            _ => L.T("プレイリスト", "Playlist"),
        },
        state.Complete ? L.Songs(state.Total) : L.T($"{state.Done} / {state.Total} 曲", $"{state.Done} of {state.Total} songs"));

    public Brush CoverBackground => IsLiked ? Ui.LikedCover() : (Brush)Application.Current.Resources["ControlFillColorSecondaryBrush"];
}

/// <summary>
/// ダウンロード（ADR-0016 の 13・14）。スマートダウンロードは分類（すべて・いつも聴いている・好きそう・おすすめ）で分けて並べ、丸ごと再生・シャッフルできる。
/// 目録だけから作るので、通信なしでも開ける。
/// </summary>
public sealed partial class DownloadsPage : Page
{
    /// <summary>選んでいる分類（null はすべて）。</summary>
    private SmartCategory? category;
    private List<TrackRow> smartRows = [];
    private List<TrackRow> singleRows = [];
    private List<TrackRow> episodeRows = [];
    private bool rendering;

    public DownloadsPage() => InitializeComponent();

    private static Services.Downloads Downloads => AppServices.Downloads;

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        Downloads.Changed += Render;
        Downloads.PropertyChanged += OnDownloadsChanged;
        AppServices.Player.PropertyChanged += OnPlayerChanged;
        rendering = true;
        SmartCountBox.Items.Clear();
        foreach (var count in SmartDownloads.Counts) SmartCountBox.Items.Add(new ComboBoxItem { Content = L.Songs(count), Tag = count });
        rendering = false;
        Render();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        Downloads.Changed -= Render;
        Downloads.PropertyChanged -= OnDownloadsChanged;
        AppServices.Player.PropertyChanged -= OnPlayerChanged;
    }

    private void OnDownloadsChanged(object? sender, PropertyChangedEventArgs e) => DispatcherQueue.TryEnqueue(RenderStatus);

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName != nameof(PlayerService.Current)) return;
        var id = AppServices.Player.Current?.VideoId;
        TrackRow.MarkCurrent(smartRows.Concat(singleRows).Concat(episodeRows), id);
    }

    private void Render()
    {
        var manifest = Downloads.Manifest;
        var settings = AppServices.Settings;
        rendering = true;
        SmartSwitch.IsOn = settings.SmartDownloads;
        SmartCountBox.SelectedIndex = Math.Max(0, Array.IndexOf(SmartDownloads.Counts, SmartDownloads.NormalizeCount(settings.SmartDownloadCount)));
        rendering = false;
        SmartPanel.Visibility = Ui.Show(settings.SmartDownloads);
        SmartCountBox.Visibility = Ui.Show(settings.SmartDownloads);
        SmartDetail.Text = L.T("いつも聴いている曲・聴いたことのない好きそうな曲・おすすめを、1日1回選び直して取っておきます",
            "Keeps songs you play often, new songs you might like, and recommendations, refreshed once a day");
        RenderChips();
        var current = AppServices.Player.Current?.VideoId;
        var n = 1;
        smartRows = manifest.Smart.Where(t => category is null || t.Category == category).Select(t => Row(n++, t.Song)).ToList();
        SmartList.ItemsSource = smartRows;
        SmartPlayButton.IsEnabled = smartRows.Count > 0;
        SmartEmpty.Text = manifest.Smart.Count == 0
            ? L.T("まだ選んでいません。通信できるときに選んで取ります", "Nothing chosen yet. Songs are picked when you're online")
            : L.T("この分類の曲はありません", "No songs in this category");
        SmartEmpty.Visibility = Ui.Show(smartRows.Count == 0);

        CollectionList.ItemsSource = manifest.Collections.Select(c => new DownloadCollectionRow(c, Downloads.StateOf(c.Id))).ToList();
        CollectionEmpty.Visibility = Ui.Show(manifest.Collections.Count == 0);

        n = 1;
        singleRows = manifest.Singles.Where(s => !IsEpisode(s)).Select(s => Row(n++, s.Song)).ToList();
        SingleList.ItemsSource = singleRows;
        SingleEmpty.Visibility = Ui.Show(singleRows.Count == 0);
        n = 1;
        episodeRows = manifest.Singles.Where(IsEpisode).Select(s => Row(n++, s.Song, album: false)).ToList();
        EpisodeList.ItemsSource = episodeRows;
        EpisodeHeader.Visibility = Ui.Show(episodeRows.Count > 0);
        EpisodeList.Visibility = Ui.Show(episodeRows.Count > 0);
        TrackRow.MarkCurrent(smartRows.Concat(singleRows).Concat(episodeRows), current);
        RenderStatus();
    }

    private static bool IsEpisode(DownloadSingle single) => single.Episode || Feed.IsExternal(single.Song.VideoId);

    private static TrackRow Row(int number, Song song, bool album = true) =>
        new(number, song) { ShowAlbum = album, Menu = r => SongMenu.Build(r.Song, episode: !album) };

    /// <summary>使っている容量・取っている途中の進み具合・通信を待っているか。</summary>
    private void RenderStatus()
    {
        UsageText.Text = L.T($"{L.Songs(Downloads.DownloadedCount)} • {Size(Downloads.UsedBytes)} を使っています", $"{L.Songs(Downloads.DownloadedCount)} • {Size(Downloads.UsedBytes)} used");
        var active = Downloads.ActiveTitle;
        ProgressPanel.Visibility = Ui.Show(active is not null);
        if (active is not null)
        {
            ProgressText.Text = L.T($"ダウンロード中: {active}（残り {Downloads.PendingCount} 曲）", $"Downloading: {active} ({Downloads.PendingCount} left)");
            ProgressBar.Value = Downloads.ActiveProgress;
        }
        NetworkBar.IsOpen = Downloads.WaitingForNetwork && Downloads.PendingCount > 0;
        NetworkBar.Message = Downloads.Network == DownloadNetwork.Unmetered
            ? L.T($"Wi-Fi など従量制でない通信になったら、残りの {Downloads.PendingCount} 曲を取ります（設定で変えられます）", $"The remaining {Downloads.PendingCount} songs will download on Wi-Fi or another unmetered network (change this in Settings)")
            : L.T($"つながったら、残りの {Downloads.PendingCount} 曲を取ります", $"The remaining {Downloads.PendingCount} songs will download when you're online");
        SmartRing.IsActive = Downloads.RefreshingSmart;
    }

    /// <summary>容量の表示（MB・GB）。</summary>
    public static string Size(long bytes) => bytes >= 1L << 30 ? $"{bytes / (double)(1L << 30):F1} GB" : $"{bytes / (double)(1L << 20):F0} MB";

    private void RenderChips()
    {
        CategoryChips.Children.Clear();
        var chips = new (SmartCategory? Category, string Title)[]
        {
            (null, L.T("すべて", "All")),
            (SmartCategory.Frequent, L.T("いつも聴いている", "Played often")),
            (SmartCategory.Likely, L.T("好きそう", "You might like")),
            (SmartCategory.Recommended, L.T("おすすめ", "Recommended")),
        };
        foreach (var (value, title) in chips)
        {
            var count = Downloads.Manifest.Smart.Count(t => value is null || t.Category == value);
            var chip = new ToggleButton { Content = $"{title} {count}", IsChecked = category == value, CornerRadius = new CornerRadius(14), Padding = new Thickness(14, 4, 14, 5) };
            chip.Click += (_, _) =>
            {
                category = value;
                Render();
            };
            CategoryChips.Children.Add(chip);
        }
    }

    private void OnSmartToggled(object sender, RoutedEventArgs e)
    {
        if (rendering) return;
        Downloads.SetSmart(SmartSwitch.IsOn);
        Render();
    }

    private void OnSmartCountChanged(object sender, SelectionChangedEventArgs e)
    {
        if (rendering || SmartCountBox.SelectedItem is not ComboBoxItem { Tag: int count }) return;
        Downloads.SetSmartCount(count);
    }

    private void PlaySmart(int start, bool shuffle)
    {
        var songs = smartRows.Select(r => r.Song).ToList();
        if (songs.Count == 0) return;
        AppServices.Player.PlayList(songs, start, null, shuffle ? true : null);
    }

    private void OnSmartPlay(object sender, RoutedEventArgs e) => PlaySmart(0, shuffle: false);

    private void OnSmartShuffle(object sender, RoutedEventArgs e) => PlaySmart(0, shuffle: true);

    private void OnSmartDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if ((e.OriginalSource as FrameworkElement)?.DataContext is TrackRow row) PlaySmart(smartRows.IndexOf(row), shuffle: false);
    }

    private void OnSingleDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if ((e.OriginalSource as FrameworkElement)?.DataContext is TrackRow row)
            AppServices.Player.PlayList(singleRows.Select(r => r.Song).ToList(), singleRows.IndexOf(row), null);
    }

    private void OnEpisodeDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if ((e.OriginalSource as FrameworkElement)?.DataContext is TrackRow row)
            AppServices.Player.PlayList(episodeRows.Select(r => r.Song).ToList(), episodeRows.IndexOf(row), null);
    }

    private void OnCollectionClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not DownloadCollectionRow { Collection: var c }) return;
        if (c.Kind == DownloadKind.Liked) Nav.LikedSongs();
        else if (c.Kind == DownloadKind.Album) Nav.Album(c.Id, new AlbumSummary(c.Id, c.Title, null, c.ThumbnailUrl));
        else Nav.Playlist(new PlaylistSummary(c.Id, c.Title, null, c.ThumbnailUrl));
    }

#if DEBUG
    /// <summary>開発用: 分類のチップを選ぶ（frequent・likely・recommended・all）と、出ている行の数。</summary>
    public int DebugCategory(string name)
    {
        category = name switch
        {
            "frequent" => SmartCategory.Frequent,
            "likely" => SmartCategory.Likely,
            "recommended" => SmartCategory.Recommended,
            _ => null,
        };
        Render();
        return smartRows.Count;
    }
#endif
}
