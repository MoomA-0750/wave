using Microsoft.UI.Dispatching;
using Wave.Core.Music;
using Wave.Core.Net;
using Wave.Core.Transfer;

namespace Wave.App.Services;

/// <summary>
/// Spotify から移す作業（設定の「Spotify から移す」）。読み込み・突き合わせ・書き込みを裏で行い、ページを離れても続ける。
/// 作業の全部（読んだもの・選び・進み具合）はデータの場所の spotify-transfer.json に保存し、閉じても続きから行える（ログアウトで消す）。
/// 記録には件数と状態だけを書く（曲名などは書かない）。
/// </summary>
public sealed class SpotifyTransfer(DispatcherQueue ui)
{
    private static readonly string FilePath = Path.Combine(AppPaths.Data, "spotify-transfer.json");

    public TransferSession? Session { get; private set; }
    /// <summary>今している作業（"read"・"login"・"match"・"write"）。していなければ null。</summary>
    public string? Busy { get; private set; }
    public TransferProgress? Progress { get; private set; }
    /// <summary>最後の作業が止まった理由と、画面に出す知らせ。</summary>
    public TransferStop LastStop { get; private set; }
    public string? Message { get; private set; }

    /// <summary>作業の状態が変わった（UI のスレッドで呼ぶ）。</summary>
    public event Action? Changed;
    /// <summary>突き合わせ（"match"）か書き込み（"write"）が終わった・止まった（UI のスレッドで呼ぶ）。窓を閉じていたら通知に使う。</summary>
    public event Action<string, TransferStop>? Finished;

    /// <summary>時間のかかる作業（突き合わせ・書き込み）をしているか。窓を閉じても裏で続ける。</summary>
    public bool RunsLong => Busy is "match" or "write";

    /// <summary>残りのおよその時間（今の作業で20件以上進んでから、その速さで見積もる）。分からなければ null。</summary>
    public TimeSpan? Remaining
    {
        get
        {
            if (Progress is not { Total: > 0 } p || paceFrom is not { } from || p.Stage != from.Stage) return null;
            var done = p.Done - from.Done;
            if (done < 20) return null;
            var per = (DateTimeOffset.UtcNow - from.At).TotalSeconds / done;
            return TimeSpan.FromSeconds(per * Math.Max(0, p.Total - p.Done));
        }
    }

    private (string Stage, int Done, DateTimeOffset At)? paceFrom;

    private CancellationTokenSource? cancel;

    /// <summary>
    /// 突き合わせの検索（ログインした通信。ログインしない検索は、同じ語でも曲の一覧に目当ての曲が出ないことがあった）。
    /// 検索の間だけ「曲名を元の表記で」を入れる（Spotify の名前は元の表記なので、洋楽がカタカナ・邦楽がローマ字で返ると比べられない）。
    /// </summary>
    private static async Task<T> OriginalAsync<T>(Func<Task<T>> work)
    {
        InnerTubeClient.OriginalTitlesOverride = true;
        return await work();
    }

    /// <summary>
    /// 突き合わせの検索に使う、ログインしない通信。本人のライブラリで続けて検索すると、YouTube がアカウントごと一時的に断り（403）、
    /// ふだんの再生・検索まで止まったので、突き合わせはアカウントに響かないログインしない検索で行う（書き込みと、見直しの手での検索はログインした通信）。
    /// </summary>
    private static readonly Lazy<MusicRepository> AnonymousMusic = new(() => new MusicRepository(new InnerTubeClient(AppServices.Http, L.Hl)));

    /// <summary>突き合わせをログインしない検索で行うか（開発用の命令 transfer-search-login で切り替えて比べる）。</summary>
    public static bool AnonymousSearch = true;

#if DEBUG
    public IEnumerable<(string, MusicRepository)> DebugRepositories()
    {
        yield return ("main", AppServices.Music);
        yield return ("main-original", AppServices.Music);
        yield return ("anon-original", AnonymousMusic.Value);
    }
#endif

    /// <summary>突き合わせの速さ（検索の間・何件ごとに何秒休むか）。開発用の命令 transfer-pace で替えて、断られない速さを探す。</summary>
    public static (int GapMs, int Batch, int PauseSeconds) Pace = (2000, 50, 120);

    private TransferRunner Runner()
    {
        var runner = new TransferRunner(new MusicTransferWriter(AppServices.Music, AnonymousSearch ? AnonymousMusic.Value : AppServices.Music), Save)
        {
            SearchGap = TimeSpan.FromMilliseconds(Pace.GapMs),
            BatchSize = Pace.Batch,
            BatchPause = TimeSpan.FromSeconds(Pace.PauseSeconds),
        };
        runner.Progress += p => ui.TryEnqueue(() =>
        {
            // 速さは段階（曲・アルバム…）ごとに、その段階の最初の知らせから測る
            if (paceFrom is not { } from || from.Stage != p.Stage) paceFrom = (p.Stage, p.Done, DateTimeOffset.UtcNow);
            if (p.Stage == "match" && p.Done % 100 == 0 && p.Done > 0 && p.WaitSeconds is null && Progress?.Done != p.Done)
                AppPaths.Log($"transfer match progress done={p.Done} total={p.Total}");
            Progress = p;
            Changed?.Invoke();
        });
        return runner;
    }

    public void Restore()
    {
        Session = TransferSession.Load(FilePath);
        // 書いている・突き合わせている途中で閉じたものは、その段階の「止めた」ところから
        if (Session is not null) AppPaths.Log($"transfer restored stage={Session.Stage} items={Session.Items.Count} done={Session.Done.Count}");
    }

    private void Save(TransferSession session)
    {
        try
        {
            session.Save(FilePath);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            AppPaths.Log($"transfer save failed type={e.GetType().Name}");
        }
    }

    public void Save()
    {
        if (Session is not null) Save(Session);
    }

    private void Set(string? busy, string? message = null)
    {
        Busy = busy;
        Message = message;
        if (busy is not null)
        {
            Progress = null;
            paceFrom = null;
        }
        Changed?.Invoke();
    }

    /// <summary>作業を止める（突き合わせ・書き込みは止めたところまでを保存する）。</summary>
    public void Stop() => cancel?.Cancel();

    /// <summary>作業を捨てる（保存したものも消す）。</summary>
    public void Discard()
    {
        cancel?.Cancel();
        Session = null;
        Progress = null;
        Message = null;
        LastStop = TransferStop.None;
        try
        {
            File.Delete(FilePath);
        }
        catch (IOException)
        {
        }
        Changed?.Invoke();
    }

    /// <summary>データのダウンロード（ZIP・JSON・展開したフォルダ）を読む。</summary>
    public async Task LoadExportAsync(string path)
    {
        if (Busy is not null) return;
        Set("read");
        SpotifyLibrary? library = null;
        try
        {
            library = await Task.Run(() => SpotifyExportReader.ReadPath(path));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or InvalidDataException)
        {
            AppPaths.Log($"transfer read failed type={e.GetType().Name}");
        }
        if (library is null || library.IsEmpty)
        {
            Set(null, library is null
                ? L.T("ファイルを読めませんでした", "Couldn't read the file")
                : L.T("プレイリスト・お気に入りの曲・アルバム・アーティストが見つかりませんでした。Spotify のデータのダウンロードの ZIP（YourLibrary.json・Playlist1.json が入ったもの）を選んでください", "No playlists, liked songs, albums or artists were found. Choose the ZIP from Spotify's data download (it contains YourLibrary.json and Playlist1.json)"));
            return;
        }
        Start(library, "export", Path.GetFileName(path));
        Set(null);
    }

    private void Start(SpotifyLibrary library, string source, string? name)
    {
        Session = TransferSession.From(library, source, name, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        Session.Stage = TransferStage.Loaded;
        LastStop = TransferStop.None;
        Save(Session);
        AppPaths.Log($"transfer loaded source={source} playlists={library.Playlists.Count} unreadable={library.Playlists.Count(p => !p.Readable)} tracks={library.Playlists.Sum(p => p.Tracks.Count)} liked={library.LikedTracks.Count} albums={library.Albums.Count} artists={library.Artists.Count} problems={library.Problems.Count}");
    }

    /// <summary>Spotify にログインして Web API から読む（ブラウザで許可してもらい、127.0.0.1 の決まったポートで戻りを受ける）。</summary>
    public async Task LoginAndReadAsync(string clientId)
    {
        if (Busy is not null) return;
        cancel = new CancellationTokenSource(TimeSpan.FromMinutes(10));
        var token = cancel.Token;
        Set("login");
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        SpotifyToken? spotifyToken;
        try
        {
            using var receiver = new LoopbackReceiver();
            var verifier = SpotifyAuth.CreateVerifier();
            var state = SpotifyAuth.CreateState();
            var url = SpotifyAuth.AuthorizeUrl(clientId, receiver.RedirectUri, SpotifyAuth.Challenge(verifier), state);
            await Windows.System.Launcher.LaunchUriAsync(new Uri(url));
            var html = "<!doctype html><meta charset=utf-8><title>wave</title><body style=\"font-family:sans-serif;padding:2em\">"
                + L.T("wave に戻ってください。このタブは閉じてかまいません。", "Return to wave. You can close this tab.") + "</body>";
            var query = await receiver.WaitAsync(html, token);
            if (query.GetValueOrDefault("state") != state || query.GetValueOrDefault("code") is not { Length: > 0 } code)
            {
                AppPaths.Log($"transfer login refused error={query.GetValueOrDefault("error") ?? "state"}");
                Set(null, L.T("Spotify でログインが許可されませんでした", "Spotify sign-in wasn't allowed"));
                return;
            }
            spotifyToken = await SpotifyAuth.ExchangeCodeAsync(http, clientId, code, receiver.RedirectUri, verifier, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), token);
        }
        catch (OperationCanceledException)
        {
            Set(null, L.T("ログインを止めました", "Sign-in cancelled"));
            return;
        }
        catch (System.Net.Sockets.SocketException e)
        {
            AppPaths.Log($"transfer login listen failed code={e.SocketErrorCode}");
            Set(null, L.T($"ログインの戻り先を開けませんでした（ポート {SpotifyAuth.CallbackPort} をほかのアプリが使っているかもしれません）", $"Couldn't open the sign-in return address (another app may be using port {SpotifyAuth.CallbackPort})"));
            return;
        }
        if (spotifyToken is null)
        {
            Set(null, L.T("Spotify のログインを完了できませんでした。Client ID と Redirect URI（http://127.0.0.1:43819/callback）を確かめてください", "Couldn't finish signing in to Spotify. Check the Client ID and the Redirect URI (http://127.0.0.1:43819/callback)"));
            return;
        }
        AppPaths.Log("transfer login ok");
        Set("read");
        var client = new SpotifyApiClient(http, clientId, spotifyToken, () => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        SpotifyLibrary? library;
        try
        {
            library = await client.ReadLibraryAsync(true, true, true, true, p => ui.TryEnqueue(() =>
            {
                Progress = new TransferProgress("read-" + p.Stage, p.Count, 0);
                Changed?.Invoke();
            }), token);
        }
        catch (OperationCanceledException)
        {
            Set(null, L.T("読み込みを止めました", "Reading stopped"));
            return;
        }
        if (library is null)
        {
            Set(null, L.T("Spotify から読めませんでした。このアプリの利用者に自分のアカウントを足しているか（Development Mode は5人まで）確かめてください", "Couldn't read from Spotify. Check that your account is added as a user of the app (Development Mode allows 5 users)"));
            return;
        }
        Start(library, "api", "Spotify");
        Set(null);
    }

    /// <summary>移すものの選びを変えた（保存する）。</summary>
    public void SelectionChanged()
    {
        Save();
        Changed?.Invoke();
    }

    /// <summary>突き合わせる（済んだものは飛ばし、止めたところから続ける）。</summary>
    public async Task MatchAsync()
    {
        if (Busy is not null || Session is not { } session) return;
        cancel = new CancellationTokenSource();
        session.Stage = TransferStage.Matching;
        Set("match");
        var runner = Runner();
        var started = DateTimeOffset.UtcNow;
        var before = session.ItemsInScope().Count(i => i.Status != MatchStatus.Pending);
        var stop = await Task.Run(() => OriginalAsync(() => runner.MatchAsync(session, cancel.Token)));
        var searched = session.ItemsInScope().Count(i => i.Status != MatchStatus.Pending) - before;
        AppPaths.Log($"transfer match pace anonymous={AnonymousSearch} gap={Pace.GapMs} batch={Pace.Batch} pause={Pace.PauseSeconds} searched={searched} minutes={(DateTimeOffset.UtcNow - started).TotalMinutes:F1} throttles={runner.Throttles} refusals={runner.Refusals} last-failure={runner.LastFailure?.Kind}/{runner.LastFailure?.Status} by-album={runner.MatchedByAlbum}");
        LastStop = stop;
        var items = session.ItemsInScope();
        AppPaths.Log($"transfer match stop={stop} matched={items.Count(i => i.Status == MatchStatus.Matched)} review={items.Count(i => i.Status == MatchStatus.Review)} notfound={items.Count(i => i.Status == MatchStatus.NotFound)} pending={items.Count(i => i.Status == MatchStatus.Pending)}");
        if (stop == TransferStop.None) session.Stage = TransferStage.Review;
        Save(session);
        Set(null, StopMessage(stop));
        Finished?.Invoke("match", stop);
    }

    /// <summary>YouTube Music に書く（書く前の確認は画面がする）。</summary>
    public async Task WriteAsync()
    {
        if (Busy is not null || Session is not { } session) return;
        if (!AppServices.Client.IsLoggedIn)
        {
            Set(null, L.T("YouTube Music にログインしてください", "Sign in to YouTube Music"));
            return;
        }
        cancel = new CancellationTokenSource();
        session.Stage = TransferStage.Writing;
        Set("write");
        var stop = await Task.Run(() => Runner().WriteAsync(session, cancel.Token));
        LastStop = stop;
        var summary = TransferRunner.Summarize(session);
        AppPaths.Log($"transfer write stop={stop} playlists={summary.Playlists} songs={summary.PlaylistSongs} missing={summary.PlaylistMissing} written={summary.Written} already={summary.Already} failed={summary.Failed}");
        session.Stage = TransferStage.Finished;
        Save(session);
        // 作ったプレイリスト・保存したアルバム・登録したアーティストをライブラリにすぐ出す
        if (summary.Written > 0) _ = AppServices.Library.RefreshAsync();
        Set(null, StopMessage(stop));
        Finished?.Invoke("write", stop);
    }

    private static string? StopMessage(TransferStop stop) => stop switch
    {
        TransferStop.Cancelled => L.T("止めました。「続ける」で続きから行えます", "Stopped. Choose Continue to pick up where you left off"),
        TransferStop.NotLoggedIn => L.T("YouTube Music のログインが要ります。ログインしてから続けてください", "You need to sign in to YouTube Music. Sign in, then continue"),
        TransferStop.Network => L.T("YouTube Music につながりませんでした。少し待ってから続けてください", "Couldn't reach YouTube Music. Wait a moment, then continue"),
        _ => null,
    };

    /// <summary>手で検索して候補を出す（見直しの「YouTube Music で探す」）。点は自動の突き合わせと同じ付け方。</summary>
    public async Task<List<MatchCandidate>?> SearchAsync(TransferItem item, string query)
    {
        return await OriginalAsync(() => SearchCoreAsync(item, query));
    }

    private static async Task<List<MatchCandidate>?> SearchCoreAsync(TransferItem item, string query)
    {
        var repo = AppServices.Music;
        switch (item.Kind)
        {
            case TransferKind.Track:
                var songs = await repo.SearchSongsAsync(query);
                return songs.IsOk ? TransferMatcher.RankSongs(item.AsTrack(), songs.Value!.Songs).OrderByDescending(c => c.Score).ToList() : null;
            case TransferKind.Album:
                var albums = await repo.SearchAlbumsAsync(query);
                return albums.IsOk ? TransferMatcher.RankAlbums(item.AsAlbum(), albums.Value!.Items) : null;
            default:
                var artists = await repo.SearchArtistsAsync(query);
                return artists.IsOk ? TransferMatcher.RankArtists(item.AsArtist(), artists.Value!.Items) : null;
        }
    }
}
