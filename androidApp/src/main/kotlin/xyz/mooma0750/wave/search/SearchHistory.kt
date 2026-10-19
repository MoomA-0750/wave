package xyz.mooma0750.wave.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 検索の履歴の1件。検索した語と、検索から開いたアーティスト・アルバム・プレイリスト・ポッドキャスト、再生した曲を同じ並びで残す
 * （種類は Windows と同じ）。
 * 画像と補足の行は表示のためだけ（無い記録もある。前の版の保存には無い）。
 */
@Serializable
sealed interface SearchHistoryEntry {
    val thumbnailUrl: String? get() = null
    @Serializable
    @SerialName("query")
    data class Query(
        val text: String,
    ) : SearchHistoryEntry

    @Serializable
    @SerialName("artist")
    data class Artist(
        val name: String,
        val browseId: String,
        val subtitle: String? = null,
        override val thumbnailUrl: String? = null,
    ) : SearchHistoryEntry

    @Serializable
    @SerialName("album")
    data class Album(
        val title: String,
        val browseId: String,
        val subtitle: String? = null,
        override val thumbnailUrl: String? = null,
    ) : SearchHistoryEntry

    @Serializable
    @SerialName("playlist")
    data class Playlist(
        val title: String,
        /** VL を除いた ID。 */
        val playlistId: String,
        val subtitle: String? = null,
        override val thumbnailUrl: String? = null,
    ) : SearchHistoryEntry

    @Serializable
    @SerialName("podcast")
    data class Podcast(
        val title: String,
        /** 番組のページ（`MPSP…`、RSS の番組はその ID）。 */
        val browseId: String,
        /** 配信者。 */
        val subtitle: String? = null,
        override val thumbnailUrl: String? = null,
    ) : SearchHistoryEntry

    @Serializable
    @SerialName("song")
    data class Song(
        val videoId: String,
        val title: String,
        val subtitle: String? = null,
        override val thumbnailUrl: String? = null,
    ) : SearchHistoryEntry
}

/** 履歴の並び（新しいものが先）。同じものは上に持ち上げ、上限を超えた分は落とす。画面にも保存にも依らない。 */
object SearchHistory {
    const val MAX_ENTRIES = 30

    fun add(
        entries: List<SearchHistoryEntry>,
        entry: SearchHistoryEntry,
    ): List<SearchHistoryEntry> {
        val normalized = normalize(entry) ?: return entries
        // 曲のメニューから開いたときなど、画像の無い記録で、前の記録の画像を消さない
        val kept = entries.firstOrNull { same(it, normalized) }?.let { fillFrom(normalized, it) } ?: normalized
        return (listOf(kept) + entries.filterNot { same(it, normalized) }).take(MAX_ENTRIES)
    }

    /** 新しい方（手前）を残して繋ぐ。保存を読む前に足された分を落とさないために使う。 */
    fun merge(
        newer: List<SearchHistoryEntry>,
        older: List<SearchHistoryEntry>,
    ): List<SearchHistoryEntry> = (newer + older.filterNot { old -> newer.any { same(it, old) } }).take(MAX_ENTRIES)

    fun remove(
        entries: List<SearchHistoryEntry>,
        entry: SearchHistoryEntry,
    ): List<SearchHistoryEntry> = entries.filterNot { same(it, entry) }

    /** 語は前後の空白を落とし、空なら残さない。 */
    private fun normalize(entry: SearchHistoryEntry): SearchHistoryEntry? =
        when (entry) {
            is SearchHistoryEntry.Query -> entry.text.trim().takeIf { it.isNotEmpty() }?.let { SearchHistoryEntry.Query(it) }
            is SearchHistoryEntry.Artist -> entry.takeIf { it.browseId.isNotEmpty() }
            is SearchHistoryEntry.Album -> entry.takeIf { it.browseId.isNotEmpty() }
            is SearchHistoryEntry.Playlist -> entry.takeIf { it.playlistId.isNotEmpty() }
            is SearchHistoryEntry.Podcast -> entry.takeIf { it.browseId.isNotEmpty() }
            is SearchHistoryEntry.Song -> entry.takeIf { it.videoId.isNotEmpty() }
        }

    private fun fillFrom(
        entry: SearchHistoryEntry,
        old: SearchHistoryEntry,
    ): SearchHistoryEntry =
        when {
            entry is SearchHistoryEntry.Artist && old is SearchHistoryEntry.Artist ->
                entry.copy(subtitle = entry.subtitle ?: old.subtitle, thumbnailUrl = entry.thumbnailUrl ?: old.thumbnailUrl)
            entry is SearchHistoryEntry.Album && old is SearchHistoryEntry.Album ->
                entry.copy(subtitle = entry.subtitle ?: old.subtitle, thumbnailUrl = entry.thumbnailUrl ?: old.thumbnailUrl)
            entry is SearchHistoryEntry.Song && old is SearchHistoryEntry.Song ->
                entry.copy(subtitle = entry.subtitle ?: old.subtitle, thumbnailUrl = entry.thumbnailUrl ?: old.thumbnailUrl)
            entry is SearchHistoryEntry.Playlist && old is SearchHistoryEntry.Playlist ->
                entry.copy(subtitle = entry.subtitle ?: old.subtitle, thumbnailUrl = entry.thumbnailUrl ?: old.thumbnailUrl)
            entry is SearchHistoryEntry.Podcast && old is SearchHistoryEntry.Podcast ->
                entry.copy(subtitle = entry.subtitle ?: old.subtitle, thumbnailUrl = entry.thumbnailUrl ?: old.thumbnailUrl)
            else -> entry
        }

    private fun same(
        a: SearchHistoryEntry,
        b: SearchHistoryEntry,
    ): Boolean =
        when {
            a is SearchHistoryEntry.Query && b is SearchHistoryEntry.Query -> a.text.equals(b.text, ignoreCase = true)
            a is SearchHistoryEntry.Artist && b is SearchHistoryEntry.Artist -> a.browseId == b.browseId
            a is SearchHistoryEntry.Album && b is SearchHistoryEntry.Album -> a.browseId == b.browseId
            a is SearchHistoryEntry.Song && b is SearchHistoryEntry.Song -> a.videoId == b.videoId
            a is SearchHistoryEntry.Playlist && b is SearchHistoryEntry.Playlist -> a.playlistId == b.playlistId
            a is SearchHistoryEntry.Podcast && b is SearchHistoryEntry.Podcast -> a.browseId == b.browseId
            else -> false
        }
}
