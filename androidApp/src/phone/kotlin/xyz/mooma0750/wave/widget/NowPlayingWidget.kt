package xyz.mooma0750.wave.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.SquareIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.library.AddToPlaylistActivity
import xyz.mooma0750.wave.library.LikedNotice
import xyz.mooma0750.wave.playback.NowPlayingSnapshot
import xyz.mooma0750.wave.playback.SongJson
import xyz.mooma0750.wave.playback.playFromButton

/**
 * 再生中の曲のウィジェット（iPhone の「再生中」と同じ並び）。ジャケット（右下に評価のボタン）・曲名・アーティスト
 * （ほかの端末で鳴っていれば端末名）と、前へ・再生/一時停止・次へ。広ければ右に「次に流れる曲」を3曲並べ、押すとその曲へ飛ぶ。
 * 地はジャケットをぼかして暗くしたもの。押すとアプリの再生画面を開く。
 * ほかの端末の再生を映している間はその端末の曲を出し、ボタンはその端末の遠隔操作になる（再生サービスのセッションの再生器が替わるため）。
 * 低いとき（iPhone に無い大きさ）は、ボタンだけか1列にする。
 */
class NowPlayingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(CONTROLS, SQUARE, WIDE, MEDIUM, MEDIUM_TALL))

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val container = (context.applicationContext as WaveApplication).container
        // 最初の描画で画像が抜けないよう、今の曲の画像は先に取っておく
        val first = WidgetHooks.nowPlaying.value
        WidgetArtwork.load(container.mediaHttpClient, first?.artworkUrl)
        first?.upNext?.forEach { WidgetArtwork.load(container.mediaHttpClient, it.artworkUrl) }
        provideContent {
            // 描いている間に曲が変わったら、そのまま描き直す
            val snapshot by WidgetHooks.nowPlaying.collectAsState()
            val artwork = rememberWidgetArtwork(container.mediaHttpClient, snapshot?.artworkUrl)
            val queueArtwork = rememberWidgetArtworks(container.mediaHttpClient, snapshot?.upNext.orEmpty().mapNotNull { it.artworkUrl })
            WaveGlanceTheme(context) {
                NowPlayingContent(snapshot, artwork, queueArtwork)
            }
        }
    }

    companion object {
        /** 2×1 ほど: ボタンだけ。 */
        val CONTROLS = DpSize(150.dp, 48.dp)

        /** 2×2 ほど（iPhone の小）: ジャケットと評価、曲名、ボタン。 */
        val SQUARE = DpSize(110.dp, 110.dp)

        /** 4×1: ジャケット・曲名・再生・次へを1列に（ボタンだけの大きさより広いとき）。 */
        val WIDE = DpSize(250.dp, 48.dp)

        /** 4×2 以上（iPhone の中）: 左に今の曲とボタン、右に次に流れる曲。 */
        val MEDIUM = DpSize(250.dp, 110.dp)

        /** 4×3 ほど: 中と同じ並びで、ジャケットの下に曲名を置く（iPhone の中と同じ）。 */
        val MEDIUM_TALL = DpSize(250.dp, 200.dp)
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NowPlayingWidget()
}

private val itemIdKey = ActionParameters.Key<String>("itemId")
private val videoIdKey = ActionParameters.Key<String>("videoId")

@Composable
private fun NowPlayingContent(
    snapshot: NowPlayingSnapshot?,
    artwork: Bitmap?,
    queueArtwork: Map<String, Bitmap>,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val open = actionStartActivity(openAppIntent(context).putExtra(WidgetHooks.EXTRA_OPEN_NOW_PLAYING, true))
    // 中くらいは、今の曲と次に流れる曲を色違いの入れ物に分けて左右につなぐ
    val split = snapshot != null && (size == NowPlayingWidget.MEDIUM || size == NowPlayingWidget.MEDIUM_TALL)
    WidgetSurface(GlanceModifier.clickable(open), split = split) {
        // Responsive では、置いた大きさに収まる宣言した大きさのうち最も大きいものが来る
        when {
            snapshot == null -> EmptyState(open)
            size == NowPlayingWidget.CONTROLS -> Controls(snapshot, GlanceModifier.fillMaxSize().padding(horizontal = 8.dp), compact = true)
            size == NowPlayingWidget.SQUARE -> SmallLayout(snapshot, artwork, open)
            size == NowPlayingWidget.WIDE -> WideLayout(snapshot, artwork, open)
            else -> MediumLayout(snapshot, artwork, queueArtwork, open)
        }
    }
}

@Composable
private fun EmptyState(open: Action) {
    val context = LocalContext.current
    val size = LocalSize.current
    val icon = @Composable { dp: androidx.compose.ui.unit.Dp ->
        Image(
            ImageProvider(R.drawable.ic_music_note),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.size(dp),
        )
    }
    when {
        // 低い: 1行だけ
        size.height < NowPlayingWidget.SQUARE.height ->
            Row(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp).clickable(open), verticalAlignment = Alignment.CenterVertically) {
                icon(24.dp)
                Spacer(GlanceModifier.width(10.dp))
                Text(context.getString(R.string.widget_nothing_playing), style = WidgetText.title(), maxLines = 1)
            }
        // 狭い: 縦に並べる
        size.width < NowPlayingWidget.WIDE.width ->
            Column(GlanceModifier.fillMaxSize().padding(14.dp).clickable(open), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                icon(32.dp)
                Spacer(GlanceModifier.height(8.dp))
                Text(context.getString(R.string.widget_nothing_playing), style = WidgetText.title().copy(textAlign = androidx.glance.text.TextAlign.Center), maxLines = 2)
            }
        else ->
            Row(GlanceModifier.fillMaxSize().padding(16.dp).clickable(open), verticalAlignment = Alignment.CenterVertically) {
                icon(32.dp)
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(context.getString(R.string.widget_nothing_playing), style = WidgetText.title(), maxLines = 1)
                    Text(context.getString(R.string.widget_open_app), style = WidgetText.subtitle(), maxLines = 1)
                }
            }
    }
}

@Composable
internal fun Artwork(
    artwork: Bitmap?,
    modifier: GlanceModifier,
    /** null なら押せない（押すのは外の行に任せる）。 */
    open: Action?,
    corner: androidx.compose.ui.unit.Dp = ArtworkCorner,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp,
) {
    val base = modifier.cornerRadius(corner).let { if (open != null) it.clickable(open) else it }
    if (artwork != null) {
        Image(ImageProvider(artwork), contentDescription = null, contentScale = ContentScale.Crop, modifier = base)
    } else {
        Box(base.background(GlanceTheme.colors.secondaryContainer), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(R.drawable.ic_music_note),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                modifier = GlanceModifier.size(iconSize),
            )
        }
    }
}

/** ジャケットの右下に評価のボタンを重ねたもの（出せないときは絵だけ）。 */
@Composable
private fun ArtworkWithLike(
    snapshot: NowPlayingSnapshot,
    artwork: Bitmap?,
    side: androidx.compose.ui.unit.Dp,
    open: Action,
) {
    Box(GlanceModifier.size(side), contentAlignment = Alignment.BottomEnd) {
        Artwork(artwork, GlanceModifier.size(side), open)
        snapshot.saved?.let { saved -> LikeButton(snapshot, saved) }
    }
}

/**
 * 評価のボタン（アプリと同じ）。＋は押すと高く評価し、✓（高く評価したかプレイリストに入っている）は
 * プレイリストに追加する画面を出す。絵の上でも見えるよう、暗い丸を敷く。
 */
@Composable
private fun LikeButton(
    snapshot: NowPlayingSnapshot,
    saved: Boolean,
) {
    val context = LocalContext.current
    val song = snapshot.song
    val action =
        if (saved && song != null) {
            actionStartActivity(
                Intent(context, AddToPlaylistActivity::class.java)
                    .putExtra(LikedNotice.EXTRA_SONG, SongJson.encode(song))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        } else {
            actionRunCallback<LikeAction>(actionParametersOf(videoIdKey to snapshot.videoId.orEmpty()))
        }
    Box(
        GlanceModifier
            .padding(3.dp)
            .size(LIKE_BUTTON)
            .cornerRadius(LIKE_BUTTON / 2)
            .background(ColorProvider(Color.Black.copy(alpha = 0.45f)))
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            ImageProvider(if (saved) R.drawable.ic_check_circle else R.drawable.ic_add_circle),
            contentDescription = context.getString(if (saved) R.string.library_add_to_playlist else R.string.playback_like),
            colorFilter = ColorFilter.tint(if (saved) GlanceTheme.colors.primary else ColorProvider(Color.White)),
            modifier = GlanceModifier.size(LIKE_BUTTON - 6.dp),
        )
    }
}

/** 曲名と、アーティストかほかの端末の名前（端末名は印付きで色を変える）。 */
@Composable
internal fun TitleAndArtist(
    snapshot: NowPlayingSnapshot,
    modifier: GlanceModifier = GlanceModifier,
    titleStyle: TextStyle = WidgetText.title(),
) {
    Column(modifier) {
        Text(snapshot.title, style = titleStyle, maxLines = 1)
        val device = snapshot.device
        if (device != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    ImageProvider(R.drawable.ic_devices),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    modifier = GlanceModifier.size(14.dp),
                )
                Spacer(GlanceModifier.width(4.dp))
                Text(device, style = WidgetText.device(), maxLines = 1)
            }
        } else if (snapshot.artist.isNotEmpty()) {
            Text(snapshot.artist, style = WidgetText.subtitle(), maxLines = 1)
        }
    }
}

/** 前へ・再生（主役なので塗る）・次へ。 */
@Composable
internal fun Controls(
    snapshot: NowPlayingSnapshot,
    modifier: GlanceModifier = GlanceModifier,
    /** 低い・狭いウィジェット: ボタンを小さくする（はみ出さないように）。 */
    compact: Boolean = false,
    alignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    /** 再生ボタンの大きさ（既定は compact で決める）。 */
    playSize: androidx.compose.ui.unit.Dp = if (compact) COMPACT_BUTTON else PLAY_BUTTON,
) {
    val context = LocalContext.current
    val side = if (playSize < COMPACT_BUTTON) SMALL_SIDE_BUTTON else if (compact) COMPACT_SIDE_BUTTON else SIDE_BUTTON
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalAlignment = alignment) {
        CircleIconButton(
            ImageProvider(R.drawable.ic_skip_previous),
            contentDescription = context.getString(R.string.nowplaying_previous),
            onClick = actionRunCallback<PreviousAction>(),
            modifier = GlanceModifier.size(side),
            backgroundColor = null,
            contentColor = GlanceTheme.colors.onSurface,
        )
        Spacer(GlanceModifier.width(4.dp))
        PlayPauseButton(snapshot.playing, GlanceModifier.size(playSize))
        Spacer(GlanceModifier.width(4.dp))
        CircleIconButton(
            ImageProvider(R.drawable.ic_skip_next),
            contentDescription = context.getString(R.string.nowplaying_next),
            onClick = actionRunCallback<NextAction>(),
            modifier = GlanceModifier.size(side),
            backgroundColor = null,
            contentColor = GlanceTheme.colors.onSurface,
        )
    }
}

@Composable
internal fun PlayPauseButton(
    playing: Boolean,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    // 主役の操作は角の丸い四角で塗る（Expressive の再生ボタン）
    SquareIconButton(
        ImageProvider(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow),
        contentDescription = context.getString(if (playing) R.string.nowplaying_pause else R.string.widget_play),
        onClick = actionRunCallback<PlayPauseAction>(),
        modifier = modifier,
        backgroundColor = GlanceTheme.colors.primary,
        contentColor = GlanceTheme.colors.onPrimary,
    )
}

/** iPhone の小: 左上にジャケット（右下に評価）、下に曲名・アーティスト・ボタン。 */
@Composable
private fun SmallLayout(
    snapshot: NowPlayingSnapshot,
    artwork: Bitmap?,
    open: Action,
) {
    // 2×2 は端末によって 140dp ほどしかないので、余白を詰める
    Column(GlanceModifier.fillMaxSize().padding(12.dp)) {
        ArtworkWithLike(snapshot, artwork, 44.dp, open)
        Spacer(GlanceModifier.defaultWeight())
        TitleAndArtist(snapshot, GlanceModifier.fillMaxWidth().clickable(open), WidgetText.headline())
        Spacer(GlanceModifier.height(2.dp))
        // 2×2 の高さに収まるよう、ボタンは小さめにする
        Controls(snapshot, GlanceModifier.fillMaxWidth(), compact = true, alignment = Alignment.Start, playSize = SMALL_PLAY_BUTTON)
    }
}

@Composable
private fun WideLayout(
    snapshot: NowPlayingSnapshot,
    artwork: Bitmap?,
    open: Action,
) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(artwork, GlanceModifier.size(44.dp), open)
        Spacer(GlanceModifier.width(10.dp))
        TitleAndArtist(snapshot, GlanceModifier.defaultWeight().clickable(open))
        PlayPauseButton(snapshot.playing, GlanceModifier.size(COMPACT_BUTTON))
        CircleIconButton(
            ImageProvider(R.drawable.ic_skip_next),
            contentDescription = context.getString(R.string.nowplaying_next),
            onClick = actionRunCallback<NextAction>(),
            backgroundColor = null,
            contentColor = GlanceTheme.colors.onSurface,
        )
    }
}

/**
 * iPhone の中: 左にジャケット（右下に評価）・曲名・アーティスト・ボタン、右に「次に流れる曲」を3曲（上下いっぱいに等しく割り振る）。
 * 低くてジャケットの下に曲名を置けないときは、ジャケットの横に曲名を置く。
 */
@Composable
private fun MediumLayout(
    snapshot: NowPlayingSnapshot,
    artwork: Bitmap?,
    queueArtwork: Map<String, Bitmap>,
    open: Action,
) {
    val context = LocalContext.current
    // Responsive では宣言した段の大きさが来る
    val tall = LocalSize.current == NowPlayingWidget.MEDIUM_TALL
    Row(GlanceModifier.fillMaxSize()) {
        WidgetContainer(GlanceModifier.defaultWeight().fillMaxHeight(), secondary = false) {
        Column(GlanceModifier.fillMaxSize().padding(ContainerPadding)) {
            if (tall) {
                ArtworkWithLike(snapshot, artwork, 56.dp, open)
                Spacer(GlanceModifier.defaultWeight())
                TitleAndArtist(snapshot, GlanceModifier.fillMaxWidth().clickable(open))
            } else {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ArtworkWithLike(snapshot, artwork, 48.dp, open)
                    Spacer(GlanceModifier.width(10.dp))
                    TitleAndArtist(snapshot, GlanceModifier.defaultWeight().clickable(open), WidgetText.headline())
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            Controls(snapshot, GlanceModifier.fillMaxWidth(), compact = true, alignment = Alignment.Start)
        }
        }
        Spacer(GlanceModifier.width(ContainerGap))
        // 右側: 次に流れる曲（押すとその曲へ飛ぶ。ほかの端末で鳴っていればその端末で）
        WidgetContainer(GlanceModifier.defaultWeight().fillMaxHeight(), secondary = true) {
        Column(GlanceModifier.fillMaxSize().padding(ContainerPadding)) {
            Text(context.getString(R.string.widget_up_next), style = WidgetText.caption().copy(fontWeight = FontWeight.Medium), maxLines = 1)
            for (item in snapshot.upNext) {
                Row(
                    GlanceModifier.fillMaxWidth().defaultWeight().clickable(actionRunCallback<JumpAction>(actionParametersOf(itemIdKey to item.itemId))),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val thumb = item.artworkUrl?.let(queueArtwork::get)
                    Artwork(thumb, GlanceModifier.size(28.dp), null, corner = 5.dp, iconSize = 16.dp)
                    Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text(item.title, style = WidgetText.subtitle().copy(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium), maxLines = 1)
                        if (item.artist.isNotEmpty()) Text(item.artist, style = WidgetText.caption(), maxLines = 1)
                    }
                }
            }
            // 次の曲が少なくても、行の高さをそろえる
            repeat(NowPlayingSnapshot.UP_NEXT_COUNT - snapshot.upNext.size) { Spacer(GlanceModifier.defaultWeight()) }
        }
        }
    }
}

/** ボタンの操作は再生サービスのセッションへ送る（ほかの端末を映している間は、その端末の遠隔操作になる）。 */
internal suspend fun withController(
    context: Context,
    block: (androidx.media3.session.MediaController) -> Unit,
) {
    val container = (context.applicationContext as WaveApplication).container
    runCatching {
        val controller = container.player.controller()
        withContext(Dispatchers.Main.immediate) {
            // サービスが居なかった（プロセスが終わっていた）: つなぐと保存したキューが戻るので、少し待ってから操作する
            if (controller.mediaItemCount == 0) {
                withTimeoutOrNull(RESTORE_WAIT_MS) {
                    while (controller.mediaItemCount == 0) delay(100)
                }
            }
            if (controller.mediaItemCount == 0) {
                // 戻すキューも無い: 「再生していない」に描き直す
                NowPlayingWidget().updateAll(context)
                return@withContext
            }
            block(controller)
        }
    }.onFailure { Log.w(WaveApplication.LOG_TAG, "widget control failed type=${it::class.simpleName}") }
}

private const val RESTORE_WAIT_MS = 5_000L

/** 狭い・低いウィジェットの再生ボタンの大きさ（既定は 60dp）。 */
internal val COMPACT_BUTTON = 48.dp

/** 広さのあるウィジェットの再生ボタンの大きさ。 */
private val PLAY_BUTTON = 52.dp
private val SIDE_BUTTON = 44.dp
private val COMPACT_SIDE_BUTTON = 40.dp
private val LIKE_BUTTON = 22.dp
private val SMALL_PLAY_BUTTON = 36.dp
private val SMALL_SIDE_BUTTON = 32.dp


class JumpAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val itemId = parameters[itemIdKey] ?: return
        val container = (context.applicationContext as WaveApplication).container
        runCatching { container.player.jumpTo(itemId) }
            .onFailure { Log.w(WaveApplication.LOG_TAG, "widget jump failed type=${it::class.simpleName}") }
    }
}

/** ＋: 今の曲を高く評価する（曲が替わっていたら再生サービスが受け付けない）。 */
class LikeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val videoId = parameters[videoIdKey]?.takeIf { it.isNotEmpty() } ?: return
        val container = (context.applicationContext as WaveApplication).container
        runCatching { container.player.setLiked(videoId, true) }
            .onFailure { Log.w(WaveApplication.LOG_TAG, "widget like failed type=${it::class.simpleName}") }
    }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) = withController(context) { if (it.playWhenReady) it.pause() else it.playFromButton() }
}

class NextAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) = withController(context) { it.seekToNext() }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) = withController(context) { it.seekToPrevious() }
}
