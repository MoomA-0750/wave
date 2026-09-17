package xyz.mooma0750.wave.handoff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffCodecTest {
    private val handoffId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private fun item(
        n: Int,
        thumbnail: String? = "https://lh3.googleusercontent.com/x=w120-h120",
    ) = HandoffItem(
        itemId = "00000000-0000-4000-8000-%012d".format(n),
        videoId = "abcdefghij" + ('A' + (n % 26)),
        title = "title $n",
        artists = listOf(HandoffRef("artist", "UCsynthetic000000000000000")),
        album = HandoffRef("album", "MPREb_synthetic"),
        durationSeconds = 200,
        thumbnailUrl = thumbnail,
        origin = HandoffOrigin.Source,
    )

    private fun snapshot(
        items: List<HandoffItem> = listOf(item(0), item(1)),
        current: String = items[0].itemId,
    ) = HandoffSnapshot(
        schemaVersion = HandoffCodec.SCHEMA_VERSION,
        handoffId = handoffId,
        items = items,
        currentItemId = current,
        positionMs = 12_000,
        playing = true,
        repeat = HandoffRepeat.All,
        source = HandoffSource.Playlist("PLsynthetic"),
        continuation = "token",
        radioPlaylistId = null,
        exhausted = false,
        listenedMs = 9_000,
        historySent = false,
        capturedAtEpochMs = 1,
    )

    @Test
    fun roundTrips() {
        val original = snapshot()
        val decoded = assertIs<HandoffDecodeResult.Ok>(HandoffCodec.decode(HandoffCodec.encode(original), handoffId)).snapshot
        assertEquals(original, decoded)
        // 判別子は短い名前（iOS・Windows で扱いやすい）
        assertTrue(HandoffCodec.encode(original).contains("\"type\":\"playlist\""))
    }

    @Test
    fun rejectsAnotherHandoffOrVersion() {
        assertIs<HandoffDecodeResult.Rejected>(HandoffCodec.decode(HandoffCodec.encode(snapshot()), "11111111-1111-4111-8111-111111111111"))
        assertEquals(HandoffDecodeResult.UnsupportedVersion(2), HandoffCodec.decode(HandoffCodec.encode(snapshot().copy(schemaVersion = 2)), handoffId))
        assertEquals(HandoffDecodeResult.UnsupportedVersion(null), HandoffCodec.decode("{}", handoffId))
    }

    @Test
    fun unknownKeysFromANewerBuildAreIgnored() {
        val text = HandoffCodec.encode(snapshot()).replaceFirst("{", "{\"futureField\":1,")
        assertIs<HandoffDecodeResult.Ok>(HandoffCodec.decode(text, handoffId))
    }

    @Test
    fun rejectsMalformedValues() {
        fun rejected(s: HandoffSnapshot) = assertIs<HandoffDecodeResult.Rejected>(HandoffCodec.validate(s, handoffId))
        rejected(snapshot().copy(items = listOf(item(0).copy(videoId = "../../evil"))))
        rejected(snapshot().copy(items = listOf(item(0), item(0))))
        rejected(snapshot().copy(currentItemId = "00000000-0000-4000-8000-999999999999"))
        rejected(snapshot().copy(items = listOf(item(0).copy(itemId = "not-a-uuid")), currentItemId = "not-a-uuid"))
        rejected(snapshot().copy(continuation = "a\nb"))
        rejected(snapshot().copy(source = HandoffSource.Radio("bad")))
        rejected(snapshot().copy(items = listOf(item(0).copy(artists = listOf(HandoffRef("a", "has space"))))))
        rejected(snapshot().copy(listenedMs = -1))
        val tooMany = (0..HandoffCodec.MAX_PLAYED_ITEMS + 1).map(::item)
        rejected(snapshot(items = tooMany, current = tooMany.last().itemId))
        assertIs<HandoffDecodeResult.Rejected>(HandoffCodec.decode("x".repeat(HandoffCodec.MAX_BYTES + 1), handoffId))
    }

    @Test
    fun foreignThumbnailsAreDroppedAndPositionIsClamped() {
        val items =
            listOf(
                item(0, "https://evil.example/x.jpg"),
                item(1, "https://user@lh3.googleusercontent.com/x"),
                item(2, "https://i.ytimg.com/vi/x/hq.jpg"),
                item(3, "https://yt3.ggpht.com/x"),
            )
        val ok = assertIs<HandoffDecodeResult.Ok>(HandoffCodec.validate(snapshot(items = items).copy(positionMs = 999_999_999), handoffId)).snapshot
        assertNull(ok.items[0].thumbnailUrl)
        assertNull(ok.items[1].thumbnailUrl)
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", ok.items[2].thumbnailUrl)
        assertEquals("https://yt3.ggpht.com/x", ok.items[3].thumbnailUrl)
        assertEquals(200_000L, ok.positionMs)
    }

    @Test
    fun windowKeepsRecentPlayedAndLimitedUpcoming() {
        val list = (0 until 500).toList()
        val (items, index, truncated) = HandoffCodec.window(list, currentIndex = 100)
        assertEquals(80, items.first())
        assertEquals(20, index)
        assertEquals(100 + HandoffCodec.MAX_UPCOMING_ITEMS, items.last())
        assertTrue(truncated)
        val (small, smallIndex, smallTruncated) = HandoffCodec.window(listOf(1, 2, 3), currentIndex = 1)
        assertEquals(listOf(1, 2, 3), small)
        assertEquals(1, smallIndex)
        assertEquals(false, smallTruncated)
    }
}
