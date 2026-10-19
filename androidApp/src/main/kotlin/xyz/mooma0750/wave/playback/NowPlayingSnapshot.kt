package xyz.mooma0750.wave.playback

import androidx.media3.common.Player

/**
 * ホーム画面のウィジェットに出す、いま再生している曲（セッションの再生器から作る。ほかの端末を映している間はその端末の曲）。
 * 何も無ければ null。
 */
data class NowPlayingSnapshot(
    val title: String,
    /** ほかの端末を映している間は「アーティスト • 端末名で再生中」。 */
    val artist: String,
    /** https の画像だけ。 */
    val artworkUrl: String?,
    val playing: Boolean,
) {
    companion object {
        fun of(player: Player): NowPlayingSnapshot? {
            if (player.mediaItemCount == 0) return null
            val item = player.currentMediaItem ?: return null
            val metadata = player.mediaMetadata
            val song = Media3QueuePlayer.songOf(item)
            val artwork =
                song?.thumbnailUrl?.takeIf { it.startsWith("https://") }?.let(Media3QueuePlayer::largeArtwork)
                    ?: metadata.artworkUri?.toString()?.takeIf { it.startsWith("https://") }
            return NowPlayingSnapshot(
                title = (metadata.title ?: song?.title)?.toString().orEmpty(),
                artist = (metadata.artist ?: song?.artistsText)?.toString().orEmpty(),
                artworkUrl = artwork,
                playing = player.playWhenReady,
            )
        }
    }
}
