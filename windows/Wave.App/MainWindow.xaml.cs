using System.Collections.Specialized;
using System.ComponentModel;
using Microsoft.UI;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Services;
using Wave.App.Services.Connect;
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
    private static readonly TimeSpan ToastDuration = TimeSpan.FromSeconds(4);
    private Microsoft.UI.Xaml.Media.Animation.Storyboard? toastCountdown;
    private bool rightPaneOpen;

    public PlayerService Player => AppServices.Player;

    public double CoverSize => 306;

    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
        // 最大化したときに上に白い線が出ないよう、タイトルバーと窓の枠もアプリの明るさで描かせる
        AppWindow.TitleBar.PreferredTheme = TitleBarTheme.UseDefaultAppMode;
        UseDarkFrame(Application.Current.RequestedTheme == ApplicationTheme.Dark);
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "wave.ico"));
        Ui.ResizeLogical(this, 1480, 940);
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.PreferredMinimumWidth = 900;
            presenter.PreferredMinimumHeight = 600;
        }
        Nav.Frame = ContentFrame;
        InitConnect();
        rightPaneOpen = AppServices.Settings.RightPaneOpen;
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

    private void OnBackClick(object sender, RoutedEventArgs e)
    {
        if (ContentFrame.CanGoBack) ContentFrame.GoBack();
    }

    private void OnForward(object sender, RoutedEventArgs e)
    {
        if (ContentFrame.CanGoForward) ContentFrame.GoForward();
    }

    private void OnNavigated(object sender, NavigationEventArgs e)
    {
        BackButton.IsEnabled = ContentFrame.CanGoBack;
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
            list.AddRange(s.Artists.Select(a => new Suggestion(a.Name, L.T("アーティスト", "Artist"), a.ThumbnailUrl, null, new HomeItem.ArtistItem(a))));
            list.AddRange(s.Songs.Select(x => new Suggestion(x.Title, L.T("曲 • ", "Song • ") + x.ArtistsText, x.ThumbnailUrl, null, new HomeItem.SongItem(x))));
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
            AccountMenu.Items.Add(new MenuFlyoutItem { Text = account.Name ?? L.T("ログイン中", "Logged in"), IsEnabled = false });
            AccountMenu.Items.Add(new MenuFlyoutSeparator());
            AccountMenu.Items.Add(MenuItem(L.T("最近再生した曲", "Recently played"), "", Nav.History));
            if (AppServices.Connect.Configured) AccountMenu.Items.Add(MenuItem(L.T("端末の連携", "Devices"), "\uE772", Nav.Devices));
        }
        else AccountMenu.Items.Add(MenuItem(L.T("ログイン", "Log in"), "", ShowLogin));
        AccountMenu.Items.Add(MenuItem(L.T("設定", "Settings"), "", Nav.Settings));
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
            if (ok) ShowToast(L.T("ログインしました", "Logged in"));
        };
        login.Activate();
    }

    private void OnLogin(object sender, RoutedEventArgs e) => ShowLogin();

    // ---- マイライブラリ ----

    private void RenderChips()
    {
        FilterChips.Children.Clear();
        foreach (var (kind, title) in new[] { ("playlist", L.T("プレイリスト", "Playlists")), ("artist", L.T("アーティスト", "Artists")), ("album", L.T("アルバム", "Albums")) })
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
            entries.AddRange(lib.Playlists.Select(p => new LibraryEntry(p.Title, L.T("プレイリスト", "Playlist") + (string.IsNullOrEmpty(p.Subtitle) ? "" : " • " + p.Subtitle), p.ThumbnailUrl, new HomeItem.PlaylistItem(p))));
        }
        if (libraryKind is null or "artist") entries.AddRange(lib.Artists.Select(a => new LibraryEntry(a.Name, L.T("アーティスト", "Artist"), a.ThumbnailUrl, new HomeItem.ArtistItem(a))));
        if (libraryKind is null or "album") entries.AddRange(lib.Albums.Select(a => new LibraryEntry(a.Title, L.T("アルバム", "Album") + (string.IsNullOrEmpty(a.Subtitle) ? "" : " • " + a.Subtitle), a.ThumbnailUrl, new HomeItem.AlbumItem(a))));
        if (filter.Length > 0) entries = entries.Where(e => e.Title.Contains(filter, StringComparison.CurrentCultureIgnoreCase)).ToList();
        if (alphabetical) entries = entries.OrderBy(e => e.Title, StringComparer.CurrentCultureIgnoreCase).ToList();
        // 高く評価した曲はいつも先頭（Spotify の「お気に入りの曲」と同じ）
        if (libraryKind is null or "playlist" && (filter.Length == 0 || L.T("高く評価した曲", "Liked Songs").Contains(filter)))
            entries.Insert(0, new LibraryEntry(L.T("高く評価した曲", "Liked Songs"), L.T("プレイリスト • 自動", "Playlist • Auto"), lib.LikedThumbnail, null, isLiked: true));
        LibraryList.ItemsSource = entries;
    }

    private void OnLibraryFilterChanged(object sender, TextChangedEventArgs e) => RenderLibrary();

    private void OnSort(object sender, RoutedEventArgs e)
    {
        alphabetical = (sender as FrameworkElement)?.Tag as string == "alpha";
        SortButton.Content = alphabetical ? L.T("アルファベット順", "Alphabetical") : L.T("最近", "Recents");
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
        if (entry.Item is { } item) menu.Items.Add(MenuItem(L.T("再生", "Play"), "", () => Nav.Play(item)));
        else if (entry.IsLiked) menu.Items.Add(MenuItem(L.T("再生", "Play"), "", () => Nav.Play(new HomeItem.PlaylistItem(new PlaylistSummary(MusicRepository.LikedSongs, entry.Title, null, null)))));
        menu.ShowAt(element, e.GetPosition(element));
    }

    private void OnCreatePlaylist(object sender, RoutedEventArgs e) => _ = Dialogs.CreatePlaylistAsync();

    // ---- 再生中の曲・キュー ----

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        switch (e.PropertyName)
        {
            case nameof(PlayerService.Current):
                // 曲が変わったら曲の表示に戻す（Android と同じ）
                ShowVideo(false);
                RenderNowPlaying();
                if (QueueView.Visibility == Visibility.Visible) RenderQueue();
                Title = Player.Current is { } current ? $"{current.Title} • {current.ArtistsText}" : "wave";
                break;
            case nameof(PlayerService.Video):
            case nameof(PlayerService.IsRemote):
                UpdateVideoSwitch();
                break;
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
    /// <summary>右の欄に今並べている歌詞（作り直さずに、明るさと位置だけ変えるため）。</summary>
    private object? lyricsPreviewSource;
    private List<(TextBlock Block, LyricLine Line)> lyricsPreviewRows = [];

    /// <summary>
    /// 右の欄の歌詞（Spotify の再生中の画面と同じく、歌っている行を明るく、前後の行を薄く）。
    /// 時刻付きの歌詞では全文を並べ、再生位置に合わせて滑らかに送る。歌詞が無い・探している間は箱ごと出さない。
    /// </summary>
    private void RenderLyricsPreview()
    {
        var lyrics = (Player.Lyrics as LyricsService.Result.Found)?.Lyrics;
        LyricsCard.Visibility = Ui.Show(lyrics is not null && Player.Current is not null);
        if (!ReferenceEquals(lyrics, lyricsPreviewSource))
        {
            lyricsPreviewSource = lyrics;
            lyricsPreviewIndex = -2;
            BuildPreview(lyrics);
        }
        if (lyrics is not SongLyrics.Synced synced) return;
        var last = LyricsTiming.CurrentIndex(synced.Lines, (long)(Player.Position * 1000));
        // 同じ時刻の行（原文と読み・訳が並ぶ歌詞）は、まとめて今の行とする
        var i = last < 0 ? last : lyricsPreviewRows.FindIndex(r => r.Line.StartMs == synced.Lines[last].StartMs);
        if (i == lyricsPreviewIndex) return;
        lyricsPreviewIndex = i;
        for (var k = 0; k < lyricsPreviewRows.Count; k++)
            lyricsPreviewRows[k].Block.Opacity = i >= 0 && k >= i && k <= last ? 1 : k < i ? 0.5 : 0.4;
        // 今のかたまりの1つ前のかたまりが上端に来るように送る（前の行を少し見せる）
        var anchor = i <= 0 ? 0 : lyricsPreviewRows.FindIndex(r => r.Line.StartMs == synced.Lines[Math.Max(0, i - 1)].StartMs);
        LyricsPreview.UpdateLayout();
        var y = anchor <= 0 ? 0 : lyricsPreviewRows[anchor].Block.TransformToVisual(LyricsPreview).TransformPoint(new Windows.Foundation.Point(0, 0)).Y;
        LyricsScroller.ChangeView(null, y, null, disableAnimation: false);
    }

    private void BuildPreview(SongLyrics? lyrics)
    {
        LyricsPreview.Children.Clear();
        lyricsPreviewRows = [];
        LyricsScroller.ChangeView(null, 0, null, disableAnimation: true);
        var primary = (Brush)Application.Current.Resources["TextFillColorPrimaryBrush"];
        switch (lyrics)
        {
            case SongLyrics.Synced synced:
                for (var k = 0; k < synced.Lines.Count; k++)
                {
                    var line = synced.Lines[k];
                    // 原文に続く読み・訳は、小さく原文の直下に添える。かたまりの間は空ける
                    var sub = k > 0 && synced.Lines[k - 1].StartMs == line.StartMs;
                    var block = new TextBlock
                    {
                        Text = string.IsNullOrWhiteSpace(line.Text) ? "♪" : line.Text,
                        TextWrapping = TextWrapping.Wrap,
                        FontSize = sub ? 13 : 16,
                        LineHeight = sub ? 18 : 22,
                        FontWeight = sub ? Microsoft.UI.Text.FontWeights.Normal : Microsoft.UI.Text.FontWeights.SemiBold,
                        Foreground = primary,
                        Opacity = 0.4,
                        Margin = new Thickness(0, k == 0 ? 0 : sub ? 1 : 10, 0, 0),
                        OpacityTransition = new ScalarTransition { Duration = TimeSpan.FromMilliseconds(350) },
                    };
                    LyricsPreview.Children.Add(block);
                    lyricsPreviewRows.Add((block, line));
                }
                break;
            case SongLyrics.Plain plain:
                foreach (var text in plain.Text.Split('\n').Take(12))
                    LyricsPreview.Children.Add(new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, FontSize = 16, LineHeight = 22, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, Foreground = primary, Margin = new Thickness(0, 0, 0, 6) });
                break;
        }
    }

    // --- 曲に重ねる映像（右の欄） ---

    private readonly MusicVideoPlayer videoPlayer = new();
    private DispatcherQueueTimer? videoTimer;

    /// <summary>MV のある曲で「曲｜動画」を出す（ほかの端末を映している間は、その再生の位置の見積もりに合わせる）。</summary>
    private void UpdateVideoSwitch()
    {
        var available = Player.Video is not null;
        VideoSwitch.Visibility = Ui.Show(available);
        if (!available) ShowVideo(false);
    }

    private void OnSongTab(object sender, RoutedEventArgs e) => ShowVideo(false);

    private void OnVideoTab(object sender, RoutedEventArgs e) => ShowVideo(true);

    private async void ShowVideo(bool show)
    {
        SongTab.IsChecked = !show;
        VideoTab.IsChecked = show;
        if (!show || Player.Video is not { } video)
        {
            videoTimer?.Stop();
            videoPlayer.Stop();
            PaneVideo.Visibility = Visibility.Collapsed;
            VideoNote.Visibility = Visibility.Collapsed;
            CoverImage.Visibility = Visibility.Visible;
            CoverBox.Background = (Brush)Application.Current.Resources["ControlFillColorSecondaryBrush"];
            return;
        }
        CoverBox.Background = new SolidColorBrush(Colors.Black);
        CoverImage.Visibility = Visibility.Collapsed;
        VideoNote.Text = L.T("動画を読み込んでいます…", "Loading video…");
        VideoNote.Visibility = Visibility.Visible;
        PaneVideo.SetMediaPlayer(videoPlayer.Player);
        if (!await videoPlayer.LoadAsync(video))
        {
            if (VideoTab.IsChecked != true || Player.Video != video) return;
            VideoNote.Text = L.T("動画を読み込めませんでした", "Couldn't load the video");
            return;
        }
        if (VideoTab.IsChecked != true || Player.Video != video) return;
        VideoNote.Visibility = Visibility.Collapsed;
        PaneVideo.Visibility = Visibility.Visible;
        videoTimer ??= CreateVideoTimer();
        videoTimer.Start();
    }

    private DispatcherQueueTimer CreateVideoTimer()
    {
        var timer = DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(200);
        timer.Tick += (_, _) =>
        {
            if (Player.Video is not { } video) return;
            videoPlayer.Sync(video, Player.IsRemote ? (long)(Player.Position * 1000) : Player.LocalPositionMs, Player.IsPlaying);
            // MV に無い区間（前奏など）はジャケットを出す
            PaneVideo.Visibility = Ui.Show(videoPlayer.Showing);
            CoverImage.Visibility = Ui.Hide(videoPlayer.Showing);
        };
        return timer;
    }

    private void RenderQueue()
    {
        var entries = new List<QueueEntry>();
        for (var i = Math.Max(0, Player.Index); i < Player.Queue.Count; i++) entries.Add(new QueueEntry(i, Player.Queue[i], i == Player.Index));
        QueueList.ItemsSource = entries;
        QueueEmpty.Visibility = entries.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        QueueList.Visibility = entries.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
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

    public string LikeGlyph(LikeStatus? like) => like == LikeStatus.Like ? "\uEC61" : "\uECC8";

    public Brush LikeBrush(LikeStatus? like) =>
        (Brush)Application.Current.Resources[like == LikeStatus.Like ? "AccentTextFillColorPrimaryBrush" : "TextFillColorSecondaryBrush"];

    public string LikeTip(LikeStatus? like) => like == LikeStatus.Like ? L.T("プレイリストに追加", "Add to playlist") : L.T("高く評価した曲に追加", "Add to Liked Songs");

    public bool IsRepeating(RepeatMode mode) => mode != RepeatMode.Off;

    public string RepeatGlyph(RepeatMode mode) => mode == RepeatMode.One ? "" : "";

    public Visibility HasSong(Song? song) => Ui.Show(song is not null);

    public string PaneHeader(Song? song) => song?.Album?.Title is { Length: > 0 } album ? album : L.T("再生中", "Now playing");

    public string SongTitleOrIdle(Song? song) => song?.Title ?? L.T("何も再生していません", "Nothing playing");

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

    /// <summary>評価していなければ評価する。評価済みならプレイリストに追加する画面を開く（Android と同じ）。</summary>
    private void OnLike(object sender, RoutedEventArgs e)
    {
        if (Player.Current is not { } song) return;
        if (Player.Like == LikeStatus.Like) _ = Dialogs.AddToPlaylistAsync(song);
        else Player.ToggleLike();
    }

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
        ToastFrame.Visibility = Visibility.Visible;
        // 下のバーを満タンから空へ動かし、空になったら閉じる
        toastCountdown?.Stop();
        var animation = new Microsoft.UI.Xaml.Media.Animation.DoubleAnimation
        {
            From = 1,
            To = 0,
            Duration = ToastDuration,
            EnableDependentAnimation = true,
        };
        Microsoft.UI.Xaml.Media.Animation.Storyboard.SetTarget(animation, ToastCountdown);
        Microsoft.UI.Xaml.Media.Animation.Storyboard.SetTargetProperty(animation, "Value");
        var board = new Microsoft.UI.Xaml.Media.Animation.Storyboard();
        board.Children.Add(animation);
        board.Completed += (_, _) =>
        {
            if (toastCountdown != board) return;
            Toast.IsOpen = false;
            ToastFrame.Visibility = Visibility.Collapsed;
        };
        toastCountdown = board;
        board.Begin();
    }

    private void OnToastClosed(InfoBar sender, InfoBarClosedEventArgs args)
    {
        toastCountdown?.Stop();
        toastCountdown = null;
        ToastFrame.Visibility = Visibility.Collapsed;
    }

    // --- 端末の連携（Connect v2） ---

    private void InitConnect()
    {
        var connect = AppServices.Connect;
        // 裏（ほかの窓が前・最小化）で再生キーを押したときは、ほかの端末の再生を引き取らずに知らせる
        var active = true;
        Activated += (_, e) => active = e.WindowActivationState != WindowActivationState.Deactivated;
        connect.IsForeground = () => active && AppWindow.Presenter is not OverlappedPresenter { State: OverlappedPresenterState.Minimized };
        connect.Changed += RenderConnect;
        connect.Notice += ShowToast;
        RenderConnect();
    }

    private void RenderConnect()
    {
        var connect = AppServices.Connect;
        DevicesButton.Visibility = connect.Configured ? Visibility.Visible : Visibility.Collapsed;
        // ほかの端末で鳴っているときは、アイコンの右にその端末名（Spotify と同じく色を付ける）
        var remote = Player.IsRemote ? Player.RemoteDevice : null;
        DevicesLabel.Visibility = remote is null ? Visibility.Collapsed : Visibility.Visible;
        DevicesLabel.Text = remote ?? "";
        DevicesIcon.Glyph = remote is null ? "\uE772" : "\uE8EA";
        DevicesIcon.Foreground = remote is not null ? (Brush)Application.Current.Resources["AccentTextFillColorPrimaryBrush"] : (Brush)Application.Current.Resources["TextFillColorPrimaryBrush"];
        if (DevicesFlyout.IsOpen) BuildDevicesPanel();
    }

    private void OnDevicesOpening(object? sender, object e) => BuildDevicesPanel();

    /// <summary>Spotify の「デバイスに接続」と同じ並び: 今鳴っている端末、その下に家のほかの端末（押すとその端末に移す）。</summary>
    private void BuildDevicesPanel()
    {
        var connect = AppServices.Connect;
        var info = connect.Info;
        DevicesPanel.Children.Clear();
        var remote = Player.IsRemote ? connect.Remote : null;
        DevicesPanel.Children.Add(new TextBlock { Text = L.T("現在のデバイス", "Current device"), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] });
        if (remote is null)
        {
            DevicesPanel.Children.Add(DeviceRow("\uE7F4", L.T("この PC", "This PC"), null, accent: true, enabled: true, action: null));
        }
        else
        {
            DevicesPanel.Children.Add(DeviceRow(connect.Members.FirstOrDefault(m => m.DeviceId == remote.DeviceId)?.IsPc == true ? "\uE7F4" : "\uE8EA", remote.DeviceLabel, string.Join(" · ", new[] { remote.Title, remote.Artist }.Where(t => !string.IsNullOrEmpty(t))), accent: true, enabled: true, action: null));
        }
        if (!info.InHousehold)
        {
            DevicesPanel.Children.Add(new TextBlock { Text = L.T("スマホと連携すると、再生を引き継げます", "Connect your phone to move playback between devices"), TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["SubtleTextStyle"] });
        }
        else
        {
            var others = connect.Members.Where(m => remote is null ? !m.IsSelf : m.DeviceId != remote.DeviceId).Where(m => !m.IsController).ToList();
            if (others.Count > 0)
            {
                DevicesPanel.Children.Add(new TextBlock { Text = L.T("ほかのデバイスを選択", "Select another device"), Margin = new Thickness(0, 8, 0, 0), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] });
                foreach (var member in others.OrderByDescending(m => m.IsSelf).ThenByDescending(m => m.Online))
                {
                    if (member.IsSelf)
                    {
                        DevicesPanel.Children.Add(DeviceRow("\uE7F4", L.T("この PC", "This PC"), null, accent: false, enabled: true, action: () =>
                        {
                            DevicesFlyout.Hide();
                            connect.Transfer();
                        }));
                    }
                    else
                    {
                        var target = member.DeviceId;
                        DevicesPanel.Children.Add(DeviceRow(member.IsPc ? "\uE7F4" : "\uE8EA", member.Label, member.Online ? null : L.T("オフライン", "Offline"), accent: false, enabled: member.Online, action: () =>
                        {
                            DevicesFlyout.Hide();
                            connect.PlayOnDevice(target);
                        }));
                    }
                }
            }
        }
        var settings = new HyperlinkButton { Content = L.T("端末の連携の設定", "Device settings"), Margin = new Thickness(0, 8, 0, 0) };
        settings.Click += (_, _) =>
        {
            DevicesFlyout.Hide();
            Nav.Devices();
        };
        DevicesPanel.Children.Add(settings);
    }

    private static FrameworkElement DeviceRow(string glyph, string title, string? subtitle, bool accent, bool enabled, Action? action)
    {
        var accentBrush = (Brush)Application.Current.Resources["AccentTextFillColorPrimaryBrush"];
        var grid = new Grid { ColumnSpacing = 12, Padding = new Thickness(8) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var icon = new FontIcon { Glyph = glyph, FontSize = 24, VerticalAlignment = VerticalAlignment.Center };
        if (accent) icon.Foreground = accentBrush;
        grid.Children.Add(icon);
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        var titleBlock = new TextBlock { Text = title, TextTrimming = TextTrimming.CharacterEllipsis, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
        if (accent) titleBlock.Foreground = accentBrush;
        text.Children.Add(titleBlock);
        if (!string.IsNullOrEmpty(subtitle)) text.Children.Add(new TextBlock { Text = subtitle, TextTrimming = TextTrimming.CharacterEllipsis, Style = (Style)Application.Current.Resources["SubtleTextStyle"] });
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        if (!enabled) grid.Opacity = 0.45;
        if (action is null || !enabled) return grid;
        var button = new Button { Content = grid, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch, Padding = new Thickness(0), Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), BorderThickness = new Thickness(0) };
        button.Click += (_, _) => action();
        return button;
    }

    [System.Runtime.InteropServices.DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    /// <summary>窓の枠（最大化したときの上の縁を含む）をダークで描かせる（DWMWA_USE_IMMERSIVE_DARK_MODE）。</summary>
    private void UseDarkFrame(bool dark)
    {
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        var value = dark ? 1 : 0;
        _ = DwmSetWindowAttribute(hwnd, 20, ref value, sizeof(int));
    }
}
