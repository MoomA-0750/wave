using System.Collections.ObjectModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Wave.App.Services;
using Wave.Core.Music;
using Wave.Core.Net;

namespace Wave.App.Views;

public sealed partial class HomePage : Page
{
    private readonly ObservableCollection<HomeShelf> shelves = [];
    private readonly HashSet<string> seen = [];
    private List<HomeChip> chips = [];
    private HomeChip? selected;
    private string? continuation;
    private bool loading;
    private int requestId;

    public HomePage()
    {
        InitializeComponent();
        Shelves.ItemsSource = shelves;
        AppServices.Account.Changed += () => DispatcherQueue.TryEnqueue(() => _ = ReloadAsync());
        AppServices.Releases.Changed += () => DispatcherQueue.TryEnqueue(RenderReleases);
        _ = ReloadAsync();
    }

    private async Task ReloadAsync()
    {
        var id = ++requestId;
        shelves.Clear();
        seen.Clear();
        continuation = null;
        FailurePanel.Visibility = Visibility.Collapsed;
        RenderChips();
        if (selected is null) _ = LoadRecentAsync(id);
        else RecentGrid.Visibility = Visibility.Collapsed;
        RenderReleases();
        // YouTube Music の新作（アルバム・シングル）は「すべて」のときだけ、ホームの棚と並べて読む（Android と同じ）
        var newReleases = selected is null ? AppServices.Music.NewReleasesAsync() : null;
        // 最初は棚が8つ以上になるまで続きを読む（Android 版と同じ）
        for (var page = 0; page < 4 && id == requestId; page++)
        {
            if (!await LoadPageAsync(id)) break;
            if (shelves.Count >= 8 || continuation is null) break;
        }
        if (newReleases is not null) await AddNewReleasesAsync(id, newReleases);
    }

    /// <summary>
    /// 新作の棚をホームの棚の先頭に置く（Android と同じ位置）。ホームに新作の棚が来ていれば出さない（同じものが2つ並ぶ）。
    /// ホームが読めなくても新作が読めたら、新作だけを出す。
    /// </summary>
    private async Task AddNewReleasesAsync(int id, Task<ApiResult<List<AlbumSummary>>> pending)
    {
        var result = await pending;
        if (id != requestId) return;
        var releases = result.IsOk ? result.Value! : [];
        var duplicated = HomeShelves.DuplicatesNewReleases(releases, shelves);
        AppPaths.Log($"home newReleases={releases.Count} failure={result.Failure} duplicated={duplicated}");
        if (duplicated || HomeShelves.NewReleasesShelf(L.T("新作", "New releases"), releases) is not { } shelf) return;
        shelves.Insert(0, shelf);
        FailurePanel.Visibility = Visibility.Collapsed;
    }

    private void RenderReleases()
    {
        var releases = selected is null ? ReleaseWatchState.Diversified(AppServices.Releases.Recent).Take(20).ToList() : [];
        NewReleasesShelf.Visibility = Ui.Show(releases.Count > 0);
        if (releases.Count == 0) return;
        NewReleasesShelf.Title = L.T("登録したアーティストの新作", "New from artists you subscribe to");
        NewReleasesShelf.Items = releases.Select(ReleaseItem).ToList();
    }

    /// <summary>新作をカードにする（アルバムは開く、動画は再生する。見つけて7日は補足に NEW を付ける）。</summary>
    public static HomeItem ReleaseItem(NewRelease r)
    {
        var fresh = !r.Seeded && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - r.FoundAtEpochMs < 7L * 24 * 60 * 60 * 1000;
        var subtitle = (fresh ? "NEW • " : "") + r.ArtistName;
        return r.AlbumBrowseId is { } album
            ? new HomeItem.AlbumItem(new AlbumSummary(album, r.Title, subtitle, r.ThumbnailUrl))
            : new HomeItem.SongItem(new Song(r.VideoId!, r.Title, [new ArtistRef(r.ArtistName, r.ArtistId)], null, null, r.ThumbnailUrl));
    }

    private async Task LoadRecentAsync(int id)
    {
        RecentGrid.Visibility = Visibility.Collapsed;
        if (!AppServices.Client.IsLoggedIn) return;
        var recent = await AppServices.Music.RecentActivityAsync();
        if (id != requestId || !recent.IsOk || recent.Value!.Count == 0) return;
        RecentGrid.ItemsSource = recent.Value.Take(8).ToList();
        RecentGrid.Visibility = Visibility.Visible;
    }

    private async Task<bool> LoadPageAsync(int id)
    {
        if (loading) return false;
        loading = true;
        LoadingRing.IsActive = true;
        try
        {
            var started = System.Diagnostics.Stopwatch.StartNew();
            var result = await AppServices.Music.HomeAsync(continuation, continuation is null ? selected?.Params : null);
            // 起動直後はほかの読み込みと重なって時間切れになることがあるので、通信の失敗は1回だけやり直す
            if (!result.IsOk && result.Failure == FailureKind.Network && id == requestId)
                result = await AppServices.Music.HomeAsync(continuation, continuation is null ? selected?.Params : null);
            if (id != requestId) return false;
            if (!result.IsOk)
            {
                AppPaths.Log($"home failed failure={result.Failure} status={result.Status} ms={started.ElapsedMilliseconds}");
                if (shelves.Count == 0) FailurePanel.Visibility = Visibility.Visible;
                return false;
            }
            var page = result.Value!;
            if (continuation is null && selected is null && page.Chips.Count > 0)
            {
                chips = page.Chips.ToList();
                RenderChips();
            }
            foreach (var shelf in page.Shelves)
            {
                if (seen.Add(shelf.Title + "|" + string.Join(",", shelf.Items.Take(3).Select(i => i.Key)))) shelves.Add(shelf);
            }
            continuation = page.Continuation;
            return true;
        }
        finally
        {
            loading = false;
            LoadingRing.IsActive = false;
        }
    }

    private void RenderChips()
    {
        Chips.Children.Clear();
        Chips.Children.Add(Chip(L.T("すべて", "All"), null));
        foreach (var chip in chips) Chips.Children.Add(Chip(chip.Title, chip));
    }

    private ToggleButton Chip(string title, HomeChip? chip)
    {
        var button = new ToggleButton { Content = title, IsChecked = Equals(selected, chip), CornerRadius = new CornerRadius(16), Padding = new Thickness(14, 5, 14, 6) };
        button.Click += (_, _) =>
        {
            selected = Equals(selected, chip) ? null : chip;
            _ = ReloadAsync();
        };
        return button;
    }

    private void OnScrolled(object? sender, ScrollViewerViewChangedEventArgs e)
    {
        // 下の端に近づいたら続きの棚を読む
        if (continuation is not null && !loading && Scroller.VerticalOffset > Scroller.ScrollableHeight - 600)
            _ = LoadPageAsync(requestId);
    }

    private void OnRecentClick(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: HomeItem item }) Nav.OpenCard(item);
    }

    private void OnRetry(object sender, RoutedEventArgs e) => _ = ReloadAsync();
}
