package xyz.mooma0750.wave.debug

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.widget.NowPlayingWidgetReceiver
import xyz.mooma0750.wave.widget.PlaylistWidget
import xyz.mooma0750.wave.widget.PlaylistWidgetReceiver

/**
 * 確認用（debug だけ）: ホーム画面に置かなくても、ウィジェットを大きさを変えて並べて描く。押すと本物と同じく動く。
 * 先に `adb shell appwidget grantbind --package xyz.mooma0750.wave` で、このアプリに置く許可を出しておく。
 * `--es playlist <ID>` でプレイリストのウィジェットの中身を決める（無ければ高く評価した曲）。
 */
class WidgetHostActivity : Activity() {
    private lateinit var host: AppWidgetHost
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        host = AppWidgetHost(this, HOST_ID)
        val manager = AppWidgetManager.getInstance(this)
        // 前回の分を片付けてから置き直す
        host.deleteHost()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val density = resources.displayMetrics.density
        val playlistId = intent.getStringExtra("playlist") ?: MusicRepository.LIKED_SONGS
        val playlistTitle = intent.getStringExtra("title") ?: "Liked Songs"
        val nowPlaying = ComponentName(this, NowPlayingWidgetReceiver::class.java)
        val playlist = ComponentName(this, PlaylistWidgetReceiver::class.java)
        val cases =
            listOf(
                Triple(nowPlaying, 180f, 56f),
                Triple(nowPlaying, 130f, 130f),
                Triple(nowPlaying, 320f, 70f),
                Triple(nowPlaying, 320f, 130f),
                Triple(nowPlaying, 260f, 290f),
                Triple(playlist, 130f, 130f),
                Triple(playlist, 320f, 80f),
                Triple(playlist, 220f, 250f),
            )
        for ((provider, width, height) in cases) {
            val id = host.allocateAppWidgetId()
            if (!manager.bindAppWidgetIdIfAllowed(id, provider)) {
                column.addView(TextView(this).apply { text = "bind not allowed: run appwidget grantbind" })
                break
            }
            val info = manager.getAppWidgetInfo(id)
            val view = host.createView(applicationContext, id, info)
            view.updateAppWidgetSize(Bundle(), listOf(SizeF(width, height)))
            column.addView(
                view,
                LinearLayout.LayoutParams((width * density).toInt(), (height * density).toInt()).apply { setMargins(0, (8 * density).toInt(), 0, 0) },
            )
            if (provider == playlist) {
                scope.launch {
                    runCatching {
                        val glanceId = GlanceAppWidgetManager(this@WidgetHostActivity).getGlanceIdBy(id)
                        updateAppWidgetState(this@WidgetHostActivity, glanceId) {
                            it[PlaylistWidget.PLAYLIST_ID] = playlistId
                            it[PlaylistWidget.TITLE] = playlistTitle
                            intent.getStringExtra("thumbnail")?.let { url -> it[PlaylistWidget.THUMBNAIL] = url }
                        }
                        PlaylistWidget().update(this@WidgetHostActivity, glanceId)
                    }.onFailure { Log.w(WaveApplication.LOG_TAG, "widget host state failed", it) }
                }
            }
            Log.i(WaveApplication.LOG_TAG, "widget host bound id=$id provider=${provider.shortClassName} size=${width}x$height")
        }
        setContentView(ScrollView(this).apply { addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
    }

    override fun onStart() {
        super.onStart()
        host.startListening()
    }

    override fun onStop() {
        host.stopListening()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val HOST_ID = 0x5741
    }
}
