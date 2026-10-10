package xyz.mooma0750.wave.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

/** 時計の見た目（Wear の Material 3。文字盤の色に合わせた動的な色が使えればそれを使う）。 */
@Composable
fun WaveWearTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(colorScheme = dynamicColorScheme(context) ?: ColorScheme(), content = content) // m3e-allow: Wear の Material 3 のテーマ（Wear では Expressive がこの名前）
}
