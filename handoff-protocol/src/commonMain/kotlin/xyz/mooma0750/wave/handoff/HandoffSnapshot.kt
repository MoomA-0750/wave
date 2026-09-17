package xyz.mooma0750.wave.handoff

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 引き取りのときに、再生中の端末（A）から手元の端末（B）へ送るキューのスナップショット（Phase 6、ADR-0009）。
 * 端末の中の状態（キューの世代・ログインの取得時刻・cpn）と認証情報は入れない。評価も送らない（B が読み直す）。
 * 端末の外に出るので、プラットフォームに依らない形に凍結する（Media3 の値・ドメインのクラスを持ち込まない）。
 */
@Serializable
data class HandoffSnapshot(
    val schemaVersion: Int,
    /** B が発行した引き取りの ID。B は自分が発行して待っているものだけを受ける。 */
    val handoffId: String,
    val items: List<HandoffItem>,
    val currentItemId: String,
    val positionMs: Long,
    val playing: Boolean,
    val repeat: HandoffRepeat,
    val source: HandoffSource?,
    val continuation: String?,
    val radioPlaylistId: String?,
    val exhausted: Boolean,
    /** 今の曲を A で聴いた時間（A の単調時計で数えた値、決定 K-8）。 */
    val listenedMs: Long,
    /** 今の曲の再生履歴を A が送り済みか。 */
    val historySent: Boolean,
    /** 表示とログのためだけ（端末間の時計はずれるので判定に使わない）。 */
    val capturedAtEpochMs: Long,
)

@Serializable
data class HandoffItem(
    /** 項目ごとの ID（UUID）。 */
    val itemId: String,
    val videoId: String,
    val title: String,
    val artists: List<HandoffRef>,
    val album: HandoffRef?,
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
    val origin: HandoffOrigin,
)

@Serializable
data class HandoffRef(
    val name: String,
    val browseId: String?,
)

@Serializable
enum class HandoffOrigin {
    @SerialName("source")
    Source,

    @SerialName("suggestion")
    Suggestion,

    @SerialName("user")
    UserAdded,
}

@Serializable
enum class HandoffRepeat {
    @SerialName("off")
    Off,

    @SerialName("one")
    One,

    @SerialName("all")
    All,
}

@Serializable
sealed interface HandoffSource {
    @Serializable
    @SerialName("radio")
    data class Radio(
        val seedVideoId: String,
    ) : HandoffSource

    @Serializable
    @SerialName("playlist")
    data class Playlist(
        val playlistId: String,
    ) : HandoffSource
}

/** 受け取ったスナップショットの検証の結果。 */
sealed interface HandoffDecodeResult {
    data class Ok(
        val snapshot: HandoffSnapshot,
    ) : HandoffDecodeResult

    /** 形式のバージョンが違う（相手を更新する必要がある）。 */
    data class UnsupportedVersion(
        val version: Int?,
    ) : HandoffDecodeResult

    /** 読めない・上限を超える・矛盾している。理由は種類だけ（値を含めない）。 */
    data class Rejected(
        val reason: String,
    ) : HandoffDecodeResult
}

object HandoffCodec {
    const val SCHEMA_VERSION = 1

    /** 暗号化する前のバイト数の上限。 */
    const val MAX_BYTES = 512 * 1024
    const val MAX_PLAYED_ITEMS = 20
    const val MAX_UPCOMING_ITEMS = 300
    private const val MAX_TEXT = 500
    private const val MAX_CONTINUATION = 4096
    private const val MAX_ARTISTS = 20
    private const val MAX_TIME_MS = 24 * 3600 * 1000L

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val BROWSE_ID = Regex("^[A-Za-z0-9_-]{2,64}$")
    private val PLAYLIST_ID = Regex("^[A-Za-z0-9_-]{2,128}$")
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val HANDOFF_ID = UUID
    private val THUMBNAIL_HOSTS = listOf(".googleusercontent.com", ".ggpht.com", ".ytimg.com")

    private val json =
        Json {
            // 相手が新しいビルドでも、知らない項目は無視して必須項目だけを検証する
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }

    fun encode(snapshot: HandoffSnapshot): String = json.encodeToString(HandoffSnapshot.serializer(), snapshot)

    /**
     * 受け取った文字列を読み、上限と形を検証する。サムネイルのホストが合わないものは、スナップショットを拒否せずに null にする。
     * @param expectedHandoffId B が発行して待っている ID。違えば拒否
     */
    fun decode(
        text: String,
        expectedHandoffId: String,
    ): HandoffDecodeResult {
        if (text.encodeToByteArray().size > MAX_BYTES) return HandoffDecodeResult.Rejected("too_large")
        val version = runCatching { json.decodeFromString(VersionOnly.serializer(), text).schemaVersion }.getOrNull()
        if (version != SCHEMA_VERSION) return HandoffDecodeResult.UnsupportedVersion(version)
        val snapshot = runCatching { json.decodeFromString(HandoffSnapshot.serializer(), text) }.getOrNull() ?: return HandoffDecodeResult.Rejected("unreadable")
        return validate(snapshot, expectedHandoffId)
    }

    fun validate(
        snapshot: HandoffSnapshot,
        expectedHandoffId: String,
    ): HandoffDecodeResult {
        fun reject(reason: String) = HandoffDecodeResult.Rejected(reason)
        if (snapshot.handoffId != expectedHandoffId || !HANDOFF_ID.matches(snapshot.handoffId)) return reject("handoff_id")
        if (snapshot.items.isEmpty() || snapshot.items.size > MAX_PLAYED_ITEMS + 1 + MAX_UPCOMING_ITEMS) return reject("item_count")
        val ids = snapshot.items.map { it.itemId }
        if (ids.toSet().size != ids.size || ids.any { !UUID.matches(it) }) return reject("item_id")
        val currentIndex = ids.indexOf(snapshot.currentItemId)
        if (currentIndex < 0) return reject("current_item")
        if (currentIndex > MAX_PLAYED_ITEMS || snapshot.items.size - 1 - currentIndex > MAX_UPCOMING_ITEMS) return reject("item_window")
        if (snapshot.listenedMs < 0 || snapshot.positionMs < 0) return reject("negative_time")
        for (item in snapshot.items) {
            if (!VIDEO_ID.matches(item.videoId)) return reject("video_id")
            if (item.title.isEmpty() || item.title.length > MAX_TEXT) return reject("title")
            if (item.artists.size > MAX_ARTISTS || item.artists.any { !validRef(it) }) return reject("artist")
            if (item.album != null && !validRef(item.album)) return reject("album")
            if (item.durationSeconds != null && item.durationSeconds !in 0..(24 * 3600)) return reject("duration")
        }
        when (val source = snapshot.source) {
            is HandoffSource.Radio -> if (!VIDEO_ID.matches(source.seedVideoId)) return reject("source")
            is HandoffSource.Playlist -> if (!PLAYLIST_ID.matches(source.playlistId)) return reject("source")
            null -> Unit
        }
        if (snapshot.radioPlaylistId != null && !PLAYLIST_ID.matches(snapshot.radioPlaylistId)) return reject("radio_playlist")
        if (snapshot.continuation != null && (snapshot.continuation.length > MAX_CONTINUATION || snapshot.continuation.any { it.isISOControl() })) return reject("continuation")
        val current = snapshot.items[currentIndex]
        val durationMs = current.durationSeconds?.let { it * 1000L }
        return HandoffDecodeResult.Ok(
            snapshot.copy(
                items = snapshot.items.map { it.copy(thumbnailUrl = it.thumbnailUrl?.takeIf(::allowedThumbnail)) },
                // 位置と聴いた時間を曲の長さ以下（長さが無ければ24時間以下）に切り詰める（拒否はしない）
                positionMs = snapshot.positionMs.coerceAtMost(durationMs ?: MAX_TIME_MS),
                listenedMs = snapshot.listenedMs.coerceAtMost(durationMs ?: MAX_TIME_MS),
            ),
        )
    }

    private fun validRef(ref: HandoffRef): Boolean =
        ref.name.isNotEmpty() && ref.name.length <= MAX_TEXT && (ref.browseId == null || BROWSE_ID.matches(ref.browseId))

    /** https・既知の画像ホストだけ（ポート・userinfo の無いもの）。 */
    private fun allowedThumbnail(url: String): Boolean {
        if (!url.startsWith("https://") || url.length > 2048) return false
        val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority || ':' in authority) return false
        val host = authority.lowercase()
        return THUMBNAIL_HOSTS.any { host.endsWith(it) }
    }

    @Serializable
    private data class VersionOnly(
        val schemaVersion: Int? = null,
    )

    /**
     * 送る側の切り詰め: 今の曲より前は MAX_PLAYED_ITEMS 件、後ろは MAX_UPCOMING_ITEMS 件まで。
     * 後ろを切ったら continuation は読み込み済みの末尾を指さなくなるので捨てる。
     * @return （切り詰めた項目, 今の項目の位置, continuation を捨てたか）
     */
    fun <T> window(
        items: List<T>,
        currentIndex: Int,
    ): Triple<List<T>, Int, Boolean> {
        val start = (currentIndex - MAX_PLAYED_ITEMS).coerceAtLeast(0)
        val end = (currentIndex + 1 + MAX_UPCOMING_ITEMS).coerceAtMost(items.size)
        return Triple(items.subList(start, end), currentIndex - start, end < items.size)
    }
}
