package xyz.mooma0750.wave.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.AlbumRef
import xyz.mooma0750.wave.core.music.AlbumSummary
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.ArtistSummary
import xyz.mooma0750.wave.core.music.SearchSuggestions
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.ui.BackButton
import xyz.mooma0750.wave.ui.Durations
import xyz.mooma0750.wave.ui.IconAction

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
    // 開いたらすぐ打てるように（Spotify と同じ）。結果を見ている途中で戻ってきたときは開かない
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (state.query.isBlank()) focus.requestFocus() }
    Scaffold(bottomBar = bottomBar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                TextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    trailingIcon = { if (state.query.isNotEmpty()) IconAction(R.drawable.ic_close, stringResource(R.string.search_clear), { viewModel.onQueryChange("") }) },
                    shape = MaterialTheme.shapes.small,
                    colors =
                        TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions =
                        KeyboardActions(onSearch = {
                            keyboard?.hide()
                            viewModel.search()
                        }),
                )
            }
            // 検索から再生した曲・開いたアーティスト・アルバムは、画像と補足の行も付けて履歴に残す（Spotify と同じ）
            val playAndRemember = { song: Song ->
                history.add(SearchHistoryEntry.Song(song.videoId, song.title, song.artists.joinToString(", ") { it.name }.ifEmpty { null }, song.thumbnailUrl))
                onSongClick(song)
            }
            val opener =
                Opener(
                    artist = { artist ->
                        history.add(SearchHistoryEntry.Artist(artist.name, artist.browseId, artist.subtitle, artist.thumbnailUrl))
                        actions.openArtist(ArtistRef(artist.name, artist.browseId))
                    },
                    album = { album ->
                        history.add(SearchHistoryEntry.Album(album.title, album.browseId, album.subtitle, album.thumbnailUrl))
                        actions.openAlbum(AlbumRef(album.title, album.browseId))
                    },
                )
            // 検索欄が空のときは、前の検索の結果ではなく、最近の検索を出す（Spotify と同じ）
            when {
                state.query.isBlank() -> HistoryList(historyEntries, history, onQuery = { keyboard?.hide(); viewModel.searchFor(it) }, onSongClick, actions)
                state.typing -> SuggestionList(state.suggestions, onQuery = { keyboard?.hide(); viewModel.searchFor(it) }, playAndRemember, opener, actions)
                else -> Results(state, viewModel, playAndRemember, opener, actions)
            }
        }
    }
}

/** 検索の結果からアーティスト・アルバムを開く（履歴に残してから開く）。 */
private class Opener(
    val artist: (ArtistSummary) -> Unit,
    val album: (AlbumSummary) -> Unit,
)

@Composable
private fun HistoryList(
    entries: List<SearchHistoryEntry>,
    history: SearchHistoryStore,
    onQuery: (String) -> Unit,
    onSongClick: (Song) -> Unit,
    actions: SongActions,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (entries.isEmpty()) return@LazyColumn
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.search_recent_searches), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { history.clear() }) { Text(stringResource(R.string.search_clear_all)) }
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
                        is SearchHistoryEntry.Song -> {
                            // 押した曲を履歴の先頭に上げてから再生する
                            history.add(entry)
                            onSongClick(Song(entry.videoId, entry.title, listOfNotNull(entry.subtitle?.let { ArtistRef(it, null) }), null, null, entry.thumbnailUrl))
                        }
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
    opener: Opener,
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
        items(suggestions.artists, key = { "ar-${it.browseId}" }) { artist -> ArtistRow(artist) { opener.artist(artist) } }
        items(suggestions.albums, key = { "al-${it.browseId}" }) { album -> AlbumRow(album) { opener.album(album) } }
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
    opener: Opener,
    actions: SongActions,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchTab.entries.forEach { tab ->
            FilterChip(selected = state.tab == tab, onClick = { viewModel.selectTab(tab) }, label = { Text(tabLabel(tab)) })
        }
    }
    when {
        state.failure != null -> Text(failureMessage(state.failure), Modifier.padding(16.dp))
        state.empty -> Text(stringResource(R.string.search_not_found), Modifier.padding(16.dp))
    }
    val openArtist = opener.artist
    val openAlbum = opener.album
    val songRow: @Composable (Song) -> Unit = { song -> SongRow(song, onClick = { onSongClick(song) }) { SongMenuButton(song, actions) } }
    LazyColumn(Modifier.fillMaxSize()) {
        when (state.tab) {
            SearchTab.All -> {
                // Spotify の「トップの検索結果」: 語がアーティスト名ならそのアーティスト、そうでなければ最初の曲
                val topArtist = state.artists.items.firstOrNull()?.takeIf { state.artistFirst }
                val topSong = state.songs.items.firstOrNull()
                if (topArtist != null || topSong != null) {
                    item(key = "top") {
                        SectionHeader(stringResource(R.string.search_top_result), more = false) {}
                        if (topArtist != null) {
                            TopResult(topArtist.thumbnailUrl, topArtist.name, stringResource(R.string.search_kind_artist), CircleShape) { openArtist(topArtist) }
                        } else if (topSong != null) {
                            TopResult(topSong.thumbnailUrl, topSong.title, listOf(stringResource(R.string.search_kind_song), topSong.artists.joinToString(", ") { it.name }).filter { it.isNotEmpty() }.joinToString(" • "), MaterialTheme.shapes.extraSmall) {
                                onSongClick(topSong)
                            }
                        }
                    }
                }
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
        if (state.loading) item { LoadingIndicator(Modifier.padding(16.dp)) }
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
    if (list.loadingMore) item { LoadingIndicator(Modifier.padding(16.dp)) }
    if (list.failure != null && list.items.isEmpty()) item { Text(failureMessage(list.failure), Modifier.padding(16.dp)) }
}

/** トップの検索結果の大きなカード。 */
@Composable
private fun TopResult(
    thumbnailUrl: String?,
    title: String,
    detail: String,
    shape: Shape,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            coil3.compose.AsyncImage(model = thumbnailUrl, contentDescription = null, modifier = Modifier.size(96.dp).clip(shape), contentScale = ContentScale.Crop)
            Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    more: Boolean,
    onMore: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (more) TextButton(onClick = onMore) { Text(stringResource(R.string.search_show_all)) }
    }
}

@Composable
private fun AlbumRow(
    album: AlbumSummary,
    onClick: () -> Unit,
) {
    CardRow(album.thumbnailUrl, album.title, album.subtitle, MaterialTheme.shapes.extraSmall, onClick)
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

@Composable
private fun tabLabel(tab: SearchTab): String =
    when (tab) {
        SearchTab.All -> stringResource(R.string.search_tab_all)
        SearchTab.Songs -> stringResource(R.string.search_tab_songs)
        SearchTab.Albums -> stringResource(R.string.search_tab_albums)
        SearchTab.Artists -> stringResource(R.string.search_tab_artists)
    }

private const val PREVIEW_COUNT = 4

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    /** アルバムの中では画像を出さない（Spotify と同じ）。 */
    showArtwork: Boolean = true,
    /** 左に出す番号（アーティストの人気の曲）。 */
    number: Int? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        number?.let { Text("$it", Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge) }
        if (showArtwork) {
            coil3.compose.AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.extraSmall),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                // アルバムの中では、アルバム名と長さは出さずアーティストだけ（Spotify と同じ）
                if (showArtwork) {
                    listOfNotNull(song.subtitle.takeIf { it.isNotEmpty() }, song.durationSeconds?.let { Durations.ofSeconds(it.toLong()) }).joinToString(" • ")
                } else {
                    song.artists.joinToString(", ") { it.name }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 高く評価した曲には印を付ける（Spotify と同じ）
        xyz.mooma0750.wave.ui.LikedBadge(song.videoId)
        trailing()
    }
}

@Composable
fun failureMessage(kind: FailureKind?): String =
    when (kind) {
        FailureKind.NotLoggedIn -> stringResource(R.string.search_failure_not_logged_in)
        FailureKind.Expired -> stringResource(R.string.search_failure_expired)
        FailureKind.Suspect -> stringResource(R.string.search_failure_suspect)
        FailureKind.Network -> stringResource(R.string.search_failure_network)
        FailureKind.Http -> stringResource(R.string.search_failure_http)
        FailureKind.Parse -> stringResource(R.string.search_failure_parse)
        FailureKind.AccountUnverified -> stringResource(R.string.search_failure_account_unverified)
        null -> ""
    }

private fun entryKey(entry: SearchHistoryEntry): String =
    when (entry) {
        is SearchHistoryEntry.Query -> "q-${entry.text}"
        is SearchHistoryEntry.Artist -> "ar-${entry.browseId}"
        is SearchHistoryEntry.Album -> "al-${entry.browseId}"
        is SearchHistoryEntry.Song -> "s-${entry.videoId}"
    }

/** 履歴の1行。画像（アーティストは丸、語は検索のアイコン）と、種類の分かる補足の行を出す。 */
@Composable
private fun HistoryRow(
    entry: SearchHistoryEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val (label, detail) =
        when (entry) {
            is SearchHistoryEntry.Query -> entry.text to null
            is SearchHistoryEntry.Artist -> entry.name to stringResource(R.string.search_kind_artist)
            is SearchHistoryEntry.Album -> entry.title to listOfNotNull(stringResource(R.string.search_kind_album), entry.subtitle).joinToString(" • ")
            is SearchHistoryEntry.Song -> entry.title to listOfNotNull(stringResource(R.string.search_kind_song), entry.subtitle).joinToString(" • ")
        }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = if (entry is SearchHistoryEntry.Artist) CircleShape else MaterialTheme.shapes.extraSmall
        Box(Modifier.size(56.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (entry.thumbnailUrl != null) {
                coil3.compose.AsyncImage(model = entry.thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                val icon =
                    when (entry) {
                        is SearchHistoryEntry.Query -> R.drawable.ic_search
                        is SearchHistoryEntry.Artist -> R.drawable.ic_person
                        is SearchHistoryEntry.Album -> R.drawable.ic_album
                        is SearchHistoryEntry.Song -> R.drawable.ic_music_note
                    }
                Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconAction(R.drawable.ic_close, stringResource(R.string.search_clear), onRemove)
    }
}
