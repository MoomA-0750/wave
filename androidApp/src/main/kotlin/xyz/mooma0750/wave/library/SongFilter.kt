package xyz.mooma0750.wave.library

import xyz.mooma0750.wave.core.music.MusicVideoType
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.music.TextFold

/** 曲の種類での絞り込み（プレイリストの中）。 */
enum class KindFilter {
    All,

    /** 音源（公式の曲）。 */
    Songs,

    /** 公式の MV。 */
    MusicVideos,

    /** 投稿者の動画（歌ってみた・ライブ映像など）。 */
    UserVideos,
    ;

    fun matches(song: Song): Boolean =
        when (this) {
            All -> true
            Songs -> song.kind == MusicVideoType.Atv
            MusicVideos -> song.kind == MusicVideoType.Omv
            UserVideos -> song.kind == MusicVideoType.Ugc
        }
}

/**
 * 一覧の中の絞り込み（プレイリストの中を探す）。サーバーには絞り込みの口が無いので、読み込んだ曲を手元で絞る。
 * 曲名・アーティスト名・アルバム名のどれかに、入力した語がすべて含まれていれば残す（空白区切りで AND）。
 * 照らし方は core の [TextFold]（大文字・小文字、全角・半角、ひらがな・カタカナを区別しない。Mac・Windows・iPhone と同じ）。
 */
object SongFilter {
    /** @return 残った曲の、元の並びでの位置。入力が空なら全部。 */
    fun matchingIndices(
        songs: List<Song>,
        query: String,
        kind: KindFilter = KindFilter.All,
    ): List<Int> {
        val terms = TextFold.terms(query)
        if (terms.isEmpty() && kind == KindFilter.All) return songs.indices.toList()
        return songs.indices.filter { kind.matches(songs[it]) && (terms.isEmpty() || matches(songs[it], terms)) }
    }

    fun matches(
        song: Song,
        query: String,
    ): Boolean {
        val terms = TextFold.terms(query)
        return terms.isEmpty() || matches(song, terms)
    }

    private fun matches(
        song: Song,
        terms: List<String>,
    ): Boolean {
        val haystack = TextFold.fold((listOf(song.title) + song.artists.map { it.name } + listOfNotNull(song.album?.title)).joinToString(" "))
        return terms.all { haystack.contains(it) }
    }
}
