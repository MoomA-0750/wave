package xyz.mooma0750.wave.connect2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.handoff.v2.Role

/**
 * Spotify の「デバイスに接続」と同じ、端末を選ぶだけの画面（下から出る）。上に今鳴っている端末、下に家のほかの端末。
 * 押すとその端末に再生を移す。家の設定（加える・外す）は下のリンクから開く。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerSheet(
    manager: ConnectV2Manager,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    /** 時計（Wear OS 版の wave）。近くにあれば、時計へ再生を移す行を出す。 */
    watch: xyz.mooma0750.wave.watchlink.WatchRemote? = null,
) {
    if (watch != null) {
        androidx.compose.runtime.DisposableEffect(watch) {
            watch.watchPeer()
            onDispose { watch.stopWatchingPeer() }
        }
    }
    val watchState by (watch?.peer ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsStateWithLifecycle()
    val info by manager.info.collectAsStateWithLifecycle()
    val members by manager.members.collectAsStateWithLifecycle()
    val remote by manager.remotePlayback.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(stringResource(R.string.devices_current), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMediumEmphasized)
            val playing = remote
            if (playing == null) {
                DeviceRow(R.drawable.ic_smartphone, stringResource(R.string.devices_this_phone), null, current = true, enabled = true, onClick = null)
            } else {
                DeviceRow(
                    if (members.firstOrNull { it.deviceId == playing.deviceId }?.isPc == true) R.drawable.ic_computer else R.drawable.ic_smartphone,
                    playing.deviceLabel,
                    listOf(playing.current.title, playing.current.artists.joinToString(", ") { it.name }).joinToString(" • "),
                    current = true,
                    enabled = true,
                    onClick = null,
                )
            }
            if (!info.inHousehold) {
                Text(stringResource(R.string.devices_not_set_up), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val others =
                    members
                        .filter { it.role == Role.Player && if (playing == null) !it.isSelf else it.deviceId != playing.deviceId }
                        .sortedWith(compareByDescending<ConnectV2Manager.MemberView> { it.isSelf }.thenByDescending { it.online })
                if (others.isNotEmpty()) {
                    Text(stringResource(R.string.devices_select_another), Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMediumEmphasized)
                    for (member in others) {
                        if (member.isSelf) {
                            DeviceRow(R.drawable.ic_smartphone, stringResource(R.string.devices_this_phone), null, current = false, enabled = true) {
                                onDismiss()
                                manager.transfer()
                            }
                        } else {
                            DeviceRow(if (member.isPc) R.drawable.ic_computer else R.drawable.ic_smartphone, member.label, if (member.online) null else stringResource(R.string.devices_offline), current = false, enabled = member.online) {
                                onDismiss()
                                manager.playOnDevice(member.deviceId)
                            }
                        }
                    }
                }
            }
            // 時計（中継を使わず、Bluetooth などで直接つなぐ）。押すと時計へ移す。スマホで何も鳴っていなければ、時計の再生をスマホへ移す
            watchState?.let { state ->
                DeviceRow(
                    R.drawable.ic_watch,
                    state.deviceName,
                    state.title?.let { listOfNotNull(it, state.artist).joinToString(" • ") },
                    current = false,
                    enabled = true,
                ) {
                    onDismiss()
                    if (remote == null && state.playing) watch?.pull() else watch?.push()
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                onDismiss()
                onOpenSettings()
            }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.devices_settings)) }
        }
    }
}

@Composable
private fun DeviceRow(
    icon: Int,
    title: String,
    subtitle: String?,
    current: Boolean,
    enabled: Boolean,
    onClick: (() -> Unit)?,
) {
    val accent = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(28.dp), tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, color = accent, style = MaterialTheme.typography.bodyLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
