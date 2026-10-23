package xyz.mooma0750.wave.widget

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import xyz.mooma0750.wave.BuildConfig
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.playback.NowPlayingSnapshot

/**
 * ランチャーのウィジェット追加画面に出す見本（本物と同じ部品で、仮の曲・プレイリストを描く）。
 * Android 15 以降はアプリが起動したときに見本を OS へ渡す（providePreview。ランチャーが対応していれば出る）。
 * それより前の端末・対応していないランチャー向けには、同じ見本を撮った画像を previewImage に入れてある（[forced] で描いて撮る）。
 */
object WidgetPreview {
    /** 確認用: 置いたウィジェットを見本で描く（previewImage の画像を撮るため。開発版の WidgetHostActivity から入れる）。 */
    @Volatile
    var forced = false

    private const val PREFS = "widget_preview"
    private const val KEY_VERSION = "published_version"

    fun nowPlaying(context: Context): NowPlayingSnapshot =
        NowPlayingSnapshot(
            title = context.getString(R.string.widget_preview_song),
            artist = context.getString(R.string.widget_preview_artist),
            artworkUrl = null,
            playing = true,
            saved = false,
            upNext =
                (1..NowPlayingSnapshot.UP_NEXT_COUNT).map {
                    NowPlayingSnapshot.UpNext("preview-$it", context.getString(R.string.widget_preview_next_song, it), context.getString(R.string.widget_preview_artist), null)
                },
        )

    fun item(context: Context): WidgetItem = WidgetItem(WidgetItemKind.Playlist, MusicRepository.LIKED_SONGS, context.getString(R.string.main_liked_songs), null, null)

    fun playlists(context: Context): List<WidgetPlaylist> =
        listOf(WidgetPlaylist(MusicRepository.LIKED_SONGS, context.getString(R.string.main_liked_songs), null)) +
            (1 until PlaylistGridWidget.LARGE_COUNT).map { WidgetPlaylist("preview-$it", context.getString(R.string.widget_preview_playlist, it), null) }

    /** Android 15 以降: 見本を OS に渡す（版ごとに一度。OS が回数を絞るので、絞られたら次の起動でやり直す）。 */
    suspend fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) == BuildConfig.VERSION_CODE) return
        val manager = GlanceAppWidgetManager(context)
        val results =
            runCatching {
                listOf(
                    manager.setWidgetPreviews(NowPlayingWidgetReceiver::class),
                    manager.setWidgetPreviews(PlaylistWidgetReceiver::class),
                    manager.setWidgetPreviews(PlaylistGridWidgetReceiver::class),
                )
            }.onFailure { Log.w(WaveApplication.LOG_TAG, "widget previews failed type=${it::class.simpleName}") }
                .getOrNull() ?: return
        Log.i(WaveApplication.LOG_TAG, "widget previews results=$results")
        if (results.all { it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }) prefs.edit().putInt(KEY_VERSION, BuildConfig.VERSION_CODE).apply()
    }
}
