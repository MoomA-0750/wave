package xyz.mooma0750.wave.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import xyz.mooma0750.wave.core.music.Song

/**
 * ホーム画面のウィジェットに出す、いま再生している曲（セッションの再生器から作る。ほかの端末を映している間はその端末の曲）。
 * 何も無ければ null。
 */
data class NowPlayingSnapshot(
    val title: String,
    val artist: String,
    /** https の画像だけ。 */
    val artworkUrl: String?,
    val playing: Boolean,
    /** 今の曲の videoId（ウィジェットで、鳴っている曲・ライブを見分ける）。 */
    val videoId: String? = null,
    /** 鳴らしているプレイリスト・アルバムの ID（この端末で鳴らしているときだけ。ほかの端末を映している間は null）。 */
    val sourceId: String? = null,
    /** ほかの端末を映している間は、その端末の名前。 */
    val device: String? = null,
    /** 評価のボタン（true: 高く評価したかプレイリストに入っていて✓、false: ＋、null: 出さない＝ログインしていない・評価を読めていない・RSS のエピソード）。 */
    val saved: Boolean? = null,
    /** 今の曲（✓からプレイリストに追加する画面へ渡す）。 */
    val song: Song? = null,
    /** 次に流れる曲（最大 [UP_NEXT_COUNT] 曲）。 */
    val upNext: List<UpNext> = emptyList(),
) {
    /** 次に流れる曲の1つ。押すと [itemId] へ飛ぶ。 */
    data class UpNext(
        val itemId: String,
        val title: String,
        val artist: String,
        val artworkUrl: String?,
    )

    companion object {
        /** 次に流れる曲を持つ数（キューのウィジェットの大に並べる数。再生中のウィジェットは先頭の3曲だけを出す）。 */
        const val UP_NEXT_COUNT = 8

        fun of(
            player: Player,
            sourceId: String? = null,
            device: String? = null,
            saved: Boolean? = null,
        ): NowPlayingSnapshot? {
            if (player.mediaItemCount == 0) return null
            val item = player.currentMediaItem ?: return null
            val metadata = player.mediaMetadata
            val song = Media3QueuePlayer.songOf(item)
            val artwork =
                song?.thumbnailUrl?.takeIf { it.startsWith("https://") }?.let(Media3QueuePlayer::largeArtwork)
                    ?: metadata.artworkUri?.toString()?.takeIf { it.startsWith("https://") }
            return NowPlayingSnapshot(
                title = (metadata.title ?: song?.title)?.toString().orEmpty(),
                // ほかの端末を映している間の通知のアーティストの行には端末名が入るので、曲の情報から取る
                artist = (song?.artistsText ?: metadata.artist)?.toString().orEmpty(),
                artworkUrl = artwork,
                playing = player.playWhenReady,
                videoId = song?.videoId,
                sourceId = sourceId,
                device = device,
                saved = saved,
                song = song,
                upNext = upNextOf(player),
            )
        }

        /** 今の曲の後ろに流れる曲（シャッフルの順で。繰り返しで頭へ戻る分は入れない）。 */
        private fun upNextOf(player: Player): List<UpNext> {
            val timeline = player.currentTimeline
            if (timeline.isEmpty) return emptyList()
            val result = mutableListOf<UpNext>()
            var index = player.currentMediaItemIndex
            while (result.size < UP_NEXT_COUNT) {
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
                if (index == C.INDEX_UNSET || index >= player.mediaItemCount) break
                val next = player.getMediaItemAt(index)
                val song = Media3QueuePlayer.songOf(next)
                result +=
                    UpNext(
                        itemId = next.mediaId,
                        title = (song?.title ?: next.mediaMetadata.title)?.toString().orEmpty(),
                        artist = (song?.artistsText ?: next.mediaMetadata.artist)?.toString().orEmpty(),
                        artworkUrl = song?.thumbnailUrl?.takeIf { it.startsWith("https://") },
                    )
            }
            return result
        }
    }
}
