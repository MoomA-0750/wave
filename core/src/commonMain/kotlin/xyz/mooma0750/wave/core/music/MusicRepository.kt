package xyz.mooma0750.wave.core.music

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import xyz.mooma0750.wave.core.lyrics.Lyrics
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.core.session.ParseOutcome
import xyz.mooma0750.wave.core.session.WaveSession
import xyz.mooma0750.wave.core.session.WriteResult

/** 曲・プレイリスト・ラジオの取得。通信は WaveSession を通し、失敗は FailureKind のまま返す。 */
class MusicRepository(
    private val session: WaveSession,
    /** ライブ配信の検索（www.youtube.com、認証情報を持たないクライアント）。無ければ YouTube Music の検索のライブだけを出す。 */
    private val live: LiveClient? = null,
) {
    /** 曲だけに絞った検索。continuation を渡したら続きのページ。 */
    suspend fun searchSongs(
        query: String,
        continuation: String? = null,
    ): CallResult<SongPage> =
        session.call(
            operation = "search",
            block = {
                if (continuation == null) {
                    it.search(YouTubeClient.WEB_REMIX, query = query, params = SONGS_FILTER)
                } else {
                    it.search(YouTubeClient.WEB_REMIX, continuation = continuation)
                }
            },
        ) { response -> ParseOutcome.Parsed(MusicParsers.searchSongs(json(response))) }

    /** 絞り込まない検索の、一番上の結果と各棚の曲・動画（検索の順）。歌詞から探すときの候補（[xyz.mooma0750.wave.core.lyrics.LyricsSearch]）。 */
    suspend fun searchTopSongs(query: String): CallResult<List<Song>> =
        session.call(
            operation = "search",
            block = { it.search(YouTubeClient.WEB_REMIX, query = query) },
        ) { response -> ParseOutcome.Parsed(MusicParsers.searchTopSongs(json(response))) }

    /** ホームのおすすめの棚。continuation を渡したら続きの棚。params は絞り込み（[HomeChip]）。 */
    suspend fun home(
        continuation: String? = null,
        params: String? = null,
    ): CallResult<HomePage> =
        session.call(operation = "browse", block = browseOrContinue(HOME, continuation, params)) { response ->
            val root = json(response)
            lastRendererNames = MusicParsers.rendererNames(root)
            ParseOutcome.Parsed(MusicParsers.homePage(root))
        }

    /** 最近再生したアルバム・プレイリスト・アーティスト（ライブラリの最近のアクティビティ）。ログイン必須。 */
    suspend fun recentActivity(): CallResult<List<HomeItem>> =
        session.call(operation = "browse", block = { it.browse(YouTubeClient.WEB_REMIX, browseId = LIBRARY_LANDING, setLogin = true) }) { response ->
            val root = json(response)
            lastRendererNames = MusicParsers.rendererNames(root)
            if (MusicParsers.isSignInPrompt(root)) ParseOutcome.LoggedOutResponse else ParseOutcome.Parsed(MusicParsers.gridItems(root))
        }

    /** ライブラリのアーティスト（ライブラリの曲のアーティスト）。ログイン必須。 */
    suspend fun libraryArtists(onPage: (List<ArtistSummary>) -> Unit = {}): CallResult<List<ArtistSummary>> =
        libraryCards(LIBRARY_ARTISTS, onPage) { (it as? HomeItem.ArtistItem)?.artist }

    /**
     * 登録（サブスクライブ）したアーティスト。ログイン必須。新作の知らせに使う。
     * 途中のページで失敗した・ページの上限で打ち切ったときは、そこまでの分を返して onIncomplete を呼ぶ。
     */
    suspend fun subscribedArtists(
        onPage: (List<ArtistSummary>) -> Unit = {},
        onIncomplete: () -> Unit = {},
    ): CallResult<List<ArtistSummary>> =
        allLibraryPages(SUBSCRIBED_ARTISTS, { root -> MusicParsers.libraryCards(root).mapNotNull { (it as? HomeItem.ArtistItem)?.artist } }, { it.browseId }, onPage, onIncomplete)

    /** ライブラリのアルバム（保存したアルバム）。ログイン必須。 */
    suspend fun libraryAlbums(onPage: (List<AlbumSummary>) -> Unit = {}): CallResult<List<AlbumSummary>> =
        libraryCards(LIBRARY_ALBUMS, onPage) { (it as? HomeItem.AlbumItem)?.album }

    private suspend fun <T : Any> libraryCards(
        browseId: String,
        onPage: (List<T>) -> Unit,
        pick: (HomeItem) -> T?,
    ): CallResult<List<T>> = allLibraryPages(browseId, { root -> MusicParsers.libraryCards(root).mapNotNull(pick) }, { it }, onPage)

    /**
     * ライブラリの一覧を、続きのページまで読む（Spotify のマイライブラリと同じく全部を並べる）。
     * 途中のページで失敗したら、そこまでの分を返す（onIncomplete を呼ぶ。ページの上限で打ち切ったときも）。
     * onPage にはページが届くたびに、それまでの分を渡す（画面に順に出す）。
     */
    private suspend fun <T> allLibraryPages(
        browseId: String,
        parse: (JsonObject) -> List<T>,
        key: (T) -> Any,
        onPage: (List<T>) -> Unit = {},
        onIncomplete: () -> Unit = {},
    ): CallResult<List<T>> {
        val items = mutableListOf<T>()
        var token: String? = null
        var pages = 0
        do {
            val page =
                session.call(operation = "browse", block = browseOrContinue(browseId, token)) { response ->
                    val root = json(response)
                    lastRendererNames = MusicParsers.rendererNames(root)
                    if (MusicParsers.isSignInPrompt(root)) ParseOutcome.LoggedOutResponse else ParseOutcome.Parsed(parse(root) to MusicParsers.libraryContinuation(root))
                }
            when (page) {
                is CallResult.Failed -> return if (pages == 0) page else CallResult.Ok(items.distinctBy(key)).also { onIncomplete() }
                is CallResult.Ok -> {
                    items += page.value.first
                    onPage(items.distinctBy(key))
                    // 同じトークンが返ったら（形が変わったときなど）止める
                    token = page.value.second?.takeIf { it != token }
                }
            }
            pages++
        } while (token != null && pages < MAX_LIBRARY_PAGES)
        if (token != null) onIncomplete()
        return CallResult.Ok(items.distinctBy(key))
    }

    /** 気分とジャンルの一覧（検索のタブのタイル）。ジャンルの先頭に、アプリで足した Lo-Fi を置く。 */
    suspend fun moods(): CallResult<List<MoodSection>> =
        session.call(operation = "browse", block = { it.browse(YouTubeClient.WEB_REMIX, browseId = MOODS, setLogin = true) }) { response ->
            ParseOutcome.Parsed(LofiGenre.addTo(MusicParsers.moods(json(response))))
        }

    /** 気分・ジャンルの1つのページ（ホームと同じ棚の並び）。Lo-Fi はアプリで組んだページ。 */
    suspend fun moodCategory(params: String): CallResult<HomePage> =
        if (params == LofiGenre.PARAMS) lofiPage() else youtubeMoodCategory(params)

    private suspend fun youtubeMoodCategory(params: String): CallResult<HomePage> =
        session.call(operation = "browse", block = { it.browse(YouTubeClient.WEB_REMIX, browseId = MOOD_CATEGORY, params = params, setLogin = true) }) { response ->
            ParseOutcome.Parsed(MusicParsers.homePage(json(response)))
        }

    /** 新作のアルバム・シングル。 */
    suspend fun newReleases(): CallResult<List<AlbumSummary>> =
        session.call(operation = "browse", block = { it.browse(YouTubeClient.WEB_REMIX, browseId = NEW_RELEASES, setLogin = true) }) { response ->
            ParseOutcome.Parsed(MusicParsers.newReleases(json(response)))
        }

    /** アルバムに絞った検索。continuation を渡したら続きのページ。 */
    suspend fun searchAlbums(
        query: String,
        continuation: String? = null,
    ): CallResult<SearchPage<AlbumSummary>> =
        session.call(operation = "search", block = searchOrContinue(query, ALBUMS_FILTER, continuation)) { response ->
            ParseOutcome.Parsed(MusicParsers.searchAlbums(json(response)))
        }

    /** アーティストに絞った検索。continuation を渡したら続きのページ。 */
    suspend fun searchArtists(
        query: String,
        continuation: String? = null,
    ): CallResult<SearchPage<ArtistSummary>> =
        session.call(operation = "search", block = searchOrContinue(query, ARTISTS_FILTER, continuation)) { response ->
            ParseOutcome.Parsed(MusicParsers.searchArtists(json(response)))
        }

    /** ポッドキャスト（番組）に絞った検索。continuation を渡したら続きのページ。 */
    suspend fun searchPodcasts(
        query: String,
        continuation: String? = null,
    ): CallResult<SearchPage<PodcastSummary>> =
        session.call(operation = "search", block = searchOrContinue(query, PODCASTS_FILTER, continuation)) { response ->
            ParseOutcome.Parsed(MusicParsers.searchPodcasts(json(response)))
        }

    /** ポッドキャストのエピソードに絞った検索。continuation を渡したら続きのページ。 */
    suspend fun searchEpisodes(
        query: String,
        continuation: String? = null,
    ): CallResult<SearchPage<PodcastEpisode>> =
        session.call(operation = "search", block = searchOrContinue(query, EPISODES_FILTER, continuation)) { response ->
            ParseOutcome.Parsed(MusicParsers.searchEpisodes(json(response)))
        }

    /** ポッドキャストの番組のページ（`MPSP…`）。エピソードは新しい順。 */
    suspend fun podcast(browseId: String): CallResult<PodcastPage> =
        session.call(operation = "browse", block = browseOrContinue(browseId, continuation = null)) { response ->
            val root = json(response)
            lastRendererNames = MusicParsers.rendererNames(root)
            parsedOrInvalid(MusicParsers.podcastPage(root, browseId))
        }

    /** 番組のページのエピソードの続き。show は番組名（行に無いのでエピソードのアーティストとして入れる）。 */
    suspend fun podcastEpisodes(
        continuation: String,
        show: ArtistRef,
        showBrowseId: String?,
    ): CallResult<SearchPage<PodcastEpisode>> =
        session.call(operation = "browse", block = browseOrContinue("", continuation)) { response ->
            ParseOutcome.Parsed(MusicParsers.podcastEpisodes(json(response), show, showBrowseId))
        }

    /** ライブラリのポッドキャスト（保存した番組）。ログイン必須。 */
    suspend fun libraryPodcasts(onPage: (List<PodcastSummary>) -> Unit = {}): CallResult<List<PodcastSummary>> =
        allLibraryPages(LIBRARY_PODCASTS, MusicParsers::libraryPodcasts, { it.browseId }, onPage)

    /**
     * ポッドキャストをライブラリに保存する（saved = true）・外す（false）。番組のプレイリスト（`PL…`）を高く評価するのと同じ書き込み。
     * 書き込みの規則は setLiked と同じ。
     */
    suspend fun setPodcastSaved(
        playlistId: String,
        saved: Boolean,
        expectedGeneration: Long? = null,
    ): WriteResult<Unit> =
        session.callWrite(
            operation = if (saved) "like" else "removelike",
            block = { if (saved) it.likePlaylist(YouTubeClient.WEB_REMIX, playlistId) else it.unlikePlaylist(YouTubeClient.WEB_REMIX, playlistId) },
            expectedGeneration = expectedGeneration,
        ) { response ->
            json(response)
            ParseOutcome.Parsed(Unit)
        }

    /**
     * ライブ配信の検索。最初のページは YouTube Music の動画の検索のうちライブのもの（Lofi Girl のような音楽のライブが上に来る）と、
     * YouTube のライブの絞り込みを合わせる。続きは YouTube のライブの絞り込みだけ。
     */
    suspend fun searchLive(
        query: String,
        continuation: String? = null,
    ): CallResult<SongPage> {
        if (continuation != null) {
            return live?.search(query, continuation)?.let { CallResult.Ok(it) } ?: CallResult.Failed(FailureKind.Network)
        }
        return coroutineScope {
            val music = async { session.call(operation = "search", block = searchOrContinue(query, VIDEOS_FILTER, null)) { response -> ParseOutcome.Parsed(MusicParsers.searchSongs(json(response))) } }
            val web = async { live?.search(query) }
            val musicLive = (music.await() as? CallResult.Ok)?.value?.songs?.filter { it.isLive }
            val webPage = web.await()
            if (musicLive == null && webPage == null) {
                (music.await() as? CallResult.Failed) ?: CallResult.Failed(FailureKind.Network)
            } else {
                CallResult.Ok(SongPage((musicLive.orEmpty() + webPage?.songs.orEmpty()).distinctBy { it.videoId }, webPage?.continuation))
            }
        }
    }

    /** アプリで足した Lo-Fi のページ。ライブ配信・YouTube Music のプレイリスト・みんなのプレイリストの棚。 */
    suspend fun lofiPage(): CallResult<HomePage> =
        coroutineScope {
            val lives = async { searchLive(LofiGenre.LIVE_QUERY) }
            val featured = async { searchPlaylists(LofiGenre.PLAYLIST_QUERY, FEATURED_PLAYLISTS_FILTER) }
            val community = async { searchPlaylists(LofiGenre.PLAYLIST_QUERY, COMMUNITY_PLAYLISTS_FILTER) }
            val liveResult = lives.await()
            val featuredResult = featured.await()
            val communityResult = community.await()
            val shelves =
                listOfNotNull(
                    (liveResult as? CallResult.Ok)?.value?.let { page -> LofiGenre.shelf(LofiGenre.LIVE_TITLE, page.songs.take(LofiGenre.MAX_ITEMS).map(HomeItem::SongItem)) },
                    (featuredResult as? CallResult.Ok)?.value?.let { page -> LofiGenre.shelf(LofiGenre.FEATURED_TITLE, LofiGenre.featured(page.items).map(HomeItem::PlaylistItem)) },
                    (communityResult as? CallResult.Ok)?.value?.let { page -> LofiGenre.shelf(LofiGenre.COMMUNITY_TITLE, page.items.take(LofiGenre.MAX_ITEMS).map(HomeItem::PlaylistItem)) },
                )
            if (shelves.isNotEmpty()) {
                CallResult.Ok(HomePage(shelves, continuation = null))
            } else {
                listOf(liveResult, featuredResult, communityResult).firstNotNullOfOrNull { it as? CallResult.Failed } ?: CallResult.Ok(HomePage(emptyList(), continuation = null))
            }
        }

    private suspend fun searchPlaylists(
        query: String,
        params: String,
    ): CallResult<SearchPage<PlaylistSummary>> =
        session.call(operation = "search", block = searchOrContinue(query, params, null)) { response ->
            ParseOutcome.Parsed(MusicParsers.searchPlaylists(json(response)))
        }

    /** 打ち込み中の候補。 */
    suspend fun searchSuggestions(input: String): CallResult<SearchSuggestions> =
        session.call(
            operation = "get_search_suggestions",
            block = { it.getSearchSuggestions(YouTubeClient.WEB_REMIX, input) },
        ) { response -> ParseOutcome.Parsed(MusicParsers.searchSuggestions(json(response))) }

    private fun searchOrContinue(
        query: String,
        params: String,
        continuation: String?,
    ): suspend (InnerTube) -> HttpResponse =
        {
            if (continuation == null) {
                it.search(YouTubeClient.WEB_REMIX, query = query, params = params)
            } else {
                it.search(YouTubeClient.WEB_REMIX, continuation = continuation)
            }
        }

    /**
     * 次に流れる曲（ラジオ）。最初は videoId と playlistId（`RDAMVM<videoId>`）、続きは continuation と playlistId で取る。
     */
    suspend fun upNext(
        videoId: String?,
        playlistId: String?,
        continuation: String? = null,
        expectedGeneration: Long? = null,
    ): CallResult<UpNextPage> =
        session.call(
            operation = "next",
            expectedGeneration = expectedGeneration,
            block = { it.next(YouTubeClient.WEB_REMIX, videoId = videoId, playlistId = playlistId, continuation = continuation) },
        ) { response -> ParseOutcome.Parsed(MusicParsers.upNext(json(response))) }

    /** ライブラリのプレイリスト（全ページ）。ログイン案内だけが返ったら、ログアウト相当の応答として扱う（確認の accountMenu が走る）。 */
    suspend fun libraryPlaylists(onPage: (List<PlaylistSummary>) -> Unit = {}): CallResult<List<PlaylistSummary>> =
        allLibraryPages(LIBRARY_PLAYLISTS, MusicParsers::playlists, { it.playlistId }, onPage)

    /**
     * 再生履歴（自分が聴いた曲）。ログイン必須。送信の設定（ADR-0006）とは別で、設定を切っていても見られる（切っている間の分は載らない）。
     */
    suspend fun history(continuation: String? = null): CallResult<HistoryPage> =
        session.call(
            operation = "browse",
            block = browseOrContinue(HISTORY, continuation),
        ) { response ->
            val root = json(response)
            lastRendererNames = MusicParsers.rendererNames(root)
            if (MusicParsers.isSignInPrompt(root)) ParseOutcome.LoggedOutResponse else ParseOutcome.Parsed(MusicParsers.historyPage(root))
        }

    /**
     * プレイリストの曲。continuation を渡したら続きのページ（並べ方は最初のページのものが続く）。高く評価した曲は playlistId = LIKED_SONGS。
     * @param order 最初のページの並べ方。null ならサーバーが覚えている並べ方（自分のプレイリストでは、選ぶとサーバーに覚えられる）。
     *   カスタムの降順はここでは扱わない（呼ぶ側が全部読んで逆にする）
     */
    suspend fun playlist(
        playlistId: String,
        continuation: String? = null,
        expectedGeneration: Long? = null,
        order: PlaylistOrder? = null,
    ): CallResult<PlaylistPage> =
        session.call(
            operation = "browse",
            expectedGeneration = expectedGeneration,
            block = browseOrContinue("VL$playlistId", continuation, order?.params),
        ) { response ->
            val root = json(response)
            lastRendererNames = MusicParsers.rendererNames(root)
            if (MusicParsers.isSignInPrompt(root)) ParseOutcome.LoggedOutResponse else ParseOutcome.Parsed(MusicParsers.playlistPage(root))
        }

    /** 今のログインの世代。複数の呼び出しにまたがる操作を、始めたときのログインに結び付けるために使う。 */
    fun currentLoginGeneration(): Long = session.loginGenerationId()

    /** 曲の評価（next の最初の応答から取る）。 */
    suspend fun likeStatus(
        videoId: String,
        expectedGeneration: Long? = null,
    ): CallResult<LikeStatus> =
        session.call(
            operation = "next",
            expectedGeneration = expectedGeneration,
            block = { it.next(YouTubeClient.WEB_REMIX, videoId = videoId, playlistId = null) },
        ) { response -> parsedOrInvalid(MusicParsers.likeStatus(json(response), videoId)) }

    /** 曲の種類と、見せる映像（音源なら対応する MV と時刻の対応表）。曲が応答に無ければ Invalid。 */
    suspend fun musicVideoInfo(videoId: String): CallResult<MusicVideoInfo> =
        session.call(
            operation = "next",
            block = { it.next(YouTubeClient.WEB_REMIX, videoId = videoId, playlistId = null) },
        ) { response -> parsedOrInvalid(MusicParsers.musicVideoInfo(json(response), videoId)) }

    /**
     * 曲の歌詞（時刻なし）。next で歌詞のタブを探し、あれば browse で読む。歌詞の無い曲は Ok(null)。
     */
    suspend fun lyrics(videoId: String): CallResult<Lyrics.Plain?> {
        val browseId =
            when (
                val tab =
                    session.call(
                        operation = "next",
                        block = { it.next(YouTubeClient.WEB_REMIX, videoId = videoId, playlistId = null) },
                    ) { response -> ParseOutcome.Parsed(MusicParsers.lyricsBrowseId(json(response))) }
            ) {
                is CallResult.Failed -> return tab
                is CallResult.Ok -> tab.value ?: return CallResult.Ok(null)
            }
        return session.call(
            operation = "browse",
            block = browseOrContinue(browseId, continuation = null),
        ) { response -> ParseOutcome.Parsed(MusicParsers.plainLyrics(json(response))) }
    }

    /**
     * 高く評価する（liked = true）・評価を外す（false）。書き込みの規則は WaveSession.callWrite。
     * 応答の中身は確かめない（2xx の JSON なら反映されたとみなす）。
     */
    suspend fun setLiked(
        videoId: String,
        liked: Boolean,
        expectedGeneration: Long? = null,
    ): WriteResult<Unit> =
        session.callWrite(
            operation = if (liked) "like" else "removelike",
            block = { if (liked) it.likeVideo(YouTubeClient.WEB_REMIX, videoId) else it.unlikeVideo(YouTubeClient.WEB_REMIX, videoId) },
            expectedGeneration = expectedGeneration,
        ) { response ->
            // 読めない応答の例外は callWrite が Invalid（Unknown）にする。キャンセルは握りつぶさない
            json(response)
            ParseOutcome.Parsed(Unit)
        }

    /**
     * アーティスト（チャンネル）を登録する（subscribe = true）・登録を外す（false）。書き込みの規則は setLiked と同じ。
     */
    suspend fun setSubscribed(
        channelId: String,
        subscribe: Boolean,
        expectedGeneration: Long? = null,
    ): WriteResult<Unit> =
        session.callWrite(
            operation = if (subscribe) "subscribe" else "unsubscribe",
            block = { if (subscribe) it.subscribeChannel(YouTubeClient.WEB_REMIX, channelId) else it.unsubscribeChannel(YouTubeClient.WEB_REMIX, channelId) },
            expectedGeneration = expectedGeneration,
        ) { response ->
            json(response)
            ParseOutcome.Parsed(Unit)
        }

    /**
     * 曲をプレイリストに追加する（決定 G-1）。innertubex は重複を避ける指定を送らないので、先に全ページを読んで確かめる。
     * 読み取りに失敗したら追加しない。読むのと追加の間に別の端末で足された場合の重複は受け入れる。
     */
    suspend fun addToPlaylist(
        playlistId: String,
        videoId: String,
        expectedGeneration: Long = currentLoginGeneration(),
        /** 作ったばかりで空と分かっているプレイリストでは、重複の確認の読み取りを省く（作成直後は読めないことがある）。 */
        knownEmpty: Boolean = false,
    ): AddToPlaylistResult {
        if (knownEmpty) return writeAdd(playlistId, videoId, expectedGeneration)
        return when (val scan = scanPlaylist(playlistId, expectedGeneration)) {
            is PlaylistScan.Incomplete -> AddToPlaylistResult.NotChecked(scan.kind)
            is PlaylistScan.Ok -> if (videoId in scan.videoIds) AddToPlaylistResult.AlreadyInPlaylist else writeAdd(playlistId, videoId, expectedGeneration)
        }
    }

    /**
     * プレイリストの中の曲の ID を全ページ読む（重複の確認と、「どのプレイリストに入っているか」の表示に使う）。
     * 続きを読み取れないまま終わったときは、最後まで読んだとみなさない。
     */
    suspend fun scanPlaylist(
        playlistId: String,
        expectedGeneration: Long = currentLoginGeneration(),
    ): PlaylistScan {
        val ids = mutableSetOf<String>()
        val setIds = mutableMapOf<String, MutableList<String>>()
        var continuation: String? = null
        var pages = 0
        do {
            when (val page = playlist(playlistId, continuation, expectedGeneration)) {
                is CallResult.Failed -> return PlaylistScan.Incomplete(page.kind)
                is CallResult.Ok -> {
                    ids += page.value.songs.map { it.videoId }
                    page.value.songs.forEachIndexed { i, song -> page.value.setVideoIds.getOrNull(i)?.let { setIds.getOrPut(song.videoId) { mutableListOf() } += it } }
                    continuation = page.value.continuation
                    // 続きが読めなかっただけかもしれない（応答の形の変化）なら、全部読んだとはみなさない
                    if (continuation == null && !page.value.endConfirmed) return PlaylistScan.Incomplete(FailureKind.Parse)
                }
            }
            // 続きのトークンが循環しても止まるように、ページ数に上限を置く（1ページ100曲で5000曲）
            if (++pages >= MAX_PLAYLIST_PAGES && continuation != null) return PlaylistScan.Incomplete(FailureKind.Parse)
        } while (continuation != null)
        return PlaylistScan.Ok(ids, setIds)
    }

    private suspend fun writeAdd(
        playlistId: String,
        videoId: String,
        expectedGeneration: Long,
    ): AddToPlaylistResult =
        when (val result = editPlaylist("add", expectedGeneration) { it.addToPlaylist(YouTubeClient.WEB_REMIX, playlistId, videoId) }) {
            is WriteResult.Done -> AddToPlaylistResult.Added
            is WriteResult.Failed -> AddToPlaylistResult.Failed(result.kind, result.status)
            WriteResult.Unknown -> AddToPlaylistResult.Unknown
        }

    /** プレイリストから1項目を外す。setVideoId はプレイリストのページ（PlaylistPage.setVideoIds）の値。 */
    suspend fun removeFromPlaylist(
        playlistId: String,
        videoId: String,
        setVideoId: String,
        expectedGeneration: Long = currentLoginGeneration(),
    ): WriteResult<Unit> = editPlaylist("remove", expectedGeneration) { it.removePlaylistSong(YouTubeClient.WEB_REMIX, playlistId, setVideoId, videoId) }

    /** 自分のプレイリストを消す（確かめで作ったものの後片付けに使う。画面からは使っていない）。playlistId は先頭の VL を除いたもの。 */
    suspend fun deletePlaylist(
        playlistId: String,
        expectedGeneration: Long = currentLoginGeneration(),
    ): WriteResult<Unit> =
        session.callWrite(
            operation = "playlist/delete",
            block = { it.deletePlaylist(YouTubeClient.WEB_REMIX, playlistId.removePrefix("VL")) },
            expectedGeneration = expectedGeneration,
        ) { ParseOutcome.Parsed(Unit) }

    /** 非公開のプレイリストを作る（innertubex が PRIVATE 固定）。作った playlistId を返す。 */
    suspend fun createPlaylist(
        title: String,
        expectedGeneration: Long = currentLoginGeneration(),
    ): WriteResult<String> =
        session.callWrite(
            operation = "playlist/create",
            block = { it.createPlaylist(YouTubeClient.WEB_REMIX, title) },
            expectedGeneration = expectedGeneration,
        ) { response ->
            val id = MusicParsers.createdPlaylistId(json(response))
            if (id != null) ParseOutcome.Parsed(id) else ParseOutcome.Invalid
        }

    /**
     * browse/edit_playlist。`STATUS_SUCCEEDED` だけを成功とし、ほかの status は反映されていない応答、status が無ければ分からないとする。
     */
    private suspend fun editPlaylist(
        operation: String,
        expectedGeneration: Long,
        block: suspend (com.metrolist.innertubex.InnerTube) -> HttpResponse,
    ): WriteResult<Unit> {
        val result =
            session.callWrite(operation = "edit_playlist/$operation", block = block, expectedGeneration = expectedGeneration) { response ->
                val status = MusicParsers.editPlaylistStatus(json(response))
                parsedOrInvalid(status)
            }
        return when (result) {
            is WriteResult.Done -> if (result.value == STATUS_SUCCEEDED) WriteResult.Done(Unit) else WriteResult.Failed(FailureKind.Http)
            is WriteResult.Failed -> result
            WriteResult.Unknown -> WriteResult.Unknown
        }
    }

    /** アルバムのページ（`MPREb_…`）。公開データだが、ログイン中の表示に合わせて cookie 付きで取る。 */
    suspend fun album(browseId: String): CallResult<AlbumPage> =
        session.call(
            operation = "browse",
            block = browseOrContinue(browseId, continuation = null),
        ) { response ->
            val root = json(response)
            lastRendererNames = MusicParsers.rendererNames(root)
            lastKeyPaths = MusicParsers.keyPaths(root, "playlistId")
            parsedOrInvalid(MusicParsers.albumPage(root, browseId))
        }

    /** アーティストのページ（`UC…`）。 */
    suspend fun artist(browseId: String): CallResult<ArtistPage> =
        session.call(
            operation = "browse",
            block = browseOrContinue(browseId, continuation = null),
        ) { response ->
            val root = json(response)
            lastRendererNames = MusicParsers.rendererNames(root)
            parsedOrInvalid(MusicParsers.artistPage(root))
        }

    /** 直近のアルバムの応答で playlistId が現れた場所（キー名だけ）。実アカウントの応答の形を調べる用。 */
    var lastKeyPaths: Set<String> = emptySet()
        private set

    /** 直近の応答に含まれていたレンダラ名（値は含まない）。解析が0件だったときの調査用。 */
    var lastRendererNames: Set<String> = emptySet()
        private set

    /** 最初のページは browseId、続きは continuation で読む（ログイン中の表示に合わせて cookie 付き）。 */
    private fun browseOrContinue(
        browseId: String,
        continuation: String?,
        params: String? = null,
    ): suspend (com.metrolist.innertubex.InnerTube) -> HttpResponse =
        {
            if (continuation == null) {
                it.browse(YouTubeClient.WEB_REMIX, browseId = browseId, params = params, setLogin = true)
            } else {
                it.browse(YouTubeClient.WEB_REMIX, continuation = continuation, setLogin = true)
            }
        }

    /** 読めなければ（形が違えば）Invalid。 */
    private fun <T> parsedOrInvalid(value: T?): ParseOutcome<T> = if (value != null) ParseOutcome.Parsed(value) else ParseOutcome.Invalid

    private suspend fun json(response: HttpResponse): JsonObject =
        WaveSession.json.parseToJsonElement(response.bodyAsTextLimited(MAX_BROWSE_BYTES)) as JsonObject

    companion object {
        /** 検索を曲に絞る params。 */
        const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        const val ALBUMS_FILTER = "EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D"
        const val ARTISTS_FILTER = "EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D"
        const val PODCASTS_FILTER = "EgWKAQJQAWoKEAkQBRAKEAMQBA%3D%3D"
        const val EPISODES_FILTER = "EgWKAQJIAWoKEAkQBRAKEAMQBA%3D%3D"

        /** ライブラリのポッドキャスト（保存した番組）。 */
        const val LIBRARY_PODCASTS = "FEmusic_library_non_music_audio_list"

        /**
         * 保存した番組の新しいエピソード（ログインしたときだけ中身がある自動のプレイリスト）。
         * browse すると番組のページと同じ形（エピソードの行）で返るので、[podcast] で `VL` を付けて読む（実アカウントでレンダラ名だけ確かめた、2026-10-13）。
         */
        const val NEW_EPISODES = "RDPN"
        const val NEW_EPISODES_BROWSE = "VL$NEW_EPISODES"
        const val VIDEOS_FILTER = "EgWKAQIQAWoSEAUQBBAJEAMQChAVEA4QERAQ"
        const val FEATURED_PLAYLISTS_FILTER = "EgeKAQQoADgBahIQBRAEEAkQAxAKEBUQDhAREBA%3D"
        const val COMMUNITY_PLAYLISTS_FILTER = "EgeKAQQoAEABahIQBRAEEAkQAxAKEBUQDhAREBA%3D"

        const val LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"
        const val HISTORY = "FEmusic_history"
        const val HOME = "FEmusic_home"
        const val LIBRARY_LANDING = "FEmusic_library_landing"
        const val MOODS = "FEmusic_moods_and_genres"
        const val LIBRARY_ARTISTS = "FEmusic_library_corpus_track_artists"
        const val SUBSCRIBED_ARTISTS = "FEmusic_library_corpus_artists"
        const val LIBRARY_ALBUMS = "FEmusic_liked_albums"
        const val MOOD_CATEGORY = "FEmusic_moods_and_genres_category"
        const val NEW_RELEASES = "FEmusic_new_releases_albums"
        const val LIKED_SONGS = "LM"

        private const val MAX_BROWSE_BYTES = 8 * 1024 * 1024
        private const val MAX_PLAYLIST_PAGES = 50
        private const val MAX_LIBRARY_PAGES = 20
        private const val STATUS_SUCCEEDED = "STATUS_SUCCEEDED"
    }
}
