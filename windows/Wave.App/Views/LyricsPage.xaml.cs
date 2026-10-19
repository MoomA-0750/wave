using System.ComponentModel;
using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Services;
using Wave.Core.Lyrics;

namespace Wave.App.Views;

/// <summary>
/// 歌詞の1行（時刻付き）。同じ時刻の行（原文に続く読み・訳）は、前の行に添える小さな行（IsSub）として出す。
/// かたまりの間は広く、かたまりの中は詰めて、原文と読み・訳の対応を分かりやすくする。
/// </summary>
public sealed partial class LyricRow(LyricLine line, bool isSub) : ObservableObject
{
    public LyricLine Line { get; } = line;
    public bool IsSub { get; } = isSub;
    public string Text => string.IsNullOrWhiteSpace(Line.Text) ? "♪" : Line.Text;
    public double FontSize => IsSub ? 21 : 30;
    public Windows.UI.Text.FontWeight Weight => IsSub ? Microsoft.UI.Text.FontWeights.SemiBold : Microsoft.UI.Text.FontWeights.Bold;
    public Thickness Gap => new(0, IsSub ? 2 : 22, 0, 0);
    [ObservableProperty] private double opacity = 0.4;
    /// <summary>歌っている行は少し大きく（切り替わりはアニメーションで）。</summary>
    [ObservableProperty] private System.Numerics.Vector3 scale = new(0.96f, 0.96f, 1);

    /// <summary>折り返した行の間も詰まりすぎないように。</summary>
    public double LineHeightOf(double size) => Math.Round(size * 1.3);

    public static List<LyricRow> From(IReadOnlyList<LyricLine> lines) =>
        lines.Select((l, i) => new LyricRow(l, i > 0 && lines[i - 1].StartMs == l.StartMs)).ToList();
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
        RetryButton.Visibility = Visibility.Collapsed;
        SourceText.Text = "";
        if (Player.Current is not { } song)
        {
            Empty(L.T("曲を再生すると、ここに歌詞が出ます", "Play a song to see its lyrics here"));
            return;
        }
        if (song.IsLive)
        {
            Empty(L.T("ライブ配信には歌詞がありません", "Live streams don't have lyrics"));
            return;
        }
        if (await Artwork.DominantColorAsync(song.ThumbnailUrl) is { } color && Player.Current?.VideoId == song.VideoId)
            Backdrop.Fill = Ui.Fade(color, 170, 40);
        switch (Player.Lyrics)
        {
            case null:
                Empty(L.T("歌詞を探しています…", "Looking for lyrics…"));
                break;
            case LyricsService.Result.Found { Lyrics: SongLyrics.Synced synced }:
                rows = LyricRow.From(synced.Lines);
                Lines.ItemsSource = rows;
                Lines.Visibility = Visibility.Visible;
                SourceText.Text = L.T("歌詞の提供: ", "Lyrics provided by ") + synced.Source;
                Follow();
                // 開いた直後は行の部品がまだ無く送れないので、並べ終わってからもう一度合わせる
                DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () =>
                {
                    if (!ReferenceEquals(Lines.ItemsSource, rows)) return;
                    current = -2;
                    Follow();
                });
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
                RetryButton.Visibility = Visibility.Visible;
                break;
        }
    }

    public static string RetryText => L.T("もう一度", "Retry");

    private void OnRetry(object sender, RoutedEventArgs e) => Player.ReloadLyrics();

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
        for (var i = 0; i < rows.Count; i++)
        {
            var now = index >= 0 && i >= index && i <= last;
            rows[i].Opacity = now ? 1.0 : i < index ? 0.55 : 0.4;
            rows[i].Scale = now ? System.Numerics.Vector3.One : new(0.96f, 0.96f, 1);
        }
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
