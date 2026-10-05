package xyz.mooma0750.wave.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.MoodCategory
import xyz.mooma0750.wave.core.music.MoodSection
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.home.HomeFeedUiState
import xyz.mooma0750.wave.home.homeFeedItems
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.ui.BackButton
import xyz.mooma0750.wave.ui.IconTextButton

data class MoodsUiState(
    val sections: List<MoodSection> = emptyList(),
    val loading: Boolean = true,
    val failure: FailureKind? = null,
)

class MoodsViewModel(
    private val music: MusicRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MoodsUiState())
    val state: StateFlow<MoodsUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.value = MoodsUiState()
        viewModelScope.launch {
            mutableState.value =
                when (val result = music.moods()) {
                    is CallResult.Ok -> MoodsUiState(result.value, loading = false)
                    is CallResult.Failed -> MoodsUiState(loading = false, failure = result.kind)
                }
        }
    }
}

/**
 * 検索のタブの最初の画面（並びは Spotify と同じ）: 大きな見出し、押すと検索の画面を開く検索欄、
 * 気分・ジャンルの色のタイルの2列の格子。
 */
@Composable
fun SearchBrowseScreen(
    viewModel: MoodsViewModel,
    onOpenSearch: () -> Unit,
    onOpenCategory: (MoodCategory) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 広い画面（開いた折りたたみ・タブレット）では列を増やす（Spotify のタブレットと同じ）
    val columns = (LocalConfiguration.current.screenWidthDp / 200).coerceIn(2, 6)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("検索", Modifier.statusBarsPadding().padding(start = 16.dp, top = 16.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        // 上に留めると Spotify のように見出しの下で止まるが、ここでは並びだけを合わせる
        item {
            Surface(
                onClick = onOpenSearch,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("何を聴きたいですか？", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (state.loading) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
        state.failure?.let { failure ->
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("ジャンルを読み込めませんでした（${failureMessage(failure)}）", style = MaterialTheme.typography.bodySmall)
                    IconTextButton(R.drawable.ic_refresh, "もう一度", viewModel::refresh)
                }
            }
        }
        state.sections.forEach { section ->
            item {
                Text(section.title, Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            section.categories.chunked(columns).forEach { row ->
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { category -> MoodTile(category, Modifier.weight(1f)) { onOpenCategory(category) } }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** 色のタイル（Spotify の「すべてを見る」のタイル）。色はテーマの面に混ぜて、Material の見た目に寄せる。 */
@Composable
private fun MoodTile(
    category: MoodCategory,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val accent = category.color?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.primary
    Card(
        onClick = onClick,
        modifier = modifier.height(96.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, accent, 0.55f)),
    ) {
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            Text(category.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

class MoodCategoryViewModel(
    private val music: MusicRepository,
    private val params: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeFeedUiState())
    val state: StateFlow<HomeFeedUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.value = HomeFeedUiState()
        viewModelScope.launch {
            mutableState.value =
                when (val result = music.moodCategory(params)) {
                    is CallResult.Ok -> HomeFeedUiState(shelves = result.value.shelves, loading = false)
                    is CallResult.Failed -> HomeFeedUiState(loading = false, failure = result.kind)
                }
        }
    }
}

/** 気分・ジャンルのページ（Spotify のジャンルのページ）。プレイリストなどの棚が並ぶ。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodCategoryScreen(
    title: String,
    viewModel: MoodCategoryViewModel,
    onPlaySong: (Song) -> Unit,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
    actions: SongActions,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            homeFeedItems(state, viewModel::refresh, {}, onPlaySong, onOpenPlaylist, actions)
        }
    }
}
