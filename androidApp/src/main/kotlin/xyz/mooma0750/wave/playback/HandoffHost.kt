package xyz.mooma0750.wave.playback

import xyz.mooma0750.wave.handoff.HandoffSnapshot

/**
 * 端末間の受け渡しと再生サービスをつなぐ、プロセス内の入口（Phase 6）。再生サービスが onCreate で登録し、onDestroy で外す。
 * メインスレッドだけから使う。MediaSession のカスタムコマンド・外部のコントローラーは通さない（受け取った snapshot は検証済みのものだけを渡す）。
 */
class HandoffHost {
    interface Endpoint {
        /**
         * 引き取られる側: 一時停止して、今のキューのスナップショットを作る。キューが空なら null。
         * キューが、アカウントを確かめたログインの世代（expectedLoginGeneration）のものでなければ null（一時停止もしない）。
         */
        fun capture(
            handoffId: String,
            expectedLoginGeneration: Long,
        ): HandoffSnapshot?

        /**
         * 引き取る側: 検証済みのスナップショットでキューを置き換えて再生を始める。受け付けなければ false。
         * アカウントを確かめたログインの世代が今の世代でなければ受け付けない。
         */
        fun adopt(
            snapshot: HandoffSnapshot,
            expectedLoginGeneration: Long,
        ): Boolean

        /** 引き取られる側: キューがあるか（引き取りに応じられるか）。 */
        fun hasQueue(): Boolean

        /** 引き取られる側: 相手が受け取った。今の再生の履歴を送り済みにする（二重に載せない）。 */
        fun markHandedOff()

        /** 引き取られる側: 相手が取り消した。一時停止する前の再生に戻す。 */
        fun resumeAfterCancel()

        /** 引き取られる側: 前の要求が取り込まれなかったので「渡し済み」を戻す。再生は再開しない（続けて次の要求に応じるため）。 */
        fun forgetHandedOff()
    }

    var endpoint: Endpoint? = null
        private set

    fun register(endpoint: Endpoint) {
        this.endpoint = endpoint
    }

    fun unregister(endpoint: Endpoint) {
        if (this.endpoint === endpoint) this.endpoint = null
    }
}
