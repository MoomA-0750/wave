package xyz.mooma0750.wave.core.lyrics

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.MusicVideoType
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult

/** 歌詞の中で、打った言葉が見つかった行。startMs は時刻付きの歌詞のときだけ。exact は言葉がそのまま入っていたか（false は大体合っている）。 */
data class LyricsMatch(
    val line: String,
    val startMs: Long?,
    val exact: Boolean,
)

/** 歌詞から探した結果の1曲。match が null なら、歌詞が取れなかったか、言葉が見つからなかった（検索の順で後ろに並べる）。 */
data class LyricsHit(
    val song: Song,
    val match: LyricsMatch?,
)

/**
 * 歌詞の一節から曲を探す（決定 2026-10-14）。外部の歌詞検索サービスは使わず、
 * YouTube Music の絞り込まない検索（歌詞の一節でも曲が上に来る）の上位の曲の歌詞を取り、打った言葉を含む曲を前に出す。
 * 歌詞は再生画面と同じ [LyricsRepository]（LRCLIB の時刻付き、無ければ YouTube Music）から取る。
 */
class LyricsSearch(
    private val candidates: suspend (String) -> CallResult<List<Song>>,
    private val lyrics: suspend (Song) -> LyricsRepository.Result,
) {
    constructor(music: MusicRepository, lyrics: LyricsRepository) : this(music::searchTopSongs, lyrics::lyrics)

    suspend fun search(query: String): CallResult<List<LyricsHit>> {
        if (LyricsMatcher.normalize(query).isEmpty()) return CallResult.Ok(emptyList())
        val songs =
            when (val found = candidates(query)) {
                is CallResult.Ok -> found.value
                is CallResult.Failed -> return found
            }
        val picked = distinctSongs(songs).take(CANDIDATES)
        val hits =
            coroutineScope {
                picked
                    .map { song ->
                        async {
                            val match = (lyrics(song) as? LyricsRepository.Result.Found)?.let { LyricsMatcher.find(it.lyrics, query) }
                            LyricsHit(song, match)
                        }
                    }.awaitAll()
            }
        return CallResult.Ok(rank(hits))
    }

    internal companion object {
        const val CANDIDATES = 6

        /** そのまま入っていた曲、大体合っている曲、見つからなかった曲の順。同じ組の中は検索の順のまま。 */
        fun rank(hits: List<LyricsHit>): List<LyricsHit> =
            hits.sortedBy {
                when {
                    it.match?.exact == true -> 0
                    it.match != null -> 1
                    else -> 2
                }
            }

        /**
         * 同じ曲の音源と MV が両方出たら、先に出た位置に音源を残す（曲として流すため）。
         * 同じ曲かは、曲名と最初のアーティストを正規化して比べる。
         */
        fun distinctSongs(songs: List<Song>): List<Song> {
            val result = mutableListOf<Song>()
            val index = mutableMapOf<String, Int>()
            for (song in songs) {
                if (song.isLive || result.any { it.videoId == song.videoId }) continue
                val key = LyricsMatcher.normalize(song.title) + "\u0000" + LyricsMatcher.normalize(song.artists.firstOrNull()?.name.orEmpty())
                val at = index[key]
                if (at == null) {
                    index[key] = result.size
                    result += song
                } else if (song.kind == MusicVideoType.Atv && result[at].kind != MusicVideoType.Atv) {
                    result[at] = song
                }
            }
            return result
        }
    }
}

/** 歌詞の行と打った言葉の照らし合わせ。画面にも通信にも依らない。 */
object LyricsMatcher {
    /** 大体合っているとみなす、言葉の2文字の組が行に入っている割合。 */
    const val APPROXIMATE_RATIO = 0.7

    /**
     * 比べやすい形にする。小文字にし、全角の英数字を半角に、カタカナをひらがなにし、文字と数字以外（空白・句読点・記号）を除く。
     * 「i'm」と「im」、「夜に駆ける」と「夜にかける」の打ち方の違いを吸収する（漢字とかなの違いは吸収しない）。
     */
    fun normalize(text: String): String =
        buildString {
            for (raw in text) {
                var c = raw
                if (c in '！'..'～') c = c - 0xFEE0
                if (c in 'ァ'..'ヶ') c = c - 0x60
                if (c.isLetterOrDigit()) append(c.lowercaseChar())
            }
        }

    /** 打った言葉を含む最初の行。行をまたいだ言葉も、その行と次の行をつないで探す。無ければ大体合っている行（一番近いもの）。 */
    fun find(
        lyrics: Lyrics,
        query: String,
    ): LyricsMatch? {
        val lines: List<Pair<String, Long?>> =
            when (lyrics) {
                is Lyrics.Synced -> lyrics.lines.map { it.text to it.startMs }
                is Lyrics.Plain -> lyrics.text.lines().map { it to null }
            }.filter { it.first.isNotBlank() }
        val q = normalize(query)
        if (q.isEmpty() || lines.isEmpty()) return null
        val normalized = lines.map { normalize(it.first) }
        val spans = normalized.indices.map { i -> normalized[i] + normalized.getOrElse(i + 1) { "" } }
        // 1行に収まる一致を先に探す（つないだ方だけで探すと、次の行だけに入っている言葉を前の行の一致にしてしまう）
        (normalized.indexOfFirst { q in it }.takeIf { it >= 0 } ?: spans.indexOfFirst { q in it }.takeIf { it >= 0 })
            ?.let { return LyricsMatch(lines[it].first.trim(), lines[it].second, exact = true) }
        if (q.length < 4) return null
        val wanted = bigrams(q)
        var best = -1
        var bestRatio = 0.0
        spans.forEachIndexed { i, span ->
            val have = bigrams(span)
            val ratio = wanted.count { it in have }.toDouble() / wanted.size
            if (ratio > bestRatio) {
                best = i
                bestRatio = ratio
            }
        }
        return if (best >= 0 && bestRatio >= APPROXIMATE_RATIO) LyricsMatch(lines[best].first.trim(), lines[best].second, exact = false) else null
    }

    private fun bigrams(text: String): Set<String> = text.windowed(2).toSet()
}
