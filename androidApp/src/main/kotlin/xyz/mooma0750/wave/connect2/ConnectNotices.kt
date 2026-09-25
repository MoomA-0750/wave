package xyz.mooma0750.wave.connect2

import xyz.mooma0750.wave.handoff.v2.ClientEvent

/** Connect v2 の出来事を、本人への知らせの文言にする（画面が前面なら Toast、裏なら通知）。 */
internal object ConnectNotices {
    data class Notice(
        val title: String,
        val text: String,
    )

    /** 画面が前面のときの Toast。知らせないものは null。 */
    fun toast(event: ClientEvent): String? =
        when (event) {
            is ClientEvent.Stopped -> "他の端末で再生が始まりました"
            is ClientEvent.TransferCompleted -> "引き取りました"
            is ClientEvent.TransferFailed -> "引き取れませんでした（${event.reason}）"
            is ClientEvent.StoreReset -> "中継の状態が作り直されました（端末の連携の画面で確かめてください）"
            ClientEvent.ChainForked, ClientEvent.ChainRolledBack, is ClientEvent.ChainInvalid -> "中継の端末一覧を確かめられませんでした"
            is ClientEvent.ServerError ->
                when (event.code) {
                    ConnectV2Manager.REMOTE_PLAYING -> "他の端末で再生中です（端末の連携の画面から「ここで再生」）"
                    ConnectV2Manager.RELAY_UNREACHABLE -> "中継につながらず、他の端末の再生を確かめられませんでした"
                    ConnectV2Manager.NOT_MEMBER -> "この端末は家の端末として受け付けられませんでした（端末の連携の画面を確かめてください）"
                    ConnectV2Manager.ACCOUNT_UNVERIFIED -> "アカウントを確かめられず、他の端末の再生を確かめられませんでした"
                    ConnectV2Manager.HOUSEHOLD_CREATE_UNSENT -> "家を作る要求を中継に送れませんでした（中継に登録した端末か、接続を確かめてください）"
                    else -> null
                }
            else -> null
        }

    /** 裏にいるときの通知（Toast は裏では出ない）。止められた・再生ボタンで鳴らさなかったときだけ（D-9、レビュー p6b34 中-5）。 */
    fun background(event: ClientEvent): Notice? =
        when (event) {
            is ClientEvent.Stopped -> Notice("他の端末で再生が始まりました", "この端末の再生は止めました")
            is ClientEvent.ServerError ->
                when (event.code) {
                    ConnectV2Manager.REMOTE_PLAYING -> Notice("他の端末で再生中です", "ここで聴くには、wave を開いて「ここで再生」を押してください")
                    ConnectV2Manager.RELAY_UNREACHABLE -> Notice("再生しませんでした", "中継につながらず、他の端末の再生を確かめられませんでした")
                    ConnectV2Manager.NOT_MEMBER -> Notice("再生しませんでした", "この端末は家の端末として受け付けられませんでした")
                    ConnectV2Manager.ACCOUNT_UNVERIFIED -> Notice("再生しませんでした", "アカウントを確かめられず、他の端末の再生を確かめられませんでした")
                    else -> null
                }
            else -> null
        }
}
