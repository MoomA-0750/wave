package xyz.mooma0750.wave.handoff.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.handoff.HandoffRepeat

/**
 * Connect v2 の形式（Phase 6b、`dev/active/phase6b-plan.md` の D-1〜D-5）。
 * 端末一覧は署名付きの記録の鎖で、状態・命令は書いた端末の署名つきの SealedDoc。バイナリは base64url（パディングなし）の文字列で運ぶ。
 */
@Serializable
enum class Role {
    /** 再生する端末（アカウントの照合あり）。 */
    @SerialName("player")
    Player,

    /** 表示と操作だけ（Phase 7 の PC）。再生しない、アカウントを持たない。 */
    @SerialName("controller")
    Controller,
}

@Serializable
enum class ChainType {
    @SerialName("genesis")
    Genesis,

    @SerialName("add")
    Add,

    @SerialName("remove")
    Remove,
}

/** 家に加わる端末の鍵。kxSignature は、その端末の身元鍵での `wave-v2-kx`（deviceId・kxPub・role）の署名。 */
@Serializable
data class MemberKeys(
    val deviceId: String,
    /** 身元の署名公開鍵（SPKI）。deviceId はこの SHA-256。 */
    val identityPub: String,
    /** 受け取りの鍵（P-256、非圧縮 SEC1）。 */
    val kxPub: String,
    val role: Role,
    val kxSignature: String,
)

/**
 * 端末一覧の記録。genesis は householdId を持たない（householdId = genesis のハッシュ）。
 * add・genesis は target、remove は removedDeviceId を持つ。
 */
@Serializable
data class ChainRecord(
    val v: Int = VERSION,
    val householdId: String? = null,
    val seq: Long,
    val prevHash: String? = null,
    val type: ChainType,
    val target: MemberKeys? = null,
    val removedDeviceId: String? = null,
    val signerId: String,
    val signature: String,
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
enum class DocKind {
    @SerialName("cursor")
    Cursor,

    @SerialName("queue")
    Queue,

    @SerialName("command")
    Command,

    @SerialName("profile")
    Profile,
}

/** SealedDoc の見出し（平文。署名と、内容・包みの AAD に入る）。 */
@Serializable
data class DocHeader(
    val v: Int = VERSION,
    val kind: DocKind,
    val householdId: String,
    val writerId: String,
    val version: Long,
    /** 同じ書き手の、同じ kind の直前の doc のハッシュ（差し替えの検出だけに使う）。 */
    val prevHash: String? = null,
    val headSeq: Long,
    val headHash: String,
    val issuedAtEpochMs: Long,
) {
    companion object {
        const val VERSION = 1
    }
}

/** 宛先ごとの包み: 内容鍵を、その端末の kx へ HPKE で包んだもの。 */
@Serializable
data class DocRecipient(
    val deviceId: String,
    val enc: String,
    val wrappedKey: String,
)

@Serializable
data class SealedDoc(
    val header: DocHeader,
    val nonce: String,
    val ciphertext: String,
    val recipients: List<DocRecipient>,
    val signature: String,
)

/** カーソル（D-5）。小さく、再生・一時停止・シーク・遷移のたびに書く。 */
@Serializable
data class CursorBody(
    /** このカーソルの曲が載っているキューの doc のハッシュ（DocSealer.hash）。書き手ごとの番号にしない（レビュー p6b12 中-4）。 */
    val queueRef: String,
    val currentItemId: String,
    val positionMs: Long,
    val playing: Boolean,
    val repeat: HandoffRepeat,
    /** このセッションを始めたときに見ていた最新のカーソルの版（L-10）。 */
    val sessionBase: Long,
    /** このセッションで自分が最初に書けたカーソルの版。書けるまでは null。 */
    val sessionStartVersion: Long? = null,
    val deviceSeq: Long,
    val listenedMs: Long,
    val historySent: Boolean,
    val accountTag: String,
    /** 表示だけ（順序の判断に使わない）。 */
    val startedAtEpochMs: Long,
)

/** キュー（D-5）。snapshot は HandoffCodec.encode の文字列（取り込む側が HandoffCodec.decode で検証する）。 */
@Serializable
data class QueueBody(
    val accountTag: String,
    val snapshot: String,
)

@Serializable
sealed interface CommandBody {
    /** 再生を始めた端末から、今の再生中の端末へ「止まって」（D-7）。 */
    @Serializable
    @SerialName("yield")
    data class Yield(
        val claimId: String,
        val from: String,
        val to: String,
        val sessionBase: Long,
        val accountTag: String,
    ) : CommandBody

    /**
     * 遠隔操作: 鳴っていない端末から、今の再生中の端末へ「再生・一時停止・次へ・前へ・シーク」。
     * 受け手は、自分宛て・自分が書き手・同じアカウント・処理していない ID・見ていた版が近いときだけ従う（中継の再送・遅延を古い操作として扱わない）。
     */
    @Serializable
    @SerialName("control")
    data class Control(
        val commandId: String,
        val from: String,
        val to: String,
        val action: ControlAction,
        /** シークの位置（seek のときだけ）。 */
        val positionMs: Long? = null,
        /** 送り手が見ていた、受け手のカーソルの版。 */
        val basedOnVersion: Long,
        val accountTag: String,
    ) : CommandBody

    /** 端末の表示名を家のほかの端末に知らせる（名前は鎖に入れないので、加入した2台以外にも伝える）。宛先は家の全端末。 */
    @Serializable
    @SerialName("profile")
    data class Profile(
        val from: String,
        val name: String,
        /** 端末の種類（"phone"・"pc" など。表示の絵だけに使う）。 */
        val kind: String? = null,
    ) : CommandBody
}

@Serializable
enum class ControlAction {
    @SerialName("play")
    Play,

    @SerialName("pause")
    Pause,

    @SerialName("next")
    Next,

    @SerialName("previous")
    Previous,

    @SerialName("seek")
    Seek,

    /** 宛先の端末に「ここで再生（引き取り）」を頼む。宛先は書き手でない端末。 */
    @SerialName("transfer")
    Transfer,
}

@Serializable
data class ProfileBody(
    val name: String,
)

object ConnectJson {
    val json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            encodeDefaults = true
        }
}
