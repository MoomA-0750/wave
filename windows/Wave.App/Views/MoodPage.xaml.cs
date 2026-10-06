using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Services;
using Wave.Core.Music;

namespace Wave.App.Views;

/// <summary>気分・ジャンルの1つのページ（棚の並び）。</summary>
public sealed partial class MoodPage : Page
{
    public MoodPage() => InitializeComponent();

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        var mood = (MoodCategory)e.Parameter;
        TitleText.Text = mood.Title;
        var result = await AppServices.Music.MoodCategoryAsync(mood.Params);
        LoadingRing.IsActive = false;
        if (result.IsOk) Shelves.ItemsSource = result.Value!.Shelves;
    }
}
