using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Controls;
using Wave.App.Services;

namespace Wave.App.Views;

/// <summary>再生履歴の区切り（「今日」など）。</summary>
public sealed class HistoryGroup(string title, IEnumerable<TrackRow> rows) : List<TrackRow>(rows)
{
    public string Title { get; } = title;
}

public sealed partial class HistoryPage : Page
{
    private List<TrackRow> rows = [];

    public HistoryPage() => InitializeComponent();

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        var result = await AppServices.Music.HistoryAsync();
        LoadingRing.IsActive = false;
        if (!result.IsOk)
        {
            ErrorText.Text = result.Failure == Core.Net.FailureKind.NotLoggedIn ? L.T("再生履歴を見るにはログインしてください", "Log in to see your listening history") : L.T("再生履歴を読み込めませんでした", "Couldn't load your listening history");
            ErrorText.Visibility = Visibility.Visible;
            return;
        }
        var n = 1;
        var groups = result.Value!.Sections.Select(s => new HistoryGroup(s.Title ?? "", s.Songs.Select(song => new TrackRow(n++, song) { Menu = r => SongMenu.Build(r.Song) }).ToList())).ToList();
        rows = groups.SelectMany(g => g).ToList();
        TrackRow.MarkCurrent(rows, AppServices.Player.Current?.VideoId);
        Tracks.ItemsSource = new CollectionViewSource { Source = groups, IsSourceGrouped = true }.View;
    }

    private void OnDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if ((e.OriginalSource as FrameworkElement)?.DataContext is TrackRow row) AppServices.Player.PlaySong(row.Song);
    }
}
