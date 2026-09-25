package xyz.mooma0750.wave.connect2

import org.junit.Test
import xyz.mooma0750.wave.handoff.v2.ClientEvent
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Connect v2 の知らせの文言（画面から移す前と同じ）。 */
class ConnectNoticesTest {
    @Test
    fun toasts() {
        assertEquals("他の端末で再生が始まりました", ConnectNotices.toast(ClientEvent.Stopped("x")))
        assertEquals("引き取れませんでした（expired）", ConnectNotices.toast(ClientEvent.TransferFailed("expired")))
        assertEquals("中継の端末一覧を確かめられませんでした", ConnectNotices.toast(ClientEvent.ChainForked))
        assertEquals("中継の端末一覧を確かめられませんでした", ConnectNotices.toast(ClientEvent.ChainInvalid("gap")))
        assertEquals("他の端末で再生中です（端末の連携の画面から「ここで再生」）", ConnectNotices.toast(ClientEvent.ServerError(ConnectV2Manager.REMOTE_PLAYING)))
        assertEquals("家を作る要求を中継に送れませんでした（中継に登録した端末か、接続を確かめてください）", ConnectNotices.toast(ClientEvent.ServerError(ConnectV2Manager.HOUSEHOLD_CREATE_UNSENT)))
        assertNull(ConnectNotices.toast(ClientEvent.ServerError("not_active")))
        assertNull(ConnectNotices.toast(ClientEvent.Joined))
    }

    @Test
    fun backgroundNoticesOnlyForStoppedAndBlockedPlayback() {
        assertEquals(ConnectNotices.Notice("他の端末で再生が始まりました", "この端末の再生は止めました"), ConnectNotices.background(ClientEvent.Stopped("x")))
        assertEquals("他の端末で再生中です", ConnectNotices.background(ClientEvent.ServerError(ConnectV2Manager.REMOTE_PLAYING))?.title)
        assertEquals("再生しませんでした", ConnectNotices.background(ClientEvent.ServerError(ConnectV2Manager.RELAY_UNREACHABLE))?.title)
        assertEquals("再生しませんでした", ConnectNotices.background(ClientEvent.ServerError(ConnectV2Manager.NOT_MEMBER))?.title)
        assertEquals("再生しませんでした", ConnectNotices.background(ClientEvent.ServerError(ConnectV2Manager.ACCOUNT_UNVERIFIED))?.title)
        // 家を作る要求の失敗・引き取りの結果は、裏では知らせない
        assertNull(ConnectNotices.background(ClientEvent.ServerError(ConnectV2Manager.HOUSEHOLD_CREATE_UNSENT)))
        assertNull(ConnectNotices.background(ClientEvent.TransferFailed("x")))
    }
}
