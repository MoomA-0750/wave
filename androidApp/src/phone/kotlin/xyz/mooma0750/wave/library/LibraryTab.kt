package xyz.mooma0750.wave.library

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.AlbumSummary
import xyz.mooma0750.wave.core.music.ArtistSummary
import xyz.mooma0750.wave.core.music.HomeItem
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.search.failureMessage
import xyz.mooma0750.wave.settings.AccountInfo
import xyz.mooma0750.wave.settings.Avatar
import xyz.mooma0750.wave.ui.IconAction
import xyz.mooma0750.wave.ui.MenuIcon

/**
 * マイライブラリ（並びは Spotify と同じ）: アカウントのアイコン・見出し・作成のボタン、種類のチップ、
 * 並べ方と表示の切り替え、先頭に「高く評価した曲」、続いてプレイリスト・アーティスト・アルバム。
 */
@Composable
fun LibraryTabScreen(
    viewModel: LibraryTabViewModel,
    account: AccountInfo,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
    onOpenArtist: (ArtistSummary) -> Unit,
    onOpenAlbum: (AlbumSummary) -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<LibraryFilter?>(null) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.Recents) }
    var grid by rememberSaveable { mutableStateOf(false) }
    // Spotify の「マイライブラリを検索」: 虫めがねを押すと入力欄に広がる
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val closeSearch = {
        searching = false
        query = ""
    }
    BackHandler(enabled = searching, onBack = closeSearch)
    var creating by remember { mutableStateOf(false) }
    // 「高く評価した曲」は並べ方に関わらず先頭に留める（Spotify の「お気に入りの曲」と同じ）。一覧にあればその画像を使う
    val serverLiked = state.entries.firstNotNullOfOrNull { (it as? LibraryEntry.Playlist)?.playlist?.takeIf { p -> p.playlistId == MusicRepository.LIKED_SONGS } }
    val entries = LibraryOrder.arrange(state, filter, sort, query).filterNot { it is LibraryEntry.Playlist && it.playlist.playlistId == MusicRepository.LIKED_SONGS }
    val liked = PlaylistSummary(MusicRepository.LIKED_SONGS, stringResource(R.string.library_liked_songs), stringResource(R.string.library_liked_songs_subtitle), serverLiked?.thumbnailUrl)
    val showLiked = (filter == null || filter == LibraryFilter.Playlists) && LibraryOrder.matches(liked.title, liked.subtitle, query)
    val open = { entry: LibraryEntry ->
        when (entry) {
            is LibraryEntry.Playlist -> onOpenPlaylist(entry.playlist)
            is LibraryEntry.Artist -> onOpenArtist(entry.artist)
            is LibraryEntry.Album -> onOpenAlbum(entry.album)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(account, 32.dp)
            Text(stringResource(R.string.library_title), Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.headlineSmallEmphasized)
            IconAction(R.drawable.ic_add, stringResource(R.string.library_create_playlist), { creating = true })
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryFilter.entries) { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = if (filter == option) null else option },
                    label = {
                        Text(
                            when (option) {
                                LibraryFilter.Playlists -> stringResource(R.string.library_filter_playlists)
                                LibraryFilter.Artists -> stringResource(R.string.library_filter_artists)
                                LibraryFilter.Albums -> stringResource(R.string.library_filter_albums)
                            },
                        )
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.library_search_in_library)) },
                    singleLine = true,
                    leadingIcon = { MenuIcon(R.drawable.ic_search) },
                    trailingIcon = { if (query.isNotEmpty()) IconAction(R.drawable.ic_close, stringResource(R.string.library_clear), { query = "" }) },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f).padding(start = 12.dp).height(52.dp).focusRequester(focus),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = closeSearch) { Text(stringResource(R.string.library_cancel)) }
            } else {
                IconAction(R.drawable.ic_search, stringResource(R.string.library_search_in_library), { searching = true })
                SortButton(sort) { sort = it }
                Spacer(Modifier.weight(1f))
                IconAction(if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view, if (grid) stringResource(R.string.library_view_as_list) else stringResource(R.string.library_view_as_grid), { grid = !grid })
            }
        }
        when {
            state.loading -> LoadingIndicator(Modifier.padding(16.dp))
            state.failure != null -> Text(failureMessage(state.failure), Modifier.padding(16.dp))
        }
        if (grid) {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showLiked) item { GridCell(LibraryEntry.Playlist(liked)) { onOpenPlaylist(liked) } }
                items(entries, key = { it.key }) { entry -> GridCell(entry) { open(entry) } }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (showLiked) item { LibraryRow(LibraryEntry.Playlist(liked)) { onOpenPlaylist(liked) } }
                items(entries, key = { it.key }) { entry -> LibraryRow(entry) { open(entry) } }
            }
        }
    }

    if (creating) {
        var title by remember { mutableStateOf("") }
        val defaultName = stringResource(R.string.library_default_playlist_name)
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.library_name_playlist_title)) },
            text = { OutlinedTextField(title, { title = it }, singleLine = true, placeholder = { Text(defaultName) }) },
            confirmButton = {
                TextButton(onClick = {
                    onCreatePlaylist(title.ifBlank { defaultName })
                    creating = false
                }) { Text(stringResource(R.string.library_create)) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
}

@Composable
private fun SortButton(
    sort: LibrarySort,
    onChange: (LibrarySort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_swap_vert), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(librarySortLabel(sort))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Text(stringResource(R.string.library_sort), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            LibrarySort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(librarySortLabel(option)) },
                    trailingIcon = { if (option == sort) MenuIcon(R.drawable.ic_check_circle) },
                    onClick = {
                        onChange(option)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun librarySortLabel(option: LibrarySort): String =
    when (option) {
        LibrarySort.Recents -> stringResource(R.string.library_sort_recents)
        LibrarySort.Added -> stringResource(R.string.library_sort_recently_added)
        LibrarySort.Alphabetical -> stringResource(R.string.library_sort_alphabetical)
    }

@Composable
private fun subtitleOf(entry: LibraryEntry): String =
    when (entry) {
        is LibraryEntry.Playlist -> entry.playlist.subtitle ?: stringResource(R.string.library_kind_playlist)
        is LibraryEntry.Artist -> stringResource(R.string.library_kind_artist)
        is LibraryEntry.Album -> entry.album.subtitle ?: stringResource(R.string.library_kind_album)
    }

private fun thumbnailOf(entry: LibraryEntry): String? =
    when (entry) {
        is LibraryEntry.Playlist -> entry.playlist.thumbnailUrl
        is LibraryEntry.Artist -> entry.artist.thumbnailUrl
        is LibraryEntry.Album -> entry.album.thumbnailUrl
    }

@Composable
private fun Thumbnail(
    entry: LibraryEntry,
    modifier: Modifier,
) {
    val shape = if (entry is LibraryEntry.Artist) CircleShape else MaterialTheme.shapes.extraSmall
    val url = thumbnailOf(entry)
    if (url == null) {
        // 「高く評価した曲」など画像の無いもの（Spotify の紫のタイルの代わりに、テーマの色のタイル）
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_favorite), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    } else {
        coil3.compose.AsyncImage(model = url, contentDescription = null, modifier = modifier.clip(shape), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun LibraryRow(
    entry: LibraryEntry,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumbnail(entry, Modifier.size(64.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(subtitleOf(entry), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GridCell(
    entry: LibraryEntry,
    onClick: () -> Unit,
) {
    Column(Modifier.clickable(onClick = onClick)) {
        Thumbnail(entry, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(entry.title, Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(subtitleOf(entry), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
