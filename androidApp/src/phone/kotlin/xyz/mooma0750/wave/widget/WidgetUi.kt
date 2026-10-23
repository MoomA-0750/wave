package xyz.mooma0750.wave.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
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
    /** ぼかした絵の地の上では、ライトモードでも暗い地として描く（文字を白にする。iPhone と同じ）。 */
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkScheme = dynamicDarkColorScheme(context)
    GlanceTheme(colors = ColorProviders(light = if (dark) darkScheme else dynamicLightColorScheme(context), dark = darkScheme), content = content)
}

/** ウィジェットの下地（端末の角の丸みで切る）。backdrop を渡すと、それを地に敷く（[WidgetArtwork.backdrop]）。 */
@Composable
fun WidgetSurface(
    modifier: GlanceModifier = GlanceModifier,
    backdrop: Bitmap? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius),
        content = {
            if (backdrop != null) Image(ImageProvider(backdrop), contentDescription = null, contentScale = ContentScale.Crop, modifier = GlanceModifier.fillMaxSize())
            content()
        },
    )
}

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
