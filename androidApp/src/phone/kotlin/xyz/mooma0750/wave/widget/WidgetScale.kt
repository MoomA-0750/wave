package xyz.mooma0750.wave.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.glance.LocalSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ウィジェットの中身の大きさの倍率（設定の「ウィジェットの大きさ」。既定は 100%）。
 * ランチャーや表示サイズの設定によっては、同じ dp でもウィジェットの中の文字・ボタンが小さく見えるので、本人が合わせられるようにする。
 * 文字・ボタン・絵・余白と、大きさで並べ方を替える境目にまとめて掛ける（境目にも掛けるので、大きくしても狭いウィジェットからはみ出さない）。
 * 効く倍率は置いた大きさごとに [WidgetScaled] が決める（一番小さい並べ方が収まるところまで）。
 * 受け口・描画・設定画面のどこからでも同じ値を同期で読めるよう、DataStore ではなく SharedPreferences に置く。
 */
object WidgetScale {
    private const val PREFS = "widget_scale"
    private const val KEY_PERCENT = "percent"
    const val DEFAULT_PERCENT = 100
    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 300
    const val STEP_PERCENT = 10

    @Volatile
    var factor: Float = 1f
        private set

    /** 置いた倍率を読み直す（ウィジェットを描く前に呼ぶ）。 */
    fun load(context: Context) {
        factor = percent(context) / 100f
    }

    fun percent(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PERCENT, DEFAULT_PERCENT)
            .coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** 倍率を変えて、置いてあるウィジェットを描き直す。 */
    suspend fun set(
        context: Context,
        percent: Int,
    ) {
        val value = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PERCENT, value).apply()
        factor = value / 100f
        WidgetHooks.updateAllWidgets(context.applicationContext)
    }
}

/** 描いている大きさで効く倍率（[WidgetScaled] が決める）。 */
val LocalWidgetScale = staticCompositionLocalOf { 1f }

/**
 * 本人の決めた倍率を、置いた大きさに一番小さい並べ方（100% で minWidth×minHeight）が収まる上限までに抑えて、中身に渡す。
 * 枠の小さい端末で倍率を上げすぎても、はみ出さずに収まる大きさまでにとどめる。
 */
@Composable
fun WidgetScaled(
    minWidth: Int,
    minHeight: Int,
    content: @Composable () -> Unit,
) {
    val size = LocalSize.current
    val fit = minOf(size.width.value / minWidth, size.height.value / minHeight)
    val scale = minOf(WidgetScale.factor, fit).coerceAtLeast(minOf(1f, WidgetScale.factor))
    CompositionLocalProvider(LocalWidgetScale provides scale, content = content)
}

/** 倍率を掛けた dp（ウィジェットの中だけで使う）。 */
val Int.wdp: Dp
    @Composable @ReadOnlyComposable get() = (this * LocalWidgetScale.current).dp

/** 倍率を掛けた sp。 */
val Int.wsp: TextUnit
    @Composable @ReadOnlyComposable get() = (this * LocalWidgetScale.current).sp

/** 大きさで並べ方を替える境目（倍率を掛ける）。 */
@Composable
@ReadOnlyComposable
fun widgetSize(
    width: Int,
    height: Int,
): DpSize = DpSize(width.wdp, height.wdp)
