package xyz.mooma0750.wave.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.MusicRepository

/**
 * ランチャーのアプリのアイコンを長押ししたときに出す「最近のプレイリスト」（動的ショートカット。iPhone のショートカットのアプリの
 * 「プレイリストを再生」に当たる）。プレイリストのウィジェットと同じ最近再生した順の先頭 [COUNT] 個を、表紙を絵にして出す。
 * 押すとアプリの画面を出さずにそのプレイリストを頭から鳴らす（ウィジェットの表紙と同じ。[PlaylistShortcutActivity]）。
 */
object PlaylistShortcuts {
    /** ランチャーが長押しに並べるのは4〜5個ほど（アプリ情報などと合わせて）。 */
    private const val COUNT = 4
    private const val PREFS = "playlist_shortcuts"
    private const val KEY_PUBLISHED = "published"
    private const val ID_PREFIX = "playlist:"
    const val EXTRA_PLAYLIST_ID = "xyz.mooma0750.wave.shortcut.PLAYLIST_ID"

    private val lock = Mutex()

    /** 起動したとき: ログインを読み終えるのを待ち、最近再生した順を読んで出し直す。 */
    suspend fun refresh(context: Context) {
        val container = (context.applicationContext as WaveApplication).container
        val auth = withTimeoutOrNull(AUTH_WAIT_MS) { container.authManager.state.first { it !is AuthState.Restoring } }
        if (auth !is AuthState.LoggedIn) return
        publish(context, WidgetPlaylists.recent(context))
    }

    /** 一覧が変わっていれば出し直す（同じなら何もしない。OS が裏からの書き換えの回数を絞るため）。 */
    fun publish(
        context: Context,
        playlists: List<WidgetPlaylist>,
    ) {
        val app = context.applicationContext
        (app as WaveApplication).container.appScope.launch {
            lock.withLock {
                val shown = playlists.take(COUNT)
                if (shown.isEmpty()) return@withLock
                val covers = shown.associate { it.id to WidgetArtwork.load(app.container.mediaHttpClient, it.thumbnailUrl) }
                // 表紙が取れなかった分は次に取れたときに出し直したいので、取れたかも比べる
                val key = shown.joinToString("\n") { "${it.id}\t${it.title}\t${it.thumbnailUrl}\t${covers[it.id] != null}" }
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (prefs.getString(KEY_PUBLISHED, null) == key) return@withLock
                val shortcuts = shown.mapIndexed { rank, playlist -> shortcutOf(app, playlist, covers[playlist.id], rank) }
                runCatching { ShortcutManagerCompat.setDynamicShortcuts(app, shortcuts) }
                    .onSuccess { ok ->
                        Log.i(WaveApplication.LOG_TAG, "playlist shortcuts published=$ok count=${shortcuts.size}")
                        if (ok) prefs.edit().putString(KEY_PUBLISHED, key).apply()
                    }.onFailure { Log.w(WaveApplication.LOG_TAG, "playlist shortcuts failed type=${it::class.simpleName}") }
            }
        }
    }

    /** ログアウトした: 出していたものを消す。 */
    fun clear(context: Context) {
        val app = context.applicationContext
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(app) }
            .onFailure { Log.w(WaveApplication.LOG_TAG, "playlist shortcuts clear failed type=${it::class.simpleName}") }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun shortcutOf(
        context: Context,
        playlist: WidgetPlaylist,
        cover: Bitmap?,
        rank: Int,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat
            .Builder(context, ID_PREFIX + playlist.id)
            .setShortLabel(playlist.title)
            .setLongLabel(context.getString(R.string.widget_play_playlist, playlist.title))
            .setIcon(IconCompat.createWithAdaptiveBitmap(cover?.let(::adaptive) ?: placeholder(context, if (playlist.id == MusicRepository.LIKED_SONGS) R.drawable.ic_favorite else R.drawable.ic_queue_music)))
            .setIntent(
                Intent(context, PlaylistShortcutActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(EXTRA_PLAYLIST_ID, playlist.id),
            ).setRank(rank)
            .build()

    /** 表紙を、ランチャーが形で切る絵の大きさ（四方に余白のある 108dp 角）いっぱいに敷く。 */
    internal fun adaptive(cover: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(ICON_PIXELS, ICON_PIXELS, Bitmap.Config.ARGB_8888)
        val side = minOf(cover.width, cover.height)
        val source = Rect((cover.width - side) / 2, (cover.height - side) / 2, (cover.width + side) / 2, (cover.height + side) / 2)
        Canvas(out).drawBitmap(cover, source, Rect(0, 0, ICON_PIXELS, ICON_PIXELS), null)
        return out
    }

    /** 表紙が無い（高く評価した曲など）: ウィジェットの表紙の代わりと同じく、tonal の地に印。 */
    internal fun placeholder(
        context: Context,
        @DrawableRes iconRes: Int,
    ): Bitmap {
        val colors = dynamicLightColorScheme(context)
        val out = Bitmap.createBitmap(ICON_PIXELS, ICON_PIXELS, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(colors.secondaryContainer.toArgb())
        val icon = ContextCompat.getDrawable(context, iconRes)
        if (icon != null) {
            // 形で切られても残る真ん中（108 のうち 72）の、さらに内側に置く
            val inset = ICON_PIXELS * 3 / 8
            icon.setTint(colors.onSecondaryContainer.toArgb())
            icon.setBounds(inset, inset, ICON_PIXELS - inset, ICON_PIXELS - inset)
            icon.draw(canvas)
        }
        return out
    }

    /** 絵の大きさ（108dp を xxxhdpi で）。 */
    private const val ICON_PIXELS = 432
    private const val AUTH_WAIT_MS = 5_000L
}

/** 「最近のプレイリスト」を押した: 画面を出さずにそのプレイリストを鳴らして閉じる（ウィジェットの表紙を押したのと同じ）。 */
class PlaylistShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val playlistId = intent.getStringExtra(PlaylistShortcuts.EXTRA_PLAYLIST_ID)
        if (playlistId != null) {
            ShortcutManagerCompat.reportShortcutUsed(this, "playlist:$playlistId")
            val app = applicationContext
            (app as WaveApplication).container.appScope.launch {
                val auth = withTimeoutOrNull(5_000L) { app.container.authManager.state.first { it !is AuthState.Restoring } }
                if (auth !is AuthState.LoggedIn) {
                    toast(app, R.string.widget_sign_in)
                    return@launch
                }
                playPlaylist(app, playlistId, shuffle = false)
            }
        }
        finish()
    }
}
