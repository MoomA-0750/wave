using System.ComponentModel;
using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Services;
using Wave.Core.Lyrics;

namespace Wave.App.Views;

/// <summary>歌詞の1行（時刻付き）。</summary>
public sealed partial class LyricRow(LyricLine line) : ObservableObject
{
    public LyricLine Line { get; } = line;
    public string Text => string.IsNullOrWhiteSpace(Line.Text) ? "♪" : Line.Text;
    [ObservableProperty] private double opacity = 0.45;
}

public sealed partial class LyricsPage : Page
{
    private List<LyricRow> rows = [];
    private int current = -2;

    public LyricsPage() => InitializeComponent();

    private PlayerService Player => AppServices.Player;

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        Player.PropertyChanged += OnPlayerChanged;
        Show();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e) => Player.PropertyChanged -= OnPlayerChanged;

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        switch (e.PropertyName)
        {
            case nameof(PlayerService.Lyrics):
            case nameof(PlayerService.Current):
                Show();
                break;
            case nameof(PlayerService.Position):
                Follow();
                break;
        }
    }

    private async void Show()
    {
        current = -2;
        rows = [];
        Lines.ItemsSource = null;
        PlainView.Visibility = Visibility.Collapsed;
        Lines.Visibility = Visibility.Collapsed;
        EmptyPanel.Visibility = Visibility.Collapsed;
        SourceText.Text = "";
        if (Player.Current is not { } song)
        {
            Empty(L.T("曲を再生すると、ここに歌詞が出ます", "Play a song to see its lyrics here"));
            return;
        }
        if (await Artwork.DominantColorAsync(song.ThumbnailUrl) is { } color && Player.Current?.VideoId == song.VideoId)
        {
            var top = color;
            top.A = 170;
            var bottom = color;
            bottom.A = 40;
            Backdrop.Fill = new LinearGradientBrush([new GradientStop { Color = top, Offset = 0 }, new GradientStop { Color = bottom, Offset = 1 }], 90);
        }
        switch (Player.Lyrics)
        {
            case null:
                Empty(L.T("歌詞を探しています…", "Looking for lyrics…"));
                break;
            case LyricsService.Result.Found { Lyrics: SongLyrics.Synced synced }:
                rows = synced.Lines.Select(l => new LyricRow(l)).ToList();
                Lines.ItemsSource = rows;
                Lines.Visibility = Visibility.Visible;
                SourceText.Text = L.T("歌詞の提供: ", "Lyrics provided by ") + synced.Source;
                Follow();
                break;
            case LyricsService.Result.Found { Lyrics: SongLyrics.Plain plain }:
                PlainText.Text = plain.Text;
                PlainView.Visibility = Visibility.Visible;
                SourceText.Text = plain.Source ?? "";
                break;
            case LyricsService.Result.None:
                Empty(L.T("この曲の歌詞は見つかりませんでした", "No lyrics found for this song"));
                break;
            default:
                Empty(L.T("歌詞を読み込めませんでした", "Couldn't load lyrics"));
                break;
        }
    }

    private void Empty(string text)
    {
        EmptyText.Text = text;
        EmptyPanel.Visibility = Visibility.Visible;
    }

    /// <summary>再生位置に合わせて、歌っている行を明るくして真ん中に寄せる。</summary>
    private void Follow()
    {
        if (rows.Count == 0) return;
        var last = LyricsTiming.CurrentIndex(rows.Select(r => r.Line).ToList(), (long)(Player.Position * 1000));
        // 同じ時刻の行（原文と読み・訳が並ぶ歌詞）は、まとめて今の行とする（Android と同じ）
        var index = last < 0 ? last : rows.FindIndex(r => r.Line.StartMs == rows[last].Line.StartMs);
        if (index == current) return;
        current = index;
        for (var i = 0; i < rows.Count; i++) rows[i].Opacity = index >= 0 && i >= index && i <= last ? 1.0 : i < index ? 0.6 : 0.4;
        if (index < 0) return;
        if (Lines.ContainerFromIndex(index) is FrameworkElement item && FindScroller(Lines) is { } scroller)
        {
            var y = item.TransformToVisual(scroller.Content as UIElement ?? scroller).TransformPoint(new Windows.Foundation.Point(0, 0)).Y;
            scroller.ChangeView(null, Math.Max(0, y - scroller.ViewportHeight * 0.35), null);
        }
        else Lines.ScrollIntoView(rows[index], ScrollIntoViewAlignment.Leading);
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

    private void OnLineClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is LyricRow row) Player.Seek(row.Line.StartMs / 1000.0);
    }
}
