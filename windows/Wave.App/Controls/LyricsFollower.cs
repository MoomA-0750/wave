using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Wave.Core.Lyrics;

namespace Wave.App.Controls;

/// <summary>
/// 再生位置に合わせて送る歌詞（Spotify の再生中の画面と同じく、歌っている行を明るく、前後の行を薄く）。
/// 時刻付きの歌詞では全文を並べ、歌っているかたまりの1つ前が上端に来るように滑らかに送る。
/// 右の欄の小さな歌詞と、全画面の大きな歌詞で使う（字の大きさだけ違う）。
/// </summary>
public sealed class LyricsFollower(ScrollViewer scroller, StackPanel panel, double fontSize, int plainLines = 12)
{
    private object? source;
    private int index = -2;
    private List<(TextBlock Block, LyricLine Line)> rows = [];

    /// <summary>歌詞（変わっていなければ作り直さない）と再生位置を反映する。</summary>
    public void Update(SongLyrics? lyrics, long positionMs)
    {
        if (!ReferenceEquals(lyrics, source))
        {
            source = lyrics;
            index = -2;
            Build(lyrics);
        }
        if (lyrics is not SongLyrics.Synced synced) return;
        var last = LyricsTiming.CurrentIndex(synced.Lines, positionMs);
        // 同じ時刻の行（原文と読み・訳が並ぶ歌詞）は、まとめて今の行とする
        var i = last < 0 ? last : rows.FindIndex(r => r.Line.StartMs == synced.Lines[last].StartMs);
        if (i == index) return;
        index = i;
        for (var k = 0; k < rows.Count; k++)
            rows[k].Block.Opacity = i >= 0 && k >= i && k <= last ? 1 : k < i ? 0.5 : 0.4;
        var anchor = i <= 0 ? 0 : rows.FindIndex(r => r.Line.StartMs == synced.Lines[Math.Max(0, i - 1)].StartMs);
        panel.UpdateLayout();
        var y = anchor <= 0 ? 0 : rows[anchor].Block.TransformToVisual(panel).TransformPoint(new Windows.Foundation.Point(0, 0)).Y;
        scroller.ChangeView(null, y, null, disableAnimation: false);
    }

    private void Build(SongLyrics? lyrics)
    {
        panel.Children.Clear();
        rows = [];
        scroller.ChangeView(null, 0, null, disableAnimation: true);
        var primary = (Brush)Application.Current.Resources["TextFillColorPrimaryBrush"];
        var sub = fontSize * 0.8;
        switch (lyrics)
        {
            case SongLyrics.Synced synced:
                for (var k = 0; k < synced.Lines.Count; k++)
                {
                    var line = synced.Lines[k];
                    // 原文に続く読み・訳は、小さく原文の直下に添える。かたまりの間は空ける
                    var isSub = k > 0 && synced.Lines[k - 1].StartMs == line.StartMs;
                    var block = new TextBlock
                    {
                        Text = string.IsNullOrWhiteSpace(line.Text) ? "♪" : line.Text,
                        TextWrapping = TextWrapping.Wrap,
                        FontSize = isSub ? sub : fontSize,
                        LineHeight = (isSub ? sub : fontSize) * 1.375,
                        FontWeight = isSub ? Microsoft.UI.Text.FontWeights.Normal : Microsoft.UI.Text.FontWeights.SemiBold,
                        Foreground = primary,
                        Opacity = 0.4,
                        Margin = new Thickness(0, k == 0 ? 0 : isSub ? 1 : fontSize * 0.6, 0, 0),
                        OpacityTransition = new ScalarTransition { Duration = TimeSpan.FromMilliseconds(350) },
                    };
                    panel.Children.Add(block);
                    rows.Add((block, line));
                }
                break;
            case SongLyrics.Plain plain:
                foreach (var text in plain.Text.Split('\n').Take(plainLines))
                    panel.Children.Add(new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, FontSize = fontSize, LineHeight = fontSize * 1.375, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, Foreground = primary, Margin = new Thickness(0, 0, 0, 6) });
                break;
        }
    }
}
