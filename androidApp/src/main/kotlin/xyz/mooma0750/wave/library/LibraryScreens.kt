package xyz.mooma0750.wave.library

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
import androidx.compose.material3.Button
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
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.search.SongMenuButton
import xyz.mooma0750.wave.search.SongRow
import xyz.mooma0750.wave.search.failureMessage

/** ホームに出すライブラリの一覧（高く評価した曲 + プレイリスト）。 */
fun LazyListScope.libraryItems(
    state: LibraryUiState,
    onOpen: (PlaylistSummary) -> Unit,
    onOpenHistory: () -> Unit,
) {
    item {
        Text("ライブラリ", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
    }
    item {
        PlaylistRow(PlaylistSummary(MusicRepository.LIKED_SONGS, "高く評価した曲", null, null), onOpen)
    }
    item {
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpenHistory).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("再生履歴", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        }
    }
    when {
        state.failure != null -> item { Text(failureMessage(state.failure), Modifier.padding(16.dp)) }
        state.loading -> item { CircularProgressIndicator(Modifier.padding(16.dp)) }
    }
    itemsIndexed(state.playlists, key = { _, playlist -> playlist.playlistId }) { _, playlist -> PlaylistRow(playlist, onOpen) }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistSummary,
    onOpen: (PlaylistSummary) -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable { onOpen(playlist) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        playlist.subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    viewModel: PlaylistViewModel,
    onPlay: (songs: List<Song>, index: Int, continuation: String?) -> Unit,
    onShufflePlay: (songs: List<Song>, continuation: String?) -> Unit,
    /** 読み込んだぶんを丸ごとキューに入れる。next = 次に再生（false は最後に追加）。 */
    onQueue: (songs: List<Song>, next: Boolean) -> Unit,
    actions: SongActions,
    onRemove: (song: Song, setVideoId: String) -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title ?: "プレイリスト", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        Button(onClick = { onPlay(state.songs, 0, state.continuation) }, enabled = state.songs.isNotEmpty()) { Text("すべて再生") }
                        TextButton(onClick = { onShufflePlay(state.songs, state.continuation) }, enabled = state.songs.isNotEmpty()) { Text("シャッフル再生") }
                    }
                    Row {
                        TextButton(onClick = { onQueue(state.songs, true) }, enabled = state.songs.isNotEmpty()) { Text("次に再生") }
                        TextButton(onClick = { onQueue(state.songs, false) }, enabled = state.songs.isNotEmpty()) { Text("キューに追加") }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { viewModel.setQuery(it) },
                    label = { Text("この中を探す") },
                    singleLine = true,
                    trailingIcon = { if (state.query.isNotEmpty()) TextButton(onClick = { viewModel.setQuery("") }) { Text("消す") } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
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
    val check = remember(membership, scope) { membership.Check(scope) }
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
                    item { TextButton(onClick = { creating = true }) { Text("新しいプレイリストを作る") } }
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
