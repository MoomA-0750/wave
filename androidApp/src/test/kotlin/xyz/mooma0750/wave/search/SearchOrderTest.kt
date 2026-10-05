package xyz.mooma0750.wave.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.mooma0750.wave.core.music.ArtistSummary

class SearchOrderTest {
    private fun artist(name: String) = ArtistSummary("UC-$name", name, null, null)

    @Test
    fun artistComesFirstOnlyWhenTheQueryIsItsName() {
        assertTrue(SearchOrder.artistFirst("yoasobi", listOf(artist("YOASOBI"))))
        assertTrue(SearchOrder.artistFirst(" Mrs. GREEN APPLE ", listOf(artist("Mrs.GREEN APPLE"))))
        // 日本語の入力で打つと全角になる
        assertTrue(SearchOrder.artistFirst("ＹＯＡＳＯＢＩ", listOf(artist("YOASOBI"))))
        assertFalse(SearchOrder.artistFirst("夜に駆ける", listOf(artist("YOASOBI"))))
        assertFalse(SearchOrder.artistFirst("yoasobi", emptyList()))
        assertFalse(SearchOrder.artistFirst(null, listOf(artist("YOASOBI"))))
    }
}
