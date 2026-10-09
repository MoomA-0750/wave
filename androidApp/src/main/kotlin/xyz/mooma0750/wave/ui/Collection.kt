package xyz.mooma0750.wave.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.mooma0750.wave.R
import androidx.compose.ui.res.stringResource

/**
 * プレイリスト・アルバムのページ（並びは Spotify と同じ）: ジャケットの色のグラデーションの上に大きなジャケット、
 * 名前と補足、左から大きな再生ボタン・シャッフル・小さな操作（Windows と同じ）、その下に曲。
 * 上の帯は、スクロールしてジャケットが隠れたら名前を出し、色を付ける。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    title: String,
    coverUrl: String?,
    onBack: () -> Unit,
    /** ジャケットの上に出すもの（Spotify のプレイリストの「この中を探す」「並べ替え」）。 */
    top: @Composable ColumnScope.() -> Unit = {},
    /** 名前の下の行（作った人・年・曲数など）。 */
    details: @Composable ColumnScope.() -> Unit = {},
    /** 操作の列の、再生ボタンとシャッフルの右（キューに追加・メニューなど）。 */
    actions: @Composable RowScope.() -> Unit = {},
    /** 操作の列の左端の、再生ボタンとシャッフルの切り替え。null なら出さない。 */
    play: PlayControls?,
    coverShape: Shape = MaterialTheme.shapes.extraSmall,
    /** アーティストのページ: 画像を横幅いっぱいに出し、名前を画像の上に重ねる（Spotify のアーティストのページ）。 */
    hero: Boolean = false,
    /** hero のとき、名前の右に並べるもの（アーティストの登録ボタン）。 */
    titleTrailing: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val art by rememberArtworkColor(coverUrl)
    val background = MaterialTheme.colorScheme.background
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val barColor by animateColorAsState(if (collapsed) lerp(background, art, 0.5f) else Color.Transparent, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "bar")
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            if (hero) {
                item(key = "collection-hero") {
                    Box(Modifier.fillMaxWidth().height(340.dp)) {
                        coil3.compose.AsyncImage(model = coverUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to background.copy(alpha = 0.95f))))
                        Row(
                            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                title,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.displaySmallEmphasized,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            titleTrailing()
                        }
                    }
                    Column(Modifier.padding(horizontal = 16.dp)) { details() }
                }
            } else item(key = "collection-header") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(lerp(background, art, 0.7f), background)))
                        .statusBarsPadding()
                        .padding(top = 56.dp, start = 16.dp, end = 16.dp),
                ) {
                    top()
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= WIDE_HEADER_MIN_WIDTH) {
                            // 広い画面（タブレット・開いた折りたたみ）: ジャケットの右に大きな題名（Spotify のタブレット・デスクトップと同じ）
                            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.Bottom) {
                                coil3.compose.AsyncImage(
                                    model = coverUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(200.dp).clip(coverShape),
                                    contentScale = ContentScale.Crop,
                                )
                                Column(Modifier.padding(start = 24.dp).weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.displaySmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(8.dp))
                                    details()
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxWidth()) {
                                coil3.compose.AsyncImage(
                                    model = coverUrl,
                                    contentDescription = null,
                                    modifier = Modifier.padding(vertical = 12.dp).size(240.dp).align(Alignment.CenterHorizontally).clip(coverShape),
                                    contentScale = ContentScale.Crop,
                                )
                                Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                details()
                            }
                        }
                    }
                }
            }
            item(key = "collection-actions") {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Windows と同じ並び: 左から再生ボタン、シャッフル、そのほかの操作
                    play?.let { controls ->
                        PlayFab(playing = false, onClick = controls.onPlay, enabled = controls.enabled, modifier = Modifier.padding(start = 12.dp, end = 8.dp))
                        IconAction(R.drawable.ic_shuffle, if (controls.shuffle) stringResource(R.string.ui_shuffle_on) else stringResource(R.string.ui_shuffle_off), { controls.onShuffleChange(!controls.shuffle) }, active = controls.shuffle, size = 28.dp)
                    }
                    actions()
                }
            }
            content()
            item(key = "collection-bottom") { Spacer(Modifier.height(16.dp)) }
        }
        TopAppBar(
            title = { if (collapsed) Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor),
        )
    }
}

/** これより広ければ、見出しのジャケットの右に題名を並べる。 */
private val WIDE_HEADER_MIN_WIDTH = 600.dp

/** 再生ボタンとシャッフルの切り替え（Spotify の、再生ボタンの左のシャッフル）。 */
class PlayControls(
    val shuffle: Boolean,
    val onShuffleChange: (Boolean) -> Unit,
    val enabled: Boolean,
    val onPlay: () -> Unit,
)

/** 補足の行（Spotify の、名前の下の小さな灰色の文字）。 */
@Composable
fun DetailText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** 合計の長さ（アルバム・プレイリストの補足。Windows の L.Length と同じ形）。 */
@Composable
fun totalLength(seconds: Int): String =
    if (seconds >= 3600) {
        stringResource(R.string.library_length_hours, seconds / 3600, seconds % 3600 / 60)
    } else {
        stringResource(R.string.library_length_minutes, seconds / 60, seconds % 60)
    }
