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

    /**
     * 曲の、もう1つの言語（画面が日本語なら英語、それ以外なら日本語）での曲名とアーティスト名。
     * YouTube Music では「緑黄色社会」、LRCLIB では「Ryokuoushoku Shakai」のように、言語で表記の違う曲を一致させるために使う。
     */
    data class OtherNames(
        val title: String,
        val artist: String,
    )

    /**
     * 時刻付きの歌詞を探す。まず曲名・アーティスト・アルバム・長さで1件を引き、無ければ検索して曲名とアーティストの合うもののうち長さの近いものを選ぶ。
     * 検索の結果に時刻付きの候補があるのに1つも一致しなければ、[otherNames] で曲名・アーティスト名の別名を取り、足してもう一度選ぶ（[otherNames] は例外を投げない）。
     */
    suspend fun synced(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
        otherNames: suspend () -> OtherNames? = { null },
    ): Result =
        try {
            exact(title, artist, album, durationSeconds)
                ?.let { Result.Found(it) }
                ?: searched(title, artist, durationSeconds, otherNames)?.let { Result.Found(it) }
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

    private suspend fun searched(
        title: String,
        artist: String,
        durationSeconds: Int?,
        otherNames: suspend () -> OtherNames?,
    ): List<LyricLine>? {
        val titles = searchTitles(title)
        val seen = mutableListOf<Track>()
        for (searchTitle in titles) {
            val tracks = search(searchTitle, artist)
            pick(tracks, title, artist, durationSeconds)?.let(::linesOf)?.let { return it }
            seen += tracks
        }
        // アーティスト名を付けて探すと、LRCLIB で別の表記（ローマ字など）で登録された曲は返らない。曲名だけでも探して候補に足す
        for (searchTitle in titles) seen += search(searchTitle, artist = null)
        return pickWithOtherNames(seen, title, artist, durationSeconds, otherNames)?.let(::linesOf)
    }

    private suspend fun search(
        searchTitle: String,
        artist: String?,
    ): List<Track> {
        val response =
            http.get("$baseUrl/api/search") {
                header(HttpHeaders.UserAgent, userAgent)
                parameter("track_name", searchTitle)
                if (artist != null) parameter("artist_name", artist)
            }
        if (response.status.value != 200) throw IllegalStateException("lrclib search status=${response.status.value}")
        return json.decodeFromString(ListSerializer(Track.serializer()), response.bodyAsText())
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

        /**
         * 検索の結果から、曲名とアーティストが一致する時刻付きのうち長さの近いものを選ぶ。長さが分からなければ最初の一致。
         * 一致するものが無ければ選ばない（長さだけが近い別の曲を選ばないため）。
         * 曲名が一致しなくても、アーティストが一致して長さが [LrclibMatch.SAME_LENGTH_WITHOUT_TITLE] 秒以内なら残す。
         */
        internal fun pick(
            tracks: List<Track>,
            title: String,
            artist: String,
            durationSeconds: Int?,
        ): Track? = pickAny(tracks, listOf(title), listOf(artist), durationSeconds)

        /**
         * 元の名前で選び、時刻付きの候補があるのに1つも一致しなければ、別名（[otherNames]）を足してもう一度選ぶ。
         * 一致したとき・時刻付きの候補が無いときは [otherNames] を呼ばない（別名を取りに行かない）。
         */
        internal suspend fun pickWithOtherNames(
            tracks: List<Track>,
            title: String,
            artist: String,
            durationSeconds: Int?,
            otherNames: suspend () -> OtherNames?,
        ): Track? {
            pick(tracks, title, artist, durationSeconds)?.let { return it }
            if (tracks.none(::isSynced)) return null
            val other = otherNames() ?: return null
            return pickAny(tracks, listOf(title, other.title), listOf(artist, other.artist), durationSeconds)
        }

        private fun isSynced(track: Track): Boolean = !track.instrumental && !track.syncedLyrics.isNullOrBlank()

        /** [pick] と同じだが、曲名・アーティストはそれぞれ [titles]・[artists] のどれかに一致すれば良い（元の名前と別名）。 */
        private fun pickAny(
            tracks: List<Track>,
            titles: List<String>,
            artists: List<String>,
            durationSeconds: Int?,
        ): Track? {
            val wantedTitles = titles.flatMap(LrclibMatch::titleKeys)
            val wantedArtists = artists.flatMap(LrclibMatch::artistKeys)
            val matching =
                tracks
                    .filter(::isSynced)
                    .filter { track ->
                        LrclibMatch.overlap(wantedArtists, LrclibMatch.artistKeys(track.artistName.orEmpty())) &&
                            (
                                LrclibMatch.overlap(wantedTitles, LrclibMatch.titleKeys(track.trackName.orEmpty())) ||
                                    (durationSeconds != null && track.duration != null && abs(track.duration - durationSeconds) <= LrclibMatch.SAME_LENGTH_WITHOUT_TITLE)
                            )
                    }
            if (durationSeconds == null) return matching.firstOrNull()
            return DURATION_TOLERANCES.firstNotNullOfOrNull { tolerance ->
                matching
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
