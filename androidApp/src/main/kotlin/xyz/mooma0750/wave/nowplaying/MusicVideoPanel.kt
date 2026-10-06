package xyz.mooma0750.wave.nowplaying

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.delay
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.MusicVideo
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.ui.IconTextButton
import androidx.compose.ui.res.stringResource

/** 再生中の曲の映像（MV）。音はそのまま、映像だけを重ねる。画面が裏に回ったら映像のプレイヤーを捨てる。 */
@Composable
fun MusicVideoPanel(
    song: Song,
    player: Player?,
    videos: MusicVideos,
    modifier: Modifier = Modifier,
) {
    var attempt by remember(song.videoId) { mutableIntStateOf(0) }
    val result by produceState<MusicVideos.Result?>(null, song.videoId, attempt) { value = videos.videoFor(song.videoId) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when (val current = result) {
            null -> Note(stringResource(R.string.nowplaying_video_searching))
            MusicVideos.Result.None -> Note(stringResource(R.string.nowplaying_video_none))
            MusicVideos.Result.Failed ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Note(stringResource(R.string.nowplaying_video_load_failed))
                    IconTextButton(R.drawable.ic_refresh, stringResource(R.string.nowplaying_retry), { attempt++ })
                }
            is MusicVideos.Result.Found ->
                if (lifecycle.isAtLeast(Lifecycle.State.STARTED)) {
                    // もう一度のたびに映像のプレイヤーを作り直す
                    key(attempt) { SyncedVideo(current.video, player, videos, onFailed = { attempt++ }) }
                }
        }
    }
}

@Composable
private fun SyncedVideo(
    video: MusicVideo,
    mainPlayer: Player?,
    videos: MusicVideos,
    onFailed: () -> Unit,
) {
    val context = LocalContext.current
    val videoPlayer = remember(video.videoId) { videos.createPlayer(context) }
    var aspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var firstFrame by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(videoPlayer) {
        val listener =
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) aspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }

                override fun onRenderedFirstFrame() {
                    firstFrame = true
                }

                override fun onPlayerError(error: PlaybackException) {
                    failed = true
                }
            }
        videoPlayer.addListener(listener)
        videoPlayer.setMediaItem(videos.mediaItem(video.videoId))
        videoPlayer.prepare()
        onDispose {
            videoPlayer.removeListener(listener)
            videoPlayer.release()
        }
    }

    LaunchedEffect(videoPlayer, mainPlayer) {
        val main = mainPlayer ?: return@LaunchedEffect
        while (true) {
            val step =
                VideoSync.step(
                    targetMs = video.videoPositionOf(main.currentPosition),
                    videoMs = videoPlayer.currentPosition,
                    playing = main.isPlaying,
                    videoReady = videoPlayer.playbackState == Player.STATE_READY,
                )
            hidden = step == VideoSync.Step.Hide
            when (step) {
                VideoSync.Step.Hide -> videoPlayer.pause()
                is VideoSync.Step.Seek -> {
                    videoPlayer.seekTo(step.toMs)
                    videoPlayer.playWhenReady = step.play
                }
                is VideoSync.Step.Run -> {
                    if (videoPlayer.playbackParameters.speed != step.speed) videoPlayer.setPlaybackSpeed(step.speed)
                    videoPlayer.playWhenReady = step.play
                }
            }
            delay(SYNC_INTERVAL_MS)
        }
    }

    if (failed) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Note(stringResource(R.string.nowplaying_video_play_failed))
            IconTextButton(R.drawable.ic_refresh, stringResource(R.string.nowplaying_retry), onFailed)
        }
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { TextureView(it).also(videoPlayer::setVideoTextureView) },
            modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio),
        )
        if (!firstFrame) Note(stringResource(R.string.nowplaying_video_loading))
        if (hidden) Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { Note(stringResource(R.string.nowplaying_video_gap)) }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Color.White)
}

private const val SYNC_INTERVAL_MS = 200L
