package xyz.mooma0750.wave.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.mooma0750.wave.R

/** 高く評価した曲の ID。曲の行がバッジを出すかを決める（MainActivity が渡す）。 */
val LocalLikedIds = compositionLocalOf<Set<String>> { emptySet() }

/** 高く評価した曲の印（Spotify の、曲の行の緑の✓）。評価していなければ何も出さない。 */
@Composable
fun LikedBadge(
    videoId: String,
    modifier: Modifier = Modifier,
) {
    if (videoId !in LocalLikedIds.current) return
    Icon(
        painterResource(R.drawable.ic_check_circle),
        contentDescription = stringResource(R.string.ui_liked_badge),
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 4.dp).size(18.dp),
    )
}

/** 取り終えた曲の ID（ダウンロード、ADR-0016 の 16）。曲の行が印を出すかを決める（MainActivity が渡す）。 */
val LocalDownloadedIds = compositionLocalOf<Set<String>> { emptySet() }

/** 丸ごと取ったもの（プレイリスト・アルバム・高く評価した曲）だけが持ち主の曲。曲のメニューから1曲だけは消せない。 */
val LocalCollectionOnlyDownloads = compositionLocalOf<Set<String>> { emptySet() }

/** 取り終えた曲の小さな印（Spotify の、曲の行の下向きの矢印）。取っていなければ何も出さない。 */
@Composable
fun DownloadedBadge(
    videoId: String,
    modifier: Modifier = Modifier,
) {
    if (videoId !in LocalDownloadedIds.current) return
    Icon(
        painterResource(R.drawable.ic_download),
        contentDescription = stringResource(R.string.downloads_badge),
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 4.dp).size(18.dp),
    )
}
