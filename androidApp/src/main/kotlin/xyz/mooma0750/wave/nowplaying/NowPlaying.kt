package xyz.mooma0750.wave.nowplaying

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import xyz.mooma0750.wave.connect2.ConnectV2Manager
import xyz.mooma0750.wave.handoff.HandoffRepeat
import xyz.mooma0750.wave.handoff.v2.ControlAction
import androidx.compose.runtime.mutableLongStateOf
import xyz.mooma0750.wave.ui.IconAction
import xyz.mooma0750.wave.R
import androidx.media3.common.Player
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import xyz.mooma0750.wave.ui.PlayFab
import xyz.mooma0750.wave.ui.rememberArtworkColor
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.core.lyrics.LyricsRepository
import xyz.mooma0750.wave.core.music.LikeStatus
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.playback.Media3QueuePlayer
import xyz.mooma0750.wave.playback.PlayerConnection
import xyz.mooma0750.wave.playback.togglePlayPause
import xyz.mooma0750.wave.search.SongMenuButton
import xyz.mooma0750.wave.ui.Durations
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

/**
 * 画面の下のミニプレーヤー（並びは Spotify と同じ: ジャケット・曲名・端末・評価・再生、下に進み具合の線）。
 * 押すと再生画面を開く。左右に払うと前後の曲へ。
 */
@Composable
fun MiniPlayer(
    connection: PlayerConnection,
    connect: ConnectV2Manager?,
    onOpen: () -> Unit,
    onOpenDevices: () -> Unit,
    actions: SongActions? = null,
) {
    val display = rememberDisplay(connection, connect)
    val state = display.state
    if (state.title == null) return
    val scope = rememberCoroutineScope()
    val song = display.song
    val art by rememberArtworkColor(song?.thumbnailUrl)
    val container = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, art, 0.35f)
    var drag by remember { mutableFloatStateOf(0f) }
    Surface(
        color = container,
        shape = RoundedCornerShape(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .pointerInput(display.remote != null, state.hasNext, state.hasPrevious) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                drag < -SWIPE_PX -> if (state.hasNext) display.next()
                                drag > SWIPE_PX -> if (state.hasPrevious) display.previous()
                            }
                            drag = 0f
                        },
                    ) { _, amount -> drag += amount }
                }.clickable(onClick = onOpen),
    ) {
        Column {
            Row(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                coil3.compose.AsyncImage(
                    model = song?.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    val remote = display.remote
                    if (remote != null) {
                        // Spotify と同じく、鳴らしている端末を色を付けて出す
                        Text(
                            stringResource(R.string.nowplaying_playing_on, remote.deviceLabel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Text(
                            listOfNotNull(state.artist, state.error?.let { stringResource(R.string.nowplaying_error, it) }, if (state.buffering) stringResource(R.string.nowplaying_buffering) else null).joinToString(" • "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconAction(R.drawable.ic_devices, stringResource(R.string.nowplaying_connected_devices), onOpenDevices, active = display.remote != null)
                LikeButton(connection, scope, onLikedTap = song?.let { current -> actions?.let { { it.addToPlaylist(current) } } })
                IconAction(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (state.isPlaying) stringResource(R.string.nowplaying_pause) else stringResource(R.string.nowplaying_play), { display.togglePlay() })
            }
            if (state.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
    }
}

/** 評価のボタン（Spotify の「＋」と「✓」）。押すと「高く評価」を付け外しする。 */
@Composable
private fun LikeButton(
    connection: PlayerConnection,
    scope: kotlinx.coroutines.CoroutineScope,
    size: androidx.compose.ui.unit.Dp = 24.dp,
    /** 評価済み（✓）のときに押した: Spotify と同じく、プレイリストに追加する画面を出す（評価はそこで外せる）。 */
    onLikedTap: (() -> Unit)? = null,
) {
    val like by connection.likeState.collectAsState()
    val videoId = like.videoId
    val liked = like.status == LikeStatus.Like
    IconAction(
        when {
            liked -> R.drawable.ic_check_circle
            like.status == null && videoId != null -> R.drawable.ic_refresh
            else -> R.drawable.ic_add_circle
        },
        when {
            like.pending -> stringResource(R.string.nowplaying_like_pending)
            liked -> stringResource(R.string.nowplaying_unlike)
            like.status == null -> stringResource(R.string.nowplaying_like_reload)
            else -> stringResource(R.string.nowplaying_like)
        },
        {
            when {
                videoId == null -> Unit
                liked && onLikedTap != null -> onLikedTap()
                else -> scope.launch { connection.setLiked(videoId, !liked) }
            }
        },
        enabled = videoId != null && !like.pending,
        active = liked,
        size = size,
    )
}

/**
 * 再生画面（並びは Spotify と同じ）。上にジャケットの色のグラデーション、閉じる・曲／動画の切り替え・メニュー、
 * 大きなジャケット（動画のときは映像）、曲名と評価、シークバー、操作の列、端末と再生キュー、下に歌詞のカード。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    connection: PlayerConnection,
    lyrics: LyricsRepository,
    videos: MusicVideos,
    actions: SongActions,
    onClose: () -> Unit,
    onOpenDevices: () -> Unit,
    connect: ConnectV2Manager? = null,
    /** 広い画面の右の列として出す（左上は閉じる代わりに全面に広げる。onClose が広げる）。 */
    inPanel: Boolean = false,
    /** Claude に聞く（null なら出さない）。 */
    onAskAi: (() -> Unit)? = null,
) {
    val display = rememberDisplay(connection, connect)
    val state = display.state
    val controller = display.controller
    val remote = display.remote
    val scope = rememberCoroutineScope()
    val song = display.song
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var video by rememberSaveable { mutableStateOf(false) }
    var fullVideo by rememberSaveable { mutableStateOf(false) }
    var pickArtist by remember { mutableStateOf<List<xyz.mooma0750.wave.core.music.ArtistRef>?>(null) }
    // 動画のある曲だけ「動画」を押せるようにする（押してから無いと分かるのを避ける）
    val hasVideo by androidx.compose.runtime.produceState<Boolean?>(null, song?.videoId) {
        value = song?.videoId?.let { videos.videoFor(it) is MusicVideos.Result.Found }
    }
    LaunchedEffect(hasVideo) { if (hasVideo == false) video = false }
    val art by rememberArtworkColor(song?.thumbnailUrl)
    val background = MaterialTheme.colorScheme.background
    val top = lerp(background, art, 0.6f)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to top, 0.75f to background)),
    ) {
        // 広い画面の全面では、左にジャケットと操作、右に歌詞を縦いっぱいに並べる（中央に細い列だけだと左右が空く）
        val sideBySide = !inPanel && maxWidth >= SIDE_BY_SIDE_MIN_WIDTH
        val leftWidth = if (sideBySide) minOf(MAX_CONTENT_WIDTH, maxWidth * 0.45f) else minOf(maxWidth, MAX_CONTENT_WIDTH)
        // 曲名から操作の列までが最初の画面に収まるよう、ジャケットは高さに合わせて小さくする（開いた折りたたみ・横向き）
        val artSize = minOf(leftWidth - 48.dp, maxHeight - CONTROLS_HEIGHT).coerceAtLeast(160.dp)
        val hasLyrics by androidx.compose.runtime.produceState<Boolean?>(null, song?.videoId) {
            value = song?.let { lyrics.lyrics(it) is xyz.mooma0750.wave.core.lyrics.LyricsRepository.Result.Found }
        }
        if (sideBySide && song != null && hasLyrics == true) {
            Card(
                Modifier
                    .align(Alignment.TopEnd)
                    .width(maxWidth - leftWidth - 48.dp)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(top = 56.dp, bottom = 24.dp, end = 24.dp),
                colors = CardDefaults.cardColors(containerColor = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, art, 0.5f)),
            ) {
                Text(stringResource(R.string.nowplaying_lyrics), Modifier.padding(start = 20.dp, top = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                LyricsPanel(song, display.clock, lyrics, Modifier.fillMaxSize().padding(horizontal = 12.dp))
            }
        }
        Column(
            Modifier
                .widthIn(max = leftWidth)
                .align(if (sideBySide) Alignment.TopStart else Alignment.TopCenter)
                .then(if (sideBySide) Modifier.padding(start = 24.dp) else Modifier)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (inPanel) {
                    IconAction(R.drawable.ic_open_in_full, stringResource(R.string.nowplaying_expand), onClose)
                } else {
                    IconAction(R.drawable.ic_arrow_down, stringResource(R.string.nowplaying_close), onClose)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // Spotify の「曲｜動画」の切り替え。動画のある曲だけ出す（無い曲・調べている間・ほかの端末を映している間は出さない）
                    if (song != null && hasVideo == true) {
                        SingleChoiceSegmentedButtonRow {
                            SegmentedButton(selected = !video, onClick = { video = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text(stringResource(R.string.nowplaying_song)) }
                            SegmentedButton(selected = video, onClick = { video = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text(stringResource(R.string.nowplaying_video)) }
                        }
                    }
                }
                if (song != null && onAskAi != null) IconAction(R.drawable.ic_ai, stringResource(R.string.ai_title), onAskAi)
                song?.let { SongMenuButton(it, actions, inQueue = true) } ?: Spacer(Modifier.size(48.dp))
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = if (video) 8.dp else 24.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                if (video && song != null) {
                    // ほかの端末を映している間も、その再生の位置（見積もり）に合わせて映像を出す。
                    // 映像は横幅いっぱいの 16:9 で出し（正方形のジャケットの枠だと小さい）、右下のボタンで全画面にする
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                        if (!fullVideo) MusicVideoPanel(song, display.clock, videos, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                        IconAction(R.drawable.ic_fullscreen, stringResource(R.string.nowplaying_video_fullscreen), { fullVideo = true }, modifier = Modifier.align(Alignment.BottomEnd))
                    }
                } else {
                    coil3.compose.AsyncImage(
                        model = song?.thumbnailUrl?.takeIf { it.startsWith("https://") }?.let(Media3QueuePlayer::largeArtwork),
                        contentDescription = null,
                        modifier = Modifier.size(artSize).clip(RoundedCornerShape(8.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // 曲名を押すとアルバムへ、アーティストを押すとアーティストのページへ（複数なら選ぶ）
                    val album = song?.album?.takeIf { it.browseId != null }
                    Text(
                        state.title.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.then(if (album != null) Modifier.clickable { actions.openAlbum(album) } else Modifier),
                    )
                    val artists = song?.artists?.filter { it.browseId != null }.orEmpty()
                    Text(
                        state.artist.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier.then(
                                when (artists.size) {
                                    0 -> Modifier
                                    1 -> Modifier.clickable { actions.openArtist(artists.single()) }
                                    else -> Modifier.clickable { pickArtist = artists }
                                },
                            ),
                    )
                }
                LikeButton(connection, scope, size = 28.dp, onLikedTap = song?.let { current -> { actions.addToPlaylist(current) } })
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                // ほかの端末を映している間は、ドラッグの間は表示だけ動かし、指を離したときに1回だけ命令を送る（命令ごとに暗号化・鍵交換をする）
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragging ?: if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = {
                        if (remote != null) {
                            dragging = it
                        } else if (state.durationMs > 0) {
                            display.seekTo((it * state.durationMs).toLong())
                        }
                    },
                    onValueChangeFinished = {
                        val target = dragging
                        dragging = null
                        if (remote != null && target != null && state.durationMs > 0) display.seekTo((target * state.durationMs).toLong())
                    },
                    enabled = state.durationMs > 0,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(state.positionMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(state.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                // シャッフルは入り切り（切ると残りの曲を入れる前の並びに戻す）。入っている間は色を付ける
                val shuffled by connection.shuffled.collectAsState()
                IconAction(
                    R.drawable.ic_shuffle,
                    if (shuffled) stringResource(R.string.ui_shuffle_on) else stringResource(R.string.ui_shuffle_off),
                    { scope.launch { connection.toggleShuffle() } },
                    active = shuffled && remote == null,
                    enabled = remote == null && state.currentIndex >= 0,
                )
                IconAction(R.drawable.ic_skip_previous, stringResource(R.string.nowplaying_previous), { display.previous() }, enabled = state.hasPrevious, size = 36.dp)
                PlayFab(state.isPlaying, { display.togglePlay() }, size = 72.dp)
                IconAction(R.drawable.ic_skip_next, stringResource(R.string.nowplaying_next), { display.next() }, enabled = state.hasNext, size = 36.dp)
                IconAction(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                    when (state.repeatMode) {
                        Player.REPEAT_MODE_ALL -> stringResource(R.string.nowplaying_repeat_all)
                        Player.REPEAT_MODE_ONE -> stringResource(R.string.nowplaying_repeat_one)
                        else -> stringResource(R.string.nowplaying_repeat_off)
                    },
                    { scope.launch { connection.cycleRepeatMode() } },
                    active = state.repeatMode != Player.REPEAT_MODE_OFF,
                    enabled = remote == null,
                )
            }
            // 左端に端末、右端にキュー（共有は「…」のメニューにある）。weight(fill = false) と Spacer の weight を並べると余りが分かれて、
            // キューのボタンが真ん中寄りに出ていたので、SpaceBetween で両端に寄せる
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                // 端末名が出ているときは、名前まで含めて押せるようにする（アイコンだけが反応するのは分かりにくい）
                Row(
                    Modifier.weight(1f, fill = false).clip(RoundedCornerShape(24.dp)).clickable(onClick = onOpenDevices).padding(end = if (remote != null) 12.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconAction(R.drawable.ic_devices, stringResource(R.string.nowplaying_connected_devices), onOpenDevices, active = remote != null)
                    if (remote != null) {
                        Text(
                            stringResource(R.string.nowplaying_playing_on, remote.deviceLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconAction(R.drawable.ic_queue_music, pluralStringResource(R.plurals.nowplaying_queue_with_count, state.queue.size, state.queue.size), { showQueue = true })
            }
            // 次の曲（Windows・Spotify と同じく、操作の下に出す。押すとキューを開く）
            state.queue.getOrNull(state.currentIndex + 1)?.let { next ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { showQueue = true },
                    colors = CardDefaults.cardColors(containerColor = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, art, 0.35f)),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.nowplaying_next_up), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showQueue = true }) { Text(stringResource(R.string.nowplaying_open_queue)) }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        coil3.compose.AsyncImage(
                            model = next.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(next.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(next.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            val like by connection.likeState.collectAsState()
            like.message?.let { Text(it, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(stringResource(R.string.nowplaying_error, it), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall) }
            // 下に続く歌詞のカード（Spotify と同じ。押すと全面で開く）。歌詞が無い曲・探している間は出さない。広い画面の全面では右に出す
            if (!sideBySide && song != null && hasLyrics == true) {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, art, 0.5f)),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.nowplaying_lyrics), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconAction(R.drawable.ic_lyrics, stringResource(R.string.nowplaying_lyrics_open_full), { showLyrics = true })
                    }
                    LyricsPanel(song, display.clock, lyrics, Modifier.fillMaxWidth().height(320.dp).padding(horizontal = 8.dp))
                }
            }
        }
    }

    pickArtist?.let { artists ->
        ArtistPickerDialog(artists, onPick = { pickArtist = null; actions.openArtist(it) }, onDismiss = { pickArtist = null })
    }

    if (fullVideo && video && song != null) {
        FullScreenVideo(song, display.clock, videos, onClose = { fullVideo = false })
    }

    if (showLyrics && song != null) {
        ModalBottomSheet(onDismissRequest = { showLyrics = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text(state.title.orEmpty(), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(state.artist.orEmpty(), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LyricsPanel(song, display.clock, lyrics, Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(8.dp))
        }
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            QueueSheet(state, if (remote == null) controller else null, connection, actions.closing { showQueue = false }, remote = remote != null)
        }
    }
}

private const val SWIPE_PX = 120f

/** アーティストが複数いる曲で、どのアーティストのページを開くかを選ぶ。 */
@Composable
fun ArtistPickerDialog(
    artists: List<xyz.mooma0750.wave.core.music.ArtistRef>,
    onPick: (xyz.mooma0750.wave.core.music.ArtistRef) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nowplaying_pick_artist)) },
        text = {
            Column {
                artists.forEach { artist ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(artist) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_person), contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                        Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_close)) } },
    )
}

/** 映像の全画面（横向きにし、上下のバーを隠す）。閉じると元の向きに戻す。 */
@Composable
private fun FullScreenVideo(
    song: xyz.mooma0750.wave.core.music.Song,
    clock: LyricsClock?,
    videos: MusicVideos,
    onClose: () -> Unit,
) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (before != null) activity.requestedOrientation = before }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // ダイアログの窓でシステムバーを隠す
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            MusicVideoPanel(song, clock, videos, Modifier.fillMaxSize())
            IconAction(R.drawable.ic_fullscreen_exit, stringResource(R.string.nowplaying_video_exit_fullscreen), onClose, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
    }
}

/** ジャケットの下の、曲名から端末・キューの列までの高さ（ジャケットの大きさの上限を決める）。 */
private val CONTROLS_HEIGHT = 400.dp

/** 広い画面でも、操作の列が横に間延びしないように（Spotify と同じく中央の列に収める）。 */
private val MAX_CONTENT_WIDTH = 560.dp

/** 全面の再生画面で、歌詞を右に並べる幅。 */
private val SIDE_BY_SIDE_MIN_WIDTH = 840.dp

/** 何か再生している（ほかの端末の再生を映している）か。広い画面で右の再生の列を出すかに使う。 */
@Composable
fun rememberHasPlayback(
    connection: PlayerConnection,
    connect: ConnectV2Manager?,
): Boolean = rememberDisplay(connection, connect).state.title != null

/**
 * キューの画面（Spotify と同じ）。一番上に今の曲、その下に自分で足した曲（「キューを消去」でまとめて外せる）、
 * その下に元から流れる予定の曲。もう流れた曲は「履歴」に分ける。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    state: PlayerUiState,
    controller: MediaController?,
    connection: PlayerConnection,
    actions: SongActions,
    /** ほかの端末のキューを映している（曲を選ぶ・外す・並べ替える操作は無い）。 */
    remote: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var history by rememberSaveable { mutableStateOf(false) }
    // ドラッグの間は手元の並びを動かして見せ、指を離したときに1回だけ再生サービスへ送る
    val rows = remember(state.queue) { state.queue.toMutableStateList() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val current = state.currentIndex
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(selected = !history, onClick = { history = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text(stringResource(R.string.nowplaying_queue)) }
                SegmentedButton(selected = history, onClick = { history = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text(stringResource(R.string.nowplaying_history)) }
            }
        }
        @Composable
        fun songRow(
            index: Int,
            row: QueueRow,
            movable: Boolean,
        ) {
            val isCurrent = index == current
            val isDragged = dragging == row.itemId
            Row(
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                    .background(if (isDragged) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                    .then(if (remote || isCurrent) Modifier else Modifier.clickable { scope.launch { connection.jumpTo(row.itemId) } })
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                coil3.compose.AsyncImage(
                    model = row.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified)
                    Text(row.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                controller?.let { c ->
                    val position = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == row.itemId }
                    position?.let { Media3QueuePlayer.songOf(c.getMediaItemAt(it)) }?.let { song ->
                        SongMenuButton(song, actions, inQueue = true, extraItems = if (!isCurrent && !remote && index > current) listOf(stringResource(R.string.nowplaying_remove_from_queue) to { scope.launch { connection.remove(row.itemId) }; Unit }) else emptyList())
                    }
                }
                if (movable) {
                    // 並べ替えのつまみ（Spotify と同じく右端。上下にドラッグする）
                    Icon(
                        painterResource(R.drawable.ic_drag_handle),
                        contentDescription = stringResource(R.string.nowplaying_reorder),
                        modifier =
                            Modifier
                                .size(48.dp)
                                .padding(12.dp)
                                .pointerInput(row.itemId) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragging = row.itemId
                                            dragOffset = 0f
                                        },
                                        onDragEnd = {
                                            val id = dragging
                                            val to = rows.indexOfFirst { it.itemId == id }
                                            dragging = null
                                            dragOffset = 0f
                                            if (id != null && to > current && to != state.queue.indexOfFirst { it.itemId == id }) {
                                                scope.launch { connection.move(id, to) }
                                            }
                                        },
                                        onDragCancel = {
                                            dragging = null
                                            dragOffset = 0f
                                        },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val id = dragging ?: return@detectDragGestures
                                        val from = rows.indexOfFirst { it.itemId == id }
                                        val height = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.size?.toFloat() ?: return@detectDragGestures
                                        // 半分を越えたら隣と入れ替える（今の曲より上には行かない）
                                        if (dragOffset > height / 2 && from < rows.lastIndex) {
                                            rows.add(from + 1, rows.removeAt(from))
                                            dragOffset -= height
                                        } else if (dragOffset < -height / 2 && from - 1 > current) {
                                            rows.add(from - 1, rows.removeAt(from))
                                            dragOffset += height
                                        }
                                    }
                                },
                    )
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }
        }

        @Composable
        fun header(
            text: String,
            trailing: @Composable () -> Unit = {},
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                trailing()
            }
        }
        if (history) {
            // もう流れた曲（新しい順）。押すとその曲に戻る
            val played = (0 until current.coerceAtMost(rows.size)).reversed()
            LazyColumn(Modifier.fillMaxWidth()) {
                if (played.isEmpty()) item { Text(stringResource(R.string.nowplaying_history_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(played.toList(), key = { rows[it].itemId }) { index -> songRow(index, rows[index], movable = false) }
            }
        } else {
            // 今の曲の直後に続く、自分で足した曲のかたまり
            var userEnd = current + 1
            while (userEnd < rows.size && rows[userEnd].userAdded) userEnd++
            LazyColumn(Modifier.fillMaxWidth(), state = listState) {
                if (current in rows.indices) {
                    item(key = "h-now") { header(stringResource(R.string.nowplaying_now_playing)) }
                    item(key = rows[current].itemId) { songRow(current, rows[current], movable = false) }
                }
                if (userEnd > current + 1) {
                    item(key = "h-user") {
                        header(stringResource(R.string.nowplaying_next_in_queue)) {
                            if (!remote) TextButton(onClick = { scope.launch { connection.clearUserQueue() } }) { Text(stringResource(R.string.nowplaying_clear_queue)) }
                        }
                    }
                    items((current + 1 until userEnd).toList(), key = { rows[it].itemId }) { index -> songRow(index, rows[index], movable = !remote) }
                }
                if (userEnd < rows.size) {
                    item(key = "h-rest") { header(stringResource(R.string.nowplaying_next_from_source)) }
                    items((userEnd until rows.size).toList(), key = { rows[it].itemId }) { index -> songRow(index, rows[index], movable = !remote) }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

private fun formatTime(ms: Long): String = Durations.ofMillis(ms)

/**
 * 画面の操作先: 手元の再生か、ほかの端末の再生か（Spotify と同じく、ほかの端末で鳴っていればそれを映し、操作はその端末への命令になる）。
 */
private class Display(
    val state: PlayerUiState,
    val song: xyz.mooma0750.wave.core.music.Song?,
    val controller: MediaController?,
    val remote: ConnectV2Manager.RemotePlayback?,
    private val connect: ConnectV2Manager?,
    val clock: LyricsClock?,
) {
    fun togglePlay() {
        val playing = remote?.playing
        if (playing != null) connect?.control(if (playing) ControlAction.Pause else ControlAction.Play) else controller?.togglePlayPause()
    }

    fun next() {
        if (remote != null) connect?.control(ControlAction.Next) else controller?.seekToNextMediaItem()
    }

    fun previous() {
        if (remote != null) connect?.control(ControlAction.Previous) else controller?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        if (remote != null) connect?.control(ControlAction.Seek, positionMs) else controller?.seekTo(positionMs)
    }
}

@Composable
private fun rememberDisplay(
    connection: PlayerConnection,
    connect: ConnectV2Manager?,
): Display {
    val (local, controller) = rememberPlayerUiState(connection)
    val remote = connect?.remotePlayback?.collectAsState()?.value
    if (remote == null) {
        val clock = remember(controller) { controller?.asLyricsClock() }
        return Display(local, Media3QueuePlayer.songOf(controller?.currentMediaItem), controller, null, connect, clock)
    }
    // 位置は1秒ごとに進める（相手のカーソルの位置と、取った時刻から見積もる）
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(remote) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(500)
        }
    }
    val current = remote.current
    val state =
        PlayerUiState(
            title = current.title,
            artist = current.artists.joinToString(", ") { it.name },
            isPlaying = remote.playing,
            positionMs = remote.positionAt(now),
            durationMs = (current.durationSeconds ?: 0) * 1000L,
            queue = remote.items.mapIndexed { index, song -> QueueRow(remote.itemIds[index], song.title, song.artists.joinToString(", ") { it.name }, song.thumbnailUrl) },
            currentIndex = remote.currentIndex,
            hasNext = remote.currentIndex < remote.items.size - 1 || remote.repeat != HandoffRepeat.Off,
            hasPrevious = true,
            repeatMode =
                when (remote.repeat) {
                    HandoffRepeat.One -> Player.REPEAT_MODE_ONE
                    HandoffRepeat.All -> Player.REPEAT_MODE_ALL
                    HandoffRepeat.Off -> Player.REPEAT_MODE_OFF
                },
        )
    val latest by androidx.compose.runtime.rememberUpdatedState(remote)
    val clock =
        remember(connect) {
            object : LyricsClock {
                override fun positionMs(): Long = latest.positionAt(android.os.SystemClock.elapsedRealtime())

                override fun isPlaying(): Boolean = latest.playing

                override fun seekTo(positionMs: Long) {
                    connect?.control(ControlAction.Seek, positionMs)
                }
            }
        }
    return Display(state, current, null, remote, connect, clock)
}
