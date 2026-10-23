package xyz.mooma0750.wave.core.download

import kotlinx.serialization.Serializable
import xyz.mooma0750.wave.core.music.Song

/** 丸ごと取っているものの種類（ADR-0016）。 */
@Serializable
enum class CollectionKind { Playlist, Album, Liked }

/** スマートダウンロードの分類（ADR-0016 の 10）。並びは、まんべんなく取るときに回す順。 */
@Serializable
enum class SmartCategory { Frequent, Discover, Recommended }

/** 丸ごと取っているもの。曲の並びは最後に読み直したときのもの（通信なしで一覧を出す）。 */
@Serializable
data class DownloadedCollection(
    val kind: CollectionKind,
    /** プレイリストは VL を除いた ID、アルバムは `MPREb_…`、高く評価した曲は LM。 */
    val id: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val addedAtMs: Long,
    val songs: List<Song> = emptyList(),
)

/** 1曲ずつ取ったもの（曲とエピソード）。 */
@Serializable
data class SingleDownload(
    val song: Song,
    val addedAtMs: Long,
    /** ポッドキャストのエピソード（ダウンロードの画面で曲と分けて出す）。 */
    val episode: Boolean = false,
)

/** スマートダウンロードで選んだ曲。 */
@Serializable
data class SmartDownload(
    val song: Song,
    val category: SmartCategory,
)

/**
 * 何を取っているかの目録（`downloads.json`）。曲のファイルは、どれか1つでも持ち主がいれば残す。
 * 署名付きの URL は持たない。
 */
@Serializable
data class DownloadManifest(
    val collections: List<DownloadedCollection> = emptyList(),
    val singles: List<SingleDownload> = emptyList(),
    val smart: List<SmartDownload> = emptyList(),
    /** 最後にスマートダウンロードを選び直した時刻。 */
    val smartRefreshedAtMs: Long? = null,
) {
    /** 取っておく曲の ID。取る順（1曲ずつ → 丸ごと → スマートダウンロード）に並ぶ。 */
    fun wantedIds(): LinkedHashSet<String> {
        val ids = LinkedHashSet<String>()
        singles.forEach { ids += it.song.videoId }
        collections.forEach { collection -> collection.songs.forEach { ids += it.videoId } }
        smart.forEach { ids += it.song.videoId }
        return ids
    }

    fun isWanted(videoId: String): Boolean = singles.any { it.song.videoId == videoId } || collections.any { c -> c.songs.any { it.videoId == videoId } } || smart.any { it.song.videoId == videoId }

    /** 曲の情報（通信なしで一覧・キューに出すため）。 */
    fun songOf(videoId: String): Song? =
        singles.firstOrNull { it.song.videoId == videoId }?.song
            ?: collections.firstNotNullOfOrNull { c -> c.songs.firstOrNull { it.videoId == videoId } }
            ?: smart.firstOrNull { it.song.videoId == videoId }?.song

    fun collection(
        kind: CollectionKind,
        id: String,
    ): DownloadedCollection? = collections.firstOrNull { it.kind == kind && it.id == id }

    /** 丸ごとのものを足す・中身を置き換える（ライブ配信は取らない）。足した時刻は前のものを残す。 */
    fun withCollection(collection: DownloadedCollection): DownloadManifest {
        val previous = collection(collection.kind, collection.id)
        val cleaned = collection.copy(songs = downloadable(collection.songs), addedAtMs = previous?.addedAtMs ?: collection.addedAtMs)
        return copy(collections = if (previous == null) collections + cleaned else collections.map { if (it === previous) cleaned else it })
    }

    fun withoutCollection(
        kind: CollectionKind,
        id: String,
    ): DownloadManifest = copy(collections = collections.filterNot { it.kind == kind && it.id == id })

    fun withSingle(
        song: Song,
        nowMs: Long,
        episode: Boolean = false,
    ): DownloadManifest {
        if (song.isLive || singles.any { it.song.videoId == song.videoId }) return this
        return copy(singles = singles + SingleDownload(song, nowMs, episode))
    }

    fun withoutSingle(videoId: String): DownloadManifest = copy(singles = singles.filterNot { it.song.videoId == videoId })

    /**
     * 曲を「ダウンロードを削除」した。1曲ずつのものとスマートダウンロードからは外す。
     * 丸ごとのものの中の曲は外さない（一覧を合わせると戻ってくるので、丸ごとのものを消してもらう）。
     */
    fun withoutSong(videoId: String): DownloadManifest = copy(singles = singles.filterNot { it.song.videoId == videoId }, smart = smart.filterNot { it.song.videoId == videoId })

    fun withSmart(
        picked: List<SmartDownload>,
        nowMs: Long,
    ): DownloadManifest = copy(smart = picked.filterNot { it.song.isLive }, smartRefreshedAtMs = nowMs)

    fun withoutSmart(): DownloadManifest = copy(smart = emptyList(), smartRefreshedAtMs = null)

    val isEmpty: Boolean get() = collections.isEmpty() && singles.isEmpty() && smart.isEmpty()

    companion object {
        /** 取れる曲だけ（ライブ配信は取らない、ADR-0016 の 3）。同じ曲は1つに。 */
        fun downloadable(songs: List<Song>): List<Song> = songs.filterNot { it.isLive }.distinctBy { it.videoId }
    }
}

/** 目録と手元のファイルから決めること（Android に依らない）。 */
object DownloadPlan {
    /** 持っているが、目録のどれも持ち主でない曲（消してよい）。 */
    fun orphans(
        manifest: DownloadManifest,
        stored: Set<String>,
    ): Set<String> = stored - manifest.wantedIds()

    /** まだ取り終えていない曲を、取る順に。 */
    fun missing(
        manifest: DownloadManifest,
        complete: Set<String>,
    ): List<String> = manifest.wantedIds().filterNot { it in complete }

    /** 丸ごとのもののうち、取り終えた曲の数と全体の数。 */
    fun progress(
        collection: DownloadedCollection,
        complete: Set<String>,
    ): Pair<Int, Int> = collection.songs.count { it.videoId in complete } to collection.songs.size

    /** 一覧を読み直したときの差: 増えた曲と外れた曲。 */
    fun diff(
        before: List<Song>,
        after: List<Song>,
    ): Pair<List<String>, List<String>> {
        val beforeIds = before.mapTo(HashSet()) { it.videoId }
        val afterIds = after.mapTo(HashSet()) { it.videoId }
        return after.map { it.videoId }.filter { it !in beforeIds }.distinct() to before.map { it.videoId }.filter { it !in afterIds }.distinct()
    }
}
