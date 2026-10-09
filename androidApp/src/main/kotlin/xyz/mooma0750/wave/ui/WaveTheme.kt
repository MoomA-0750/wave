package xyz.mooma0750.wave.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import xyz.mooma0750.wave.R
import androidx.compose.ui.res.stringResource

/** 端末の壁紙から作った色（Material You）で、ダークモードにも従う。minSdk 31 なので常に使える。動きは Expressive のばね。 */
@Composable
fun WaveTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialExpressiveTheme(colorScheme = colorScheme, motionScheme = MotionScheme.expressive()) {
        // 画面の下地と文字の色をテーマに合わせる（Scaffold の外に出る文字も読めるように）
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

/** 2行目（アーティスト名など）の色。 */
val Subdued: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

/**
 * ジャケットの色（Spotify の再生画面・プレイリストの上のグラデーション）。暗めに落とした色を返す。
 * 読めない間・画像の無いときは [fallback]。
 */
@Composable
fun rememberArtworkColor(
    url: String?,
    fallback: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
): State<Color> {
    val context = LocalContext.current
    var target by remember(url) { mutableStateOf(ArtworkColors.cached(url) ?: fallback) }
    LaunchedEffect(url) {
        if (url == null || ArtworkColors.cached(url) != null) return@LaunchedEffect
        val request =
            ImageRequest
                .Builder(context)
                .data(url)
                .size(48)
                .allowHardware(false)
                .build()
        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        target = ArtworkColors.of(url, bitmap)
    }
    return animateColorAsState(target, MaterialTheme.motionScheme.slowEffectsSpec(), label = "artwork")
}

object ArtworkColors {
    private val cache = object : LinkedHashMap<String, Color>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?) = size > 64
    }

    @Synchronized
    fun cached(url: String?): Color? = url?.let { cache[it] }

    @Synchronized
    fun of(
        url: String,
        bitmap: Bitmap,
    ): Color = cache.getOrPut(url) { Color(dominant(bitmap)) }

    /** 鮮やかな画素を重く数えた平均の色を、明るさを抑えて返す（白い文字が読めるように）。 */
    fun dominant(bitmap: Bitmap): Int {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var weight = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val pixel = bitmap.getPixel(x, y)
                android.graphics.Color.colorToHSV(pixel, hsv)
                // 真っ黒・真っ白の画素は色を決めない
                val w = 0.05 + hsv[1] * hsv[2] * (1 - kotlin.math.abs(hsv[2] - 0.6))
                r += android.graphics.Color.red(pixel) * w
                g += android.graphics.Color.green(pixel) * w
                b += android.graphics.Color.blue(pixel) * w
                weight += w
            }
        }
        if (weight == 0.0) return android.graphics.Color.DKGRAY
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt()), hsv)
        hsv[1] = (hsv[1] * 1.2f).coerceAtMost(0.85f)
        hsv[2] = hsv[2].coerceIn(0.25f, 0.55f)
        return android.graphics.Color.HSVToColor(hsv)
    }
}

/** アイコンだけのボタン。label は読み上げに使う。active なら強調の色にする（シャッフル・リピートなど）。 */
@Composable
fun IconAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color = Color.Unspecified,
    size: Dp = 24.dp,
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(size),
            tint =
                when {
                    active -> MaterialTheme.colorScheme.primary
                    tint != Color.Unspecified -> tint
                    else -> LocalContentColor.current
                },
        )
    }
}

/** 丸い大きな再生ボタン（Spotify の緑のボタンの位置に置く。色はテーマの primary）。 */
@Composable
fun PlayFab(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    enabled: Boolean = true,
) {
    FilledIconButton(onClick = onClick, modifier = modifier.size(size), enabled = enabled) {
        Icon(
            painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow),
            contentDescription = if (playing) stringResource(R.string.ui_pause) else stringResource(R.string.ui_play),
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

enum class ButtonStyle { Filled, Outlined, Text }

/** アイコンと文字のボタン。 */
@Composable
fun IconTextButton(
    @DrawableRes icon: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ButtonStyle = ButtonStyle.Text,
) {
    val content: @Composable () -> Unit = {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(text)
    }
    when (style) {
        ButtonStyle.Filled -> Button(onClick, modifier, enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { content() }
        ButtonStyle.Outlined -> OutlinedButton(onClick, modifier, enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { content() }
        ButtonStyle.Text -> TextButton(onClick, modifier, enabled, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) { content() }
    }
}

/** 上の帯の「戻る」。 */
@Composable
fun BackButton(onBack: () -> Unit) {
    IconAction(R.drawable.ic_arrow_back, stringResource(R.string.ui_back), onBack)
}

/** メニューの項目の頭に付けるアイコン。 */
@Composable
fun MenuIcon(
    @DrawableRes icon: Int,
) {
    Icon(painterResource(icon), contentDescription = null)
}

/** 大きな再生ボタンのアイコンの大きさ。 */
val LargeIconSize = 36.dp
