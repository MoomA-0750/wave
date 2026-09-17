package xyz.mooma0750.wave.core.music

import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonObject
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.ParseOutcome
import xyz.mooma0750.wave.core.session.WaveSession

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

    private suspend fun json(response: HttpResponse): JsonObject =
        WaveSession.json.parseToJsonElement(response.bodyAsTextLimited(MAX_BROWSE_BYTES)) as JsonObject

    companion object {
        /** 検索を曲に絞る params。 */
        const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"

        /** ある曲から始まるラジオの playlistId。 */
        fun radioPlaylistId(videoId: String): String = "RDAMVM$videoId"

        private const val MAX_BROWSE_BYTES = 8 * 1024 * 1024
    }
}
