using System.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Controls;
using Wave.App.Services;
using Wave.Core.Music;

namespace Wave.App.Views;

public sealed partial class ArtistPage : Page
{
    private string browseId = "";
    private Core.Music.ArtistPage? artist;
    private List<TrackRow> rows = [];

    public ArtistPage() => InitializeComponent();

    private PlayerService Player => AppServices.Player;

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        browseId = (string)e.Parameter;
        Player.PropertyChanged += OnPlayerChanged;
        shufflePlay = false;
        UpdateShuffleButton();
        var result = await AppServices.Music.ArtistAsync(browseId);
        LoadingRing.IsActive = false;
        if (!result.IsOk)
        {
            ErrorText.Visibility = Visibility.Visible;
            return;
        }
        artist = result.Value!;
        NameText.Text = artist.Name;
        Hero.Source = Ui.Img(artist.ThumbnailUrl, 1080);
        var n = 1;
        rows = artist.TopSongs.Select(s => new TrackRow(n++, s) { ShowAlbum = true, Menu = r => SongMenu.Build(r.Song) }).ToList();
        TrackRow.MarkCurrent(rows, Player.Current?.VideoId);
        TopSongs.ItemsSource = rows;
        PlayButton.IsEnabled = rows.Count > 0;
        AllSongs.Visibility = Ui.Show(artist.AllSongsPlaylistId is not null);
        var shelves = artist.AlbumShelves.Select(s => new HomeShelf(s.Title, null, s.Albums.Select(a => (HomeItem)new HomeItem.AlbumItem(a)).ToList())).ToList();
        // 最近の新作（登録したアーティストの見張りで見つけたもの）を先頭に（Android と同じ）
        var releases = AppServices.Releases.Recent.Where(r => r.ArtistId == browseId).Select(HomePage.ReleaseItem).ToList();
        if (releases.Count > 0) shelves.Insert(0, new HomeShelf(L.T("新作", "New releases"), null, releases));
        Shelves.ItemsSource = shelves;
        UpdatePlayIcon();
        UpdateSubscribe();
        _ = ShowLivePostsAsync(artist);
        await ShowLikedAsync(artist);
    }

    /// <summary>チャンネルの投稿のうち、ライブ・ツアーの告知らしいものを並べる（押すと YouTube の投稿を開く）。</summary>
    private async Task ShowLivePostsAsync(Core.Music.ArtistPage page)
    {
        var channelId = page.ChannelId ?? (browseId.StartsWith("UC") ? browseId : null);
        if (channelId is null) return;
        var result = await AppServices.Music.ArtistPostsAsync(channelId);
        if (artist != page) return;
        var posts = result.IsOk ? LiveNews.Of(result.Value!).Take(6).ToList() : [];
        AppPaths.Log($"artist posts ok={result.IsOk} posts={result.Value?.Count} live={posts.Count}");
        LivePosts.Children.Clear();
        foreach (var post in posts) LivePosts.Children.Add(LivePostCard(post));
        LivePanel.Visibility = Ui.Show(posts.Count > 0);
    }

    private static Button LivePostCard(ArtistPost post)
    {
        var body = new StackPanel { Spacing = 6 };
        if (post.ImageUrl is { } url) body.Children.Add(new Image { Source = Ui.Img(url, 520), Height = 140, Stretch = Microsoft.UI.Xaml.Media.Stretch.UniformToFill });
        body.Children.Add(new TextBlock { Text = post.Text, TextWrapping = TextWrapping.Wrap, MaxLines = 6, TextTrimming = TextTrimming.CharacterEllipsis });
        if (post.PublishedText is { } published) body.Children.Add(new TextBlock { Text = published, Style = (Style)Application.Current.Resources["SubtleTextStyle"] });
        var card = new Button
        {
            Content = body,
            Width = 260,
            VerticalAlignment = VerticalAlignment.Stretch,
            VerticalContentAlignment = VerticalAlignment.Top,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Padding = new Thickness(12),
            CornerRadius = new CornerRadius(8),
        };
        card.Click += async (_, _) => await Windows.System.Launcher.LaunchUriAsync(new Uri(post.Url));
        return card;
    }

    private bool subscribing;

    private void UpdateSubscribe()
    {
        SubscribeButton.Visibility = Ui.Show(artist?.ChannelId is not null && AppServices.Client.IsLoggedIn);
        SubscribeButton.Content = artist?.Subscribed == true ? L.T("登録済み", "Subscribed") : L.T("登録", "Subscribe");
        SubscribeButton.IsEnabled = !subscribing && artist?.Subscribed is not null;
        // 登録していなければ色付き（AccentButtonStyle）、登録済みなら控えめに
        SubscribeButton.Style = artist?.Subscribed == true ? null : (Style)Application.Current.Resources["AccentButtonStyle"];
    }

    /// <summary>登録の切り替え。書けたら表示を変える（Android と同じく楽観的に変えない）。</summary>
    private async void OnSubscribe(object sender, RoutedEventArgs e)
    {
        if (artist is not { ChannelId: { } channelId } page || subscribing) return;
        var target = page.Subscribed != true;
        subscribing = true;
        UpdateSubscribe();
        var result = await AppServices.Music.SetSubscribedAsync(channelId, target);
        subscribing = false;
        if (artist == page && result.IsOk) artist = page with { Subscribed = target };
        else if (!result.IsOk) Player.Message = L.T("登録を変更できませんでした", "Couldn't change your subscription");
        UpdateSubscribe();
    }

    /// <summary>このアーティストの、自分が高く評価した曲の数（ログインしていなければ出さない）。</summary>
    private async Task ShowLikedAsync(Core.Music.ArtistPage page)
    {
        if (!AppServices.Client.IsLoggedIn) return;
        LikedText.Text = L.T("確かめています…", "Checking…");
        LikedRow.Visibility = Visibility.Visible;
        LikedRow.IsEnabled = false;
        var (songs, complete) = await AppServices.Library.LikedSongsAsync();
        if (artist != page) return;
        var count = LibraryState.ByArtist(songs, browseId, page.Name).Count;
        LikedRow.Visibility = Ui.Show(count > 0);
        LikedRow.IsEnabled = true;
        LikedText.Text = complete ? L.T($"{page.Name} の {count} 曲", $"{count} {(count == 1 ? "song" : "songs")} by {page.Name}")
            : L.T($"{page.Name} の {count} 曲以上", $"{count}+ songs by {page.Name}");
    }

    private void OnLiked(object sender, RoutedEventArgs e)
    {
        if (artist is { } page) Nav.LikedSongsBy(browseId, page.Name);
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e) => Player.PropertyChanged -= OnPlayerChanged;

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PlayerService.Current)) TrackRow.MarkCurrent(rows, Player.Current?.VideoId);
        if (e.PropertyName is nameof(PlayerService.IsPlaying) or nameof(PlayerService.SourceId)) UpdatePlayIcon();
        if (e.PropertyName is nameof(PlayerService.Shuffle) or nameof(PlayerService.SourceId)) UpdateShuffleButton();
    }

    private bool IsPlayingHere => Player.SourceId == browseId && Player.Current is not null;

    private void UpdatePlayIcon() => PlayIcon.Glyph = IsPlayingHere && Player.IsPlaying ? "" : "";

    private void OnPlay(object sender, RoutedEventArgs e)
    {
        if (IsPlayingHere)
        {
            Player.TogglePlay();
            return;
        }
        Player.PlayList(rows.Select(r => r.Song).ToList(), 0, browseId, shufflePlay ? true : null);
    }

    /// <summary>
    /// 一覧の「シャッフル再生」の指定（Android と同じ）。この一覧を再生しているときは、キューのシャッフルの入り切りになる。
    /// </summary>
    private bool shufflePlay;

    private void OnShuffle(object sender, RoutedEventArgs e)
    {
        if (IsPlayingHere) Player.ToggleShuffle();
        else shufflePlay = !shufflePlay;
        UpdateShuffleButton();
    }

    private void UpdateShuffleButton() => ShuffleButton.IsChecked = IsPlayingHere ? Player.Shuffle : shufflePlay;

    private void OnDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if ((e.OriginalSource as FrameworkElement)?.DataContext is TrackRow row) Player.PlayList(rows.Select(r => r.Song).ToList(), rows.IndexOf(row), browseId);
    }

    private void OnAllSongs(object sender, RoutedEventArgs e)
    {
        if (artist?.AllSongsPlaylistId is { } id) Nav.Playlist(new PlaylistSummary(id, artist.Name + L.T(" のすべての曲", " — all songs"), null, artist.ThumbnailUrl));
    }
}
