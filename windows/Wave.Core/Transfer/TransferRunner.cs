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

    public Task<HashSet<string>?> PlaylistVideoIdsAsync(string playlistId, CancellationToken cancel) => repository.ScanPlaylistAsync(playlistId, cancel);

    public Task<bool> AddToPlaylistAsync(string playlistId, IReadOnlyList<string> videoIds, CancellationToken cancel) =>
        repository.AddManyToPlaylistAsync(playlistId, videoIds, cancel);

    public Task<HashSet<string>?> LikedVideoIdsAsync(CancellationToken cancel) => repository.ScanPlaylistAsync(MusicRepository.LikedSongs, cancel);

    public async Task<bool?> IsLikedAsync(string videoId, CancellationToken cancel) =>
        await repository.SongInfoAsync(videoId, cancel) is { IsOk: true } info && info.Value!.Like is { } like ? like == LikeStatus.Like : null;

    public Task<ApiResult<bool>> SetLikedAsync(string videoId, CancellationToken cancel) => repository.SetLikedAsync(videoId, true, cancel);

    public async Task<ApiResult<(bool? Saved, string? SavePlaylistId)>> AlbumSaveStateAsync(string browseId, CancellationToken cancel) =>
        (await repository.AlbumAsync(browseId, cancel)).Map(a => (a.Saved, a.SavePlaylistId));

    public Task<ApiResult<bool>> SaveAlbumAsync(string savePlaylistId, CancellationToken cancel) => repository.SetPlaylistSavedAsync(savePlaylistId, true, cancel);

    public async Task<ApiResult<(bool? Subscribed, string? ChannelId)>> ArtistSubscriptionAsync(string browseId, CancellationToken cancel) =>
        (await repository.ArtistAsync(browseId, cancel)).Map(a => (a.Subscribed, a.ChannelId));

    public Task<ApiResult<bool>> SubscribeAsync(string channelId, CancellationToken cancel) => repository.SetSubscribedAsync(channelId, true, cancel);
}

/// <summary>進み具合（画面に出す）。Stage は "match"・"playlist"・"like"・"album"・"artist"。</summary>
public sealed record TransferProgress(string Stage, int Done, int Total, string? Current = null);

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
    public TimeSpan WriteGap { get; init; } = TimeSpan.FromMilliseconds(700);
    public static readonly TimeSpan[] Backoff = [TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(10), TimeSpan.FromSeconds(30)];
    /// <summary>プレイリストを作るときに一緒に渡す曲の数と、あとから足すときの1回の数。</summary>
    public const int CreateChunk = 50;
    public const int AddChunk = 25;
    /// <summary>この数ごとに保存する（毎回書くと大きなライブラリで重い。止まったら最後に保存する）。</summary>
    private const int SaveEvery = 10;

    public event Action<TransferProgress>? Progress;

    /// <summary>失敗したら待ってやり直す。ログインが要る・形が読めないときはやり直さない。</summary>
    private async Task<ApiResult<T>> RetryAsync<T>(Func<Task<ApiResult<T>>> call, CancellationToken cancel)
    {
        for (var attempt = 0; ; attempt++)
        {
            var result = await call();
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

    private static TransferStop StopOf<T>(ApiResult<T> r) => r.Failure == FailureKind.NotLoggedIn ? TransferStop.NotLoggedIn : TransferStop.Network;

    /// <summary>まだ突き合わせていないもの（Pending）を検索して候補を付ける。</summary>
    public async Task<TransferStop> MatchAsync(TransferSession session, CancellationToken cancel)
    {
        var items = session.ItemsInScope();
        var total = items.Count;
        var done = items.Count(i => i.Status != MatchStatus.Pending);
        var sinceSave = 0;
        try
        {
            foreach (var item in items.Where(i => i.Status == MatchStatus.Pending))
            {
                cancel.ThrowIfCancellationRequested();
                Progress?.Invoke(new TransferProgress("match", done, total, item.Title));
                var stop = await MatchOneAsync(item, cancel);
                if (stop != TransferStop.None) return stop;
                done++;
                if (++sinceSave >= SaveEvery) { save(session); sinceSave = 0; }
                await delay(SearchGap, cancel);
            }
            Progress?.Invoke(new TransferProgress("match", done, total));
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
                    await delay(SearchGap, cancel);
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
                Progress?.Invoke(new TransferProgress("playlist", n, plan.Playlists.Count, p.Name));
                cancel.ThrowIfCancellationRequested();
                if (entry.CreatedPlaylistId is null)
                {
                    // 作るのはやり直さない（通信が途中で切れて、作れていたのに2つ目を作るのを避ける）。失敗は次に続けるときにもう一度
                    var created = await writer.CreatePlaylistAsync(p.Name, p.VideoIds.Take(CreateChunk).ToList(), cancel);
                    if (!created.IsOk)
                    {
                        if (created.Failure == FailureKind.NotLoggedIn) return TransferStop.NotLoggedIn;
                        session.Done[op] = WriteOutcome.Failed;
                        continue;
                    }
                    entry.CreatedPlaylistId = created.Value;
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
                    Progress?.Invoke(new TransferProgress("like", i, plan.Likes.Count));
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
                Progress?.Invoke(new TransferProgress("album", i, plan.Albums.Count));
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
                Progress?.Invoke(new TransferProgress("artist", i, plan.Artists.Count));
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
            Progress?.Invoke(new TransferProgress("done", 1, 1));
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
