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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.library.LibraryViewModel
import xyz.mooma0750.wave.search.failureMessage
import xyz.mooma0750.wave.ui.BackButton
import xyz.mooma0750.wave.ui.MenuIcon
import xyz.mooma0750.wave.ui.WaveTheme

/**
 * プレイリストのウィジェットを置くときに、どのプレイリストにするかを選ぶ（ライブラリのプレイリストと「高く評価した曲」）。
 * 置いた後も、ウィジェットを長押しして選び直せる。
 */
class PlaylistWidgetConfigActivity : ComponentActivity() {
    // Android 12 以前は、設定で固定した画面の言語をここで当てる（13 以降は OS が当てる）
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(xyz.mooma0750.wave.settings.AppLanguage.wrap(newBase))

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
        setContent {
            WaveTheme {
                val auth by container.authManager.state.collectAsStateWithLifecycle()
                PlaylistPicker(
                    loggedIn = auth is AuthState.LoggedIn,
                    restoring = auth == AuthState.Restoring,
                    viewModel = viewModel { LibraryViewModel(container.music, container.libraryCache) },
                    likedTitle = stringResource(R.string.main_liked_songs),
                    onPick = { pick(widgetId, it) },
                    onCancel = ::finish,
                )
            }
        }
    }

    private fun pick(
        widgetId: Int,
        playlist: PlaylistSummary,
    ) {
        lifecycleScope.launch {
            runCatching {
                val glanceId = GlanceAppWidgetManager(this@PlaylistWidgetConfigActivity).getGlanceIdBy(widgetId)
                updateAppWidgetState(this@PlaylistWidgetConfigActivity, glanceId) { prefs ->
                    prefs[PlaylistWidget.PLAYLIST_ID] = playlist.playlistId
                    prefs[PlaylistWidget.TITLE] = playlist.title
                    playlist.thumbnailUrl?.let { prefs[PlaylistWidget.THUMBNAIL] = it } ?: prefs.remove(PlaylistWidget.THUMBNAIL)
                    prefs.remove(PlaylistWidget.LOADING)
                }
                PlaylistWidget().update(this@PlaylistWidgetConfigActivity, glanceId)
            }.onFailure { Log.w(WaveApplication.LOG_TAG, "widget config failed type=${it::class.simpleName}") }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}

@Composable
private fun PlaylistPicker(
    loggedIn: Boolean,
    restoring: Boolean,
    viewModel: LibraryViewModel,
    likedTitle: String,
    onPick: (PlaylistSummary) -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_choose_playlist)) }, navigationIcon = { BackButton(onCancel) }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                restoring -> LoadingIndicator(Modifier.align(Alignment.Center))
                !loggedIn -> Text(stringResource(R.string.widget_sign_in), Modifier.align(Alignment.Center).padding(24.dp))
                else -> PlaylistList(viewModel, likedTitle, onPick)
            }
        }
    }
}

@Composable
private fun PlaylistList(
    viewModel: LibraryViewModel,
    likedTitle: String,
    onPick: (PlaylistSummary) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    // 「高く評価した曲」はライブラリの一覧に無いこともあるので、いつも先頭に出す
    val liked = state.playlists.firstOrNull { it.playlistId == MusicRepository.LIKED_SONGS } ?: PlaylistSummary(MusicRepository.LIKED_SONGS, likedTitle, null, null)
    val others = state.playlists.filter { it.playlistId != MusicRepository.LIKED_SONGS }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = MusicRepository.LIKED_SONGS) { PlaylistRow(liked, onPick, liked = true) }
        when {
            state.failure != null -> item { Text(failureMessage(state.failure), Modifier.padding(16.dp)) }
            state.loading && others.isEmpty() -> item { Box(Modifier.padding(16.dp)) { LoadingIndicator() } }
        }
        items(others, key = { it.playlistId }) { PlaylistRow(it, onPick) }
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistSummary,
    onPick: (PlaylistSummary) -> Unit,
    liked: Boolean = false,
) {
    ListItem(
        headlineContent = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = playlist.subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = {
            if (liked || playlist.thumbnailUrl == null) {
                Box(
                    Modifier.size(56.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { MenuIcon(if (liked) R.drawable.ic_favorite else R.drawable.ic_queue_music) }
            } else {
                coil3.compose.AsyncImage(
                    model = playlist.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop,
                )
            }
        },
        modifier = Modifier.clickable { onPick(playlist) },
    )
}
