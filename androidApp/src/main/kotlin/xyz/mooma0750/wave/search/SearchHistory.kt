package xyz.mooma0750.wave.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 検索の履歴の1件。検索した語と、検索から開いたアーティスト・アルバムを同じ並びで残す。 */
@Serializable
sealed interface SearchHistoryEntry {
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
    ) : SearchHistoryEntry

    @Serializable
    @SerialName("album")
    data class Album(
        val title: String,
        val browseId: String,
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
        return (listOf(normalized) + entries.filterNot { same(it, normalized) }).take(MAX_ENTRIES)
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
        }

    private fun same(
        a: SearchHistoryEntry,
        b: SearchHistoryEntry,
    ): Boolean =
        when {
            a is SearchHistoryEntry.Query && b is SearchHistoryEntry.Query -> a.text.equals(b.text, ignoreCase = true)
            a is SearchHistoryEntry.Artist && b is SearchHistoryEntry.Artist -> a.browseId == b.browseId
            a is SearchHistoryEntry.Album && b is SearchHistoryEntry.Album -> a.browseId == b.browseId
            else -> false
        }
}
