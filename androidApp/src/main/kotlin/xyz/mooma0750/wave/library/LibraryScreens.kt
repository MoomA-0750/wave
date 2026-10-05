package xyz.mooma0750.wave.library

import xyz.mooma0750.wave.core.music.PlaylistSort
import xyz.mooma0750.wave.core.music.PlaylistOrder
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Box
import xyz.mooma0750.wave.ui.IconAction
import xyz.mooma0750.wave.ui.IconTextButton
import xyz.mooma0750.wave.ui.ButtonStyle
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.ui.BackButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.saveable.rememberSaveable
import xyz.mooma0750.wave.ui.CollectionScreen
import xyz.mooma0750.wave.ui.DetailText
import xyz.mooma0750.wave.ui.MenuIcon
import xyz.mooma0750.wave.ui.PlayControls
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.search.SongMenuButton
import xyz.mooma0750.wave.search.SongRow
import xyz.mooma0750.wave.search.failureMessage

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
                    placeholder = { Text("プレイリスト内を検索") },
                    singleLine = true,
                    leadingIcon = { MenuIcon(R.drawable.ic_search) },
                    trailingIcon = { if (state.query.isNotEmpty()) IconAction(R.drawable.ic_close, "消す", { viewModel.setQuery("") }) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(52.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                SortRow(state.order, state.sortable, viewModel::setOrder)
            }
        },
        details = {
            summary.subtitle?.let { DetailText(it) }
            DetailText(if (state.continuation != null) "${state.songs.size}曲以上" else "${state.songs.size}曲")
        },
        actions = {
            QueueMenu(enabled = state.songs.isNotEmpty(), onQueue = { next -> onQueue(state.songs, next) })
        },
        play =
            PlayControls(shuffle, { shuffle = it }, enabled = state.songs.isNotEmpty()) {
                if (shuffle) onShufflePlay(state.songs, state.continuation) else onPlay(state.songs, 0, state.continuation)
            },
    ) {
        // カスタムの降順は、全部読んでから逆に並べる。読んでいる途中の（逆になる前の）並びは出さない
        if (state.order.reversedLocally && state.loadingAll) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("全部読んでから並べ替えています…", Modifier.padding(start = 12.dp))
                }
            }
            return@CollectionScreen
        }
        state.failure?.let { item { Text(failureMessage(it), Modifier.padding(16.dp)) } }
        if (!state.loading && state.failure == null && state.songs.isEmpty()) item { Text("曲がありません", Modifier.padding(16.dp)) }
        // 絞り込み中は、元の並びでの位置を持ったまま出す（再生も「外す」も元の位置に当てる）
        val shown = SongFilter.matchingIndices(state.songs, state.query)
        if (state.query.isNotBlank()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("${shown.size}件", style = MaterialTheme.typography.bodySmall)
                    if (state.loadingAll) Text("残りのページを読んでいます…", style = MaterialTheme.typography.bodySmall)
                    if (state.incomplete) Text("全部は読めませんでした（この先に一致する曲があるかもしれません）", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (shown.isEmpty() && !state.loadingAll) item { Text("見つかりませんでした", Modifier.padding(16.dp)) }
        }
        items(shown, key = { index -> "$index-${state.songs[index].videoId}" }) { index ->
            val song = state.songs[index]
            val setVideoId = state.setVideoIds.getOrNull(index)
            SongRow(song, onClick = { onPlay(state.songs, index, state.continuation) }) {
                SongMenuButton(
                    song,
                    actions,
                    extraItems = if (state.editable && setVideoId != null) listOf("このプレイリストから外す" to { onRemove(song, setVideoId) }) else emptyList(),
                )
            }
            // 絞り込み中は、続きを読むのは loadAll に任せる
            if (state.query.isBlank() && index == state.songs.lastIndex && state.continuation != null) {
                LaunchedEffect(state.songs.size) { viewModel.loadMore() }
            }
        }
        if (state.loading) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
    }
}

/** 丸ごとキューに入れるメニュー（Spotify の「⋯」）。 */
@Composable
fun QueueMenu(
    enabled: Boolean,
    onQueue: (next: Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(R.drawable.ic_more_vert, "その他", { open = true }, enabled = enabled)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("次に再生") }, leadingIcon = { MenuIcon(R.drawable.ic_playlist_play) }, onClick = {
                open = false
                onQueue(true)
            })
            DropdownMenuItem(text = { Text("キューに追加") }, leadingIcon = { MenuIcon(R.drawable.ic_playlist_add) }, onClick = {
                open = false
                onQueue(false)
            })
        }
    }
}

/** 並べ方の選択と、昇順・降順の切り替え。サーバーで並べ替えられないプレイリストはカスタムだけ。 */
@Composable
private fun SortRow(
    order: PlaylistOrder,
    sortable: Boolean,
    onChange: (PlaylistOrder) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // Spotify の「並べ替え」: 押すと並べ方の一覧。選んでいるものをもう一度押すと昇順・降順が入れ替わる
    Box {
        FilledTonalButton(onClick = { open = true }, modifier = Modifier.padding(start = 8.dp).height(52.dp), shape = RoundedCornerShape(8.dp)) { Text("並べ替え") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("並べ替え", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            PlaylistSort.entries.filter { it == PlaylistSort.Custom || sortable }.forEach { sort ->
                val selected = sort == order.sort
                DropdownMenuItem(
                    text = { Text(sortLabel(sort), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    trailingIcon = {
                        if (selected) MenuIcon(if (order.descending) R.drawable.ic_arrow_downward else R.drawable.ic_arrow_upward)
                    },
                    onClick = {
                        open = false
                        onChange(if (selected) order.copy(descending = !order.descending) else order.copy(sort = sort))
                    },
                )
            }
        }
    }
}

private fun sortLabel(sort: PlaylistSort): String =
    when (sort) {
        PlaylistSort.Custom -> "カスタム"
        PlaylistSort.Added -> "追加した日"
        PlaylistSort.Published -> "リリース（公開日）"
        PlaylistSort.Title -> "曲名"
        PlaylistSort.Artist -> "アーティスト"
        PlaylistSort.Album -> "アルバム"
    }

/** 曲をプレイリストに追加する（仮）。追加先は自分が編集できるプレイリストだけ。 */
@Composable
fun AddToPlaylistDialog(
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit,
    onPick: (PlaylistSummary) -> Unit,
    onCreate: (title: String) -> Unit,
    /** 追加する曲。どのプレイリストに入っているかを確かめるのに使う。 */
    song: Song,
    membership: PlaylistMembership,
    playlistsVersion: Int,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val editable = state.playlists.filter { it.editable }
    // すでに入っているプレイリストを出す（YouTube Music に一覧で返す口が無いので、1つずつ読む）
    val check = remember(membership, scope) { PlaylistMembership.Check(membership, scope) }
    val statuses by check.status.collectAsStateWithLifecycle()
    LaunchedEffect(check, song.videoId, editable.map { it.playlistId }, playlistsVersion) {
        if (editable.isNotEmpty()) check.start(editable.map { it.playlistId }, song.videoId, playlistsVersion)
    }
    DisposableEffect(check) { onDispose { check.cancel() } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "新しいプレイリスト（非公開）" else "プレイリストに追加") },
        text = {
            if (creating) {
                androidx.compose.material3.OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    placeholder = { Text("プレイリストの名前") },
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    item { IconTextButton(R.drawable.ic_add, "新しいプレイリストを作る", { creating = true }) }
                    when {
                        state.failure != null -> item { Text(failureMessage(state.failure)) }
                        state.loading -> item { CircularProgressIndicator(Modifier.padding(8.dp)) }
                    }
                    itemsIndexed(editable, key = { _, playlist -> playlist.playlistId }) { _, playlist ->
                        val status = statuses[playlist.playlistId]
                        Column(Modifier.fillMaxWidth().clickable { onPick(playlist) }.padding(vertical = 12.dp)) {
                            Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            when (status) {
                                PlaylistMembership.Status.Checking -> Text("確認中…", style = MaterialTheme.typography.bodySmall)
                                PlaylistMembership.Status.In -> Text("この曲は入っています", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                PlaylistMembership.Status.Unknown -> Text("入っているか分かりません", style = MaterialTheme.typography.bodySmall)
                                PlaylistMembership.Status.NotIn, null -> Unit
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) TextButton(onClick = { onCreate(title.trim()) }, enabled = title.isNotBlank()) { Text("作って追加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
