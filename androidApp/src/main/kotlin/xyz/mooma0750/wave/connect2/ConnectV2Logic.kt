package xyz.mooma0750.wave.connect2

import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.handoff.HandoffCodec
import xyz.mooma0750.wave.handoff.HandoffDecodeResult
import xyz.mooma0750.wave.handoff.HandoffSnapshot
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.CursorBody
import java.util.Base64

/** 家ごとのアカウントタグ（D-3）: `SHA-256(ラベル ‖ householdId ‖ DATASYNC_ID)` を base64url（詰め物なし）で。値はログに出さない。 */
internal object AccountTag {
    const val LABEL = "wave-v2-account-tag"

    fun of(
        householdId: String,
        accountId: String,
    ): String {
        val digest = WaveCrypto.sha256(WaveCrypto.lengthPrefixed(LABEL.encodeToByteArray(), householdId.encodeToByteArray(), accountId.encodeToByteArray()))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}

/** 中継から取り込むキュー（Android の状態に依らない）。 */
internal object RemoteQueue {
    private val json = Json { ignoreUnknownKeys = true }

    /** キューの doc の文字列を検証して読む。読めなければ null。 */
    fun decode(text: String): HandoffSnapshot? {
        val id = runCatching { json.decodeFromString(HandoffSnapshot.serializer(), text).handoffId }.getOrNull() ?: return null
        return (HandoffCodec.decode(text, id) as? HandoffDecodeResult.Ok)?.snapshot
    }

    /**
     * キューの doc（置いた時点の曲）を、カーソルの今の曲・リピート・履歴に合わせる（レビュー p6b34 高-2）。
     * 今の曲がキューに無ければ取り込まない。窓（前20件・後300件）は今の曲で切り直し、位置は曲の長さで切り詰める（validate）。
     */
    fun atCursor(
        snapshot: HandoffSnapshot,
        cursor: CursorBody,
        positionMs: Long,
    ): HandoffSnapshot? {
        val index = snapshot.items.indexOfFirst { it.itemId == cursor.currentItemId }
        if (index < 0) return null
        val (items, _, truncatedTail) = HandoffCodec.window(snapshot.items, index)
        val moved =
            snapshot.copy(
                items = items,
                // 後ろを切ったら、続きの取得は読み込み済みの末尾を指さないので捨てる（再レビュー 低-G）
                continuation = if (truncatedTail) null else snapshot.continuation,
                exhausted = if (truncatedTail) false else snapshot.exhausted,
                currentItemId = cursor.currentItemId,
                positionMs = positionMs.coerceAtLeast(0),
                repeat = cursor.repeat,
                listenedMs = cursor.listenedMs.coerceAtLeast(0),
                historySent = cursor.historySent,
            )
        return (HandoffCodec.validate(moved, moved.handoffId) as? HandoffDecodeResult.Ok)?.snapshot
    }
}
