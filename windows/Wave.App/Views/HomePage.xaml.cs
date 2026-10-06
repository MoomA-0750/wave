using System.Collections.ObjectModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Wave.App.Services;
using Wave.Core.Music;

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
        // 最初は棚が8つ以上になるまで続きを読む（Android 版と同じ）
        for (var page = 0; page < 4 && id == requestId; page++)
        {
            if (!await LoadPageAsync(id)) break;
            if (shelves.Count >= 8 || continuation is null) break;
        }
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
            var result = await AppServices.Music.HomeAsync(continuation, continuation is null ? selected?.Params : null);
            if (id != requestId) return false;
            if (!result.IsOk)
            {
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
        if (sender is FrameworkElement { Tag: HomeItem item }) Nav.Open(item);
    }

    private void OnRetry(object sender, RoutedEventArgs e) => _ = ReloadAsync();
}
