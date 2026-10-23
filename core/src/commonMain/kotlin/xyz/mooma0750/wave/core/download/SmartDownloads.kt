package xyz.mooma0750.wave.core.download

import kotlinx.serialization.Serializable
import xyz.mooma0750.wave.core.music.MusicVideoType
import xyz.mooma0750.wave.core.music.Song

/** この端末で聴いた回数（スマートダウンロードの「いつも聴いている」に使う）。 */
@Serializable
data class PlayCount(
    val song: Song,
    val count: Int,
    val lastPlayedMs: Long,
)

object PlayCounts {
    /** 覚えておく曲の数。超えたら、最後に聴いたのが古い曲から忘れる。 */
    const val LIMIT = 2000

    /** 1回聴いた。最後に聴いた曲を先頭に置く。 */
    fun record(
        counts: List<PlayCount>,
        song: Song,
        nowMs: Long,
        limit: Int = LIMIT,
    ): List<PlayCount> {
        val previous = counts.firstOrNull { it.song.videoId == song.videoId }
        val updated = PlayCount(song, (previous?.count ?: 0) + 1, nowMs)
        val rest = counts.filterNot { it.song.videoId == song.videoId }
        return (listOf(updated) + rest).sortedByDescending { it.lastPlayedMs }.take(limit)
    }
}

/**
 * スマートダウンロードの選び方（ADR-0016 の 10・11）。通信と保存は呼ぶ側が行い、ここは選ぶだけ（Android に依らない）。
 */
object SmartDownloads {
    /** 曲数の選択肢と既定。 */
    val COUNTS = listOf(25, 50, 100, 200)
    const val DEFAULT_COUNT = 50

    /** 同じアーティストを全体で何曲まで取るか。 */
    const val MAX_PER_ARTIST = 2

    /** 「好きそう」の種にする「いつも聴いている」の上位の曲の数。 */
    const val MAX_SEEDS = 5

    /**
     * 「いつも聴いている」: この端末の再生回数と、YouTube の再生履歴（新しい順）に出る曲。
     * 履歴に出る曲は1回聴いたとして足す。回数の多い順、同じなら新しい順（端末の時刻、無ければ履歴の並び）。
     */
    fun frequent(
        counts: List<PlayCount>,
        history: List<Song>,
    ): List<Song> {
        val historyRank = HashMap<String, Int>()
        history.forEachIndexed { index, song -> historyRank.putIfAbsent(song.videoId, index) }
        val local = counts.associateBy { it.song.videoId }
        val songs = LinkedHashMap<String, Song>()
        counts.forEach { songs[it.song.videoId] = it.song }
        history.forEach { songs.putIfAbsent(it.videoId, it) }
        return songs.values
            .filterNot { it.isLive }
            .sortedWith(
                compareByDescending<Song> { (local[it.videoId]?.count ?: 0) + if (it.videoId in historyRank) 1 else 0 }
                    .thenByDescending { local[it.videoId]?.lastPlayedMs ?: Long.MIN_VALUE }
                    .thenBy { historyRank[it.videoId] ?: Int.MAX_VALUE },
            )
    }

    /** 「好きそう」の種（「いつも聴いている」の上位）。 */
    fun seeds(frequent: List<Song>): List<Song> = frequent.take(MAX_SEEDS)

    /**
     * 「好きそう（聴いたことのない曲）」: 種ごとのラジオから、聴いたことのある曲（再生回数・再生履歴・高く評価した曲）を除いて、
     * 種ごとに順番に1曲ずつ取る。
     */
    fun discover(
        radios: List<List<Song>>,
        known: Set<String>,
    ): List<Song> {
        val result = LinkedHashMap<String, Song>()
        val queues = radios.map { radio -> radio.filterNot { it.videoId in known || it.isLive }.toMutableList() }
        while (queues.any { it.isNotEmpty() }) {
            queues.forEach { queue ->
                while (queue.isNotEmpty()) {
                    val song = queue.removeAt(0)
                    if (song.videoId !in result) {
                        result[song.videoId] = song
                        break
                    }
                }
            }
        }
        return result.values.toList()
    }

    /** 「おすすめ」: ホームの1ページ目の曲の棚の曲。足りなければホームの最初のミックスの曲を後ろに足す。 */
    fun recommended(
        shelves: List<List<Song>>,
        mix: List<Song>,
    ): List<Song> = (shelves.flatten() + mix).filterNot { it.isLive }.distinctBy { it.videoId }

    /**
     * まんべんなく取る: 分類から1曲ずつ順番に取る。同じアーティストは全体で [MAX_PER_ARTIST] 曲まで、分類どうしで重なった曲は
     * 先に入った分類に入れる。どれかの分類が足りなければ、ほかの分類で埋める。
     */
    fun pick(
        candidates: Map<SmartCategory, List<Song>>,
        total: Int,
        maxPerArtist: Int = MAX_PER_ARTIST,
    ): List<SmartDownload> {
        val picked = ArrayList<SmartDownload>()
        val taken = HashSet<String>()
        val perArtist = HashMap<String, Int>()
        val iterators = SmartCategory.entries.associateWith { (candidates[it] ?: emptyList()).iterator() }
        val exhausted = HashSet<SmartCategory>()
        while (picked.size < total && exhausted.size < SmartCategory.entries.size) {
            for (category in SmartCategory.entries) {
                if (picked.size >= total) break
                if (category in exhausted) continue
                val iterator = iterators.getValue(category)
                var added = false
                while (iterator.hasNext()) {
                    val song = iterator.next()
                    if (song.isLive || song.videoId in taken || tooLong(song) || !isMusic(song)) continue
                    val artist = artistKey(song)
                    if (artist != null && (perArtist[artist] ?: 0) >= maxPerArtist) continue
                    taken += song.videoId
                    if (artist != null) perArtist[artist] = (perArtist[artist] ?: 0) + 1
                    picked += SmartDownload(song, category)
                    added = true
                    break
                }
                if (!added) exhausted += category
            }
        }
        return picked
    }

    /** 長すぎる曲（作業用のまとめの動画など）は取らない。長さが分からなければ取る。 */
    fun tooLong(song: Song): Boolean = (song.durationSeconds ?: 0) > MAX_SECONDS

    /** 曲（音源・公式の MV）か。投稿者の動画（作業用のまとめなど）は自動では取らない。種類が分からなければ取る。 */
    fun isMusic(song: Song): Boolean = song.kind == null || song.kind == MusicVideoType.Atv || song.kind == MusicVideoType.Omv

    /** 自動で取る曲の長さの上限（15分）。 */
    const val MAX_SECONDS = 15 * 60

    /** 同じアーティストかを見分ける鍵（最初のアーティスト）。分からなければ null（数えない）。 */
    fun artistKey(song: Song): String? {
        val first = song.artists.firstOrNull() ?: return null
        return first.browseId ?: first.name.trim().lowercase().takeIf { it.isNotEmpty() }
    }

    /** 1日1回選び直すときが来たか。 */
    fun refreshDue(
        lastRefreshedAtMs: Long?,
        nowMs: Long,
        intervalMs: Long = REFRESH_INTERVAL_MS,
    ): Boolean = lastRefreshedAtMs == null || nowMs - lastRefreshedAtMs >= intervalMs || nowMs < lastRefreshedAtMs

    const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L
}
