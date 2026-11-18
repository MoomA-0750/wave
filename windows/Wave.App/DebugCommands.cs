#if DEBUG
using Microsoft.UI.Xaml;
using Wave.App.Services;
using Wave.Core.Music;

namespace Wave.App;

/// <summary>
/// 開発用（Debug ビルドだけ）: データの場所の debug-command.txt に書いた命令を行い、結果を記録に書く（Android の DebugActions と同じ考え）。
/// 画面の操作を外から確かめるためのもの。1行に1命令。読んだらファイルを消す。
/// </summary>
public static class DebugCommands
{
    private static readonly string FilePath = Path.Combine(AppPaths.Data, "debug-command.txt");

    // 参照を持たないとタイマーが捨てられて止まる
    private static Microsoft.UI.Dispatching.DispatcherQueueTimer? timer;

    public static void Start(MainWindow window)
    {
        timer = window.DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(500);
        timer.Tick += async (_, _) =>
        {
            if (!File.Exists(FilePath)) return;
            string[] lines;
            try
            {
                lines = File.ReadAllLines(FilePath);
                File.Delete(FilePath);
            }
            catch (IOException)
            {
                return;
            }
            foreach (var line in lines.Where(l => l.Length > 0))
            {
                try
                {
                    AppPaths.Log("debug command=" + line.Split(' ')[0]);
                    await RunAsync(window, line);
                }
                catch (Exception e)
                {
                    AppPaths.Log($"debug command failed type={e.GetType().Name}");
                }
            }
        };
        timer.Start();
    }

    private static async Task RunAsync(MainWindow window, string line)
    {
        var parts = line.Split(' ', 2);
        var arg = parts.Length > 1 ? parts[1] : "";
        var player = AppServices.Player;
        switch (parts[0])
        {
            case "wait":
                await Task.Delay(int.Parse(arg));
                break;
            case "home":
                Nav.Home();
                break;
            case "settings":
                Nav.Settings();
                break;
            case "devices":
                Nav.Devices();
                break;
            case "liked":
                Nav.LikedSongs();
                break;
            case "album":
                Nav.Album(arg);
                break;
            case "artist":
                Nav.Artist(arg);
                break;
            case "playlist":
                Nav.Playlist(new PlaylistSummary(arg, arg, null, null));
                break;
            case "podcast":
                // ポッドキャストの番組のページ（MPSP…）
                Nav.Podcast(new PodcastSummary(arg, arg, null, null));
                break;
            case "feed":
            case "feed-play":
            {
                // RSS の番組（ADR-0012）を目録で探し、最初の番組のフィードを読む。件数と形・時間だけを記録する（題などは書かない）。
                // feed-play は最新のエピソードから流す
                var term = arg.Length > 0 ? arg : "rebuild";
                var watch = System.Diagnostics.Stopwatch.StartNew();
                var found = await AppServices.Feeds.SearchAsync(term);
                AppPaths.Log($"debug feed search={found?.Count} rss={found?.Count(p => Feed.IsFeedShow(p.BrowseId))} images={found?.Count(p => p.ThumbnailUrl is not null)} ms={watch.ElapsedMilliseconds}");
                if (found?.FirstOrDefault() is not { } first || Feed.FeedUrlOf(first.BrowseId) is not { } url) break;
                watch.Restart();
                var read = await AppServices.Feeds.ShowAsync(url);
                var readMs = watch.ElapsedMilliseconds;
                if (read is not FeedResult.Ok ok)
                {
                    var failed = (FeedResult.Failed)read;
                    AppPaths.Log($"debug feed failed {failed.Failure} status={failed.Status} ms={readMs}");
                    break;
                }
                watch.Restart();
                var feedPage = ok.Show.ToPage(null, positions: AppServices.FeedLibrary.State.Positions.GetValueOrDefault);
                var pageMs = watch.ElapsedMilliseconds;
                watch.Restart();
                var all = ok.Show.AllEpisodes;
                var allMs = watch.ElapsedMilliseconds;
                AppPaths.Log($"debug feed read ms={readMs} page ms={pageMs} all ms={allMs} items={ok.Show.ItemCount} episodes={all.Count} durations={all.Count(e => e.DurationSeconds is not null)} dates={all.Count(e => e.PublishedEpochSeconds is not null)} descriptions={all.Count(e => e.Description is not null)} image={ok.Show.ThumbnailUrl is not null} first-page={feedPage.Episodes.Count} more={feedPage.Continuation is not null} newest-first={all.Count < 2 || (all[0].PublishedEpochSeconds ?? 0) >= (all[^1].PublishedEpochSeconds ?? 0)}");
                if (parts[0] == "feed-play" && feedPage.Episodes.Count > 0) player.PlayList(feedPage.Episodes.Select(e => e.Song).ToList(), 0, null);
                break;
            }
            case "library-kind":
                AppPaths.Log($"debug library-kind entries={window.DebugLibraryKind(arg)} rss={AppServices.FeedLibrary.State.Subscriptions.Count}");
                break;
            case "podcast-save":
                if (Nav.Frame.Content is Views.PodcastPage savePage) savePage.DebugSave();
                break;
            case "album-save":
                if (Nav.Frame.Content is Views.CollectionPage albumSavePage) albumSavePage.DebugSave();
                break;
            case "album-save-state":
                AppPaths.Log($"debug album-save-state {(Nav.Frame.Content as Views.CollectionPage)?.DebugSaveState}");
                break;
            case "podcast-episode":
                if (Nav.Frame.Content is Views.PodcastPage playPage) playPage.DebugPlay(int.Parse(arg));
                break;
            case "podcast-more":
                if (Nav.Frame.Content is Views.PodcastPage morePage) await morePage.DebugMoreAsync();
                break;
            case "podcast-state":
                AppPaths.Log($"debug podcast-state {(Nav.Frame.Content as Views.PodcastPage)?.DebugState}");
                break;
            case "feed-state":
            {
                // 登録と聴いたところの件数、今の曲がエピソードならその覚えている位置
                var state = AppServices.FeedLibrary.State;
                var current = Feed.AudioUrlOf(player.Current?.VideoId) is { } audio ? state.Positions.GetValueOrDefault(audio) : null;
                AppPaths.Log($"debug feed-state subscriptions={state.Subscriptions.Count} positions={state.Positions.Count} external={Feed.IsExternal(player.Current?.VideoId)} playing={player.IsPlaying} position={player.Position:0} duration={player.Duration:0} saved={current?.PositionSeconds} finished={current?.Finished} lyrics={player.Lyrics?.GetType().Name} like={player.Like} video={player.Video is not null} queue={player.Queue.Count} source={player.SourceId is not null}");
                break;
            }
            case "pane":
                window.DebugPane(arg);
                break;
            case "back":
                if (Nav.Frame.CanGoBack) Nav.Frame.GoBack();
                break;
            case "shelf-right":
                // ページの最初の棚を右へ送る（送るボタンを押したのと同じ）
                Shelves(Nav.Frame.Content as DependencyObject).FirstOrDefault()?.DebugRight();
                break;
            case "fullscreen":
                window.DebugFullScreen(arg == "on");
                break;
            case "video":
                window.DebugVideo(arg == "on");
                break;
            case "video-expand":
                window.DebugVideoExpand();
                break;
            case "video-only":
                window.DebugVideoOnly(arg == "on");
                break;
            case "video-pip":
                window.DebugVideoPip(arg != "off");
                break;
            case "window-fullscreen":
                window.DebugWindowFullScreen(arg == "on");
                break;
            case "ai-pair":
                AppPaths.Log("debug ai pair ok=" + AiChat.Pair(arg));
                break;
            case "ai":
                window.DebugPane("ai");
                AppServices.Ai.Send(arg);
                while (AppServices.Ai.Running) await Task.Delay(500);
                foreach (var m in AppServices.Ai.Messages)
                    AppPaths.Log("debug ai " + m switch
                    {
                        AiMessage.User => "user",
                        AiMessage.Assistant a => $"assistant chars={a.Text.Length} stamps=[{string.Join(",", Wave.Core.Ai.AiText.Timestamps(a.Text).Select(t => t.Label))}] synced={a.VideoId is not null}",
                        AiMessage.Action a => "action " + a.Text,
                        AiMessage.Songs s => $"songs n={s.Items.Count}",
                        AiMessage.Failure f => "failure " + f.Text,
                        _ => "?",
                    });
                break;
            // Claude の会話の履歴: 一覧・新しい会話・開く（番号）・消す（番号）・最後の答えの時刻へ飛ぶ（番号）
            case "ai-history":
                for (var i = 0; i < AppServices.Ai.History.Conversations.Count; i++)
                {
                    var c = AppServices.Ai.History.Conversations[i];
                    AppPaths.Log($"debug ai history {i} title_chars={c.Title.Length} messages={c.Messages.Count} current={c.Id == AppServices.Ai.ConversationId}");
                }
                AppPaths.Log($"debug ai history n={AppServices.Ai.History.Conversations.Count}");
                break;
            case "ai-new":
                AppServices.Ai.Clear();
                break;
            case "ai-open":
                if (AppServices.Ai.History.Conversations.ElementAtOrDefault(int.TryParse(arg, out var openIndex) ? openIndex : 0) is { } opened) AppServices.Ai.Open(opened.Id);
                AppPaths.Log($"debug ai open messages={AppServices.Ai.Messages.Count}");
                break;
            case "ai-delete":
                if (AppServices.Ai.History.Conversations.ElementAtOrDefault(int.TryParse(arg, out var deleteIndex) ? deleteIndex : 0) is { } deleted) AppServices.Ai.Delete(deleted.Id);
                break;
            case "ai-seek":
            {
                var answer = AppServices.Ai.Messages.OfType<AiMessage.Assistant>().LastOrDefault(a => Wave.Core.Ai.AiText.Timestamps(a.Text).Count > 0);
                var stamp = answer is null ? null : Wave.Core.Ai.AiText.Timestamps(answer.Text).ElementAtOrDefault(int.TryParse(arg, out var stampIndex) ? stampIndex : 0);
                AppPaths.Log($"debug ai seek stamp={stamp?.Label} synced={answer?.VideoId is not null}");
                if (answer?.VideoId is { } id && stamp is not null) AiChat.Seek(id, stamp.PositionMs);
                break;
            }
            case "membership-toggle":
            {
                // 追加の画面で、名前の一致するプレイリストの行を押したのと同じ（入っていれば外し、無ければ足す）
                if (player.Current is not { } song || AppServices.Library.Editable.FirstOrDefault(p => p.Title == arg) is not { } target) break;
                var status = await AppServices.Library.Membership.CheckAsync(target.PlaylistId, song.VideoId, default);
                if (status == PlaylistMembership.Status.In) await SongMenu.RemoveAsync(target, song);
                else await SongMenu.AddAsync(target, song);
                AppPaths.Log($"debug membership-toggle was={status} inPlaylist={player.InPlaylist}");
                break;
            }
            case "song-card":
            {
                // 検索の先頭の曲を、棚のカードを押したのと同じ開き方で開く
                var result = await AppServices.Music.SearchSongsAsync(arg);
                if (result.IsOk && result.Value!.Songs.Count > 0) Nav.OpenCard(new HomeItem.SongItem(result.Value.Songs[0]));
                AppPaths.Log($"debug song-card page={Nav.Frame.CurrentSourcePageType?.Name} playing={player.Current?.VideoId is not null}");
                break;
            }
            case "search-play":
            {
                var result = await AppServices.Music.SearchSongsAsync(arg);
                if (result.IsOk && result.Value!.Songs.Count > 0) player.PlayList(result.Value.Songs.Take(5).ToList());
                break;
            }
            case "artist-play":
            {
                // アーティストのページの再生ボタンと同じ流し方（人気の曲から始め、「すべての曲」の残りを足す）
                var artist = await AppServices.Music.ArtistAsync(arg);
                if (!artist.IsOk) break;
                var all = artist.Value!.AllSongsPlaylistId is { } allId ? AppServices.Music.FullPlaylistAsync(allId) : null;
                await Views.ArtistPage.PlayArtistAsync(player, arg, artist.Value.TopSongs, 0, null, all);
                AppPaths.Log($"debug artist-play queue={player.Queue.Count} artists={string.Join(",", player.Queue.Select(s => s.Artists.FirstOrDefault()?.Name).Distinct())}");
                break;
            }
            case "queue-add":
            {
                // 検索の先頭の曲を「キューに追加」する
                var result = await AppServices.Music.SearchSongsAsync(arg);
                if (result.IsOk && result.Value!.Songs.Count > 0) player.AddToQueue(result.Value.Songs[0]);
                break;
            }
            case "queue-next":
            {
                var result = await AppServices.Music.SearchSongsAsync(arg);
                if (result.IsOk && result.Value!.Songs.Count > 0) player.PlayNext(result.Value.Songs[0]);
                break;
            }
            case "live-play":
            {
                // ライブの検索の先頭を、検索の結果を選んだのと同じ流し方で流す
                var result = await AppServices.Music.SearchLiveAsync(arg);
                AppPaths.Log($"debug live-search ok={result.IsOk} n={result.Value?.Songs.Count} live={result.Value?.Songs.Count(s => s.IsLive)} more={result.Value?.Continuation is not null}");
                if (result.IsOk && result.Value!.Songs.Count > 0) player.PlaySong(result.Value.Songs[0]);
                break;
            }
            case "live-more":
            {
                var first = await AppServices.Music.SearchLiveAsync(arg);
                if (first.Value?.Continuation is not { } token) break;
                var next = await AppServices.Music.SearchLiveAsync(arg, token);
                AppPaths.Log($"debug live-more ok={next.IsOk} n={next.Value?.Songs.Count} more={next.Value?.Continuation is not null}");
                break;
            }
            case "play-id":
                // ライブの印の無い曲として流す（WEB_REMIX の /player がライブを示したら HLS に切り替わるか）
                player.PlaySong(new Song(arg, arg, [], null, null, null));
                break;
            case "lofi":
            {
                // 気分とジャンルの Lo-Fi のタイルを押したのと同じ
                var moods = await AppServices.Music.MoodsAsync();
                var tile = moods.Value?.SelectMany(s => s.Categories).FirstOrDefault(c => c.Params == LofiGenre.Params);
                AppPaths.Log($"debug lofi tile={tile is not null} first={moods.Value?.FirstOrDefault(s => s.Categories.Any(c => c.Params == LofiGenre.Params))?.Categories.FirstOrDefault()?.Title}");
                Nav.Mood(tile ?? LofiGenre.Category);
                var page = await AppServices.Music.MoodCategoryAsync(LofiGenre.Params);
                AppPaths.Log($"debug lofi page ok={page.IsOk} " + string.Join(" | ", page.Value?.Shelves.Select(s => $"{s.Title}={s.Items.Count}") ?? []));
                break;
            }
            case "search-tab":
            {
                // 検索欄に打って、結果のタブを選んだのと同じ（例: search-tab live lofi）
                var words = arg.Split(' ', 2);
                window.SearchFor(words.Length > 1 ? words[1] : "");
                await Task.Delay(300);
                if (Nav.Frame.Content is Views.SearchPage page)
                {
                    page.DebugTab(words[0]);
                    for (var i = 0; i < 80 && page.DebugRows(words[0]) == 0; i++) await Task.Delay(250);
                    AppPaths.Log($"debug search-tab rows={page.DebugRows(words[0])}" + (words[0] == "lyrics" ? $" top={page.DebugLyricsTop}" : "") + $" empty={page.DebugEmpty}");
                }
                break;
            }
            case "live-state":
                AppPaths.Log($"debug live-state live={player.IsLive} playing={player.IsPlaying} loading={player.IsLoading} position={player.Position:0} duration={player.Duration:0} video={player.Video is not null} lyrics={player.Lyrics is not null} queue={player.Queue.Count} title={player.Current?.Title}");
                break;
            case "seek":
                player.Seek(double.Parse(arg));
                break;
            case "seek-end":
                // 曲の終わりの arg 秒前へ（曲の終わりの動きを確かめる）
                player.Seek(Math.Max(0, player.Duration - double.Parse(arg)));
                break;
            case "repeat":
                // リピートを直に置く（off・all・one。ボタンと違い、ラジオへのつなぎ直しは起こさない）
                player.Repeat = Enum.Parse<RepeatMode>(arg, ignoreCase: true);
                break;
            case "album-play":
            {
                // アルバムのカードの再生ボタンと同じ流し方（出どころをアルバムにする）。arg は検索の言葉（先頭の曲のアルバム）
                var found = await AppServices.Music.SearchSongsAsync(arg);
                if (found.Value?.Songs.FirstOrDefault(s => s.Album?.BrowseId is not null) is not { } hit) break;
                var albumId = hit.Album!.BrowseId!;
                var album = await AppServices.Music.AlbumAsync(albumId);
                if (album.IsOk) player.PlayList(album.Value!.Songs, 0, albumId);
                AppPaths.Log($"debug album-play ok={album.IsOk} id={albumId} songs={album.Value?.Songs.Count} artist={hit.Artists.FirstOrDefault()?.BrowseId}");
                break;
            }
            case "next":
                player.Next();
                break;
            case "previous":
                player.Previous();
                break;
            case "queue-dump":
                AppPaths.Log($"debug queue index={player.Index} " + string.Join(" | ", Enumerable.Range(Math.Max(0, player.Index), Math.Min(8, Math.Max(0, player.Queue.Count - player.Index))).Select(i => (player.IsUserAdded(i) ? "U:" : "") + player.Queue[i].Title)));
                break;
            case "queue-clear":
                player.ClearUserQueue();
                break;
            // 「次に流れる曲」の消去（元の流れの曲を外して流れを止める。ほかの端末で鳴っている間は queue_edit の clear_flow で送る）
            case "queue-clear-flow":
                player.ClearFlow();
                break;
            // キューの並べ替え・外す（ほかの端末で鳴っている間は、その端末へ queue_edit で送る）。位置はキュー全体の番号
            case "queue-move":
                {
                    var moveArgs = arg.Split(' ', StringSplitOptions.RemoveEmptyEntries);
                    if (moveArgs.Length == 2 && int.TryParse(moveArgs[0], out var moveFrom) && int.TryParse(moveArgs[1], out var moveTo)) player.MoveUpcoming(moveFrom, moveTo);
                }
                break;
            case "volume":
                if (double.TryParse(arg, System.Globalization.CultureInfo.InvariantCulture, out var debugVolume)) player.Volume = debugVolume;
                break;
            case "queue-remove":
                if (int.TryParse(arg, out var removeAt)) player.RemoveAt(removeAt);
                break;
            case "shuffle":
                player.ToggleShuffle();
                break;
            case "pause":
                if (player.IsPlaying) player.TogglePlay();
                break;
            case "play":
                if (!player.IsPlaying) player.TogglePlay();
                break;
            case "lyrics-probe":
                // 開発用: 今の曲の時刻なしの歌詞（YouTube Music）の取得だけを試す（歌詞の失敗の切り分け）
                if (player.Current is { } probe)
                {
                    var plain = await AppServices.Music.PlainLyricsAsync(probe.VideoId);
                    AppPaths.Log($"debug lyrics-probe plainOk={plain.IsOk} failure={plain.Failure} hasText={plain.Value is not null}");
                }
                break;
            case "now":
                AppPaths.Log($"debug now title={player.Current?.Title} position={player.Position:0} playing={player.IsPlaying} remote={player.IsRemote} lyrics={player.Lyrics?.GetType().Name}");
                break;
            case "playlist-delete":
            {
                // 名前の一致する自分のプレイリストを消す（確かめで作ったものの後片付け）
                await AppServices.Library.RefreshAsync();
                foreach (var p in AppServices.Library.Editable.Where(p => p.Title == arg).ToList())
                {
                    var id = p.PlaylistId.StartsWith("VL", StringComparison.Ordinal) ? p.PlaylistId[2..] : p.PlaylistId;
                    var r = await AppServices.Client.PostAsync("playlist/delete", new System.Text.Json.Nodes.JsonObject { ["playlistId"] = id });
                    AppPaths.Log($"debug playlist delete ok={r.IsOk}");
                }
                await AppServices.Library.RefreshAsync();
                break;
            }
            case "queue-save-dialog":
                // 「キューをプレイリストとして保存」のボタンを押したのと同じ（名前の画面を出す）
                _ = Dialogs.SaveQueueAsync();
                break;
            case "queue-save":
                // キューの保存の画面で「保存」を押したのと同じ（名前は arg）
                await Dialogs.SaveQueueAsync(arg, QueueSave.Songs(player.Queue));
                break;
            case "suggestions":
                if (Nav.Frame.Content is Views.CollectionPage suggestPage) AppPaths.Log($"debug suggestions count={suggestPage.DebugSuggestionCount}");
                break;
            case "suggestion-add":
                if (Nav.Frame.Content is Views.CollectionPage addPage) addPage.DebugAddSuggestion();
                break;
            case "suggestions-refresh":
                if (Nav.Frame.Content is Views.CollectionPage refreshPage) refreshPage.DebugRefreshSuggestions();
                break;
            case "playlist-create":
            {
                var r = await AppServices.Music.CreatePlaylistAsync(arg);
                AppPaths.Log($"debug playlist create ok={r.IsOk}");
                break;
            }
            case "nav-time":
            {
                // 移る処理そのもの（Navigate の中）と、次に描かれるまでの時間を測る（arg: settings / transfer / back）
                var sw = System.Diagnostics.Stopwatch.StartNew();
                if (arg == "settings") Nav.Settings(); else if (arg == "transfer") Nav.Transfer(); else Nav.Frame.GoBack();
                var navigate = sw.ElapsedMilliseconds;
                Nav.Frame.UpdateLayout();
                var layout = sw.ElapsedMilliseconds;
                void OnRendering(object? o, object e)
                {
                    Microsoft.UI.Xaml.Media.CompositionTarget.Rendering -= OnRendering;
                    AppPaths.Log($"debug nav-time {arg} navigate={navigate}ms layout={layout - navigate}ms first-frame={sw.ElapsedMilliseconds}ms");
                }
                Microsoft.UI.Xaml.Media.CompositionTarget.Rendering += OnRendering;
                break;
            }
            case "ui-lag":
            {
                // UI のスレッドが止まった時間を記録する（16ms ごとの刻みが 60ms を超えて遅れたら書く。arg は秒）
                var watch = System.Diagnostics.Stopwatch.StartNew();
                var last = 0L;
                var lagTimer = window.DispatcherQueue.CreateTimer();
                lagTimer.Interval = TimeSpan.FromMilliseconds(16);
                lagTimer.Tick += (_, _) =>
                {
                    var now = watch.ElapsedMilliseconds;
                    if (now - last > 60 && last > 0) AppPaths.Log($"debug ui-lag gap={now - last}ms at={now} gc0={GC.CollectionCount(0)} gc1={GC.CollectionCount(1)} gc2={GC.CollectionCount(2)} pause={GC.GetTotalPauseDuration().TotalMilliseconds:0}ms");
                    last = now;
                    if (now > int.Parse(arg) * 1000) lagTimer.Stop();
                };
                lagTimer.Start();
                AppPaths.Log("debug ui-lag start");
                break;
            }
            case "transfer":
                // Spotify から移すページを開く
                Nav.Transfer();
                break;
            case "transfer-load":
                // データのダウンロードの ZIP・JSON・フォルダを読む（arg は Windows の場所）
                await AppServices.Transfer.LoadExportAsync(arg);
                break;
            case "transfer-include":
            {
                // 移す種類を切り替える（例: transfer-include liked off）
                if (AppServices.Transfer.Session is not { } ts) break;
                var on = arg.EndsWith(" on", StringComparison.Ordinal);
                switch (arg.Split(' ')[0])
                {
                    case "playlists": ts.IncludePlaylists = on; break;
                    case "liked": ts.IncludeLiked = on; break;
                    case "albums": ts.IncludeAlbums = on; break;
                    case "artists": ts.IncludeArtists = on; break;
                }
                AppServices.Transfer.SelectionChanged();
                break;
            }
            case "transfer-playlist-only":
            {
                // 番号のプレイリストだけを移す（ほかのプレイリストは外す）
                if (AppServices.Transfer.Session is not { } ts) break;
                var keep = int.Parse(arg);
                for (var i = 0; i < ts.Playlists.Count; i++) ts.Playlists[i].Include = i == keep && ts.Playlists[i].Readable;
                AppServices.Transfer.SelectionChanged();
                break;
            }
            case "transfer-match":
                await AppServices.Transfer.MatchAsync();
                break;
            case "transfer-rematch":
                // 突き合わせをやり直す（本人の選びは残す）
                foreach (var item in AppServices.Transfer.Session?.Items ?? []) item.Status = Wave.Core.Transfer.MatchStatus.Pending;
                if (AppServices.Transfer.Session is { } rs) rs.Stage = Wave.Core.Transfer.TransferStage.Loaded;
                await AppServices.Transfer.MatchAsync();
                break;
            case "transfer-state":
            {
                var ts = AppServices.Transfer.Session;
                var scope = ts?.ItemsInScope() ?? [];
                var plan = ts is null ? null : Wave.Core.Transfer.TransferPlan.Of(ts);
                AppPaths.Log($"debug transfer stage={ts?.Stage} busy={AppServices.Transfer.Busy} stop={AppServices.Transfer.LastStop} scope={scope.Count} "
                    + string.Join(" ", scope.GroupBy(i => i.Status).Select(g => $"{g.Key}={g.Count()}"))
                    + $" excluded={scope.Count(i => i.Excluded)} manual={scope.Count(i => i.Manual)} plan-playlists={plan?.Playlists.Count} plan-songs={plan?.PlaylistSongs} likes={plan?.Likes.Count} albums={plan?.Albums.Count} artists={plan?.Artists.Count} done={ts?.Done.Count}"
                    + $" page={(Nav.Frame.Content as Views.TransferPage)?.DebugState}");
                break;
            }
            case "transfer-filter":
            {
                // 見直しの絞り込み（例: transfer-filter review track）
                var f = arg.Split(' ');
                (Nav.Frame.Content as Views.TransferPage)?.DebugFilter(f[0], f.Length > 1 ? f[1] : "all");
                break;
            }
            case "transfer-choose":
            {
                // 見えている行の番号と候補の番号（-1 で外す）
                var f = arg.Split(' ');
                (Nav.Frame.Content as Views.TransferPage)?.DebugChoose(int.Parse(f[0]), int.Parse(f[1]));
                break;
            }
            case "transfer-menu":
                (Nav.Frame.Content as Views.TransferPage)?.DebugOpenMenu(int.Parse(arg));
                break;
            case "transfer-search":
                (Nav.Frame.Content as Views.TransferPage)?.DebugSearch(int.Parse(arg));
                break;
            case "transfer-confirm":
                (Nav.Frame.Content as Views.TransferPage)?.DebugConfirm();
                break;
            case "transfer-write":
                // 確かめの画面を出さずに書く（開発用）
                await AppServices.Transfer.WriteAsync();
                break;
            case "transfer-probe":
            {
                // 突き合わせの検索の通り方を比べる（件数と、曲名が一致した数だけ）
                var title = Wave.Core.Transfer.TransferMatcher.Fold(arg.Split(' ').Last());
                foreach (var (name, repo) in AppServices.Transfer.DebugRepositories())
                {
                    Wave.Core.Net.InnerTubeClient.OriginalTitlesOverride = name == "main-original" ? true : null;
                    var r = await repo.SearchSongsAsync(arg);
                    Wave.Core.Net.InnerTubeClient.OriginalTitlesOverride = null;
                    AppPaths.Log($"debug transfer-probe {name} ok={r.IsOk} failure={r.Failure} status={r.Status} songs={r.Value?.Songs.Count} same-title={r.Value?.Songs.Count(x => Wave.Core.Transfer.TransferMatcher.Fold(x.Title) == title)}");
                }
                break;
            }
            case "transfer-add-test":
            {
                // 作ったプレイリストに、高く評価する曲をまとめて足す（入っている曲は飛ばされるか）。前後の曲数だけを書く
                if (AppServices.Transfer.Session is not { } ts || ts.Playlists.FirstOrDefault(p => p.CreatedPlaylistId is not null)?.CreatedPlaylistId is not { } pid) break;
                var ids = ts.LikedKeys.Select(k => ts.Item(k)?.Target).OfType<string>().ToList();
                var before = await AppServices.Music.ScanPlaylistAsync(pid);
                var ok = await AppServices.Music.AddManyToPlaylistAsync(pid, ids);
                var after = await AppServices.Music.ScanPlaylistItemsAsync(pid);
                AppPaths.Log($"debug transfer-add-test ok={ok} sent={ids.Count} new={ids.Count(i => before?.Contains(i) == false)} before={before?.Count} after={after?.Count} entries={after?.Values.Sum(v => v.Count)}");
                break;
            }
            case "transfer-stop":
                AppServices.Transfer.Stop();
                break;
            case "transfer-revert":
            {
                // 確かめで書いたものを元に戻す（wave が書いたものだけ。前からあったものは触らない）
                if (AppServices.Transfer.Session is not { } ts) break;
                var music = AppServices.Music;
                foreach (var p in ts.Playlists.Where(p => p.CreatedPlaylistId is not null))
                {
                    var id = p.CreatedPlaylistId!.StartsWith("VL", StringComparison.Ordinal) ? p.CreatedPlaylistId[2..] : p.CreatedPlaylistId;
                    var r = await AppServices.Client.PostAsync("playlist/delete", new System.Text.Json.Nodes.JsonObject { ["playlistId"] = id });
                    AppPaths.Log($"debug transfer revert playlist ok={r.IsOk}");
                }
                foreach (var (key, _) in ts.Done.Where(d => d.Value == Wave.Core.Transfer.WriteOutcome.Written).ToList())
                {
                    var opKind = key[..key.IndexOf(':')];
                    var id = key[(key.IndexOf(':') + 1)..];
                    var ok = opKind switch
                    {
                        "like" => (await music.SetLikedAsync(id, false)).IsOk,
                        "album" => (await music.AlbumAsync(id)) is { IsOk: true } a && a.Value!.SavePlaylistId is { } sp && (await music.SetPlaylistSavedAsync(sp, false)).IsOk,
                        "artist" => (await music.ArtistAsync(id)) is { IsOk: true } ar && ar.Value!.ChannelId is { } ch && (await music.SetSubscribedAsync(ch, false)).IsOk,
                        _ => true,
                    };
                    AppPaths.Log($"debug transfer revert {opKind} ok={ok}");
                }
                await AppServices.Library.RefreshAsync();
                break;
            }
            case "transfer-discard":
                AppServices.Transfer.Discard();
                break;
            case "playlists":
                await AppServices.Library.RefreshAsync();
                AppPaths.Log("debug playlists " + string.Join(" | ", AppServices.Library.Editable.Select(p => p.Title)));
                break;
            case "releases-check":
                await AppServices.Releases.CheckAsync();
                break;
            case "connect-offer":
                {
                    // 「端末を追加」を押したのと同じ。出た文字列（wave-pair:…）は加える端末へ渡すため、データの場所の debug-offer.txt に書く（2分で無効）
                    var connect = AppServices.Connect;
                    connect.StartOffer();
                    for (var i = 0; i < 40 && connect.PairingState.OfferText == null; i++) await Task.Delay(250);
                    if (connect.PairingState.OfferText is { } offer) File.WriteAllText(Path.Combine(AppPaths.Data, "debug-offer.txt"), offer);
                    AppPaths.Log($"debug connect offer ok={connect.PairingState.OfferText != null}");
                    break;
                }
            case "connect-accept": AppServices.Connect.Accept(arg); break;
            case "connect-code": AppServices.Connect.EnterCode(arg); break;
            case "connect-confirm": AppServices.Connect.ConfirmOnB(); break;
            case "connect-cancel": AppServices.Connect.CancelPairing(); break;
            case "connect-transfer": AppServices.Connect.Transfer(); break;
            case "connect-play":
                // デバイスの一覧で、名前の一致する端末を選んだのと同じ
                if (AppServices.Connect.Members.FirstOrDefault(m => m.Label == arg) is { } device) AppServices.Connect.PlayOnDevice(device.DeviceId);
                break;
            case "connect-kind":
                // 「表示名|種類」の端末の種類を覚え直す（wave-api の行の見た目を確かめる）
                if (arg.Split('|') is [var label, var kind]) AppServices.Connect.DebugSetKind(label, kind);
                break;
            case "connect-state":
                {
                    var c = AppServices.Connect;
                    var p = c.PairingState;
                    AppPaths.Log($"debug connect relay={c.Info.Relay} household={c.Info.InHousehold} pairing={p.Kind} code={p.Code} peer={p.PeerName} offer={p.OfferText != null} text={p.Text}");
                    foreach (var m in c.Members) AppPaths.Log($"debug connect member label={m.Label} self={m.IsSelf} online={m.Online} active={m.Active} pc={m.IsPc} controller={m.IsController} api={m.IsApi}");
                    if (c.Remote is { } r) AppPaths.Log($"debug connect remote device={r.DeviceLabel} title={r.Title} playing={r.Playing} positionMs={r.PositionMs} online={r.Online}");
                    break;
                }
            // ---- ダウンロード（ADR-0016） ----
            case "downloads":
                Nav.Downloads();
                break;
            case "downloads-category":
                // ダウンロードの画面の分類のチップ（all・frequent・likely・recommended）を選び、出た行の数
                if (Nav.Frame.Content is Views.DownloadsPage downloadsPage) AppPaths.Log($"debug downloads-category {arg} rows={downloadsPage.DebugCategory(arg)}");
                break;
            case "download-collection":
                // 開いているプレイリスト・アルバムのページのダウンロードのボタンを押す
                if (Nav.Frame.Content is Views.CollectionPage downloadPage) downloadPage.DebugDownload();
                break;
            case "download-collection-state":
                if (Nav.Frame.Content is Views.CollectionPage statePage) AppPaths.Log("debug download-collection " + statePage.DebugDownloadState);
                break;
            case "download-current":
                if (player.Current is { } currentSong) AppServices.Downloads.AddSingle(currentSong);
                break;
            case "downloads-state":
            {
                var d = AppServices.Downloads;
                var m = d.Manifest;
                AppPaths.Log($"debug downloads collections={m.Collections.Count} singles={m.Singles.Count} smart={m.Smart.Count} wanted={m.WantedIds().Count} done={d.DownloadedCount} bytes={d.UsedBytes} pending={d.PendingCount} active={d.ActiveTitle is not null} waiting={d.WaitingForNetwork} allows={d.NetworkAllows} network={NetworkState.Describe()} policy={d.Network} "
                    + string.Join(' ', m.Collections.Select(c => $"[{c.Kind} {d.StateOf(c.Id).Done}/{d.StateOf(c.Id).Total}]"))
                    + " smart:" + string.Join(',', m.Smart.GroupBy(t => t.Category).Select(g => $"{g.Key}={g.Count()}")));
                break;
            }
            case "download-network":
                // any・unmetered
                AppServices.Downloads.SetNetwork(Wave.Core.Downloads.DownloadPolicy.Parse(arg));
                break;
            case "smart-on":
                if (int.TryParse(arg, out var smartCount)) AppServices.Downloads.SetSmartCount(smartCount);
                AppServices.Downloads.SetSmart(true);
                break;
            case "smart-off":
                AppServices.Downloads.SetSmart(false);
                break;
            case "smart-refresh":
                await AppServices.Downloads.RefreshSmartAsync(force: true);
                break;
            case "downloads-sync":
                await AppServices.Downloads.RefreshAsync();
                break;
            case "play-downloaded":
            {
                // 取り終えた曲だけを、目録の順に流す
                // 取り終えた曲だけを、目録の順に流す（episodes なら RSS のエピソードだけ）
                var done = AppServices.Downloads.Manifest.WantedSongs().Where(s => AppServices.Downloads.IsDownloaded(s.VideoId) && (arg != "episodes" || Feed.IsExternal(s.VideoId))).ToList();
                if (done.Count > 0) player.PlayList(done, 0, null);
                AppPaths.Log($"debug play-downloaded songs={done.Count}");
                break;
            }
            case "play-mixed":
            {
                // 取っていない曲・取った曲の順に混ぜて流す（通信できないときに、取った曲へ飛ぶかを確かめる）
                var d = AppServices.Downloads;
                var wanted = d.Manifest.WantedSongs();
                var done = wanted.Where(s => d.IsDownloaded(s.VideoId)).Take(2).ToList();
                var (liked, _) = await AppServices.Library.LikedSongsAsync();
                var missing = liked.Where(s => !d.IsDownloaded(s.VideoId)).Take(2).ToList();
                player.PlayList([.. missing, .. done], 0, null);
                AppPaths.Log($"debug play-mixed missing={missing.Count} done={done.Count}");
                break;
            }
            case "network-fake":
                // offline・metered・off（ふりをやめる）
                NetworkState.DebugOffline = arg == "offline" ? true : null;
                NetworkState.DebugMetered = arg == "metered" ? true : null;
                NetworkState.DebugChanged();
                AppPaths.Log($"debug network-fake {NetworkState.Describe()} connected={NetworkState.IsConnected} metered={NetworkState.IsMetered}");
                break;
            case "downloads-delete-all":
                AppServices.Downloads.DeleteAll();
                break;
        }
    }

    private static IEnumerable<Controls.ShelfView> Shelves(DependencyObject? node)
    {
        if (node is null) yield break;
        if (node is Controls.ShelfView shelf) { yield return shelf; yield break; }
        for (var i = 0; i < Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChildrenCount(node); i++)
            foreach (var found in Shelves(Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChild(node, i))) yield return found;
    }
}
#endif
