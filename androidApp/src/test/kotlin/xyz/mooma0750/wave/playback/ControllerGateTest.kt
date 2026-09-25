package xyz.mooma0750.wave.playback

import org.junit.Test
import xyz.mooma0750.wave.playback.ControllerGate.Access
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** onConnect の受け入れの判断表（すべての組み合わせ）。 */
class ControllerGateTest {
    @Test
    fun everyCombination() {
        for (notification in listOf(true, false)) {
            for (ui in listOf(true, false)) {
                for (trusted in listOf(true, false)) {
                    for (reads in listOf(true, false)) {
                        val expected =
                            when {
                                notification -> Access.OwnNotification
                                ui -> Access.OwnUi
                                trusted || reads -> Access.PlaybackOnly
                                else -> Access.Reject
                            }
                        assertEquals(expected, ControllerGate.classify(notification, ui, trusted) { reads }, "notification=$notification ui=$ui trusted=$trusted reads=$reads")
                    }
                }
            }
        }
    }

    @Test
    fun notificationAccessIsOnlyLookedUpForUntrustedOutsiders() {
        var looked = false
        val lookup = {
            looked = true
            false
        }
        ControllerGate.classify(isOwnNotificationController = true, isOwnUiController = false, isTrusted = false, readsOurNotification = lookup)
        ControllerGate.classify(isOwnNotificationController = false, isOwnUiController = true, isTrusted = false, readsOurNotification = lookup)
        ControllerGate.classify(isOwnNotificationController = false, isOwnUiController = false, isTrusted = true, readsOurNotification = lookup)
        assertFalse(looked, "信頼されている・自分のコントローラーで、通知の読み取りの許可を調べた")
        assertEquals(Access.Reject, ControllerGate.classify(isOwnNotificationController = false, isOwnUiController = false, isTrusted = false, readsOurNotification = lookup))
    }
}
