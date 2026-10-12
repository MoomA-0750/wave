package xyz.mooma0750.wave.core.music

import kotlinx.serialization.Serializable

/** ポッドキャストの番組のカード（検索・ライブラリ）。 */
@Serializable
data class PodcastSummary(
    /** `MPSP…`（番組のページ）。 */
    val browseId: String,
    val title: String,
    /** 配信者（「Rebuild Podcast」など）。 */
    val author: String?,
    val thumbnailUrl: String?,
) {
    /** 番組のエピソードの一覧のプレイリスト（`PL…`）。保存（ライブラリに追加）と、エピソードを続けて流すのに使う。 */
    val playlistId: String get() = browseId.removePrefix(PODCAST_PREFIX)

    companion object {
        const val PODCAST_PREFIX = "MPSP"
    }
}

/**
 * ポッドキャストのエピソード。再生は曲と同じ（[song] の videoId で流す。番組名を [Song.artists] に入れてある）。
 */
data class PodcastEpisode(
    val song: Song,
    /** 「6 日前」「2024/10/01」など、応答の表示のまま。 */
    val publishedText: String?,
    /** 「1 時間 37 分」など、応答の表示のまま（番組のページだけ。検索では null）。 */
    val durationText: String? = null,
    val description: String? = null,
    /** 聴いたところ（0〜100）。ログインしていない・聴いていなければ null。 */
    val progressPercent: Int? = null,
    /** 番組のページ（`MPSP…`）。分からなければ null。 */
    val showBrowseId: String? = null,
)

/** ポッドキャストの番組のページ（`MPSP…`）。 */
data class PodcastPage(
    val browseId: String,
    val title: String,
    val author: String?,
    val description: String?,
    val thumbnailUrl: String?,
    /** 新しい順のエピソード（最初のページ）。 */
    val episodes: List<PodcastEpisode>,
    /** エピソードの続き。無ければ最後のページ。 */
    val continuation: String?,
    /** ライブラリに保存しているか（ログインしていない・分からなければ null）。 */
    val saved: Boolean? = null,
) {
    val playlistId: String get() = browseId.removePrefix(PodcastSummary.PODCAST_PREFIX)

    val summary: PodcastSummary get() = PodcastSummary(browseId, title, author, thumbnailUrl)
}
