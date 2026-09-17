package xyz.mooma0750.wave.debug

import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.Song

/** debug ビルドだけの再生確認。 */
object DebugPlayback {
    const val VIDEO_ID = "dQw4w9WgXcQ"

    val fixedSong = Song(VIDEO_ID, "開発用の確認曲", listOf(ArtistRef("wave", null)), null, null, null)
}
