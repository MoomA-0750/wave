package xyz.mooma0750.wave.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.Feed
import xyz.mooma0750.wave.core.music.PodcastEpisode
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.search.SongMenuButton
import xyz.mooma0750.wave.search.failureMessage
import xyz.mooma0750.wave.ui.CollectionScreen
import xyz.mooma0750.wave.ui.DetailText
import xyz.mooma0750.wave.ui.PlayControls

/**
 * ポッドキャストの番組のページ: 画像・題・配信者・説明、ライブラリへの保存（ログイン中だけ）、新しい順のエピソード。
 * エピソードを押すと、そのエピソードから番組の一覧を続けて流す（曲と同じ再生）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PodcastScreen(
    viewModel: PodcastViewModel,
    title: String,
    actions: SongActions,
    /** 読んだエピソードの index から流す。playlistId は番組のプレイリスト（`PL…`）。RSS の番組では null（ADR-0012）。 */
    onPlay: (songs: List<Song>, index: Int, playlistId: String?, shuffle: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val page = state.page
    var shuffle by rememberSaveable { mutableStateOf(false) }
    val songs = state.episodes.map { it.song }
    CollectionScreen(
        title = page?.title ?: title,
        coverUrl = page?.thumbnailUrl,
        onBack = onBack,
        coverShape = MaterialTheme.shapes.medium,
        details = {
            page?.let { podcast ->
                podcast.author?.let { Text(it, style = MaterialTheme.typography.titleSmallEmphasized) }
                podcast.description?.let { Description(it) }
            }
        },
        actions = {
            // 保存（Spotify の「フォロー」）。ログインしていない応答では状態が分からないので出さない
            if (page?.saved != null) {
                val failed = stringResource(R.string.podcast_save_failed)
                Spacer(Modifier.width(8.dp))
                ToggleButton(checked = page.saved == true, onCheckedChange = { viewModel.toggleSaved(failed) }, enabled = !state.saving) {
                    Text(stringResource(if (page.saved == true) R.string.podcast_saved else R.string.podcast_save))
                }
            }
        },
        play = PlayControls(shuffle, { shuffle = it }, enabled = songs.isNotEmpty()) { page?.let { onPlay(songs, 0, it.playlistId.takeUnless { _ -> Feed.isFeedShow(it.browseId) }, shuffle) } },
    ) {
        when {
            state.failure != null -> item { Text(failureMessage(state.failure), Modifier.padding(16.dp)) }
            page == null -> item { LoadingIndicator(Modifier.padding(16.dp)) }
            state.episodes.isEmpty() -> item { Text(stringResource(R.string.podcast_no_episodes), Modifier.padding(16.dp)) }
            else -> {
                itemsIndexed(state.episodes, key = { index, episode -> "$index-${episode.song.videoId}" }) { index, episode ->
                    EpisodeRow(episode, onClick = { onPlay(songs, index, page.playlistId.takeUnless { Feed.isFeedShow(page.browseId) }, false) }) { SongMenuButton(episode.song, actions) }
                    // 最後まで来たら続きを読む
                    if (index == state.episodes.lastIndex && state.continuation != null) LaunchedEffect(state.episodes.size) { viewModel.loadMore() }
                }
                if (state.loadingMore) item { LoadingIndicator(Modifier.padding(16.dp)) }
            }
        }
    }
}

/** 番組の説明。長ければ3行で切り、「もっと見る」で全部出す。 */
@Composable
private fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text(
        text,
        Modifier.padding(top = 4.dp).clickable { expanded = !expanded },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
    )
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.podcast_show_less else R.string.podcast_show_more)) }
}

/** エピソードの行: 画像、題、「日付 • 長さ」、説明の2行、聴いたところ。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EpisodeRow(
    episode: PodcastEpisode,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        coil3.compose.AsyncImage(
            model = episode.song.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(episode.song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            listOfNotNull(episode.publishedText, episode.durationText).takeIf { it.isNotEmpty() }?.let { DetailText(it.joinToString(" • ")) }
            episode.description?.let {
                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            episode.progressPercent?.let { percent ->
                LinearWavyProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        trailing()
    }
}
