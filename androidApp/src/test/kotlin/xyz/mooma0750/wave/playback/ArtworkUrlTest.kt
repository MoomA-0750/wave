package xyz.mooma0750.wave.playback

import org.junit.Test
import kotlin.test.assertEquals

/** 通知・ロック画面・時計に出す画像は、一覧のサムネイルのままだと小さすぎる。 */
class ArtworkUrlTest {
    private fun large(url: String) = Media3QueuePlayer.largeArtwork(url)

    @Test
    fun googleImagesAreAskedForALargeSquare() {
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544", large("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj"))
        assertEquals("https://yt3.ggpht.com/abc=w544-h544", large("https://yt3.ggpht.com/abc=s192"))
        // 大きさの指定が無いものにも足す
        assertEquals("https://yt3.googleusercontent.com/abc=w544-h544", large("https://yt3.googleusercontent.com/abc"))
        // 問い合わせの部分は落とす（大きさの指定と混ざらないように）
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544", large("https://lh3.googleusercontent.com/abc=w60-h60?authuser=0"))
    }

    @Test
    fun videoThumbnailsUseTheLargerFileThatAlwaysExists() {
        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", large("https://i.ytimg.com/vi/abc123/default.jpg"))
        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", large("https://i.ytimg.com/vi/abc123/hqdefault.jpg?sqp=xyz&rs=abc"))
        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", large("https://i.ytimg.com/vi/abc123/hq720.jpg"))
    }

    @Test
    fun otherHostsAreLeftAlone() {
        assertEquals("https://example.com/cover.png", large("https://example.com/cover.png"))
    }
}
