package xyz.mooma0750.wave.wear

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.LikeStatus
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.library.PlaylistMembership
import xyz.mooma0750.wave.nowplaying.rememberPlayerUiState
import xyz.mooma0750.wave.playback.Media3QueuePlayer
import xyz.mooma0750.wave.playback.PlayerConnection

/**
 * 時計の再生画面。周りの輪が進み具合、真ん中に曲名と前・再生・次、下に評価（✓）・キュー・メニュー。
 * ベゼル（回転入力）で音量を変える（Wear OS の音楽アプリと同じ）。
 */
@Composable
fun NowPlayingScreen(
    connection: PlayerConnection,
    membership: PlaylistMembership,
    onOpenQueue: () -> Unit,
    onOpenMenu: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
) {
    val (state, controller) = rememberPlayerUiState(connection)
    val scope = rememberCoroutineScope()
    val song = controller?.currentMediaItem?.let(Media3QueuePlayer::songOf)
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var volume by remember { mutableFloatStateOf(audio.volumeFraction()) }
    var volumeShownAt by remember { mutableLongStateOf(0L) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    ScreenScaffold {
        Box(
            Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { event ->
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (event.verticalScrollPixels > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                    volume = audio.volumeFraction()
                    volumeShownAt = System.currentTimeMillis()
                    true
                }.focusRequester(focus)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize().padding(3.dp), strokeWidth = 5.dp)
            // 回したら少しの間だけ音量を出す
            var showVolume by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(volumeShownAt) {
                if (volumeShownAt == 0L) return@LaunchedEffect
                showVolume = 1f
                delay(1_500)
                showVolume = 0f
            }
            if (showVolume > 0f) LevelIndicator(value = { volume }, modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp))
            Column(
                Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 14.dp)) {
                    Text(
                        state.title ?: stringResource(R.string.wear_nothing_playing),
                        modifier = Modifier.basicMarquee(),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        when {
                            state.error != null -> stringResource(R.string.nowplaying_error)
                            state.buffering -> stringResource(R.string.nowplaying_buffering)
                            else -> state.artist.orEmpty()
                        },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { controller?.seekToPrevious() }, enabled = controller != null) {
                        Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.nowplaying_previous))
                    }
                    FilledIconButton(
                        onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                        modifier = Modifier.size(IconButtonDefaults.LargeButtonSize),
                        enabled = controller != null,
                    ) {
                        Icon(
                            painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                            stringResource(if (state.isPlaying) R.string.nowplaying_pause else R.string.nowplaying_play),
                            modifier = Modifier.size(IconButtonDefaults.LargeIconSize),
                        )
                    }
                    IconButton(onClick = { controller?.seekToNext() }, enabled = state.hasNext) {
                        Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.nowplaying_next))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LikeButton(connection, membership, song, onSaved = { song?.let(onAddToPlaylist) }, scope = scope)
                    IconButton(onClick = onOpenQueue) { Icon(painterResource(R.drawable.ic_queue_music), stringResource(R.string.nowplaying_queue)) }
                    IconButton(onClick = { song?.let(onOpenMenu) }, enabled = song != null) {
                        Icon(painterResource(R.drawable.ic_more_horiz), stringResource(R.string.library_more_options))
                    }
                }
            }
        }
    }
}

/** 評価のボタン（スマホと同じ）。高く評価したか、どれかのプレイリストに入っていれば✓。✓を押すとプレイリストに追加する画面。 */
@Composable
private fun LikeButton(
    connection: PlayerConnection,
    membership: PlaylistMembership,
    song: Song?,
    onSaved: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val like by connection.likeState.collectAsState()
    val nowIn by membership.nowPlaying.collectAsState()
    val videoId = like.videoId
    val liked = like.status == LikeStatus.Like
    val saved = liked || (nowIn?.videoId == videoId && nowIn?.playlistIds?.isNotEmpty() == true)
    IconButton(
        onClick = {
            when {
                videoId == null -> Unit
                saved -> onSaved()
                else -> scope.launch { connection.setLiked(videoId, true) }
            }
        },
        enabled = videoId != null && !like.pending && song != null,
    ) {
        Icon(
            painterResource(
                when {
                    saved -> R.drawable.ic_check_circle
                    like.status == null && videoId != null -> R.drawable.ic_refresh
                    else -> R.drawable.ic_add_circle
                },
            ),
            stringResource(
                when {
                    like.pending -> R.string.nowplaying_like_pending
                    saved -> R.string.nowplaying_saved_in_playlist
                    else -> R.string.nowplaying_like
                },
            ),
            tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun AudioManager.volumeFraction(): Float {
    val max = getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

/** 繰り返しの次の状態の文言。 */
@Composable
fun repeatLabel(mode: Int): String =
    stringResource(
        when (mode) {
            Player.REPEAT_MODE_ALL -> R.string.nowplaying_repeat_all
            Player.REPEAT_MODE_ONE -> R.string.nowplaying_repeat_one
            else -> R.string.nowplaying_repeat_off
        },
    )
