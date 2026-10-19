using System.Text.Json.Nodes;
using Wave.Core.Lyrics;
using Wave.Core.Net;

namespace Wave.Core.Music;

/// <summary>プレイリストへの追加の結果（Android 版の決定 G-1: 先に全ページを読んで重複を確かめる）。</summary>
public enum AddToPlaylistResult { Added, AlreadyInPlaylist, NotChecked, Failed }

/// <summary>曲・プレイリスト・ラジオなどの取得と書き込み（Android 版の MusicRepository と同じ呼び方）。</summary>
/// <param name="live">ライブ配信の検索（www.youtube.com、認証情報を持たないクライアント）。無ければ YouTube Music の検索のライブだけを出す。</param>
public sealed class MusicRepository(InnerTubeClient client, LiveClient? live = null)
{
    public const string SongsFilter = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D";
    public const string AlbumsFilter = "EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D";
    public const string ArtistsFilter = "EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D";
    public const string VideosFilter = "EgWKAQIQAWoSEAUQBBAJEAMQChAVEA4QERAQ";
    public const string FeaturedPlaylistsFilter = "EgeKAQQoADgBahIQBRAEEAkQAxAKEBUQDhAREBA%3D";
    public const string CommunityPlaylistsFilter = "EgeKAQQoAEABahIQBRAEEAkQAxAKEBUQDhAREBA%3D";
    public const string LibraryPlaylists = "FEmusic_liked_playlists";
    public const string History = "FEmusic_history";
    public const string Home = "FEmusic_home";
    public const string LibraryLanding = "FEmusic_library_landing";
    public const string MoodsBrowse = "FEmusic_moods_and_genres";
    public const string LibraryArtists = "FEmusic_library_corpus_track_artists";
    public const string LibraryAlbums = "FEmusic_liked_albums";
    public const string SubscribedArtists = "FEmusic_library_corpus_artists";
    public const string MoodCategoryBrowse = "FEmusic_moods_and_genres_category";
    public const string NewReleasesBrowse = "FEmusic_new_releases_albums";
    public const string LikedSongs = "LM";
    public const string PodcastsFilter = "EgWKAQJQAWoKEAkQBRAKEAMQBA%3D%3D";
    public const string EpisodesFilter = "EgWKAQJIAWoKEAkQBRAKEAMQBA%3D%3D";
    /// <summary>ライブラリのポッドキャスト（保存した番組）。</summary>
    public const string LibraryPodcastsBrowse = "FEmusic_library_non_music_audio_list";
    /// <summary>保存した番組の新しいエピソード（ログインしたときだけ中身がある自動のプレイリスト）。</summary>
    public const string NewEpisodes = "RDPN";
    /// <summary>新しいエピソードは browse すると番組のページと同じ形で返るので、番組のページとして VL を付けて読む（Android と同じ）。</summary>
    public const string NewEpisodesBrowse = "VL" + NewEpisodes;
    private const int MaxLibraryPages = 20;
    private const int MaxPlaylistPages = 50;

    public InnerTubeClient Client => client;

    public Task<ApiResult<SongPage>> SearchSongsAsync(string query, string? continuation = null, CancellationToken cancel = default) =>
        SearchAsync(query, SongsFilter, continuation, MusicParsers.SearchSongs, cancel);

    /// <summary>絞り込まない検索の、一番上の結果と各棚の曲・動画（検索の順）。歌詞から探すときの候補（<see cref="LyricsSearch"/>）。</summary>
    public async Task<ApiResult<List<Song>>> SearchTopSongsAsync(string query, CancellationToken cancel = default) =>
        (await client.PostAsync("search", new JsonObject { ["query"] = query }, cancel: cancel)).Map(MusicParsers.SearchTopSongs);

    public Task<ApiResult<SearchPage<AlbumSummary>>> SearchAlbumsAsync(string query, string? continuation = null, CancellationToken cancel = default) =>
        SearchAsync(query, AlbumsFilter, continuation, MusicParsers.SearchAlbums, cancel);

    public Task<ApiResult<SearchPage<ArtistSummary>>> SearchArtistsAsync(string query, string? continuation = null, CancellationToken cancel = default) =>
        SearchAsync(query, ArtistsFilter, continuation, MusicParsers.SearchArtists, cancel);

    public Task<ApiResult<SearchPage<PodcastSummary>>> SearchPodcastsAsync(string query, string? continuation = null, CancellationToken cancel = default) =>
        SearchAsync(query, PodcastsFilter, continuation, MusicParsers.SearchPodcasts, cancel);

    public Task<ApiResult<SearchPage<PodcastEpisode>>> SearchEpisodesAsync(string query, string? continuation = null, CancellationToken cancel = default) =>
        SearchAsync(query, EpisodesFilter, continuation, MusicParsers.SearchEpisodes, cancel);

    /// <summary>ポッドキャストの番組のページ（MPSP…）。エピソードは新しい順。</summary>
    public async Task<ApiResult<PodcastPage>> PodcastAsync(string browseId, CancellationToken cancel = default)
    {
        var result = await BrowseAsync(browseId, null, null, cancel);
        if (!result.IsOk) return ApiResult<PodcastPage>.Failed(result.Failure!.Value, result.Status);
        return MusicParsers.PodcastPage(result.Value!, browseId) is { } page ? ApiResult<PodcastPage>.Ok(page) : ApiResult<PodcastPage>.Failed(FailureKind.Parse);
    }

    /// <summary>番組のページのエピソードの続き。show は番組名（行に無いのでエピソードのアーティストとして入れる）。</summary>
    public async Task<ApiResult<SearchPage<PodcastEpisode>>> PodcastEpisodesAsync(string continuation, ArtistRef show, string? showBrowseId, CancellationToken cancel = default) =>
        (await BrowseAsync(null, continuation, null, cancel)).Map(root => MusicParsers.PodcastEpisodes(root, show, showBrowseId));

    /// <summary>ライブラリのポッドキャスト（保存した番組）。ログイン必須。</summary>
    public Task<ApiResult<List<PodcastSummary>>> LibraryPodcastsAsync(Action<List<PodcastSummary>>? onPage = null, CancellationToken cancel = default) =>
        AllLibraryPagesAsync(LibraryPodcastsBrowse, MusicParsers.LibraryPodcasts, p => p.BrowseId, onPage, cancel);

    /// <summary>ポッドキャストをライブラリに保存する・外す（番組のプレイリスト PL… を高く評価するのと同じ書き込み。Android と同じ）。</summary>
    public async Task<ApiResult<bool>> SetPodcastSavedAsync(string playlistId, bool saved, CancellationToken cancel = default)
    {
        if (!client.IsLoggedIn) return ApiResult<bool>.Failed(FailureKind.NotLoggedIn);
        var body = new JsonObject { ["target"] = new JsonObject { ["playlistId"] = playlistId } };
        return (await client.PostAsync(saved ? "like/like" : "like/removelike", body, cancel: cancel)).Map(_ => true);
    }

    private async Task<ApiResult<T>> SearchAsync<T>(string query, string filter, string? continuation, Func<JsonNode, T> parse, CancellationToken cancel)
    {
        var body = continuation is null ? new JsonObject { ["query"] = query, ["params"] = filter } : new JsonObject();
        var q = continuation is null ? null : new Dictionary<string, string> { ["continuation"] = continuation, ["ctoken"] = continuation };
        return (await client.PostAsync("search", body, query: q, cancel: cancel)).Map(parse);
    }

    public async Task<ApiResult<SearchSuggestions>> SuggestionsAsync(string input, CancellationToken cancel = default) =>
        (await client.PostAsync("music/get_search_suggestions", new JsonObject { ["input"] = input }, setLogin: false, cancel: cancel)).Map(MusicParsers.SearchSuggestions);

    public async Task<ApiResult<HomePage>> HomeAsync(string? continuation = null, string? chipParams = null, CancellationToken cancel = default) =>
        (await BrowseAsync(Home, continuation, chipParams, cancel)).Map(MusicParsers.HomePage);

    /// <summary>気分とジャンルの一覧（検索のタイル）。ジャンルの先頭に、アプリで足した Lo-Fi を置く。</summary>
    public async Task<ApiResult<List<MoodSection>>> MoodsAsync(CancellationToken cancel = default) =>
        (await BrowseAsync(MoodsBrowse, null, null, cancel)).Map(root => LofiGenre.AddTo(MusicParsers.Moods(root)));

    /// <summary>気分・ジャンルの1つのページ（ホームと同じ棚の並び）。Lo-Fi はアプリで組んだページ。</summary>
    public async Task<ApiResult<HomePage>> MoodCategoryAsync(string moodParams, CancellationToken cancel = default) =>
        moodParams == LofiGenre.Params ? await LofiPageAsync(cancel)
            : (await BrowseAsync(MoodCategoryBrowse, null, moodParams, cancel)).Map(MusicParsers.HomePage);

    /// <summary>
    /// ライブ配信の検索。最初のページは YouTube Music の動画の検索のうちライブのもの（Lofi Girl のような音楽のライブが上に来る）と、
    /// YouTube のライブの絞り込みを合わせる。続きは YouTube のライブの絞り込みだけ。両方とも失敗したときだけ失敗。
    /// </summary>
    public async Task<ApiResult<SongPage>> SearchLiveAsync(string query, string? continuation = null, CancellationToken cancel = default)
    {
        if (continuation is not null)
        {
            var next = live is null ? null : await live.SearchAsync(query, continuation, cancel);
            return next is null ? ApiResult<SongPage>.Failed(FailureKind.Network) : ApiResult<SongPage>.Ok(next);
        }
        var musicTask = SearchAsync(query, VideosFilter, null, MusicParsers.SearchSongs, cancel);
        var webTask = live?.SearchAsync(query, null, cancel) ?? Task.FromResult<SongPage?>(null);
        await Task.WhenAll(musicTask, webTask);
        var music = musicTask.Result;
        var web = webTask.Result;
        var musicLive = music.IsOk ? music.Value!.Songs.Where(s => s.IsLive).ToList() : null;
        if (musicLive is null && web is null) return music.IsOk ? ApiResult<SongPage>.Failed(FailureKind.Network) : ApiResult<SongPage>.Failed(music.Failure!.Value, music.Status);
        var songs = (musicLive ?? []).Concat(web?.Songs ?? []).DistinctBy(s => s.VideoId).ToList();
        return ApiResult<SongPage>.Ok(new SongPage(songs, web?.Continuation));
    }

    /// <summary>アプリで足した Lo-Fi のページ。ライブ配信・YouTube Music のプレイリスト・みんなのプレイリストの棚。</summary>
    public async Task<ApiResult<HomePage>> LofiPageAsync(CancellationToken cancel = default)
    {
        var livesTask = SearchLiveAsync(LofiGenre.LiveQuery, null, cancel);
        var featuredTask = SearchPlaylistsAsync(LofiGenre.PlaylistQuery, FeaturedPlaylistsFilter, cancel);
        var communityTask = SearchPlaylistsAsync(LofiGenre.PlaylistQuery, CommunityPlaylistsFilter, cancel);
        await Task.WhenAll(livesTask, featuredTask, communityTask);
        var lives = livesTask.Result;
        var featured = featuredTask.Result;
        var community = communityTask.Result;
        var shelves = new[]
        {
            lives.IsOk ? LofiGenre.Shelf(LofiGenre.LiveTitle, lives.Value!.Songs.Take(LofiGenre.MaxItems).Select(s => (HomeItem)new HomeItem.SongItem(s)).ToList()) : null,
            featured.IsOk ? LofiGenre.Shelf(LofiGenre.FeaturedTitle, LofiGenre.Featured(featured.Value!.Items).Select(p => (HomeItem)new HomeItem.PlaylistItem(p)).ToList()) : null,
            community.IsOk ? LofiGenre.Shelf(LofiGenre.CommunityTitle, community.Value!.Items.Take(LofiGenre.MaxItems).Select(p => (HomeItem)new HomeItem.PlaylistItem(p)).ToList()) : null,
        }.OfType<HomeShelf>().ToList();
        if (shelves.Count > 0) return ApiResult<HomePage>.Ok(new HomePage(shelves, null, []));
        if (!lives.IsOk) return ApiResult<HomePage>.Failed(lives.Failure!.Value, lives.Status);
        if (!featured.IsOk) return ApiResult<HomePage>.Failed(featured.Failure!.Value, featured.Status);
        if (!community.IsOk) return ApiResult<HomePage>.Failed(community.Failure!.Value, community.Status);
        return ApiResult<HomePage>.Ok(new HomePage([], null, []));
    }

    private Task<ApiResult<SearchPage<PlaylistSummary>>> SearchPlaylistsAsync(string query, string filter, CancellationToken cancel) =>
        SearchAsync(query, filter, null, MusicParsers.SearchPlaylists, cancel);

    public async Task<ApiResult<List<AlbumSummary>>> NewReleasesAsync(CancellationToken cancel = default) =>
        (await BrowseAsync(NewReleasesBrowse, null, null, cancel)).Map(MusicParsers.NewReleases);

    /// <summary>最近再生したアルバム・プレイリスト・アーティスト。ログイン必須。</summary>
    public Task<ApiResult<List<HomeItem>>> RecentActivityAsync(CancellationToken cancel = default) =>
        LoggedInBrowseAsync(LibraryLanding, null, MusicParsers.GridItems, cancel);

    public Task<ApiResult<List<PlaylistSummary>>> LibraryPlaylistsAsync(Action<List<PlaylistSummary>>? onPage = null, CancellationToken cancel = default) =>
        AllLibraryPagesAsync(LibraryPlaylists, MusicParsers.Playlists, p => p.PlaylistId, onPage, cancel);

    public Task<ApiResult<List<ArtistSummary>>> LibraryArtistsAsync(Action<List<ArtistSummary>>? onPage = null, CancellationToken cancel = default) =>
        AllLibraryPagesAsync(LibraryArtists, root => MusicParsers.LibraryCards(root).OfType<HomeItem.ArtistItem>().Select(a => a.Artist).ToList(), a => a.BrowseId, onPage, cancel);

    /// <summary>登録（サブスクライブ）したアーティスト。新作の知らせに使う（Android と同じ）。</summary>
    public Task<ApiResult<List<ArtistSummary>>> SubscribedArtistsAsync(CancellationToken cancel = default) =>
        AllLibraryPagesAsync(SubscribedArtists, root => MusicParsers.LibraryCards(root).OfType<HomeItem.ArtistItem>().Select(a => a.Artist).ToList(), a => a.BrowseId, null, cancel);

    public Task<ApiResult<List<AlbumSummary>>> LibraryAlbumsAsync(Action<List<AlbumSummary>>? onPage = null, CancellationToken cancel = default) =>
        AllLibraryPagesAsync(LibraryAlbums, root => MusicParsers.LibraryCards(root).OfType<HomeItem.AlbumItem>().Select(a => a.Album).ToList(), a => a.BrowseId, onPage, cancel);

    /// <summary>ライブラリの一覧を続きのページまで読む。途中で失敗したらそこまでの分を返す。onPage にはそれまでの分を渡す。</summary>
    private async Task<ApiResult<List<T>>> AllLibraryPagesAsync<T>(string browseId, Func<JsonNode, List<T>> parse, Func<T, object> key, Action<List<T>>? onPage, CancellationToken cancel)
    {
        var items = new List<T>();
        string? token = null;
        var pages = 0;
        do
        {
            var page = await LoggedInBrowseAsync(browseId, token, root => (Items: parse(root), Next: MusicParsers.LibraryContinuation(root)), cancel);
            if (!page.IsOk) return pages == 0 ? ApiResult<List<T>>.Failed(page.Failure!.Value, page.Status) : ApiResult<List<T>>.Ok(items.DistinctBy(key).ToList());
            items.AddRange(page.Value.Items);
            onPage?.Invoke(items.DistinctBy(key).ToList());
            // 同じトークンが返ったら止める
            token = page.Value.Next != token ? page.Value.Next : null;
            pages++;
        } while (token is not null && pages < MaxLibraryPages);
        return ApiResult<List<T>>.Ok(items.DistinctBy(key).ToList());
    }

    public Task<ApiResult<HistoryPage>> HistoryAsync(string? continuation = null, CancellationToken cancel = default) =>
        LoggedInBrowseAsync(History, continuation, MusicParsers.HistoryPage, cancel);

    /// <summary>次に流れる曲（ラジオ）。最初は videoId と playlistId（RDAMVM&lt;videoId&gt;）、続きは continuation で取る。</summary>
    public async Task<ApiResult<UpNextPage>> UpNextAsync(string? videoId, string? playlistId, string? continuation = null, CancellationToken cancel = default)
    {
        var body = new JsonObject { ["contentCheckOk"] = true, ["racyCheckOk"] = true };
        if (videoId is not null) body["videoId"] = videoId;
        if (playlistId is not null) body["playlistId"] = playlistId;
        if (continuation is not null) body["continuation"] = continuation;
        return (await client.PostAsync("next", body, cancel: cancel)).Map(MusicParsers.UpNext);
    }

    /// <summary>プレイリストの曲。高く評価した曲は playlistId = LM。</summary>
    /// <param name="order">並べ方の params（PlaylistOrder）。null なら付けない</param>
    public Task<ApiResult<PlaylistPage>> PlaylistAsync(string playlistId, string? continuation = null, CancellationToken cancel = default, string? order = null) =>
        LoggedInBrowseAsync("VL" + playlistId, continuation, MusicParsers.PlaylistPage, cancel, order);

    /// <summary>プレイリストの曲を全部読む（再生とシャッフル用）。onPage にはそれまでの分を渡す。</summary>
    public async Task<ApiResult<PlaylistPage>> FullPlaylistAsync(string playlistId, Action<PlaylistPage>? onPage = null, CancellationToken cancel = default, string? order = null)
    {
        var first = await PlaylistAsync(playlistId, null, cancel, order);
        if (!first.IsOk) return first;
        var page = first.Value!;
        var songs = page.Songs.ToList();
        var setIds = page.SetVideoIds.ToList();
        var token = page.Continuation;
        onPage?.Invoke(page);
        for (var i = 0; token is not null && i < MaxPlaylistPages; i++)
        {
            var next = await PlaylistAsync(playlistId, token, cancel);
            if (!next.IsOk) break;
            songs.AddRange(next.Value!.Songs);
            setIds.AddRange(next.Value.SetVideoIds);
            token = next.Value.Continuation != token ? next.Value.Continuation : null;
            onPage?.Invoke(page with { Songs = songs.ToList(), SetVideoIds = setIds.ToList(), Continuation = token });
        }
        return ApiResult<PlaylistPage>.Ok(page with { Songs = songs, SetVideoIds = setIds, Continuation = token });
    }

    /// <summary>曲の評価と歌詞のタブ（next の最初の応答から取る）。</summary>
    public async Task<ApiResult<SongInfo>> SongInfoAsync(string videoId, CancellationToken cancel = default) =>
        (await client.PostAsync("next", new JsonObject { ["videoId"] = videoId, ["contentCheckOk"] = true, ["racyCheckOk"] = true }, cancel: cancel))
            .Map(root => MusicParsers.SongInfo(root, videoId));

    /// <summary>YouTube Music の時刻なしの歌詞。無ければ Ok(null)。</summary>
    public async Task<ApiResult<SongLyrics.Plain?>> PlainLyricsAsync(string videoId, CancellationToken cancel = default)
    {
        var info = await SongInfoAsync(videoId, cancel);
        if (!info.IsOk) return ApiResult<SongLyrics.Plain?>.Failed(info.Failure!.Value, info.Status);
        if (info.Value!.LyricsBrowseId is not { } browseId) return ApiResult<SongLyrics.Plain?>.Ok(null);
        return (await BrowseAsync(browseId, null, null, cancel)).Map(MusicParsers.PlainLyrics);
    }

    /// <summary>アーティストの YouTube のチャンネルの投稿（本人のお知らせ。ライブ・ツアーの告知を拾うのに使う。Android と同じ）。</summary>
    public async Task<ApiResult<List<ArtistPost>>> ArtistPostsAsync(string channelId, CancellationToken cancel = default) =>
        (await client.PostYouTubeAsync("browse", new JsonObject { ["browseId"] = channelId, ["params"] = PostsParams }, cancel)).Map(MusicParsers.ArtistPosts);

    /// <summary>チャンネルの「投稿」タブを開く params。</summary>
    private const string PostsParams = "EgVwb3N0c_IGBAoCSgA=";

    /// <summary>アーティスト（チャンネル）を登録する・登録を外す（Android と同じ）。</summary>
    public async Task<ApiResult<bool>> SetSubscribedAsync(string channelId, bool subscribe, CancellationToken cancel = default)
    {
        if (!client.IsLoggedIn) return ApiResult<bool>.Failed(FailureKind.NotLoggedIn);
        var body = new JsonObject { ["channelIds"] = new JsonArray(channelId) };
        return (await client.PostAsync(subscribe ? "subscription/subscribe" : "subscription/unsubscribe", body, cancel: cancel)).Map(_ => true);
    }

    public async Task<ApiResult<bool>> SetLikedAsync(string videoId, bool liked, CancellationToken cancel = default)
    {
        if (!client.IsLoggedIn) return ApiResult<bool>.Failed(FailureKind.NotLoggedIn);
        var body = new JsonObject { ["target"] = new JsonObject { ["videoId"] = videoId } };
        return (await client.PostAsync(liked ? "like/like" : "like/removelike", body, cancel: cancel)).Map(_ => true);
    }

    /// <summary>
    /// プレイリストの中の曲の ID を全ページ読む（重複の確認と「どのプレイリストに入っているか」の表示。Android と同じ）。
    /// 最後まで読めなければ null。
    /// </summary>
    public async Task<HashSet<string>?> ScanPlaylistAsync(string playlistId, CancellationToken cancel = default) =>
        (await ScanPlaylistItemsAsync(playlistId, cancel))?.Keys.ToHashSet();

    /// <summary>
    /// プレイリストの中の曲と、曲ごとのプレイリスト内の項目の ID（外すのに使う。同じ曲が2回入っていれば2つ。取れなかった曲は空）。
    /// 最後まで読めなければ null。
    /// </summary>
    public async Task<Dictionary<string, List<string>>?> ScanPlaylistItemsAsync(string playlistId, CancellationToken cancel = default)
    {
        var ids = new Dictionary<string, List<string>>();
        string? token = null;
        var pages = 0;
        do
        {
            var page = await PlaylistAsync(playlistId, token, cancel);
            if (!page.IsOk) return null;
            for (var i = 0; i < page.Value!.Songs.Count; i++)
            {
                var setIds = ids.TryGetValue(page.Value.Songs[i].VideoId, out var known) ? known : ids[page.Value.Songs[i].VideoId] = [];
                if (i < page.Value.SetVideoIds.Count && page.Value.SetVideoIds[i] is { } setId) setIds.Add(setId);
            }
            token = page.Value.Continuation;
            if (token is null && !page.Value.EndConfirmed) return null;
            if (++pages >= MaxPlaylistPages && token is not null) return null;
        } while (token is not null);
        return ids;
    }

    /// <summary>曲をプレイリストに追加する。先に全ページを読んで重複を確かめ、読めなければ追加しない。</summary>
    public async Task<AddToPlaylistResult> AddToPlaylistAsync(string playlistId, string videoId, bool knownEmpty = false, CancellationToken cancel = default)
    {
        if (!knownEmpty)
        {
            var ids = await ScanPlaylistAsync(playlistId, cancel);
            if (ids is null) return AddToPlaylistResult.NotChecked;
            if (ids.Contains(videoId)) return AddToPlaylistResult.AlreadyInPlaylist;
        }
        var result = await EditPlaylistAsync(playlistId, new JsonObject { ["action"] = "ACTION_ADD_VIDEO", ["addedVideoId"] = videoId }, cancel);
        return result ? AddToPlaylistResult.Added : AddToPlaylistResult.Failed;
    }

    public Task<bool> RemoveFromPlaylistAsync(string playlistId, string videoId, string setVideoId, CancellationToken cancel = default) =>
        EditPlaylistAsync(playlistId, new JsonObject { ["action"] = "ACTION_REMOVE_VIDEO", ["setVideoId"] = setVideoId, ["removedVideoId"] = videoId }, cancel);

    private async Task<bool> EditPlaylistAsync(string playlistId, JsonObject action, CancellationToken cancel)
    {
        if (!client.IsLoggedIn) return false;
        var body = new JsonObject { ["playlistId"] = playlistId.StartsWith("VL", StringComparison.Ordinal) ? playlistId[2..] : playlistId, ["actions"] = new JsonArray(action) };
        var result = await client.PostAsync("browse/edit_playlist", body, cancel: cancel);
        return result.IsOk && MusicParsers.EditPlaylistStatus(result.Value!) == "STATUS_SUCCEEDED";
    }

    /// <summary>非公開のプレイリストを作る。作った playlistId を返す。</summary>
    public async Task<ApiResult<string>> CreatePlaylistAsync(string title, CancellationToken cancel = default)
    {
        if (!client.IsLoggedIn) return ApiResult<string>.Failed(FailureKind.NotLoggedIn);
        var result = await client.PostAsync("playlist/create", new JsonObject { ["title"] = title, ["privacyStatus"] = "PRIVATE" }, cancel: cancel);
        if (!result.IsOk) return ApiResult<string>.Failed(result.Failure!.Value, result.Status);
        return MusicParsers.CreatedPlaylistId(result.Value!) is { } id ? ApiResult<string>.Ok(id) : ApiResult<string>.Failed(FailureKind.Parse);
    }

    public async Task<ApiResult<AlbumPage>> AlbumAsync(string browseId, CancellationToken cancel = default)
    {
        var result = await BrowseAsync(browseId, null, null, cancel);
        if (!result.IsOk) return ApiResult<AlbumPage>.Failed(result.Failure!.Value, result.Status);
        return MusicParsers.AlbumPage(result.Value!, browseId) is { } page ? ApiResult<AlbumPage>.Ok(page) : ApiResult<AlbumPage>.Failed(FailureKind.Parse);
    }

    public async Task<ApiResult<ArtistPage>> ArtistAsync(string browseId, CancellationToken cancel = default)
    {
        var result = await BrowseAsync(browseId, null, null, cancel);
        if (!result.IsOk) return ApiResult<ArtistPage>.Failed(result.Failure!.Value, result.Status);
        return MusicParsers.ArtistPage(result.Value!) is { } page ? ApiResult<ArtistPage>.Ok(page) : ApiResult<ArtistPage>.Failed(FailureKind.Parse);
    }

    /// <summary>アカウントの名前と画像（ログインの確認を兼ねる）。</summary>
    public async Task<ApiResult<AccountInfo>> AccountAsync(CancellationToken cancel = default)
    {
        if (!client.IsLoggedIn) return ApiResult<AccountInfo>.Failed(FailureKind.NotLoggedIn);
        var generation = client.Generation;
        var result = await client.PostAsync("account/account_menu", new JsonObject(), cancel: cancel);
        if (!result.IsOk) return ApiResult<AccountInfo>.Failed(result.Failure!.Value, result.Status);
        var header = result.Value!.FindAll("activeAccountHeaderRenderer").FirstOrDefault();
        // ヘッダが無いだけでは切れたことにしない（疑いにして確かめる。ADR-0004）
        if (header is null)
        {
            client.ReportSuspect(generation);
            return ApiResult<AccountInfo>.Failed(FailureKind.NotLoggedIn);
        }
        client.ReportAuthenticated(generation);
        var name = string.Concat(header.Path("accountName", "runs").Items().Select(r => r.Path("text").Str()));
        var photo = header.Path("accountPhoto", "thumbnails").Items().LastOrDefault().Path("url").Str();
        return ApiResult<AccountInfo>.Ok(new AccountInfo(name, photo));
    }

    /// <summary>
    /// 今のログインが、保存した DATASYNC_ID のアカウントか（Android の WaveSession.verifiedAccount と同じ）。端末の連携でアカウントの照合に使う。
    /// accounts_list の選択中のアカウントがちょうど1件で、その datasyncIdToken（"||" より前）が保存した値と同じで、
    /// 確かめている間にログインが変わっていないときだけ返す。値はログ・画面に出さない。
    /// </summary>
    public async Task<VerifiedAccount?> VerifiedAccountAsync(CancellationToken cancel = default)
    {
        var generation = client.Generation;
        var saved = client.CurrentCredentials?.RevealDataSyncId();
        if (saved is null) return null;
        var result = await client.PostAsync("account/accounts_list", new JsonObject(), cancel: cancel);
        if (!result.IsOk) return null;
        var selected = AccountTokens(result.Value!).Where(t => t.Selected).Select(t => t.Id).Distinct().ToList();
        if (selected.Count != 1 || selected[0] != saved || client.Generation != generation) return null;
        return new VerifiedAccount(generation, saved);
    }

    /// <summary>accounts_list の応答から、各アカウントの（選択中か, datasyncIdToken の "||" より前）を集める。</summary>
    internal static List<(bool Selected, string Id)> AccountTokens(JsonNode root)
    {
        var tokens = new List<(bool, string)>();
        void Walk(JsonNode? node, bool selected)
        {
            switch (node)
            {
                case JsonObject obj:
                    var isSelected = selected || (obj["isSelected"] is JsonValue v && v.ToString() == "true");
                    foreach (var (name, value) in obj)
                    {
                        if (name == "datasyncIdToken" && value is JsonValue s && s.TryGetValue<string>(out var text) && Wave.Core.Auth.Credentials.NormalizeDataSyncId(text) is { } id)
                            tokens.Add((isSelected, id));
                        Walk(value, isSelected);
                    }
                    break;
                case JsonArray array:
                    foreach (var item in array) Walk(item, selected);
                    break;
            }
        }
        Walk(root, false);
        return tokens;
    }

    private async Task<ApiResult<JsonNode>> BrowseAsync(string? browseId, string? continuation, string? browseParams, CancellationToken cancel)
    {
        var body = new JsonObject();
        if (continuation is not null) body["continuation"] = continuation;
        else
        {
            body["browseId"] = browseId;
            if (browseParams is not null) body["params"] = browseParams;
        }
        return await client.PostAsync("browse", body, cancel: cancel);
    }

    /// <summary>ログイン必須の browse。ログインの案内だけが返ったら NotLoggedIn。</summary>
    private async Task<ApiResult<T>> LoggedInBrowseAsync<T>(string browseId, string? continuation, Func<JsonNode, T> parse, CancellationToken cancel, string? browseParams = null)
    {
        if (!client.IsLoggedIn) return ApiResult<T>.Failed(FailureKind.NotLoggedIn);
        var generation = client.Generation;
        var result = await BrowseAsync(browseId, continuation, browseParams, cancel);
        if (!result.IsOk) return ApiResult<T>.Failed(result.Failure!.Value, result.Status);
        if (!MusicParsers.IsSignInPrompt(result.Value!)) return ApiResult<T>.Ok(parse(result.Value!));
        // ログイン中のはずなのにログインの案内だけが返った: 疑いにして確かめる（これだけでは切れたことにしない）
        client.ReportSuspect(generation);
        return ApiResult<T>.Failed(FailureKind.NotLoggedIn);
    }
}

public sealed record AccountInfo(string Name, string? PhotoUrl);
