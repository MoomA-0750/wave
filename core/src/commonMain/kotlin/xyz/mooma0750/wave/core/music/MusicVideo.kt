package xyz.mooma0750.wave.core.music

/** YouTube Music の曲の種類（`musicVideoType`）。 */
@kotlinx.serialization.Serializable
enum class MusicVideoType {
    /** 音源（静止画）。対応する MV（counterpart）があることがある。 */
    Atv,

    /** 公式の MV。 */
    Omv,

    /** 投稿者の動画。 */
    Ugc,
    Other,
    ;

    companion object {
        fun of(value: String?): MusicVideoType? =
            when (value) {
                null -> null
                "MUSIC_VIDEO_TYPE_ATV" -> Atv
                "MUSIC_VIDEO_TYPE_OMV" -> Omv
                "MUSIC_VIDEO_TYPE_UGC" -> Ugc
                else -> Other
            }
    }
}

/** 音源と MV の時刻の対応の1区間。MV には前奏や間奏が足されていて、同じ時刻にはならない。 */
data class TimeSegment(
    val primaryStartMs: Long,
    val counterpartStartMs: Long,
    val durationMs: Long,
)

/** 曲に見せる映像。 */
data class MusicVideo(
    /** 映像を取る videoId（OMV・UGC はその曲自身、ATV は対応する MV）。 */
    val videoId: String,
    /** 曲の時刻から映像の時刻への対応。空なら同じ時刻（その曲自身の映像）。 */
    val segments: List<TimeSegment>,
) {
    /** 曲の positionMs に当たる映像の時刻。対応する区間の外（MV に無い部分）なら null。 */
    fun videoPositionOf(positionMs: Long): Long? {
        if (segments.isEmpty()) return positionMs
        val segment = segments.firstOrNull { positionMs >= it.primaryStartMs && positionMs < it.primaryStartMs + it.durationMs } ?: return null
        return segment.counterpartStartMs + (positionMs - segment.primaryStartMs)
    }
}

/** 曲の映像についての情報（next の最初の応答から取る）。 */
data class MusicVideoInfo(
    val type: MusicVideoType?,
    /** 見せる映像。音源で対応する MV が無ければ null。 */
    val video: MusicVideo?,
)
