package xyz.mooma0750.wave.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.widget.WidgetScale
import kotlin.math.roundToInt

/**
 * ホーム画面のウィジェットの大きさ（文字・ボタン・絵・余白の倍率。80〜160% を 10% ずつ）。
 * ランチャーや表示サイズの設定によっては中身が小さく見えるので、本人が合わせられるようにする。指を離したときに置いてあるウィジェットを描き直す。
 */
@Composable
fun WidgetScaleSetting(scope: CoroutineScope) {
    val context = LocalContext.current
    var percent by remember { mutableFloatStateOf(WidgetScale.percent(context).toFloat()) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_widget_scale_title)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_widget_scale_summary))
                Slider(
                    value = percent,
                    onValueChange = { percent = it },
                    onValueChangeFinished = {
                        val value = percent.roundToInt()
                        scope.launch { WidgetScale.set(context, value) }
                    },
                    valueRange = WidgetScale.MIN_PERCENT.toFloat()..WidgetScale.MAX_PERCENT.toFloat(),
                    steps = (WidgetScale.MAX_PERCENT - WidgetScale.MIN_PERCENT) / WidgetScale.STEP_PERCENT - 1,
                )
            }
        },
        trailingContent = { Text(stringResource(R.string.settings_widget_scale_value, percent.roundToInt()), style = MaterialTheme.typography.labelLarge) },
    )
}
