package xyz.mooma0750.wave.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import xyz.mooma0750.wave.R

/** 端末の壁紙から作った色（Material You）で、ダークモードにも従う。minSdk 31 なので常に使える。 */
@Composable
fun WaveTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colorScheme) {
        // 画面の下地と文字の色をテーマに合わせる（Scaffold の外に出る文字も読めるように）
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

/** アイコンだけのボタン。label は読み上げに使う。active なら強調の色にする（リピート・歌詞などの切り替え）。 */
@Composable
fun IconAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = label, tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
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
    IconAction(R.drawable.ic_arrow_back, "戻る", onBack)
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
