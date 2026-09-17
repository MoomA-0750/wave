package xyz.mooma0750.wave.playback

import xyz.mooma0750.wave.handoff.HandoffRepeat
import xyz.mooma0750.wave.handoff.HandoffSnapshot

/**
 * Connect v2 と再生サービスの入口（Phase 6b）。再生サービスが onCreate で登録し、onDestroy で外す。Endpoint は Main で呼ぶ。
 * 再生サービスは、キュー・位置の変化と「本人が再生を始めた」を Listener に知らせる（Main から呼ぶ。受ける側がスレッドを移す）。
 */
class ConnectV2Host {
    interface Endpoint {
        /** 今のキューの書き出し（一時停止しない）。キューが空・ログインしていなければ null。 */
        fun snapshot(): HandoffSnapshot?

        /** 今の再生の位置など。キューが空なら null。 */
        fun cursor(): Cursor?

        fun pause()

        /**
         * 検証済みのスナップショットで置き換えて positionMs から再生を始める。
         * アカウントを確かめたログインの世代が今の世代でなければ受け付けない（ADR-0007）。
         */
        fun adopt(
            snapshot: HandoffSnapshot,
            positionMs: Long,
            expectedLoginGeneration: Long,
        ): Boolean
    }

    data class Cursor(
        val currentItemId: String,
        val positionMs: Long,
        val playing: Boolean,
        val repeat: HandoffRepeat,
        val listenedMs: Long,
        val historySent: Boolean,
    )

    interface Listener {
        fun onQueueChanged()

        /** @param significant 再生・一時停止・曲の遷移（すぐ書く）。false はシークなど（間引く） */
        fun onCursorChanged(significant: Boolean)

        /**
         * 本人が再生を始めた・再開した・曲を選んだ（L-3）。
         * @param newSelection 曲を選んだ（キューを置き換えた・飛んだ）。false は再生ボタン（画面・通知・Bluetooth）
         */
        fun onUserStartedPlayback(newSelection: Boolean)
    }

    @Volatile
    var endpoint: Endpoint? = null
        private set

    @Volatile
    var listener: Listener? = null

    fun register(endpoint: Endpoint) {
        this.endpoint = endpoint
    }

    fun unregister(endpoint: Endpoint) {
        if (this.endpoint === endpoint) this.endpoint = null
    }
}
