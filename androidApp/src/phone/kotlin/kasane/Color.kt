// 書き出したもの。直すのは kasane の tokens.css と themes/personal.css（scripts/build.mjs）
// kasane 1.1.0  source b09fbea03c805e10  seed #2B6CE0 #2B6CE0
package kasane

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** 動的色が無いとき（Android 11 以前）の控えの色。kasane の personal を種の色から導いたもの */
fun kasaneLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF2A5FBE),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE8F6),
    onPrimaryContainer = Color(0xFF154AA7),
    error = Color(0xFFC52940),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6E8EC),
    onErrorContainer = Color(0xFFC52940),
    background = Color(0xFFEFF5FF),
    onBackground = Color(0xFF212C3D),
    surface = Color(0xFFFCFEFF),
    onSurface = Color(0xFF212C3D),
    surfaceVariant = Color(0xFFE4EDFC),
    onSurfaceVariant = Color(0xFF4C5B76),
    outline = Color(0xFF7584A0),
    outlineVariant = Color(0xFFD4DDEB),
)

fun kasaneDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF6B9DF5),
    onPrimary = Color(0xFF0B1628),
    primaryContainer = Color(0xFF263144),
    onPrimaryContainer = Color(0xFF80B4FF),
    error = Color(0xFFFF757C),
    onError = Color(0xFF2B0B09),
    errorContainer = Color(0xFF322830),
    onErrorContainer = Color(0xFFFF757C),
    background = Color(0xFF13171F),
    onBackground = Color(0xFFE2E8F3),
    surface = Color(0xFF1B1F27),
    onSurface = Color(0xFFE2E8F3),
    surfaceVariant = Color(0xFF202731),
    onSurfaceVariant = Color(0xFFA7B2C4),
    outline = Color(0xFF5D6570),
    outlineVariant = Color(0xFF282D36),
)
