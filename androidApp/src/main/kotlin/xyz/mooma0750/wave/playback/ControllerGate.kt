package xyz.mooma0750.wave.playback

/**
 * 再生サービスにつないでくるコントローラーを、どこまで受け入れるか（onConnect の判断。Android の状態に依らない）。
 * 判断の順番と中身は変えないこと（F1d inner gate 指摘1）。
 */
internal object ControllerGate {
    enum class Access {
        /** Media3 がプロセス内に作る通知用コントローラー: 既定の操作と評価のボタン（受けるときにトークンを確かめる）。 */
        OwnNotification,

        /** このアプリの画面: キューの操作を含むすべて。 */
        OwnUi,

        /** 信頼された外部・通知を読めるアプリ（時計・車載のコンパニオン）: 再生の操作だけ。 */
        PlaybackOnly,

        /** それ以外の他アプリ: 拒否（レガシーでも受け入れない。プラットフォームのセッションのトークンを渡さない）。 */
        Reject,
    }

    /**
     * @param readsOurNotification 本人が通知の読み取りを許可したアプリか。信頼されていないときだけ調べる（Media3 の isTrusted がこれを拾わないことがある）
     */
    fun classify(
        isOwnNotificationController: Boolean,
        isOwnUiController: Boolean,
        isTrusted: Boolean,
        readsOurNotification: () -> Boolean,
    ): Access =
        when {
            isOwnNotificationController -> Access.OwnNotification
            isOwnUiController -> Access.OwnUi
            isTrusted || readsOurNotification() -> Access.PlaybackOnly
            else -> Access.Reject
        }
}
