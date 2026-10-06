package xyz.mooma0750.wave.connect2

import org.junit.Test
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.handoff.v2.ClientEvent
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Connect v2 の知らせの文言（文字列リソースの ID と引数）。 */
class ConnectNoticesTest {
    @Test
    fun toasts() {
        assertEquals(ConnectNotices.Message(R.string.connect_v2_notice_stopped), ConnectNotices.toastMessage(ClientEvent.Stopped("x")))
        assertEquals(ConnectNotices.Message(R.string.connect_v2_notice_transfer_failed, listOf("expired")), ConnectNotices.toastMessage(ClientEvent.TransferFailed("expired")))
        assertEquals(ConnectNotices.Message(R.string.connect_v2_member_list_unverified), ConnectNotices.toastMessage(ClientEvent.ChainForked))
        assertEquals(ConnectNotices.Message(R.string.connect_v2_member_list_unverified), ConnectNotices.toastMessage(ClientEvent.ChainInvalid("gap")))
        assertEquals(ConnectNotices.Message(R.string.connect_v2_notice_remote_playing), ConnectNotices.toastMessage(ClientEvent.ServerError(ConnectV2Manager.REMOTE_PLAYING)))
        assertEquals(ConnectNotices.Message(R.string.connect_v2_notice_household_create_unsent), ConnectNotices.toastMessage(ClientEvent.ServerError(ConnectV2Manager.HOUSEHOLD_CREATE_UNSENT)))
        assertNull(ConnectNotices.toastMessage(ClientEvent.ServerError("not_active")))
        assertNull(ConnectNotices.toastMessage(ClientEvent.Joined))
    }

    @Test
    fun backgroundNoticesOnlyForStoppedAndBlockedPlayback() {
        assertEquals(ConnectNotices.Notice(R.string.connect_v2_notice_stopped, R.string.connect_v2_notice_stopped_text), ConnectNotices.background(ClientEvent.Stopped("x")))
        assertEquals(R.string.connect_v2_notice_remote_playing_title, ConnectNotices.background(ClientEvent.ServerError(ConnectV2Manager.REMOTE_PLAYING))?.title)
        assertEquals(R.string.connect_v2_notice_not_played, ConnectNotices.background(ClientEvent.ServerError(ConnectV2Manager.RELAY_UNREACHABLE))?.title)
        assertEquals(R.string.connect_v2_notice_not_played, ConnectNotices.background(ClientEvent.ServerError(ConnectV2Manager.NOT_MEMBER))?.title)
        assertEquals(R.string.connect_v2_notice_not_played, ConnectNotices.background(ClientEvent.ServerError(ConnectV2Manager.ACCOUNT_UNVERIFIED))?.title)
        // 家を作る要求の失敗・引き取りの結果は、裏では知らせない
        assertNull(ConnectNotices.background(ClientEvent.ServerError(ConnectV2Manager.HOUSEHOLD_CREATE_UNSENT)))
        assertNull(ConnectNotices.background(ClientEvent.TransferFailed("x")))
    }
}
