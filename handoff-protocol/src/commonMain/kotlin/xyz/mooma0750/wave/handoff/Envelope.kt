package xyz.mooma0750.wave.handoff

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 中継を通る封筒（ADR-0008・0009）。中継が読むのは `to` だけ（振り分け）と `from`（接続の鍵との一致の確認）。
 * 本文は送り手→受け手の方向の鍵で AES-GCM に封じ、v・suite・from・to・type・seq・handoffId を AAD にする。
 * 端末 ID は署名公開鍵の SHA-256（base64url、パディングなし）。
 */
@Serializable
data class Envelope(
    val v: Int,
    val suite: Int,
    val from: String,
    val to: String,
    val type: EnvelopeType,
    /** ペアごと・方向ごとに単調増加。受け手は最後に受けた値以下を捨てる。 */
    val seq: Long,
    val handoffId: String? = null,
    val nonce: String,
    val ciphertext: String,
) {
    companion object {
        const val VERSION = 1

        /** P-256 ECDH + HKDF-SHA256 + AES-256-GCM。 */
        const val SUITE_P256_AESGCM = 1

        /** 暗号文の上限（snapshot の 512 KiB に余裕を足す）。 */
        const val MAX_CIPHERTEXT_CHARS = 1024 * 1024
    }
}

@Serializable
enum class EnvelopeType {
    /** B → A: 引き取りたい。 */
    @SerialName("request")
    Request,

    /** A → B: キューのスナップショット。 */
    @SerialName("snapshot")
    Snapshot,

    /** B → A: 受け取って再生を始めた。 */
    @SerialName("ack")
    Ack,

    /** B → A: 引き取りをやめた（A は再開してよい）。 */
    @SerialName("cancel")
    Cancel,

    /** どちらからでも: ペアを解除した。 */
    @SerialName("unpair")
    Unpair,

    /** Unpair を受けた側から: 解除を受け取った（送った側は、これを受けてから鍵を消す）。 */
    @SerialName("unpair_ack")
    UnpairAck,
}
