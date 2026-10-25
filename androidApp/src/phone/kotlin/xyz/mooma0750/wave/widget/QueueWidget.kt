package xyz.mooma0750.wave.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.playback.NowPlayingSnapshot

/**
 * キューのウィジェット（iPhone の「キュー」と同じ並び）。見出し「次に流れる曲」（ほかの端末で鳴っていれば右に端末名）の下に、
 * 次に流れる曲をジャケット・曲名・アーティストで並べ、押すとその曲へ飛ぶ（ほかの端末を映している間はその端末で）。
 * 小は3曲、中（横長）は2列に3曲ずつ、大（縦に高い）は8曲（[queuePlan]）。ほかのところを押すとアプリの再生画面を開く。
 * 中身は再生中のウィジェットと同じ（[WidgetHooks.nowPlaying]）。
 */
class QueueWidget : GlanceAppWidget() {
    // 並べる数を置いた実際の大きさから決める
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        WidgetScale.load(context)
        if (WidgetPreview.forced) return providePreviewContent(context)
        val container = (context.applicationContext as WaveApplication).container
        // 最初の描画でジャケットが抜けないよう、先に取っておく
        WidgetHooks.nowPlaying.value?.upNext?.forEach { WidgetArtwork.load(container.mediaHttpClient, it.artworkUrl) }
        provideContent {
            val snapshot by WidgetHooks.nowPlaying.collectAsState()
            val artwork = rememberWidgetArtworks(container.mediaHttpClient, snapshot?.upNext.orEmpty().mapNotNull { it.artworkUrl })
            // 倍率は、小（3曲）が収まるところまで
            WidgetScaled(minWidth = 110, minHeight = 110) {
                WaveGlanceTheme(context) {
                    QueueContent(snapshot, artwork)
                }
            }
        }
    }

    /** ランチャーのウィジェット追加画面の見本（Android 15 以降。[WidgetPreview]）。 */
    override suspend fun providePreview(
        context: Context,
        widgetCategory: Int,
    ) = providePreviewContent(context)

    private suspend fun providePreviewContent(context: Context): Nothing =
        provideContent {
            WidgetScaled(minWidth = 110, minHeight = 110) {
                WaveGlanceTheme(context) {
                    QueueContent(WidgetPreview.nowPlaying(context), emptyMap())
                }
            }
        }

    companion object {
        /** これより広ければ2列（iPhone の中）。 */
        const val WIDE_WIDTH = 250

        /** これより高ければ1列に8曲（iPhone の大）。 */
        const val TALL_HEIGHT = 260
    }
}

class QueueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = QueueWidget()

    override fun onReceive(
        context: Context,
        intent: android.content.Intent,
    ) {
        // 並べ方の境目に倍率を掛けるので、描き始める前に読んでおく
        WidgetScale.load(context)
        super.onReceive(context, intent)
    }
}

/** 列の数と、1列に並べる曲の数。 */
internal data class QueuePlan(
    val columns: Int,
    val rows: Int,
)

/** 置いた大きさ（倍率を掛ける前の dp に直したもの）から並べ方を決める（iPhone と同じ。小3曲・中2列×3曲・大8曲）。 */
internal fun queuePlan(
    width: Float,
    height: Float,
): QueuePlan =
    when {
        height >= QueueWidget.TALL_HEIGHT -> QueuePlan(columns = 1, rows = 8)
        width >= QueueWidget.WIDE_WIDTH -> QueuePlan(columns = 2, rows = 3)
        else -> QueuePlan(columns = 1, rows = 3)
    }

@Composable
private fun QueueContent(
    snapshot: NowPlayingSnapshot?,
    artwork: Map<String, Bitmap>,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val scale = LocalWidgetScale.current
    val plan = queuePlan(size.width.value / scale, size.height.value / scale)
    val open = actionStartActivity(openAppIntent(context).putExtra(WidgetHooks.EXTRA_OPEN_NOW_PLAYING, true))
    // 2×2 は端末によって高さが無いので、余白を詰める
    val padding = if (plan.columns == 1 && plan.rows == 3) 12.wdp else WidgetPadding
    val items = snapshot?.upNext.orEmpty()
    WidgetSurface(GlanceModifier.clickable(open)) {
        Column(GlanceModifier.fillMaxSize().padding(padding)) {
            Header(snapshot)
            if (items.isEmpty()) {
                Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                    Text(
                        context.getString(if (snapshot == null) R.string.widget_nothing_playing else R.string.widget_nothing_up_next),
                        style = WidgetText.caption().copy(textAlign = TextAlign.Center),
                        maxLines = 2,
                    )
                }
            } else {
                Spacer(GlanceModifier.height(HEADER_GAP))
                // 1行の高さ。曲名とアーティストの2行が入らなければ曲名だけにする
                val rowHeight = (size.height - padding * 2 - HEADER_HEIGHT - HEADER_GAP) / plan.rows
                val twoLines = rowHeight >= (TWO_LINE_BLOCK * context.resources.configuration.fontScale * scale).dp
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    for (column in 0 until plan.columns) {
                        if (column > 0) Spacer(GlanceModifier.width(COLUMN_GAP))
                        // 中は左の列を上から、続きを右の列に
                        val part = items.drop(column * plan.rows).take(plan.rows)
                        Column(GlanceModifier.defaultWeight()) {
                            for (item in part) QueueRow(item, item.artworkUrl?.let(artwork::get), rowHeight, twoLines)
                            // 曲が足りなくても、行の高さをそろえる
                            repeat(plan.rows - part.size) { Spacer(GlanceModifier.height(rowHeight)) }
                        }
                    }
                }
            }
        }
    }
}

/** 見出し「次に流れる曲」と、ほかの端末で鳴っていればその端末名。 */
@Composable
private fun Header(snapshot: NowPlayingSnapshot?) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxWidth().height(HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Text(
            context.getString(R.string.widget_up_next),
            style = WidgetText.caption().copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        val device = snapshot?.device
        if (device != null) {
            Image(
                ImageProvider(R.drawable.ic_devices),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(12.wdp),
            )
            Spacer(GlanceModifier.width(4.wdp))
            Text(device, style = WidgetText.device(11.wsp), maxLines = 1)
        }
    }
}

/** 次に流れる曲の1行（ジャケット・曲名・アーティスト）。押すとその曲へ飛ぶ。 */
@Composable
private fun QueueRow(
    item: NowPlayingSnapshot.UpNext,
    thumb: Bitmap?,
    height: Dp,
    twoLines: Boolean,
) {
    Row(
        GlanceModifier.fillMaxWidth().height(height).clickable(actionRunCallback<JumpAction>(actionParametersOf(itemIdKey to item.itemId))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val side = (height - 4.wdp).coerceIn(14.wdp, 32.wdp)
        Artwork(thumb, GlanceModifier.size(side), null, corner = 5.wdp, iconSize = side / 2)
        Spacer(GlanceModifier.width(8.wdp))
        Column(GlanceModifier.defaultWeight()) {
            Text(item.title, style = WidgetText.subtitle().copy(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium), maxLines = 1)
            if (twoLines && item.artist.isNotEmpty()) Text(item.artist, style = WidgetText.caption(), maxLines = 1)
        }
    }
}

private val HEADER_HEIGHT: Dp
    @Composable get() = 16.wdp
private val HEADER_GAP: Dp
    @Composable get() = 4.wdp
private val COLUMN_GAP: Dp
    @Composable get() = 12.wdp

/** 曲名（13sp）とアーティスト（11sp）の2行の高さの見積もり（dp。倍率を掛ける前）。 */
private const val TWO_LINE_BLOCK = 32f
