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
/// </summary>
public sealed partial class PodcastPage : Page
{
    private PodcastSummary summary = null!;
    private Core.Music.PodcastPage? page;
    private readonly ObservableCollection<PodcastEpisode> episodes = [];
    private string? continuation;
    private bool loadingMore;
    private bool saving;

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
        var result = await AppServices.Music.PodcastAsync(summary.BrowseId);
        LoadingRing.IsActive = false;
        if (!result.IsOk)
        {
            AppPaths.Log($"podcast failed {result.Failure}");
            // 保存した番組が無いアカウントの「新しいエピソード」は、番組のページの形で返らない。失敗ではなく空として出す（Android・Mac と同じ）
            var emptyNewEpisodes = result.Failure == Core.Net.FailureKind.Parse && summary.BrowseId == MusicRepository.NewEpisodesBrowse;
            if (emptyNewEpisodes) ErrorText.Text = L.T("エピソードがありません", "No episodes");
            ErrorText.Visibility = Visibility.Visible;
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
        PlayButton.IsEnabled = episodes.Count > 0;
        UpdateSave();
    }

    private void OnMoreDescription(object sender, RoutedEventArgs e)
    {
        var expanded = DescriptionText.MaxLines == 0;
        DescriptionText.MaxLines = expanded ? 3 : 0;
        MoreLink.Content = expanded ? L.T("すべて表示", "Show more") : L.T("一部のみ表示", "Show less");
    }

    private void UpdateSave()
    {
        SaveButton.Visibility = Ui.Show(page?.Saved is not null && AppServices.Client.IsLoggedIn);
        SaveButton.Content = page?.Saved == true ? L.T("保存済み", "Saved") : L.T("ライブラリに保存", "Save to library");
        SaveButton.IsEnabled = !saving;
        // 保存していなければ色付き、保存済みなら控えめに（アーティストの登録と同じ）
        SaveButton.Style = page?.Saved == true ? null : (Style)Application.Current.Resources["AccentButtonStyle"];
    }

    /// <summary>保存の切り替え。書けたら表示を変える（アーティストの登録と同じく楽観的に変えない）。</summary>
    private async void OnSave(object sender, RoutedEventArgs e)
    {
        if (page is not { Saved: { } saved } current || saving) return;
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

    private void PlayFrom(int index)
    {
        if (page is null || episodes.Count == 0) return;
        AppServices.Player.PlayList(episodes.Select(x => x.Song).ToList(), index, page.PlaylistId);
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
        loadingMore = true;
        var result = await AppServices.Music.PodcastEpisodesAsync(token, new ArtistRef(current.Title, null), current.BrowseId);
        loadingMore = false;
        if (page != current || !result.IsOk) return;
        continuation = result.Value!.Continuation != token ? result.Value.Continuation : null;
        foreach (var episode in result.Value.Items.Where(x => episodes.All(y => y.Song.VideoId != x.Song.VideoId))) episodes.Add(episode);
    }
}
