package xyz.mooma0750.wave.widget

import org.junit.Test
import kotlin.test.assertEquals

/** ウィジェットの並べ方（iPhone と同じにそろえる）。 */
class WidgetPlanTest {
    @Test
    fun largeWithFiveOrMoreDropsThePlayerAndCaptionsFourByTwo() {
        assertEquals(GridPlan(player = false, rows = 2, columns = 4, style = GridStyle.Captioned), gridPlan(GridLayout.Large, 5))
        assertEquals(GridPlan(player = false, rows = 2, columns = 4, style = GridStyle.Captioned), gridPlan(GridLayout.Large, 8))
    }

    @Test
    fun largeWithFourOrFewerKeepsThePlayerAndOneRow() {
        assertEquals(GridPlan(player = true, rows = 1, columns = 4, style = GridStyle.Covers), gridPlan(GridLayout.Large, 4))
        assertEquals(GridPlan(player = true, rows = 1, columns = 4, style = GridStyle.Covers), gridPlan(GridLayout.Medium, 4))
    }

    @Test
    fun twoOrFewerPutNamesBesideTheCoversInEverySize() {
        assertEquals(GridPlan(player = false, rows = 2, columns = 1, style = GridStyle.Named), gridPlan(GridLayout.Small, 2))
        assertEquals(GridPlan(player = true, rows = 1, columns = 2, style = GridStyle.Named), gridPlan(GridLayout.Medium, 1))
        assertEquals(GridPlan(player = true, rows = 1, columns = 2, style = GridStyle.Named), gridPlan(GridLayout.Large, 2))
        assertEquals(GridPlan(player = false, rows = 2, columns = 2, style = GridStyle.Covers), gridPlan(GridLayout.Small, 3))
    }

    @Test
    fun queueShowsThreeSixOrEightSongsBySize() {
        assertEquals(QueuePlan(columns = 1, rows = 3), queuePlan(160f, 160f))
        assertEquals(QueuePlan(columns = 2, rows = 3), queuePlan(340f, 160f))
        assertEquals(QueuePlan(columns = 1, rows = 8), queuePlan(340f, 340f))
    }
}
