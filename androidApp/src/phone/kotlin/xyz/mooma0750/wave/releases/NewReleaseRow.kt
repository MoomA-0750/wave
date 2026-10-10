package xyz.mooma0750.wave.releases

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.AlbumRef
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.NewRelease
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.navigation.SongActions

/** 新作を開く: アルバム・シングルはそのページ、動画は再生する。 */
fun openRelease(
    release: NewRelease,
    actions: SongActions,
    onPlaySong: (Song) -> Unit,
) {
    val album = release.albumBrowseId
    val video = release.videoId
    when {
        album != null -> actions.openAlbum(AlbumRef(release.title, album))
        video != null -> onPlaySong(Song(video, release.title, listOf(ArtistRef(release.artistName, release.artistId)), null, null, release.thumbnailUrl))
    }
}

/**
 * 登録したアーティストの新作の棚（ホーム・アーティストのページ）。見つけて7日以内のものに「NEW」を付ける。
 * showArtist = false ならアーティスト名を出さない（アーティストのページ）。
 */
@Composable
fun NewReleaseRow(
    releases: List<NewRelease>,
    title: String,
    actions: SongActions,
    onPlaySong: (Song) -> Unit,
    showArtist: Boolean = true,
) {
    if (releases.isEmpty()) return
    val now = System.currentTimeMillis()
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLargeEmphasized)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(releases, key = { it.albumBrowseId ?: it.videoId ?: it.title }) { release ->
                Column(Modifier.width(140.dp).clickable { openRelease(release, actions, onPlaySong) }) {
                    Box {
                        coil3.compose.AsyncImage(
                            model = release.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier.size(140.dp).clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop,
                        )
                        if (!release.seeded && now - release.foundAtEpochMs < NEW_BADGE_MS) {
                            Surface(Modifier.align(Alignment.TopStart).padding(6.dp), shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.primary) {
                                Text(stringResource(R.string.releases_new_badge), Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmallEmphasized)
                            }
                        }
                    }
                    Text(release.title, Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    val kind = if (release.videoId != null) stringResource(R.string.releases_kind_video) else stringResource(R.string.releases_kind_release)
                    Text(if (showArtist) "${release.artistName} • $kind" else kind, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private const val NEW_BADGE_MS = 7L * 24 * 60 * 60 * 1000
