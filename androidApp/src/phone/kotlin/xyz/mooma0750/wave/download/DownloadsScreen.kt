package xyz.mooma0750.wave.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.download.CollectionKind
import xyz.mooma0750.wave.core.download.DownloadPlan
import xyz.mooma0750.wave.core.download.DownloadedCollection
import xyz.mooma0750.wave.core.download.SmartCategory
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.search.SongMenuButton
import xyz.mooma0750.wave.search.SongRow
import xyz.mooma0750.wave.settings.AppSettings
import xyz.mooma0750.wave.ui.ActionButtonGroup
import xyz.mooma0750.wave.ui.BackButton
import xyz.mooma0750.wave.ui.GroupAction
import xyz.mooma0750.wave.ui.IconAction

/**
 * ダウンロードの画面（ADR-0016 の 13・14）: 使っている容量と進み具合、スマートダウンロード（入り切り・分類・曲）、
 * 丸ごと取っているもの、エピソード、1曲ずつ取った曲。通信なしでも開ける。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadsScreen(
    downloads: DownloadManager,
    settings: AppSettings,
    actions: SongActions,
    onPlay: (songs: List<Song>, index: Int, shuffle: Boolean) -> Unit,
    onOpenCollection: (DownloadedCollection) -> Unit,
    onBack: () -> Unit,
) {
    val state by downloads.state.collectAsStateWithLifecycle()
    val net by downloads.network.state.collectAsStateWithLifecycle()
    val smartEnabled by settings.smartDownloads.collectAsStateWithLifecycle(initialValue = null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    // 分類の絞り込み（null は「すべて」）
    var category by rememberSaveable { mutableStateOf<SmartCategory?>(null) }
    val manifest = state.manifest
    val smartSongs = manifest.smart.filter { category == null || it.category == category }.map { it.song }
    val episodes = manifest.singles.filter { it.episode }.map { it.song }
    val songs = manifest.singles.filterNot { it.episode }.map { it.song }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.downloads_title)) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item(key = "status") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.downloads_storage, android.text.format.Formatter.formatShortFileSize(context, state.bytesUsed)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!net.online) Text(stringResource(R.string.downloads_offline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.active?.let { active ->
                        Text(stringResource(R.string.downloads_downloading, active.title.orEmpty()), Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        val total = active.total
                        if (total != null && total > 0) {
                            LinearWavyProgressIndicator(progress = { (active.bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                    }
                    if (state.pending > 0) {
                        Text(
                            if (state.waitingForNetwork) stringResource(R.string.downloads_waiting_network) + " • " + stringResource(R.string.downloads_pending, state.pending) else stringResource(R.string.downloads_pending, state.pending),
                            Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // スマートダウンロード
            item(key = "smart") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.downloads_smart), style = MaterialTheme.typography.titleMediumEmphasized) },
                    supportingContent = { Text(stringResource(R.string.downloads_smart_summary)) },
                    trailingContent = { smartEnabled?.let { enabled -> Switch(checked = enabled, onCheckedChange = { scope.launch { settings.setSmartDownloads(it) } }) } },
                )
            }
            if (smartEnabled == true) {
                item(key = "smart-chips") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf<SmartCategory?>(null) + SmartCategory.entries) { option ->
                            FilterChip(selected = category == option, onClick = { category = option }, label = { Text(categoryLabel(option)) })
                        }
                    }
                }
                item(key = "smart-play") {
                    ActionButtonGroup(
                        listOf(
                            GroupAction(stringResource(R.string.downloads_play), primary = true, enabled = smartSongs.isNotEmpty()) { onPlay(smartSongs, 0, false) },
                            GroupAction(stringResource(R.string.downloads_shuffle), enabled = smartSongs.isNotEmpty()) { onPlay(smartSongs, 0, true) },
                        ),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (state.refreshingSmart) {
                    item(key = "smart-loading") {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator()
                            Text(stringResource(R.string.downloads_smart_refreshing), Modifier.padding(start = 12.dp))
                        }
                    }
                } else if (smartSongs.isEmpty()) {
                    item(key = "smart-empty") { Text(stringResource(R.string.downloads_smart_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                itemsIndexed(smartSongs, key = { index, song -> "smart-$index-${song.videoId}" }) { index, song ->
                    SongRow(song, onClick = { onPlay(smartSongs, index, false) }) { SongMenuButton(song, actions) }
                }
            }
            if (manifest.collections.isNotEmpty()) {
                item(key = "collections-title") { SectionTitle(stringResource(R.string.downloads_collections)) }
                items(manifest.collections, key = { "c-${it.kind}-${it.id}" }) { collection ->
                    CollectionRow(collection, DownloadPlan.progress(collection, state.complete)) { onOpenCollection(collection) }
                }
            }
            if (episodes.isNotEmpty()) {
                item(key = "episodes-title") { SectionTitle(stringResource(R.string.downloads_episodes)) }
                itemsIndexed(episodes, key = { index, song -> "e-$index-${song.videoId}" }) { index, song ->
                    SongRow(song, onClick = { onPlay(episodes, index, false) }) { SongMenuButton(song, actions) }
                }
            }
            if (songs.isNotEmpty()) {
                item(key = "songs-title") { SectionTitle(stringResource(R.string.downloads_songs)) }
                itemsIndexed(songs, key = { index, song -> "s-$index-${song.videoId}" }) { index, song ->
                    SongRow(song, onClick = { onPlay(songs, index, false) }) { SongMenuButton(song, actions) }
                }
            }
            if (manifest.isEmpty && smartEnabled == false) {
                item(key = "empty") { Text(stringResource(R.string.downloads_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun categoryLabel(category: SmartCategory?): String =
    when (category) {
        null -> stringResource(R.string.downloads_smart_all)
        SmartCategory.Frequent -> stringResource(R.string.downloads_smart_frequent)
        SmartCategory.Discover -> stringResource(R.string.downloads_smart_discover)
        SmartCategory.Recommended -> stringResource(R.string.downloads_smart_recommended)
    }

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMediumEmphasized)
}

@Composable
private fun CollectionRow(
    collection: DownloadedCollection,
    progress: Pair<Int, Int>,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val url = collection.thumbnailUrl ?: collection.songs.firstOrNull()?.thumbnailUrl
        if (collection.kind == CollectionKind.Liked || url == null) {
            Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(painterResource(if (collection.kind == CollectionKind.Liked) R.drawable.ic_favorite else R.drawable.ic_download), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        } else {
            coil3.compose.AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.extraSmall), contentScale = ContentScale.Crop)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(collection.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.downloads_in_progress, progress.first, progress.second), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (progress.second > 0 && progress.first >= progress.second) DoneMark()
    }
}

/** 取り終えた印（塗りの丸に下向きの矢印）。 */
@Composable
private fun DoneMark(size: androidx.compose.ui.unit.Dp = 24.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.7f))
    }
}

/**
 * プレイリスト・アルバム・高く評価した曲のページの頭のダウンロードのボタン（ADR-0016 の 15）。
 * 取っていない／取っている途中（何曲中何曲）／取り終えた、の3つの見た目。取っているものを押すと、消すか確かめる。
 * 開いたときに取っているものなら、一覧を読み直して合わせる。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CollectionDownloadButton(
    downloads: DownloadManager,
    kind: CollectionKind,
    id: String,
    title: String,
    thumbnailUrl: String?,
    songs: List<Song>,
) {
    val state by downloads.state.collectAsStateWithLifecycle()
    LaunchedEffect(kind, id) { downloads.syncIfDownloaded(kind, id) }
    var confirming by remember { mutableStateOf(false) }
    when (val status = state.collectionState(kind, id)) {
        CollectionDownload.None ->
            IconAction(R.drawable.ic_download, stringResource(R.string.downloads_download), { downloads.downloadCollection(kind, id, title, thumbnailUrl, songs) }, enabled = songs.isNotEmpty(), size = 28.dp)
        is CollectionDownload.InProgress -> {
            val label = stringResource(R.string.downloads_in_progress, status.done, status.total)
            IconButton(onClick = { confirming = true }, modifier = Modifier.semantics { contentDescription = label }) {
                CircularWavyProgressIndicator(progress = { if (status.total == 0) 0f else status.done.toFloat() / status.total }, modifier = Modifier.size(28.dp))
            }
            Text("${status.done}/${status.total}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is CollectionDownload.Done -> {
            val label = stringResource(R.string.downloads_downloaded)
            IconButton(onClick = { confirming = true }, modifier = Modifier.semantics { contentDescription = label }) { DoneMark(28.dp) }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.downloads_remove_confirm_title)) },
            text = { Text(stringResource(R.string.downloads_remove_confirm_message, title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    downloads.removeCollection(kind, id)
                }) { Text(stringResource(R.string.downloads_remove_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.downloads_cancel)) } },
        )
    }
}

/** 設定の「ダウンロード」の項目（ADR-0016 の 17）: 使う通信、スマートダウンロードの入り切りと曲数、使っている容量、すべて削除。 */
@Composable
fun DownloadSettings(
    downloads: DownloadManager,
    settings: AppSettings,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val state by downloads.state.collectAsStateWithLifecycle()
    val any by settings.downloadOnAnyNetwork.collectAsStateWithLifecycle(initialValue = null)
    val smart by settings.smartDownloads.collectAsStateWithLifecycle(initialValue = null)
    val count by settings.smartDownloadCount.collectAsStateWithLifecycle(initialValue = null)
    Column {
        ChoiceSetting(
            title = stringResource(R.string.settings_downloads_network),
            options = listOf(false, true),
            selected = any,
            label = { if (it) stringResource(R.string.settings_downloads_network_any) else stringResource(R.string.settings_downloads_network_unmetered) },
            onSelect = { scope.launch { settings.setDownloadOnAnyNetwork(it) } },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.downloads_smart)) },
            supportingContent = { Text(stringResource(R.string.downloads_smart_summary)) },
            trailingContent = { smart?.let { enabled -> Switch(checked = enabled, onCheckedChange = { scope.launch { settings.setSmartDownloads(it) } }) } },
        )
        ChoiceSetting(
            title = stringResource(R.string.settings_smart_count),
            options = xyz.mooma0750.wave.core.download.SmartDownloads.COUNTS,
            selected = count,
            label = { stringResource(R.string.settings_smart_count_value, it) },
            onSelect = { scope.launch { settings.setSmartDownloadCount(it) } },
        )
        var confirming by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_downloads_delete_all)) },
            supportingContent = { Text(stringResource(R.string.settings_downloads_storage, android.text.format.Formatter.formatShortFileSize(context, state.bytesUsed))) },
            leadingContent = { xyz.mooma0750.wave.ui.MenuIcon(R.drawable.ic_delete) },
            modifier = Modifier.clickable { confirming = true },
        )
        if (confirming) {
            AlertDialog(
                onDismissRequest = { confirming = false },
                title = { Text(stringResource(R.string.settings_downloads_delete_all)) },
                text = { Text(stringResource(R.string.settings_downloads_delete_all_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirming = false
                        scope.launch { downloads.deleteAll() }
                    }) { Text(stringResource(R.string.downloads_remove_confirm)) }
                },
                dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.downloads_cancel)) } },
            )
        }
    }
}

/** 選ぶ設定（押すとメニューで選ぶ。画面の言語の設定と同じ形）。 */
@Composable
private fun <T> ChoiceSetting(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { selected?.let { Text(label(it)) } },
            modifier = Modifier.clickable { open = true },
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            options.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label(option)) },
                    trailingIcon = { if (option == selected) xyz.mooma0750.wave.ui.MenuIcon(R.drawable.ic_check_circle) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
