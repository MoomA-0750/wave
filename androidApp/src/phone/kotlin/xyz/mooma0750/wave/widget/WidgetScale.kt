package xyz.mooma0750.wave.widget

import android.content.Context
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ウィジェットの中身の大きさの倍率（設定の「ウィジェットの大きさ」。既定は 100%）。
 * ランチャーや表示サイズの設定によっては、同じ dp でもウィジェットの中の文字・ボタンが小さく見えるので、本人が合わせられるようにする。
 * 文字・ボタン・絵・余白と、大きさで並べ方を替える境目にまとめて掛ける（境目にも掛けるので、大きくしても狭いウィジェットからはみ出さない）。
 * 受け口・描画・設定画面のどこからでも同じ値を同期で読めるよう、DataStore ではなく SharedPreferences に置く。
 */
object WidgetScale {
    private const val PREFS = "widget_scale"
    private const val KEY_PERCENT = "percent"
    const val DEFAULT_PERCENT = 100
    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 160
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

/** 倍率を掛けた dp（ウィジェットの中だけで使う）。 */
val Int.wdp: Dp get() = (this * WidgetScale.factor).dp

/** 倍率を掛けた sp。 */
val Int.wsp: TextUnit get() = (this * WidgetScale.factor).sp

/** 大きさで並べ方を替える境目（倍率を掛ける）。 */
fun widgetSize(
    width: Int,
    height: Int,
): DpSize = DpSize(width.wdp, height.wdp)
