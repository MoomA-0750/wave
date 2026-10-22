package xyz.mooma0750.wave.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.LofiGenre
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.music.TextFold
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.search.failureMessage
import xyz.mooma0750.wave.ui.BackButton
import xyz.mooma0750.wave.ui.ChoiceButtonGroup
import xyz.mooma0750.wave.ui.MenuIcon
import xyz.mooma0750.wave.ui.WaveTheme

/**
 * ワンタップ再生のウィジェットを置くときに、何を鳴らすかを選ぶ（プレイリスト・アルバム・曲・ライブ）。
 * 検索の欄が空なら、プレイリスト・アルバムはライブラリ、曲・ライブは最近再生したもの（ライブを見ていなければ Lo-Fi のライブ）。
 * 打ち込むと、プレイリストはライブラリの中を、ほかは YouTube Music を検索する。置いた後も、ウィジェットを長押しして選び直せる。
 */
class PlaylistWidgetConfigActivity : ComponentActivity() {
    // Android 12 以前は、設定で固定した画面の言語をここで当てる（13 以降は OS が当てる）
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(xyz.mooma0750.wave.settings.AppLanguage.wrap(newBase))

    private val saving = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // 選ばずに戻ったら、置くのをやめる
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        enableEdgeToEdge()
        val container = (application as WaveApplication).container
        val likedTitle = getString(R.string.main_liked_songs)
        setContent {
            WaveTheme {
                val auth by container.authManager.state.collectAsStateWithLifecycle()
                ItemPicker(
                    loggedIn = auth is AuthState.LoggedIn,
                    restoring = auth == AuthState.Restoring,
                    saving = saving.value,
                    model = viewModel { WidgetItemPickerModel(container.music, likedTitle) },
                    onPick = { pick(widgetId, it) },
                    onCancel = ::finish,
                )
            }
        }
    }

    private fun pick(
        widgetId: Int,
        picked: WidgetItem,
    ) {
        if (saving.value) return
        saving.value = true
        val music = (application as WaveApplication).container.music
        lifecycleScope.launch {
            runCatching {
                // アルバムは鳴らすときのキューの出どころ（OLAK…）を先に調べておく（ウィジェットで、鳴っている間を見分ける）
                val item =
                    if (picked.kind == WidgetItemKind.Album) {
                        picked.copy(sourceId = (music.album(picked.id) as? CallResult.Ok)?.value?.playlistId)
                    } else {
                        picked
                    }
                val glanceId = GlanceAppWidgetManager(this@PlaylistWidgetConfigActivity).getGlanceIdBy(widgetId)
                updateAppWidgetState(this@PlaylistWidgetConfigActivity, glanceId) { prefs ->
                    item.writeTo(prefs)
                    prefs.remove(PlaylistWidget.LOADING)
                }
                PlaylistWidget().update(this@PlaylistWidgetConfigActivity, glanceId)
                Log.i(WaveApplication.LOG_TAG, "widget item kind=${item.kind} source=${item.sourceId != null}")
            }.onFailure { Log.w(WaveApplication.LOG_TAG, "widget config failed type=${it::class.simpleName}") }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}

/** 選べる一覧の中身。 */
data class PickerState(
    val items: List<WidgetItem> = emptyList(),
    val loading: Boolean = true,
    val failure: FailureKind? = null,
)

/** 種類と検索の語から、選べるものの一覧を作る（ライブラリと再生履歴は一度だけ読む）。 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class WidgetItemPickerModel(
    private val music: MusicRepository,
    private val likedTitle: String,
) : ViewModel() {
    val kind = MutableStateFlow(WidgetItemKind.Playlist)
    val query = MutableStateFlow("")

    val state: StateFlow<PickerState> =
        combine(kind, query.debounce { if (it.isBlank()) 0L else SEARCH_DELAY_MS }) { k, q -> k to q.trim() }
            .distinctUntilChanged()
            .flatMapLatest { (k, q) ->
                flow {
                    emit(PickerState())
                    emit(load(k, q))
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PickerState())

    private var playlists: List<PlaylistSummary>? = null
    private var history: List<Song>? = null

    private suspend fun load(
        kind: WidgetItemKind,
        query: String,
    ): PickerState =
        when (kind) {
            WidgetItemKind.Playlist -> {
                val list = playlists ?: (music.libraryPlaylists() as? CallResult.Ok)?.value?.also { playlists = it }
                // 「高く評価した曲」はライブラリの一覧に無いこともあるので、いつも先頭に出す
                val liked = list?.firstOrNull { it.playlistId == MusicRepository.LIKED_SONGS } ?: PlaylistSummary(MusicRepository.LIKED_SONGS, likedTitle, null, null)
                val all = listOf(liked) + list.orEmpty().filter { it.playlistId != MusicRepository.LIKED_SONGS }
                PickerState(
                    all.filter { TextFold.matches(query, it.title, it.subtitle) }.map(WidgetItem::of),
                    loading = false,
                    failure = if (list == null) FailureKind.Network else null,
                )
            }
            WidgetItemKind.Album ->
                result(if (query.isEmpty()) music.libraryAlbums() else music.searchAlbums(query).map { it.items }) { albums -> albums.map(WidgetItem::of) }
            WidgetItemKind.Song ->
                if (query.isEmpty()) {
                    recent { !it.isLive }
                } else {
                    result(music.searchSongs(query)) { page -> page.songs.filter { !it.isLive }.map(WidgetItem::of) }
                }
            WidgetItemKind.Live -> {
                val recent = if (query.isEmpty()) recent { it.isLive } else null
                if (recent != null && recent.items.isNotEmpty()) {
                    recent
                } else {
                    result(music.searchLive(query.ifEmpty { LofiGenre.LIVE_QUERY })) { page -> page.songs.filter { it.isLive }.map(WidgetItem::of) }
                }
            }
        }

    /** 最近再生した曲（再生履歴の最初のページ）。 */
    private suspend fun recent(keep: (Song) -> Boolean): PickerState {
        val songs =
            history ?: when (val page = music.history()) {
                is CallResult.Ok -> page.value.songs.also { history = it }
                is CallResult.Failed -> return PickerState(loading = false, failure = page.kind)
            }
        return PickerState(songs.filter(keep).distinctBy { it.videoId }.map(WidgetItem::of), loading = false)
    }

    private fun <T> result(
        call: CallResult<T>,
        items: (T) -> List<WidgetItem>,
    ): PickerState =
        when (call) {
            is CallResult.Ok -> PickerState(items(call.value), loading = false)
            is CallResult.Failed -> PickerState(loading = false, failure = call.kind)
        }

    private fun <T, R> CallResult<T>.map(transform: (T) -> R): CallResult<R> =
        when (this) {
            is CallResult.Ok -> CallResult.Ok(transform(value))
            is CallResult.Failed -> this
        }

    private companion object {
        const val SEARCH_DELAY_MS = 400L
    }
}

@Composable
private fun ItemPicker(
    loggedIn: Boolean,
    restoring: Boolean,
    saving: Boolean,
    model: WidgetItemPickerModel,
    onPick: (WidgetItem) -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_choose_playlist)) }, navigationIcon = { BackButton(onCancel) }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                restoring || saving -> LoadingIndicator(Modifier.align(Alignment.Center))
                !loggedIn -> Text(stringResource(R.string.widget_sign_in), Modifier.align(Alignment.Center).padding(24.dp))
                else -> ItemList(model, onPick)
            }
        }
    }
}

@Composable
private fun ItemList(
    model: WidgetItemPickerModel,
    onPick: (WidgetItem) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val kind by model.kind.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(model.query.value) }
    val kinds = WidgetItemKind.entries
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                model.query.value = it
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.widget_search_hint)) },
            leadingIcon = { MenuIcon(R.drawable.ic_search) },
        )
        ChoiceButtonGroup(
            labels =
                listOf(
                    stringResource(R.string.library_filter_playlists),
                    stringResource(R.string.search_tab_albums),
                    stringResource(R.string.search_tab_songs),
                    stringResource(R.string.search_tab_live),
                ),
            selected = kinds.indexOf(kind),
            onSelect = { model.kind.value = kinds[it] },
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            when {
                state.loading -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
                state.failure != null && state.items.isEmpty() -> item { Text(failureMessage(state.failure), Modifier.padding(16.dp)) }
                state.items.isEmpty() -> item { Text(stringResource(R.string.widget_no_history), Modifier.padding(16.dp)) }
            }
            if (!state.loading) items(state.items, key = { "${it.kind}-${it.id}" }) { ItemRow(it, onPick) }
        }
    }
}

@Composable
private fun ItemRow(
    item: WidgetItem,
    onPick: (WidgetItem) -> Unit,
) {
    val liked = item.kind == WidgetItemKind.Playlist && item.id == MusicRepository.LIKED_SONGS
    val subtitle =
        listOfNotNull(
            stringResource(R.string.live_badge).takeIf { item.kind == WidgetItemKind.Live },
            item.subtitle?.takeIf { it.isNotEmpty() },
        ).joinToString(" • ")
    ListItem(
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle.takeIf { it.isNotEmpty() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = {
            if (liked || item.thumbnailUrl == null) {
                Box(
                    Modifier.size(56.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    MenuIcon(
                        when {
                            liked -> R.drawable.ic_favorite
                            item.kind == WidgetItemKind.Album -> R.drawable.ic_album
                            item.kind == WidgetItemKind.Song -> R.drawable.ic_music_note
                            item.kind == WidgetItemKind.Live -> R.drawable.ic_live
                            else -> R.drawable.ic_queue_music
                        },
                    )
                }
            } else {
                coil3.compose.AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop,
                )
            }
        },
        modifier = Modifier.clickable { onPick(item) },
    )
}
