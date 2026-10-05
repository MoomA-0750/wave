package xyz.mooma0750.wave.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigatorTest {
    @Test
    fun eachTabKeepsItsOwnStack() {
        val nav = AppNavigator()
        nav.push(Screen.History)
        nav.select(Tab.Search)
        assertNull(nav.current)
        nav.push(Screen.Search)
        nav.select(Tab.Home)
        assertEquals(Screen.History, nav.current)
        nav.select(Tab.Search)
        assertEquals(Screen.Search, nav.current)
    }

    @Test
    fun backPopsThenReturnsHomeThenStops() {
        val nav = AppNavigator()
        nav.select(Tab.Library)
        nav.push(Screen.Settings)
        nav.back()
        assertEquals(Tab.Library, nav.tab)
        assertTrue(nav.canGoBack)
        nav.back()
        assertEquals(Tab.Home, nav.tab)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun selectingTheCurrentTabAgainGoesToItsTop() {
        val nav = AppNavigator()
        nav.push(Screen.History)
        nav.push(Screen.Settings)
        nav.select(Tab.Home)
        assertNull(nav.current)
    }
}
