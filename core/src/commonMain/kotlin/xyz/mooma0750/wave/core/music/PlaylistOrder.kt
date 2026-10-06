package xyz.mooma0750.wave.core.music

import kotlin.io.encoding.Base64

/**
 * プレイリストの並べ方。サーバーで並べ替える（プレイリストのページの並べ替えのメニューと同じ params）。
 * params は `{139: {1: 向き（1 = 昇順, 2 = 降順）, 2: 並べ方}}`、カスタム（Default ordering）は `{139: {}}`。
 * 公開日（1）はメニューに無いが、公開のプレイリストで先頭の曲の公開日が昇順・降順に並ぶことを確かめた（2026-09-30）。
 *
 * 自分のプレイリストでは、サーバーが最後に選んだ並べ方を覚える（params を付けない読み込みもその並びになる）。
 * ログイン中の応答のメニューには「選ばれている」印が無く、覚えている並べ方は読めない（実アカウントで確認、2026-09-30）。
 * なので、アプリで選んだ並べ方を端末に持ち、開くたびに明示して送る（公式アプリでの並びもそれに変わる）。
 */
enum class PlaylistSort(
    internal val key: Int?,
) {
    /** プレイリストの並び（手で並べた順）。 */
    Custom(null),

    /** 追加した日。 */
    Added(3),

    /** 動画の公開日（リリース順の代わり。自動生成の音源は発売日が入っていることが多い）。 */
    Published(1),
    Title(5),
    Artist(6),
    Album(7),

    /** 曲の長さ。サーバーに並べ方が無いので、カスタムの並びで全部読んでから手元で並べる（Windows 版と同じ）。 */
    Duration(null),
}

data class PlaylistOrder(
    val sort: PlaylistSort = PlaylistSort.Custom,
    val descending: Boolean = false,
) {
    /** browse の params。カスタムは Default ordering（サーバーは逆順を返さないので、降順は全部読んでから手元で逆にする）。 */
    val params: String
        get() =
            when (val key = sort.key) {
                null -> DEFAULT_ORDERING
                else -> Base64.encode(byteArrayOf(0xda.toByte(), 0x08, 0x04, 0x08, if (descending) 2 else 1, 0x10, key.toByte()))
            }

    /** 全部読んでから手元で逆に並べる。 */
    val reversedLocally: Boolean
        get() = sort == PlaylistSort.Custom && descending

    /** 全部読んでから手元で並べる（カスタムの降順と、長さ）。読んでいる途中の並びは出さない。 */
    val arrangedLocally: Boolean
        get() = reversedLocally || sort == PlaylistSort.Duration

    /**
     * 全部読んだ曲を手元で並べる。extra は曲と同じ位置に対応する値（プレイリストの項目の ID）で、曲と一緒に並べ替える。
     * 長さは同じ長さなら元の並びのまま、長さの分からない曲は向きに関わらず最後に置く。手元で並べない並べ方ならそのまま返す。
     */
    fun <T> arrange(
        songs: List<Song>,
        extra: List<T>,
    ): Pair<List<Song>, List<T>> {
        val order: List<Int> =
            when {
                reversedLocally -> songs.indices.reversed().toList()
                sort == PlaylistSort.Duration -> {
                    val (known, unknown) = songs.indices.partition { songs[it].durationSeconds != null }
                    val byLength = compareBy<Int> { songs[it].durationSeconds }
                    known.sortedWith(if (descending) byLength.reversed() else byLength) + unknown
                }
                else -> return songs to extra
            }
        // 対応が崩れている（件数が違う）ときは extra を並べ替えない
        return order.map { songs[it] } to if (extra.size == songs.size) order.map { extra[it] } else extra
    }

    /** 端末に保存する形。 */
    fun encode(): String = "${sort.name}:${if (descending) "desc" else "asc"}"

    companion object {
        /** 並べ替えのメニューの「Default ordering」。 */
        const val DEFAULT_ORDERING = "2ggA"

        /** 端末に保存する形（`Title:desc`）から。読めなければ null。 */
        fun decode(value: String): PlaylistOrder? {
            val sort = PlaylistSort.entries.firstOrNull { it.name == value.substringBefore(':') } ?: return null
            return when (value.substringAfter(':', "")) {
                "asc" -> PlaylistOrder(sort, descending = false)
                "desc" -> PlaylistOrder(sort, descending = true)
                else -> null
            }
        }
    }
}
