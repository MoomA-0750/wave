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

        /** 一時停止した再生を再開する（本人の再生ボタンとして知らせない）。 */
        fun resume()

        /**
         * 検証済みのスナップショットで置き換えて positionMs から再生を始める。
         * アカウントを確かめたログインの世代が今の世代でなければ受け付けない（ADR-0007）。
         */
        fun adopt(
            snapshot: HandoffSnapshot,
            positionMs: Long,
            expectedLoginGeneration: Long,
        ): Boolean

        /**
         * 検証済みのスナップショットで置き換えるが、鳴らさない（ほかの端末で最後に鳴ったキューに合わせておく）。
         * 手元で鳴っている・鳴らそうとしているとき、ログインの世代が違うときは受け付けない。
         */
        fun stage(
            snapshot: HandoffSnapshot,
            expectedLoginGeneration: Long,
        ): Boolean

        /** ほかの端末からの遠隔操作に従う（本人の操作として知らせない）。 */
        fun control(
            action: xyz.mooma0750.wave.handoff.v2.ControlAction,
            positionMs: Long?,
        ): Boolean

        /** ほかの端末で足した曲を、自分のキューへ入れる（QueueSession.addFromRemote）。 */
        fun queueAdd(
            items: List<xyz.mooma0750.wave.handoff.HandoffItem>,
            next: Boolean,
            afterItemId: String?,
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

        /** 本人が一時停止した（D-9 の待ちの後に、再生を戻さない）。 */
        fun onUserPaused()
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
