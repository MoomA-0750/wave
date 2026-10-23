package xyz.mooma0750.wave.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.library.LibraryOrder

/** プレイリストのウィジェットに並べる1つ。 */
data class WidgetPlaylist(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("thumbnail", thumbnailUrl)

    companion object {
        fun of(playlist: PlaylistSummary) = WidgetPlaylist(playlist.playlistId, playlist.title, playlist.thumbnailUrl)

        fun of(json: JSONObject) = WidgetPlaylist(json.getString("id"), json.optString("title"), json.optString("thumbnail").takeIf { it.startsWith("https://") })

        fun listOf(text: String?): List<WidgetPlaylist> =
            runCatching {
                val array = JSONArray(text ?: return emptyList())
                (0 until array.length()).map { of(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())

        fun encode(list: List<WidgetPlaylist>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }
}

/**
 * プレイリストのウィジェットに出す一覧（ライブラリの「最近」と同じ並び: 最近再生したものが先、残りは追加順。高く評価した曲はいつも先頭）。
 * 読んだ一覧は端末に残し、読めないとき（ログアウト中・電波が無い）や読み直すまでの間はそれを出す。
 */
object WidgetPlaylists {
    private const val PREFS = "widget_playlists"
    private const val KEY_RECENT = "recent"

    /** 読み直す間隔（曲が替わるたびに描き直すので、毎回は読まない）。 */
    private const val REFRESH_MS = 5 * 60_000L

    private val lock = Mutex()

    @Volatile
    private var loadedAt = 0L

    fun saved(context: Context): List<WidgetPlaylist> = WidgetPlaylist.listOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECENT, null))

    /** 最近再生した順の一覧。読んでから間もなければ残した分を返す。 */
    suspend fun recent(
        context: Context,
        force: Boolean = false,
    ): List<WidgetPlaylist> =
        lock.withLock {
            val saved = saved(context)
            if (!force && saved.isNotEmpty() && SystemClock.elapsedRealtime() - loadedAt < REFRESH_MS && loadedAt != 0L) return saved
            val container = (context.applicationContext as WaveApplication).container
            if (container.authManager.state.value !is AuthState.LoggedIn) return saved
            val fresh =
                coroutineScope {
                    val recent = async { container.music.recentActivity() }
                    val playlists = container.music.libraryPlaylists()
                    val keys = (recent.await() as? CallResult.Ok)?.value.orEmpty().mapNotNull(LibraryOrder::keyOf)
                    (playlists as? CallResult.Ok)?.value?.let { arrange(it, keys, context.getString(R.string.main_liked_songs)) }
                }
            if (fresh == null) {
                Log.i(WaveApplication.LOG_TAG, "widget playlists load failed")
                return saved
            }
            loadedAt = SystemClock.elapsedRealtime()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RECENT, WidgetPlaylist.encode(fresh)).apply()
            fresh
        }

    /** 高く評価した曲を先頭に、残りを最近再生した順（同じなら追加順）に並べる。 */
    internal fun arrange(
        playlists: List<PlaylistSummary>,
        recentKeys: List<String>,
        likedTitle: String,
    ): List<WidgetPlaylist> {
        val rank = recentKeys.withIndex().associate { (index, key) -> key to index }
        val liked = playlists.firstOrNull { it.playlistId == MusicRepository.LIKED_SONGS } ?: PlaylistSummary(MusicRepository.LIKED_SONGS, likedTitle, null, null)
        val rest =
            playlists
                .filter { it.playlistId != MusicRepository.LIKED_SONGS }
                .withIndex()
                .sortedWith(compareBy({ rank["p-${it.value.playlistId}"] ?: Int.MAX_VALUE }, { it.index }))
                .map { it.value }
        return (listOf(liked) + rest).map(WidgetPlaylist::of)
    }

    /** ログアウトしたら残した一覧を消す。 */
    fun clear(context: Context) {
        loadedAt = 0L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
