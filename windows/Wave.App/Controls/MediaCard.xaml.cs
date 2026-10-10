using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Wave.Core.Music;

namespace Wave.App.Controls;

public sealed partial class MediaCard : UserControl
{
    public static readonly DependencyProperty ItemProperty =
        DependencyProperty.Register(nameof(Item), typeof(HomeItem), typeof(MediaCard), new PropertyMetadata(null));

    public HomeItem? Item
    {
        get => (HomeItem?)GetValue(ItemProperty);
        set => SetValue(ItemProperty, value);
    }

    public MediaCard() => InitializeComponent();

    public string Subtitle(HomeItem? item) => item switch
    {
        null => "",
        HomeItem.ArtistItem => L.T("アーティスト", "Artist"),
        _ => item.Subtitle ?? "",
    };

    private void OnEnter(object sender, PointerRoutedEventArgs e)
    {
        Root.Background = (Brush)Application.Current.Resources["SubtleFillColorSecondaryBrush"];
        PlayButton.Opacity = 1;
    }

    private void OnExit(object sender, PointerRoutedEventArgs e)
    {
        Root.Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        PlayButton.Opacity = 0;
    }

    private void OnTapped(object sender, TappedRoutedEventArgs e)
    {
        if (Item is { } item) Nav.OpenCard(item);
    }

    private void OnPlay(object sender, RoutedEventArgs e)
    {
        if (Item is { } item) Nav.Play(item);
    }

    private void OnRightTapped(object sender, RightTappedRoutedEventArgs e)
    {
        if (Item is HomeItem.SongItem s) SongMenu.Build(s.Song).ShowAt(this, e.GetPosition(this));
    }
}
