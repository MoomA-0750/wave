package xyz.mooma0750.wave.widget

import android.content.Context
import android.content.Intent
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import xyz.mooma0750.wave.MainActivity

/**
 * ウィジェットの見た目の共通部分。色はアプリと同じ動的な色（壁紙から取る）、角は端末のウィジェットの丸みに合わせる。
 * 中の部品は Material 3 Expressive に寄せて、主役の操作（再生）を大きく塗り、ほかはトーンを落とす。
 */
@Composable
fun WaveGlanceTheme(
    context: Context,
    content: @Composable () -> Unit,
) {
    GlanceTheme(colors = ColorProviders(light = dynamicLightColorScheme(context), dark = dynamicDarkColorScheme(context)), content = content)
}

/**
 * ウィジェットの下地（端末の角の丸みで切る）。地は Material の単色。
 * split にすると地を塗らず、中に置いた [WidgetContainer] どうしの隙間から壁紙が見える（YouTube Music のウィジェットのように、
 * 色違いの入れ物をつないだ見た目。外側の角は端末の丸みで切られ、つなぎ目の角だけ小さくなる）。
 */
@Composable
fun WidgetSurface(
    modifier: GlanceModifier = GlanceModifier,
    split: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .appWidgetBackground()
            .let { if (split) it else it.background(GlanceTheme.colors.widgetBackground) }
            .cornerRadius(android.R.dimen.system_app_widget_background_radius),
        content = { content() },
    )
}

/** つないだ入れ物の1つ。accent は主役の側（再生中の曲）で、色を変える。 */
@Composable
fun WidgetContainer(
    modifier: GlanceModifier,
    accent: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .background(if (accent) GlanceTheme.colors.secondaryContainer else GlanceTheme.colors.widgetBackground)
            .cornerRadius(ContainerInnerCorner),
        content = { content() },
    )
}

/** つないだ入れ物の隙間と、つなぎ目の角。 */
val ContainerGap = 4.dp
val ContainerInnerCorner = 6.dp

/** つないだ入れ物の中の余白（2つに分けると幅が狭くなるので、1つのときより詰める）。 */
val ContainerPadding = 12.dp

object WidgetText {
    @Composable
    fun title() = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)

    @Composable
    fun subtitle() = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp)

    /** 小さいウィジェットの曲名。 */
    @Composable
    fun headline() = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold) // m3e-allow: Glance の文字にはテーマの文字の型が無い

    /** 見出し・次に流れる曲のアーティスト。 */
    @Composable
    fun caption() = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp)

    /** ほかの端末で鳴っているときの端末名（iPhone のアクセントの色に当たる）。 */
    @Composable
    fun device(size: androidx.compose.ui.unit.TextUnit = 13.sp) = TextStyle(color = GlanceTheme.colors.primary, fontSize = size)
}

/** ウィジェットの中身の余白（iPhone と同じく四方をそろえる）。 */
val WidgetPadding = 16.dp

/** 画像の角（Expressive の中くらいの角）。 */
val ArtworkCorner = 16.dp

/** アプリを開く Intent（開いていれば今の画面の上で onNewIntent に届く）。 */
fun openAppIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
