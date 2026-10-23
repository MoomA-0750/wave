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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.ui.BackButton
import xyz.mooma0750.wave.ui.ChoiceButtonGroup
import xyz.mooma0750.wave.ui.MenuIcon
import xyz.mooma0750.wave.ui.WaveTheme

/**
 * プレイリストのウィジェットに何を並べるか（iPhone のウィジェットの設定と同じ）。「最近再生した順」（既定）か「選ぶ」で、
 * 選ぶときはライブラリのプレイリストから並べる数（大きいウィジェットの8個）まで、選んだ順に並べる。
 * 置くときは開かず（最近再生した順で置く）、ウィジェットを長押しして開く。
 */
class PlaylistGridConfigActivity : ComponentActivity() {
    // Android 12 以前は、設定で固定した画面の言語をここで当てる（13 以降は OS が当てる）
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(xyz.mooma0750.wave.settings.AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // 置くときに開かれて戻ったら、最近再生した順で置く（選ぶのは任意）
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        enableEdgeToEdge()
        val container = (application as WaveApplication).container
        setContent {
            WaveTheme {
                var chosenMode by remember { mutableStateOf<Boolean?>(null) }
                var chosen by remember { mutableStateOf(emptyList<WidgetPlaylist>()) }
                var playlists by remember { mutableStateOf<List<WidgetPlaylist>?>(null) }
                LaunchedEffect(Unit) {
                    runCatching {
                        val glanceId = GlanceAppWidgetManager(this@PlaylistGridConfigActivity).getGlanceIdBy(widgetId)
                        val prefs = getAppWidgetState(this@PlaylistGridConfigActivity, PreferencesGlanceStateDefinition, glanceId)
                        chosen = WidgetPlaylist.listOf(prefs[PlaylistGridWidget.CHOSEN])
                        chosenMode = prefs[PlaylistGridWidget.SOURCE] == PlaylistGridWidget.SOURCE_CHOSEN
                    }.onFailure {
                        Log.w(WaveApplication.LOG_TAG, "widget grid config read failed type=${it::class.simpleName}")
                        chosenMode = false
                    }
                    playlists = WidgetPlaylists.recent(this@PlaylistGridConfigActivity)
                }
                val loggedIn = container.authManager.state.value is AuthState.LoggedIn
                GridConfig(
                    chosenMode = chosenMode,
                    chosen = chosen,
                    playlists = playlists,
                    loggedIn = loggedIn,
                    onMode = { chosenMode = it },
                    onToggle = { playlist ->
                        chosen =
                            if (chosen.any { it.id == playlist.id }) {
                                chosen.filter { it.id != playlist.id }
                            } else if (chosen.size < PlaylistGridWidget.LARGE_COUNT) {
                                chosen + playlist
                            } else {
                                chosen
                            }
                    },
                    onDone = { save(widgetId, chosenMode == true, chosen) },
                    onCancel = ::finish,
                )
            }
        }
    }

    private fun save(
        widgetId: Int,
        chosenMode: Boolean,
        chosen: List<WidgetPlaylist>,
    ) {
        lifecycleScope.launch {
            runCatching {
                val glanceId = GlanceAppWidgetManager(this@PlaylistGridConfigActivity).getGlanceIdBy(widgetId)
                updateAppWidgetState(this@PlaylistGridConfigActivity, glanceId) { prefs ->
                    prefs[PlaylistGridWidget.SOURCE] = if (chosenMode) PlaylistGridWidget.SOURCE_CHOSEN else PlaylistGridWidget.SOURCE_RECENT
                    prefs[PlaylistGridWidget.CHOSEN] = WidgetPlaylist.encode(chosen)
                }
                PlaylistGridWidget().update(this@PlaylistGridConfigActivity, glanceId)
                Log.i(WaveApplication.LOG_TAG, "widget grid source=${if (chosenMode) "chosen" else "recent"} count=${chosen.size}")
            }.onFailure { Log.w(WaveApplication.LOG_TAG, "widget grid config failed type=${it::class.simpleName}") }
            finish()
        }
    }
}

@Composable
private fun GridConfig(
    chosenMode: Boolean?,
    chosen: List<WidgetPlaylist>,
    playlists: List<WidgetPlaylist>?,
    loggedIn: Boolean,
    onMode: (Boolean) -> Unit,
    onToggle: (WidgetPlaylist) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.widget_playlists_title)) },
                navigationIcon = { BackButton(onCancel) },
                actions = {
                    Button(onClick = onDone, enabled = chosenMode != null, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(end = 8.dp)) {
                        Text(stringResource(R.string.widget_playlists_done))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (chosenMode == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                return@Column
            }
            ChoiceButtonGroup(
                labels = listOf(stringResource(R.string.widget_playlists_recent), stringResource(R.string.widget_playlists_chosen)),
                selected = if (chosenMode) 1 else 0,
                onSelect = { onMode(it == 1) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                if (chosenMode) stringResource(R.string.widget_playlists_chosen_note, PlaylistGridWidget.LARGE_COUNT) else stringResource(R.string.widget_playlists_recent_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (!chosenMode) return@Column
            when {
                !loggedIn -> Text(stringResource(R.string.widget_sign_in), Modifier.padding(16.dp))
                playlists == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(playlists, key = { it.id }) { playlist ->
                            val checked = chosen.any { it.id == playlist.id }
                            PlaylistRow(playlist, checked, enabled = checked || chosen.size < PlaylistGridWidget.LARGE_COUNT) { onToggle(playlist) }
                        }
                    }
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: WidgetPlaylist,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val liked = playlist.id == MusicRepository.LIKED_SONGS
    ListItem(
        headlineContent = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
        trailingContent = { Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onToggle),
    )
}
