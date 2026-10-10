package xyz.mooma0750.wave.wear

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song

/** 時計の画面。スマホの Screen と同じものに、時計だけのもの（再生画面・キュー・歌詞・音量・曲のメニューなど）を足す。 */
sealed interface WearScreen {
    data object Home : WearScreen

    data object NowPlaying : WearScreen

    data object Queue : WearScreen

    data object Lyrics : WearScreen

    data object Volume : WearScreen

    data object Search : WearScreen

    data object Browse : WearScreen

    data class Mood(
        val title: String,
        val params: String,
    ) : WearScreen

    data object Feed : WearScreen

    data object Library : WearScreen

    data object History : WearScreen

    data object Releases : WearScreen

    data object Settings : WearScreen

    data object Devices : WearScreen

    data object Ai : WearScreen

    data class Playlist(
        val summary: PlaylistSummary,
        val initialQuery: String? = null,
    ) : WearScreen

    data class Artist(
        val browseId: String,
        val name: String,
    ) : WearScreen

    data class Album(
        val browseId: String,
        val title: String,
    ) : WearScreen

    /** 曲の画面（スマホの曲の画面と曲のメニューを兼ねる）。 */
    data class SongPage(
        val song: Song,
        /** キューの中の曲から開いたとき（キューから外せる）。 */
        val queueItemId: String? = null,
        /** 自分のプレイリストの中の曲から開いたとき（プレイリストから外せる）。 */
        val playlistId: String? = null,
        val setVideoId: String? = null,
    ) : WearScreen

    data class AddToPlaylist(
        val song: Song,
    ) : WearScreen
}

/** 画面の積み重ね。いちばん下はメニュー（ホーム）。 */
class WearNavigator {
    val stack = mutableStateListOf<WearScreen>(WearScreen.Home)
    val current: WearScreen get() = stack.last()

    fun push(screen: WearScreen) {
        // 同じ画面を続けて積まない（2回押したときなど）
        if (stack.last() != screen) stack.add(screen)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** 再生画面へ（すでに積んであればそこまで戻る）。 */
    fun openNowPlaying() {
        val index = stack.indexOf(WearScreen.NowPlaying)
        if (index >= 0) {
            while (stack.size > index + 1) stack.removeAt(stack.lastIndex)
        } else {
            stack.add(WearScreen.NowPlaying)
        }
    }

    fun clear() {
        stack.clear()
        stack.add(WearScreen.Home)
    }
}

/** 右へのスワイプ・戻るボタンで1つ戻る（Wear OS の決まり）。下の画面はスワイプの間だけ見せる。 */
@Composable
fun WearNavHost(
    nav: WearNavigator,
    content: @Composable (WearScreen) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    // 閉じた画面の保存した状態（スクロール位置・絞り込み）は捨てる（同じ画面を開き直したときに前の状態が戻らないように）
    val shown = androidx.compose.runtime.remember { mutableSetOf<String>() }
    val alive = nav.stack.indices.map { keyOf(nav, it) }.toSet()
    androidx.compose.runtime.SideEffect {
        (shown - alive).forEach(holder::removeState)
        shown.retainAll(alive)
        shown.addAll(alive)
    }
    val canGoBack = nav.stack.size > 1
    BackHandler(enabled = canGoBack) { nav.back() }
    val state = rememberSwipeToDismissBoxState()
    val top = nav.current
    val below = nav.stack.getOrNull(nav.stack.lastIndex - 1)
    SwipeToDismissBox(
        state = state,
        onDismissed = nav::back,
        userSwipeEnabled = canGoBack,
        backgroundKey = below?.let { keyOf(nav, nav.stack.lastIndex - 1) } ?: "none",
        contentKey = keyOf(nav, nav.stack.lastIndex),
    ) { isBackground ->
        if (isBackground) {
            below?.let { screen -> key(keyOf(nav, nav.stack.lastIndex - 1)) { holder.SaveableStateProvider(keyOf(nav, nav.stack.lastIndex - 1)) { content(screen) } } }
        } else {
            key(keyOf(nav, nav.stack.lastIndex)) { holder.SaveableStateProvider(keyOf(nav, nav.stack.lastIndex)) { content(top) } }
        }
    }
}

private fun keyOf(
    nav: WearNavigator,
    index: Int,
): String = "$index-${nav.stack[index].hashCode()}"
