package xyz.mooma0750.wave.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.mooma0750.wave.core.music.AlbumRef
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.ui.Durations

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onSongClick: (Song) -> Unit,
    actions: SongActions,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    /** 検索の履歴（語・アーティスト・アルバム）。 */
    history: SearchHistoryStore,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val historyEntries by history.entries.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    Scaffold(
        topBar = { TopAppBar(title = { Text("検索") }, navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } }) },
        bottomBar = bottomBar,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true,
                placeholder = { Text("曲名・アーティスト") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(onSearch = {
                        keyboard?.hide()
                        viewModel.search()
                    }),
            )
            // 検索欄が空のときは、前の検索の結果ではなく、最近の検索（語と、そこから開いたアーティスト・アルバム）を出す（Spotify と同じ）
            val showHistory = state.query.isBlank()
            if (!showHistory) {
                when {
                    state.failure != null -> Text(failureMessage(state.failure), Modifier.padding(16.dp))
                    state.empty -> Text("見つかりませんでした", Modifier.padding(16.dp))
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (showHistory && historyEntries.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text("最近の検索", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { history.clear() }) { Text("すべて消す") }
                        }
                    }
                    items(historyEntries, key = { entry -> entryKey(entry) }) { entry ->
                        HistoryRow(
                            entry,
                            onClick = {
                                when (entry) {
                                    is SearchHistoryEntry.Query -> {
                                        keyboard?.hide()
                                        viewModel.searchFor(entry.text)
                                    }
                                    is SearchHistoryEntry.Artist -> actions.openArtist(ArtistRef(entry.name, entry.browseId))
                                    is SearchHistoryEntry.Album -> actions.openAlbum(AlbumRef(entry.title, entry.browseId))
                                }
                            },
                            onRemove = { history.remove(entry) },
                        )
                    }
                }
                if (!showHistory) {
                    itemsIndexed(state.songs, key = { index, song -> "$index-${song.videoId}" }) { index, song ->
                        SongRow(song, onClick = { onSongClick(song) }) { SongMenuButton(song, actions) }
                        if (index == state.songs.lastIndex && state.continuation != null) {
                            LaunchedEffect(state.songs.size) { viewModel.loadMore() }
                        }
                    }
                }
                if (state.loading) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        coil3.compose.AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(song.subtitle.takeIf { it.isNotEmpty() }, song.durationSeconds?.let { Durations.ofSeconds(it.toLong()) }).joinToString(" • "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        trailing()
    }
}

fun failureMessage(kind: FailureKind?): String =
    when (kind) {
        FailureKind.NotLoggedIn -> "ログインしていません"
        FailureKind.Expired -> "再ログインが必要です"
        FailureKind.Suspect -> "ログイン状態を確認しています。少し待ってからもう一度試してください"
        FailureKind.Network -> "通信できませんでした"
        FailureKind.Http -> "サーバーから応答がありませんでした"
        FailureKind.Parse -> "応答を読み取れませんでした"
        FailureKind.AccountUnverified -> "書き込み先のアカウントを確かめられませんでした（しばらくして直らなければ、ログインし直してください）"
        null -> ""
    }

private fun entryKey(entry: SearchHistoryEntry): String =
    when (entry) {
        is SearchHistoryEntry.Query -> "q-${entry.text}"
        is SearchHistoryEntry.Artist -> "ar-${entry.browseId}"
        is SearchHistoryEntry.Album -> "al-${entry.browseId}"
    }

/** 履歴の1行。語・アーティスト・アルバムの区別が分かるようにする。 */
@Composable
private fun HistoryRow(
    entry: SearchHistoryEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val (label, kind) =
        when (entry) {
            is SearchHistoryEntry.Query -> entry.text to "検索した語"
            is SearchHistoryEntry.Artist -> entry.name to "アーティスト"
            is SearchHistoryEntry.Album -> entry.title to "アルバム"
        }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kind, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRemove) { Text("消す") }
    }
}
