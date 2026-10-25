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
import xyz.mooma0750.wave.widget.PlaylistGridWidget
import xyz.mooma0750.wave.widget.PlaylistGridWidgetReceiver
import xyz.mooma0750.wave.widget.PlaylistWidget
import xyz.mooma0750.wave.widget.PlaylistWidgetReceiver
import xyz.mooma0750.wave.widget.QueueWidgetReceiver
import xyz.mooma0750.wave.widget.WidgetItem
import xyz.mooma0750.wave.widget.WidgetItemKind
import xyz.mooma0750.wave.widget.WidgetPlaylist
import xyz.mooma0750.wave.widget.WidgetPlaylists

/**
 * 確認用（debug だけ）: ホーム画面に置かなくても、ウィジェットを大きさを変えて並べて描く。押すと本物と同じく動く。
 * 先に `adb shell appwidget grantbind --package xyz.mooma0750.wave` で、このアプリに置く許可を出しておく。
 * `--es playlist <ID>` でワンタップ再生のウィジェットの中身を決める（無ければ高く評価した曲）。
 * `--es kind Album|Song|Live` と `--es subtitle <文>` で、プレイリスト以外にもできる（ID は playlist に渡す）。
 * `--ei grid <数>` で、プレイリストのウィジェットを「選んだプレイリスト」にして、最近再生した順の先頭からその数だけ並べる。
 */
class WidgetHostActivity : Activity() {
    private lateinit var host: AppWidgetHost
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // `--ez preview true` で、置いたウィジェットを見本で描く（previewImage の画像を撮るため）
        xyz.mooma0750.wave.widget.WidgetPreview.forced = intent.getBooleanExtra("preview", false)
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
        val grid = ComponentName(this, PlaylistGridWidgetReceiver::class.java)
        val queue = ComponentName(this, QueueWidgetReceiver::class.java)
        val gridCount = intent.getIntExtra("grid", 0)
        // `--es only now|item|grid|queue` でその種類だけを並べる（画面に収めて撮るため）
        val only = intent.getStringExtra("only")
        val cases =
            listOf(
                Triple(nowPlaying, 180f, 56f),
                Triple(nowPlaying, 160f, 160f),
                Triple(nowPlaying, 320f, 70f),
                Triple(nowPlaying, 340f, 140f),
                Triple(nowPlaying, 340f, 160f),
                Triple(nowPlaying, 340f, 190f),
                Triple(playlist, 160f, 160f),
                Triple(playlist, 340f, 160f),
                Triple(playlist, 340f, 340f),
                Triple(grid, 160f, 160f),
                Triple(grid, 340f, 160f),
                Triple(grid, 340f, 340f),
                Triple(queue, 160f, 160f),
                Triple(queue, 340f, 160f),
                Triple(queue, 340f, 340f),
            ).let { own ->
                // `--es provider <パッケージ/クラス>` で、ほかのアプリのウィジェットを同じ大きさで並べる（見比べるため）
                val other = intent.getStringExtra("provider")?.let(ComponentName::unflattenFromString) ?: return@let own
                listOf(160f to 160f, 340f to 160f, 340f to 250f, 340f to 340f).map { (w, h) -> Triple(other, w, h) }
            }.filter { (provider, _, _) ->
                if (provider.packageName != packageName) return@filter true
                when (only) {
                    "now" -> provider == nowPlaying
                    "item" -> provider == playlist
                    "grid" -> provider == grid
                    "queue" -> provider == queue
                    else -> true
                }
            }
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
                            val kind = intent.getStringExtra("kind")?.let { name -> WidgetItemKind.entries.firstOrNull { k -> k.name == name } } ?: WidgetItemKind.Playlist
                            WidgetItem(kind, playlistId, playlistTitle, intent.getStringExtra("subtitle"), intent.getStringExtra("thumbnail")).writeTo(it)
                        }
                        PlaylistWidget().update(this@WidgetHostActivity, glanceId)
                    }.onFailure { Log.w(WaveApplication.LOG_TAG, "widget host state failed", it) }
                }
            }
            if (provider == grid && gridCount > 0) {
                scope.launch {
                    runCatching {
                        val chosen = WidgetPlaylists.recent(this@WidgetHostActivity).take(gridCount)
                        val glanceId = GlanceAppWidgetManager(this@WidgetHostActivity).getGlanceIdBy(id)
                        updateAppWidgetState(this@WidgetHostActivity, glanceId) {
                            it[PlaylistGridWidget.SOURCE] = PlaylistGridWidget.SOURCE_CHOSEN
                            it[PlaylistGridWidget.CHOSEN] = WidgetPlaylist.encode(chosen)
                        }
                        PlaylistGridWidget().update(this@WidgetHostActivity, glanceId)
                    }.onFailure { Log.w(WaveApplication.LOG_TAG, "widget host grid state failed", it) }
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
