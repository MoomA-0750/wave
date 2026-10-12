package xyz.mooma0750.wave.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.SquareIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.playback.Media3QueuePlayer

/**
 * 1つのプレイリストのウィジェット。置くときに選んだプレイリストの表紙と名前を出し、再生（とシャッフル）を押すと、
 * アプリの画面を開かずに再生サービスでそのプレイリストを始める。表紙・名前を押すとアプリでそのプレイリストを開く。
 */
class PlaylistWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val client = (context.applicationContext as WaveApplication).container.mediaHttpClient
        provideContent {
            val prefs = currentState<Preferences>()
            val playlist = WidgetPlaylist.of(prefs)
            val artwork = rememberWidgetArtwork(client, playlist?.thumbnailUrl?.let(Media3QueuePlayer::largeArtwork))
            WaveGlanceTheme(context) {
                PlaylistContent(playlist, artwork, loading = prefs[LOADING] == true)
            }
        }
    }

    companion object {
        /** 2×2 ほど: 表紙と再生ボタン。 */
        val SMALL = DpSize(110.dp, 110.dp)

        /** 4×1〜4×2: 表紙の横に名前とボタン。 */
        val WIDE = DpSize(220.dp, 48.dp)

        /** 3×3 以上: 大きな表紙の下に名前とボタン。 */
        val LARGE = DpSize(180.dp, 220.dp)

        val PLAYLIST_ID = stringPreferencesKey("playlistId")
        val TITLE = stringPreferencesKey("title")
        val THUMBNAIL = stringPreferencesKey("thumbnail")

        /** 再生を押して、曲を読んでいる間。 */
        val LOADING = booleanPreferencesKey("loading")
    }
}

class PlaylistWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = PlaylistWidget()
}

/** ウィジェットに選んだプレイリスト。 */
data class WidgetPlaylist(
    val playlistId: String,
    val title: String,
    val thumbnailUrl: String?,
) {
    companion object {
        fun of(prefs: Preferences): WidgetPlaylist? {
            val id = prefs[PlaylistWidget.PLAYLIST_ID] ?: return null
            return WidgetPlaylist(id, prefs[PlaylistWidget.TITLE].orEmpty(), prefs[PlaylistWidget.THUMBNAIL])
        }
    }
}

private val playlistIdKey = ActionParameters.Key<String>("playlistId")
private val shuffleKey = ActionParameters.Key<Boolean>("shuffle")

@Composable
private fun PlaylistContent(
    playlist: WidgetPlaylist?,
    artwork: Bitmap?,
    loading: Boolean,
) {
    val context = LocalContext.current
    if (playlist == null) {
        // 選ぶ前（置いている途中）
        WidgetSurface {
            Box(GlanceModifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                Text(context.getString(R.string.widget_playlist_not_chosen), style = WidgetText.subtitle(), maxLines = 3)
            }
        }
        return
    }
    val open =
        actionStartActivity(
            openAppIntent(context)
                .putExtra(WidgetHooks.EXTRA_OPEN_PLAYLIST_ID, playlist.playlistId)
                .putExtra(WidgetHooks.EXTRA_OPEN_PLAYLIST_TITLE, playlist.title),
        )
    val size = LocalSize.current
    val liked = playlist.playlistId == MusicRepository.LIKED_SONGS
    WidgetSurface(GlanceModifier.clickable(open)) {
        when (size) {
            PlaylistWidget.SMALL ->
                Box(GlanceModifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.BottomEnd) {
                    Cover(artwork, GlanceModifier.fillMaxSize(), open, liked, Alignment.TopStart)
                    Box(GlanceModifier.padding(6.dp)) { PlayButton(playlist, loading, GlanceModifier.size(COMPACT_BUTTON)) }
                }
            PlaylistWidget.WIDE ->
                Row(GlanceModifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val side = (size.height - 12.dp).coerceIn(44.dp, 120.dp)
                    Cover(artwork, GlanceModifier.size(side), open, liked)
                    Spacer(GlanceModifier.width(10.dp))
                    Text(playlist.title, style = WidgetText.title(), maxLines = 2, modifier = GlanceModifier.defaultWeight().clickable(open))
                    ShuffleButton(playlist, loading)
                    PlayButton(playlist, loading, GlanceModifier.size(COMPACT_BUTTON))
                }
            else ->
                Column(GlanceModifier.fillMaxSize().padding(12.dp)) {
                    Cover(artwork, GlanceModifier.fillMaxWidth().defaultWeight(), open, liked)
                    Spacer(GlanceModifier.height(8.dp))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(playlist.title, style = WidgetText.title(), maxLines = 2, modifier = GlanceModifier.defaultWeight().clickable(open))
                        ShuffleButton(playlist, loading)
                        PlayButton(playlist, loading)
                    }
                }
        }
    }
}

@Composable
private fun Cover(
    artwork: Bitmap?,
    modifier: GlanceModifier,
    open: Action,
    liked: Boolean,
    /** 再生ボタンを重ねるとき、仮の絵を左上に寄せる（ボタンで隠れないように）。 */
    iconAlignment: Alignment = Alignment.Center,
) {
    val base = modifier.cornerRadius(ArtworkCorner).clickable(open)
    if (artwork != null) {
        Image(ImageProvider(artwork), contentDescription = null, contentScale = ContentScale.Crop, modifier = base)
    } else {
        Box(base.background(GlanceTheme.colors.secondaryContainer).padding(12.dp), contentAlignment = iconAlignment) {
            Image(
                ImageProvider(if (liked) R.drawable.ic_favorite else R.drawable.ic_queue_music),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                modifier = GlanceModifier.size(36.dp),
            )
        }
    }
}

/** 主役の操作（再生）は角の丸い四角で大きく塗る。読んでいる間は待ちの表示にする。 */
@Composable
private fun PlayButton(
    playlist: WidgetPlaylist,
    loading: Boolean,
    modifier: GlanceModifier = GlanceModifier.size(60.dp),
) {
    val context = LocalContext.current
    if (loading) {
        Box(modifier.cornerRadius(16.dp).background(GlanceTheme.colors.primary), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(GlanceModifier.size(28.dp), color = GlanceTheme.colors.onPrimary)
        }
        return
    }
    SquareIconButton(
        ImageProvider(R.drawable.ic_play_arrow),
        contentDescription = context.getString(R.string.widget_play_playlist, playlist.title),
        onClick = actionRunCallback<PlayPlaylistAction>(actionParametersOf(playlistIdKey to playlist.playlistId, shuffleKey to false)),
        modifier = modifier,
        backgroundColor = GlanceTheme.colors.primary,
        contentColor = GlanceTheme.colors.onPrimary,
    )
}

@Composable
private fun ShuffleButton(
    playlist: WidgetPlaylist,
    loading: Boolean,
) {
    val context = LocalContext.current
    CircleIconButton(
        ImageProvider(R.drawable.ic_shuffle),
        contentDescription = context.getString(R.string.widget_shuffle_playlist, playlist.title),
        onClick = actionRunCallback<PlayPlaylistAction>(actionParametersOf(playlistIdKey to playlist.playlistId, shuffleKey to true)),
        enabled = !loading,
        backgroundColor = GlanceTheme.colors.secondaryContainer,
        contentColor = GlanceTheme.colors.onSecondaryContainer,
    )
    Spacer(GlanceModifier.width(6.dp))
}

/**
 * プレイリストの最初のページを読んで、再生サービスで再生する（続きはキューが読み足す）。ほかの端末を映している間は、
 * アプリの画面から選んだときと同じく、その端末で鳴らしてもらう。
 */
class PlayPlaylistAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val playlistId = parameters[playlistIdKey] ?: return
        val shuffle = parameters[shuffleKey] == true
        val container = (context.applicationContext as WaveApplication).container
        val widget = PlaylistWidget()
        suspend fun setLoading(value: Boolean) {
            updateAppWidgetState(context, glanceId) { it[PlaylistWidget.LOADING] = value }
            widget.update(context, glanceId)
        }
        setLoading(true)
        try {
            // プロセスを起こしたばかりなら、ログインを読み終えるのを待つ
            val auth = withTimeoutOrNull(AUTH_WAIT_MS) { container.authManager.state.first { it !is AuthState.Restoring } }
            if (auth !is AuthState.LoggedIn) {
                toast(context, R.string.widget_sign_in)
                return
            }
            val order = container.settings.playlistOrders.get(playlistId)
            // 手元で並べる並べ方（全部読んでから並べる）は、ウィジェットでは最初のページをサーバーの並びで始める
            val page = container.music.playlist(playlistId, order = order.takeUnless { it.arrangedLocally })
            when (page) {
                is CallResult.Ok ->
                    if (page.value.songs.isEmpty()) {
                        toast(context, R.string.widget_playlist_empty)
                    } else {
                        container.player.playFrom(page.value.songs, 0, playlistId, page.value.continuation, shuffle)
                    }
                is CallResult.Failed -> {
                    Log.w(WaveApplication.LOG_TAG, "widget playlist failed kind=${page.kind}")
                    toast(context, R.string.widget_playlist_failed)
                }
            }
        } finally {
            setLoading(false)
        }
    }

    private suspend fun toast(
        context: Context,
        text: Int,
    ) = withContext(Dispatchers.Main) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }

    private companion object {
        const val AUTH_WAIT_MS = 5_000L
    }
}
