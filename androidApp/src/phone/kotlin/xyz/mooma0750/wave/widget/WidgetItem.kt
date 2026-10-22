package xyz.mooma0750.wave.widget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import xyz.mooma0750.wave.core.music.AlbumSummary
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.playback.NowPlayingSnapshot

/** ウィジェットで鳴らすものの種類。 */
enum class WidgetItemKind { Playlist, Album, Song, Live }

/**
 * ウィジェットに選んだ1つ（プレイリスト・アルバム・曲・ライブ）。
 * @param id プレイリストは playlistId、アルバムは browseId（`MPREb_…`）、曲・ライブは videoId
 * @param sourceId アルバムを鳴らすときのキューの出どころ（`OLAK5uy_…`）。鳴っているかを見分けるのに使う
 */
data class WidgetItem(
    val kind: WidgetItemKind,
    val id: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
    val sourceId: String? = null,
) {
    /** 今の曲がこれから鳴っているか（プレイリスト・アルバムはキューの出どころ、曲・ライブは今の曲で見る）。 */
    fun isCurrent(now: NowPlayingSnapshot?): Boolean =
        now != null &&
            when (kind) {
                WidgetItemKind.Playlist -> now.sourceId == id
                WidgetItemKind.Album -> sourceId != null && now.sourceId == sourceId
                WidgetItemKind.Song, WidgetItemKind.Live -> now.videoId == id
            }

    /** 曲・ライブを鳴らすための曲（アーティスト名は subtitle に入れてある）。 */
    fun toSong(): Song =
        Song(
            videoId = id,
            title = title,
            artists = subtitle?.takeIf { it.isNotEmpty() }?.let { listOf(ArtistRef(it, null)) }.orEmpty(),
            album = null,
            durationSeconds = null,
            thumbnailUrl = thumbnailUrl,
            isLive = kind == WidgetItemKind.Live,
        )

    fun writeTo(prefs: MutablePreferences) {
        prefs[PlaylistWidget.KIND] = kind.name
        prefs[PlaylistWidget.ITEM_ID] = id
        prefs[PlaylistWidget.TITLE] = title
        subtitle?.let { prefs[PlaylistWidget.SUBTITLE] = it } ?: prefs.remove(PlaylistWidget.SUBTITLE)
        thumbnailUrl?.let { prefs[PlaylistWidget.THUMBNAIL] = it } ?: prefs.remove(PlaylistWidget.THUMBNAIL)
        sourceId?.let { prefs[PlaylistWidget.SOURCE_ID] = it } ?: prefs.remove(PlaylistWidget.SOURCE_ID)
    }

    companion object {
        /** 置いてあるウィジェットの設定から読む（種類の無いものは、プレイリストだけを選べた頃に置いたもの）。 */
        fun of(prefs: Preferences): WidgetItem? {
            val id = prefs[PlaylistWidget.ITEM_ID] ?: return null
            val kind = prefs[PlaylistWidget.KIND]?.let { name -> WidgetItemKind.entries.firstOrNull { it.name == name } } ?: WidgetItemKind.Playlist
            return WidgetItem(kind, id, prefs[PlaylistWidget.TITLE].orEmpty(), prefs[PlaylistWidget.SUBTITLE], prefs[PlaylistWidget.THUMBNAIL], prefs[PlaylistWidget.SOURCE_ID])
        }

        fun of(playlist: PlaylistSummary) = WidgetItem(WidgetItemKind.Playlist, playlist.playlistId, playlist.title, playlist.subtitle, playlist.thumbnailUrl)

        fun of(album: AlbumSummary) = WidgetItem(WidgetItemKind.Album, album.browseId, album.title, album.subtitle, album.thumbnailUrl)

        fun of(song: Song) =
            WidgetItem(
                if (song.isLive) WidgetItemKind.Live else WidgetItemKind.Song,
                song.videoId,
                song.title,
                song.artistsText,
                song.thumbnailUrl,
            )
    }
}
