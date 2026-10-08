package xyz.mooma0750.wave.core.music

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 登録したアーティストの新作（アルバム・シングル・動画）。 */
data class NewRelease(
    val artistId: String,
    val artistName: String,
    val title: String,
    /** アルバム・シングルなら browseId（`MPRE…`）。 */
    val albumBrowseId: String?,
    /** 動画なら videoId。 */
    val videoId: String?,
    val thumbnailUrl: String?,
)

/**
 * 新作の見張り。アーティストごとに「前に見たアルバム・動画の ID」を覚え、増えたものを新作とする。
 * 初めて見るアーティストは覚えるだけで知らせない（登録した直後に過去の作品をすべて知らせない）。
 */
@Serializable
data class ReleaseWatchState(
    val seen: Map<String, Set<String>> = emptyMap(),
) {
    /** ページを見て、新作と次の状態を返す。 */
    fun observe(
        artistId: String,
        page: ArtistPage,
    ): Pair<List<NewRelease>, ReleaseWatchState> {
        val albums = page.albumShelves.flatMap { it.albums }.distinctBy { it.browseId }
        val ids = albums.map { "a:${it.browseId}" } + page.videos.map { "v:${it.videoId}" }
        // 棚が読めなかった（空）ときは覚えている分を消さない
        if (ids.isEmpty()) return emptyList<NewRelease>() to this
        val before = seen[artistId]
        val next = copy(seen = seen + (artistId to ((before.orEmpty() + ids).toSet().takeLastIds(MAX_IDS_PER_ARTIST, ids))))
        if (before == null) return emptyList<NewRelease>() to next
        val fresh =
            albums.filter { "a:${it.browseId}" !in before }.map { NewRelease(artistId, page.name, it.title, it.browseId, null, it.thumbnailUrl) } +
                page.videos.filter { "v:${it.videoId}" !in before }.map { NewRelease(artistId, page.name, it.title, null, it.videoId, it.thumbnailUrl) }
        return fresh to next
    }

    /** 登録を外したアーティストを忘れる。 */
    fun retain(artistIds: Set<String>): ReleaseWatchState = copy(seen = seen.filterKeys { it in artistIds })

    companion object {
        private const val MAX_IDS_PER_ARTIST = 300
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): ReleaseWatchState? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        fun encode(state: ReleaseWatchState): String = json.encodeToString(serializer(), state)

        /** 覚える ID が増えすぎないように、今ページにあるものを優先して残す。 */
        private fun Set<String>.takeLastIds(
            max: Int,
            current: List<String>,
        ): Set<String> = if (size <= max) this else (current + (this - current.toSet())).take(max).toSet()
    }
}
