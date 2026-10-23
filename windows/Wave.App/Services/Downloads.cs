using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Dispatching;
using Wave.Core.Downloads;
using Wave.Core.Music;

namespace Wave.App.Services;

/// <summary>プレイリスト・アルバム・高く評価した曲を丸ごと取っているか（ページの頭のボタン）。Done は取り終えた曲数。</summary>
public sealed record CollectionDownloadState(bool Saved, int Done, int Total)
{
    public bool Complete => Saved && Done >= Total;
}

/// <summary>
/// ダウンロード（ADR-0016）。本人が選んだもの・スマートダウンロードの曲を、消えない置き場に1つずつ取る。
/// アプリが動いている間だけ取り、起動の少しあとと6時間おきに、丸ごと取っているものを読み直し、スマートダウンロードを1日1回選び直す。
/// 使う通信は設定に従い、合わない通信の間は待って、合う通信になったら続ける。
/// </summary>
public sealed partial class Downloads : ObservableObject
{
    private static readonly TimeSpan Interval = TimeSpan.FromHours(6);
    /// <summary>通信などの失敗のあと、取り直すまでの間（通信が変わったときは待たずに取り直す）。</summary>
    private static readonly TimeSpan RetryAfter = TimeSpan.FromSeconds(60);
    private static readonly string CountsPath = Path.Combine(AppPaths.Data, "play-counts.json");
    private static readonly object CountsGate = new();

    private readonly DispatcherQueue ui;
    private readonly DownloadStore store;
    private readonly TrackDownloader downloader;
    private readonly object gate = new();
    private DownloadManifest manifest;
    private PlayCounts counts;
    private DispatcherQueueTimer? timer;
    private DispatcherQueueTimer? retryTimer;
    /// <summary>この時刻まで取り直さない曲（取れない曲は long.MaxValue。UI の糸だけで触る）。</summary>
    private readonly Dictionary<string, long> holdUntil = [];
    private Task? worker;
    private bool kickAgain;
    private CancellationTokenSource workerCancel = new();
    /// <summary>取り終えた曲（行の印・数に使う。目録か取れた曲が変わったら作り直す）。</summary>
    private HashSet<string> completed = [];

    /// <summary>目録・取れた曲が変わった（UI の糸で）。</summary>
    public event Action? Changed;

    /// <summary>取っている曲の名前（取っていなければ null）と、その進み具合（0〜1）。</summary>
    [ObservableProperty] private string? activeTitle;
    [ObservableProperty] private double activeProgress;
    /// <summary>まだ取れていない曲の数（取っている曲を含む）。</summary>
    [ObservableProperty] private int pendingCount;
    /// <summary>設定に合う通信を待っている。</summary>
    [ObservableProperty] private bool waitingForNetwork;
    /// <summary>スマートダウンロードを選び直している。</summary>
    [ObservableProperty] private bool refreshingSmart;

    public Downloads(DispatcherQueue ui)
    {
        this.ui = ui;
        store = new DownloadStore(Path.Combine(AppPaths.Data, "downloads"), NowMs);
        downloader = new TrackDownloader(AppServices.Http, store) { Log = AppPaths.Log };
        manifest = store.ReadManifest();
        counts = ReadCounts();
        RebuildCompleted();
    }

    private static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public DownloadManifest Manifest
    {
        get
        {
            lock (gate) return manifest;
        }
    }

    public DownloadNetwork Network => DownloadPolicy.Parse(AppServices.Settings.DownloadNetwork);

    /// <summary>今の通信で取ってよいか。</summary>
    public bool NetworkAllows => DownloadPolicy.Allows(Network, NetworkState.IsConnected, NetworkState.IsMetered);

    public void Start()
    {
        NetworkState.Watch();
        NetworkState.Changed += () => ui.TryEnqueue(() =>
        {
            AppPaths.Log($"downloads network {NetworkState.Describe()}");
            ClearHolds(permanentToo: false);
            Kick();
        });
        timer = ui.CreateTimer();
        // 最初は起動の30秒後（ほかの読み込みと重ねない）、その後は6時間おき
        timer.Interval = TimeSpan.FromSeconds(30);
        timer.Tick += (_, _) =>
        {
            timer.Interval = Interval;
            _ = RefreshAsync();
        };
        timer.Start();
        retryTimer = ui.CreateTimer();
        retryTimer.Interval = RetryAfter;
        retryTimer.IsRepeating = false;
        retryTimer.Tick += (_, _) => Kick();
        Kick();
    }

    /// <summary>丸ごと取っているものを読み直し、スマートダウンロードを（1日たっていれば）選び直す。</summary>
    public async Task RefreshAsync()
    {
        await SyncCollectionsAsync();
        await RefreshSmartAsync(force: false);
        Kick();
    }

    // ---- 調べる ----

    /// <summary>取り終えた曲か（行の印）。</summary>
    public bool IsDownloaded(string videoId) => completed.Contains(videoId);

    /// <summary>取り終えた曲の形式（鳴らすとき。ファイルを確かめる）。</summary>
    public DownloadedFile? Local(string videoId) => store.Completed(videoId);

    public string BytesPath(string videoId) => store.BytesPath(videoId);

    /// <summary>何かの理由で取っている（取ろうとしている）曲か。</summary>
    public bool IsWanted(string videoId) => Manifest.IsWanted(videoId);

    /// <summary>メニューの「ダウンロードを削除」で外せる曲か（1曲ずつ取った曲・スマートダウンロードの曲）。</summary>
    public bool IsRemovable(string videoId)
    {
        var m = Manifest;
        return m.IsSingle(videoId) || m.IsSmart(videoId);
    }

    /// <summary>取ったファイルの大きさの合計（途中の分も含む）。</summary>
    public long UsedBytes => store.TotalBytes;

    /// <summary>取り終えた曲の数。</summary>
    public int DownloadedCount => completed.Count;

    public CollectionDownloadState StateOf(string id)
    {
        if (Manifest.Collection(id) is not { } collection) return new CollectionDownloadState(false, 0, 0);
        var songs = collection.Songs.Where(DownloadManifest.Downloadable).Select(s => s.VideoId).Distinct().ToList();
        return new CollectionDownloadState(true, songs.Count(completed.Contains), songs.Count);
    }

    // ---- 変える ----

    public void AddCollection(string kind, string id, string title, string? thumbnailUrl, IReadOnlyList<Song> songs) =>
        Mutate(m => m.WithCollection(new DownloadCollection(kind, id, title, thumbnailUrl, NowMs(), songs.ToList())), "add-collection");

    public void RemoveCollection(string id) => Mutate(m => m.WithoutCollection(id), "remove-collection");

    /// <summary>丸ごと取っているものを読み直した曲の並びに合わせる（増えた曲を取り、外れた曲を消す。ほかの理由でも取っている曲は残す）。</summary>
    public void SyncCollection(string id, IReadOnlyList<Song> songs)
    {
        if (Manifest.Collection(id) is null) return;
        CollectionDiff? diff = null;
        Mutate(m =>
        {
            var (next, d) = m.WithCollectionSongs(id, songs);
            diff = d;
            return next;
        }, "sync-collection");
        AppPaths.Log($"downloads sync added={diff?.Added.Count} removed={diff?.Removed.Count} songs={songs.Count}");
    }

    public void AddSingle(Song song, bool episode = false) => Mutate(m => m.WithSingle(song, NowMs(), episode), "add-single");

    public void RemoveSingle(string videoId) => Mutate(m => m.WithoutSingle(videoId), "remove-single");

    /// <summary>スマートダウンロードの入り切り（切ると、その曲は消す。手で取った曲は残す）。</summary>
    public void SetSmart(bool on)
    {
        if (AppServices.Settings.SmartDownloads == on) return;
        AppServices.Settings.SmartDownloads = on;
        AppServices.Settings.Save();
        if (on) _ = RefreshSmartAsync(force: true);
        else Mutate(m => m.WithSmart([], 0), "smart-off");
        Changed?.Invoke();
    }

    public void SetSmartCount(int count)
    {
        count = SmartDownloads.NormalizeCount(count);
        if (AppServices.Settings.SmartDownloadCount == count) return;
        AppServices.Settings.SmartDownloadCount = count;
        AppServices.Settings.Save();
        if (AppServices.Settings.SmartDownloads) _ = RefreshSmartAsync(force: true);
    }

    public void SetNetwork(DownloadNetwork network)
    {
        AppServices.Settings.DownloadNetwork = DownloadPolicy.Format(network);
        AppServices.Settings.Save();
        Kick();
    }

    /// <summary>「ダウンロードをすべて削除」。丸ごと・1曲ずつ・スマートダウンロードの全部を外し、ファイルも消す（設定は残す）。</summary>
    public void DeleteAll()
    {
        workerCancel.Cancel();
        workerCancel = new CancellationTokenSource();
        lock (gate)
        {
            manifest = DownloadManifest.Empty;
            store.Clear();
        }
        holdUntil.Clear();
        AppPaths.Log("downloads delete-all");
        NotifyChanged();
    }

    /// <summary>ログアウト: 取ったものを全部消す（ほかのアカウントに前のアカウントの音を残さない。ADR-0016 の 9）。この端末の再生回数も消す。</summary>
    public void Clear()
    {
        DeleteAll();
        lock (gate) counts = PlayCounts.Empty;
        try
        {
            File.Delete(CountsPath);
        }
        catch (IOException)
        {
        }
    }

    private void Mutate(Func<DownloadManifest, DownloadManifest> change, string reason)
    {
        int purged;
        int wanted;
        lock (gate)
        {
            manifest = change(manifest);
            try
            {
                store.WriteManifest(manifest);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                AppPaths.Log($"downloads manifest write failed type={e.GetType().Name}");
            }
            var ids = manifest.WantedIds();
            wanted = ids.Count;
            purged = store.Purge(ids);
        }
        AppPaths.Log($"downloads {reason} wanted={wanted} purged={purged}");
        NotifyChanged();
        Kick();
    }

    private void RebuildCompleted() => completed = Manifest.WantedSongs().Select(s => s.VideoId).Where(store.IsComplete).ToHashSet();

    private void NotifyChanged()
    {
        RebuildCompleted();
        Changed?.Invoke();
    }

    // ---- 丸ごと取っているものの読み直し ----

    public async Task SyncCollectionsAsync()
    {
        if (!AppServices.Client.IsLoggedIn || !NetworkState.IsConnected) return;
        foreach (var collection in Manifest.Collections.ToList())
        {
            IReadOnlyList<Song>? songs = null;
            if (collection.Kind == DownloadKind.Album)
            {
                var album = await AppServices.Music.AlbumAsync(collection.Id);
                if (album.IsOk) songs = album.Value!.Songs;
            }
            else
            {
                // 最後まで読めたときだけ合わせる（途中で失敗した一覧で消さない）
                var playlist = await AppServices.Music.FullPlaylistAsync(collection.Id);
                if (playlist.IsOk && playlist.Value!.Continuation is null) songs = playlist.Value.Songs;
            }
            if (songs is not null) SyncCollection(collection.Id, songs);
            // まとめて叩かない
            await Task.Delay(300);
        }
    }

    // ---- スマートダウンロード ----

    /// <summary>
    /// スマートダウンロードを選び直す（ADR-0016 の 10〜12）。force でなければ1日1回。選び直して外れた曲は消し、新しく入った曲を取る。
    /// 再生履歴もホームも読めなかったとき（通信の失敗）は、今の曲を外さない。
    /// </summary>
    public async Task RefreshSmartAsync(bool force)
    {
        if (RefreshingSmart || !AppServices.Settings.SmartDownloads || !AppServices.Client.IsLoggedIn || !NetworkState.IsConnected) return;
        if (!force && !SmartDownloads.IsDue(Manifest.SmartRefreshedAtMs, NowMs())) return;
        RefreshingSmart = true;
        try
        {
            var count = SmartDownloads.NormalizeCount(AppServices.Settings.SmartDownloadCount);
            var music = AppServices.Music;
            var history = await music.HistoryAsync();
            var historySongs = history.IsOk ? history.Value!.Sections.SelectMany(s => s.Songs).ToList() : [];
            var (liked, _) = await AppServices.Library.LikedSongsAsync();
            List<PlayCount> local;
            lock (gate) local = counts.Entries.ToList();
            var frequent = SmartDownloads.Frequent(local, historySongs);
            var known = local.Select(c => c.Song.VideoId).Concat(historySongs.Select(s => s.VideoId)).Concat(liked.Select(s => s.VideoId)).ToHashSet();
            var radios = new List<IReadOnlyList<Song>>();
            foreach (var seed in frequent.Take(SmartDownloads.MaxSeeds))
            {
                var radio = await music.UpNextAsync(seed.VideoId, AutoRadio.PlaylistIdOf(seed.VideoId));
                if (radio.IsOk) radios.Add(radio.Value!.Songs);
            }
            var likely = SmartDownloads.Likely(radios, known);
            var home = await music.HomeAsync();
            var recommended = home.IsOk ? SmartDownloads.Recommended(home.Value!.Shelves) : [];
            // 曲の棚で足りなければ、ホームの最初のミックスの曲
            if (home.IsOk && recommended.Count < count && SmartDownloads.FirstMix(home.Value!.Shelves) is { } mix)
            {
                var mixPage = await music.PlaylistAsync(mix.PlaylistId);
                if (mixPage.IsOk) recommended = recommended.Concat(mixPage.Value!.Songs).DistinctBy(s => s.VideoId).ToList();
            }
            if (!history.IsOk && !home.IsOk)
            {
                AppPaths.Log("smart refresh skipped (history and home failed)");
                return;
            }
            var picked = SmartDownloads.Select(frequent, likely, recommended, count);
            // 1曲も選べなかったら、前に選んだ曲をそのまま残す
            if (picked.Count == 0)
            {
                AppPaths.Log("smart refresh picked nothing; keeping previous");
                return;
            }
            if (!AppServices.Settings.SmartDownloads) return;
            // 選び直したら、取れなかった曲も取り直してみる
            ClearHolds(permanentToo: true);
            Mutate(m => m.WithSmart(picked, NowMs()), "smart-refresh");
            AppPaths.Log($"smart refresh count={count} frequent={frequent.Count} likely={likely.Count} recommended={recommended.Count} picked={picked.Count} "
                + string.Join(' ', picked.GroupBy(t => t.Category).Select(g => $"{g.Key}={g.Count()}")));
        }
        catch (Exception e) when (e is HttpRequestException or IOException or TaskCanceledException)
        {
            AppPaths.Log($"smart refresh failed type={e.GetType().Name}");
        }
        finally
        {
            RefreshingSmart = false;
        }
    }

    // ---- この端末の再生回数 ----

    /// <summary>1回聴いた（再生履歴を送るのと同じ聴いた時間で数える）。</summary>
    public void RecordPlay(Song song)
    {
        PlayCounts snapshot;
        lock (gate) snapshot = counts = counts.Record(song, NowMs());
        _ = Task.Run(() =>
        {
            try
            {
                lock (CountsGate) File.WriteAllText(CountsPath, snapshot.Encode());
            }
            catch (IOException)
            {
            }
        });
    }

    private static PlayCounts ReadCounts()
    {
        try
        {
            return File.Exists(CountsPath) ? PlayCounts.Decode(File.ReadAllText(CountsPath)) : PlayCounts.Empty;
        }
        catch (IOException)
        {
            return PlayCounts.Empty;
        }
    }

    // ---- 取る ----

    /// <summary>取り残しがあれば取り始める（取っている途中なら、終わったあとにもう一度見る）。</summary>
    public void Kick()
    {
        if (worker is { IsCompleted: false })
        {
            kickAgain = true;
            return;
        }
        kickAgain = false;
        worker = RunAsync(workerCancel.Token);
    }

    /// <summary>取り残しを1つずつ取る（UI の糸で回し、取るところは裏の糸で）。</summary>
    private async Task RunAsync(CancellationToken cancel)
    {
        var failures = 0;
        var retryLater = false;
        while (!cancel.IsCancellationRequested)
        {
            var now = NowMs();
            var pending = Manifest.WantedSongs().Where(s => !store.IsComplete(s.VideoId)).ToList();
            PendingCount = pending.Count;
            var next = pending.FirstOrDefault(s => holdUntil.GetValueOrDefault(s.VideoId) <= now);
            if (next is null)
            {
                retryLater = pending.Any(s => holdUntil.GetValueOrDefault(s.VideoId) != long.MaxValue);
                if (kickAgain)
                {
                    kickAgain = false;
                    continue;
                }
                break;
            }
            if (!NetworkAllows)
            {
                if (!WaitingForNetwork) AppPaths.Log($"downloads waiting network {NetworkState.Describe()} policy={Network} pending={pending.Count}");
                WaitingForNetwork = true;
                break;
            }
            WaitingForNetwork = false;
            var song = next;
            var external = Feed.IsExternal(song.VideoId);
            // YouTube の曲はログインしてから取る（RSS のエピソードは要らない）
            if (!external && !AppServices.Client.IsLoggedIn)
            {
                holdUntil[song.VideoId] = now + (long)RetryAfter.TotalMilliseconds;
                continue;
            }
            ActiveTitle = song.Title;
            ActiveProgress = 0;
            DownloadOutcome outcome;
            try
            {
                outcome = await Task.Run(() => external
                    ? downloader.DownloadEpisodeAsync(song, AppServices.FeedHttp, AppServices.Feeds.UserAgent, () => IsWanted(song.VideoId), Progress, cancel)
                    : downloader.DownloadSongAsync(song, c => AppServices.Resolver.ResolveAsync(song.VideoId, c), AppServices.Resolver.OnStreamRejected, () => IsWanted(song.VideoId), Progress, cancel));
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception e)
            {
                AppPaths.Log($"download crashed type={e.GetType().Name}");
                outcome = DownloadOutcome.Failed;
            }
            AppPaths.Log($"download outcome={outcome} episode={external} left={pending.Count - (outcome == DownloadOutcome.Done ? 1 : 0)}");
            switch (outcome)
            {
                case DownloadOutcome.Done:
                    failures = 0;
                    NotifyChanged();
                    break;
                case DownloadOutcome.Unavailable or DownloadOutcome.Live:
                    holdUntil[song.VideoId] = long.MaxValue;
                    break;
                case DownloadOutcome.NotLoggedIn or DownloadOutcome.Failed:
                    holdUntil[song.VideoId] = now + (long)RetryAfter.TotalMilliseconds;
                    failures++;
                    break;
            }
            // 続けて失敗するなら通信が悪い: 少し待ってから続ける
            if (failures >= 3)
            {
                retryLater = true;
                break;
            }
        }
        ActiveTitle = null;
        ActiveProgress = 0;
        if (retryLater && retryTimer is { IsRunning: false }) retryTimer.Start();
        if (kickAgain && !cancel.IsCancellationRequested) ui.TryEnqueue(Kick);
    }

    /// <summary>取り直さない印を外す（permanentToo なら、取れなかった曲の印も）。</summary>
    private void ClearHolds(bool permanentToo)
    {
        foreach (var key in holdUntil.Where(h => permanentToo || h.Value != long.MaxValue).Select(h => h.Key).ToList()) holdUntil.Remove(key);
    }

    private void Progress(long have, long total) => ui.TryEnqueue(() => ActiveProgress = total > 0 ? (double)have / total : 0);
}
