using System.ComponentModel;
using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Wave.App.Services;
using Wave.Core.Music;
using Windows.Media.Playback;

namespace Wave.App;

/// <summary>映像の小窓（PiP）。元の窓とは別の、最前面でリサイズできる窓。ホバーすると曲名・評価・前/再生/次・シークバー・「wave に戻す」を重ねる。</summary>
public sealed class PipWindow : Window
{
    private readonly MediaPlayerElement video = new() { AreTransportControlsEnabled = false, Stretch = Stretch.Uniform, IsHitTestVisible = false };
    private readonly Grid overlay = new() { Opacity = 0 };
    private readonly TextBlock title = new() { Foreground = new SolidColorBrush(Colors.White), TextTrimming = TextTrimming.CharacterEllipsis, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold };
    private readonly TextBlock artist = new() { Foreground = new SolidColorBrush(Colors.White), Opacity = 0.75, FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly FontIcon likeIcon = new() { FontSize = 16 };
    private readonly FontIcon playIcon = new() { FontSize = 16 };
    private readonly Slider slider = new() { Minimum = 0, IsThumbToolTipEnabled = false, VerticalAlignment = VerticalAlignment.Center };
    private readonly Button likeButton = new();
    private readonly Action onLike;
    private bool byUser;
    private bool updating;

    /// <summary>×で閉じたときも呼ばれる（元の窓へ戻す）。</summary>
    public event Action? BackRequested;

    private PlayerService Player => AppServices.Player;

    public PipWindow(MediaPlayer? source, Action onLike)
    {
        this.onLike = onLike;
        Title = "wave";
        video.SetMediaPlayer(source);

        var root = new Grid { Background = new SolidColorBrush(Colors.Black) };
        root.Children.Add(video);
        root.Children.Add(BuildOverlay());
        // 透明な層でホバーを受ける（映像は当たり判定が無い）
        root.Background = new SolidColorBrush(Colors.Black);
        root.PointerEntered += (_, _) => { overlay.Opacity = 1; };
        root.PointerExited += (_, _) => overlay.Opacity = 0;
        Content = root;

        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.IsAlwaysOnTop = true;
            presenter.IsResizable = true;
            presenter.IsMaximizable = false;
        }
        root.Loaded += (_, _) =>
        {
            var scale = root.XamlRoot?.RasterizationScale ?? 1;
            AppWindow.Resize(new((int)(480 * scale), (int)(300 * scale)));
        };

        Player.PropertyChanged += OnPlayerChanged;
        Render();
        Closed += (_, _) =>
        {
            Player.PropertyChanged -= OnPlayerChanged;
            video.SetMediaPlayer(null);
            BackRequested?.Invoke();
        };
    }

    public void SetSource(MediaPlayer? source) => video.SetMediaPlayer(source);

    private UIElement BuildOverlay()
    {
        overlay.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        overlay.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        overlay.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        overlay.OpacityTransition = new ScalarTransition();

        // 上: 曲名・アーティスト・評価・wave に戻す
        var top = new Grid { Background = new SolidColorBrush(Windows.UI.Color.FromArgb(0xB3, 0, 0, 0)), Padding = new Thickness(12, 8, 8, 8), ColumnSpacing = 4 };
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var names = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        names.Children.Add(title);
        names.Children.Add(artist);
        top.Children.Add(names);
        likeButton.Content = likeIcon;
        likeButton.Foreground = new SolidColorBrush(Colors.White);
        likeButton.Click += (_, _) => onLike();
        Grid.SetColumn(likeButton, 1);
        top.Children.Add(likeButton);
        var back = new Button { Content = new FontIcon { Glyph = "", FontSize = 16 }, Foreground = new SolidColorBrush(Colors.White) };
        ToolTipService.SetToolTip(back, L.T("wave に戻す", "Back to wave"));
        back.Click += (_, _) => Close();
        Grid.SetColumn(back, 2);
        top.Children.Add(back);
        overlay.Children.Add(top);

        // 下: 前・再生/一時停止・次・シークバー
        var bottom = new Grid { Background = new SolidColorBrush(Windows.UI.Color.FromArgb(0xB3, 0, 0, 0)), Padding = new Thickness(8, 4, 12, 4), ColumnSpacing = 4 };
        for (var i = 0; i < 3; i++) bottom.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        bottom.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        Button Make(string glyph, Action act, FontIcon? icon = null)
        {
            var button = new Button { Content = icon ?? new FontIcon { Glyph = glyph, FontSize = 16 }, Foreground = new SolidColorBrush(Colors.White) };
            button.Click += (_, _) => act();
            return button;
        }
        var prev = Make("", () => Player.Previous());
        var play = Make("", () => Player.TogglePlay(), playIcon);
        var next = Make("", () => Player.Next());
        Grid.SetColumn(play, 1);
        Grid.SetColumn(next, 2);
        Grid.SetColumn(slider, 3);
        bottom.Children.Add(prev);
        bottom.Children.Add(play);
        bottom.Children.Add(next);
        bottom.Children.Add(slider);
        Grid.SetRow(bottom, 2);
        overlay.Children.Add(bottom);

        slider.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler((_, _) => byUser = true), handledEventsToo: true);
        slider.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler((_, _) => byUser = false), handledEventsToo: true);
        slider.AddHandler(UIElement.PointerCaptureLostEvent, new PointerEventHandler((_, _) => byUser = false), handledEventsToo: true);
        slider.ValueChanged += (_, e) =>
        {
            if (!updating && byUser && Math.Abs(e.NewValue - Player.Position) > 1.5) Player.Seek(e.NewValue);
        };
        return overlay;
    }

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e) => DispatcherQueue.TryEnqueue(Render);

    private void Render()
    {
        var song = Player.Current;
        title.Text = song?.Title ?? "";
        artist.Text = song?.ArtistsText ?? "";
        Title = song is null ? "wave" : $"{song.Title} • {song.ArtistsText}";
        likeButton.Visibility = song is not null && !Feed.IsExternal(song.VideoId) ? Visibility.Visible : Visibility.Collapsed;
        var liked = Player.Like == LikeStatus.Like || Player.InPlaylist;
        likeIcon.Glyph = liked ? "" : "";
        likeIcon.Foreground = new SolidColorBrush(liked ? Windows.UI.Color.FromArgb(0xFF, 0x4C, 0xC2, 0xFF) : Colors.White);
        playIcon.Glyph = Player.IsPlaying ? "" : "";
        updating = true;
        slider.Maximum = Math.Max(1, Player.Duration);
        if (!byUser) slider.Value = Player.Position;
        updating = false;
    }
}
