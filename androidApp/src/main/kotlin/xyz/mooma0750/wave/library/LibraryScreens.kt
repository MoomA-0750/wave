package xyz.mooma0750.wave.library

import xyz.mooma0750.wave.core.music.AddToPlaylistResult
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistOrder
import xyz.mooma0750.wave.core.music.PlaylistSort
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.search.SongMenuButton
import xyz.mooma0750.wave.search.SongRow
import xyz.mooma0750.wave.search.failureMessage
import xyz.mooma0750.wave.ui.BackButton
import xyz.mooma0750.wave.ui.ButtonStyle
import xyz.mooma0750.wave.ui.CollectionScreen
import xyz.mooma0750.wave.ui.DetailText
import xyz.mooma0750.wave.ui.IconAction
import xyz.mooma0750.wave.ui.IconTextButton
import xyz.mooma0750.wave.ui.MenuIcon
import xyz.mooma0750.wave.ui.PlayControls

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    viewModel: PlaylistViewModel,
    /** 開く元の一覧のカード（ジャケットと補足の行に使う）。 */
    summary: PlaylistSummary,
    onPlay: (songs: List<Song>, index: Int, continuation: String?) -> Unit,
    onShufflePlay: (songs: List<Song>, continuation: String?) -> Unit,
    /** 読み込んだぶんを丸ごとキューに入れる。next = 次に再生（false は最後に追加）。 */
    onQueue: (songs: List<Song>, next: Boolean) -> Unit,
    actions: SongActions,
    onRemove: (song: Song, setVideoId: String) -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var shuffle by rememberSaveable { mutableStateOf(false) }
    // 曲の種類の絞り込み（曲・MV・投稿者の動画）。絞っている間は、再生も絞った曲だけにする
    var kind by rememberSaveable { mutableStateOf(KindFilter.All) }
    val kindSongs = if (kind == KindFilter.All) state.songs else state.songs.filter(kind::matches)
    val playContinuation = if (kind == KindFilter.All) state.continuation else null
    CollectionScreen(
        title = state.title ?: summary.title,
        coverUrl = summary.thumbnailUrl ?: state.songs.firstOrNull()?.thumbnailUrl,
        onBack = onBack,
        top = {
            // Spotify のプレイリストの上の「この中を探す」と「並べ替え」
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { viewModel.setQuery(it) },
                    placeholder = { Text(stringResource(R.string.library_search_in_playlist)) },
                    singleLine = true,
                    leadingIcon = { MenuIcon(R.drawable.ic_search) },
                    trailingIcon = { if (state.query.isNotEmpty()) IconAction(R.drawable.ic_close, stringResource(R.string.library_clear), { viewModel.setQuery("") }) },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f).height(52.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                SortRow(state.order, state.sortable, viewModel::setOrder)
            }
            // 種類のチップ（動画や歌ってみたと、ふつうの曲を分けて出す）
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                items(KindFilter.entries.toList()) { option ->
                    androidx.compose.material3.FilterChip(
                        selected = kind == option,
                        onClick = {
                            kind = if (kind == option) KindFilter.All else option
                            // 種類を絞るときは、全部読んでから絞る（読んだぶんだけだと少なく見える）
                            if (kind != KindFilter.All) viewModel.loadAll()
                        },
                        label = { Text(kindLabel(option)) },
                    )
                }
            }
        },
        details = {
            // 一覧の補足（「作った人 • 曲数」）があればそれを、無ければ読んだ曲数を出す
            DetailText(summary.subtitle ?: if (state.continuation != null) pluralStringResource(R.plurals.library_song_count_more, state.songs.size, state.songs.size) else pluralStringResource(R.plurals.library_song_count, state.songs.size, state.songs.size))
        },
        actions = {
            QueueMenu(enabled = kindSongs.isNotEmpty(), onQueue = { next -> onQueue(kindSongs, next) })
        },
        play =
            PlayControls(shuffle, { shuffle = it }, enabled = kindSongs.isNotEmpty()) {
                if (shuffle) onShufflePlay(kindSongs, playContinuation) else onPlay(kindSongs, 0, playContinuation)
            },
    ) {
        // カスタムの降順・長さは、全部読んでから手元で並べる。読んでいる途中の（並べる前の）並びは出さない
        if (state.order.arrangedLocally && state.loadingAll) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator()
                    Text(stringResource(R.string.library_sorting_after_loading_all), Modifier.padding(start = 12.dp))
                }
            }
            return@CollectionScreen
        }
        state.failure?.let { item { Text(failureMessage(it), Modifier.padding(16.dp)) } }
        if (!state.loading && state.failure == null && state.songs.isEmpty()) item { Text(stringResource(R.string.library_no_songs), Modifier.padding(16.dp)) }
        // 絞り込み中は、元の並びでの位置を持ったまま出す（再生も「外す」も元の位置に当てる）
        val shown = SongFilter.matchingIndices(state.songs, state.query, kind)
        if (state.query.isNotBlank() || kind != KindFilter.All) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(pluralStringResource(R.plurals.library_match_count, shown.size, shown.size), style = MaterialTheme.typography.bodySmall)
                    if (state.loadingAll) Text(stringResource(R.string.library_loading_remaining_pages), style = MaterialTheme.typography.bodySmall)
                    if (state.incomplete) Text(stringResource(R.string.library_search_incomplete), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (shown.isEmpty() && !state.loadingAll) item { Text(stringResource(R.string.library_not_found), Modifier.padding(16.dp)) }
        }
        items(shown, key = { index -> "$index-${state.songs[index].videoId}" }) { index ->
            val song = state.songs[index]
            val setVideoId = state.setVideoIds.getOrNull(index)
            SongRow(song, onClick = { if (kind == KindFilter.All) onPlay(state.songs, index, state.continuation) else onPlay(kindSongs, kindSongs.indexOf(song).coerceAtLeast(0), null) }) {
                SongMenuButton(
                    song,
                    actions,
                    extraItems = if (state.editable && setVideoId != null) listOf(stringResource(R.string.library_remove_from_this_playlist) to { onRemove(song, setVideoId) }) else emptyList(),
                )
            }
            // 絞り込み中は、続きを読むのは loadAll に任せる
            if (state.query.isBlank() && kind == KindFilter.All && index == state.songs.lastIndex && state.continuation != null) {
                LaunchedEffect(state.songs.size) { viewModel.loadMore() }
            }
        }
        if (state.loading) item { LoadingIndicator(Modifier.padding(16.dp)) }
    }
}

@Composable
private fun kindLabel(kind: KindFilter): String =
    when (kind) {
        KindFilter.All -> stringResource(R.string.library_kind_all)
        KindFilter.Songs -> stringResource(R.string.library_kind_songs)
        KindFilter.MusicVideos -> stringResource(R.string.library_kind_music_videos)
        KindFilter.UserVideos -> stringResource(R.string.library_kind_user_videos)
    }

/** 丸ごとキューに入れるメニュー（Spotify の「⋯」）。 */
@Composable
fun QueueMenu(
    enabled: Boolean,
    onQueue: (next: Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(R.drawable.ic_more_vert, stringResource(R.string.library_more_options), { open = true }, enabled = enabled)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.library_play_next)) }, leadingIcon = { MenuIcon(R.drawable.ic_playlist_play) }, onClick = {
                open = false
                onQueue(true)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.library_add_to_queue)) }, leadingIcon = { MenuIcon(R.drawable.ic_playlist_add) }, onClick = {
                open = false
                onQueue(false)
            })
        }
    }
}

/** 並べ方の選択と、昇順・降順の切り替え。サーバーで並べ替えられないプレイリストはカスタムと長さ（手元で並べる）だけ。 */
@Composable
private fun SortRow(
    order: PlaylistOrder,
    sortable: Boolean,
    onChange: (PlaylistOrder) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // Spotify の「並べ替え」: 押すと並べ方の一覧。選んでいるものをもう一度押すと昇順・降順が入れ替わる
    Box {
        FilledTonalButton(onClick = { open = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(start = 8.dp).height(52.dp)) { Text(stringResource(R.string.library_sort)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(stringResource(R.string.library_sort), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            PlaylistSort.entries.filter { it == PlaylistSort.Custom || it == PlaylistSort.Duration || sortable }.forEach { sort ->
                val selected = sort == order.sort
                DropdownMenuItem(
                    text = { Text(sortLabel(sort), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    trailingIcon = {
                        if (selected) MenuIcon(if (order.descending) R.drawable.ic_arrow_downward else R.drawable.ic_arrow_upward)
                    },
                    onClick = {
                        open = false
                        // 同じ並べ方はもう一度押すと向きを変える。別の並べ方に替えたら既定の向き（追加した日は新しい順、ほかは昇順。Windows と同じ）
                        onChange(if (selected) order.copy(descending = !order.descending) else PlaylistOrder(sort, descending = sort == PlaylistSort.Added))
                    },
                )
            }
        }
    }
}

@Composable
private fun sortLabel(sort: PlaylistSort): String =
    when (sort) {
        PlaylistSort.Custom -> stringResource(R.string.library_sort_custom)
        PlaylistSort.Added -> stringResource(R.string.library_sort_date_added)
        PlaylistSort.Published -> stringResource(R.string.library_sort_release_date)
        PlaylistSort.Title -> stringResource(R.string.library_sort_title)
        PlaylistSort.Artist -> stringResource(R.string.library_sort_artist)
        PlaylistSort.Album -> stringResource(R.string.library_sort_album)
        PlaylistSort.Duration -> stringResource(R.string.library_sort_duration)
    }

/**
 * 曲をプレイリストに追加する。追加先は自分が編集できるプレイリストだけ。
 * 押しても閉じず、続けてほかのプレイリストにも足せる（閉じるのは「完了」）。入っているプレイリストには右に印を出す。
 */
@Composable
fun AddToPlaylistDialog(
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit,
    /** 足す。結果を onResult で返す（どのスレッドからでもよい）。受け付けなければ false。 */
    onPick: (playlist: PlaylistSummary, onResult: (AddToPlaylistResult) -> Unit) -> Boolean,
    onCreate: (title: String) -> Unit,
    /** 追加する曲。どのプレイリストに入っているかを確かめるのに使う。 */
    song: Song,
    membership: PlaylistMembership,
    /** 「高く評価した曲」に入っているか（分からなければ null で行を出さない）。Spotify と同じく、ここで評価を外せる。 */
    liked: Boolean? = null,
    onToggleLiked: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 開くたびに裏で読み直す（前の一覧はすぐ出したまま。ほかの端末で作ったプレイリストも出す）
    LaunchedEffect(viewModel, song.videoId) { viewModel.refresh() }
    var creating by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val editable = state.playlists.filter { it.editable }
    // すでに入っているプレイリストを出す（YouTube Music に一覧で返す口が無いので、1つずつ読む）
    val check = remember(membership, scope) { PlaylistMembership.Check(membership, scope) }
    val statuses by check.status.collectAsStateWithLifecycle()
    // 足している途中のプレイリスト
    var adding by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(check, song.videoId, editable.map { it.playlistId }) {
        if (editable.isNotEmpty()) check.start(editable.map { it.playlistId }, song.videoId)
    }
    DisposableEffect(check) { onDispose { check.cancel() } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) stringResource(R.string.library_new_playlist_private) else stringResource(R.string.library_add_to_playlist)) },
        text = {
            if (creating) {
                androidx.compose.material3.OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.library_playlist_name_hint)) },
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    item { IconTextButton(R.drawable.ic_add, stringResource(R.string.library_new_playlist), { creating = true }) }
                    if (liked != null) {
                        item {
                            Row(Modifier.fillMaxWidth().clickable(onClick = onToggleLiked).padding(vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(stringResource(R.string.library_liked_songs_row), Modifier.weight(1f), maxLines = 1)
                                androidx.compose.material3.Icon(
                                    androidx.compose.ui.res.painterResource(if (liked) R.drawable.ic_check_circle else R.drawable.ic_add_circle),
                                    contentDescription = null,
                                    tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    when {
                        state.failure != null -> item { Text(failureMessage(state.failure)) }
                        state.loading -> item { LoadingIndicator(Modifier.padding(8.dp)) }
                    }
                    itemsIndexed(editable, key = { _, playlist -> playlist.playlistId }) { _, playlist ->
                        val id = playlist.playlistId
                        val status = statuses[id]
                        val busy = id in adding
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = status != PlaylistMembership.Status.In && !busy) {
                                    val accepted =
                                        onPick(playlist) { result ->
                                            scope.launch {
                                                adding = adding - id
                                                if (result == AddToPlaylistResult.Added || result == AddToPlaylistResult.AlreadyInPlaylist) check.markIn(id)
                                            }
                                        }
                                    if (accepted) adding = adding + id
                                }.padding(vertical = 12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                when {
                                    busy -> Text(stringResource(R.string.library_membership_adding), style = MaterialTheme.typography.bodySmall)
                                    status == PlaylistMembership.Status.Unknown -> Text(stringResource(R.string.library_membership_unknown), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            when {
                                busy || status == PlaylistMembership.Status.Checking -> LoadingIndicator(Modifier.size(24.dp))
                                // 入っているプレイリストには印（高く評価した曲の行と同じ✓）
                                status == PlaylistMembership.Status.In ->
                                    androidx.compose.material3.Icon(
                                        androidx.compose.ui.res.painterResource(R.drawable.ic_check_circle),
                                        contentDescription = stringResource(R.string.library_membership_in),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(onClick = { onCreate(title.trim()) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.library_create_and_add)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_done)) }
            }
        },
        dismissButton = { if (creating) TextButton(onClick = { creating = false }) { Text(stringResource(R.string.library_back)) } },
    )
}
