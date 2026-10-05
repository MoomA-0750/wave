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

/** ホームの絞り込み（「リラックス」「元気が出る」など）。押すと params を付けてホームを取り直す。 */
data class HomeChip(
    val title: String,
    val params: String,
)

/** ホームの1ページ。続きは棚の追加。絞り込みは最初のページにだけ来る。 */
data class HomePage(
    val shelves: List<HomeShelf>,
    val continuation: String?,
    val chips: List<HomeChip> = emptyList(),
)

/** 気分・ジャンルの1枚（検索のタブの色のタイル）。 */
data class MoodCategory(
    val title: String,
    /** browse FEmusic_moods_and_genres_category に付ける params。 */
    val params: String,
    /** タイルの色（ARGB）。無いこともある。 */
    val color: Long?,
)

/** 気分・ジャンルのまとまり（「気分」「ジャンル」）。 */
data class MoodSection(
    val title: String,
    val categories: List<MoodCategory>,
)
