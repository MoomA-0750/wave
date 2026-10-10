package xyz.mooma0750.wave.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.watchlink.WatchRemote

/**
 * 端末（スマホの「デバイスに接続」にあたる）。スマホの再生を見て操作し、時計とスマホの間で再生を移す。
 * スマホが Windows などの再生を映していれば、その操作もスマホを通して届く。スマホから家のほかの端末へ再生を移せる。
 */
@Composable
fun DevicesScreen(
    remote: WatchRemote,
    watchHasQueue: Boolean,
) {
    DisposableEffect(remote) {
        remote.watchPeer()
        onDispose { remote.stopWatchingPeer() }
    }
    val peer by remote.peer.collectAsStateWithLifecycle()
    val status by remote.peerStatus.collectAsStateWithLifecycle()
    val result by remote.result.collectAsStateWithLifecycle()
    ListScreen(stringResource(R.string.nowplaying_connected_devices)) { look ->
        result?.second?.let { outcome ->
            item(key = "result") {
                MessageRow(
                    look,
                    stringResource(
                        when (outcome) {
                            WatchRemote.Result.Pulled -> R.string.wear_remote_pulled
                            WatchRemote.Result.Pushed -> R.string.wear_remote_pushed
                            WatchRemote.Result.NothingToPull -> R.string.wear_remote_nothing
                            WatchRemote.Result.Failed -> R.string.wear_remote_failed
                        },
                    ),
                )
            }
        }
        val phone = peer
        when {
            phone == null && status == WatchRemote.PeerStatus.NotFound -> item(key = "none") { MessageRow(look, stringResource(R.string.wear_remote_no_phone)) }
            phone == null -> item(key = "loading") { LoadingRow(look) }
            else -> {
                item(key = "phone") {
                    EntryRow(
                        look,
                        phone.playingOn?.let { stringResource(R.string.nowplaying_playing_on, it) } ?: phone.deviceName,
                        phone.title?.let { listOfNotNull(it, phone.artist).joinToString(" · ") } ?: stringResource(R.string.wear_nothing_playing),
                        phone.thumbnailUrl,
                        icon = if (phone.thumbnailUrl == null) R.drawable.ic_smartphone else null,
                    ) {}
                }
                if (phone.hasQueue) {
                    item(key = "controls") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { remote.command(WatchRemote.CMD_PREVIOUS) }) { Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.nowplaying_previous)) }
                            FilledIconButton(onClick = { remote.command(if (phone.playing) WatchRemote.CMD_PAUSE else WatchRemote.CMD_PLAY) }) {
                                Icon(painterResource(if (phone.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow), stringResource(if (phone.playing) R.string.nowplaying_pause else R.string.nowplaying_play))
                            }
                            IconButton(onClick = { remote.command(WatchRemote.CMD_NEXT) }) { Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.nowplaying_next)) }
                        }
                    }
                    item(key = "volume") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(onClick = { remote.command(WatchRemote.CMD_VOLUME_DOWN) }) { Icon(painterResource(R.drawable.ic_volume_down), stringResource(R.string.wear_volume_down)) }
                            phone.liked?.let { liked ->
                                IconButton(onClick = { remote.command(if (liked) WatchRemote.CMD_UNLIKE else WatchRemote.CMD_LIKE) }) {
                                    Icon(painterResource(if (liked) R.drawable.ic_check_circle else R.drawable.ic_add_circle), stringResource(if (liked) R.string.nowplaying_unlike else R.string.nowplaying_like))
                                }
                            }
                            IconButton(onClick = { remote.command(WatchRemote.CMD_VOLUME_UP) }) { Icon(painterResource(R.drawable.ic_volume_up), stringResource(R.string.wear_volume_up)) }
                        }
                    }
                    // ほかの端末の再生を映しているときは、スマホのキューではないので引き取らない（先にスマホへ移す）
                    if (phone.playingOn == null) {
                        item(key = "pull") { ActionRow(look, R.drawable.ic_headphones, stringResource(R.string.wear_remote_pull), onClick = remote::pull) }
                    } else {
                        item(key = "here") { ActionRow(look, R.drawable.ic_smartphone, stringResource(R.string.wear_remote_play_on_phone)) { remote.command(WatchRemote.CMD_PLAY_HERE) } }
                    }
                }
                if (watchHasQueue) item(key = "push") { ActionRow(look, R.drawable.ic_smartphone, stringResource(R.string.wear_remote_push), onClick = remote::push) }
                if (phone.devices.isNotEmpty()) item(key = "devices-h") { MessageRow(look, stringResource(R.string.devices_select_another)) }
                items(phone.devices, key = { "d-${it.deviceId}" }) { device ->
                    ActionRow(
                        look,
                        if (device.isPc) R.drawable.ic_computer else R.drawable.ic_smartphone,
                        device.label,
                        if (device.online) null else stringResource(R.string.devices_offline),
                        enabled = device.online,
                    ) { remote.command(WatchRemote.CMD_PLAY_ON + device.deviceId) }
                }
            }
        }
    }
}
