package xyz.mooma0750.wave.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.items
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.AddToPlaylistResult
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.library.LibraryViewModel
import xyz.mooma0750.wave.library.PlaylistMembership
import xyz.mooma0750.wave.search.failureMessage

/**
 * プレイリストに追加する画面（スマホと同じ）。入っているプレイリストには✓、押すたびに入れる・外すを切り替える。
 * 高く評価した曲の行もここで切り替えられる。新しいプレイリストは声かキーボードで名前を入れて作る。
 */
@Composable
fun AddToPlaylistScreen(
    song: Song,
    viewModel: LibraryViewModel,
    membership: PlaylistMembership,
    liked: Boolean?,
    onToggleLiked: () -> Unit,
    onPick: (PlaylistSummary, (AddToPlaylistResult) -> Unit) -> Boolean,
    onUnpick: (PlaylistSummary, (Boolean) -> Unit) -> Boolean,
    onCreate: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, song.videoId) { viewModel.refresh() }
    val scope = rememberCoroutineScope()
    val editable = state.playlists.filter { it.editable }
    val check = remember(membership, scope) { PlaylistMembership.Check(membership, scope) }
    val statuses by check.status.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(emptySet<String>()) }
    var removing by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(check, song.videoId, editable.map { it.playlistId }) {
        if (editable.isNotEmpty()) check.start(editable.map { it.playlistId }, song.videoId)
    }
    DisposableEffect(check) { onDispose { check.cancel() } }
    val create = rememberTextInput(stringResource(R.string.library_playlist_name_hint), onCreate)
    ListScreen(
        stringResource(R.string.library_add_to_playlist),
        edgeButton = { MainEdgeButton(R.drawable.ic_add, stringResource(R.string.library_new_playlist), onClick = create) },
    ) { look ->
        if (liked != null) {
            item(key = "liked") {
                ActionRow(look, if (liked) R.drawable.ic_check_circle else R.drawable.ic_add_circle, stringResource(R.string.library_liked_songs_row), onClick = onToggleLiked)
            }
        }
        when {
            state.failure != null -> item(key = "failure") { MessageRow(look, failureMessage(state.failure)) }
            state.loading && editable.isEmpty() -> item(key = "loading") { LoadingRow(look) }
        }
        items(editable, key = { it.playlistId }) { playlist ->
            val id = playlist.playlistId
            val status = statuses[id]
            val busy = id in adding || id in removing
            ActionRow(
                look,
                when {
                    status == PlaylistMembership.Status.In -> R.drawable.ic_check_circle
                    busy || status == PlaylistMembership.Status.Checking -> R.drawable.ic_refresh
                    else -> R.drawable.ic_add_circle
                },
                playlist.title,
                secondary =
                    when {
                        id in removing -> stringResource(R.string.library_membership_removing)
                        busy -> stringResource(R.string.library_membership_adding)
                        status == PlaylistMembership.Status.Checking -> stringResource(R.string.library_membership_checking)
                        status == PlaylistMembership.Status.Unknown -> stringResource(R.string.library_membership_unknown)
                        else -> null
                    },
                enabled = status != PlaylistMembership.Status.Checking && !busy,
            ) {
                if (status == PlaylistMembership.Status.In) {
                    val accepted =
                        onUnpick(playlist) { removed ->
                            scope.launch {
                                removing = removing - id
                                if (removed) check.markNotIn(id)
                            }
                        }
                    if (accepted) removing = removing + id
                } else {
                    val accepted =
                        onPick(playlist) { result ->
                            scope.launch {
                                adding = adding - id
                                if (result == AddToPlaylistResult.Added || result == AddToPlaylistResult.AlreadyInPlaylist) check.markIn(id)
                            }
                        }
                    if (accepted) adding = adding + id
                }
            }
        }
    }
}
