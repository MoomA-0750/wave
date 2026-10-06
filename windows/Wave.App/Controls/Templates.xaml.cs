using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;

namespace Wave.App.Controls;

public sealed partial class Templates : ResourceDictionary
{
    public Templates() => InitializeComponent();

    public static GridLength AlbumColumn(bool show) => show ? new GridLength(2, GridUnitType.Star) : new GridLength(0);

    public static Brush TitleBrush(bool current) =>
        (Brush)Application.Current.Resources[current ? "AccentTextFillColorPrimaryBrush" : "TextFillColorPrimaryBrush"];

    private void OnMoreClick(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: TrackRow row } element) row.BuildMenu().ShowAt(element);
    }

    private void OnRowRightTapped(object sender, RightTappedRoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: TrackRow row } element)
        {
            row.BuildMenu().ShowAt(element, e.GetPosition(element));
            e.Handled = true;
        }
    }
}
