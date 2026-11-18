using System.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Wave.App.Services;
using Wave.Core.Music;
using Wave.Core.Transfer;

namespace Wave.App.Views;

/// <summary>見直しの一覧の1行（Spotify のもの → 選んだ YouTube Music のもの）。</summary>
public sealed class TransferRow(TransferItem item) : INotifyPropertyChanged
{
    public TransferItem Item { get; } = item;

    public event PropertyChangedEventHandler? PropertyChanged;

    public string SourceTitle => Item.Title;

    public string SourceSubtitle => string.Join(" • ", new[]
    {
        Item.Kind switch
        {
            TransferKind.Album => L.T("アルバム", "Album"),
            TransferKind.Artist => L.T("アーティスト", "Artist"),
            _ => L.T("曲", "Song"),
        },
        Item.ArtistsText,
        Item.Album,
    }.Where(s => !string.IsNullOrEmpty(s)));

    public string TargetTitle => Item.Excluded ? L.T("移さない", "Not moved") : Item.Chosen?.Title ?? L.T("見つかりません", "Not found");

    public string TargetSubtitle => Item.Excluded ? "" : Item.Chosen is { } c ? TransferPage.CandidateSubtitle(c) : L.T("「選ぶ」から YouTube Music で探せます", "Use Choose to search YouTube Music");

    // 絵は行ごとに1つを持ち続ける（絞り込みで出し直すたびに作り直すと、読み直す間の数コマ空になった）
    private (string? Url, ImageSource? Image) thumb;

    public ImageSource? Thumb
    {
        get
        {
            var url = Item.Excluded ? null : Item.Chosen?.ThumbnailUrl;
            if (thumb.Url != url || (url is not null && thumb.Image is null)) thumb = (url, Ui.Small(url));
            return thumb.Image;
        }
    }

    public CornerRadius Radius => Ui.Radius(Item.Kind == TransferKind.Artist);

    public string StatusText =>
        Item.Excluded ? L.T("外す", "Excluded")
        : Item.Manual ? L.T("選んだ", "Chosen")
        : Item.Status switch
        {
            MatchStatus.Matched => L.T("一致", "Matched"),
            MatchStatus.Review => L.T("要確認", "Check"),
            MatchStatus.NotFound => L.T("見つからない", "Not found"),
            _ => L.T("未確認", "Pending"),
        };

    public Brush StatusBackground => (Brush)Application.Current.Resources[
        Item.Excluded ? "ControlFillColorSecondaryBrush"
        : Item.Manual ? "SystemFillColorSuccessBackgroundBrush"
        : Item.Status switch
        {
            MatchStatus.Review => "SystemFillColorCautionBackgroundBrush",
            MatchStatus.NotFound => "SystemFillColorCriticalBackgroundBrush",
            MatchStatus.Matched => "SystemFillColorSuccessBackgroundBrush",
            _ => "ControlFillColorSecondaryBrush",
        }];

    public void Refresh() => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(""));
}

/// <summary>
/// Spotify から移す。読み込む（データのダウンロード・Web API）→ 移すものを選ぶ → 突き合わせ → 見直す（候補から選び直す・外す・手で探す）→ 確かめてから書き込む。
/// 作業そのものは AppServices.Transfer が持ち、このページは出すだけ（離れても作業は続く）。
/// </summary>
public sealed partial class TransferPage : Page
{
    private static SpotifyTransfer Transfer => AppServices.Transfer;

    /// <summary>見直しの絞り込み（状態: all・review・notfound・excluded、種類: all・track・album・artist）。</summary>
    private string statusFilter = "all";
    private string kindFilter = "all";
    private List<TransferRow> rows = [];
    private TransferStage? renderedStage;
    /// <summary>一覧を作ったときの作業（ページは使い回すので、やり直したら作り直す）。</summary>
    private TransferSession? renderedSession;

    public TransferPage() => InitializeComponent();

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        Transfer.Changed += Render;
        RedirectBox.Text = SpotifyAuth.RegisteredRedirectUri;
        ClientIdBox.Text = AppServices.Settings.SpotifyClientId ?? "";
        Render();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        Transfer.Changed -= Render;
        Transfer.Save();
    }

    private void Render()
    {
        var session = Transfer.Session;
        var busy = Transfer.Busy;
        var stage = session?.Stage;
        var reviewing = busy is null && stage == TransferStage.Review;
        ReviewPanel.Visibility = Ui.Show(reviewing);
        MainScroll.Visibility = Ui.Show(!reviewing);
        ProgressPanel.Visibility = Ui.Show(busy is not null);
        StartPanel.Visibility = Ui.Show(busy is null && session is null);
        ChoosePanel.Visibility = Ui.Show(busy is null && stage is TransferStage.Loaded or TransferStage.Matching);
        ResultPanel.Visibility = Ui.Show(busy is null && stage is TransferStage.Writing or TransferStage.Finished);
        ShowMessage(reviewing ? ReviewMessageBar : MessageBar, Transfer.Message);
        (reviewing ? MessageBar : ReviewMessageBar).IsOpen = false;
        if (busy is not null) RenderProgress(busy);
        else if (reviewing) RenderReview(stage != renderedStage || session != renderedSession);
        else if (stage is TransferStage.Loaded or TransferStage.Matching) RenderChoose(session!);
        else if (stage is TransferStage.Writing or TransferStage.Finished) RenderResult(session!);
        renderedStage = busy is null ? stage : null;
        renderedSession = session;
    }

    private static void ShowMessage(InfoBar bar, string? message)
    {
        bar.Message = message ?? "";
        bar.IsOpen = message is not null;
        bar.Severity = Transfer.LastStop == TransferStop.Cancelled ? InfoBarSeverity.Informational : InfoBarSeverity.Warning;
    }

    // --- 進み具合 ---

    private void RenderProgress(string busy)
    {
        var p = Transfer.Progress;
        ProgressTitle.Text = busy switch
        {
            "login" => L.T("ブラウザで Spotify にログインして、許可してください…", "Sign in to Spotify in your browser and allow access…"),
            "read" => L.T("Spotify から読んでいます…", "Reading from Spotify…"),
            "match" => L.T("YouTube Music で同じものを探しています…", "Finding the same items on YouTube Music…"),
            _ => L.T("YouTube Music に書き込んでいます…", "Writing to YouTube Music…"),
        };
        BackgroundHint.Visibility = Ui.Show(Transfer.RunsLong);
        ProgressBar.IsIndeterminate = p is null || p.Total == 0;
        if (p is { Total: > 0 }) ProgressBar.Value = (double)p.Done / p.Total;
        ProgressDetail.Text = p is null ? "" : p.Stage switch
        {
            "match" => L.T($"{p.Done} / {p.Total} 件", $"{p.Done} of {p.Total}") + (p.Current is { } c ? " • " + c : ""),
            "playlist" => L.T($"プレイリスト {p.Done + 1} / {p.Total}", $"Playlist {p.Done + 1} of {p.Total}") + (p.Current is { } n ? " • " + n : ""),
            "like" => L.T($"高く評価 {p.Done} / {p.Total} 曲", $"Liking {p.Done} of {p.Total} songs"),
            "album" => L.T($"アルバムの保存 {p.Done} / {p.Total} 枚", $"Saving albums {p.Done} of {p.Total}"),
            "artist" => L.T($"アーティストの登録 {p.Done} / {p.Total} 人", $"Subscribing {p.Done} of {En(p.Total, "artist")}"),
            "read-playlists" => L.T($"プレイリスト {p.Done} 個", En(p.Done, "playlist")),
            "read-playlist-items" => L.T($"プレイリストの中身 {p.Done} 個", $"Contents of {En(p.Done, "playlist")}"),
            "read-liked" => L.T($"お気に入りの曲 {p.Done} 曲", $"{p.Done} liked songs"),
            "read-albums" => L.T($"アルバム {p.Done} 枚", En(p.Done, "album")),
            "read-artists" => L.T($"アーティスト {p.Done} 人", En(p.Done, "artist")),
            _ => "",
        };
        if (p?.WaitSeconds is { } wait)
            ProgressDetail.Text = L.T($"YouTube Music に続けての検索を一時的に断られたので、{(wait >= 60 ? $"{wait / 60} 分" : $"{wait} 秒")}待ってから続けます", $"YouTube Music is briefly refusing requests, so wave waits {(wait >= 60 ? $"{wait / 60} min" : $"{wait} s")} and continues");
        else if (Transfer.Remaining is { } left) ProgressDetail.Text += " • " + Remaining(left);
        StopButton.Visibility = Ui.Show(busy is "login" or "match" or "write" or "read");
    }

    /// <summary>残りのおよその時間（「残り およそ 1 時間 20 分」）。</summary>
    internal static string Remaining(TimeSpan left)
    {
        var minutes = (int)Math.Ceiling(left.TotalMinutes);
        if (minutes <= 1) return L.T("残り 1 分ほど", "about a minute left");
        if (minutes < 60) return L.T($"残り およそ {minutes} 分", $"about {minutes} min left");
        var h = minutes / 60;
        var m = minutes % 60 / 10 * 10;
        return m == 0 ? L.T($"残り およそ {h} 時間", $"about {h} h left") : L.T($"残り およそ {h} 時間 {m} 分", $"about {h} h {m} min left");
    }

    private void OnStop(object sender, RoutedEventArgs e) => Transfer.Stop();

    // --- 読み込む ---

    private async void OnOpenFile(object sender, RoutedEventArgs e)
    {
        if (App.Window is null) return;
        var picker = new Microsoft.Windows.Storage.Pickers.FileOpenPicker(App.Window.AppWindow.Id);
        picker.FileTypeFilter.Add(".zip");
        picker.FileTypeFilter.Add(".json");
        var file = await picker.PickSingleFileAsync();
        if (file?.Path is { Length: > 0 } path) await Transfer.LoadExportAsync(path);
    }

    private async void OnOpenFolder(object sender, RoutedEventArgs e)
    {
        if (App.Window is null) return;
        var picker = new Microsoft.Windows.Storage.Pickers.FolderPicker(App.Window.AppWindow.Id);
        var folder = await picker.PickSingleFolderAsync();
        if (folder?.Path is { Length: > 0 } path) await Transfer.LoadExportAsync(path);
    }

    private async void OnLogin(object sender, RoutedEventArgs e)
    {
        var id = new string(ClientIdBox.Text.Where(char.IsAsciiLetterOrDigit).ToArray());
        if (id.Length == 0)
        {
            MessageBar.Message = L.T("Client ID を貼ってください", "Paste the Client ID");
            MessageBar.Severity = InfoBarSeverity.Warning;
            MessageBar.IsOpen = true;
            return;
        }
        AppServices.Settings.SpotifyClientId = id;
        AppServices.Settings.Save();
        await Transfer.LoginAndReadAsync(id);
    }

    // --- 移すものを選ぶ ---

    private bool renderingChoose;

    private void RenderChoose(TransferSession s)
    {
        renderingChoose = true;
        SourceText.Text = (s.Source == "api" ? L.T("読んだ元: Spotify の Web API（ログイン）", "Source: Spotify Web API (signed in)") : L.T("読んだ元: Spotify のデータのダウンロード", "Source: Spotify data download"))
            + (s.SourceName is { } name && s.Source != "api" ? $" • {name}" : "");
        ProblemsBar.IsOpen = s.Problems.Count > 0;
        ProblemsBar.Message = string.Join("\n", s.Problems.Select(ProblemText));
        var readable = s.Playlists.Where(p => p.Readable).ToList();
        PlaylistsCheck.Content = L.T($"プレイリスト（{readable.Count} 個・{readable.Sum(p => p.TrackKeys.Count)} 曲）", $"Playlists ({readable.Count}, {L.Songs(readable.Sum(p => p.TrackKeys.Count))})");
        LikedCheck.Content = L.T($"お気に入りの曲 → 高く評価（{s.LikedKeys.Count} 曲）", $"Liked songs → like ({L.Songs(s.LikedKeys.Count)})");
        AlbumsCheck.Content = L.T($"保存したアルバム → ライブラリに保存（{s.AlbumKeys.Count} 枚）", $"Saved albums → save to library ({s.AlbumKeys.Count})");
        ArtistsCheck.Content = L.T($"フォロー中のアーティスト → 登録（{s.ArtistKeys.Count} 人）", $"Followed artists → subscribe ({s.ArtistKeys.Count})");
        PlaylistsCheck.IsChecked = s.IncludePlaylists;
        LikedCheck.IsChecked = s.IncludeLiked;
        AlbumsCheck.IsChecked = s.IncludeAlbums;
        ArtistsCheck.IsChecked = s.IncludeArtists;
        PlaylistsCheck.IsEnabled = readable.Count > 0;
        LikedCheck.IsEnabled = s.LikedKeys.Count > 0;
        AlbumsCheck.IsEnabled = s.AlbumKeys.Count > 0;
        ArtistsCheck.IsEnabled = s.ArtistKeys.Count > 0;

        PlaylistsHeader.Visibility = PlaylistChecks.Visibility = Ui.Show(s.Playlists.Count > 0);
        PlaylistChecks.Children.Clear();
        for (var i = 0; i < s.Playlists.Count; i++)
        {
            var p = s.Playlists[i];
            var check = new CheckBox
            {
                Content = p.Name + L.T("（", " (") + (!p.Readable ? L.T("中身を読めない: 他人のプレイリスト", "can't read: made by someone else") : p.TrackKeys.Count == 0 ? L.T("空なので移さない", "empty, nothing to move") : L.Songs(p.TrackKeys.Count)) + L.T("）", ")"),
                IsChecked = p.Include && p.Readable,
                IsEnabled = p.Readable && p.TrackKeys.Count > 0 && s.IncludePlaylists,
            };
            check.Click += (_, _) =>
            {
                p.Include = check.IsChecked == true;
                Transfer.SelectionChanged();
            };
            PlaylistChecks.Children.Add(check);
        }
        var scope = s.ItemsInScope();
        var pending = scope.Count(i => i.Status == MatchStatus.Pending);
        MatchButton.Content = pending < scope.Count && pending > 0
            ? L.T($"続きを突き合わせる（残り {pending} 件）", $"Continue matching ({pending} left)")
            : pending == 0 && scope.Count > 0 ? L.T("見直しへ", "Review") : L.T($"突き合わせる（{scope.Count} 件）", $"Find matches ({scope.Count})");
        MatchButton.IsEnabled = scope.Count > 0;
        renderingChoose = false;
    }

    private static string ProblemText(ImportProblem p) => p.Kind switch
    {
        ImportProblemKind.Unreadable => L.T($"{p.File}: 読めませんでした（JSON の形が壊れています）", $"{p.File}: couldn't be read (the JSON is broken)"),
        ImportProblemKind.UnknownShape => L.T($"{p.File}: 知っている項目がありませんでした", $"{p.File}: no known items"),
        ImportProblemKind.SkippedItems => L.T($"{p.File}: {p.Count} 件を読み飛ばしました（曲名などがありません）", $"{p.File}: skipped {Items(p.Count)} with no title"),
        ImportProblemKind.SkippedEpisodes => L.T($"{p.File}: ポッドキャストのエピソードなど {p.Count} 件は移しません", $"{p.File}: {Items(p.Count)} that aren't songs (podcast episodes and the like) won't be moved"),
        _ => L.T($"{p.File}: Spotify から読めませんでした", $"{p.File}: couldn't be read from Spotify"),
    };

    private static string Items(int count) => En(count, "item");

    /// <summary>英語の数と名詞（1 album / 2 albums）。</summary>
    private static string En(int count, string noun) => count == 1 ? $"1 {noun}" : $"{count} {noun}s";

    private void OnCategoryClick(object sender, RoutedEventArgs e)
    {
        if (renderingChoose || Transfer.Session is not { } s) return;
        s.IncludePlaylists = PlaylistsCheck.IsChecked == true;
        s.IncludeLiked = LikedCheck.IsChecked == true;
        s.IncludeAlbums = AlbumsCheck.IsChecked == true;
        s.IncludeArtists = ArtistsCheck.IsChecked == true;
        Transfer.SelectionChanged();
    }

    private async void OnMatch(object sender, RoutedEventArgs e)
    {
        if (Transfer.Session is not { } s) return;
        if (s.ItemsInScope().All(i => i.Status != MatchStatus.Pending))
        {
            s.Stage = TransferStage.Review;
            Transfer.SelectionChanged();
            return;
        }
        await Transfer.MatchAsync();
    }

    private async void OnDiscard(object sender, RoutedEventArgs e)
    {
        var dialog = new ContentDialog
        {
            Title = L.T("最初からやり直しますか？", "Start over?"),
            Content = L.T("読み込んだものと見直しの選びを消します。YouTube Music に書き込んだものはそのままです。", "This clears what was read and your review choices. Anything already written to YouTube Music stays."),
            PrimaryButtonText = L.T("やり直す", "Start over"),
            CloseButtonText = L.T("キャンセル", "Cancel"),
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary) Transfer.Discard();
    }

    // --- 見直す ---

    private void RenderReview(bool rebuild)
    {
        if (Transfer.Session is not { } s) return;
        var scope = s.ItemsInScope();
        if (rebuild || rows.Count == 0 || rows.Count != scope.Count)
        {
            // 要確認・見つからないを先に、種類は曲・アルバム・アーティストの順
            rows = scope.OrderBy(i => i.Status switch { MatchStatus.Review => 0, MatchStatus.NotFound => 1, MatchStatus.Pending => 2, _ => 3 })
                .ThenBy(i => i.Kind)
                .Select(i => new TransferRow(i)).ToList();
        }
        RenderChips(scope);
        ApplyFilter();
        RenderReviewSummary();
    }

    private void RenderReviewSummary()
    {
        if (Transfer.Session is not { } s) return;
        var scope = s.ItemsInScope();
        int matched = scope.Count(i => !i.Excluded && (i.Status == MatchStatus.Matched || i.Manual)),
            review = scope.Count(i => !i.Excluded && !i.Manual && i.Status == MatchStatus.Review),
            notFound = scope.Count(i => !i.Excluded && !i.Manual && i.Status == MatchStatus.NotFound),
            excluded = scope.Count(i => i.Excluded);
        ReviewSummary.Text = L.T(
            $"{scope.Count} 件のうち、一致 {matched}・要確認 {review}・見つからない {notFound}・外す {excluded}。要確認は自動で一番近いものを選んであります。「選ぶ」でほかの候補・手で探したものに替えたり、外したりできます",
            $"Of {scope.Count}: {matched} matched, {review} to check, {notFound} not found, {excluded} excluded. Items to check already have the closest match chosen. Use Choose to pick another candidate, search yourself or exclude it");
        var plan = TransferPlan.Of(s);
        PlanText.Text = PlanSummary(plan);
        WriteButton.IsEnabled = !plan.IsEmpty;
        foreach (var chip in StatusChips.Children.OfType<ToggleButton>()) chip.Content = StatusChipTitle((string)chip.Tag, scope);
    }

    private static string PlanSummary(TransferPlan plan)
    {
        if (plan.IsEmpty) return L.T("書き込むものはありません", "Nothing to write");
        var parts = new List<string>();
        if (plan.Playlists.Count > 0) parts.Add(L.T($"プレイリスト {plan.Playlists.Count} 個（{plan.PlaylistSongs} 曲）", $"{En(plan.Playlists.Count, "playlist")} ({L.Songs(plan.PlaylistSongs)})"));
        if (plan.Likes.Count > 0) parts.Add(L.T($"高く評価 {plan.Likes.Count} 曲", $"like {L.Songs(plan.Likes.Count)}"));
        if (plan.Albums.Count > 0) parts.Add(L.T($"アルバムの保存 {plan.Albums.Count} 枚", $"save {En(plan.Albums.Count, "album")}"));
        if (plan.Artists.Count > 0) parts.Add(L.T($"アーティストの登録 {plan.Artists.Count} 人", $"subscribe to {En(plan.Artists.Count, "artist")}"));
        return string.Join(L.T("・", ", "), parts);
    }

    private static string StatusChipTitle(string tag, List<TransferItem> scope) => tag switch
    {
        "review" => L.T("要確認", "To check") + $" {scope.Count(i => !i.Excluded && !i.Manual && i.Status == MatchStatus.Review)}",
        "notfound" => L.T("見つからない", "Not found") + $" {scope.Count(i => !i.Excluded && !i.Manual && i.Status == MatchStatus.NotFound)}",
        "excluded" => L.T("外したもの", "Excluded") + $" {scope.Count(i => i.Excluded)}",
        _ => L.T("すべて", "All") + $" {scope.Count}",
    };

    private void RenderChips(List<TransferItem> scope)
    {
        if (StatusChips.Children.Count == 0)
        {
            foreach (var tag in new[] { "all", "review", "notfound", "excluded" })
            {
                var chip = new ToggleButton { Tag = tag, CornerRadius = new CornerRadius(14), Padding = new Thickness(12, 4, 12, 5) };
                // Click でなく Checked で受ける（UI Automation の切り替え（ナレーターなど）は Click を起こさず、印だけ変わって一覧が変わらなかった）
                chip.Checked += (_, _) => { if (statusFilter != tag) SetStatusFilter(tag); };
                chip.Unchecked += (_, _) => { if (statusFilter == tag) chip.IsChecked = true; };
                StatusChips.Children.Add(chip);
            }
            foreach (var (tag, title) in new[] { ("all", L.T("すべての種類", "All kinds")), ("track", L.T("曲", "Songs")), ("album", L.T("アルバム", "Albums")), ("artist", L.T("アーティスト", "Artists")) })
            {
                var chip = new ToggleButton { Tag = tag, Content = title, CornerRadius = new CornerRadius(14), Padding = new Thickness(12, 4, 12, 5) };
                chip.Checked += (_, _) => { if (kindFilter != tag) SetKindFilter(tag); };
                chip.Unchecked += (_, _) => { if (kindFilter == tag) chip.IsChecked = true; };
                KindChips.Children.Add(chip);
            }
        }
        foreach (var chip in StatusChips.Children.OfType<ToggleButton>())
        {
            chip.Content = StatusChipTitle((string)chip.Tag, scope);
            chip.IsChecked = (string)chip.Tag == statusFilter;
        }
        foreach (var chip in KindChips.Children.OfType<ToggleButton>())
        {
            var tag = (string)chip.Tag;
            chip.IsChecked = tag == kindFilter;
            // その種類が無ければ出さない
            chip.Visibility = Ui.Show(tag == "all" || scope.Any(i => KindTag(i.Kind) == tag));
        }
    }

    private static string KindTag(TransferKind kind) => kind switch { TransferKind.Album => "album", TransferKind.Artist => "artist", _ => "track" };

    private void SetStatusFilter(string tag)
    {
        statusFilter = tag;
        foreach (var chip in StatusChips.Children.OfType<ToggleButton>()) chip.IsChecked = (string)chip.Tag == tag;
        ApplyFilter();
    }

    private void SetKindFilter(string tag)
    {
        kindFilter = tag;
        foreach (var chip in KindChips.Children.OfType<ToggleButton>()) chip.IsChecked = (string)chip.Tag == tag;
        ApplyFilter();
    }

    private void ApplyFilter()
    {
        ReviewList.ItemsSource = rows.Where(r =>
            (kindFilter == "all" || KindTag(r.Item.Kind) == kindFilter)
            && statusFilter switch
            {
                "review" => !r.Item.Excluded && !r.Item.Manual && r.Item.Status == MatchStatus.Review,
                "notfound" => !r.Item.Excluded && !r.Item.Manual && r.Item.Status == MatchStatus.NotFound,
                "excluded" => r.Item.Excluded,
                _ => true,
            }).ToList();
    }

    /// <summary>候補の補足（アーティスト・アルバムなど、曲は長さと種類、点）。</summary>
    internal static string CandidateSubtitle(MatchCandidate c)
    {
        var kind = c.Kind switch
        {
            Song.KindMusicVideo => L.T("MV", "Music video"),
            Song.KindUserVideo => L.T("動画", "Video"),
            _ => null,
        };
        return string.Join(" • ", new[] { c.Subtitle, kind, c.DurationSeconds is { } d ? Ui.Time(d) : null, $"{Math.Round(c.Score * 100)}%" }.Where(s => !string.IsNullOrEmpty(s)));
    }

    private void OnRowChoose(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is not TransferRow row) return;
        var menu = new MenuFlyout();
        foreach (var c in row.Item.Candidates)
        {
            var option = new RadioMenuFlyoutItem
            {
                Text = Short($"{c.Title} — {CandidateSubtitle(c)}"),
                GroupName = "candidates",
                IsChecked = !row.Item.Excluded && row.Item.ChosenId == c.Id,
            };
            option.Click += (_, _) => Choose(row, c);
            menu.Items.Add(option);
        }
        if (row.Item.Candidates.Count > 0) menu.Items.Add(new MenuFlyoutSeparator());
        if (row.Item.Kind == TransferKind.Track && !row.Item.Excluded && row.Item.Chosen is { } chosen)
        {
            var play = new MenuFlyoutItem { Text = L.T("選んでいる曲を再生", "Play the chosen song"), Icon = new FontIcon { Glyph = "" } };
            play.Click += (_, _) => AppServices.Player.PlaySong(new Song(chosen.Id, chosen.Title, [], null, chosen.DurationSeconds, chosen.ThumbnailUrl, chosen.Kind));
            menu.Items.Add(play);
        }
        var search = new MenuFlyoutItem { Text = L.T("YouTube Music で探す…", "Search YouTube Music…"), Icon = new FontIcon { Glyph = "" } };
        search.Click += async (_, _) => await SearchDialogAsync(row);
        menu.Items.Add(search);
        var exclude = new MenuFlyoutItem
        {
            Text = row.Item.Excluded ? L.T("移す（外さない）", "Move it (don't exclude)") : L.T("外す（移さない）", "Exclude (don't move)"),
            Icon = new FontIcon { Glyph = row.Item.Excluded ? "" : "" },
        };
        exclude.Click += (_, _) =>
        {
            row.Item.Excluded = !row.Item.Excluded;
            Changed(row);
        };
        menu.Items.Add(exclude);
        menu.ShowAt((FrameworkElement)sender, new FlyoutShowOptions { Placement = FlyoutPlacementMode.BottomEdgeAlignedRight });
    }

    /// <summary>メニューの1行は折り返さないので、長いものは切る（窓からはみ出さない）。</summary>
    private static string Short(string text) => text.Length <= 72 ? text : text[..71] + "…";

    private void Choose(TransferRow row, MatchCandidate candidate)
    {
        row.Item.Choose(candidate);
        Changed(row);
    }

    private void Changed(TransferRow row)
    {
        row.Refresh();
        Transfer.Save();
        RenderReviewSummary();
    }

    /// <summary>YouTube Music を手で検索して選ぶ。</summary>
    private async Task SearchDialogAsync(TransferRow row)
    {
        var item = row.Item;
        var box = new AutoSuggestBox
        {
            Text = item.Kind switch
            {
                TransferKind.Track => TransferMatcher.SongQuery(item.AsTrack()),
                TransferKind.Album => TransferMatcher.AlbumQuery(item.AsAlbum()),
                _ => item.Title,
            },
            QueryIcon = new SymbolIcon(Symbol.Find),
        };
        var list = new ListView { SelectionMode = ListViewSelectionMode.Single, Height = 360, Margin = new Thickness(-12, 0, -12, 0) };
        var status = new TextBlock { Style = (Style)Application.Current.Resources["SubtleTextStyle"], TextWrapping = TextWrapping.Wrap, MaxLines = 0 };
        var content = new StackPanel { Spacing = 8, MinWidth = 440 };
        content.Children.Add(new TextBlock { Text = row.SourceTitle + " — " + row.SourceSubtitle, TextWrapping = TextWrapping.Wrap });
        content.Children.Add(box);
        content.Children.Add(status);
        content.Children.Add(list);
        var dialog = new ContentDialog
        {
            Title = L.T("YouTube Music で探す", "Search YouTube Music"),
            Content = content,
            PrimaryButtonText = L.T("選ぶ", "Choose"),
            CloseButtonText = L.T("キャンセル", "Cancel"),
            DefaultButton = ContentDialogButton.Primary,
            IsPrimaryButtonEnabled = false,
            XamlRoot = XamlRoot,
        };
        List<MatchCandidate> found = [];
        async Task RunAsync(string query)
        {
            if (query.Trim().Length == 0) return;
            status.Text = L.T("探しています…", "Searching…");
            list.Items.Clear();
            dialog.IsPrimaryButtonEnabled = false;
            var result = await Transfer.SearchAsync(item, query.Trim());
            found = result ?? [];
            status.Text = result is null ? L.T("YouTube Music につながりませんでした", "Couldn't reach YouTube Music") : found.Count == 0 ? L.T("見つかりませんでした", "No results") : "";
            foreach (var c in found) list.Items.Add(CandidateRow(c, item.Kind == TransferKind.Artist));
        }
        // AutoSuggestBox の送信は日本語の変換の確定では起きない
        box.QuerySubmitted += async (_, args) => await RunAsync(args.QueryText);
        list.SelectionChanged += (_, _) => dialog.IsPrimaryButtonEnabled = list.SelectedIndex >= 0;
        list.DoubleTapped += (_, _) =>
        {
            if (list.SelectedIndex < 0) return;
            Choose(row, found[list.SelectedIndex]);
            dialog.Hide();
        };
        dialog.Opened += async (_, _) => await RunAsync(box.Text);
        if (await dialog.ShowAsync() == ContentDialogResult.Primary && list.SelectedIndex >= 0 && list.SelectedIndex < found.Count)
            Choose(row, found[list.SelectedIndex]);
    }

    private static Grid CandidateRow(MatchCandidate c, bool round)
    {
        var grid = new Grid { ColumnSpacing = 10, Padding = new Thickness(0, 4, 0, 4) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(40) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var cover = new Border { Width = 40, Height = 40, CornerRadius = Ui.Radius(round), Background = (Brush)Application.Current.Resources["ControlFillColorSecondaryBrush"], Child = new Image { Source = Ui.Small(c.ThumbnailUrl), Stretch = Stretch.UniformToFill } };
        grid.Children.Add(cover);
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        text.Children.Add(new TextBlock { Text = c.Title, TextTrimming = TextTrimming.CharacterEllipsis });
        text.Children.Add(new TextBlock { Text = CandidateSubtitle(c), Style = (Style)Application.Current.Resources["SubtleTextStyle"], TextTrimming = TextTrimming.CharacterEllipsis });
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        return grid;
    }

    private void OnBackToChoose(object sender, RoutedEventArgs e)
    {
        if (Transfer.Session is not { } s) return;
        s.Stage = TransferStage.Loaded;
        Transfer.SelectionChanged();
    }

    // --- 書き込む ---

    /// <summary>何をいくつ書くかを出して確かめてから書く（本人のライブラリに残り、まとめては戻せない）。</summary>
    private async void OnWrite(object sender, RoutedEventArgs e) => await ConfirmAndWriteAsync();

    internal async Task ConfirmAndWriteAsync(bool skipConfirm = false)
    {
        if (Transfer.Session is not { } s) return;
        var plan = TransferPlan.Of(s);
        if (plan.IsEmpty) return;
        if (!skipConfirm)
        {
            var lines = new StackPanel { Spacing = 6, MaxWidth = 480 };
            void Line(string text, bool subtle = false) => lines.Children.Add(new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Style = subtle ? (Style)Application.Current.Resources["SubtleTextStyle"] : null, MaxLines = 0 });
            foreach (var p in plan.Playlists.Take(5))
                Line(p.ExistingPlaylistId is null
                    ? L.T($"プレイリスト「{p.Name}」を作って {p.VideoIds.Count} 曲を入れる", $"Create playlist “{p.Name}” with {L.Songs(p.VideoIds.Count)}")
                    : L.T($"作ったプレイリスト「{p.Name}」の足りない曲を足す", $"Add missing songs to the playlist “{p.Name}”"));
            if (plan.Playlists.Count > 5) Line(L.T($"ほか {plan.Playlists.Count - 5} 個のプレイリスト", $"and {En(plan.Playlists.Count - 5, "more playlist")}"));
            if (plan.Likes.Count > 0) Line(L.T($"{plan.Likes.Count} 曲を高く評価する", $"Like {L.Songs(plan.Likes.Count)}"));
            if (plan.Albums.Count > 0) Line(L.T($"アルバム {plan.Albums.Count} 枚をライブラリに保存する", $"Save {En(plan.Albums.Count, "album")} to your library"));
            if (plan.Artists.Count > 0) Line(L.T($"アーティスト {plan.Artists.Count} 人を登録する", $"Subscribe to {En(plan.Artists.Count, "artist")}"));
            Line(L.T("YouTube Music のライブラリに残り、wave からまとめて元に戻すことはできません。前からあるもの（評価済みの曲など）は書きません。途中で止めても、続きから行えます",
                "These stay in your YouTube Music library and wave can't undo them all at once. Items you already have are left alone. You can stop and continue later"), subtle: true);
            var dialog = new ContentDialog
            {
                Title = L.T("YouTube Music に書き込みますか？", "Write to YouTube Music?"),
                Content = lines,
                PrimaryButtonText = L.T("書き込む", "Write"),
                CloseButtonText = L.T("キャンセル", "Cancel"),
                DefaultButton = ContentDialogButton.Close,
                XamlRoot = XamlRoot,
            };
            if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        }
        await Transfer.WriteAsync();
    }

    private void RenderResult(TransferSession s)
    {
        var summary = TransferRunner.Summarize(s);
        var remaining = TransferPlan.Of(s);
        ResultTitle.Text = s.Stage == TransferStage.Writing || !remaining.IsEmpty
            ? L.T("途中まで書き込みました", "Partly written")
            : L.T("書き込みました", "Written");
        ResultLines.Children.Clear();
        void Line(string text) => ResultLines.Children.Add(new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap });
        var by = summary.ByKind;
        if (summary.Playlists > 0)
            Line(L.T($"プレイリスト {summary.Playlists} 個を作り、{summary.PlaylistSongs} 曲を入れました", $"Created {En(summary.Playlists, "playlist")} with {L.Songs(summary.PlaylistSongs)}")
                + (summary.PlaylistMissing > 0 ? L.T($"（{summary.PlaylistMissing} 曲は入りませんでした）", $" ({L.Songs(summary.PlaylistMissing)} couldn't be added)") : ""));
        if (by.TryGetValue("like", out var like))
            Line(L.T($"高く評価: {like.Written} 曲", $"Liked: {L.Songs(like.Written)}") + (like.Already > 0 ? L.T($"（前から評価済み {like.Already} 曲）", $" ({like.Already} already liked)") : ""));
        if (by.TryGetValue("album", out var album))
            Line(L.T($"アルバムの保存: {album.Written} 枚", $"Albums saved: {album.Written}") + (album.Already > 0 ? L.T($"（前から保存済み {album.Already} 枚）", $" ({album.Already} already saved)") : ""));
        if (by.TryGetValue("artist", out var artist))
            Line(L.T($"アーティストの登録: {artist.Written} 人", $"Artists subscribed: {artist.Written}") + (artist.Already > 0 ? L.T($"（前から登録済み {artist.Already} 人）", $" ({artist.Already} already subscribed)") : ""));
        if (summary.Failed > 0) Line(L.T($"書けなかったもの: {summary.Failed} 件。「続ける」でもう一度書きます", $"Not written: {summary.Failed}. Choose Continue to try again"));
        if (ResultLines.Children.Count == 0) Line(L.T("まだ何も書いていません", "Nothing has been written yet"));
        ContinueButton.Visibility = Ui.Show(!remaining.IsEmpty);
    }

    private void OnBackToReview(object sender, RoutedEventArgs e)
    {
        if (Transfer.Session is not { } s) return;
        s.Stage = TransferStage.Review;
        Transfer.SelectionChanged();
    }

    private void OnFinish(object sender, RoutedEventArgs e)
    {
        Transfer.Discard();
        Nav.Settings();
    }

#if DEBUG
    // --- 開発用（DebugCommands から） ---

    public void DebugFilter(string status, string kind)
    {
        SetStatusFilter(status);
        SetKindFilter(kind);
    }

    public string DebugState => $"rows={rows.Count} shown={(ReviewList.ItemsSource as System.Collections.ICollection)?.Count} status={statusFilter} kind={kindFilter}";

    /// <summary>見えている行の index 番目の候補の candidate 番目を選ぶ（-1 で外す）。</summary>
    public void DebugChoose(int index, int candidate)
    {
        if ((ReviewList.ItemsSource as List<TransferRow>) is not { } shown || index >= shown.Count) return;
        var row = shown[index];
        if (candidate < 0)
        {
            row.Item.Excluded = true;
            Changed(row);
        }
        else if (candidate < row.Item.Candidates.Count) Choose(row, row.Item.Candidates[candidate]);
    }

    public void DebugOpenMenu(int index)
    {
        if (ReviewList.ContainerFromIndex(index) is ListViewItem { ContentTemplateRoot: Grid grid } && grid.Children.OfType<Button>().LastOrDefault() is { } button)
            OnRowChoose(button, new RoutedEventArgs());
    }

    public void DebugSearch(int index)
    {
        if ((ReviewList.ItemsSource as List<TransferRow>) is { } shown && index < shown.Count) _ = SearchDialogAsync(shown[index]);
    }

    public void DebugConfirm() => _ = ConfirmAndWriteAsync();
#endif
}
