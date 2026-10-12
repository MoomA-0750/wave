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

/** ウィジェットの下地（端末の角の丸みで切る）。 */
@Composable
fun WidgetSurface(
    modifier: GlanceModifier = GlanceModifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius),
        content = { content() },
    )
}

object WidgetText {
    @Composable
    fun title() = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)

    @Composable
    fun subtitle() = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp)
}

/** 画像の角（Expressive の中くらいの角）。 */
val ArtworkCorner = 16.dp

/** アプリを開く Intent（開いていれば今の画面の上で onNewIntent に届く）。 */
fun openAppIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
