package xyz.mooma0750.wave.wear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.lazy.TransformationSpec
import xyz.mooma0750.wave.core.music.Song

/** 一覧の行の見た目を、行ごとにそろえて作るための道具（スクロールに合わせて縮む）。 */
class RowLook(
    val spec: TransformationSpec,
) {
    @Composable
    fun TransformingLazyColumnItemScope.modifier(): Modifier = Modifier.fillMaxWidth().transformedHeight(this, spec)

    @Composable
    fun TransformingLazyColumnItemScope.transformation(): SurfaceTransformation = SurfaceTransformation(spec)
}

/**
 * 時計の一覧の画面（見出し・スクロール・回転入力・下の丸いボタン）。どの一覧もこれで作る。
 */
@Composable
fun ListScreen(
    title: String?,
    edgeButton: (@Composable () -> Unit)? = null,
    content: TransformingLazyColumnScope.(RowLook) -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val look = RowLook(rememberTransformationSpec())
    val body: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit = { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            if (title != null) {
                item(key = "title") {
                    ListHeader(modifier = Modifier.fillMaxWidth().transformedHeight(this, look.spec), transformation = SurfaceTransformation(look.spec)) {
                        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
            content(look)
        }
    }
    if (edgeButton != null) {
        ScreenScaffold(scrollState = state, edgeButton = { edgeButton() }) { padding -> body(padding) }
    } else {
        ScreenScaffold(scrollState = state) { padding -> body(padding) }
    }
}

/** 下の丸いボタン（その画面の主な操作。再生など）。 */
@Composable
fun MainEdgeButton(
    icon: Int,
    label: String,
    /** 文字も出す（何をするボタンか分かりにくいとき）。 */
    showLabel: Boolean = false,
    onClick: () -> Unit,
) {
    EdgeButton(onClick = onClick, buttonSize = EdgeButtonSize.Medium) {
        if (showLabel) Text(label, maxLines = 1) else Icon(painterResource(icon), contentDescription = label)
    }
}

/** 画像（曲・アルバムは角丸、アーティストは丸）。 */
@Composable
fun Thumb(
    url: String?,
    size: Int = 32,
    shape: Shape = MaterialTheme.shapes.small,
) {
    coil3.compose.AsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier.size(size.dp).clip(shape),
        contentScale = ContentScale.Crop,
    )
}

/** 一覧の1行（画像・題・補足）。長押しは曲のメニューなど。 */
@Composable
fun TransformingLazyColumnItemScope.EntryRow(
    look: RowLook,
    title: String,
    subtitle: String?,
    thumbnailUrl: String?,
    round: Boolean = false,
    icon: Int? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    with(look) {
        FilledTonalButton(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier(),
            transformation = transformation(),
            icon =
                when {
                    icon != null -> ({ Icon(painterResource(icon), contentDescription = null) })
                    thumbnailUrl != null -> ({ Thumb(thumbnailUrl, shape = if (round) CircleShape else MaterialTheme.shapes.small) })
                    else -> null
                },
            secondaryLabel = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            label = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        )
    }
}

/** 曲の1行。押すと onClick（再生など）、長押しで曲のメニュー。 */
@Composable
fun TransformingLazyColumnItemScope.SongRow(
    look: RowLook,
    song: Song,
    liked: Boolean = false,
    onMenu: () -> Unit,
    onClick: () -> Unit,
) = EntryRow(look, if (liked) "♥ ${song.title}" else song.title, song.subtitle, song.thumbnailUrl, onLongClick = onMenu, onClick = onClick)

/** 操作の1行（アイコンと文字）。 */
@Composable
fun TransformingLazyColumnItemScope.ActionRow(
    look: RowLook,
    icon: Int,
    label: String,
    secondary: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    with(look) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier(),
            transformation = transformation(),
            colors = ButtonDefaults.filledTonalButtonColors(),
            icon = { Icon(painterResource(icon), contentDescription = null) },
            secondaryLabel = secondary?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
            label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        )
    }
}

/** 一覧の中の文（説明・失敗）。 */
@Composable
fun TransformingLazyColumnItemScope.MessageRow(
    look: RowLook,
    text: String,
) {
    with(look) {
        Text(text, modifier = modifier(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
    }
}

/** 読み込み中の行。 */
@Composable
fun TransformingLazyColumnItemScope.LoadingRow(look: RowLook) {
    with(look) {
        Box(modifier(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) }
    }
}

/** 画面いっぱいの読み込み中。 */
@Composable
fun FullScreenLoading() {
    ScreenScaffold {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(48.dp)) }
    }
}
