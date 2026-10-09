package xyz.mooma0750.wave.core.music

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 登録したアーティストの新作（アルバム・シングル・動画）。 */
@Serializable
data class NewRelease(
    val artistId: String,
    val artistName: String,
    val title: String,
    /** アルバム・シングルなら browseId（`MPRE…`）。 */
    val albumBrowseId: String?,
    /** 動画なら videoId。 */
    val videoId: String?,
    val thumbnailUrl: String?,
    /** 見つけた時刻（エポックミリ秒）。 */
    val foundAtEpochMs: Long = 0,
    /** 初めて見たときに、今年の作品として覚えたもの（知らせていない）。 */
    val seeded: Boolean = false,
)

/**
 * 棚に出す並び: アーティストごとに perArtist 件まで、アーティストが交互になるように並べる（新作を毎日出すアーティストで埋まらないように）。
 * 見つけた新作（seeded でないもの）を先に出す。
 */
fun diversifiedReleases(
    releases: List<NewRelease>,
    perArtist: Int = 2,
): List<NewRelease> {
    fun roundRobin(items: List<NewRelease>): List<NewRelease> {
        val byArtist = items.groupBy { it.artistId }.values.map { it.take(perArtist) }
        return (0 until perArtist).flatMap { round -> byArtist.mapNotNull { it.getOrNull(round) } }
    }
    return roundRobin(releases.filterNot { it.seeded }) + roundRobin(releases.filter { it.seeded })
}

/**
 * 新作の見張り。アーティストごとに「前に見たアルバム・動画の ID」を覚え、増えたアルバム・シングルのうち今年のものを新作とする。
 * 初めて見るアーティストは覚えるだけで知らせない（登録した直後に過去の作品をすべて知らせない）。
 */
@Serializable
data class ReleaseWatchState(
    val seen: Map<String, Set<String>> = emptyMap(),
    /** 最近の新作（新しい順。ホームとアーティストのページに出す）。 */
    val recent: List<NewRelease> = emptyList(),
) {
    /**
     * ページを見て、新作と次の状態を返す。
     * 初めて見るアーティストは知らせず、今年の作品（補足に今年の年がある）だけを「最近の新作」として覚える。
     */
    fun observe(
        artistId: String,
        page: ArtistPage,
        nowEpochMs: Long = 0,
        year: Int? = null,
    ): Pair<List<NewRelease>, ReleaseWatchState> {
        val albums = page.albumShelves.flatMap { it.albums }.distinctBy { it.browseId }
        val ids = albums.map { "a:${it.browseId}" } + page.videos.map { "v:${it.videoId}" }
        // 棚が読めなかった（空）ときは覚えている分を消さない
        if (ids.isEmpty()) return emptyList<NewRelease>() to this
        val before = seen[artistId]
        val next = copy(seen = seen + (artistId to ((before.orEmpty() + ids).toSet().takeLastIds(MAX_IDS_PER_ARTIST, ids))))
        if (before == null) {
            val thisYear = year?.toString() ?: return emptyList<NewRelease>() to next
            val seeds = albums.filter { it.subtitle?.contains(thisYear) == true }.map { NewRelease(artistId, page.name, it.title, it.browseId, null, it.thumbnailUrl, nowEpochMs, seeded = true) }
            return emptyList<NewRelease>() to next.withRecent(seeds)
        }
        // 新作は「初めて見た」だけでなく、今年の作品（補足に今年の年がある）に限る。棚の中身は入れ替わるので、
        // 何年も前の作品が棚に入り直しただけでも初めて見る扱いになり、通知が大量に来ていた。
        // 動画の棚（ライブ映像・切り抜きなども並ぶ）は新作として扱わない（覚えるだけ）
        val thisYear = year?.toString()
        val fresh =
            albums
                .filter { "a:${it.browseId}" !in before && (thisYear == null || it.subtitle?.contains(thisYear) == true) }
                .map { NewRelease(artistId, page.name, it.title, it.browseId, null, it.thumbnailUrl, nowEpochMs) }
        return fresh to next.withRecent(fresh)
    }

    /** 新作を「最近の新作」の先頭に足す（見つけた新作を先に、覚えただけのものは後に。古いものは捨てる）。 */
    private fun withRecent(added: List<NewRelease>): ReleaseWatchState {
        if (added.isEmpty()) return this
        val all = (added + recent).distinctBy { it.albumBrowseId ?: it.videoId }
        return copy(recent = (all.filterNot { it.seeded } + all.filter { it.seeded }).take(MAX_RECENT))
    }

    /** 古くなった新作を外す。 */
    fun expire(
        nowEpochMs: Long,
        maxAgeMs: Long = RECENT_MAX_AGE_MS,
    ): ReleaseWatchState = copy(recent = recent.filter { nowEpochMs - it.foundAtEpochMs <= maxAgeMs && it.videoId == null }) // 動画は新作として扱わなくなった（前に覚えた分も外す）

    /** 登録を外したアーティストを忘れる。 */
    fun retain(artistIds: Set<String>): ReleaseWatchState = copy(seen = seen.filterKeys { it in artistIds }, recent = recent.filter { it.artistId in artistIds })

    companion object {
        private const val MAX_IDS_PER_ARTIST = 300
        private const val MAX_RECENT = 60
        const val RECENT_MAX_AGE_MS = 45L * 24 * 60 * 60 * 1000
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
