package xyz.mooma0750.wave.debug

import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.Song

/** debug ビルドだけの再生確認。 */
object DebugPlayback {
    const val VIDEO_ID = "dQw4w9WgXcQ"

    val fixedSong = Song(VIDEO_ID, "開発用の確認曲", listOf(ArtistRef("wave", null)), null, null, null)

    /** 再生できない曲（存在しない videoId）。飛ばして次へ進むかの確認用。 */
    val unplayableSong = Song("zzzzzzzzzzz", "開発用の再生できない曲", listOf(ArtistRef("wave", null)), null, null, null)

    /** 公開プレイリスト（195曲、2ページ）。最後まで行ってラジオへつながるかの確認用。 */
    const val PUBLIC_PLAYLIST_ID = "PLIyWtPwrYr7Yqqj0-n0Sc4tPlaYSfoeGS"
}
