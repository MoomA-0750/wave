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
