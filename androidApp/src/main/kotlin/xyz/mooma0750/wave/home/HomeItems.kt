package xyz.mooma0750.wave.home

import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.AlbumRef
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.HomeItem
import xyz.mooma0750.wave.core.music.HomeShelf
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.navigation.SongActions

/** 棚の見出し。アプリが作った棚は端末の言語で出す。 */
@Composable
fun shelfTitle(shelf: HomeShelf): String =
    when {
        shelf.strapline != null -> shelf.title
        shelf.title == HomeShelves.NEW_RELEASES_TITLE -> stringResource(R.string.home_new_releases)
        else -> shelf.title
    }

/** カードに出す画像と文字。 */
data class HomeItemLook(
    val thumbnailUrl: String?,
    val title: String,
    val subtitle: String?,
) {
    companion object {
        fun of(item: HomeItem): HomeItemLook =
            when (item) {
                is HomeItem.SongItem -> HomeItemLook(item.song.thumbnailUrl, item.song.title, if (item.song.isLive) listOf("LIVE", item.song.subtitle).filter { it.isNotEmpty() }.joinToString(" • ") else item.song.subtitle)
                is HomeItem.AlbumItem -> HomeItemLook(item.album.thumbnailUrl, item.album.title, item.album.subtitle)
                is HomeItem.PlaylistItem -> HomeItemLook(item.playlist.thumbnailUrl, item.playlist.title, item.playlist.subtitle)
                is HomeItem.ArtistItem -> HomeItemLook(item.artist.thumbnailUrl, item.artist.name, item.artist.subtitle)
            }
    }
}

fun openHomeItem(
    item: HomeItem,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
    actions: SongActions,
) {
    when (item) {
        // 曲も、アルバム・プレイリストと同じく先に画面を開く（押してすぐ再生するものと画面を開くものが混ざらないように）
        // ライブ配信は曲の画面（歌詞・アルバム）が無いので、押したらそのまま流す
        is HomeItem.SongItem -> if (item.song.isLive) actions.startRadio(item.song) else actions.openSong(item.song)
        is HomeItem.AlbumItem -> actions.openAlbum(AlbumRef(item.album.title, item.album.browseId))
        is HomeItem.PlaylistItem -> onOpenPlaylist(item.playlist)
        is HomeItem.ArtistItem -> actions.openArtist(ArtistRef(item.artist.name, item.artist.browseId))
    }
}
