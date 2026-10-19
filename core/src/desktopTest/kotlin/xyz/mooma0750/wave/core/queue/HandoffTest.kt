package xyz.mooma0750.wave.core.queue

import xyz.mooma0750.wave.core.music.AlbumRef
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.handoff.HandoffItem
import xyz.mooma0750.wave.handoff.HandoffOrigin
import xyz.mooma0750.wave.handoff.HandoffRef
import kotlin.test.Test
import kotlin.test.assertEquals

class HandoffTest {
    @Test
    fun handoffItemBecomesTheSameSong() {
        val item =
            HandoffItem(
                itemId = "i1",
                videoId = "v1",
                title = "曲",
                artists = listOf(HandoffRef("A", "UC1"), HandoffRef("B", null)),
                album = HandoffRef("アルバム", "MPRE1"),
                durationSeconds = 200,
                thumbnailUrl = "https://example.com/a.jpg",
                origin = HandoffOrigin.Source,
                isLive = true,
            )
        val song = item.toSong()
        assertEquals(Song("v1", "曲", listOf(ArtistRef("A", "UC1"), ArtistRef("B", null)), AlbumRef("アルバム", "MPRE1"), 200, "https://example.com/a.jpg", isLive = true), song)
        assertEquals("A, B", song.artistsText)
        // キューへの戻しも同じ曲になる
        assertEquals(song, HandoffMapping.queueItemOf(item).song)
    }
}
