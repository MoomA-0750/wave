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
            case "pane":
                window.DebugPane(arg);
                break;
            case "fullscreen":
                window.DebugFullScreen(arg == "on");
                break;
            case "video":
                window.DebugVideo(arg == "on");
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
                        AiMessage.Assistant a => $"assistant chars={a.Text.Length}",
                        AiMessage.Action a => "action " + a.Text,
                        AiMessage.Songs s => $"songs n={s.Items.Count}",
                        AiMessage.Failure f => "failure " + f.Text,
                        _ => "?",
                    });
                break;
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
            case "queue-dump":
                AppPaths.Log($"debug queue index={player.Index} " + string.Join(" | ", Enumerable.Range(Math.Max(0, player.Index), Math.Min(8, Math.Max(0, player.Queue.Count - player.Index))).Select(i => (player.IsUserAdded(i) ? "U:" : "") + player.Queue[i].Title)));
                break;
            case "queue-clear":
                player.ClearUserQueue();
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
            case "now":
                AppPaths.Log($"debug now title={player.Current?.Title} position={player.Position:0} playing={player.IsPlaying} remote={player.IsRemote}");
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
            case "playlist-create":
            {
                var r = await AppServices.Music.CreatePlaylistAsync(arg);
                AppPaths.Log($"debug playlist create ok={r.IsOk}");
                break;
            }
            case "playlists":
                await AppServices.Library.RefreshAsync();
                AppPaths.Log("debug playlists " + string.Join(" | ", AppServices.Library.Editable.Select(p => p.Title)));
                break;
            case "releases-check":
                await AppServices.Releases.CheckAsync();
                break;
        }
    }
}
#endif
