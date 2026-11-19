using Wave.Core.Music;
using Wave.Core.Net;

namespace Wave.Core.Transfer;

/// <summary>YouTube Music への読み書き（試験では偽物に差し替える）。</summary>
public interface ITransferWriter
{
    Task<ApiResult<IReadOnlyList<Song>>> SearchSongsAsync(string query, CancellationToken cancel);
    /// <summary>絞り込まない検索の曲・動画（曲の検索で見つからないときの2回目）。</summary>
    Task<ApiResult<IReadOnlyList<Song>>> SearchTopSongsAsync(string query, CancellationToken cancel);
    Task<ApiResult<IReadOnlyList<AlbumSummary>>> SearchAlbumsAsync(string query, CancellationToken cancel);
    Task<ApiResult<IReadOnlyList<ArtistSummary>>> SearchArtistsAsync(string query, CancellationToken cancel);
    Task<ApiResult<string>> CreatePlaylistAsync(string title, IReadOnlyList<string> videoIds, CancellationToken cancel);
    /// <summary>ライブラリのプレイリストの名前を全部読む。読めなければ null。</summary>
    Task<HashSet<string>?> LibraryPlaylistTitlesAsync(CancellationToken cancel);
    /// <summary>アルバムのページの曲（アルバム名・アーティストが無い曲にはページのものを入れる）。</summary>
    Task<ApiResult<IReadOnlyList<Song>>> AlbumSongsAsync(string browseId, CancellationToken cancel);
    /// <summary>プレイリストの曲の ID を全部読む。読み切れなければ null。</summary>
    Task<HashSet<string>?> PlaylistVideoIdsAsync(string playlistId, CancellationToken cancel);
    /// <summary>曲をまとめて足す（入っていない曲だけを渡す）。</summary>
    Task<bool> AddToPlaylistAsync(string playlistId, IReadOnlyList<string> videoIds, CancellationToken cancel);
    /// <summary>高く評価した曲の ID を全部読む。読み切れなければ null。</summary>
    Task<HashSet<string>?> LikedVideoIdsAsync(CancellationToken cancel);
    /// <summary>1曲を高く評価しているか（一覧を読めなかったとき）。分からなければ null。</summary>
    Task<bool?> IsLikedAsync(string videoId, CancellationToken cancel);
    Task<ApiResult<bool>> SetLikedAsync(string videoId, CancellationToken cancel);
    /// <summary>アルバムを保存しているかと、保存に使うプレイリストの ID。</summary>
    Task<ApiResult<(bool? Saved, string? SavePlaylistId)>> AlbumSaveStateAsync(string browseId, CancellationToken cancel);
    Task<ApiResult<bool>> SaveAlbumAsync(string savePlaylistId, CancellationToken cancel);
    /// <summary>アーティストを登録しているかと、登録に使うチャンネルの ID。</summary>
    Task<ApiResult<(bool? Subscribed, string? ChannelId)>> ArtistSubscriptionAsync(string browseId, CancellationToken cancel);
    Task<ApiResult<bool>> SubscribeAsync(string channelId, CancellationToken cancel);
    /// <summary>「ログインが要る」と返ったあと、本当にログインが切れたか（確かめが済むまで待つ）。切れていなければ、続けての頼みに YouTube が一時的に断っただけ。</summary>
    Task<bool> LoginLostAsync(CancellationToken cancel);
}

/// <summary>MusicRepository を使う本物（検索は searchRepository、書き込みと読み直しはログインした repository）。</summary>
public sealed class MusicTransferWriter(MusicRepository repository, MusicRepository searchRepository) : ITransferWriter
{
    public async Task<ApiResult<IReadOnlyList<Song>>> SearchSongsAsync(string query, CancellationToken cancel) =>
        (await searchRepository.SearchSongsAsync(query, cancel: cancel)).Map(p => p.Songs);

    public async Task<ApiResult<IReadOnlyList<Song>>> SearchTopSongsAsync(string query, CancellationToken cancel) =>
        (await searchRepository.SearchTopSongsAsync(query, cancel)).Map(l => (IReadOnlyList<Song>)l);

    public async Task<ApiResult<IReadOnlyList<AlbumSummary>>> SearchAlbumsAsync(string query, CancellationToken cancel) =>
        (await searchRepository.SearchAlbumsAsync(query, cancel: cancel)).Map(p => p.Items);

    public async Task<ApiResult<IReadOnlyList<ArtistSummary>>> SearchArtistsAsync(string query, CancellationToken cancel) =>
        (await searchRepository.SearchArtistsAsync(query, cancel: cancel)).Map(p => p.Items);

    public Task<ApiResult<string>> CreatePlaylistAsync(string title, IReadOnlyList<string> videoIds, CancellationToken cancel) =>
        repository.CreatePlaylistAsync(title, videoIds, cancel);

    public async Task<HashSet<string>?> LibraryPlaylistTitlesAsync(CancellationToken cancel)
    {
        // 途中のページで切れた一覧では、同じ名前を見落とすので「読めなかった」とする
        var incomplete = false;
        var r = await repository.LibraryPlaylistsAsync(cancel: cancel, onIncomplete: () => incomplete = true);
        return r.IsOk && !incomplete ? r.Value!.Select(p => p.Title).ToHashSet() : null;
    }

    public Task<HashSet<string>?> PlaylistVideoIdsAsync(string playlistId, CancellationToken cancel) => repository.ScanPlaylistAsync(playlistId, cancel);

    public Task<bool> AddToPlaylistAsync(string playlistId, IReadOnlyList<string> videoIds, CancellationToken cancel) =>
        repository.AddManyToPlaylistAsync(playlistId, videoIds, cancel);

    public Task<HashSet<string>?> LikedVideoIdsAsync(CancellationToken cancel) => repository.ScanPlaylistAsync(MusicRepository.LikedSongs, cancel);

    public async Task<bool?> IsLikedAsync(string videoId, CancellationToken cancel) =>
        await repository.SongInfoAsync(videoId, cancel) is { IsOk: true } info && info.Value!.Like is { } like ? like == LikeStatus.Like : null;

    public Task<ApiResult<bool>> SetLikedAsync(string videoId, CancellationToken cancel) => repository.SetLikedAsync(videoId, true, cancel);

    public async Task<bool> LoginLostAsync(CancellationToken cancel)
    {
        // 403 のあとは裏で account_menu の確かめが走る。済むまで（最大40秒）待って、切れたと分かったときだけ true
        var session = repository.Client.Session;
        for (var i = 0; i < 40 && session.Status == SessionStatus.Suspect; i++) await Task.Delay(TimeSpan.FromSeconds(1), cancel);
        return session.Status is SessionStatus.Expired or SessionStatus.LoggedOut;
    }

    public async Task<ApiResult<IReadOnlyList<Song>>> AlbumSongsAsync(string browseId, CancellationToken cancel) =>
        (await searchRepository.AlbumAsync(browseId, cancel)).Map<IReadOnlyList<Song>>(a => a.Songs.Select(s => s with
        {
            Album = s.Album ?? new AlbumRef(a.Title, a.BrowseId),
            Artists = s.Artists.Count > 0 ? s.Artists : a.Artists,
        }).ToList());

    public async Task<ApiResult<(bool? Saved, string? SavePlaylistId)>> AlbumSaveStateAsync(string browseId, CancellationToken cancel) =>
        (await repository.AlbumAsync(browseId, cancel)).Map(a => (a.Saved, a.SavePlaylistId));

    public Task<ApiResult<bool>> SaveAlbumAsync(string savePlaylistId, CancellationToken cancel) => repository.SetPlaylistSavedAsync(savePlaylistId, true, cancel);

    public async Task<ApiResult<(bool? Subscribed, string? ChannelId)>> ArtistSubscriptionAsync(string browseId, CancellationToken cancel) =>
        (await repository.ArtistAsync(browseId, cancel)).Map(a => (a.Subscribed, a.ChannelId));

    public Task<ApiResult<bool>> SubscribeAsync(string channelId, CancellationToken cancel) => repository.SetSubscribedAsync(channelId, true, cancel);
}

/// <summary>進み具合（画面に出す）。Stage は "match"・"playlist"・"like"・"album"・"artist"。</summary>
/// <summary>進み具合。WaitSeconds は、YouTube に一時的に断られて待っている秒数（待っていなければ null）。</summary>
public sealed record TransferProgress(string Stage, int Done, int Total, string? Current = null, int? WaitSeconds = null);

/// <summary>止まった理由。</summary>
public enum TransferStop { None, Cancelled, NotLoggedIn, Network }

/// <summary>
/// 突き合わせと書き込みを行う。YouTube に止められないよう1件ずつ間を空け、失敗したら待って（2・10・30 秒）やり直す。
/// ログインが要ると言われたら止める。進み具合は session に書き、save で保存する（止めても続きから行える）。
/// </summary>
public sealed class TransferRunner(ITransferWriter writer, Action<TransferSession> save, Func<TimeSpan, CancellationToken, Task>? delay = null)
{
    private readonly Func<TimeSpan, CancellationToken, Task> delay = delay ?? Task.Delay;

    /// <summary>検索の間・書き込みの間に空ける時間。</summary>
    public TimeSpan SearchGap { get; init; } = TimeSpan.FromMilliseconds(250);
    /// <summary>この件数を探すごとに、BatchPause だけ休む（0 なら休まない）。続けての頼みが多いと YouTube が一時的に断るため。</summary>
    public int BatchSize { get; init; }
    public TimeSpan BatchPause { get; init; } = TimeSpan.Zero;
    /// <summary>同じアルバムの曲がこの数以上あれば、アルバムを1回探してページの曲目で突き合わせる（頼む回数を減らす）。0 なら使わない。</summary>
    public int AlbumGroupMin { get; init; } = 3;
    /// <summary>アルバムのページで決まった曲の数（記録に使う）。</summary>
    public int MatchedByAlbum { get; private set; }
    /// <summary>突き合わせの間に断られた回数（記録と速さの調べに使う）。</summary>
    public int Throttles { get; private set; }
    /// <summary>ログインしない検索で 403 と断られた回数（やり直さずにすぐ止める）。</summary>
    public int Refusals { get; private set; }
    /// <summary>最後に失敗した頼みの種類と HTTP の番号（止まった理由の記録用）。</summary>
    public (FailureKind? Kind, int? Status, string Op)? LastFailure { get; private set; }
    public TimeSpan WriteGap { get; init; } = TimeSpan.FromMilliseconds(700);
    public static readonly TimeSpan[] Backoff = [TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(10), TimeSpan.FromSeconds(30)];
    /// <summary>ログインは切れていないのに「ログインが要る」（403）と断られたときに待つ時間（続けての頼みが多すぎたとみなす）。尽きたら止める。</summary>
    public static readonly TimeSpan[] ThrottleBackoff = [TimeSpan.FromMinutes(1), TimeSpan.FromMinutes(2), TimeSpan.FromMinutes(5), TimeSpan.FromMinutes(5), TimeSpan.FromMinutes(10), TimeSpan.FromMinutes(10)];
    /// <summary>プレイリストを作るときに一緒に渡す曲の数と、あとから足すときの1回の数。</summary>
    public const int CreateChunk = 50;
    public const int AddChunk = 25;
    /// <summary>この数ごとに保存する（毎回書くと大きなライブラリで重い。止まったら最後に保存する）。</summary>
    private const int SaveEvery = 10;

    public event Action<TransferProgress>? Progress;

    private TransferProgress? last;
    /// <summary>今の検索の間隔。断られるたびに倍にする（3秒まで）。</summary>
    private TimeSpan? gap;
    private TimeSpan searchGap => gap ?? SearchGap;
    public static readonly TimeSpan MaxSearchGap = TimeSpan.FromSeconds(3);

    private void Report(TransferProgress p)
    {
        last = p;
        Progress?.Invoke(p);
    }

    /// <summary>失敗したら待ってやり直す。ログインが要る・形が読めないときはやり直さない。</summary>
    private async Task<ApiResult<T>> RetryAsync<T>(Func<Task<ApiResult<T>>> call, CancellationToken cancel, [System.Runtime.CompilerServices.CallerArgumentExpression(nameof(call))] string op = "")
    {
        var throttled = 0;
        for (var attempt = 0; ; attempt++)
        {
            var result = await call();
            if (result.Failure == FailureKind.NotLoggedIn && throttled < ThrottleBackoff.Length && !await writer.LoginLostAsync(cancel))
            {
                // ログインは生きている: 続けての頼みに一時的に断られただけなので、長めに待って同じ頼みをやり直す
                var wait = ThrottleBackoff[throttled++];
                Throttles++;
                gap = TimeSpan.FromTicks(Math.Min(MaxSearchGap.Ticks, Math.Max(searchGap.Ticks * 2, TimeSpan.FromMilliseconds(500).Ticks)));
                if (last is { } p) Progress?.Invoke(p with { WaitSeconds = (int)wait.TotalSeconds });
                await delay(wait, cancel);
                if (last is { } q) Progress?.Invoke(q);
                attempt = -1;
                continue;
            }
            if (!result.IsOk) LastFailure = (result.Failure, result.Status, OpName(op));
            if (result.Failure == FailureKind.Http && result.Status == 403)
            {
                // ログインしない検索で断られた: ふだんの再生・検索に響かないよう、やり直さずに止める
                Refusals++;
                return result;
            }
            if (result.IsOk || result.Failure is FailureKind.NotLoggedIn or FailureKind.Parse || attempt >= Backoff.Length) return result;
            await delay(Backoff[attempt], cancel);
        }
    }

    private async Task<T?> RetryNullAsync<T>(Func<Task<T?>> call, CancellationToken cancel) where T : class
    {
        for (var attempt = 0; ; attempt++)
        {
            var result = await call();
            if (result is not null || attempt >= Backoff.Length) return result;
            await delay(Backoff[attempt], cancel);
        }
    }

    /// <summary>移してきたと分かる印（YouTube Music に同じ名前のプレイリストが前からあるときだけ付ける）。</summary>
    public const string FromSpotifySuffix = " (from Spotify)";

    /// <summary>作るプレイリストの名前。同じ名前（前後の空白・大小文字は区別しない）が前からあれば印を付け、無ければ元の名前のまま。
    /// ライブラリを読めなかったときは、重なるかもしれないので印を付ける。</summary>
    public static string PlaylistTitle(string name, IEnumerable<string> existing, bool existingUnknown = false)
    {
        var key = name.Trim();
        return existingUnknown || existing.Any(t => string.Equals(t.Trim(), key, StringComparison.OrdinalIgnoreCase)) ? key + FromSpotifySuffix : name;
    }

    /// <summary>「() => writer.SearchSongsAsync(…)」から「SearchSongs」だけを取る。</summary>
    private static string OpName(string expression) =>
        System.Text.RegularExpressions.Regex.Match(expression, @"writer\.(\w+?)(Async)?\(") is { Success: true } m ? m.Groups[1].Value : expression;

    private static TransferStop StopOf<T>(ApiResult<T> r) => r.Failure == FailureKind.NotLoggedIn ? TransferStop.NotLoggedIn : TransferStop.Network;

    /// <summary>まだ突き合わせていないもの（Pending）を検索して候補を付ける。</summary>
    public async Task<TransferStop> MatchAsync(TransferSession session, CancellationToken cancel)
    {
        var items = session.ItemsInScope();
        var total = items.Count;
        var done = items.Count(i => i.Status != MatchStatus.Pending);
        var sinceSave = 0;
        var matched = 0;
        // 同じアルバムの曲をまとめる（アーティストとアルバム名で）
        var groups = AlbumGroupMin <= 0 ? [] : items.Where(i => i.Status == MatchStatus.Pending && i.Kind == TransferKind.Track)
            .Select(i => (Item: i, Key: AlbumKey(i.AsTrack())))
            .Where(x => x.Key is not null)
            .GroupBy(x => x.Key!)
            .Where(g => g.Count() >= AlbumGroupMin)
            .ToDictionary(g => g.Key, g => g.Select(x => x.Item).ToList());
        var triedAlbums = new HashSet<string>();
        try
        {
            // Where は遅れて評価されるので、アルバムのページで決まった曲は飛ばされる
            foreach (var item in items.Where(i => i.Status == MatchStatus.Pending))
            {
                cancel.ThrowIfCancellationRequested();
                Report(new TransferProgress("match", done, total, item.Title));
                if (item.Kind == TransferKind.Track && AlbumKey(item.AsTrack()) is { } key && groups.TryGetValue(key, out var group) && triedAlbums.Add(key))
                {
                    var (albumStop, decided) = await MatchAlbumGroupAsync(group, cancel);
                    if (albumStop != TransferStop.None) return albumStop;
                    done += decided;
                    MatchedByAlbum += decided;
                    if (decided > 0) { save(session); sinceSave = 0; }
                    await delay(searchGap, cancel);
                    if (item.Status != MatchStatus.Pending) continue;
                }
                var stop = await MatchOneAsync(item, cancel);
                if (stop != TransferStop.None) return stop;
                done++;
                matched++;
                if (++sinceSave >= SaveEvery) { save(session); sinceSave = 0; }
                if (BatchSize > 0 && matched % BatchSize == 0) await delay(BatchPause, cancel);
                else await delay(searchGap, cancel);
            }
            Report(new TransferProgress("match", done, total));
            return TransferStop.None;
        }
        catch (OperationCanceledException)
        {
            return TransferStop.Cancelled;
        }
        finally
        {
            save(session);
        }
    }

    private static string? AlbumKey(SpotifyTrack track) =>
        track.Album is { Length: > 0 } album && track.Artists.Count > 0 ? TransferMatcher.Fold(track.Artists[0]) + "\u0001" + TransferMatcher.Fold(album) : null;

    /// <summary>アルバムを探し、確かに合うアルバムのページの曲目で、まとめた曲を突き合わせる。一致した曲の数を返す（合わない曲は Pending のまま、1曲ずつ探す）。</summary>
    private async Task<(TransferStop Stop, int Decided)> MatchAlbumGroupAsync(List<TransferItem> group, CancellationToken cancel)
    {
        var first = group[0].AsTrack();
        var album = new SpotifyAlbum(first.Album!, first.Artists);
        var albums = await RetryAsync(() => writer.SearchAlbumsAsync(TransferMatcher.AlbumQuery(album), cancel), cancel);
        if (!albums.IsOk) return (albums.Failure == FailureKind.Parse ? TransferStop.None : StopOf(albums), 0);
        var best = TransferMatcher.RankAlbums(album, albums.Value ?? []);
        if (TransferMatcher.StatusOf(best) != MatchStatus.Matched) return (TransferStop.None, 0);
        await delay(searchGap, cancel);
        var songs = await RetryAsync(() => writer.AlbumSongsAsync(best[0].Id, cancel), cancel);
        if (!songs.IsOk) return (songs.Failure == FailureKind.Parse ? TransferStop.None : StopOf(songs), 0);
        var decided = 0;
        foreach (var item in group.Where(i => i.Status == MatchStatus.Pending))
        {
            var candidates = TransferMatcher.RankSongs(item.AsTrack(), songs.Value ?? []);
            if (TransferMatcher.StatusOf(candidates) != MatchStatus.Matched) continue;
            item.SetCandidates(candidates);
            decided++;
        }
        return (TransferStop.None, decided);
    }

    private async Task<TransferStop> MatchOneAsync(TransferItem item, CancellationToken cancel)
    {
        switch (item.Kind)
        {
            case TransferKind.Track:
            {
                var track = item.AsTrack();
                var query = TransferMatcher.SongQuery(track);
                var songs = await RetryAsync(() => writer.SearchSongsAsync(query, cancel), cancel);
                if (!songs.IsOk && songs.Failure != FailureKind.Parse) return StopOf(songs);
                var candidates = TransferMatcher.RankSongs(track, songs.Value ?? []);
                if (TransferMatcher.StatusOf(candidates) != MatchStatus.Matched)
                {
                    // 曲（song）で見つからなければ、動画も含めた検索の上位から探す
                    await delay(searchGap, cancel);
                    var top = await RetryAsync(() => writer.SearchTopSongsAsync(query, cancel), cancel);
                    if (!top.IsOk && top.Failure != FailureKind.Parse) return StopOf(top);
                    candidates = TransferMatcher.Merge(candidates, TransferMatcher.RankSongs(track, top.Value ?? []));
                }
                item.SetCandidates(candidates);
                return TransferStop.None;
            }
            case TransferKind.Album:
            {
                var album = item.AsAlbum();
                var albums = await RetryAsync(() => writer.SearchAlbumsAsync(TransferMatcher.AlbumQuery(album), cancel), cancel);
                if (!albums.IsOk && albums.Failure != FailureKind.Parse) return StopOf(albums);
                item.SetCandidates(TransferMatcher.RankAlbums(album, albums.Value ?? []));
                return TransferStop.None;
            }
            default:
            {
                var artist = item.AsArtist();
                var artists = await RetryAsync(() => writer.SearchArtistsAsync(artist.Name, cancel), cancel);
                if (!artists.IsOk && artists.Failure != FailureKind.Parse) return StopOf(artists);
                item.SetCandidates(TransferMatcher.RankArtists(artist, artists.Value ?? []));
                return TransferStop.None;
            }
        }
    }

    /// <summary>計画のとおり書く（プレイリスト → 高く評価 → アルバム → アーティスト）。済んだものは session.Done に残る。</summary>
    public async Task<TransferStop> WriteAsync(TransferSession session, CancellationToken cancel)
    {
        var plan = TransferPlan.Of(session);
        HashSet<string>? titles = null;
        var titlesUnknown = false;
        var sinceSave = 0;
        void Saved()
        {
            if (++sinceSave < SaveEvery) return;
            save(session);
            sinceSave = 0;
        }
        try
        {
            // --- プレイリスト ---
            for (var n = 0; n < plan.Playlists.Count; n++)
            {
                var p = plan.Playlists[n];
                var entry = session.Playlists[p.Index];
                var op = TransferSession.PlaylistOp(p.Index);
                Report(new TransferProgress("playlist", n, plan.Playlists.Count, p.Name));
                cancel.ThrowIfCancellationRequested();
                if (entry.CreatedPlaylistId is null)
                {
                    // 作るのはやり直さない（通信が途中で切れて、作れていたのに2つ目を作るのを避ける）。失敗は次に続けるときにもう一度
                    // 同じ名前が前からあれば、移してきたと分かる印を付けて別に作る（読めなければ印を付ける方に倒す）
                    if (titles is null)
                    {
                        var read = await RetryNullAsync(() => writer.LibraryPlaylistTitlesAsync(cancel), cancel);
                        titlesUnknown = read is null;
                        titles = read ?? [];
                    }
                    var title = PlaylistTitle(p.Name, titles, titlesUnknown);
                    var created = await writer.CreatePlaylistAsync(title, p.VideoIds.Take(CreateChunk).ToList(), cancel);
                    if (!created.IsOk)
                    {
                        if (created.Failure == FailureKind.NotLoggedIn) return TransferStop.NotLoggedIn;
                        session.Done[op] = WriteOutcome.Failed;
                        continue;
                    }
                    entry.CreatedPlaylistId = created.Value;
                    titles.Add(title);
                    save(session);
                    await delay(WriteGap, cancel);
                }
                var id = entry.CreatedPlaylistId!;
                // 入っている曲を読み、足りない曲だけを足す（同じ曲を二重に足さない）
                var present = await RetryNullAsync(() => writer.PlaylistVideoIdsAsync(id, cancel), cancel);
                if (present is null)
                {
                    session.Done[op] = WriteOutcome.Failed;
                    continue;
                }
                var missing = p.VideoIds.Where(v => !present.Contains(v)).ToList();
                for (var i = 0; i < missing.Count; i += AddChunk)
                {
                    cancel.ThrowIfCancellationRequested();
                    var chunk = missing.Skip(i).Take(AddChunk).ToList();
                    var added = false;
                    for (var attempt = 0; !(added = await writer.AddToPlaylistAsync(id, chunk, cancel)); attempt++)
                    {
                        if (attempt >= Backoff.Length) break;
                        await delay(Backoff[attempt], cancel);
                    }
                    // まとめて足せなければ（足せない曲が1つ混ざると全部が断られる）、1曲ずつ足す
                    if (!added && chunk.Count > 1)
                    {
                        foreach (var v in chunk)
                        {
                            await delay(WriteGap, cancel);
                            await writer.AddToPlaylistAsync(id, [v], cancel);
                        }
                    }
                    await delay(WriteGap, cancel);
                }
                if (missing.Count > 0)
                {
                    present = await RetryNullAsync(() => writer.PlaylistVideoIdsAsync(id, cancel), cancel) ?? present;
                }
                entry.Missing = p.VideoIds.Count(v => !present.Contains(v));
                session.Done[op] = entry.Missing == 0 ? WriteOutcome.Written : WriteOutcome.Failed;
                save(session);
            }

            // --- 高く評価 ---
            if (plan.Likes.Count > 0)
            {
                var liked = await RetryNullAsync(() => writer.LikedVideoIdsAsync(cancel), cancel);
                for (var i = 0; i < plan.Likes.Count; i++)
                {
                    cancel.ThrowIfCancellationRequested();
                    var v = plan.Likes[i];
                    Report(new TransferProgress("like", i, plan.Likes.Count));
                    // 一覧を読めなければ1曲ずつ確かめる（前から評価していた曲を「書いた」と数えない）
                    if (liked?.Contains(v) ?? await writer.IsLikedAsync(v, cancel) == true)
                    {
                        session.Done[TransferSession.LikeOp(v)] = WriteOutcome.Already;
                        continue;
                    }
                    var r = await RetryAsync(() => writer.SetLikedAsync(v, cancel), cancel);
                    if (r.Failure == FailureKind.NotLoggedIn) return TransferStop.NotLoggedIn;
                    session.Done[TransferSession.LikeOp(v)] = r.IsOk ? WriteOutcome.Written : WriteOutcome.Failed;
                    Saved();
                    await delay(WriteGap, cancel);
                }
            }

            // --- アルバムの保存 ---
            for (var i = 0; i < plan.Albums.Count; i++)
            {
                cancel.ThrowIfCancellationRequested();
                var a = plan.Albums[i];
                Report(new TransferProgress("album", i, plan.Albums.Count));
                var state = await RetryAsync(() => writer.AlbumSaveStateAsync(a, cancel), cancel);
                if (state.Failure == FailureKind.NotLoggedIn) return TransferStop.NotLoggedIn;
                WriteOutcome outcome;
                if (!state.IsOk || state.Value.SavePlaylistId is not { } savePlaylistId) outcome = state.IsOk && state.Value.Saved == true ? WriteOutcome.Already : WriteOutcome.Failed;
                else if (state.Value.Saved == true) outcome = WriteOutcome.Already;
                else
                {
                    await delay(WriteGap, cancel);
                    var r = await RetryAsync(() => writer.SaveAlbumAsync(savePlaylistId, cancel), cancel);
                    if (r.Failure == FailureKind.NotLoggedIn) return TransferStop.NotLoggedIn;
                    outcome = r.IsOk ? WriteOutcome.Written : WriteOutcome.Failed;
                }
                session.Done[TransferSession.AlbumOp(a)] = outcome;
                Saved();
                await delay(WriteGap, cancel);
            }

            // --- アーティストの登録 ---
            for (var i = 0; i < plan.Artists.Count; i++)
            {
                cancel.ThrowIfCancellationRequested();
                var a = plan.Artists[i];
                Report(new TransferProgress("artist", i, plan.Artists.Count));
                var state = await RetryAsync(() => writer.ArtistSubscriptionAsync(a, cancel), cancel);
                if (state.Failure == FailureKind.NotLoggedIn) return TransferStop.NotLoggedIn;
                WriteOutcome outcome;
                if (!state.IsOk || state.Value.ChannelId is not { } channelId) outcome = state.IsOk && state.Value.Subscribed == true ? WriteOutcome.Already : WriteOutcome.Failed;
                else if (state.Value.Subscribed == true) outcome = WriteOutcome.Already;
                else
                {
                    await delay(WriteGap, cancel);
                    var r = await RetryAsync(() => writer.SubscribeAsync(channelId, cancel), cancel);
                    if (r.Failure == FailureKind.NotLoggedIn) return TransferStop.NotLoggedIn;
                    outcome = r.IsOk ? WriteOutcome.Written : WriteOutcome.Failed;
                }
                session.Done[TransferSession.ArtistOp(a)] = outcome;
                Saved();
                await delay(WriteGap, cancel);
            }
            Report(new TransferProgress("done", 1, 1));
            return TransferStop.None;
        }
        catch (OperationCanceledException)
        {
            return TransferStop.Cancelled;
        }
        finally
        {
            save(session);
        }
    }

    /// <summary>書き込みの結果の数（種類ごと）。</summary>
    public sealed record Summary(int Playlists, int PlaylistSongs, int PlaylistMissing, int Written, int Already, int Failed, Dictionary<string, (int Written, int Already, int Failed)> ByKind);

    public static Summary Summarize(TransferSession s)
    {
        var byKind = new Dictionary<string, (int, int, int)>();
        foreach (var (key, outcome) in s.Done)
        {
            var kind = key[..key.IndexOf(':')];
            var (w, a, f) = byKind.GetValueOrDefault(kind);
            byKind[kind] = outcome switch
            {
                WriteOutcome.Written => (w + 1, a, f),
                WriteOutcome.Already => (w, a + 1, f),
                _ => (w, a, f + 1),
            };
        }
        var created = s.Playlists.Where(p => p.CreatedPlaylistId is not null).ToList();
        var songs = created.Sum(p => p.TrackKeys.Select(k => s.Item(k)?.Target).Where(t => t is not null).Distinct().Count() - p.Missing);
        return new Summary(created.Count, songs, created.Sum(p => p.Missing),
            s.Done.Values.Count(o => o == WriteOutcome.Written), s.Done.Values.Count(o => o == WriteOutcome.Already), s.Done.Values.Count(o => o == WriteOutcome.Failed), byKind);
    }
}
