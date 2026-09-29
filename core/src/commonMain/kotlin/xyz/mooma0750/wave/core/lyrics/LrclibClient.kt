package xyz.mooma0750.wave.core.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * LRCLIB（lrclib.net。投稿型の時刻付き歌詞の集まり）から歌詞を探す。
 * 送るのは曲名・アーティスト名・アルバム名・長さだけ。http には認証情報を持たないクライアントを渡す（ADR-0003。YouTube の cookie と混ぜない）。
 */
class LrclibClient(
    private val http: HttpClient,
    /** LRCLIB はアプリ名と版を User-Agent に入れるよう求めている。 */
    private val userAgent: String,
    private val baseUrl: String = "https://lrclib.net",
) {
    /** 探した結果。 */
    sealed interface Result {
        data class Found(
            val lines: List<LyricLine>,
        ) : Result

        data object NotFound : Result

        /** 通信・応答の失敗（あとでもう一度探してよい）。 */
        data object Failed : Result
    }

    @Serializable
    internal data class Track(
        val trackName: String? = null,
        val artistName: String? = null,
        val albumName: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean = false,
        val syncedLyrics: String? = null,
    )

    /** 時刻付きの歌詞を探す。まず曲名・アーティスト・アルバム・長さで1件を引き、無ければ検索して長さの近いものを選ぶ。 */
    suspend fun synced(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
    ): Result =
        try {
            exact(title, artist, album, durationSeconds)
                ?.let { Result.Found(it) }
                ?: searchTitles(title).firstNotNullOfOrNull { search(it, artist, durationSeconds) }?.let { Result.Found(it) }
                ?: Result.NotFound
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            Result.Failed
        }

    private suspend fun exact(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
    ): List<LyricLine>? {
        val response =
            http.get("$baseUrl/api/get") {
                header(HttpHeaders.UserAgent, userAgent)
                parameter("track_name", title)
                parameter("artist_name", artist)
                if (album != null) parameter("album_name", album)
                if (durationSeconds != null) parameter("duration", durationSeconds)
            }
        return when (response.status.value) {
            200 -> json.decodeFromString(Track.serializer(), response.bodyAsText()).let(::linesOf)
            404 -> null
            else -> throw IllegalStateException("lrclib get status=${response.status.value}")
        }
    }

    private suspend fun search(
        title: String,
        artist: String,
        durationSeconds: Int?,
    ): List<LyricLine>? {
        val response =
            http.get("$baseUrl/api/search") {
                header(HttpHeaders.UserAgent, userAgent)
                parameter("track_name", title)
                parameter("artist_name", artist)
            }
        if (response.status.value != 200) throw IllegalStateException("lrclib search status=${response.status.value}")
        val tracks = json.decodeFromString(ListSerializer(Track.serializer()), response.bodyAsText())
        return pick(tracks, durationSeconds)?.let(::linesOf)
    }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 長さの差の許容（秒）。近い方から順に試す。同じ曲名でも長さの違う版（別の録音・短縮版）が混じるため。 */
        private val DURATION_TOLERANCES = listOf(2.0, 5.0)

        private fun linesOf(track: Track): List<LyricLine>? =
            track
                .takeIf { !it.instrumental }
                ?.syncedLyrics
                ?.let(Lrc::parse)
                ?.takeIf { lines -> lines.any { it.text.isNotBlank() } }

        /** 検索の結果から、時刻付きで長さの近いものを選ぶ。長さが分からなければ最初の時刻付き。 */
        internal fun pick(
            tracks: List<Track>,
            durationSeconds: Int?,
        ): Track? {
            val synced = tracks.filter { !it.instrumental && !it.syncedLyrics.isNullOrBlank() }
            if (durationSeconds == null) return synced.firstOrNull()
            return DURATION_TOLERANCES.firstNotNullOfOrNull { tolerance ->
                synced
                    .filter { it.duration != null && abs(it.duration - durationSeconds) <= tolerance }
                    .minByOrNull { abs(it.duration!! - durationSeconds) }
            }
        }

        /**
         * 検索に使う曲名。YouTube Music の曲名に付く「(From "THE FIRST TAKE")」「[Remastered]」「 - Orion」のような添え書きを外したものも試す。
         */
        internal fun searchTitles(title: String): List<String> {
            val withoutBrackets = title.replace(Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]"""), "").trim()
            val beforeDash = withoutBrackets.substringBefore(" - ").trim()
            return listOf(title, withoutBrackets, beforeDash).filter { it.isNotBlank() }.distinct()
        }
    }
}
