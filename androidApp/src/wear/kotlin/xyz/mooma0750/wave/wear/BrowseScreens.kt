package xyz.mooma0750.wave.wear

import android.app.Activity
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import android.app.RemoteInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.artist.AlbumViewModel
import xyz.mooma0750.wave.artist.ArtistPlay
import xyz.mooma0750.wave.artist.ArtistViewModel
import xyz.mooma0750.wave.core.music.AlbumPage
import xyz.mooma0750.wave.core.music.AlbumRef
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.HomeItem
import xyz.mooma0750.wave.core.music.NewRelease
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.home.HomeFeedViewModel
import xyz.mooma0750.wave.home.HomeItemLook
import xyz.mooma0750.wave.home.openHomeItem
import xyz.mooma0750.wave.home.shelfTitle
import xyz.mooma0750.wave.library.HistoryRow
import xyz.mooma0750.wave.library.HistoryViewModel
import xyz.mooma0750.wave.library.LibraryEntry
import xyz.mooma0750.wave.library.LibraryOrder
import xyz.mooma0750.wave.library.LibrarySort
import xyz.mooma0750.wave.library.LibraryTabViewModel
import xyz.mooma0750.wave.library.PlaylistViewModel
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.search.MoodCategoryViewModel
import xyz.mooma0750.wave.search.MoodsViewModel
import xyz.mooma0750.wave.search.SearchHistoryEntry
import xyz.mooma0750.wave.search.SearchHistoryStore
import xyz.mooma0750.wave.search.SearchViewModel
import xyz.mooma0750.wave.search.failureMessage

/** 一覧の曲。押すと onPlay、長押しで曲の画面。 */
private fun TransformingLazyColumnScope.songRows(
    look: RowLook,
    keyPrefix: String,
    songs: List<Song>,
    liked: Set<String>,
    actions: SongActions,
    onPlay: (Int) -> Unit,
) {
    itemsIndexed(songs, key = { index, song -> "$keyPrefix-$index-${song.videoId}" }) { index, song ->
        SongRow(look, song, liked = song.videoId in liked, onMenu = { actions.openSong(song) }) { onPlay(index) }
    }
}

/** ホームの棚（スマホのホームと同じ中身）。 */
@Composable
fun FeedScreen(
    feed: HomeFeedViewModel,
    actions: SongActions,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
    newReleases: List<NewRelease>,
    onOpenReleases: () -> Unit,
) {
    val state by feed.state.collectAsStateWithLifecycle()
    ListScreen(stringResource(R.string.nav_home)) { look ->
        if (state.chips.isNotEmpty()) {
            item(key = "chips") {
                EntryRow(look, state.selected?.title ?: stringResource(R.string.home_chip_all), null, null, icon = R.drawable.ic_sort) {
                    // 押すたびに次の絞り込みへ（時計では横に並べられないので、順に回す）
                    val all = listOf(null) + state.chips
                    feed.select(all[(all.indexOf(state.selected) + 1) % all.size])
                }
            }
        }
        if (newReleases.isNotEmpty() && state.selected == null) {
            item(key = "releases") { EntryRow(look, stringResource(R.string.releases_home_title), newReleases.first().title, newReleases.first().thumbnailUrl, onClick = onOpenReleases) }
        }
        if (state.recent.isNotEmpty()) {
            item(key = "recent-h") { MessageRow(look, stringResource(R.string.home_recently_played)) }
            items(state.recent, key = { "recent-${it.hashCode()}" }) { item -> HomeRow(look, item, actions, onOpenPlaylist) }
        }
        state.shelves.forEachIndexed { shelfIndex, shelf ->
            item(key = "shelf-$shelfIndex") { MessageRow(look, shelfTitle(shelf)) }
            items(shelf.items, key = { "s-$shelfIndex-${it.hashCode()}" }) { item -> HomeRow(look, item, actions, onOpenPlaylist) }
        }
        when {
            state.loading || state.loadingMore -> item(key = "loading") { LoadingRow(look) }
            state.failure != null -> item(key = "failure") { ActionRow(look, R.drawable.ic_refresh, stringResource(R.string.home_retry), failureMessage(state.failure), onClick = feed::refresh) }
            state.continuation != null -> item(key = "more") { ActionRow(look, R.drawable.ic_add, stringResource(R.string.home_more_recommendations), onClick = feed::loadMore) }
        }
    }
}

@Composable
private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope.HomeRow(
    look: RowLook,
    item: HomeItem,
    actions: SongActions,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
) {
    val itemLook = HomeItemLook.of(item)
    EntryRow(look, itemLook.title, itemLook.subtitle, itemLook.thumbnailUrl, round = item is HomeItem.ArtistItem) { openHomeItem(item, onOpenPlaylist, actions) }
}

/** 新作（スマホのホームの「登録したアーティストの新作」）。 */
@Composable
fun ReleasesScreen(
    releases: List<NewRelease>,
    actions: SongActions,
) {
    ListScreen(stringResource(R.string.releases_home_title)) { look ->
        if (releases.isEmpty()) item { MessageRow(look, stringResource(R.string.wear_no_releases)) }
        items(releases, key = { "${it.albumBrowseId}-${it.videoId}" }) { release ->
            EntryRow(look, release.title, release.artistName, release.thumbnailUrl) {
                val album = release.albumBrowseId
                if (album != null) actions.openAlbum(AlbumRef(release.title, album)) else actions.openArtist(ArtistRef(release.artistName, release.artistId))
            }
        }
    }
}

/** 文字の入力（声・キーボード）を開く。 */
@Composable
fun rememberTextInput(
    label: String,
    onText: (String) -> Unit,
): () -> Unit {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(INPUT_KEY)?.toString()?.trim()
            if (!text.isNullOrEmpty()) onText(text)
        }
    return {
        val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val input =
            RemoteInput
                .Builder(INPUT_KEY)
                .setLabel(label)
                .wearableExtender {
                    setEmojisAllowed(false)
                    setInputActionType(EditorInfo.IME_ACTION_SEARCH)
                }.build()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        launcher.launch(intent)
    }
}

private const val INPUT_KEY = "text"

/** 検索（スマホの検索と同じ。語は声かキーボードで入れる）。曲を押すと再生、長押しで曲の画面。 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    history: SearchHistoryStore,
    liked: Set<String>,
    actions: SongActions,
    onPlaySong: (Song) -> Unit,
    onOpenBrowse: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entries by history.entries.collectAsStateWithLifecycle()
    val ask = rememberTextInput(stringResource(R.string.search_hint)) { viewModel.searchFor(it) }
    // 初めて開いたら、すぐに入力を出す（時計では押す回数を減らす）
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!asked && state.submitted == null) {
            asked = true
            ask()
        }
    }
    ListScreen(
        title = state.submitted ?: stringResource(R.string.search_title),
        edgeButton = { MainEdgeButton(R.drawable.ic_search, stringResource(R.string.search_hint), onClick = ask) },
    ) { look ->
        val submitted = state.submitted
        if (submitted == null) {
            item(key = "browse") { ActionRow(look, R.drawable.ic_grid_view, stringResource(R.string.wear_browse), onClick = onOpenBrowse) }
            if (entries.isNotEmpty()) item(key = "recent-h") { MessageRow(look, stringResource(R.string.search_recent_searches)) }
            items(entries, key = { "h-${it.hashCode()}" }) { entry ->
                when (entry) {
                    is SearchHistoryEntry.Query -> EntryRow(look, entry.text, null, null, icon = R.drawable.ic_history) { viewModel.searchFor(entry.text) }
                    is SearchHistoryEntry.Artist -> EntryRow(look, entry.name, stringResource(R.string.search_kind_artist), entry.thumbnailUrl, round = true) { actions.openArtist(ArtistRef(entry.name, entry.browseId)) }
                    is SearchHistoryEntry.Album -> EntryRow(look, entry.title, stringResource(R.string.search_kind_album), entry.thumbnailUrl) { actions.openAlbum(AlbumRef(entry.title, entry.browseId)) }
                    is SearchHistoryEntry.Song -> EntryRow(look, entry.title, entry.subtitle, entry.thumbnailUrl) { viewModel.searchFor(entry.title) }
                }
            }
            return@ListScreen
        }
        if (state.loading) {
            item(key = "loading") { LoadingRow(look) }
            return@ListScreen
        }
        state.failure?.let { failure -> item(key = "failure") { ActionRow(look, R.drawable.ic_refresh, stringResource(R.string.search_retry), failureMessage(failure)) { viewModel.searchFor(submitted) } } }
        if (state.empty) item(key = "none") { MessageRow(look, stringResource(R.string.search_not_found)) }
        val artists = state.artists.items.take(if (state.artistFirst) 3 else 2)
        if (state.artistFirst) {
            items(artists, key = { "a-${it.browseId}" }) { artist ->
                EntryRow(look, artist.name, stringResource(R.string.search_kind_artist), artist.thumbnailUrl, round = true) {
                    history.add(SearchHistoryEntry.Artist(artist.name, artist.browseId))
                    actions.openArtist(ArtistRef(artist.name, artist.browseId))
                }
            }
        }
        if (state.songs.items.isNotEmpty()) item(key = "songs-h") { MessageRow(look, stringResource(R.string.search_tab_songs)) }
        itemsIndexed(state.songs.items, key = { index, song -> "s-$index-${song.videoId}" }) { _, song ->
            SongRow(look, song, liked = song.videoId in liked, onMenu = { actions.openSong(song) }) { onPlaySong(song) }
        }
        if (state.songs.continuation != null) {
            item(key = "songs-more") { ActionRow(look, R.drawable.ic_add, stringResource(R.string.search_show_all), enabled = !state.songs.loadingMore) { viewModel.loadMore(xyz.mooma0750.wave.search.SearchTab.Songs) } }
        }
        if (!state.artistFirst && artists.isNotEmpty()) {
            item(key = "artists-h") { MessageRow(look, stringResource(R.string.search_tab_artists)) }
            items(artists, key = { "a-${it.browseId}" }) { artist ->
                EntryRow(look, artist.name, stringResource(R.string.search_kind_artist), artist.thumbnailUrl, round = true) {
                    history.add(SearchHistoryEntry.Artist(artist.name, artist.browseId))
                    actions.openArtist(ArtistRef(artist.name, artist.browseId))
                }
            }
        }
        if (state.albums.items.isNotEmpty()) item(key = "albums-h") { MessageRow(look, stringResource(R.string.search_tab_albums)) }
        items(state.albums.items, key = { "al-${it.browseId}" }) { album ->
            EntryRow(look, album.title, album.subtitle, album.thumbnailUrl) {
                history.add(SearchHistoryEntry.Album(album.title, album.browseId))
                actions.openAlbum(AlbumRef(album.title, album.browseId))
            }
        }
        item(key = "clear") { ActionRow(look, R.drawable.ic_close, stringResource(R.string.search_clear), onClick = viewModel::clear) }
    }
}

/** ジャンル・気分（スマホの検索の「すべてを見る」と同じ）。 */
@Composable
fun BrowseScreen(
    viewModel: MoodsViewModel,
    onOpen: (String, String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ListScreen(stringResource(R.string.wear_browse)) { look ->
        if (state.loading) item { LoadingRow(look) }
        state.failure?.let { item { ActionRow(look, R.drawable.ic_refresh, stringResource(R.string.search_retry), failureMessage(it), onClick = viewModel::refresh) } }
        state.sections.forEachIndexed { index, section ->
            item(key = "h-$index") { MessageRow(look, section.title) }
            items(section.categories, key = { "c-$index-${it.params}" }) { category -> EntryRow(look, category.title, null, null) { onOpen(category.title, category.params) } }
        }
    }
}

/** 1つのジャンル・気分のページ。 */
@Composable
fun MoodScreen(
    title: String,
    viewModel: MoodCategoryViewModel,
    actions: SongActions,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ListScreen(title) { look ->
        if (state.loading) item { LoadingRow(look) }
        state.failure?.let { item { ActionRow(look, R.drawable.ic_refresh, stringResource(R.string.search_retry), failureMessage(it), onClick = viewModel::refresh) } }
        state.shelves.forEachIndexed { shelfIndex, shelf ->
            item(key = "shelf-$shelfIndex") { MessageRow(look, shelfTitle(shelf)) }
            items(shelf.items, key = { "s-$shelfIndex-${it.hashCode()}" }) { item -> HomeRow(look, item, actions, onOpenPlaylist) }
        }
    }
}

/** ライブラリ（スマホの「ライブラリ」と同じ。最近の順。高く評価した曲を先頭に）。 */
@Composable
fun LibraryScreen(
    viewModel: LibraryTabViewModel,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
    onOpenArtist: (String, String) -> Unit,
    onOpenAlbum: (String, String) -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(LibrarySort.Recents) }
    val create = rememberTextInput(stringResource(R.string.library_playlist_name_hint), onCreatePlaylist)
    ListScreen(stringResource(R.string.library_title), edgeButton = { MainEdgeButton(R.drawable.ic_add, stringResource(R.string.library_create_playlist), onClick = create) }) { look ->
        item(key = "sort") {
            ActionRow(
                look,
                R.drawable.ic_sort,
                stringResource(R.string.library_sort),
                stringResource(
                    when (sort) {
                        LibrarySort.Recents -> R.string.library_sort_recents
                        LibrarySort.Added -> R.string.library_sort_recently_added
                        LibrarySort.Alphabetical -> R.string.library_sort_alphabetical
                    },
                ),
            ) { sort = LibrarySort.entries[(sort.ordinal + 1) % LibrarySort.entries.size] }
        }
        if (state.loading && state.entries.isEmpty()) item { LoadingRow(look) }
        state.failure?.let { item { ActionRow(look, R.drawable.ic_refresh, stringResource(R.string.search_retry), failureMessage(it), onClick = viewModel::refresh) } }
        items(LibraryOrder.arrange(state, null, sort), key = { it.key }) { entry ->
            when (entry) {
                is LibraryEntry.Playlist -> EntryRow(look, entry.playlist.title, entry.playlist.subtitle, entry.playlist.thumbnailUrl) { onOpenPlaylist(entry.playlist) }
                is LibraryEntry.Artist -> EntryRow(look, entry.artist.name, entry.artist.subtitle, entry.artist.thumbnailUrl, round = true) { onOpenArtist(entry.artist.browseId, entry.artist.name) }
                is LibraryEntry.Album -> EntryRow(look, entry.album.title, entry.album.subtitle, entry.album.thumbnailUrl) { onOpenAlbum(entry.album.browseId, entry.album.title) }
            }
        }
    }
}

/** プレイリスト（スマホと同じ。再生・シャッフル・まとめてキューへ・自分のプレイリストなら曲を外す）。 */
@Composable
fun PlaylistScreen(
    summary: PlaylistSummary,
    viewModel: PlaylistViewModel,
    liked: Set<String>,
    actions: SongActions,
    onPlay: (List<Song>, Int, String?, Boolean) -> Unit,
    onQueue: (List<Song>, Boolean) -> Unit,
    /** 曲の画面を開く（自分のプレイリストなら、外すための項目の ID も渡す）。 */
    onSongMenu: (Song, String?) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val songs = state.songs
    ListScreen(
        state.title ?: summary.title,
        edgeButton = { MainEdgeButton(R.drawable.ic_play_arrow, stringResource(R.string.ui_play)) { if (songs.isNotEmpty()) onPlay(songs, 0, state.continuation, false) } },
    ) { look ->
        if (songs.isNotEmpty()) {
            item(key = "shuffle") { ActionRow(look, R.drawable.ic_shuffle, stringResource(R.string.wear_shuffle_play)) { onPlay(songs, 0, state.continuation, true) } }
            item(key = "next") { ActionRow(look, R.drawable.ic_playlist_play, stringResource(R.string.search_play_next)) { onQueue(songs, true) } }
            item(key = "queue") { ActionRow(look, R.drawable.ic_queue_music, stringResource(R.string.search_add_to_queue)) { onQueue(songs, false) } }
        }
        if (state.query.isNotEmpty()) item(key = "query") { ActionRow(look, R.drawable.ic_close, state.query, stringResource(R.string.library_clear)) { viewModel.setQuery("") } }
        if (state.loading && songs.isEmpty()) item(key = "loading") { LoadingRow(look) }
        state.failure?.let { item(key = "failure") { MessageRow(look, failureMessage(it)) } }
        if (!state.loading && songs.isEmpty() && state.failure == null) item(key = "empty") { MessageRow(look, stringResource(R.string.library_no_songs)) }
        itemsIndexed(songs, key = { index, song -> "p-$index-${song.videoId}" }) { index, song ->
            val setVideoId = state.setVideoIds.getOrNull(index)
            SongRow(
                look,
                song,
                liked = song.videoId in liked,
                onMenu = { onSongMenu(song, setVideoId?.takeIf { state.editable }) },
            ) { onPlay(songs, index, state.continuation, false) }
        }
        if (state.continuation != null) item(key = "more") { ActionRow(look, R.drawable.ic_add, stringResource(R.string.wear_load_more), onClick = viewModel::loadMore) }
    }
}

/** アーティスト（スマホと同じ。人気の曲・すべての曲・高く評価した曲・アルバム・新作・登録）。 */
@Composable
fun ArtistScreen(
    name: String,
    viewModel: ArtistViewModel,
    liked: Set<String>,
    actions: SongActions,
    newReleases: List<NewRelease>,
    onPlay: (ArtistPlay) -> Unit,
    onOpenAllSongs: (String, String) -> Unit,
    onOpenLiked: (String) -> Unit,
    onOpenAlbum: (String, String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val likedSongs by viewModel.likedSongs.collectAsStateWithLifecycle()
    val subscribing by viewModel.subscribing.collectAsStateWithLifecycle()
    val page = state.page
    val failedText = stringResource(R.string.artist_subscribe_failed)
    ListScreen(
        page?.name ?: name,
        edgeButton = page?.let { { MainEdgeButton(R.drawable.ic_play_arrow, stringResource(R.string.ui_play)) { viewModel.playRequest(null, shuffle = false)?.let(onPlay) } } },
    ) { look ->
        if (page == null) {
            state.failure?.let { item { MessageRow(look, failureMessage(it)) } } ?: item { LoadingRow(look) }
            return@ListScreen
        }
        item(key = "shuffle") { ActionRow(look, R.drawable.ic_shuffle, stringResource(R.string.wear_shuffle_play)) { viewModel.playRequest(null, shuffle = true)?.let(onPlay) } }
        page.subscribed?.let { subscribed ->
            item(key = "subscribe") {
                ActionRow(look, if (subscribed) R.drawable.ic_check_circle else R.drawable.ic_add_circle, stringResource(if (subscribed) R.string.artist_subscribed else R.string.artist_subscribe), enabled = !subscribing) {
                    viewModel.toggleSubscribed(failedText)
                }
            }
        }
        if (likedSongs.songs.isNotEmpty()) {
            item(key = "liked") { ActionRow(look, R.drawable.ic_favorite, stringResource(R.string.artist_liked_songs), likedSongs.songs.size.toString()) { onOpenLiked(page.name) } }
        }
        newReleases.forEach { release ->
            item(key = "new-${release.albumBrowseId}-${release.videoId}") {
                EntryRow(look, release.title, stringResource(R.string.releases_new_badge), release.thumbnailUrl) {
                    release.albumBrowseId?.let { onOpenAlbum(it, release.title) }
                }
            }
        }
        item(key = "popular-h") { MessageRow(look, stringResource(R.string.artist_popular)) }
        itemsIndexed(page.topSongs, key = { index, song -> "t-$index-${song.videoId}" }) { index, song ->
            SongRow(look, song, liked = song.videoId in liked, onMenu = { actions.openSong(song) }) { viewModel.playRequest(song, shuffle = false)?.let(onPlay) }
        }
        page.allSongsPlaylistId?.let { id -> item(key = "all") { ActionRow(look, R.drawable.ic_view_list, stringResource(R.string.artist_see_all_songs)) { onOpenAllSongs(id, page.name) } } }
        page.albumShelves.forEachIndexed { shelfIndex, shelf ->
            item(key = "shelf-$shelfIndex") { MessageRow(look, shelf.title) }
            items(shelf.albums, key = { "al-$shelfIndex-${it.browseId}" }) { album -> EntryRow(look, album.title, album.subtitle, album.thumbnailUrl) { onOpenAlbum(album.browseId, album.title) } }
        }
    }
}

/** アルバム（スマホと同じ。再生・シャッフル・まとめてキューへ）。 */
@Composable
fun AlbumScreen(
    title: String,
    viewModel: AlbumViewModel,
    liked: Set<String>,
    actions: SongActions,
    onPlay: (AlbumPage, Int, Boolean) -> Unit,
    onQueue: (AlbumPage, Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val page = state.page
    ListScreen(
        page?.title ?: title,
        edgeButton = page?.let { { MainEdgeButton(R.drawable.ic_play_arrow, stringResource(R.string.ui_play)) { onPlay(it, 0, false) } } },
    ) { look ->
        if (page == null) {
            state.failure?.let { item { MessageRow(look, failureMessage(it)) } } ?: item { LoadingRow(look) }
            return@ListScreen
        }
        item(key = "head") {
            EntryRow(look, page.artists.joinToString { it.name }.ifEmpty { page.subtitle.orEmpty() }, page.subtitle, page.thumbnailUrl) {
                page.artists.firstOrNull { it.browseId != null }?.let(actions.openArtist)
            }
        }
        item(key = "shuffle") { ActionRow(look, R.drawable.ic_shuffle, stringResource(R.string.wear_shuffle_play)) { onPlay(page, 0, true) } }
        item(key = "next") { ActionRow(look, R.drawable.ic_playlist_play, stringResource(R.string.search_play_next)) { onQueue(page, true) } }
        item(key = "queue") { ActionRow(look, R.drawable.ic_queue_music, stringResource(R.string.search_add_to_queue)) { onQueue(page, false) } }
        itemsIndexed(page.songs, key = { index, song -> "s-$index-${song.videoId}" }) { index, song ->
            SongRow(look, song, liked = song.videoId in liked, onMenu = { actions.openSong(song) }) { onPlay(page, index, false) }
        }
    }
}

/** 再生履歴（スマホと同じ）。 */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    liked: Set<String>,
    actions: SongActions,
    onPlay: (List<Song>, Int) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ListScreen(stringResource(R.string.library_history_title)) { look ->
        if (state.loading && state.sections.isEmpty()) item { LoadingRow(look) }
        state.failure?.let { item { MessageRow(look, failureMessage(it)) } }
        if (!state.loading && state.sections.isEmpty() && state.failure == null) item { MessageRow(look, stringResource(R.string.library_history_empty)) }
        items(state.rows, key = { it.hashCode() }) { row ->
            when (row) {
                is HistoryRow.Heading -> MessageRow(look, row.title)
                is HistoryRow.Item -> SongRow(look, row.song, liked = row.song.videoId in liked, onMenu = { actions.openSong(row.song) }) { onPlay(state.songs, row.songIndex) }
            }
        }
        if (state.continuation != null) item(key = "more") { ActionRow(look, R.drawable.ic_add, stringResource(R.string.wear_load_more), onClick = viewModel::loadMore) }
    }
}
