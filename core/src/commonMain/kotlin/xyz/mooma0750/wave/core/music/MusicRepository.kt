package xyz.mooma0750.wave.core.music

import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonObject
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.core.session.ParseOutcome
import xyz.mooma0750.wave.core.session.WaveSession
import xyz.mooma0750.wave.core.session.WriteResult

/** 曲・プレイリスト・ラジオの取得。通信は WaveSession を通し、失敗は FailureKind のまま返す。 */
class MusicRepository(
    private val session: WaveSession,
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

    /** ライブラリのプレイリスト。ログイン案内だけが返ったら、ログアウト相当の応答として扱う（確認の accountMenu が走る）。 */
    suspend fun libraryPlaylists(): CallResult<List<PlaylistSummary>> =
        session.call(
            operation = "browse",
            block = { it.browse(YouTubeClient.WEB_REMIX, browseId = LIBRARY_PLAYLISTS, setLogin = true) },
        ) { response ->
            val root = json(response)
            if (MusicParsers.isSignInPrompt(root)) {
                ParseOutcome.LoggedOutResponse
            } else {
                lastCardMenuIcons = MusicParsers.playlistCardMenuIcons(root)
                ParseOutcome.Parsed(MusicParsers.playlists(root)).also { lastRendererNames = MusicParsers.rendererNames(root) }
            }
        }

    /** 直近のライブラリの応答の、カードごとのメニューのアイコン名。調査用。 */
    var lastCardMenuIcons: List<Set<String>> = emptyList()
        private set

    /** プレイリストの曲。continuation を渡したら続きのページ。高く評価した曲は playlistId = LIKED_SONGS。 */
    suspend fun playlist(
        playlistId: String,
        continuation: String? = null,
        expectedGeneration: Long? = null,
    ): CallResult<PlaylistPage> =
        session.call(
            operation = "browse",
            expectedGeneration = expectedGeneration,
            block = {
                if (continuation == null) {
                    it.browse(YouTubeClient.WEB_REMIX, browseId = "VL$playlistId", setLogin = true)
                } else {
                    it.browse(YouTubeClient.WEB_REMIX, continuation = continuation, setLogin = true)
                }
            },
        ) { response ->
            val root = json(response)
            lastRendererNames = MusicParsers.rendererNames(root)
            lastKeyPaths = MusicParsers.keyPathsLike(root, "continuation")
            if (MusicParsers.isSignInPrompt(root)) ParseOutcome.LoggedOutResponse else ParseOutcome.Parsed(MusicParsers.playlistPage(root))
        }

    /** 今のログインの世代。複数の呼び出しにまたがる操作を、始めたときのログインに結び付けるために使う。 */
    fun currentLoginGeneration(): Long = session.currentGenerationId()

    /** 曲の評価（next の最初の応答から取る）。 */
    suspend fun likeStatus(
        videoId: String,
        expectedGeneration: Long? = null,
    ): CallResult<LikeStatus> =
        session.call(
            operation = "next",
            expectedGeneration = expectedGeneration,
            block = { it.next(YouTubeClient.WEB_REMIX, videoId = videoId, playlistId = null) },
        ) { response -> MusicParsers.likeStatus(json(response), videoId)?.let { ParseOutcome.Parsed(it) } ?: ParseOutcome.Invalid }

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
        var continuation: String? = null
        var pages = 0
        do {
            when (val page = playlist(playlistId, continuation, expectedGeneration)) {
                is CallResult.Failed -> return AddToPlaylistResult.NotChecked(page.kind)
                is CallResult.Ok -> {
                    if (page.value.songs.any { it.videoId == videoId }) return AddToPlaylistResult.AlreadyInPlaylist
                    continuation = page.value.continuation
                    // 続きが読めなかっただけかもしれない（応答の形の変化）なら、全部読んだとはみなさず追加しない
                    if (continuation == null && !page.value.endConfirmed) return AddToPlaylistResult.NotChecked(FailureKind.Parse)
                }
            }
            // 続きのトークンが循環しても止まるように、ページ数に上限を置く（1ページ100曲で5000曲）
            if (++pages >= MAX_PLAYLIST_PAGES && continuation != null) return AddToPlaylistResult.NotChecked(FailureKind.Parse)
        } while (continuation != null)
        return writeAdd(playlistId, videoId, expectedGeneration)
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
                if (status != null) ParseOutcome.Parsed(status) else ParseOutcome.Invalid
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
            block = { it.browse(YouTubeClient.WEB_REMIX, browseId = browseId, setLogin = true) },
        ) { response ->
            val root = json(response)
            lastRendererNames = MusicParsers.rendererNames(root)
            lastKeyPaths = MusicParsers.keyPaths(root, "playlistId")
            MusicParsers.albumPage(root, browseId)?.let { ParseOutcome.Parsed(it) } ?: ParseOutcome.Invalid
        }

    /** アーティストのページ（`UC…`）。 */
    suspend fun artist(browseId: String): CallResult<ArtistPage> =
        session.call(
            operation = "browse",
            block = { it.browse(YouTubeClient.WEB_REMIX, browseId = browseId, setLogin = true) },
        ) { response ->
            val root = json(response)
            lastRendererNames = MusicParsers.rendererNames(root)
            MusicParsers.artistPage(root)?.let { ParseOutcome.Parsed(it) } ?: ParseOutcome.Invalid
        }

    /** 直近のアルバムの応答で playlistId が現れた場所（キー名だけ）。実アカウントの応答の形を調べる用。 */
    var lastKeyPaths: Set<String> = emptySet()
        private set

    /** 直近の応答に含まれていたレンダラ名（値は含まない）。解析が0件だったときの調査用。 */
    var lastRendererNames: Set<String> = emptySet()
        private set

    private suspend fun json(response: HttpResponse): JsonObject =
        WaveSession.json.parseToJsonElement(response.bodyAsTextLimited(MAX_BROWSE_BYTES)) as JsonObject

    companion object {
        /** 検索を曲に絞る params。 */
        const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"

        const val LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"
        const val LIKED_SONGS = "LM"

        /** ある曲から始まるラジオの playlistId。 */
        fun radioPlaylistId(videoId: String): String = "RDAMVM$videoId"

        private const val MAX_BROWSE_BYTES = 8 * 1024 * 1024
        private const val MAX_PLAYLIST_PAGES = 50
        private const val STATUS_SUCCEEDED = "STATUS_SUCCEEDED"
    }
}
