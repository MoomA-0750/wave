using System.Collections.ObjectModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Services;
using Wave.Core.Music;

namespace Wave.App.Views;

/// <summary>
/// ポッドキャストの番組のページ（Android と同じ）。エピソードを押すと、読み込んだエピソードを番組のプレイリスト（PL…）を出どころにして、
/// その話から流す。下まで来たら続きのエピソードを読む。保存（ライブラリに追加）はログインしていて状態が分かるときだけ出す。
/// RSS の番組（rss:…、ADR-0012）はフィードを読んで同じ形で出す。保存はこの端末の登録の切り替えで、流すときはプレイリストを出どころにしない。
/// </summary>
public sealed partial class PodcastPage : Page
{
    private PodcastSummary summary = null!;
    private Core.Music.PodcastPage? page;
    private readonly ObservableCollection<PodcastEpisode> episodes = [];
    private string? continuation;
    private bool loadingMore;
    private bool saving;

    /// <summary>読んだ RSS の番組（YouTube の番組なら null）と、読んだ項目の数（続きを読むたびに増える）。</summary>
    private FeedShow? feedShow;
    private int feedLoaded = FeedShow.PageSize;

    public PodcastPage()
    {
        InitializeComponent();
        Episodes.ItemsSource = episodes;
        Episodes.Loaded += (_, _) => AttachScroll();
    }

    public static double ProgressValue(int? percent) => percent ?? 0;

    public static Visibility ProgressVisible(int? percent) => Ui.Show(percent is not null);

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        summary = (PodcastSummary)e.Parameter;
        TitleText.Text = summary.Title;
        AuthorText.Text = summary.Author ?? "";
        Cover.Source = Ui.Img(summary.ThumbnailUrl, 544);
        if (Feed.FeedUrlOf(summary.BrowseId) is { } feedUrl)
        {
            await LoadFeedAsync(feedUrl);
            return;
        }
        var result = await AppServices.Music.PodcastAsync(summary.BrowseId);
        LoadingRing.IsActive = false;
        if (!result.IsOk)
        {
            AppPaths.Log($"podcast failed {result.Failure}");
            // 保存した番組が無いアカウントの「新しいエピソード」は、番組のページの形で返らない。失敗ではなく空として出す（Android・Mac と同じ）
            var emptyNewEpisodes = result.Failure == Core.Net.FailureKind.Parse && summary.BrowseId == MusicRepository.NewEpisodesBrowse;
            if (emptyNewEpisodes) ErrorText.Text = L.T("エピソードがありません", "No episodes");
            ErrorText.Visibility = Visibility.Visible;
            // YouTube の新着が無くても、RSS で登録した番組の新しい回は出す
            if (emptyNewEpisodes)
            {
                page = new Core.Music.PodcastPage(summary.BrowseId, summary.Title, null, null, null, [], null);
                await MergeFeedEpisodesAsync(page);
            }
            return;
        }
        page = result.Value!;
        AppPaths.Log($"podcast episodes={page.Episodes.Count} more={page.Continuation is not null} saved={page.Saved}");
        TitleText.Text = page.Title;
        AuthorText.Text = page.Author ?? "";
        DescriptionText.Text = page.Description ?? "";
        MoreLink.Visibility = Ui.Show((page.Description?.Length ?? 0) > 200);
        if (page.ThumbnailUrl is not null) Cover.Source = Ui.Img(page.ThumbnailUrl, 544);
        foreach (var episode in page.Episodes) episodes.Add(episode);
        continuation = page.Continuation;
        PlayButton.Visibility = Ui.Show(episodes.Count > 0);
        UpdateSave();
        if (summary.BrowseId == MusicRepository.NewEpisodesBrowse) await MergeFeedEpisodesAsync(page);
    }

    /// <summary>
    /// 「新しいエピソード」に、RSS で登録した番組の新しい回を混ぜる（ADR-0012。Android の PodcastViewModel.mergeFeedEpisodes と同じ）。
    /// 番組のフィードは4つずつ並べて読み、読めなかった番組は飛ばす。混ぜた後の続き（YouTube の続き）はそのまま後ろに付く。
    /// </summary>
    private async Task MergeFeedEpisodesAsync(Core.Music.PodcastPage loaded)
    {
        var library = AppServices.FeedLibrary;
        var subscriptions = library.State.Subscriptions;
        if (subscriptions.Count == 0) return;
        var started = System.Diagnostics.Stopwatch.StartNew();
        using var gate = new SemaphoreSlim(4);
        var shows = (await Task.WhenAll(subscriptions.Select(async subscription =>
        {
            await gate.WaitAsync();
            try
            {
                return await AppServices.Feeds.ShowAsync(subscription.FeedUrl) is FeedResult.Ok ok ? ok.Show : null;
            }
            finally
            {
                gate.Release();
            }
        }))).OfType<FeedShow>().ToList();
        if (page != loaded) return;
        var merged = NewEpisodes.Merge(episodes.ToList(), shows, DateTimeOffset.UtcNow.ToUnixTimeSeconds(), library.State.Positions.GetValueOrDefault);
        AppPaths.Log($"podcast new episodes feeds={shows.Count}/{subscriptions.Count} episodes={merged.Count} rss={merged.Count(e => Feed.IsExternal(e.Song.VideoId))} ms={started.ElapsedMilliseconds}");
        page = loaded with { Episodes = merged };
        episodes.Clear();
        foreach (var episode in merged) episodes.Add(episode);
        PlayButton.Visibility = Ui.Show(episodes.Count > 0);
        if (episodes.Count > 0) ErrorText.Visibility = Visibility.Collapsed;
    }

    /// <summary>RSS の番組を読む（Android の PodcastViewModel.loadFeed と同じ）。</summary>
    private async Task LoadFeedAsync(string url)
    {
        var library = AppServices.FeedLibrary;
        var started = System.Diagnostics.Stopwatch.StartNew();
        var result = await AppServices.Feeds.ShowAsync(url);
        LoadingRing.IsActive = false;
        if (result is not FeedResult.Ok ok)
        {
            var failed = (FeedResult.Failed)result;
            AppPaths.Log($"podcast feed failed {failed.Failure} status={failed.Status}");
            ErrorText.Visibility = Visibility.Visible;
            return;
        }
        feedShow = ok.Show;
        library.Refresh(feedShow);
        var state = library.State;
        page = feedShow.ToPage(state.IsSubscribed(url), feedLoaded, state.Positions.GetValueOrDefault);
        AppPaths.Log($"podcast feed episodes={page.Episodes.Count} items={feedShow.ItemCount} saved={page.Saved} ms={started.ElapsedMilliseconds}");
        TitleText.Text = page.Title;
        AuthorText.Text = page.Author ?? "";
        DescriptionText.Text = page.Description ?? "";
        MoreLink.Visibility = Ui.Show((page.Description?.Length ?? 0) > 200);
        if (page.ThumbnailUrl is not null) Cover.Source = Ui.Img(page.ThumbnailUrl, 544);
        foreach (var episode in page.Episodes) episodes.Add(episode);
        continuation = page.Continuation;
        PlayButton.Visibility = Ui.Show(episodes.Count > 0);
        UpdateSave();
        library.Changed += OnFeedLibraryChanged;
    }

    /// <summary>聴いたところ・登録が変わったら、表示を合わせる（行は置き換えるだけで、並びとスクロールは保つ）。</summary>
    private void OnFeedLibraryChanged(FeedLibraryState state) => DispatcherQueue.TryEnqueue(() =>
    {
        if (feedShow is not { } show || page is null) return;
        page = page with { Saved = state.IsSubscribed(show.FeedUrl) };
        var fresh = show.ToPage(page.Saved, feedLoaded, state.Positions.GetValueOrDefault).Episodes;
        for (var i = 0; i < Math.Min(fresh.Count, episodes.Count); i++)
            if (episodes[i].Song.VideoId == fresh[i].Song.VideoId && episodes[i].ProgressPercent != fresh[i].ProgressPercent) episodes[i] = fresh[i];
        UpdateSave();
    });

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        base.OnNavigatedFrom(e);
        AppServices.FeedLibrary.Changed -= OnFeedLibraryChanged;
    }

    private void OnMoreDescription(object sender, RoutedEventArgs e)
    {
        var expanded = DescriptionText.MaxLines == 0;
        DescriptionText.MaxLines = expanded ? 3 : 0;
        MoreLink.Content = expanded ? L.T("すべて表示", "Show more") : L.T("一部のみ表示", "Show less");
    }

    private void UpdateSave()
    {
        // RSS の番組の登録はこの端末のものなので、ログインしていなくても出す
        SaveButton.Visibility = Ui.Show(page?.Saved is not null && (feedShow is not null || AppServices.Client.IsLoggedIn));
        SaveButton.Content = page?.Saved == true ? L.T("保存済み", "Saved") : L.T("ライブラリに保存", "Save to library");
        SaveButton.IsEnabled = !saving;
        // 保存していなければ色付き、保存済みなら控えめに（アーティストの登録と同じ）
        SaveButton.Style = page?.Saved == true ? null : (Style)Application.Current.Resources["AccentButtonStyle"];
    }

    /// <summary>保存の切り替え。書けたら表示を変える（アーティストの登録と同じく楽観的に変えない）。</summary>
    private async void OnSave(object sender, RoutedEventArgs e)
    {
        if (page is not { Saved: { } saved } current || saving) return;
        // RSS の番組は、この端末の登録を切り替える（表示は登録の変化で変わる）
        if (feedShow is { } show)
        {
            if (saved) AppServices.FeedLibrary.Unsubscribe(show.FeedUrl);
            else AppServices.FeedLibrary.Subscribe(show.Subscription);
            AppPaths.Log($"podcast feed save={!saved}");
            return;
        }
        saving = true;
        UpdateSave();
        var result = await AppServices.Music.SetPodcastSavedAsync(current.PlaylistId, !saved);
        saving = false;
        AppPaths.Log($"podcast save ok={result.IsOk} save={!saved}");
        if (page == current && result.IsOk)
        {
            page = current with { Saved = !saved };
            _ = AppServices.Library.RefreshPodcastsAsync();
        }
        else if (!result.IsOk) AppServices.Player.Message = L.T("ライブラリを変更できませんでした", "Couldn't change your library");
        UpdateSave();
    }

    private void OnPlay(object sender, RoutedEventArgs e) => PlayFrom(0);

#if DEBUG
    /// <summary>開発用: 「保存」を押したのと同じ。</summary>
    public void DebugSave() => OnSave(SaveButton, new RoutedEventArgs());

    /// <summary>開発用: index 番目のエピソードを押したのと同じ。</summary>
    public void DebugPlay(int index) => PlayFrom(index);

    /// <summary>開発用: 読んだエピソードの数と、聴いたところの出ている数。</summary>
    public string DebugState => $"episodes={episodes.Count} progress={episodes.Count(e => e.ProgressPercent is not null)} saved={page?.Saved} more={continuation is not null} save-visible={SaveButton.Visibility == Visibility.Visible}";

    /// <summary>開発用: 続きを読む（下まで来たのと同じ）。</summary>
    public Task DebugMoreAsync() => LoadMoreAsync();
#endif

    private void PlayFrom(int index)
    {
        if (page is null || episodes.Count == 0) return;
        // RSS の番組は YouTube のプレイリストとして扱わない（出どころを付けない）
        AppServices.Player.PlayList(episodes.Select(x => x.Song).ToList(), index, feedShow is null ? page.PlaylistId : null);
    }

    private void OnDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if ((e.OriginalSource as FrameworkElement)?.DataContext is not PodcastEpisode episode) return;
        PlayFrom(episodes.IndexOf(episode));
    }

    private void OnRowRightTapped(object sender, RightTappedRoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: PodcastEpisode episode } element)
        {
            SongMenu.Build(episode.Song).ShowAt(element, e.GetPosition(element));
            e.Handled = true;
        }
    }

    private void AttachScroll()
    {
        if (FindScroller(Episodes) is not { } scroller) return;
        scroller.ViewChanged += (_, _) =>
        {
            if (scroller.VerticalOffset > scroller.ScrollableHeight - 400) _ = LoadMoreAsync();
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

    private async Task LoadMoreAsync()
    {
        if (loadingMore || page is not { } current || continuation is not { } token) return;
        // RSS の番組は、読んだフィードの続きの項目を組み立てる
        if (feedShow is { } show)
        {
            var more = show.More(token, AppServices.FeedLibrary.State.Positions.GetValueOrDefault);
            feedLoaded = FeedShow.LoadedAfter(token) ?? feedLoaded;
            continuation = more.Continuation;
            foreach (var episode in more.Items.Where(x => episodes.All(y => y.Song.VideoId != x.Song.VideoId))) episodes.Add(episode);
            AppPaths.Log($"podcast feed more loaded={episodes.Count} more={continuation is not null}");
            return;
        }
        loadingMore = true;
        var result = await AppServices.Music.PodcastEpisodesAsync(token, new ArtistRef(current.Title, null), current.BrowseId);
        loadingMore = false;
        if (page != current || !result.IsOk) return;
        continuation = result.Value!.Continuation != token ? result.Value.Continuation : null;
        foreach (var episode in result.Value.Items.Where(x => episodes.All(y => y.Song.VideoId != x.Song.VideoId))) episodes.Add(episode);
    }
}
