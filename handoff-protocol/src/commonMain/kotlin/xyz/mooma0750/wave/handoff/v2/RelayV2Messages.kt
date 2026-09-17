package xyz.mooma0750.wave.handoff.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** 状態の種類（サーバーが版を持つもの）。 */
@Serializable
enum class StateKind {
    @SerialName("cursor")
    Cursor,

    @SerialName("queue")
    Queue,
}

/** サーバーが持つ最新の状態（配るときの形）。 */
@Serializable
data class StoredState(
    val kind: StateKind,
    val version: Long,
    val doc: SealedDoc,
    val storeId: String,
    val receivedAtServerMs: Long,
    val serverNowMs: Long,
    val activeDeviceId: String? = null,
)

/**
 * 中継 v2 のメッセージ（`/v2/connect`、`/v2/pair`。判別子は `op`、`dev/active/phase6b-plan.md` のワイヤの表）。
 * サーバーは SealedDoc の中身を読まない。見出し（書き手・版・一覧の頭）と、送り手の接続の端末 ID だけで振り分ける。
 */
@Serializable
sealed interface RelayV2 {
    // --- 接続 ---

    @Serializable
    @SerialName("challenge")
    data class Challenge(
        val host: String,
        val nonce: String,
    ) : RelayV2

    @Serializable
    @SerialName("auth")
    data class Auth(
        val publicKey: String,
        val signature: String,
    ) : RelayV2

    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val deviceId: String,
        val storeId: String,
        val householdId: String? = null,
        val headSeq: Long? = null,
        val headHash: String? = null,
        val activeDeviceId: String? = null,
        val online: List<String> = emptyList(),
    ) : RelayV2

    // --- 端末一覧の鎖 ---

    @Serializable
    @SerialName("household_create")
    data class HouseholdCreate(
        val genesis: ChainRecord,
    ) : RelayV2

    @Serializable
    @SerialName("chain_get")
    data class ChainGet(
        val fromSeq: Long = 0,
    ) : RelayV2

    @Serializable
    @SerialName("chain")
    data class Chain(
        val records: List<ChainRecord>,
    ) : RelayV2

    @Serializable
    @SerialName("chain_append")
    data class ChainAppend(
        val record: ChainRecord,
    ) : RelayV2

    @Serializable
    @SerialName("chain_ok")
    data class ChainOk(
        val headSeq: Long,
        val headHash: String,
    ) : RelayV2

    @Serializable
    @SerialName("chain_conflict")
    data class ChainConflict(
        val headSeq: Long,
        val headHash: String,
    ) : RelayV2

    @Serializable
    @SerialName("chain_changed")
    data class ChainChanged(
        val headSeq: Long,
        val headHash: String,
    ) : RelayV2

    // --- 状態 ---

    @Serializable
    @SerialName("state_get")
    data object StateGet : RelayV2

    @Serializable
    @SerialName("state_put")
    data class StatePut(
        val requestId: String,
        val kind: StateKind,
        val expectVersion: Long,
        val doc: SealedDoc,
    ) : RelayV2

    @Serializable
    @SerialName("state_ok")
    data class StateOk(
        val requestId: String,
        val kind: StateKind,
        val version: Long,
    ) : RelayV2

    @Serializable
    @SerialName("state_conflict")
    data class StateConflict(
        val requestId: String,
        val kind: StateKind,
        val latest: StoredState? = null,
    ) : RelayV2

    @Serializable
    @SerialName("state")
    data class State(
        val state: StoredState,
    ) : RelayV2

    // --- 再生を始める・引き取る ---

    @Serializable
    @SerialName("claim")
    data class Claim(
        val claimId: String,
        val yieldDoc: SealedDoc,
    ) : RelayV2

    @Serializable
    @SerialName("transfer")
    data class Transfer(
        val claimId: String,
        val basedOnVersion: Long,
        val yieldDoc: SealedDoc,
    ) : RelayV2

    @Serializable
    @SerialName("yield")
    data class YieldDelivery(
        val claimId: String,
        val yieldDoc: SealedDoc,
    ) : RelayV2

    @Serializable
    @SerialName("yielded")
    data class Yielded(
        val claimId: String,
        val expectVersion: Long,
        val doc: SealedDoc,
    ) : RelayV2

    @Serializable
    @SerialName("release")
    data class Release(
        val claimId: String,
    ) : RelayV2

    @Serializable
    @SerialName("active_changed")
    data class ActiveChanged(
        val activeDeviceId: String?,
        val claimId: String? = null,
        val claimOrder: Long,
        /** transfer の途中（新しい端末の最初の書き込みを待っている）。 */
        val pendingOwner: String? = null,
    ) : RelayV2

    @Serializable
    @SerialName("pending_expired")
    data class PendingExpired(
        val claimId: String,
    ) : RelayV2

    @Serializable
    @SerialName("presence")
    data class Presence(
        val online: List<String>,
    ) : RelayV2

    // --- 加入（v1 と同じ手順、Hello・Confirm は不透明な JSON） ---

    @Serializable
    @SerialName("pairing_open")
    data class PairingOpen(
        val tokenHash: String,
    ) : RelayV2

    @Serializable
    @SerialName("pair_hello_in")
    data class PairHelloIn(
        val token: String,
        val hello: JsonElement,
    ) : RelayV2

    @Serializable
    @SerialName("pair_hello")
    data class PairHello(
        val tokenHash: String,
        val hello: JsonElement,
    ) : RelayV2

    @Serializable
    @SerialName("pair_confirm")
    data class PairConfirm(
        val tokenHash: String,
        val confirm: JsonElement,
    ) : RelayV2

    @Serializable
    @SerialName("pair_confirmed")
    data class PairConfirmed(
        val confirm: JsonElement,
    ) : RelayV2

    @Serializable
    @SerialName("error")
    data class Error(
        val code: String,
        val requestId: String? = null,
    ) : RelayV2

    companion object {
        const val AUTH_LABEL = "wave-relay-v2-auth"
        const val MAX_FRAME_CHARS = 1_200_000
        const val MAX_PAIR_FRAME_CHARS = 8 * 1024

        val json =
            Json {
                ignoreUnknownKeys = true
                classDiscriminator = "op"
                encodeDefaults = true
            }

        fun encode(message: RelayV2): String = json.encodeToString(serializer(), message)

        fun decode(text: String): RelayV2? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
