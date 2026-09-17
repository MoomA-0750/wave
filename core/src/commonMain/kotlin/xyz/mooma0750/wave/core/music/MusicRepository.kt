package xyz.mooma0750.wave.core.music

import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonObject
import xyz.mooma0750.wave.core.session.CallResult
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
    ): CallResult<UpNextPage> =
        session.call(
            operation = "next",
            block = { it.next(YouTubeClient.WEB_REMIX, videoId = videoId, playlistId = playlistId, continuation = continuation) },
        ) { response -> ParseOutcome.Parsed(MusicParsers.upNext(json(response))) }

    /** ライブラリのプレイリスト。ログイン案内だけが返ったら、ログアウト相当の応答として扱う（確認の accountMenu が走る）。 */
    suspend fun libraryPlaylists(): CallResult<List<PlaylistSummary>> =
        session.call(
            operation = "browse",
            block = { it.browse(YouTubeClient.WEB_REMIX, browseId = LIBRARY_PLAYLISTS, setLogin = true) },
        ) { response ->
            val root = json(response)
            if (MusicParsers.isSignInPrompt(root)) ParseOutcome.LoggedOutResponse else ParseOutcome.Parsed(MusicParsers.playlists(root)).also { lastRendererNames = MusicParsers.rendererNames(root) }
        }

    /** プレイリストの曲。continuation を渡したら続きのページ。高く評価した曲は playlistId = LIKED_SONGS。 */
    suspend fun playlist(
        playlistId: String,
        continuation: String? = null,
    ): CallResult<PlaylistPage> =
        session.call(
            operation = "browse",
            block = {
                if (continuation == null) {
                    it.browse(YouTubeClient.WEB_REMIX, browseId = "VL$playlistId", setLogin = true)
                } else {
                    it.browse(YouTubeClient.WEB_REMIX, continuation = continuation, setLogin = true)
                }
            },
        ) { response ->
            val root = json(response)
            if (MusicParsers.isSignInPrompt(root)) ParseOutcome.LoggedOutResponse else ParseOutcome.Parsed(MusicParsers.playlistPage(root)).also { lastRendererNames = MusicParsers.rendererNames(root) }
        }

    /** 曲の評価（next の最初の応答から取る）。 */
    suspend fun likeStatus(videoId: String): CallResult<LikeStatus> =
        session.call(
            operation = "next",
            block = { it.next(YouTubeClient.WEB_REMIX, videoId = videoId, playlistId = null) },
        ) { response -> MusicParsers.likeStatus(json(response), videoId)?.let { ParseOutcome.Parsed(it) } ?: ParseOutcome.Invalid }

    /**
     * 高く評価する（liked = true）・評価を外す（false）。書き込みの規則は WaveSession.callWrite。
     * 応答の中身は確かめない（2xx の JSON なら反映されたとみなす）。
     */
    suspend fun setLiked(
        videoId: String,
        liked: Boolean,
    ): WriteResult<Unit> =
        session.callWrite(
            operation = if (liked) "like" else "removelike",
            block = { if (liked) it.likeVideo(YouTubeClient.WEB_REMIX, videoId) else it.unlikeVideo(YouTubeClient.WEB_REMIX, videoId) },
        ) { response ->
            runCatching { json(response) }.fold(onSuccess = { ParseOutcome.Parsed(Unit) }, onFailure = { ParseOutcome.Invalid })
        }

    /** アルバムのページ（`MPREb_…`）。公開データだが、ログイン中の表示に合わせて cookie 付きで取る。 */
    suspend fun album(browseId: String): CallResult<AlbumPage> =
        session.call(
            operation = "browse",
            block = { it.browse(YouTubeClient.WEB_REMIX, browseId = browseId, setLogin = true) },
        ) { response ->
            val root = json(response)
            lastRendererNames = MusicParsers.rendererNames(root)
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
    }
}
