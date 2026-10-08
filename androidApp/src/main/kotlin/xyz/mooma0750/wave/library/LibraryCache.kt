package xyz.mooma0750.wave.library

import xyz.mooma0750.wave.core.music.PlaylistSummary

/**
 * 前に読んだライブラリ（プロセスの中だけ。ログアウトで消す）。画面を開いたときにまずこれを出し、裏で読み直して差し替える
 * （プレイリストを1つ変えるたびに一覧が空になってから出直していた）。
 */
class LibraryCache {
    @Volatile
    var tab: LibraryTabUiState? = null

    /** 自分のプレイリスト（プレイリストに追加する画面）。 */
    @Volatile
    var playlists: List<PlaylistSummary>? = null

    fun clear() {
        tab = null
        playlists = null
    }
}
