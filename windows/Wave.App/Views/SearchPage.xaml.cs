using System.Collections.ObjectModel;
using System.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Controls;
using Wave.App.Services;
using Wave.Core.Music;

namespace Wave.App.Views;

/// <summary>気分・ジャンルのタイル。</summary>
public sealed record MoodTile(MoodCategory Category, Brush Brush);

public sealed partial class SearchPage : Page
{
    private static List<MoodTile>? moodCache;
    private string? query;
    private int requestId;
    private HomeItem? top;
    private readonly ObservableCollection<TrackRow> songRows = [];
    private readonly ObservableCollection<HomeItem> cards = [];
    private string? songContinuation;
    private string? albumContinuation;
    private string? artistContinuation;
    private List<AlbumSummary> albums = [];
    private List<ArtistSummary> artists = [];
    private bool loadingMore;
    // ポッドキャスト・エピソードは、その絞り込みを初めて選んだときに読む（「すべて」には出さない。Android と同じ）
    private List<PodcastSummary>? podcasts;
    private string? podcastContinuation;
    private readonly ObservableCollection<PodcastEpisode> episodes = [];
    private bool episodesLoaded;
    private string? episodeContinuation;
    private bool loadingTab;
    private readonly ObservableCollection<TrackRow> liveRows = [];
    private string? liveContinuation;
    /// <summary>ライブのタブを読んだ検索の番号（タブを開いたときに一度だけ読む）。</summary>
    private int liveLoadedFor = -1;
    private bool liveLoaded;
    private bool noResults;

    public SearchPage()
    {
        InitializeComponent();
        SongList.ItemsSource = songRows;
        LiveList.ItemsSource = liveRows;
        CardGrid.ItemsSource = cards;
        EpisodeList.ItemsSource = episodes;
        AppServices.Player.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName != nameof(PlayerService.Current)) return;
            TrackRow.MarkCurrent(songRows, AppServices.Player.Current?.VideoId);
            TrackRow.MarkCurrent(liveRows, AppServices.Player.Current?.VideoId);
        };
        SongList.Loaded += (_, _) => Attach(SongList, () => LoadMoreSongsAsync());
        CardGrid.Loaded += (_, _) => Attach(CardGrid, () => LoadMoreCardsAsync());
        EpisodeList.Loaded += (_, _) => Attach(EpisodeList, () => LoadMoreEpisodesAsync());
        LiveList.Loaded += (_, _) => Attach(LiveList, () => LoadMoreLiveAsync());
    }

    public static Visibility IsQuery(string kind) => kind == "query" ? Visibility.Visible : Visibility.Collapsed;

    public static bool IsRound(string kind) => kind == "artist";

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        Search(e.Parameter as string);
    }

    /// <summary>検索し直す（上の検索欄から呼ばれる）。空なら「すべてをブラウズ」に戻る。</summary>
    public void Search(string? text)
    {
        query = string.IsNullOrWhiteSpace(text) ? null : text.Trim();
        if (query is null)
        {
            ResultsView.Visibility = Visibility.Collapsed;
            BrowseView.Visibility = Visibility.Visible;
            ShowRecent();
            _ = LoadMoodsAsync();
            return;
        }
        BrowseView.Visibility = Visibility.Collapsed;
        ResultsView.Visibility = Visibility.Visible;
        _ = LoadResultsAsync();
    }

    private void ShowRecent()
    {
        var history = AppServices.Settings.SearchHistory.ToList();
        RecentPanel.Visibility = Ui.Show(history.Count > 0);
        RecentList.ItemsSource = history.Take(12).ToList();
    }

    private async Task LoadMoodsAsync()
    {
        if (moodCache is null)
        {
            var result = await AppServices.Music.MoodsAsync();
            if (!result.IsOk) return;
            var index = 0;
            moodCache = result.Value!.SelectMany(s => s.Categories).Select(c => new MoodTile(c, Ui.Tile(c.Color, index++))).ToList();
        }
        MoodTiles.ItemsSource = moodCache;
    }

    private async Task LoadResultsAsync()
    {
        var id = ++requestId;
        var q = query!;
        LoadingRing.IsActive = true;
        Empty.Visibility = Visibility.Collapsed;
        AllView.Visibility = Visibility.Collapsed;
        songRows.Clear();
        cards.Clear();
        episodes.Clear();
        podcasts = null;
        podcastContinuation = null;
        episodesLoaded = false;
        episodeContinuation = null;
        loadingTab = false;
        EpisodeList.Visibility = Visibility.Collapsed;
        liveRows.Clear();
        liveContinuation = null;
        liveLoadedFor = -1;
        liveLoaded = false;
        noResults = false;
        LiveList.Visibility = Visibility.Collapsed;
        var songsTask = AppServices.Music.SearchSongsAsync(q);
        var albumsTask = AppServices.Music.SearchAlbumsAsync(q);
        var artistsTask = AppServices.Music.SearchArtistsAsync(q);
        await Task.WhenAll(songsTask, albumsTask, artistsTask);
        if (id != requestId) return;
        LoadingRing.IsActive = false;
        var songs = songsTask.Result.IsOk ? songsTask.Result.Value!.Songs.ToList() : [];
        songContinuation = songsTask.Result.Value?.Continuation;
        albums = albumsTask.Result.IsOk ? albumsTask.Result.Value!.Items.ToList() : [];
        albumContinuation = albumsTask.Result.Value?.Continuation;
        artists = artistsTask.Result.IsOk ? artistsTask.Result.Value!.Items.ToList() : [];
        artistContinuation = artistsTask.Result.Value?.Continuation;
        if (songs.Count == 0 && albums.Count == 0 && artists.Count == 0)
        {
            noResults = true;
            Empty.Visibility = Visibility.Visible;
            // ライブ・ポッドキャスト・エピソードのタブを開いていれば、それは別に探す
            if (SelectedTab is "live" or "podcasts" or "episodes") ShowTab();
            return;
        }
        var number = 1;
        foreach (var s in songs) songRows.Add(new TrackRow(number++, s) { Menu = r => SongMenu.Build(r.Song) });
        TrackRow.MarkCurrent(songRows, AppServices.Player.Current?.VideoId);
        TopSongs.ItemsSource = songRows.Take(4).Select(r => new TrackRow(r.Number, r.Song) { ShowAlbum = false, IsCurrent = r.IsCurrent }).ToList();
        ArtistShelf.Items = artists.Select(a => (HomeItem)new HomeItem.ArtistItem(a)).ToList();
        ArtistShelf.Visibility = Ui.Show(artists.Count > 0);
        AlbumShelf.Items = albums.Select(a => (HomeItem)new HomeItem.AlbumItem(a)).ToList();
        AlbumShelf.Visibility = Ui.Show(albums.Count > 0);
        top = TopResult(q, songs, artists, albums);
        ShowTop();
        ShowTab();
    }

    /// <summary>トップの検索結果: アーティストの名前が検索語と同じならアーティスト、ほかは最初の曲（Android 版の SearchOrder と同じ）。</summary>
    internal static HomeItem? TopResult(string q, List<Song> songs, List<ArtistSummary> artists, List<AlbumSummary> albums)
    {
        var key = Normalize(q);
        if (artists.FirstOrDefault(a => Normalize(a.Name) == key) is { } artist) return new HomeItem.ArtistItem(artist);
        if (albums.FirstOrDefault(a => Normalize(a.Title) == key) is { } album) return new HomeItem.AlbumItem(album);
        if (songs.Count > 0) return new HomeItem.SongItem(songs[0]);
        if (artists.Count > 0) return new HomeItem.ArtistItem(artists[0]);
        return albums.Count > 0 ? new HomeItem.AlbumItem(albums[0]) : null;
    }

    internal static string Normalize(string text) =>
        new string(text.Normalize(NormalizationForm.FormKC).ToLowerInvariant().Where(c => !char.IsWhiteSpace(c)).ToArray());

    private void ShowTop()
    {
        TopPanel.Visibility = Ui.Show(top is not null);
        if (top is null) return;
        TopImage.Source = Ui.Img(top.ThumbnailUrl);
        TopImageBorder.CornerRadius = Ui.CoverRadius(top);
        TopTitle.Text = top.Title;
        TopSubtitle.Text = top switch
        {
            HomeItem.SongItem s => L.T("曲 • ", "Song • ") + s.Song.ArtistsText,
            HomeItem.ArtistItem => L.T("アーティスト", "Artist"),
            HomeItem.AlbumItem a => a.Album.Subtitle ?? L.T("アルバム", "Album"),
            _ => "",
        };
    }

#if DEBUG
    /// <summary>開発用: タブを選ぶ（DebugCommands の search-tab）。</summary>
    public void DebugTab(string tag) => Tabs.SelectedItem = Tabs.Items.FirstOrDefault(i => i.Tag as string == tag);

    /// <summary>開発用: ライブのタブの行の数。</summary>
    public int DebugLiveRows => liveRows.Count;
#endif

    private string SelectedTab => (Tabs.SelectedItem?.Tag as string) ?? "all";

    private void OnTabChanged(SelectorBar sender, SelectorBarSelectionChangedEventArgs args) => ShowTab();

    private void ShowTab()
    {
        if (query is null || LoadingRing.IsActive) return;
        var tab = SelectedTab;
        var live = tab == "live";
        AllView.Visibility = Ui.Show(tab == "all" && !noResults);
        SongList.Visibility = Ui.Show(tab == "songs");
        CardGrid.Visibility = Ui.Show(tab is "albums" or "artists" or "podcasts");
        EpisodeList.Visibility = Ui.Show(tab == "episodes");
        LiveList.Visibility = Ui.Show(live);
        // ライブ・ポッドキャスト・エピソードは、選んだときに別に読んだ結果で「見つからない」を決める
        Empty.Visibility = Ui.Show(live ? liveLoaded && liveRows.Count == 0 : noResults && tab is not ("podcasts" or "episodes"));
        if (live && liveLoadedFor != requestId) _ = LoadLiveAsync();
        if (tab is "albums" or "artists")
        {
            cards.Clear();
            IEnumerable<HomeItem> items = tab == "albums" ? albums.Select(a => new HomeItem.AlbumItem(a)) : artists.Select(a => new HomeItem.ArtistItem(a));
            foreach (var item in items) cards.Add(item);
        }
        else if (tab == "podcasts")
        {
            cards.Clear();
            if (podcasts is null) _ = LoadPodcastsAsync();
            else
            {
                foreach (var p in podcasts) cards.Add(new HomeItem.PodcastItem(p));
                Empty.Visibility = Ui.Show(podcasts.Count == 0);
            }
        }
        else if (tab == "episodes")
        {
            if (!episodesLoaded) _ = LoadEpisodesAsync();
            else Empty.Visibility = Ui.Show(episodes.Count == 0);
        }
    }

    /// <summary>ポッドキャスト（番組）の最初のページ。</summary>
    private async Task LoadPodcastsAsync()
    {
        if (loadingTab || query is null) return;
        loadingTab = true;
        var id = requestId;
        LoadingRing.IsActive = true;
        // Apple の目録の番組（RSS、ADR-0012）を先に、YouTube の番組を後に並べる。どちらかが読めれば、読めた分を出す（Android と同じ）
        var directory = AppServices.Feeds.SearchAsync(query);
        var page = await AppServices.Music.SearchPodcastsAsync(query);
        var rss = await directory;
        if (id != requestId) return;
        loadingTab = false;
        LoadingRing.IsActive = false;
        podcasts = [.. rss ?? [], .. page.IsOk ? page.Value!.Items : []];
        podcastContinuation = page.Value?.Continuation;
        AppPaths.Log($"search podcasts ok={page.IsOk} directory={rss?.Count} items={podcasts.Count}");
        if (SelectedTab == "podcasts") ShowTab();
    }

    /// <summary>エピソードの最初のページ。</summary>
    private async Task LoadEpisodesAsync()
    {
        if (loadingTab || query is null) return;
        loadingTab = true;
        var id = requestId;
        LoadingRing.IsActive = true;
        var page = await AppServices.Music.SearchEpisodesAsync(query);
        if (id != requestId) return;
        loadingTab = false;
        LoadingRing.IsActive = false;
        episodesLoaded = true;
        episodes.Clear();
        if (page.IsOk) foreach (var e in page.Value!.Items) episodes.Add(e);
        episodeContinuation = page.Value?.Continuation;
        AppPaths.Log($"search episodes ok={page.IsOk} items={episodes.Count}");
        if (SelectedTab == "episodes") ShowTab();
    }

    private async Task LoadMoreEpisodesAsync()
    {
        if (loadingMore || episodeContinuation is null || query is null) return;
        loadingMore = true;
        var id = requestId;
        var page = await AppServices.Music.SearchEpisodesAsync(query, episodeContinuation);
        loadingMore = false;
        if (id != requestId || !page.IsOk) return;
        episodeContinuation = page.Value!.Continuation;
        foreach (var e in page.Value.Items.Where(e => episodes.All(x => x.Song.VideoId != e.Song.VideoId))) episodes.Add(e);
    }

    /// <summary>エピソードを押した: その1話を再生する（曲を押したのと同じ）。</summary>
    private void OnEpisodeDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if ((e.OriginalSource as FrameworkElement)?.DataContext is not PodcastEpisode episode) return;
        Remember(new HomeItem.SongItem(episode.Song));
        AppServices.Player.PlaySong(episode.Song);
    }

    private void OnEpisodeRightTapped(object sender, RightTappedRoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: PodcastEpisode episode } element) SongMenu.Build(episode.Song).ShowAt(element, e.GetPosition(element));
    }

    private void Remember(HomeItem item) => AppServices.Settings.AddSearch(SearchHistoryEntry.Of(item));

    private void OnTopClick(object sender, RoutedEventArgs e)
    {
        if (top is null) return;
        Remember(top);
        Nav.Open(top);
    }

    private void OnTopPlay(object sender, RoutedEventArgs e)
    {
        if (top is null) return;
        Remember(top);
        Nav.Play(top);
    }

    private void OnSongDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if ((e.OriginalSource as FrameworkElement)?.DataContext is not TrackRow row) return;
        Remember(new HomeItem.SongItem(row.Song));
        AppServices.Player.PlaySong(row.Song);
    }

    private void OnRecentClick(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: SearchHistoryEntry entry }) return;
        AppServices.Settings.AddSearch(entry);
        if (entry.ToItem() is { } item) Nav.Open(item);
        else App.Window?.SearchFor(entry.Title);
    }

    private void OnRemoveRecent(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: SearchHistoryEntry entry }) AppServices.Settings.RemoveSearch(entry);
        ShowRecent();
    }

    private void OnClearHistory(object sender, RoutedEventArgs e)
    {
        AppServices.Settings.SearchHistory.Clear();
        AppServices.Settings.Save();
        ShowRecent();
    }

    private void OnMoodClick(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: MoodCategory mood }) Nav.Mood(mood);
    }

    /// <summary>一覧の下の端に来たら続きを読む。</summary>
    private static void Attach(ListViewBase list, Func<Task> loadMore)
    {
        var scroller = FindScroller(list);
        if (scroller is null) return;
        scroller.ViewChanged += (_, _) =>
        {
            if (scroller.VerticalOffset > scroller.ScrollableHeight - 400) _ = loadMore();
        };
    }

    private static ScrollViewer? FindScroller(DependencyObject root)
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            var child = VisualTreeHelper.GetChild(root, i);
            if (child is ScrollViewer s) return s;
            if (FindScroller(child) is { } found) return found;
        }
        return null;
    }

    /// <summary>ライブのタブの最初のページ（タブを開いたときに読む）。</summary>
    private async Task LoadLiveAsync()
    {
        if (query is null) return;
        var id = requestId;
        liveLoadedFor = id;
        LiveLoading.IsActive = true;
        var page = await AppServices.Music.SearchLiveAsync(query);
        if (id != requestId) return;
        LiveLoading.IsActive = false;
        liveLoaded = true;
        if (page.IsOk)
        {
            liveContinuation = page.Value!.Continuation;
            var number = 1;
            foreach (var s in page.Value.Songs) liveRows.Add(new TrackRow(number++, s) { Menu = r => SongMenu.Build(r.Song) });
            TrackRow.MarkCurrent(liveRows, AppServices.Player.Current?.VideoId);
        }
        AppPaths.Log($"search live ok={page.IsOk} n={liveRows.Count} more={liveContinuation is not null}");
        if (SelectedTab == "live") Empty.Visibility = Ui.Show(liveRows.Count == 0);
    }

    private async Task LoadMoreLiveAsync()
    {
        if (loadingMore || liveContinuation is null || query is null) return;
        loadingMore = true;
        var id = requestId;
        var page = await AppServices.Music.SearchLiveAsync(query, liveContinuation);
        loadingMore = false;
        if (id != requestId || !page.IsOk) return;
        liveContinuation = page.Value!.Continuation;
        var known = liveRows.Select(r => r.Song.VideoId).ToHashSet();
        var number = liveRows.Count + 1;
        foreach (var s in page.Value.Songs.Where(s => known.Add(s.VideoId))) liveRows.Add(new TrackRow(number++, s) { Menu = r => SongMenu.Build(r.Song) });
    }

    private async Task LoadMoreSongsAsync()
    {
        if (loadingMore || songContinuation is null || query is null) return;
        loadingMore = true;
        var id = requestId;
        var page = await AppServices.Music.SearchSongsAsync(query, songContinuation);
        loadingMore = false;
        if (id != requestId || !page.IsOk) return;
        songContinuation = page.Value!.Continuation;
        var number = songRows.Count + 1;
        foreach (var s in page.Value.Songs) songRows.Add(new TrackRow(number++, s) { Menu = r => SongMenu.Build(r.Song) });
    }

    private async Task LoadMoreCardsAsync()
    {
        if (loadingMore || query is null) return;
        var tab = SelectedTab;
        var id = requestId;
        if (tab == "albums" && albumContinuation is { } ac)
        {
            loadingMore = true;
            var page = await AppServices.Music.SearchAlbumsAsync(query, ac);
            loadingMore = false;
            if (id != requestId || !page.IsOk) return;
            albumContinuation = page.Value!.Continuation;
            albums.AddRange(page.Value.Items);
            foreach (var a in page.Value.Items) cards.Add(new HomeItem.AlbumItem(a));
        }
        else if (tab == "artists" && artistContinuation is { } rc)
        {
            loadingMore = true;
            var page = await AppServices.Music.SearchArtistsAsync(query, rc);
            loadingMore = false;
            if (id != requestId || !page.IsOk) return;
            artistContinuation = page.Value!.Continuation;
            artists.AddRange(page.Value.Items);
            foreach (var a in page.Value.Items) cards.Add(new HomeItem.ArtistItem(a));
        }
        else if (tab == "podcasts" && podcastContinuation is { } pc && podcasts is not null)
        {
            loadingMore = true;
            var page = await AppServices.Music.SearchPodcastsAsync(query, pc);
            loadingMore = false;
            if (id != requestId || !page.IsOk) return;
            podcastContinuation = page.Value!.Continuation;
            podcasts.AddRange(page.Value.Items);
            foreach (var p in page.Value.Items) cards.Add(new HomeItem.PodcastItem(p));
        }
    }
}
