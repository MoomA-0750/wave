// 書き出したもの。直すのは kasane の tokens.css と themes/personal.css（scripts/build.mjs）
// kasane 1.1.0  source 3d5c1044cf587f0b
package kasane

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** kasane の角丸（dp）。ピルは CircleShape か RoundedCornerShape(50) を使う */
object KasaneRadius {
    val sm: Dp = 6.dp
    val base: Dp = 10.dp
    val lg: Dp = 16.dp
    val min: Dp = 2.dp
    val control: Dp = 10.dp
    val field: Dp = 10.dp
    val card: Dp = 26.dp
    val row: Dp = 26.dp
}

/** 同心の角丸: 中の角丸 = max(radius-min, 外 − 余白)。外がピルなら使わない */
fun kasaneInnerRadius(outer: Dp, padding: Dp): Dp = maxOf(KasaneRadius.min, outer - padding)

/**
 * M3 の Shapes の段に kasane の角丸を当てたもの（仮の対応。本人の確認待ち: kasane の docs/tokens-format.md）。
 * extraSmall は M3 の既定のまま。small = sm、medium = base（ボタン・入力欄）、large = lg、extraLarge = card
 */
val KasaneShapes = Shapes(
    small = RoundedCornerShape(KasaneRadius.sm),
    medium = RoundedCornerShape(KasaneRadius.base),
    large = RoundedCornerShape(KasaneRadius.lg),
    extraLarge = RoundedCornerShape(KasaneRadius.card),
)
