package xyz.mooma0750.wave.widget

import android.content.Context
import android.content.Intent
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.size
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
 * split にすると地を塗らず、中に [WidgetContainer] を並べる（YouTube Music のウィジェットのように、
 * 角の丸い色違いの入れ物を細い隙間で重ねた見た目。隙間と角の外からは壁紙が見える）。
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

/**
 * 重ねる入れ物の1つ。四隅ともウィジェットと同じ丸み。今の曲の側は普通の地、もう一方（プレイリスト・次に流れる曲）は
 * 一段明るい surfaceVariant（YouTube Music と同じ組み合わせ）。
 */
@Composable
fun WidgetContainer(
    modifier: GlanceModifier,
    secondary: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .background(if (secondary) GlanceTheme.colors.surfaceVariant else GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius),
        content = { content() },
    )
}

/**
 * 地のあるアイコンのボタン（Glance の部品は形と大きさが決まっているので、自分で組む）。primary は主役（再生）で、
 * 角の丸い四角を primary で塗る。そうでなければ tonal（secondaryContainer）の丸いボタン。アイコンは高さの半分。
 */
@Composable
fun WidgetIconButton(
    icon: Int,
    contentDescription: String,
    onClick: Action,
    modifier: GlanceModifier,
    height: Dp,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .cornerRadius(if (primary) height * 0.3f else height / 2)
            .background(if (primary) GlanceTheme.colors.primary else GlanceTheme.colors.secondaryContainer)
            .let { if (enabled) it.clickable(onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            ImageProvider(icon),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(if (primary) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSecondaryContainer),
            modifier = GlanceModifier.size(height * 0.5f),
        )
    }
}

/** 並べたボタンの隙間。 */
val ButtonGap get() = 4.wdp

/** 重ねる入れ物の隙間（YouTube Music はほとんど空けない）。 */
val ContainerGap get() = 2.wdp

/** つないだ入れ物の中の余白（2つに分けると幅が狭くなるので、1つのときより詰める）。 */
val ContainerPadding get() = 12.wdp

object WidgetText {
    @Composable
    fun title() = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.wsp, fontWeight = FontWeight.Medium)

    @Composable
    fun subtitle() = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.wsp)

    /** 小さいウィジェットの曲名。 */
    @Composable
    fun headline() = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.wsp, fontWeight = FontWeight.Bold) // m3e-allow: Glance の文字にはテーマの文字の型が無い

    /** 見出し・次に流れる曲のアーティスト。 */
    @Composable
    fun caption() = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.wsp)

    /** ほかの端末で鳴っているときの端末名（iPhone のアクセントの色に当たる）。 */
    @Composable
    fun device(size: androidx.compose.ui.unit.TextUnit = 13.wsp) = TextStyle(color = GlanceTheme.colors.primary, fontSize = size)
}

/** ウィジェットの中身の余白（iPhone と同じく四方をそろえる）。 */
val WidgetPadding get() = 16.wdp

/** 画像の角（Expressive の中くらいの角）。 */
val ArtworkCorner get() = 16.wdp

/** アプリを開く Intent（開いていれば今の画面の上で onNewIntent に届く）。 */
fun openAppIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
