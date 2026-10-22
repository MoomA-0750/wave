package xyz.mooma0750.wave.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.glance.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.playback.NowPlayingSnapshot
import xyz.mooma0750.wave.playback.playFromButton

/**
 * 再生中の曲のウィジェット。ジャケット・曲名・アーティストと、前へ・再生/一時停止・次へ。押すとアプリの再生画面を開く。
 * ほかの端末の再生を映している間はその端末の曲を出し、ボタンはその端末の遠隔操作になる（再生サービスのセッションの再生器が替わるため）。
 * 大きさで出し方を変える（小さい横長はボタンだけ、小さい正方形はジャケットと再生ボタン、広ければ全部）。
 */
class NowPlayingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(CONTROLS, SQUARE, WIDE, WIDE_TALL, LARGE))

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val container = (context.applicationContext as WaveApplication).container
        // 最初の描画で画像が抜けないよう、今の曲の画像は先に取っておく
        WidgetArtwork.load(container.mediaHttpClient, WidgetHooks.nowPlaying.value?.artworkUrl)
        provideContent {
            // 描いている間に曲が変わったら、そのまま描き直す
            val snapshot by WidgetHooks.nowPlaying.collectAsState()
            val artwork = rememberWidgetArtwork(container.mediaHttpClient, snapshot?.artworkUrl)
            WaveGlanceTheme(context) {
                NowPlayingContent(snapshot, artwork)
            }
        }
    }

    companion object {
        /** 2×1 ほど: ボタンだけ。 */
        val CONTROLS = DpSize(150.dp, 48.dp)

        /** 2×2 ほど: ジャケットと再生ボタン。 */
        val SQUARE = DpSize(110.dp, 110.dp)

        /** 4×1: ジャケット・曲名・再生・次へを1列に（ボタンだけの大きさより広いとき）。 */
        val WIDE = DpSize(250.dp, 48.dp)

        /** 4×2: ジャケットの横に曲名とボタン。 */
        val WIDE_TALL = DpSize(250.dp, 110.dp)

        /** 3×3 以上: 大きなジャケットの下に曲名とボタン。 */
        val LARGE = DpSize(200.dp, 250.dp)
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NowPlayingWidget()
}

@Composable
private fun NowPlayingContent(
    snapshot: NowPlayingSnapshot?,
    artwork: Bitmap?,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val open = actionStartActivity(openAppIntent(context).putExtra(WidgetHooks.EXTRA_OPEN_NOW_PLAYING, true))
    WidgetSurface(GlanceModifier.clickable(open)) {
        // Responsive では、置いた大きさに収まる宣言した大きさのうち最も大きいものが来る
        when {
            snapshot == null -> EmptyState(open)
            size == NowPlayingWidget.CONTROLS -> Controls(snapshot, GlanceModifier.fillMaxSize().padding(horizontal = 8.dp), compact = true)
            size == NowPlayingWidget.SQUARE -> SquareLayout(snapshot, artwork, open)
            size == NowPlayingWidget.WIDE -> WideLayout(snapshot, artwork, open)
            size == NowPlayingWidget.WIDE_TALL -> WideTallLayout(snapshot, artwork, open)
            else -> LargeLayout(snapshot, artwork, open)
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
private fun Artwork(
    artwork: Bitmap?,
    modifier: GlanceModifier,
    open: Action,
    /** 再生ボタンを重ねるとき、仮の絵を左上に寄せる（ボタンで隠れないように）。 */
    iconAlignment: Alignment = Alignment.Center,
) {
    val base = modifier.cornerRadius(ArtworkCorner).clickable(open)
    if (artwork != null) {
        Image(ImageProvider(artwork), contentDescription = null, contentScale = ContentScale.Crop, modifier = base)
    } else {
        Box(base.background(GlanceTheme.colors.secondaryContainer).padding(12.dp), contentAlignment = iconAlignment) {
            Image(
                ImageProvider(R.drawable.ic_music_note),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                modifier = GlanceModifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun TitleAndArtist(
    snapshot: NowPlayingSnapshot,
    modifier: GlanceModifier = GlanceModifier,
) {
    Column(modifier) {
        Text(snapshot.title, style = WidgetText.title(), maxLines = 1)
        if (snapshot.artist.isNotEmpty()) Text(snapshot.artist, style = WidgetText.subtitle(), maxLines = 1)
    }
}

/** 前へ・再生（主役なので大きく塗る）・次へ。 */
@Composable
private fun Controls(
    snapshot: NowPlayingSnapshot,
    modifier: GlanceModifier = GlanceModifier,
    /** 低いウィジェット: 再生ボタンを小さくする（はみ出さないように）。 */
    compact: Boolean = false,
) {
    val context = LocalContext.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        CircleIconButton(
            ImageProvider(R.drawable.ic_skip_previous),
            contentDescription = context.getString(R.string.nowplaying_previous),
            onClick = actionRunCallback<PreviousAction>(),
            backgroundColor = null,
            contentColor = GlanceTheme.colors.onSurfaceVariant,
        )
        Spacer(GlanceModifier.width(8.dp))
        PlayPauseButton(snapshot.playing, if (compact) GlanceModifier.size(COMPACT_BUTTON) else GlanceModifier)
        Spacer(GlanceModifier.width(8.dp))
        CircleIconButton(
            ImageProvider(R.drawable.ic_skip_next),
            contentDescription = context.getString(R.string.nowplaying_next),
            onClick = actionRunCallback<NextAction>(),
            backgroundColor = null,
            contentColor = GlanceTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlayPauseButton(
    playing: Boolean,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    // 主役の操作は角の丸い四角で大きく（Expressive の再生ボタン）
    SquareIconButton(
        ImageProvider(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow),
        contentDescription = context.getString(if (playing) R.string.nowplaying_pause else R.string.widget_play),
        onClick = actionRunCallback<PlayPauseAction>(),
        modifier = modifier,
        backgroundColor = GlanceTheme.colors.primary,
        contentColor = GlanceTheme.colors.onPrimary,
    )
}

@Composable
private fun SquareLayout(
    snapshot: NowPlayingSnapshot,
    artwork: Bitmap?,
    open: Action,
) {
    Box(GlanceModifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.BottomEnd) {
        Artwork(artwork, GlanceModifier.fillMaxSize(), open, Alignment.TopStart)
        Box(GlanceModifier.padding(6.dp)) { PlayPauseButton(snapshot.playing, GlanceModifier.size(COMPACT_BUTTON)) }
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
            contentColor = GlanceTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun WideTallLayout(
    snapshot: NowPlayingSnapshot,
    artwork: Bitmap?,
    open: Action,
) {
    Row(GlanceModifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val side = (LocalSize.current.height - 20.dp).coerceIn(56.dp, 140.dp)
        Artwork(artwork, GlanceModifier.size(side), open)
        Spacer(GlanceModifier.width(12.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            TitleAndArtist(snapshot, GlanceModifier.fillMaxWidth().clickable(open))
            Spacer(GlanceModifier.height(4.dp))
            Controls(snapshot, GlanceModifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LargeLayout(
    snapshot: NowPlayingSnapshot,
    artwork: Bitmap?,
    open: Action,
) {
    Column(GlanceModifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(artwork, GlanceModifier.fillMaxWidth().defaultWeight(), open)
        Spacer(GlanceModifier.height(8.dp))
        TitleAndArtist(snapshot, GlanceModifier.fillMaxWidth().clickable(open))
        Spacer(GlanceModifier.height(4.dp))
        Controls(snapshot, GlanceModifier.fillMaxWidth())
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
