package xyz.mooma0750.wave.wear

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import kotlinx.coroutines.delay
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.watchlink.RemoteState
import xyz.mooma0750.wave.watchlink.WatchRemote

/** 再生画面に出す再生（スマホのものか、この時計のものか）。 */
enum class PlayerTarget { Phone, Watch }

/**
 * 再生画面にどちらを出すか。スマホの操作を主にするので、この時計で鳴らしていなければスマホを出す。
 * 時計で鳴らしている間は時計。どちらも鳴っていなければ、キューのあるほう（両方あればスマホ）。
 */
fun choosePlayerTarget(
    watchPlaying: Boolean,
    watchHasQueue: Boolean,
    phone: RemoteState?,
): PlayerTarget =
    when {
        watchPlaying -> PlayerTarget.Watch
        phone?.hasQueue == true -> PlayerTarget.Phone
        watchHasQueue -> PlayerTarget.Watch
        phone != null -> PlayerTarget.Phone
        else -> PlayerTarget.Watch
    }

/** 画面が出ている間、スマホの再生を届けてもらう（画面ごとに数える。裏に回ったら止める）。 */
@Composable
fun WatchPhone(remote: WatchRemote) {
    androidx.lifecycle.compose.LifecycleStartEffect(remote) {
        remote.watchPeer()
        onStopOrDispose { remote.stopWatchingPeer() }
    }
}

/** スマホの再生の位置（届いた位置から、鳴っている間は手元で進める）。 */
@Composable
private fun rememberPhonePosition(
    remote: WatchRemote,
    phone: RemoteState,
): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(phone.playing) {
        while (phone.playing) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val elapsed = if (phone.playing) (now - remote.peerReceivedAt).coerceAtLeast(0) else 0
    return (phone.positionMs + elapsed).let { if (phone.durationMs > 0) it.coerceAtMost(phone.durationMs) else it }
}

/**
 * スマホの再生画面（時計の再生画面と同じ形）。操作はスマホへ送り、スマホがほかの端末を映していればその端末に届く。
 * ベゼルはスマホの音量を変える（時計は手元の操作なので、スマホの音量ボタンと同じに扱う）。
 */
@Composable
fun PhoneNowPlayingScreen(
    remote: WatchRemote,
    deviceLabel: String?,
    onDeviceLabel: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    WatchPhone(remote)
    val peer by remote.peer.collectAsStateWithLifecycle()
    val status by remote.peerStatus.collectAsStateWithLifecycle()
    val phone = peer
    if (phone == null) {
        PlayerLayout(
            artworkUrl = null,
            progress = 0f,
            title = stringResource(if (status == WatchRemote.PeerStatus.NotFound) R.string.wear_remote_no_phone_short else R.string.wear_remote_searching),
            subtitle = "",
            onSubtitle = null,
            deviceLabel = deviceLabel,
            onDeviceLabel = onDeviceLabel,
            isPlaying = false,
            enabled = false,
            hasNext = false,
            onPrevious = {},
            onPlayPause = {},
            onNext = {},
            volume = { null },
            onRotate = { false },
            likeButton = {},
            onOpenQueue = null,
            onOpenMenu = null,
        )
        return
    }
    val position = rememberPhonePosition(remote, phone)
    // 押した直後は、スマホの答えが来るまで押したほうの見た目にしておく
    var pressedPlay by remember { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    LaunchedEffect(phone.playing) { pressedPlay = null }
    val playing = pressedPlay ?: phone.playing
    var lastVolumeAt by remember { mutableLongStateOf(0L) }
    PlayerLayout(
        artworkUrl = phone.thumbnailUrl,
        progress = if (phone.durationMs > 0) (position.toFloat() / phone.durationMs).coerceIn(0f, 1f) else 0f,
        title = phone.title ?: stringResource(R.string.wear_nothing_playing),
        subtitle = listOfNotNull(phone.artist, phone.playingOn?.let { stringResource(R.string.nowplaying_playing_on, it) }).joinToString(" · "),
        onSubtitle = null,
        deviceLabel = deviceLabel,
        onDeviceLabel = onDeviceLabel,
        isPlaying = playing,
        enabled = phone.hasQueue,
        hasNext = true,
        onPrevious = { remote.command(WatchRemote.CMD_PREVIOUS) },
        onPlayPause = {
            pressedPlay = !playing
            remote.command(if (playing) WatchRemote.CMD_PAUSE else WatchRemote.CMD_PLAY)
        },
        onNext = { remote.command(WatchRemote.CMD_NEXT) },
        // スマホの音量は数で届かないので、ゲージは出さない
        volume = { null },
        onRotate = { up ->
            // 回し続けたときに送りすぎない
            val now = SystemClock.elapsedRealtime()
            if (now - lastVolumeAt >= VOLUME_STEP_MS) {
                lastVolumeAt = now
                remote.command(if (up) WatchRemote.CMD_VOLUME_UP else WatchRemote.CMD_VOLUME_DOWN)
            }
            false
        },
        likeButton = {
            val liked = phone.liked
            IconButton(shapes = IconButtonDefaults.animatedShapes(), onClick = { liked?.let { remote.command(if (it) WatchRemote.CMD_UNLIKE else WatchRemote.CMD_LIKE) } }, enabled = liked != null) {
                Icon(
                    painterResource(if (liked == true) R.drawable.ic_check_circle else R.drawable.ic_add_circle),
                    stringResource(if (liked == true) R.string.nowplaying_unlike else R.string.nowplaying_like),
                    tint = if (liked == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        onOpenQueue = if (phone.playingOn == null && phone.queue.isNotEmpty()) onOpenQueue else null,
        onOpenMenu = null,
    )
}

/** スマホのキュー（今の曲のまわり）。行を押すとスマホでその曲へ移る。シャッフル・繰り返しもスマホで切り替える。 */
@Composable
fun PhoneQueueScreen(remote: WatchRemote) {
    WatchPhone(remote)
    val peer by remote.peer.collectAsStateWithLifecycle()
    ListScreen(stringResource(R.string.nowplaying_queue)) { look ->
        val phone = peer
        if (phone == null) {
            item(key = "loading") { LoadingRow(look) }
            return@ListScreen
        }
        item(key = "shuffle") {
            ActionRow(look, R.drawable.ic_shuffle, stringResource(if (phone.shuffled) R.string.ui_shuffle_on else R.string.ui_shuffle_off)) { remote.command(WatchRemote.CMD_SHUFFLE) }
        }
        item(key = "repeat") {
            ActionRow(look, if (phone.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat, repeatLabel(phone.repeatMode)) {
                remote.command(WatchRemote.CMD_REPEAT)
            }
        }
        if (phone.queue.isEmpty()) item(key = "empty") { MessageRow(look, stringResource(R.string.queue_notice_empty)) }
        items(phone.queue, key = { "q-${it.itemId}" }) { row ->
            EntryRow(
                look,
                if (row.current) "▶ ${row.title}" else row.title,
                if (row.userAdded) "${row.artist} · ${stringResource(R.string.nowplaying_next_in_queue)}" else row.artist,
                row.thumbnailUrl,
            ) { if (!row.current) remote.command(WatchRemote.CMD_JUMP + row.itemId) }
        }
    }
}

private const val VOLUME_STEP_MS = 120L
