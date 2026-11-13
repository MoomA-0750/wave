package xyz.mooma0750.wave.home

import org.junit.Test
import xyz.mooma0750.wave.core.music.AlbumSummary
import xyz.mooma0750.wave.core.music.HomeItem
import xyz.mooma0750.wave.core.music.HomeShelf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeShelvesTest {
    private fun albums(vararg ids: String) = ids.map { HomeItem.AlbumItem(AlbumSummary(it, it, null, null)) }

    private val ours = HomeShelf(HomeShelves.NEW_RELEASES_TITLE, null, albums("a", "b", "c", "d"))

    @Test
    fun laterPageDropsTheYouTubeShelfThatDuplicatesOursAndKeepsOursOnTop() {
        val first = listOf(ours, HomeShelf("Mixed for you", null, albums("x")))
        val duplicate = HomeShelf("New releases", null, albums("a", "b", "c", "z"))
        val other = HomeShelf("Listen again", "me", albums("a", "y"))
        val result = HomeShelves.appendPage(first, listOf(duplicate, other))
        // 先に出した棚（先頭のこちらの「新作」）はそのまま。重なる YouTube の棚だけを落とす
        assertEquals(first + other, result)
    }

    @Test
    fun withoutOurShelfLaterPagesAreAppendedAsIs() {
        val first = listOf(HomeShelf("Mixed for you", null, albums("x")))
        val page = listOf(HomeShelf("New releases", null, albums("a", "b", "c")))
        assertEquals(first + page, HomeShelves.appendPage(first, page))
    }

    @Test
    fun firstPageDuplicateIsStillDetected() {
        assertTrue(HomeShelves.duplicatesNewReleases(listOf(ours, HomeShelf("New releases", null, albums("a", "b", "c")))))
    }
}
