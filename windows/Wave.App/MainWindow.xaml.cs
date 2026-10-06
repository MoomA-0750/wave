using System.Collections.Specialized;
using System.ComponentModel;
using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Services;
using Wave.App.Views;
using Wave.Core.Lyrics;
using Wave.Core.Music;

namespace Wave.App;

/// <summary>マイライブラリの1行。</summary>
public sealed class LibraryEntry(string title, string? subtitle, string? thumbnailUrl, HomeItem? item, bool isLiked = false)
{
    public string Title { get; } = title;
    public string? Subtitle { get; } = subtitle;
    public string? ThumbnailUrl { get; } = thumbnailUrl;
    public HomeItem? Item { get; } = item;
    public bool IsLiked { get; } = isLiked;
    public bool IsArtist => Item is HomeItem.ArtistItem;
    public Brush CoverBackground => IsLiked
        ? new LinearGradientBrush([new GradientStop { Color = Windows.UI.Color.FromArgb(255, 0x45, 0x0A, 0xF5), Offset = 0 }, new GradientStop { Color = Windows.UI.Color.FromArgb(255, 0xC4, 0xEF, 0xD9), Offset = 1 }], 45)
        : (Brush)Application.Current.Resources["ControlFillColorSecondaryBrush"];
}

/// <summary>キューの1行。</summary>
public sealed class QueueEntry(int index, Song song, bool isCurrent)
{
    public int Index { get; } = index;
    public Song Song { get; } = song;
    public bool IsCurrent { get; } = isCurrent;
}

/// <summary>検索欄の候補（語・曲・アルバム・アーティスト）。</summary>
public sealed class Suggestion(string title, string? subtitle, string? thumbnailUrl, string? query, HomeItem? item)
{
    public string Title { get; } = title;
    public string? Subtitle { get; } = subtitle;
    public string? ThumbnailUrl { get; } = thumbnailUrl;
    public string? Query { get; } = query;
    public HomeItem? Item { get; } = item;
    public bool IsQuery => Item is null;
    public bool Round => Item is HomeItem.ArtistItem;
    public override string ToString() => Title;
}

public sealed partial class MainWindow : Window
{
    private string? libraryKind;
    private bool alphabetical;
    private CancellationTokenSource? suggestCancel;
    private readonly DispatcherTimer toastTimer = new() { Interval = TimeSpan.FromSeconds(3.5) };
    private bool rightPaneOpen;

    public PlayerService Player => AppServices.Player;

    public double CoverSize => 306;

    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "wave.ico"));
        Ui.ResizeLogical(this, 1480, 940);
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.PreferredMinimumWidth = 900;
            presenter.PreferredMinimumHeight = 600;
        }
        Nav.Frame = ContentFrame;
        rightPaneOpen = AppServices.Settings.RightPaneOpen;
        toastTimer.Tick += (_, _) =>
        {
            toastTimer.Stop();
            Toast.IsOpen = false;
        };
        Player.PropertyChanged += OnPlayerChanged;
        Player.Queue.CollectionChanged += OnQueueChanged;
        AppServices.Account.PropertyChanged += (_, _) => RenderAccount();
        AppServices.Library.Playlists.CollectionChanged += (_, _) => RenderLibrary();
        AppServices.Library.Artists.CollectionChanged += (_, _) => RenderLibrary();
        AppServices.Library.Albums.CollectionChanged += (_, _) => RenderLibrary();
        AppServices.Library.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(LibraryState.IsLoading)) LibraryLoading.Visibility = Ui.Show(AppServices.Library.IsLoading);
            if (e.PropertyName == nameof(LibraryState.LikedThumbnail)) RenderLibrary();
        };
        AppServices.Account.Changed += () => _ = AppServices.Library.RefreshAsync();
        SizeChanged += (_, _) => Layout();
        RenderChips();
        RenderAccount();
        RenderLibrary();
        RenderNowPlaying();
        Layout();
        Nav.Home();
        _ = AppServices.Library.RefreshAsync();
    }

    // ---- 画面の広さに合わせた並び ----

    private void Layout()
    {
        var width = AppWindow.Size.Width / (Content.XamlRoot?.RasterizationScale ?? 1);
        var showRight = rightPaneOpen && width >= 1180;
        RightColumn.Width = showRight ? new GridLength(340) : new GridLength(0);
        RightPane.Visibility = Ui.Show(showRight);
        LibraryColumn.Width = new GridLength(width < 1000 ? 240 : 300);
        PaneToggle.IsChecked = showRight && NowPlayingView.Visibility == Visibility.Visible;
        QueueToggle.IsChecked = showRight && QueueView.Visibility == Visibility.Visible;
        SearchBox.Width = width < 1100 ? 300 : 440;
    }

    private void SetRightPane(bool open, bool queue)
    {
        rightPaneOpen = open;
        AppServices.Settings.RightPaneOpen = open;
        AppServices.Settings.Save();
        NowPlayingView.Visibility = Ui.Hide(queue);
        QueueView.Visibility = Ui.Show(queue);
        if (queue) RenderQueue();
        Layout();
    }

    // ---- タイトルバー ----

    private void OnBack(TitleBar sender, object args)
    {
        if (ContentFrame.CanGoBack) ContentFrame.GoBack();
    }

    private void OnForward(object sender, RoutedEventArgs e)
    {
        if (ContentFrame.CanGoForward) ContentFrame.GoForward();
    }

    private void OnNavigated(object sender, NavigationEventArgs e)
    {
        AppTitleBar.IsBackButtonEnabled = ContentFrame.CanGoBack;
        ForwardButton.IsEnabled = ContentFrame.CanGoForward;
        LyricsToggle.IsChecked = e.SourcePageType == typeof(LyricsPage);
    }

    private void OnHome(object sender, RoutedEventArgs e) => Nav.Home();

    private void OnFocusSearch(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        SearchBox.Focus(FocusState.Keyboard);
        args.Handled = true;
    }

    private void OnSearchFocus(object sender, RoutedEventArgs e)
    {
        // 検索欄に入ったら「すべてをブラウズ」を出す（Spotify と同じ）
        if (ContentFrame.Content is not SearchPage && string.IsNullOrEmpty(SearchBox.Text)) Nav.Search();
    }

    private async void OnSearchTextChanged(AutoSuggestBox sender, AutoSuggestBoxTextChangedEventArgs args)
    {
        if (args.Reason != AutoSuggestionBoxTextChangeReason.UserInput) return;
        suggestCancel?.Cancel();
        var cancel = suggestCancel = new CancellationTokenSource();
        var text = sender.Text.Trim();
        if (text.Length == 0)
        {
            sender.ItemsSource = null;
            if (ContentFrame.Content is SearchPage page) page.Search(null);
            return;
        }
        try
        {
            await Task.Delay(150, cancel.Token);
            var result = await AppServices.Music.SuggestionsAsync(text, cancel.Token);
            if (cancel.IsCancellationRequested || !result.IsOk) return;
            var s = result.Value!;
            var list = new List<Suggestion>();
            list.AddRange(s.Queries.Take(5).Select(q => new Suggestion(q, null, null, q, null)));
            list.AddRange(s.Artists.Select(a => new Suggestion(a.Name, "アーティスト", a.ThumbnailUrl, null, new HomeItem.ArtistItem(a))));
            list.AddRange(s.Songs.Select(x => new Suggestion(x.Title, "曲 • " + x.ArtistsText, x.ThumbnailUrl, null, new HomeItem.SongItem(x))));
            list.AddRange(s.Albums.Select(a => new Suggestion(a.Title, a.Subtitle, a.ThumbnailUrl, null, new HomeItem.AlbumItem(a))));
            sender.ItemsSource = list;
        }
        catch (OperationCanceledException)
        {
        }
    }

    private void OnSearchSubmitted(AutoSuggestBox sender, AutoSuggestBoxQuerySubmittedEventArgs args)
    {
        if (args.ChosenSuggestion is Suggestion { Item: { } item })
        {
            AppServices.Settings.AddSearch(SearchHistoryEntry.Of(item));
            Nav.Open(item);
            return;
        }
        var query = args.ChosenSuggestion is Suggestion { Query: { } q } ? q : args.QueryText;
        SearchFor(query);
    }

    /// <summary>語で検索する（検索のページにいればその場で、いなければ開いて）。</summary>
    public void SearchFor(string query)
    {
        query = query.Trim();
        SearchBox.Text = query;
        SearchBox.ItemsSource = null;
        if (query.Length > 0) AppServices.Settings.AddSearch(SearchHistoryEntry.Query(query));
        if (ContentFrame.Content is SearchPage page) page.Search(query);
        else Nav.Search(query);
    }

    // ---- アカウント ----

    private void RenderAccount()
    {
        var account = AppServices.Account;
        AccountPicture.DisplayName = account.Name ?? "";
        AccountPicture.ProfilePicture = Ui.Small(account.PhotoUrl);
        CreateButton.IsEnabled = account.IsLoggedIn;
        LoggedOutPanel.Visibility = Ui.Hide(account.IsLoggedIn);
        LibraryList.Visibility = Ui.Show(account.IsLoggedIn);
    }

    private void OnAccountMenuOpening(object? sender, object e)
    {
        AccountMenu.Items.Clear();
        var account = AppServices.Account;
        if (account.IsLoggedIn)
        {
            AccountMenu.Items.Add(new MenuFlyoutItem { Text = account.Name ?? "ログイン中", IsEnabled = false });
            AccountMenu.Items.Add(new MenuFlyoutSeparator());
            AccountMenu.Items.Add(MenuItem("最近再生した曲", "", Nav.History));
        }
        else AccountMenu.Items.Add(MenuItem("ログイン", "", ShowLogin));
        AccountMenu.Items.Add(MenuItem("設定", "", Nav.Settings));
    }

    private static MenuFlyoutItem MenuItem(string text, string glyph, Action action)
    {
        var item = new MenuFlyoutItem { Text = text, Icon = new FontIcon { Glyph = glyph } };
        item.Click += (_, _) => action();
        return item;
    }

    private LoginWindow? login;

    public void ShowLogin()
    {
        if (login is not null)
        {
            login.Activate();
            return;
        }
        login = new LoginWindow();
        login.Finished += ok =>
        {
            login = null;
            if (ok) ShowToast("ログインしました");
        };
        login.Activate();
    }

    private void OnLogin(object sender, RoutedEventArgs e) => ShowLogin();

    // ---- マイライブラリ ----

    private void RenderChips()
    {
        FilterChips.Children.Clear();
        foreach (var (kind, title) in new[] { ("playlist", "プレイリスト"), ("artist", "アーティスト"), ("album", "アルバム") })
        {
            var chip = new ToggleButton { Content = title, IsChecked = libraryKind == kind, CornerRadius = new CornerRadius(14), Padding = new Thickness(12, 4, 12, 5) };
            chip.Click += (_, _) =>
            {
                libraryKind = libraryKind == kind ? null : kind;
                RenderChips();
                RenderLibrary();
            };
            FilterChips.Children.Add(chip);
        }
    }

    private void RenderLibrary()
    {
        var lib = AppServices.Library;
        var filter = LibraryFilter.Text.Trim();
        var entries = new List<LibraryEntry>();
        if (libraryKind is null or "playlist")
        {
            entries.AddRange(lib.Playlists.Select(p => new LibraryEntry(p.Title, "プレイリスト" + (string.IsNullOrEmpty(p.Subtitle) ? "" : " • " + p.Subtitle), p.ThumbnailUrl, new HomeItem.PlaylistItem(p))));
        }
        if (libraryKind is null or "artist") entries.AddRange(lib.Artists.Select(a => new LibraryEntry(a.Name, "アーティスト", a.ThumbnailUrl, new HomeItem.ArtistItem(a))));
        if (libraryKind is null or "album") entries.AddRange(lib.Albums.Select(a => new LibraryEntry(a.Title, "アルバム" + (string.IsNullOrEmpty(a.Subtitle) ? "" : " • " + a.Subtitle), a.ThumbnailUrl, new HomeItem.AlbumItem(a))));
        if (filter.Length > 0) entries = entries.Where(e => e.Title.Contains(filter, StringComparison.CurrentCultureIgnoreCase)).ToList();
        if (alphabetical) entries = entries.OrderBy(e => e.Title, StringComparer.CurrentCultureIgnoreCase).ToList();
        // 高く評価した曲はいつも先頭（Spotify の「お気に入りの曲」と同じ）
        if (libraryKind is null or "playlist" && (filter.Length == 0 || "高く評価した曲".Contains(filter)))
            entries.Insert(0, new LibraryEntry("高く評価した曲", "プレイリスト • 自動", lib.LikedThumbnail, null, isLiked: true));
        LibraryList.ItemsSource = entries;
    }

    private void OnLibraryFilterChanged(object sender, TextChangedEventArgs e) => RenderLibrary();

    private void OnSort(object sender, RoutedEventArgs e)
    {
        alphabetical = (sender as FrameworkElement)?.Tag as string == "alpha";
        SortButton.Content = alphabetical ? "アルファベット順" : "最近";
        RenderLibrary();
    }

    private void OnLibraryClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not LibraryEntry entry) return;
        if (entry.IsLiked) Nav.LikedSongs();
        else if (entry.Item is { } item) Nav.Open(item);
    }

    private void OnLibraryRightTapped(object sender, RightTappedRoutedEventArgs e)
    {
        if (sender is not FrameworkElement { DataContext: LibraryEntry entry } element) return;
        var menu = new MenuFlyout();
        if (entry.Item is { } item) menu.Items.Add(MenuItem("再生", "", () => Nav.Play(item)));
        else if (entry.IsLiked) menu.Items.Add(MenuItem("再生", "", () => Nav.Play(new HomeItem.PlaylistItem(new PlaylistSummary(MusicRepository.LikedSongs, entry.Title, null, null)))));
        menu.ShowAt(element, e.GetPosition(element));
    }

    private void OnCreatePlaylist(object sender, RoutedEventArgs e) => _ = Dialogs.CreatePlaylistAsync();

    // ---- 再生中の曲・キュー ----

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        switch (e.PropertyName)
        {
            case nameof(PlayerService.Current):
            case nameof(PlayerService.Index):
                RenderNowPlaying();
                if (QueueView.Visibility == Visibility.Visible) RenderQueue();
                Title = Player.Current is { } song ? $"{song.Title} • {song.ArtistsText}" : "wave";
                break;
            case nameof(PlayerService.Lyrics):
            case nameof(PlayerService.Position):
                RenderLyricsPreview();
                break;
            case nameof(PlayerService.Message):
                if (Player.Message is { } message)
                {
                    ShowToast(message);
                    Player.Message = null;
                }
                break;
        }
    }

    private void OnQueueChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        RenderNowPlaying();
        if (QueueView.Visibility == Visibility.Visible) RenderQueue();
    }

    private void RenderNowPlaying()
    {
        var next = Player.Index + 1 < Player.Queue.Count ? Player.Queue[Player.Index + 1] : null;
        NextSongPanel.Visibility = Ui.Show(next is not null);
        if (next is not null)
        {
            NextImage.Source = Ui.Small(next.ThumbnailUrl);
            NextTitle.Text = next.Title;
            NextArtist.Text = next.ArtistsText;
        }
        RenderLyricsPreview();
    }

    private int lyricsPreviewIndex = -2;

    private void RenderLyricsPreview()
    {
        switch (Player.Lyrics)
        {
            case LyricsService.Result.Found { Lyrics: SongLyrics.Synced synced }:
                var i = LyricsTiming.CurrentIndex(synced.Lines, (long)(Player.Position * 1000));
                if (i == lyricsPreviewIndex) return;
                lyricsPreviewIndex = i;
                LyricsPreview.Text = string.Join("\n", synced.Lines.Skip(Math.Max(0, i)).Take(4).Select(l => l.Text));
                break;
            case LyricsService.Result.Found { Lyrics: SongLyrics.Plain plain }:
                lyricsPreviewIndex = -2;
                LyricsPreview.Text = plain.Text;
                break;
            case null when Player.Current is not null:
                lyricsPreviewIndex = -2;
                LyricsPreview.Text = "歌詞を探しています…";
                break;
            default:
                lyricsPreviewIndex = -2;
                LyricsPreview.Text = Player.Current is null ? "" : "歌詞はありません";
                break;
        }
    }

    private void RenderQueue()
    {
        var entries = new List<QueueEntry>();
        for (var i = Math.Max(0, Player.Index); i < Player.Queue.Count; i++) entries.Add(new QueueEntry(i, Player.Queue[i], i == Player.Index));
        QueueList.ItemsSource = entries;
    }

    private void OnQueueClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is QueueEntry entry && !entry.IsCurrent) Player.PlayAt(entry.Index);
    }

    private void OnQueueRightTapped(object sender, RightTappedRoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: QueueEntry entry } element)
            SongMenu.Build(entry.Song, queueIndex: entry.IsCurrent ? null : entry.Index).ShowAt(element, e.GetPosition(element));
    }

    private void OnShowQueue(object sender, RoutedEventArgs e) => SetRightPane(true, queue: true);

    private void OnShowNowPlaying(object sender, RoutedEventArgs e) => SetRightPane(true, queue: false);

    private void OnOpenLyrics(object sender, RoutedEventArgs e) => Nav.Lyrics();

    private void OnArtistLink(object sender, RoutedEventArgs e)
    {
        if (Player.Current?.Artists.FirstOrDefault(a => a.BrowseId is not null)?.BrowseId is { } id) Nav.Artist(id);
    }

    private void OnAlbumLink(object sender, RoutedEventArgs e)
    {
        if (Player.Current?.Album?.BrowseId is { } id) Nav.Album(id);
    }

    private void OnArtTapped(object sender, TappedRoutedEventArgs e) => SetRightPane(!(rightPaneOpen && NowPlayingView.Visibility == Visibility.Visible), queue: false);

    // ---- 再生の操作 ----

    public string PlayGlyph(bool playing) => playing ? "" : "";

    public bool IsLiked(LikeStatus? like) => like == LikeStatus.Like;

    public string LikeGlyph(LikeStatus? like) => like == LikeStatus.Like ? "" : "";

    public bool IsRepeating(RepeatMode mode) => mode != RepeatMode.Off;

    public string RepeatGlyph(RepeatMode mode) => mode == RepeatMode.One ? "" : "";

    public Visibility HasSong(Song? song) => Ui.Show(song is not null);

    public double MaxOf(double duration) => Math.Max(1, duration);

    public string VolumeGlyph(double volume) => volume switch
    {
        <= 0 => "",
        < 0.34 => "",
        < 0.67 => "",
        _ => "",
    };

    private double volumeBeforeMute = 0.7;

    private void OnMute(object sender, RoutedEventArgs e)
    {
        if (Player.Volume > 0)
        {
            volumeBeforeMute = Player.Volume;
            Player.Volume = 0;
        }
        else Player.Volume = volumeBeforeMute;
    }

    private void OnPlayPause(object sender, RoutedEventArgs e) => Player.TogglePlay();

    private void OnNext(object sender, RoutedEventArgs e) => Player.Next();

    private void OnPrevious(object sender, RoutedEventArgs e) => Player.Previous();

    private void OnShuffle(object sender, RoutedEventArgs e) => Player.ToggleShuffle();

    private void OnRepeat(object sender, RoutedEventArgs e) => Player.CycleRepeat();

    private void OnLike(object sender, RoutedEventArgs e) => Player.ToggleLike();

    private void OnPositionChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        // 再生位置の更新による変化は小さい。大きく動いたときだけ、利用者が動かしたとみなして移る
        if (Math.Abs(e.NewValue - Player.Position) > 1.5) Player.Seek(e.NewValue);
    }

    private void OnLyricsToggle(object sender, RoutedEventArgs e)
    {
        if (ContentFrame.Content is LyricsPage && ContentFrame.CanGoBack) ContentFrame.GoBack();
        else Nav.Lyrics();
    }

    private void OnQueueToggle(object sender, RoutedEventArgs e)
    {
        var showing = rightPaneOpen && QueueView.Visibility == Visibility.Visible;
        SetRightPane(!showing, queue: !showing);
    }

    private void OnPaneToggle(object sender, RoutedEventArgs e)
    {
        var showing = rightPaneOpen && NowPlayingView.Visibility == Visibility.Visible;
        SetRightPane(!showing, queue: false);
    }

    /// <summary>スペースで再生・一時停止（文字の入力中は除く）。Ctrl+←/→ で前・次の曲。</summary>
    private void OnRootKeyDown(object sender, KeyRoutedEventArgs e)
    {
        var focused = FocusManager.GetFocusedElement(Content.XamlRoot);
        if (focused is TextBox or AutoSuggestBox or PasswordBox) return;
        var ctrl = Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(Windows.System.VirtualKey.Control).HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down);
        switch (e.Key)
        {
            case Windows.System.VirtualKey.Space:
                Player.TogglePlay();
                e.Handled = true;
                break;
            case Windows.System.VirtualKey.Right when ctrl:
                Player.Next();
                e.Handled = true;
                break;
            case Windows.System.VirtualKey.Left when ctrl:
                Player.Previous();
                e.Handled = true;
                break;
        }
    }

    public void ShowToast(string message)
    {
        Toast.Message = message;
        Toast.IsOpen = true;
        toastTimer.Stop();
        toastTimer.Start();
    }
}
