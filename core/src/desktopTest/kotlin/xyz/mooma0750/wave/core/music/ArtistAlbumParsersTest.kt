package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistAlbumParsersTest {
    @Test
    fun albumRowsAreFilledFromTheHeader() {
        val page = assertNotNull(MusicParsers.albumPage(fixture("browse_album"), "MPREb_test"))
        assertEquals(4, page.songs.size)
        assertTrue(page.playlistId!!.startsWith("OLAK5uy_"))
        assertTrue(page.artists.single().browseId!!.startsWith("UC"))
        assertNotNull(page.thumbnailUrl)
        assertTrue(
            page.songs.all {
                it.videoId.isNotBlank() && it.title.isNotBlank() && it.durationSeconds != null &&
                    it.artists == page.artists && it.album == AlbumRef(page.title, "MPREb_test") && it.thumbnailUrl == page.thumbnailUrl
            },
        )
    }

    @Test
    fun greyedOutRowsAreSkipped() {
        val text = fixtureText("browse_album")
        // 最初の行をグレーアウトにする
        val greyed = text.replaceFirst("\"musicResponsiveListItemRenderer\":{", "\"musicResponsiveListItemRenderer\":{\"musicItemRendererDisplayPolicy\":\"MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT\",")
        val page = assertNotNull(MusicParsers.albumPage(Json.parseToJsonElement(greyed) as JsonObject, "MPREb_test"))
        assertEquals(3, page.songs.size)
    }

    @Test
    fun artistPageHasTopSongsAllSongsAndAlbumShelves() {
        val page = assertNotNull(MusicParsers.artistPage(fixture("browse_artist")))
        assertTrue(page.name.isNotBlank())
        assertNotNull(page.thumbnailUrl)
        assertEquals(5, page.topSongs.size)
        assertTrue(page.topSongs.all { it.artists.isNotEmpty() && it.thumbnailUrl != null })
        assertTrue(page.allSongsPlaylistId!!.startsWith("OLAK5uy_"))
        // アルバムとシングルの棚だけ（動画・プレイリスト・似たアーティストの棚は除く）
        assertEquals(2, page.albumShelves.size)
        assertTrue(page.albumShelves.flatMap { it.albums }.all { it.browseId.startsWith("MPREb_") && it.title.isNotBlank() })
    }

    @Test
    fun userChannelsAreNotLinkedAsArtists() {
        val text = fixtureText("browse_artist").replace("MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL")
        val page = assertNotNull(MusicParsers.artistPage(Json.parseToJsonElement(text) as JsonObject))
        assertTrue(page.topSongs.flatMap { it.artists }.isNotEmpty())
        assertTrue(page.topSongs.flatMap { it.artists }.all { it.browseId == null })
    }

    @Test
    fun unrelatedResponsesAreNull() {
        assertNull(MusicParsers.artistPage(fixture("browse_album")))
        assertNull(MusicParsers.albumPage(fixture("search_songs"), "MPREb_x"))
    }

    @Test
    fun likeStatusIsReadOnlyForTheRequestedSong() {
        val root = fixture("next_radio")
        val videoId = requireNotNull(Regex("\"target\":\\{\"videoId\":\"([^\"]+)\"").find(fixtureText("next_radio").replace(" ", ""))).groupValues[1]
        assertEquals(LikeStatus.Indifferent, MusicParsers.likeStatus(root, videoId))
        assertNull(MusicParsers.likeStatus(root, "other-video"))
        assertNull(MusicParsers.likeStatus(fixture("next_radio_continuation"), videoId), "続きの応答に評価は無い")
        val liked = Json.parseToJsonElement(fixtureText("next_radio").replace("\"INDIFFERENT\"", "\"LIKE\"")) as JsonObject
        assertEquals(LikeStatus.Like, MusicParsers.likeStatus(liked, videoId))
    }

    @Test
    fun albumPlaylistIdFallsBackToTheCanonicalUrlThenToAnyAlbumPlaylist() {
        // 再生ボタンが無い応答（ログイン時の形）: canonical URL の list= から取る
        val expected = assertNotNull(MusicParsers.albumPage(fixture("browse_album"), "MPREb_test")).playlistId
        val noButton = fixtureText("browse_album").replace("musicPlayButtonRenderer", "unknownPlayButtonRenderer")
        assertEquals(expected, assertNotNull(MusicParsers.albumPage(Json.parseToJsonElement(noButton) as JsonObject, "MPREb_test")).playlistId)
        // canonical URL も無い: アルバムの playlistId（OLAK5uy_）を持つ watchPlaylistEndpoint から取る
        val noCanonical = noButton.replace("urlCanonical", "unknownCanonical")
        assertEquals(expected, assertNotNull(MusicParsers.albumPage(Json.parseToJsonElement(noCanonical) as JsonObject, "MPREb_test")).playlistId)
        // どれも無ければ null
        val none = noCanonical.replace("watchPlaylistEndpoint", "unknownEndpoint")
        assertNull(assertNotNull(MusicParsers.albumPage(Json.parseToJsonElement(none) as JsonObject, "MPREb_test")).playlistId)
    }

    private fun fixtureText(name: String): String = xyz.mooma0750.wave.core.Fixtures.text(name)

    private fun fixture(name: String): JsonObject = xyz.mooma0750.wave.core.Fixtures.json(name)

    @Test
    fun artistPageCarriesItsChannelAndSubscription() {
        val page = MusicParsers.artistPage(xyz.mooma0750.wave.core.Fixtures.json("browse_artist"))!!
        kotlin.test.assertEquals("UCvpredjG93ifbCP1Y77JyFA", page.channelId)
        kotlin.test.assertEquals(false, page.subscribed)
    }
}
