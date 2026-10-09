package xyz.mooma0750.wave.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** [ActionButtonGroup] に並べるボタン1つ。primary は塗りつぶし（1グループに1つまで）、ほかは枠線。 */
class GroupAction(
    val label: String,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * 横に並べたボタン（Material 3 Expressive の ButtonGroup）。押したボタンが少し広がり、形も押すと変わる。
 * 幅に入りきらないボタンは「…」のメニューへ回る。
 */
@Composable
fun ActionButtonGroup(
    actions: List<GroupAction>,
    modifier: Modifier = Modifier,
) {
    ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }, modifier = modifier) {
        actions.forEach { action ->
            customItem(
                buttonGroupContent = {
                    val interaction = remember { MutableInteractionSource() }
                    val buttonModifier = Modifier.animateWidth(interaction)
                    if (action.primary) {
                        Button(
                            onClick = action.onClick,
                            shapes = ButtonDefaults.shapes(),
                            modifier = buttonModifier,
                            enabled = action.enabled,
                            interactionSource = interaction,
                        ) { Text(action.label, maxLines = 1) }
                    } else {
                        OutlinedButton(
                            onClick = action.onClick,
                            shapes = ButtonDefaults.shapes(),
                            modifier = buttonModifier,
                            enabled = action.enabled,
                            interactionSource = interaction,
                        ) { Text(action.label, maxLines = 1) }
                    }
                },
                menuContent = { menu ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = {
                            menu.dismiss()
                            action.onClick()
                        },
                        enabled = action.enabled,
                    )
                },
            )
        }
    }
}
