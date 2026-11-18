using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Foundation;

namespace Wave.App.Controls;

/// <summary>子を横に並べ、入りきらなければ次の行へ折り返す（WinUI に標準の WrapPanel が無いため）。</summary>
public sealed partial class WrapPanel : Panel
{
    public double Spacing { get; set; }
    public double RowSpacing { get; set; }

    protected override Size MeasureOverride(Size available) => Arrange(available.Width, null);

    protected override Size ArrangeOverride(Size final)
    {
        Arrange(final.Width, final);
        return final;
    }

    /// <summary>並べる（arrange が null なら測るだけ）。使う大きさを返す。</summary>
    private Size Arrange(double width, Size? arrange)
    {
        double x = 0, y = 0, row = 0, widest = 0;
        foreach (var child in Children)
        {
            if (arrange is null) child.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
            var size = child.DesiredSize;
            if (x > 0 && x + size.Width > width)
            {
                x = 0;
                y += row + RowSpacing;
                row = 0;
            }
            if (arrange is not null) child.Arrange(new Rect(x, y, size.Width, size.Height));
            x += size.Width + Spacing;
            row = Math.Max(row, size.Height);
            widest = Math.Max(widest, x - Spacing);
        }
        return new Size(double.IsInfinity(width) ? widest : Math.Max(0, Math.Min(widest, width)), y + row);
    }
}
