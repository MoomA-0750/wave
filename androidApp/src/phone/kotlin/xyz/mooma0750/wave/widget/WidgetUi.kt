package xyz.mooma0750.wave.widget

import android.content.Context
import android.content.Intent
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
import androidx.glance.LocalContext
import androidx.glance.unit.ColorProvider
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import xyz.mooma0750.wave.MainActivity
import xyz.mooma0750.wave.R

/**
 * ウィジェットの見た目の共通部分。色は Glance の既定の動的な色（端末の system_accent・neutral の段を資源のまま渡すので、
 * 壁紙を替えると描き直さなくても OS の側で色が替わる。地は Google のウィジェット（YouTube Music など）と同じ system_accent2_50 / 800）。
 * 角は端末のウィジェットの丸みに合わせる。中の部品は Material 3 Expressive に寄せて、主役の操作（再生）を大きく塗り、ほかはトーンを落とす。
 */
@Composable
fun WaveGlanceTheme(
    @Suppress("UNUSED_PARAMETER") context: Context,
    content: @Composable () -> Unit,
) {
    GlanceTheme(content = content)
}

/**
 * ウィジェットの下地（端末の角の丸みで切る）。地は Material の単色。
 * split にすると地を塗らず、中に [WidgetContainer] を隙間なく並べる（YouTube Music のウィジェットのように、色違いの入れ物を
 * つないだ見た目）。外側の角はこの下地の丸みで切るので、入れ物どうしが接する角だけが入れ物の控えめな丸みになり、そこから壁紙が少し見える。
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
            .cornerRadius(widgetCorner()),
        content = { content() },
    )
}

/**
 * つなぐ入れ物の1つ。角の丸みはウィジェットの丸みより控えめ（[containerCorner]。外側は [WidgetSurface] が切る）。今の曲の側は
 * ほかのウィジェットと同じ地（widgetBackground）、もう一方（プレイリスト・次に流れる曲）は YouTube Music と同じく一段濃く色付ける
 * （[R.color.widget_second_container]。ライトは system_accent2_100、ダークは system_accent2_600）。
 */
@Composable
fun WidgetContainer(
    modifier: GlanceModifier,
    secondary: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .background(if (secondary) ColorProvider(R.color.widget_second_container) else playerContainerColor())
            .cornerRadius(containerCorner()),
        content = { content() },
    )
}

/** 今の曲の側の入れ物の色（ほかのウィジェットと同じ地）。 */
@Composable
fun playerContainerColor(): ColorProvider = GlanceTheme.colors.widgetBackground

/** ウィジェットの角の丸み（端末の丸みに大きさの倍率を掛ける。文字やボタンと一緒に大きくする）。 */
@Composable
fun widgetCorner(): Dp {
    val resources = LocalContext.current.resources
    val system = resources.getDimension(android.R.dimen.system_app_widget_background_radius) / resources.displayMetrics.density
    return (system * LocalWidgetScale.current).dp
}

/** つないだ入れ物どうしが接する角の丸み（YouTube Music・YouTube と同じく、外側より控えめにする）。 */
@Composable
fun containerCorner(): Dp = widgetCorner() * 0.6f

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
val ButtonGap: Dp
    @Composable get() = 4.wdp

/** つないだ入れ物の中の余白（2つに分けると幅が狭くなるので、1つのときより詰める）。 */
val ContainerPadding: Dp
    @Composable get() = 12.wdp

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
val WidgetPadding: Dp
    @Composable get() = 16.wdp

/** 画像の角（Expressive の中くらいの角）。 */
val ArtworkCorner: Dp
    @Composable get() = 16.wdp

/** アプリを開く Intent（開いていれば今の画面の上で onNewIntent に届く）。 */
fun openAppIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
