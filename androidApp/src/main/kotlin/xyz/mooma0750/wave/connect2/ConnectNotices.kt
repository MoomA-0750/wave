package xyz.mooma0750.wave.connect2

import android.content.res.Resources
import androidx.annotation.StringRes
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.handoff.v2.ClientEvent

/** Connect v2 の出来事を、本人への知らせの文言にする（画面が前面なら Toast、裏なら通知）。文言は文字列リソースの ID で返し、使う側が引く。 */
internal object ConnectNotices {
    /** 文字列リソースの ID と、その引数。 */
    data class Message(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) {
        fun resolve(resources: Resources): String = resources.getString(id, *args.toTypedArray())
    }

    data class Notice(
        @StringRes val title: Int,
        @StringRes val text: Int,
    )

    /** 画面が前面のときの Toast の文言。知らせないものは null。 */
    fun toastMessage(event: ClientEvent): Message? =
        when (event) {
            is ClientEvent.Stopped -> Message(R.string.connect_v2_notice_stopped)
            is ClientEvent.TransferCompleted -> Message(R.string.connect_v2_notice_transferred)
            is ClientEvent.TransferFailed -> Message(R.string.connect_v2_notice_transfer_failed, listOf(event.reason))
            is ClientEvent.StoreReset -> Message(R.string.connect_v2_notice_store_reset)
            ClientEvent.ChainForked, ClientEvent.ChainRolledBack, is ClientEvent.ChainInvalid -> Message(R.string.connect_v2_member_list_unverified)
            is ClientEvent.ServerError ->
                when (event.code) {
                    ConnectV2Manager.REMOTE_PLAYING -> Message(R.string.connect_v2_notice_remote_playing)
                    ConnectV2Manager.RELAY_UNREACHABLE -> Message(R.string.connect_v2_notice_relay_unreachable)
                    ConnectV2Manager.NOT_MEMBER -> Message(R.string.connect_v2_notice_not_member)
                    ConnectV2Manager.ACCOUNT_UNVERIFIED -> Message(R.string.connect_v2_notice_account_unverified)
                    ConnectV2Manager.HOUSEHOLD_CREATE_UNSENT -> Message(R.string.connect_v2_notice_household_create_unsent)
                    ConnectV2Manager.CONTROL_UNSENT -> Message(R.string.connect_v2_notice_control_unsent)
                    else -> null
                }
            else -> null
        }

    /** 画面が前面のときの Toast。知らせないものは null。 */
    fun toast(
        event: ClientEvent,
        resources: Resources,
    ): String? = toastMessage(event)?.resolve(resources)

    /** 以前の呼び方（resources を渡さない）。アプリの Resources で引く。 */

    /** 裏にいるときの通知（Toast は裏では出ない）。止められた・再生ボタンで鳴らさなかったときだけ（D-9、レビュー p6b34 中-5）。 */
    fun background(event: ClientEvent): Notice? =
        when (event) {
            is ClientEvent.Stopped -> Notice(R.string.connect_v2_notice_stopped, R.string.connect_v2_notice_stopped_text)
            is ClientEvent.ServerError ->
                when (event.code) {
                    ConnectV2Manager.REMOTE_PLAYING -> Notice(R.string.connect_v2_notice_remote_playing_title, R.string.connect_v2_notice_remote_playing_text)
                    ConnectV2Manager.RELAY_UNREACHABLE -> Notice(R.string.connect_v2_notice_not_played, R.string.connect_v2_notice_relay_unreachable)
                    ConnectV2Manager.NOT_MEMBER -> Notice(R.string.connect_v2_notice_not_played, R.string.connect_v2_notice_not_member_short)
                    ConnectV2Manager.ACCOUNT_UNVERIFIED -> Notice(R.string.connect_v2_notice_not_played, R.string.connect_v2_notice_account_unverified)
                    else -> null
                }
            else -> null
        }
}
