package xyz.mooma0750.wave.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 下のタブ（Spotify と同じ3つ）。 */
enum class Tab { Home, Search, Library }

/**
 * タブごとの画面の積み重ね（Spotify と同じで、タブを切り替えても各タブの奥の画面は残る）。
 * 戻るは今のタブの画面を1つ閉じ、タブの最初まで戻ったらホームのタブに戻る。
 */
class AppNavigator {
    var tab by mutableStateOf(Tab.Home)
        private set
    private val stacks = Tab.entries.associateWith { mutableStateListOf<Screen>() }

    val current: Screen? get() = stacks.getValue(tab).lastOrNull()

    val canGoBack: Boolean get() = stacks.getValue(tab).isNotEmpty() || tab != Tab.Home

    fun push(screen: Screen) {
        stacks.getValue(tab) += screen
    }

    fun back() {
        val stack = stacks.getValue(tab)
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else tab = Tab.Home
    }

    /** 今のタブをもう一度押したら、そのタブの最初に戻る（Spotify と同じ）。 */
    fun select(next: Tab) {
        if (next == tab) stacks.getValue(tab).clear() else tab = next
    }

    fun clearAll() {
        stacks.values.forEach { it.clear() }
        tab = Tab.Home
    }
}
