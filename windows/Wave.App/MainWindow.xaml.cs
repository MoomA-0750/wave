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
using Wave.App.Controls;
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

/// <summary>キューの1行。Section はかたまりの最初の行にだけ付く見出し（「再生中」「キューの次の曲」「次に流れる曲」）。</summary>
public sealed class QueueEntry(int index, Song song, bool isCurrent, string? section = null)
{
    public int Index { get; } = index;
    public Song Song { get; } = song;
    public bool IsCurrent { get; } = isCurrent;
    public string? Section { get; } = section;
    public bool HasSection => Section is not null;
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
    private CancellationTokenSource? suggestCancel;
    private static readonly TimeSpan ToastDuration = TimeSpan.FromSeconds(4);
    private Microsoft.UI.Xaml.Media.Animation.Storyboard? toastCountdown;
    private bool rightPaneOpen;

    public PlayerService Player => AppServices.Player;

    public double CoverSize => 306;

    public MainWindow()
    {
        InitializeComponent();
        // 閉じるときに、キューと位置を書いておく（次の起動で戻す）
        Closed += (_, _) => Player.SaveQueue();
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
        InitPositionSlider();
        InitConnect();
#if DEBUG
        DebugCommands.Start(this);
#endif
        rightPaneOpen = AppServices.Settings.RightPaneOpen;
        Player.PropertyChanged += OnPlayerChanged;
        AppServices.Library.Membership.NowPlayingChanged += () => Player.InPlaylist = AppServices.Library.Membership.IsSavedNowPlaying(Player.Current?.VideoId);
        Player.Queue.CollectionChanged += OnQueueChanged;
        AppServices.Account.PropertyChanged += (_, _) => RenderAccount();
        AppServices.Library.Playlists.CollectionChanged += (_, _) => RenderLibrary();
        AppServices.Library.Artists.CollectionChanged += (_, _) => RenderLibrary();
        AppServices.Library.Albums.CollectionChanged += (_, _) => RenderLibrary();
        AppServices.Library.Podcasts.CollectionChanged += (_, _) => RenderLibrary();
        AppServices.Library.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(LibraryState.IsLoading)) LibraryLoading.Visibility = Ui.Show(AppServices.Library.IsLoading);
            // 起動直後は、プレイリストの一覧が届いてから今の曲を先読みする
            if (e.PropertyName == nameof(LibraryState.IsLoading) && !AppServices.Library.IsLoading) PrefetchMembership();
            if (e.PropertyName is nameof(LibraryState.LikedThumbnail) or nameof(LibraryState.RecentKeys)) RenderLibrary();
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
        // Claude は上のボタンから呼ぶので、少し狭い窓でも右の欄に出す（再生中の画面・キューは広いときだけ）
        var showRight = rightPaneOpen && (width >= 1180 || (AiView.Visibility == Visibility.Visible && width >= 880));
        RightColumn.Width = showRight ? new GridLength(340) : new GridLength(0);
        RightPane.Visibility = Ui.Show(showRight);
        LibraryColumn.Width = new GridLength(width < 1000 ? 240 : 300);
        PaneToggle.IsChecked = showRight && NowPlayingView.Visibility == Visibility.Visible;
        QueueToggle.IsChecked = showRight && QueueView.Visibility == Visibility.Visible;
        AiToggle.IsChecked = showRight && AiView.Visibility == Visibility.Visible;
        SearchBox.Width = width < 1100 ? 300 : 440;
    }

#if DEBUG
    /// <summary>開発用: 右の欄を切り替える（nowplaying / queue / history / ai / ai-toggle）。</summary>
    public void DebugPane(string which)
    {
        switch (which)
        {
            case "queue":
                queueHistory = false;
                SetRightPane(true, queue: true);
                break;
            case "history":
                queueHistory = true;
                SetRightPane(true, queue: true);
                break;
            case "ai":
                OnShowAi(this, new RoutedEventArgs());
                break;
            case "ai-toggle":
                OnAiToggle(this, new RoutedEventArgs());
                break;
            default:
                SetRightPane(true, queue: false);
                break;
        }
        AppPaths.Log($"debug pane {which} right={RightPane.Visibility} ai={AiView.Visibility} queue={QueueView.Visibility} aiToggle={AiToggle.IsChecked}");
    }

    public void DebugFullScreen(bool on) => SetFullScreen(on);

    public void DebugVideo(bool on) => ShowVideo(on);
#endif

    private void SetRightPane(bool open, bool queue)
    {
        rightPaneOpen = open;
        AppServices.Settings.RightPaneOpen = open;
        AppServices.Settings.Save();
        AiView.Visibility = Visibility.Collapsed;
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
        foreach (var (kind, title) in new[] { ("playlist", L.T("プレイリスト", "Playlists")), ("artist", L.T("アーティスト", "Artists")), ("album", L.T("アルバム", "Albums")), ("podcast", L.T("ポッドキャスト", "Podcasts")) })
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
        if (libraryKind is null or "podcast") entries.AddRange(lib.Podcasts.Select(c => new LibraryEntry(c.Title, L.T("ポッドキャスト", "Podcast") + (string.IsNullOrEmpty(c.Author) ? "" : " • " + c.Author), c.ThumbnailUrl, new HomeItem.PodcastItem(c))));
        if (filter.Length > 0) entries = entries.Where(e => e.Title.Contains(filter, StringComparison.CurrentCultureIgnoreCase)).ToList();
        var sort = AppServices.Settings.LibrarySort;
        if (sort == "alpha") entries = entries.OrderBy(e => e.Title, StringComparer.CurrentCultureIgnoreCase).ToList();
        else if (sort == "recent")
        {
            // 最近再生したものを先に、残りは追加した順のまま（Android と同じ）
            var rank = lib.RecentKeys.Select((key, i) => (key, i)).GroupBy(x => x.key).ToDictionary(g => g.Key, g => g.First().i);
            entries = entries.Select((e, i) => (e, i)).OrderBy(x => x.e.Item is { } item && LibraryState.KeyOf(item) is { } k && rank.TryGetValue(k, out var r) ? r : int.MaxValue)
                .ThenBy(x => x.i).Select(x => x.e).ToList();
        }
        // 高く評価した曲はいつも先頭（Spotify の「お気に入りの曲」と同じ）
        if (libraryKind is null or "playlist" && (filter.Length == 0 || L.T("高く評価した曲", "Liked Songs").Contains(filter)))
            entries.Insert(0, new LibraryEntry(L.T("高く評価した曲", "Liked Songs"), L.T("プレイリスト • 自動", "Playlist • Auto"), lib.LikedThumbnail, null, isLiked: true));
        // ポッドキャストで絞ったときは、保存した番組の新しいエピソード（自動のプレイリスト）を先頭に（Android と同じ）
        if (libraryKind == "podcast" && (filter.Length == 0 || L.T("新しいエピソード", "New episodes").Contains(filter)))
            entries.Insert(0, new LibraryEntry(L.T("新しいエピソード", "New episodes"), L.T("プレイリスト • 自動", "Playlist • Auto"), null,
                new HomeItem.PodcastItem(new PodcastSummary(MusicRepository.NewEpisodesBrowse, L.T("新しいエピソード", "New episodes"), null, null))));
        LibraryList.ItemsSource = entries;
        LibraryGrid.ItemsSource = entries;
        var grid = AppServices.Settings.LibraryGrid;
        LibraryList.Visibility = Ui.Hide(grid);
        LibraryGrid.Visibility = Ui.Show(grid);
        // 押すと切り替わる先の絵（Spotify と同じ）
        ViewToggleIcon.Glyph = grid ? "\uE8FD" : "\uF0E2";
        ToolTipService.SetToolTip(ViewToggle, grid ? L.T("リストで表示", "List view") : L.T("グリッドで表示", "Grid view"));
        SortButton.Content = sort switch
        {
            "alpha" => L.T("アルファベット順", "Alphabetical"),
            "added" => L.T("最近追加した順", "Recently added"),
            _ => L.T("最近", "Recents"),
        };
        SortRecent.IsChecked = sort == "recent";
        SortAdded.IsChecked = sort == "added";
        SortAlpha.IsChecked = sort == "alpha";
    }

    private void OnLibraryViewToggle(object sender, RoutedEventArgs e)
    {
        AppServices.Settings.LibraryGrid = !AppServices.Settings.LibraryGrid;
        AppServices.Settings.Save();
        RenderLibrary();
    }

    private void OnLibraryFilterChanged(object sender, TextChangedEventArgs e) => RenderLibrary();

    private void OnSort(object sender, RoutedEventArgs e)
    {
        AppServices.Settings.LibrarySort = (sender as FrameworkElement)?.Tag as string ?? "recent";
        AppServices.Settings.Save();
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

    private CancellationTokenSource? membershipPrefetch;

    /// <summary>再生が始まった曲がどのプレイリストに入っているかを先に調べておく（追加の画面をすぐ出し、評価のボタンに✓を付ける。Android と同じ）。</summary>
    private void PrefetchMembership()
    {
        membershipPrefetch?.Cancel();
        var membership = AppServices.Library.Membership;
        Player.InPlaylist = membership.IsSavedNowPlaying(Player.Current?.VideoId);
        if (Player.Current is not { } song || !AppServices.Client.IsLoggedIn) return;
        var token = (membershipPrefetch = new CancellationTokenSource()).Token;
        var ids = AppServices.Library.Editable.Select(p => p.PlaylistId).ToList();
        _ = PrefetchAsync();

        async Task PrefetchAsync()
        {
            try
            {
                await membership.PrefetchAsync(ids, song.VideoId, token);
            }
            catch (OperationCanceledException)
            {
            }
        }
    }

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        switch (e.PropertyName)
        {
            case nameof(PlayerService.Current):
                // 曲が変わったら曲の表示に戻す（Android と同じ）
                ShowVideo(false);
                RenderNowPlaying();
                RenderFullView();
                if (QueueView.Visibility == Visibility.Visible) RenderQueue();
                Title = Player.Current is { } current ? $"{current.Title} • {current.ArtistsText}" : "wave";
                PrefetchMembership();
                break;
            case nameof(PlayerService.Video):
            case nameof(PlayerService.IsRemote):
                UpdateVideoSwitch();
                RenderFullView();
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

    private LyricsFollower? lyricsPreview;
    private LyricsFollower? fullLyrics;

    /// <summary>
    /// 右の欄の歌詞と、全画面の歌詞。歌詞が無い・探している間は箱ごと出さない。
    /// </summary>
    private void RenderLyricsPreview()
    {
        var lyrics = (Player.Lyrics as LyricsService.Result.Found)?.Lyrics;
        LyricsCard.Visibility = Ui.Show(lyrics is not null && Player.Current is not null);
        var position = (long)(Player.Position * 1000);
        (lyricsPreview ??= new LyricsFollower(LyricsScroller, LyricsPreview, 16)).Update(lyrics, position);
        if (FullView.Visibility == Visibility.Visible)
        {
            FullLyricsScroller.Visibility = Ui.Show(lyrics is not null);
            (fullLyrics ??= new LyricsFollower(FullLyricsScroller, FullLyrics, 30, plainLines: 200)).Update(lyrics, position);
        }
    }

    // --- 全画面の再生中の画面（Spotify のデスクトップの全画面表示。下の操作の帯は出したまま） ---

    private void OnFullScreenToggle(object sender, RoutedEventArgs e) => SetFullScreen(FullView.Visibility != Visibility.Visible);

    private void SetFullScreen(bool on)
    {
        FullView.Visibility = Ui.Show(on);
        FullScreenToggle.IsChecked = on;
        AppWindow.SetPresenter(on ? AppWindowPresenterKind.FullScreen : AppWindowPresenterKind.Overlapped);
        // 映像は1つの枠にしか映せないので、出す枠を移す
        if (Player.Video is not null && VideoTab.IsChecked == true) (on ? FullVideo : PaneVideo).SetMediaPlayer(videoPlayer.Player);
        RenderFullView();
        RenderLyricsPreview();
    }

    private void RenderFullView()
    {
        if (FullView.Visibility != Visibility.Visible) return;
        var song = Player.Current;
        FullCover.Source = Ui.Large(song?.ThumbnailUrl);
        FullBackdrop.Source = Ui.Large(song?.ThumbnailUrl);
        FullTitle.Text = song?.Title ?? "";
        FullArtist.Text = song?.ArtistsText ?? "";
        var video = VideoTab.IsChecked == true && Player.Video is not null;
        FullCover.Visibility = Ui.Hide(video);
        FullVideo.Visibility = Ui.Show(video && videoPlayer.Showing);
        FullVideoSwitch.Visibility = VideoSwitch.Visibility;
        FullSongTab.IsChecked = !video;
        FullVideoTab.IsChecked = video;
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

    private void OnVideoRetry(object sender, RoutedEventArgs e) => ShowVideo(true);

    private void OnVideoTab(object sender, RoutedEventArgs e) => ShowVideo(true);

    private async void ShowVideo(bool show)
    {
        SongTab.IsChecked = !show;
        VideoTab.IsChecked = show;
        RenderFullView();
        if (!show || Player.Video is not { } video)
        {
            videoTimer?.Stop();
            videoPlayer.Stop();
            PaneVideo.Visibility = Visibility.Collapsed;
            VideoNotePanel.Visibility = Visibility.Collapsed;
            CoverImage.Visibility = Visibility.Visible;
            CoverBox.Background = (Brush)Application.Current.Resources["ControlFillColorSecondaryBrush"];
            return;
        }
        CoverBox.Background = new SolidColorBrush(Colors.Black);
        CoverImage.Visibility = Visibility.Collapsed;
        VideoNote.Text = L.T("動画を読み込んでいます…", "Loading video…");
        VideoNotePanel.Visibility = Visibility.Visible;
        VideoRetry.Visibility = Visibility.Collapsed;
        (FullView.Visibility == Visibility.Visible ? FullVideo : PaneVideo).SetMediaPlayer(videoPlayer.Player);
        if (!await videoPlayer.LoadAsync(video))
        {
            if (VideoTab.IsChecked != true || Player.Video != video) return;
            VideoNote.Text = L.T("動画を読み込めませんでした", "Couldn't load the video");
            VideoRetry.Visibility = Visibility.Visible;
            return;
        }
        if (VideoTab.IsChecked != true || Player.Video != video) return;
        VideoNotePanel.Visibility = Visibility.Collapsed;
        PaneVideo.Visibility = Visibility.Visible;
        RenderFullView();
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
            // MV に無い区間（前奏など）は、黒地にその旨を出す（Android と同じ）
            PaneVideo.Visibility = Ui.Show(videoPlayer.Showing);
            if (FullView.Visibility == Visibility.Visible) FullVideo.Visibility = Ui.Show(videoPlayer.Showing);
            VideoNote.Text = L.T("この部分には動画がありません", "There's no video for this part");
            VideoNotePanel.Visibility = Ui.Hide(videoPlayer.Showing);
        };
        return timer;
    }

    private bool queueHistory;

    /// <summary>
    /// キュー（Spotify と同じ）: 一番上に今の曲、その下に自分で足した曲、その下に元から流れる予定の曲。もう流れた曲は「履歴」に分ける。
    /// </summary>
    private void RenderQueue()
    {
        var entries = new System.Collections.ObjectModel.ObservableCollection<QueueEntry>();
        QueueTab.IsChecked = !queueHistory;
        HistoryTab.IsChecked = queueHistory;
        if (queueHistory)
        {
            // 新しい順。押すとその曲に戻る
            for (var i = Math.Min(Player.Index, Player.Queue.Count) - 1; i >= 0; i--) entries.Add(new QueueEntry(i, Player.Queue[i], false));
            ClearQueueButton.Visibility = Visibility.Collapsed;
        }
        else
        {
            var userEnd = Player.UserQueueEnd;
            for (var i = Math.Max(0, Player.Index); i < Player.Queue.Count; i++)
            {
                string? section = i == Player.Index ? L.T("再生中", "Now playing")
                    : i == Player.Index + 1 && i < userEnd ? L.T("キューの次の曲", "Next in queue")
                    : i == userEnd ? L.T("次に流れる曲", "Next up")
                    : null;
                entries.Add(new QueueEntry(i, Player.Queue[i], i == Player.Index, section));
            }
            ClearQueueButton.Visibility = Ui.Show(userEnd > Player.Index + 1 && !Player.IsRemote);
        }
        QueueList.ItemsSource = entries;
        QueueEmpty.Visibility = entries.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        QueueEmptyTitle.Text = queueHistory ? L.T("まだ流れた曲はありません", "Nothing played yet") : L.T("キューは空です", "Your queue is empty");
        QueueEmptyText.Text = queueHistory ? L.T("流れた曲は、ここに新しい順に並びます", "Songs you've played will show up here") : L.T("曲を再生すると、ここに次の曲が並びます", "Play something and upcoming songs will show up here");
        QueueList.Visibility = entries.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        QueueList.CanReorderItems = !queueHistory;
        QueueList.CanDragItems = !queueHistory;
    }

    private void OnQueueTab(object sender, RoutedEventArgs e)
    {
        queueHistory = false;
        RenderQueue();
    }

    private void OnHistoryTab(object sender, RoutedEventArgs e)
    {
        queueHistory = true;
        RenderQueue();
    }

    private void OnClearQueue(object sender, RoutedEventArgs e)
    {
        Player.ClearUserQueue();
        RenderQueue();
    }

    private void OnQueueDragStarting(object sender, DragItemsStartingEventArgs e)
    {
        // 今の曲と、ほかの端末を映している間は並べ替えない
        if (Player.IsRemote || e.Items.OfType<QueueEntry>().Any(q => q.IsCurrent)) e.Cancel = true;
    }

    private void OnQueueDragCompleted(ListViewBase sender, DragItemsCompletedEventArgs e)
    {
        if (e.DropResult != Windows.ApplicationModel.DataTransfer.DataPackageOperation.Move || e.Items.FirstOrDefault() is not QueueEntry moved) return;
        if (QueueList.ItemsSource is not IList<QueueEntry> shown) return;
        // 表示の並びでの位置から、キューでの行き先を求める（今の曲より前には置かない）
        var to = Player.Index + Math.Max(1, shown.IndexOf(moved));
        Player.MoveUpcoming(moved.Index, to);
        RenderQueue();
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

    // ---- Claude に聞く ----

    private bool aiWired;

    // 上のボタンで Claude を閉じたときに戻す、開く前の右の欄（閉じていた・再生中の画面・キュー）
    private (bool Open, bool Queue) beforeAi;

    /// <summary>上のバーの Claude のボタン（Ctrl+J）。開いていれば、開く前の右の欄に戻す。</summary>
    private void OnAiToggle(object sender, RoutedEventArgs e)
    {
        if (rightPaneOpen && AiView.Visibility == Visibility.Visible)
        {
            SetRightPane(beforeAi.Open, beforeAi.Queue);
            return;
        }
        OnShowAi(sender, e);
    }

    private void OnShowAi(object sender, RoutedEventArgs e)
    {
        if (!(rightPaneOpen && AiView.Visibility == Visibility.Visible)) beforeAi = (rightPaneOpen, QueueView.Visibility == Visibility.Visible);
        SetRightPane(true, queue: false);
        NowPlayingView.Visibility = Visibility.Collapsed;
        AiView.Visibility = Visibility.Visible;
        Layout();
        if (!aiWired)
        {
            aiWired = true;
            AppServices.Ai.Changed += () => DispatcherQueue.TryEnqueue(RenderAi);
            foreach (var suggestion in new[]
            {
                L.T("この歌詞ってどういう意味だと思う？", "What do these lyrics mean?"),
                L.T("これに似た曲ある？", "Any songs like this?"),
                L.T("この曲から始まるプレイリストを作って", "Make a playlist from this song"),
            })
            {
                var button = new HyperlinkButton { Content = suggestion, Padding = new Thickness(0, 2, 0, 2) };
                button.Click += (_, _) => AppServices.Ai.Send(suggestion);
                AiSuggestions.Children.Add(button);
            }
        }
        RenderAi();
        AiInput.Focus(FocusState.Programmatic);
    }

    private void RenderAi()
    {
        var chat = AppServices.Ai;
        AiMessages.Children.Clear();
        if (chat.Messages.Count == 0)
            AiMessages.Children.Add(new TextBlock { Text = L.T("曲探し・プレイリスト作りを頼めます。再生中の曲について、歌詞の意味や似た曲も聞けます。", "Ask Claude to find music, make a playlist, or tell you about the song that's playing — what the lyrics mean, similar songs, and more."), TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["SubtleTextStyle"], MaxLines = 0 }); // 案内文は折り返して全部出す（SubtleTextStyle は1行で切る）
        foreach (var message in chat.Messages) AiMessages.Children.Add(AiElement(message));
        if (chat.Running) AiMessages.Children.Add(new ProgressBar { IsIndeterminate = true });
        AiSendIcon.Glyph = chat.Running ? "\uE71A" : "\uE724";
        AiSuggestions.Visibility = Ui.Hide(chat.Running);
        AiScroller.UpdateLayout();
        AiScroller.ChangeView(null, AiScroller.ScrollableHeight, null, disableAnimation: true);
    }

    private static UIElement AiElement(AiMessage message)
    {
        var subtle = (Style)Application.Current.Resources["SubtleTextStyle"];
        switch (message)
        {
            case AiMessage.User user:
                return new Border
                {
                    HorizontalAlignment = HorizontalAlignment.Right,
                    MaxWidth = 260,
                    CornerRadius = new CornerRadius(14),
                    Padding = new Thickness(12, 6, 12, 8),
                    Background = (Brush)Application.Current.Resources["AccentFillColorDefaultBrush"],
                    Child = new TextBlock { Text = user.Text, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["TextOnAccentFillColorPrimaryBrush"] },
                };
            case AiMessage.Assistant assistant:
                return MarkdownBlock(assistant.Text);
            case AiMessage.Action action:
                return new TextBlock { Text = "・" + action.Text, TextWrapping = TextWrapping.Wrap, Style = subtle };
            case AiMessage.Failure failure:
                return new TextBlock { Text = failure.Text, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"] };
            case AiMessage.Songs songs:
            {
                // 押すと再生、右クリックで曲のメニュー
                var panel = new StackPanel { Spacing = 2 };
                foreach (var song in songs.Items)
                {
                    var grid = new Grid { ColumnSpacing = 10 };
                    grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(40) });
                    grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    grid.Children.Add(new Border { Width = 40, Height = 40, CornerRadius = new CornerRadius(4), Child = new Image { Source = Ui.Small(song.ThumbnailUrl), Stretch = Stretch.UniformToFill } });
                    var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                    text.Children.Add(new TextBlock { Text = song.Title, TextTrimming = TextTrimming.CharacterEllipsis });
                    text.Children.Add(new TextBlock { Text = song.ArtistsText, Style = subtle, TextTrimming = TextTrimming.CharacterEllipsis });
                    Grid.SetColumn(text, 1);
                    grid.Children.Add(text);
                    var button = new Button { Content = grid, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch, Padding = new Thickness(6), Background = new SolidColorBrush(Colors.Transparent), BorderThickness = new Thickness(0) };
                    button.Click += (_, _) => AppServices.Player.PlaySong(song);
                    button.ContextFlyout = SongMenu.Build(song);
                    panel.Children.Add(button);
                }
                return panel;
            }
            default:
                return new TextBlock();
        }
    }

    /// <summary>Claude の答えの Markdown のうち、太字（**…**）と見出し（# …）だけを見た目にする（Android と同じ）。</summary>
    private static TextBlock MarkdownBlock(string text)
    {
        var block = new TextBlock { TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true };
        var lines = text.Split('\n');
        for (var i = 0; i < lines.Length; i++)
        {
            if (i > 0) block.Inlines.Add(new Microsoft.UI.Xaml.Documents.LineBreak());
            var line = lines[i];
            var heading = System.Text.RegularExpressions.Regex.Match(line, @"^#{1,6}\s+");
            if (heading.Success) line = line[heading.Length..];
            var parts = line.Split("**");
            for (var k = 0; k < parts.Length; k++)
            {
                var run = new Microsoft.UI.Xaml.Documents.Run { Text = parts[k] };
                if (heading.Success || k % 2 == 1) run.FontWeight = Microsoft.UI.Text.FontWeights.Bold;
                block.Inlines.Add(run);
            }
        }
        return block;
    }

    private void OnAiSend(object sender, RoutedEventArgs e)
    {
        if (AppServices.Ai.Running)
        {
            AppServices.Ai.Stop();
            return;
        }
        AppServices.Ai.Send(AiInput.Text);
        AiInput.Text = "";
    }

    private void OnAiInputKeyDown(object sender, KeyRoutedEventArgs e)
    {
        // Enter で送る（Shift+Enter は改行）
        var shift = Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(Windows.System.VirtualKey.Shift).HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down);
        if (e.Key != Windows.System.VirtualKey.Enter || shift || AppServices.Ai.Running) return;
        e.Handled = true;
        AppServices.Ai.Send(AiInput.Text);
        AiInput.Text = "";
    }

    private void OnAiClear(object sender, RoutedEventArgs e) => AppServices.Ai.Clear();

    private void OnShowNowPlaying(object sender, RoutedEventArgs e) => SetRightPane(true, queue: false);

    private void OnOpenLyrics(object sender, RoutedEventArgs e) => Nav.Lyrics();

    /// <summary>アーティストを押した: 1人ならそのページへ、複数なら選ぶメニューを出す（Android と同じ）。</summary>
    private void OnArtistLink(object sender, RoutedEventArgs e)
    {
        var artists = Player.Current?.Artists.Where(a => a.BrowseId is not null).ToList() ?? [];
        if (artists.Count == 1) OpenFromFull(() => Nav.Artist(artists[0].BrowseId!));
        else if (artists.Count > 1 && sender is FrameworkElement anchor)
        {
            var menu = new MenuFlyout();
            foreach (var artist in artists)
            {
                var item = new MenuFlyoutItem { Text = artist.Name, Icon = new FontIcon { Glyph = "\uE77B" } };
                item.Click += (_, _) => OpenFromFull(() => Nav.Artist(artist.BrowseId!));
                menu.Items.Add(item);
            }
            menu.ShowAt(anchor);
        }
    }

    /// <summary>曲名を押した: アルバムへ。</summary>
    private void OnAlbumLink(object sender, RoutedEventArgs e)
    {
        if (Player.Current?.Album?.BrowseId is { } id) OpenFromFull(() => Nav.Album(id));
    }

    /// <summary>全画面からページを開くときは、全画面を閉じてから開く。</summary>
    private void OpenFromFull(Action open)
    {
        if (FullView.Visibility == Visibility.Visible) SetFullScreen(false);
        open();
    }

    private void OnArtTapped(object sender, TappedRoutedEventArgs e) => SetRightPane(!(rightPaneOpen && NowPlayingView.Visibility == Visibility.Visible), queue: false);

    // ---- 再生の操作 ----

    public string PlayGlyph(bool playing) => playing ? "" : "";

    public bool IsLiked(LikeStatus? like) => like == LikeStatus.Like;

    /// <summary>評価済みは✓、まだなら＋、読めていない（ログイン中で評価が分からない）なら↻（押すと読み直す。Android と同じ）。</summary>
    // Spotify と同じく、高く評価していなくても、どれかのプレイリストに入っていれば✓（押すとプレイリストに追加する画面。Android と同じ）
    public string LikeGlyph(LikeStatus? like, Song? current, bool inPlaylist) => like switch
    {
        LikeStatus.Like => "\uEC61",
        _ when inPlaylist => "\uEC61",
        null when AppServices.Client.IsLoggedIn && current is not null => "\uE72C",
        _ => "\uECC8",
    };

    public Brush LikeBrush(LikeStatus? like, bool inPlaylist) =>
        (Brush)Application.Current.Resources[like == LikeStatus.Like || inPlaylist ? "AccentTextFillColorPrimaryBrush" : "TextFillColorSecondaryBrush"];

    public string LikeTip(LikeStatus? like, Song? current, bool inPlaylist) => like switch
    {
        LikeStatus.Like => L.T("プレイリストに追加", "Add to playlist"),
        _ when inPlaylist => L.T("プレイリストに入っています（追加先を変える）", "Saved in a playlist (change)"),
        null when AppServices.Client.IsLoggedIn && current is not null => L.T("評価を読み直す", "Reload like status"),
        _ => L.T("高く評価した曲に追加", "Add to Liked Songs"),
    };

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
        if (Player.Like == LikeStatus.Like || Player.InPlaylist) _ = Dialogs.AddToPlaylistAsync(song);
        else if (Player.Like is null && AppServices.Client.IsLoggedIn) Player.ReloadLike();
        else Player.ToggleLike();
    }

    /// <summary>本人がシークバーを触っている（押している・キーで動かした直後）。これ以外の値の変化（曲が変わって最大値が縮んだなど）ではシークしない。</summary>
    private bool sliderByUser;

    private void InitPositionSlider()
    {
        PositionSlider.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler((_, _) => sliderByUser = true), handledEventsToo: true);
        PositionSlider.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler((_, _) => sliderByUser = false), handledEventsToo: true);
        PositionSlider.AddHandler(UIElement.PointerCaptureLostEvent, new PointerEventHandler((_, _) => sliderByUser = false), handledEventsToo: true);
        PositionSlider.AddHandler(UIElement.KeyDownEvent, new KeyEventHandler((_, _) => sliderByUser = true), handledEventsToo: true);
        PositionSlider.AddHandler(UIElement.KeyUpEvent, new KeyEventHandler((_, _) => sliderByUser = false), handledEventsToo: true);
    }

    private void OnPositionChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        // 再生位置の更新による変化は小さい。本人が触っていて、大きく動いたときだけ移る
        if (sliderByUser && Math.Abs(e.NewValue - Player.Position) > 1.5) Player.Seek(e.NewValue);
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
            case Windows.System.VirtualKey.F11:
                SetFullScreen(FullView.Visibility != Visibility.Visible);
                e.Handled = true;
                break;
            case Windows.System.VirtualKey.Escape when FullView.Visibility == Visibility.Visible:
                SetFullScreen(false);
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
