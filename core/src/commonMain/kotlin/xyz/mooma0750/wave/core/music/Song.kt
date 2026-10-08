package xyz.mooma0750.wave.core.music

import kotlinx.serialization.Serializable

/** 画面に依らない曲の表現。UI・再生キュー・端末間の受け渡しで共通に使う。 */
@Serializable
data class Song(
    val videoId: String,
    val title: String,
    val artists: List<ArtistRef>,
    val album: AlbumRef?,
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
    /** 曲の種類（音源・公式の MV・投稿者の動画）。応答に無ければ null（一覧の絞り込みでは「すべて」にだけ出る）。 */
    val kind: MusicVideoType? = null,
) {
    /** 「アーティスト • アルバム」の1行。 */
    val subtitle: String
        get() = listOfNotNull(artists.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() }, album?.title).joinToString(" • ")
}

@Serializable
data class ArtistRef(
    val name: String,
    val browseId: String?,
)

@Serializable
data class AlbumRef(
    val title: String,
    val browseId: String?,
)

/** 曲の一覧の1ページ。 */
data class SongPage(
    val songs: List<Song>,
    /** 続きを取るためのトークン。無ければ最後のページ。 */
    val continuation: String?,
)

/** ラジオ（次に流れる曲）の1ページ。 */
data class UpNextPage(
    val songs: List<Song>,
    val continuation: String?,
    /** ラジオの playlistId（続きの取得に使う）。 */
    val playlistId: String?,
)

/** ライブラリなどに並ぶプレイリスト。 */
@Serializable
data class PlaylistSummary(
    /** VL を除いた ID（キューの出どころや続きの取得に使う）。 */
    val playlistId: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
    /** 自分が編集できる（カードのメニューに編集がある）。追加先の一覧に使う。 */
    val editable: Boolean = false,
)

/** プレイリストの1ページ。 */
data class PlaylistPage(
    val title: String?,
    val songs: List<Song>,
    val continuation: String?,
    /** songs と同じ並びの、プレイリスト内の項目の ID（削除に使う）。取れなければ null。 */
    val setVideoIds: List<String?> = emptyList(),
    /** 自分が編集できるプレイリストか（最初のページのヘッダで判定する）。 */
    val editable: Boolean = false,
    /**
     * continuation が無いことを、知っている形の応答から確かめられた（最後のページ）。
     * 形が変わって続きを読み取れなかっただけの場合は false（重複を確かめる書き込みで「全部読んだ」と誤認しない）。
     */
    val endConfirmed: Boolean = false,
    /** サーバーで並べ替えられる（最初のページに並べ替えのメニューがある）。 */
    val sortable: Boolean = false,
)

/** 再生履歴の1つの区切り（「今日」「今週」など）。 */
data class HistorySection(
    val title: String?,
    val songs: List<Song>,
)

/** 再生履歴の1ページ。区切りごとに曲が並ぶ。 */
data class HistoryPage(
    val sections: List<HistorySection>,
    val continuation: String?,
) {
    val songs: List<Song> get() = sections.flatMap { it.songs }
}

/** プレイリストの中の曲の ID を全ページ読んだ結果。 */
sealed interface PlaylistScan {
    data class Ok(
        val videoIds: Set<String>,
    ) : PlaylistScan

    /** 最後まで読めなかった（中に在るかどうかを言えない）。 */
    data class Incomplete(
        val kind: xyz.mooma0750.wave.core.session.FailureKind,
    ) : PlaylistScan
}

/** プレイリストへの追加の結果（決定 G-1: 先に全ページを読んで重複を確かめる）。 */
sealed interface AddToPlaylistResult {
    data object Added : AddToPlaylistResult

    /** 既にある。追加していない。 */
    data object AlreadyInPlaylist : AddToPlaylistResult

    /** 重複を確かめられなかった。追加していない。 */
    data class NotChecked(
        val kind: xyz.mooma0750.wave.core.session.FailureKind,
    ) : AddToPlaylistResult

    /** 追加を送ったが、反映されていないと分かる応答。 */
    data class Failed(
        val kind: xyz.mooma0750.wave.core.session.FailureKind,
        val status: Int?,
    ) : AddToPlaylistResult

    /** 追加を送ったが、反映されたか分からない。自動で送り直さない。 */
    data object Unknown : AddToPlaylistResult
}

/** 曲の評価。 */
enum class LikeStatus { Like, Dislike, Indifferent }

/** アルバム・シングルのカード。 */
@Serializable
data class AlbumSummary(
    /** `MPREb_…` */
    val browseId: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
)

/** アルバムのページ。 */
data class AlbumPage(
    val browseId: String,
    val title: String,
    val subtitle: String?,
    val artists: List<ArtistRef>,
    val thumbnailUrl: String?,
    /** 再生に使う playlistId（`OLAK5uy_…`）。無ければ曲の一覧だけで再生する。 */
    val playlistId: String?,
    val songs: List<Song>,
)

/** アーティストのページ（F1c の範囲: 曲の棚・すべての曲・アルバム類の棚）。 */
data class ArtistPage(
    val name: String,
    val thumbnailUrl: String?,
    val topSongs: List<Song>,
    /** 「すべて表示」の先のプレイリスト（VL を除いた ID）。 */
    val allSongsPlaylistId: String?,
    /** アルバム・シングルなどの棚。見出しは応答の表示のまま（言語に依存するので判定に使わない）。 */
    val albumShelves: List<AlbumShelf>,
)

data class AlbumShelf(
    val title: String,
    val albums: List<AlbumSummary>,
)

/** アーティストの行（検索の結果）。 */
data class ArtistSummary(
    /** `UC…` */
    val browseId: String,
    val name: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
)

/** アルバム・アーティストの検索の1ページ。 */
data class SearchPage<T>(
    val items: List<T>,
    val continuation: String?,
)

/** 打ち込み中の候補。語の候補と、そのまま開ける曲・アルバム・アーティスト。 */
data class SearchSuggestions(
    val queries: List<String>,
    val songs: List<Song>,
    val albums: List<AlbumSummary>,
    val artists: List<ArtistSummary>,
) {
    val isEmpty: Boolean get() = queries.isEmpty() && songs.isEmpty() && albums.isEmpty() && artists.isEmpty()
}
