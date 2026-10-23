package xyz.mooma0750.wave.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
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
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
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
import androidx.glance.text.TextAlign
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.playback.NowPlayingSnapshot

/**
 * プレイリストのウィジェット（iPhone の「プレイリスト」と同じ並び）。最近再生した順（既定）か、選んだプレイリストの表紙を並べ、
 * 押すとアプリを開かずにそのプレイリストを頭から鳴らす（ほかの端末を映している間はその端末で）。名前は出さない。
 * 小は表紙を 2×2、中は上に再生中の曲と前へ・再生/一時停止・次へ、下に表紙を4つ、大は下を 4×2 にする。
 * 鳴らしているプレイリストは表紙を縁取り、右下に鳴っている・止まっている印を出す。地は再生中のジャケット（無ければ先頭の表紙）をぼかしたもの。
 */
class PlaylistGridWidget : GlanceAppWidget() {
    // 表紙の大きさを置いた広さから決めるので、宣言した段ではなく実際の大きさで描く
    override val sizeMode = SizeMode.Exact

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val container = (context.applicationContext as WaveApplication).container
        // プロセスを起こしたばかりなら、ログインを読み終えるのを待つ
        withTimeoutOrNull(AUTH_WAIT_MS) { container.authManager.state.first { it !is AuthState.Restoring } }
        val recent = WidgetPlaylists.recent(context)
        val client = container.mediaHttpClient
        // 最初の描画で表紙が抜けないよう、先に取っておく
        recent.take(LARGE_COUNT).forEach { WidgetArtwork.load(client, it.thumbnailUrl) }
        WidgetArtwork.load(client, WidgetHooks.nowPlaying.value?.artworkUrl)
        provideContent {
            val prefs = currentState<Preferences>()
            val chosen = WidgetPlaylist.listOf(prefs[CHOSEN]).takeIf { prefs[SOURCE] == SOURCE_CHOSEN }.orEmpty()
            // 選ぶにしていても1つも選んでいなければ、最近再生した順で埋める
            val layout = layoutFor(LocalSize.current)
            val shown = (chosen.ifEmpty { recent }).take(if (layout == GridLayout.Large) LARGE_COUNT else 4)
            val now by WidgetHooks.nowPlaying.collectAsState()
            val covers = rememberWidgetArtworks(client, shown.mapNotNull { it.thumbnailUrl })
            val artwork = rememberWidgetArtwork(client, now?.artworkUrl)
            WaveGlanceTheme(context) {
                GridContent(shown, covers, now, artwork)
            }
        }
    }

    companion object {
        /** 2×2 ほど: 表紙を 2×2。 */
        val SMALL = DpSize(110.dp, 110.dp)

        /** 4×2: 上に再生中、下に表紙を4つ。 */
        val MEDIUM = DpSize(250.dp, 140.dp)

        /** 4×4: 上に再生中、下に表紙を 4×2。 */
        val LARGE = DpSize(250.dp, 260.dp)

        const val LARGE_COUNT = 8

        /** 出すもの（最近再生した順か、選んだプレイリスト）。 */
        val SOURCE = stringPreferencesKey("source")
        const val SOURCE_RECENT = "recent"
        const val SOURCE_CHOSEN = "chosen"

        /** 選んだプレイリスト（並べる順。WidgetPlaylist の JSON の配列）。 */
        val CHOSEN = stringPreferencesKey("chosen")

        private const val AUTH_WAIT_MS = 5_000L

        /** 置いた大きさから並べ方を決める（iPhone の小・中・大に当てる）。 */
        fun layoutFor(size: DpSize): GridLayout =
            when {
                size.width < MEDIUM.width || size.height < MEDIUM.height -> GridLayout.Small
                size.height < LARGE.height -> GridLayout.Medium
                else -> GridLayout.Large
            }
    }
}

enum class GridLayout { Small, Medium, Large }

class PlaylistGridWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = PlaylistGridWidget()
}

private val playlistIdKey = ActionParameters.Key<String>("playlistId")

@Composable
private fun GridContent(
    shown: List<WidgetPlaylist>,
    covers: Map<String, Bitmap>,
    now: NowPlayingSnapshot?,
    artwork: Bitmap?,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val layout = PlaylistGridWidget.layoutFor(size)
    val open = actionStartActivity(openAppIntent(context))
    // 中・大は、再生中の曲とプレイリストを色違いの入れ物に分けて上下につなぐ（YouTube Music のウィジェットと同じ見た目）
    val split = shown.isNotEmpty() && layout != GridLayout.Small
    WidgetSurface(split = split) {
        when {
            shown.isEmpty() ->
                Column(
                    GlanceModifier.fillMaxSize().padding(WidgetPadding).clickable(open),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        ImageProvider(R.drawable.ic_queue_music),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
                        modifier = GlanceModifier.size(32.dp),
                    )
                    Spacer(GlanceModifier.height(6.dp))
                    Text(context.getString(R.string.widget_playlists_sign_in), style = WidgetText.caption().copy(textAlign = TextAlign.Center), maxLines = 3)
                }
            // 小は表紙だけを 2×2 に
            layout == GridLayout.Small ->
                Box(GlanceModifier.fillMaxSize().padding(WidgetPadding), contentAlignment = Alignment.Center) {
                    Grid(shown, covers, now, rows = 2, columns = 2, spacing = TILE_GAP * 2, width = size.width - WidgetPadding * 2, height = size.height - WidgetPadding * 2)
                }
            // 中・大は上の入れ物に再生中の曲と操作、下の入れ物に表紙
            else ->
                Column(GlanceModifier.fillMaxSize()) {
                    WidgetContainer(GlanceModifier.fillMaxWidth().height(TOP_HEIGHT), secondary = false) {
                        Box(GlanceModifier.fillMaxSize().padding(horizontal = ContainerPadding), contentAlignment = Alignment.CenterStart) { NowPlayingRow(now, artwork) }
                    }
                    Spacer(GlanceModifier.height(ContainerGap))
                    WidgetContainer(GlanceModifier.fillMaxWidth().defaultWeight(), secondary = true) {
                        Box(GlanceModifier.fillMaxSize().padding(TILE_INSET), contentAlignment = Alignment.Center) {
                            Grid(
                                shown,
                                covers,
                                now,
                                rows = if (layout == GridLayout.Large) 2 else 1,
                                columns = 4,
                                spacing = TILE_GAP,
                                width = size.width - TILE_INSET * 2,
                                height = size.height - TOP_HEIGHT - ContainerGap - TILE_INSET * 2,
                            )
                        }
                    }
                }
        }
    }
}

/** 再生中の曲（ジャケット・曲名・アーティストか端末名）と前へ・再生/一時停止・次へ。押すと再生画面を開く。 */
@Composable
private fun NowPlayingRow(
    now: NowPlayingSnapshot?,
    artwork: Bitmap?,
) {
    val context = LocalContext.current
    val open = actionStartActivity(openAppIntent(context).putExtra(WidgetHooks.EXTRA_OPEN_NOW_PLAYING, true))
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (now == null) {
            Text(context.getString(R.string.widget_nothing_playing), style = WidgetText.subtitle(), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        } else {
            Artwork(artwork, GlanceModifier.size(40.dp), open, corner = 6.dp, iconSize = 20.dp)
            Spacer(GlanceModifier.width(10.dp))
            TitleAndArtist(now, GlanceModifier.defaultWeight().clickable(open), WidgetText.headline())
            Controls(now, compact = true)
        }
    }
}

/** 表紙を rows×columns に並べる（足りなければ空けておく。並びの大きさをそろえる）。 */
@Composable
private fun Grid(
    shown: List<WidgetPlaylist>,
    covers: Map<String, Bitmap>,
    now: NowPlayingSnapshot?,
    rows: Int,
    columns: Int,
    spacing: Dp,
    /** 並べられる広さ。 */
    width: Dp,
    height: Dp,
) {
    // 幅と高さの両方に収まる正方形の大きさ
    val byWidth = (width - spacing * (columns - 1)) / columns
    val byHeight = (height - spacing * (rows - 1)) / rows
    val side = minOf(byWidth, byHeight).coerceAtLeast(24.dp)
    Column(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in 0 until rows) {
            if (row > 0) Spacer(GlanceModifier.height(spacing))
            Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                for (column in 0 until columns) {
                    if (column > 0) Spacer(GlanceModifier.width(spacing))
                    val playlist = shown.getOrNull(row * columns + column)
                    if (playlist == null) {
                        Spacer(GlanceModifier.size(side))
                    } else {
                        Tile(playlist, playlist.thumbnailUrl?.let(covers::get), now, side)
                    }
                }
            }
        }
    }
}

/** 表紙1つ。鳴らしているプレイリストは縁取り、右下に鳴っている・止まっている印を出す（アプリの一覧と同じ考え）。 */
@Composable
private fun Tile(
    playlist: WidgetPlaylist,
    cover: Bitmap?,
    now: NowPlayingSnapshot?,
    side: Dp,
) {
    val context = LocalContext.current
    val current = now?.sourceId == playlist.id
    val play = actionRunCallback<PlayGridPlaylistAction>(actionParametersOf(playlistIdKey to playlist.id))
    // Glance には枠線が無いので、色を塗った角丸の上に一回り小さい表紙を重ねる
    val frame = if (current) GlanceModifier.background(GlanceTheme.colors.primary).padding(TILE_BORDER) else GlanceModifier
    Box(GlanceModifier.size(side).cornerRadius(TILE_CORNER).then(frame).clickable(play), contentAlignment = Alignment.BottomEnd) {
        val inner = GlanceModifier.fillMaxSize().cornerRadius(if (current) TILE_CORNER - TILE_BORDER else TILE_CORNER)
        if (cover != null) {
            Image(ImageProvider(cover), contentDescription = context.getString(R.string.widget_play_playlist, playlist.title), contentScale = ContentScale.Crop, modifier = inner)
        } else {
            Box(inner.background(GlanceTheme.colors.widgetBackground), contentAlignment = Alignment.Center) {
                Image(
                    ImageProvider(if (playlist.id == MusicRepository.LIKED_SONGS) R.drawable.ic_favorite else R.drawable.ic_queue_music),
                    contentDescription = context.getString(R.string.widget_play_playlist, playlist.title),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                    modifier = GlanceModifier.size((side / 3).coerceAtMost(28.dp)),
                )
            }
        }
        if (current) {
            // 明るい表紙の上でも見えるよう、暗い丸に白い印
            Box(GlanceModifier.padding(4.dp)) {
                Box(
                    GlanceModifier.size(20.dp).cornerRadius(10.dp).background(ColorProvider(Color.Black.copy(alpha = 0.6f))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        ImageProvider(if (now?.playing == true) R.drawable.ic_graphic_eq else R.drawable.ic_pause),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
                        modifier = GlanceModifier.size(13.dp),
                    )
                }
            }
        }
    }
}

private val TILE_CORNER = 10.dp

/** 表紙どうしの隙間と、入れ物の縁から表紙までの余白（YouTube Music の並びに合わせて詰める）。 */
private val TILE_GAP = 3.dp
private val TILE_INSET = 8.dp
private val TILE_BORDER = 3.dp

/** 中・大の上の入れ物（再生中の行）の高さ。 */
private val TOP_HEIGHT = COMPACT_BUTTON + ContainerPadding * 2

/** 表紙を押した: そのプレイリストを頭から鳴らす（iPhone と同じ）。 */
class PlayGridPlaylistAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val playlistId = parameters[playlistIdKey] ?: return
        val container = (context.applicationContext as WaveApplication).container
        val auth = withTimeoutOrNull(5_000L) { container.authManager.state.first { it !is AuthState.Restoring } }
        if (auth !is AuthState.LoggedIn) {
            toast(context, R.string.widget_sign_in)
            return
        }
        playPlaylist(context, playlistId, shuffle = false)
    }
}
