package xyz.mooma0750.wave.playback

import xyz.mooma0750.wave.handoff.HandoffSnapshot

/**
 * 端末間の受け渡しと再生サービスをつなぐ、プロセス内の入口（Phase 6）。再生サービスが onCreate で登録し、onDestroy で外す。
 * メインスレッドだけから使う。MediaSession のカスタムコマンド・外部のコントローラーは通さない（受け取った snapshot は検証済みのものだけを渡す）。
 */
class HandoffHost {
    interface Endpoint {
        /** 引き取られる側: 一時停止して、今のキューのスナップショットを作る。キューが空なら null。 */
        fun capture(handoffId: String): HandoffSnapshot?

        /** 引き取る側: 検証済みのスナップショットでキューを置き換えて再生を始める。受け付けなければ false。 */
        fun adopt(snapshot: HandoffSnapshot): Boolean
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
