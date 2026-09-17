package xyz.mooma0750.wave.handoff

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 端末と中継のあいだのメッセージ（ADR-0008）。WebSocket のテキストの1フレームに1つ。判別子は `op`。
 * 中継は封筒の本文を読まない。ペアリングの Hello・Confirm も不透明な JSON として運ぶだけ。
 */
@Serializable
sealed interface RelayMessage {
    // --- 中継 → 端末 ---

    /** 接続の直後。端末は `sign("wave-relay-auth-v1", len(host)‖host‖len(nonce)‖nonce)` を返す。 */
    @Serializable
    @SerialName("challenge")
    data class Challenge(
        val host: String,
        val nonce: String,
    ) : RelayMessage

    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val deviceId: String,
    ) : RelayMessage

    @Serializable
    @SerialName("delivered")
    data class Delivered(
        val envelope: Envelope,
    ) : RelayMessage

    @Serializable
    @SerialName("online_result")
    data class OnlineResult(
        val ids: List<String>,
    ) : RelayMessage

    /** 失敗の返事。`offline` などの送信の失敗には、どの封筒のことかを返す（送り手自身が付けた値だけ）。 */
    @Serializable
    @SerialName("error")
    data class Error(
        val code: String,
        val to: String? = null,
        val type: EnvelopeType? = null,
        val handoffId: String? = null,
    ) : RelayMessage

    /** 未登録の B からのペアリングの第1手を、pairing_open した A にだけ届ける。 */
    @Serializable
    @SerialName("pair_hello")
    data class PairHello(
        val tokenHash: String,
        val hello: JsonElement,
    ) : RelayMessage

    /** A の Confirm を、/pair の B に届ける。 */
    @Serializable
    @SerialName("pair_confirmed")
    data class PairConfirmed(
        val confirm: JsonElement,
    ) : RelayMessage

    // --- 端末 → 中継 ---

    @Serializable
    @SerialName("auth")
    data class Auth(
        val publicKey: String,
        val signature: String,
    ) : RelayMessage

    @Serializable
    @SerialName("send")
    data class Send(
        val envelope: Envelope,
    ) : RelayMessage

    @Serializable
    @SerialName("online")
    data class Online(
        val ids: List<String>,
    ) : RelayMessage

    /** ペアリングを承認した A が、B の署名公開鍵を中継に登録する。署名は `wave-relay-register-v1` で host・接続の nonce・公開鍵に。 */
    @Serializable
    @SerialName("register")
    data class Register(
        val publicKey: String,
        val signature: String,
    ) : RelayMessage

    /** 紛失した端末を中継から締め出す（決定 K-11、どの登録端末からでも）。署名は `wave-relay-revoke-v1`。 */
    @Serializable
    @SerialName("revoke")
    data class Revoke(
        val deviceId: String,
        val signature: String,
    ) : RelayMessage

    /** A が QR を出す前に。token の SHA-256（base64url）だけを預ける。2分・1件。 */
    @Serializable
    @SerialName("pairing_open")
    data class PairingOpen(
        val tokenHash: String,
    ) : RelayMessage

    @Serializable
    @SerialName("pair_confirm")
    data class PairConfirm(
        val tokenHash: String,
        val confirm: JsonElement,
    ) : RelayMessage

    /** 未登録の B が /pair で最初に送る。 */
    @Serializable
    @SerialName("pair_hello_in")
    data class PairHelloIn(
        val token: String,
        val hello: JsonElement,
    ) : RelayMessage

    companion object {
        const val AUTH_LABEL = "wave-relay-auth-v1"
        const val REGISTER_LABEL = "wave-relay-register-v1"
        const val REVOKE_LABEL = "wave-relay-revoke-v1"

        /** 1フレームの上限（snapshot の封筒に余裕を足す）。/pair はさらに小さく制限する。 */
        const val MAX_FRAME_CHARS = 1_200_000
        const val MAX_PAIR_FRAME_CHARS = 8 * 1024

        val json =
            Json {
                ignoreUnknownKeys = true
                classDiscriminator = "op"
            }

        fun encode(message: RelayMessage): String = json.encodeToString(serializer(), message)

        fun decode(text: String): RelayMessage? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
