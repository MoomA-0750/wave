using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Wave.Core.Music;

namespace Wave.App.Controls;

public sealed partial class ShelfView : UserControl
{
    public static readonly DependencyProperty TitleProperty = DependencyProperty.Register(nameof(Title), typeof(string), typeof(ShelfView), new PropertyMetadata(""));
    public static readonly DependencyProperty StraplineProperty = DependencyProperty.Register(nameof(Strapline), typeof(string), typeof(ShelfView), new PropertyMetadata(null));
    public static readonly DependencyProperty ItemsProperty = DependencyProperty.Register(nameof(Items), typeof(IReadOnlyList<HomeItem>), typeof(ShelfView), new PropertyMetadata(null));

    public string Title
    {
        get => (string)GetValue(TitleProperty);
        set => SetValue(TitleProperty, value);
    }

    public string? Strapline
    {
        get => (string?)GetValue(StraplineProperty);
        set => SetValue(StraplineProperty, value);
    }

    public IReadOnlyList<HomeItem>? Items
    {
        get => (IReadOnlyList<HomeItem>?)GetValue(ItemsProperty);
        set => SetValue(ItemsProperty, value);
    }

    public ShelfView() => InitializeComponent();

    private void OnLeft(object sender, RoutedEventArgs e) => Scroller.ChangeView(Math.Max(0, Scroller.HorizontalOffset - Scroller.ViewportWidth * 0.8), null, null);

    private void OnRight(object sender, RoutedEventArgs e) => Scroller.ChangeView(Scroller.HorizontalOffset + Scroller.ViewportWidth * 0.8, null, null);

#if DEBUG
    public void DebugRight() => OnRight(this, new RoutedEventArgs());
#endif

    private void OnViewChanged(object? sender, ScrollViewerViewChangedEventArgs e) => UpdateArrows();

    // 並びの大きさが変わった直後は、ScrollViewer の送れる幅（ScrollableWidth）がまだ前の値なので、置き終えてから見る
    private void OnScrollerSizeChanged(object sender, SizeChangedEventArgs e) => DispatcherQueue.TryEnqueue(UpdateArrows);

    /// <summary>送るボタンは、その向きに続きがあるときだけ見せる。</summary>
    private void UpdateArrows()
    {
        var left = Scroller.HorizontalOffset > 1;
        var right = Scroller.HorizontalOffset < Scroller.ScrollableWidth - 1;
        LeftButton.Opacity = left ? 1 : 0;
        LeftButton.IsEnabled = left;
        RightButton.Opacity = right ? 1 : 0;
        RightButton.IsEnabled = right;
    }
}
