package xyz.mooma0750.wave.core.music

/** ホームの棚の1枚。曲・アルバム・プレイリスト・アーティストが混ざる。 */
sealed interface HomeItem {
    data class SongItem(
        val song: Song,
    ) : HomeItem

    data class AlbumItem(
        val album: AlbumSummary,
    ) : HomeItem

    data class PlaylistItem(
        val playlist: PlaylistSummary,
    ) : HomeItem

    data class ArtistItem(
        val artist: ArtistSummary,
    ) : HomeItem
}

/** ホームの棚（「もう一度聴く」「おすすめ」など）。見出しは応答の表示のまま（言語に依るので判定に使わない）。 */
data class HomeShelf(
    val title: String,
    /** 見出しの上の小さな文字（「〇〇さんへ」など）。 */
    val strapline: String?,
    val items: List<HomeItem>,
)

/** ホームの1ページ。続きは棚の追加。 */
data class HomePage(
    val shelves: List<HomeShelf>,
    val continuation: String?,
)
