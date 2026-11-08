package xyz.mooma0750.wave.core.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayLinkTest {
    private val video = PlayLink.Video("jfKfPfyJRdk")

    @Test
    fun videoLinks() {
        assertEquals(video, PlayLink.parse("https://music.youtube.com/watch?v=jfKfPfyJRdk"))
        assertEquals(video, PlayLink.parse("https://www.youtube.com/watch?v=jfKfPfyJRdk&t=30s"))
        assertEquals(video, PlayLink.parse("https://m.youtube.com/watch?feature=share&v=jfKfPfyJRdk"))
        assertEquals(video, PlayLink.parse("https://youtu.be/jfKfPfyJRdk?si=abc"))
        assertEquals(video, PlayLink.parse("https://www.youtube.com/live/jfKfPfyJRdk?feature=shared"))
        assertEquals(video, PlayLink.parse("https://youtube.com/shorts/jfKfPfyJRdk"))
        // スキームの無いもの・前後の空白
        assertEquals(video, PlayLink.parse("  music.youtube.com/watch?v=jfKfPfyJRdk\n"))
        // v= と list= の両方があれば動画（その曲から鳴らす）
        assertEquals(video, PlayLink.parse("https://music.youtube.com/watch?v=jfKfPfyJRdk&list=RDAMVMjfKfPfyJRdk"))
    }

    @Test
    fun playlistLinks() {
        assertEquals(PlayLink.Playlist("PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx"), PlayLink.parse("https://music.youtube.com/playlist?list=PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx"))
        assertEquals(PlayLink.Playlist("OLAK5uy_kXo3qRLEbmwWnhMmzxTx3NOVCQyB4Fgo8"), PlayLink.parse("https://music.youtube.com/playlist?list=OLAK5uy_kXo3qRLEbmwWnhMmzxTx3NOVCQyB4Fgo8"))
        assertEquals(PlayLink.Playlist("RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs"), PlayLink.parse("https://www.youtube.com/playlist?list=RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs&si=x"))
        // v= の形が合わず list= だけが読めるもの
        assertEquals(PlayLink.Playlist("PL123"), PlayLink.parse("https://www.youtube.com/watch?list=PL123"))
    }

    @Test
    fun albumLinks() {
        assertEquals(PlayLink.Album("MPREb_4pL8gzRtw1p"), PlayLink.parse("https://music.youtube.com/browse/MPREb_4pL8gzRtw1p"))
        // browse の先がアルバムでないもの（アーティストなど）は読まない
        assertNull(PlayLink.parse("https://music.youtube.com/browse/UCSJ4gkVC6NrvII8umztf0Ow"))
    }

    @Test
    fun bareIds() {
        assertEquals(video, PlayLink.parse("jfKfPfyJRdk"))
        assertEquals(PlayLink.Playlist("PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx"), PlayLink.parse("PLxA687tYuMWhkqYjvAGtW_heiEL4Hk_Lx"))
        assertEquals(PlayLink.Playlist("OLAK5uy_kXo3qRLEbmwWnhMmzxTx3NOVCQyB4Fgo8"), PlayLink.parse("OLAK5uy_kXo3qRLEbmwWnhMmzxTx3NOVCQyB4Fgo8"))
        assertEquals(PlayLink.Playlist("RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs"), PlayLink.parse("RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs"))
        assertEquals(PlayLink.Album("MPREb_4pL8gzRtw1p"), PlayLink.parse("MPREb_4pL8gzRtw1p"))
    }

    @Test
    fun unreadable() {
        assertNull(PlayLink.parse(""))
        assertNull(PlayLink.parse("   "))
        assertNull(PlayLink.parse("https://example.com/watch?v=jfKfPfyJRdk"))
        assertNull(PlayLink.parse("https://music.youtube.com/"))
        assertNull(PlayLink.parse("https://youtu.be/short"))
        // 長さの合わない ID、知らない形の ID、使えない文字
        assertNull(PlayLink.parse("abc"))
        assertNull(PlayLink.parse("UCSJ4gkVC6NrvII8umztf0Ow"))
        assertNull(PlayLink.parse("jfKfPfy RdkX"))
        assertNull(PlayLink.parse("jfKfPfyJRdké"))
    }
}
