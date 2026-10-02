package xyz.mooma0750.wave.search

import xyz.mooma0750.wave.ui.IconAction
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.ui.BackButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import xyz.mooma0750.wave.core.music.AlbumSummary
import xyz.mooma0750.wave.core.music.ArtistSummary
import xyz.mooma0750.wave.core.music.SearchSuggestions
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
        topBar = { TopAppBar(title = { Text("検索") }, navigationIcon = { BackButton(onBack) }) },
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
            when {
                state.query.isBlank() -> HistoryList(historyEntries, history, onQuery = { keyboard?.hide(); viewModel.searchFor(it) }, actions)
                state.typing -> SuggestionList(state.suggestions, onQuery = { keyboard?.hide(); viewModel.searchFor(it) }, onSongClick, actions)
                else -> Results(state, viewModel, onSongClick, actions)
            }
        }
    }
}

@Composable
private fun HistoryList(
    entries: List<SearchHistoryEntry>,
    history: SearchHistoryStore,
    onQuery: (String) -> Unit,
    actions: SongActions,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (entries.isEmpty()) return@LazyColumn
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("最近の検索", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { history.clear() }) { Text("すべて消す") }
            }
        }
        items(entries, key = { entry -> entryKey(entry) }) { entry ->
            HistoryRow(
                entry,
                onClick = {
                    when (entry) {
                        is SearchHistoryEntry.Query -> onQuery(entry.text)
                        is SearchHistoryEntry.Artist -> actions.openArtist(ArtistRef(entry.name, entry.browseId))
                        is SearchHistoryEntry.Album -> actions.openAlbum(AlbumRef(entry.title, entry.browseId))
                    }
                },
                onRemove = { history.remove(entry) },
            )
        }
    }
}

/** 打ち込み中の候補。語の候補を先に、そのまま開けるアーティスト・アルバム・曲を後に出す。 */
@Composable
private fun SuggestionList(
    suggestions: SearchSuggestions?,
    onQuery: (String) -> Unit,
    onSongClick: (Song) -> Unit,
    actions: SongActions,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (suggestions == null) return@LazyColumn
        items(suggestions.queries, key = { "q-$it" }) { query ->
            Row(
                Modifier.fillMaxWidth().clickable { onQuery(query) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        items(suggestions.artists, key = { "ar-${it.browseId}" }) { artist -> ArtistRow(artist) { actions.openArtist(ArtistRef(artist.name, artist.browseId)) } }
        items(suggestions.albums, key = { "al-${it.browseId}" }) { album -> AlbumRow(album) { actions.openAlbum(AlbumRef(album.title, album.browseId)) } }
        items(suggestions.songs, key = { "s-${it.videoId}" }) { song ->
            SongRow(song, onClick = { onSongClick(song) }) { SongMenuButton(song, actions) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Results(
    state: SearchUiState,
    viewModel: SearchViewModel,
    onSongClick: (Song) -> Unit,
    actions: SongActions,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchTab.entries.forEach { tab ->
            FilterChip(selected = state.tab == tab, onClick = { viewModel.selectTab(tab) }, label = { Text(tabLabel(tab)) })
        }
    }
    when {
        state.failure != null -> Text(failureMessage(state.failure), Modifier.padding(16.dp))
        state.empty -> Text("見つかりませんでした", Modifier.padding(16.dp))
    }
    val openArtist = { artist: ArtistSummary -> actions.openArtist(ArtistRef(artist.name, artist.browseId)) }
    val openAlbum = { album: AlbumSummary -> actions.openAlbum(AlbumRef(album.title, album.browseId)) }
    val songRow: @Composable (Song) -> Unit = { song -> SongRow(song, onClick = { onSongClick(song) }) { SongMenuButton(song, actions) } }
    LazyColumn(Modifier.fillMaxSize()) {
        when (state.tab) {
            SearchTab.All -> {
                // 種類ごとに少しずつ。続きは種類の絞り込みで見る
                val sections =
                    listOf(SearchTab.Songs, SearchTab.Albums, SearchTab.Artists)
                        .let { if (state.artistFirst) listOf(SearchTab.Artists) + it.minus(SearchTab.Artists) else it }
                sections.forEach { tab ->
                    val count =
                        when (tab) {
                            SearchTab.Songs -> state.songs.items.size
                            SearchTab.Albums -> state.albums.items.size
                            else -> state.artists.items.size
                        }
                    if (count == 0) return@forEach
                    item(key = "h-$tab") { SectionHeader(tabLabel(tab), more = count > PREVIEW_COUNT) { viewModel.selectTab(tab) } }
                    when (tab) {
                        SearchTab.Songs -> items(state.songs.items.take(PREVIEW_COUNT), key = { "s-${it.videoId}" }) { songRow(it) }
                        SearchTab.Albums -> items(state.albums.items.take(PREVIEW_COUNT), key = { "al-${it.browseId}" }) { AlbumRow(it) { openAlbum(it) } }
                        else -> items(state.artists.items.take(PREVIEW_COUNT), key = { "ar-${it.browseId}" }) { ArtistRow(it) { openArtist(it) } }
                    }
                }
            }
            SearchTab.Songs -> pagedItems(state.songs, { "s-$it-" }, { viewModel.loadMore(SearchTab.Songs) }) { songRow(it) }
            SearchTab.Albums -> pagedItems(state.albums, { "al-$it-" }, { viewModel.loadMore(SearchTab.Albums) }) { AlbumRow(it) { openAlbum(it) } }
            SearchTab.Artists -> pagedItems(state.artists, { "ar-$it-" }, { viewModel.loadMore(SearchTab.Artists) }) { ArtistRow(it) { openArtist(it) } }
        }
        if (state.loading) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
    }
}

/** 種類で絞ったときの一覧。最後まで来たら続きを読む。 */
private fun <T> LazyListScope.pagedItems(
    list: ResultList<T>,
    keyPrefix: (Int) -> String,
    loadMore: () -> Unit,
    row: @Composable (T) -> Unit,
) {
    itemsIndexed(list.items, key = { index, _ -> keyPrefix(index) }) { index, item ->
        row(item)
        if (index == list.items.lastIndex && list.continuation != null) {
            LaunchedEffect(list.items.size) { loadMore() }
        }
    }
    if (list.loadingMore) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
    if (list.failure != null && list.items.isEmpty()) item { Text(failureMessage(list.failure), Modifier.padding(16.dp)) }
}

@Composable
private fun SectionHeader(
    title: String,
    more: Boolean,
    onMore: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (more) TextButton(onClick = onMore) { Text("すべて表示") }
    }
}

@Composable
private fun AlbumRow(
    album: AlbumSummary,
    onClick: () -> Unit,
) {
    CardRow(album.thumbnailUrl, album.title, album.subtitle, RoundedCornerShape(4.dp), onClick)
}

@Composable
private fun ArtistRow(
    artist: ArtistSummary,
    onClick: () -> Unit,
) {
    CardRow(artist.thumbnailUrl, artist.name, artist.subtitle, CircleShape, onClick)
}

@Composable
private fun CardRow(
    thumbnailUrl: String?,
    title: String,
    subtitle: String?,
    shape: Shape,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        coil3.compose.AsyncImage(
            model = thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(shape),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun tabLabel(tab: SearchTab): String =
    when (tab) {
        SearchTab.All -> "すべて"
        SearchTab.Songs -> "曲"
        SearchTab.Albums -> "アルバム"
        SearchTab.Artists -> "アーティスト"
    }

private const val PREVIEW_COUNT = 4

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kind, style = MaterialTheme.typography.bodySmall)
        }
        IconAction(R.drawable.ic_close, "消す", onRemove)
    }
}
