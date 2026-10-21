package xyz.mooma0750.wave.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.nowplaying.rememberPlayerUiState
import xyz.mooma0750.wave.playback.PlayerConnection

/** 時計のいちばん上の画面。スマホの下のタブ（ホーム・検索・Claude・ライブラリ）と、よく使う入口を縦に並べる。 */
@Composable
fun HomeMenu(
    connection: PlayerConnection,
    /** 再生画面にスマホの再生を出すときの、スマホの様子（この時計の再生を出すときは null）。 */
    phone: xyz.mooma0750.wave.watchlink.RemoteState?,
    hasDevices: Boolean,
    onOpen: (WearScreen) -> Unit,
) {
    val (state, _) = rememberPlayerUiState(connection)
    val liked = stringResource(R.string.main_liked_songs)
    ListScreen(stringResource(R.string.app_name)) { look ->
        when {
            phone != null ->
                item(key = "now") {
                    ActionRow(
                        look,
                        if (phone.playing) R.drawable.ic_graphic_eq else R.drawable.ic_smartphone,
                        phone.title ?: stringResource(R.string.wear_nothing_playing),
                        listOfNotNull(phone.playingOn ?: phone.deviceName, phone.artist).joinToString(" · "),
                    ) { onOpen(WearScreen.NowPlaying) }
                }
            state.title != null ->
                item(key = "now") {
                    ActionRow(look, if (state.isPlaying) R.drawable.ic_graphic_eq else R.drawable.ic_play_circle, state.title.orEmpty(), state.artist) { onOpen(WearScreen.NowPlaying) }
                }
        }
        item(key = "search") { ActionRow(look, R.drawable.ic_search, stringResource(R.string.nav_search)) { onOpen(WearScreen.Search) } }
        item(key = "home") { ActionRow(look, R.drawable.ic_home, stringResource(R.string.nav_home)) { onOpen(WearScreen.Feed) } }
        item(key = "liked") {
            ActionRow(look, R.drawable.ic_favorite, liked) { onOpen(WearScreen.Playlist(PlaylistSummary(MusicRepository.LIKED_SONGS, liked, null, null))) }
        }
        item(key = "library") { ActionRow(look, R.drawable.ic_library, stringResource(R.string.nav_library)) { onOpen(WearScreen.Library) } }
        item(key = "history") { ActionRow(look, R.drawable.ic_history, stringResource(R.string.library_history_title)) { onOpen(WearScreen.History) } }
        item(key = "releases") { ActionRow(look, R.drawable.ic_album, stringResource(R.string.releases_home_title)) { onOpen(WearScreen.Releases) } }
        item(key = "ai") { ActionRow(look, R.drawable.ic_ai, stringResource(R.string.nav_ai)) { onOpen(WearScreen.Ai) } }
        if (hasDevices) item(key = "devices") { ActionRow(look, R.drawable.ic_devices, stringResource(R.string.nowplaying_connected_devices)) { onOpen(WearScreen.Devices) } }
        item(key = "settings") { ActionRow(look, R.drawable.ic_settings, stringResource(R.string.settings_title)) { onOpen(WearScreen.Settings) } }
    }
}
