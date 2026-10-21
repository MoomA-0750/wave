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

    /** ライブラリ（RSS で登録した番組・聴いたところ、ADR-0013）。家のどの端末も書ける。 */
    @SerialName("library")
    Library,
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
    /** 鳴らしている端末の音量（PC はアプリの中の音量、スマホ・時計は本体の音量）（0〜100）。ほかの端末の音量の表示に使う。前の版の書き手は付けない（null）。 */
    val volumePercent: Int? = null,
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
        /**
         * 送り手が見ていた、受け手の今の曲（次へ・前へ・シーク）。受け手の今の曲と違えば従わない
         * （曲の終わりぎわに押した「次へ」が、曲が変わった後に届いて次の曲まで飛ばすのを防ぐ）。前の版の送り手は付けない（null）。
         */
        val onItemId: String? = null,
        /** 音量（volume のときだけ、0〜100）。鳴らしている端末の音量（PC はアプリの中の音量、スマホ・時計は本体の音量）にする。 */
        val volumePercent: Int? = null,
    ) : CommandBody

    /**
     * 遠隔のキューへの追加: 鳴っていない端末から、今の再生中の端末へ「この曲をキューに入れて」。従う条件は Control と同じ。
     * 受け手は afterItemId の曲が自分のキューにあればその直後へ、無ければ next なら今の曲の直後、そうでなければ
     * 自分で足した曲のかたまりの最後へ入れる。項目の ID は送り手が振ったものをそのまま使う（分けて送った続きを afterItemId でつなぐ）。
     * 1件の平文は詰め物の1ブロック（4KB）に収める。曲が多ければ送り手が分けて、2件目からは前の件の最後の曲を afterItemId にする。
     */
    @Serializable
    @SerialName("queue_add")
    data class QueueAdd(
        val commandId: String,
        val from: String,
        val to: String,
        val items: List<xyz.mooma0750.wave.handoff.HandoffItem>,
        /** 「次に再生」なら true、「キューに追加」なら false。 */
        val next: Boolean,
        val afterItemId: String? = null,
        /** 送り手が見ていた、受け手のカーソルの版。 */
        val basedOnVersion: Long,
        val accountTag: String,
    ) : CommandBody

    /**
     * 遠隔のキューの並べ替え・外す: 鳴っていない端末のキューの画面から、今の再生中の端末へ。従う条件は QueueAdd と同じ。
     * op が "move" なら itemIds の1曲を afterItemId の曲の直後へ（afterItemId が無ければ今の曲の直後へ）動かす。
     * "remove" なら itemIds の曲を外す。どれも今の曲より後ろの曲だけで、受け手のキューに無い ID・今の曲・前の曲は無視する。
     */
    @Serializable
    @SerialName("queue_edit")
    data class QueueEdit(
        val commandId: String,
        val from: String,
        val to: String,
        val op: String,
        val itemIds: List<String>,
        val afterItemId: String? = null,
        /** 送り手が見ていた、受け手のカーソルの版。 */
        val basedOnVersion: Long,
        val accountTag: String,
    ) : CommandBody {
        companion object {
            const val MOVE = "move"
            const val REMOVE = "remove"
        }
    }

    /**
     * ログインを渡す（ADR-0015）: 家の端末から wave-api（種類 api の端末）へ、本人が押したときだけ。宛先の端末の受け取りの鍵にだけ包む
     * （ほかの端末には包まない）。受け手は、自分宛て・送り主が署名した player・処理していない ID・出してから5分以内のときだけ受け取る。
     * 値は WebView のログインで取った生の値（Credentials.fromLogin に渡すもの）。
     */
    @Serializable
    @SerialName("login_grant")
    data class LoginGrant(
        val commandId: String,
        val from: String,
        val to: String,
        val cookie: String,
        val visitorData: String? = null,
        val dataSyncId: String? = null,
        val obtainedAtEpochMs: Long,
    ) : CommandBody {
        override fun toString(): String = "LoginGrant(commandId=$commandId, from=$from, to=$to)"
    }

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

    /** 鳴らしている端末の音量（PC はアプリの中の音量、スマホ・時計は本体の音量）を変える（volumePercent）。 */
    @SerialName("volume")
    Volume,
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

/**
 * ライブラリの文書の中身（ADR-0013）。RSS で登録した番組と、エピソードの聴いたところ。
 * 番組・エピソードごとに、updatedAtMs の新しい方を残して合わせる（[merge]）。外した番組も印（removed）として残す。
 */
@Serializable
data class LibraryBody(
    val v: Int = VERSION,
    /** フィードの URL ごと。 */
    val feeds: Map<String, LibraryFeed> = emptyMap(),
    /** 音声の URL ごと。 */
    val positions: Map<String, LibraryPosition> = emptyMap(),
) {
    /** 2つを合わせる。同じ時刻なら [other] を残す（自分の書き込みが後から届いた相手の書き込みを消さないよう、呼ぶ側は相手を other に渡す）。 */
    fun merge(other: LibraryBody): LibraryBody {
        val feeds = (feeds.keys + other.feeds.keys).associateWith { key -> newer(this.feeds[key], other.feeds[key]) { it.updatedAtMs } }
        val positions = (positions.keys + other.positions.keys).associateWith { key -> newer(this.positions[key], other.positions[key]) { it.updatedAtMs } }
        return LibraryBody(VERSION, feeds, trim(positions))
    }

    companion object {
        const val VERSION = 1

        /** 聴いたところは新しい順にこの数まで（文書を数百 KB に収める）。 */
        const val MAX_POSITIONS = 500

        private fun <T : Any> newer(
            mine: T?,
            other: T?,
            at: (T) -> Long,
        ): T =
            when {
                mine == null -> other!!
                other == null -> mine
                at(mine) > at(other) -> mine
                else -> other
            }

        private fun trim(positions: Map<String, LibraryPosition>): Map<String, LibraryPosition> =
            if (positions.size <= MAX_POSITIONS) positions else positions.entries.sortedByDescending { it.value.updatedAtMs }.take(MAX_POSITIONS).associate { it.key to it.value }
    }
}

@Serializable
data class LibraryFeed(
    val title: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val addedAtMs: Long = 0,
    /** 外した（この印のほうが新しければ、ほかの端末でも外す）。 */
    val removed: Boolean = false,
    val updatedAtMs: Long,
)

@Serializable
data class LibraryPosition(
    val positionSeconds: Int,
    val durationSeconds: Int? = null,
    val finished: Boolean = false,
    val updatedAtMs: Long,
)

