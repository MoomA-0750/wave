package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mooma0750.wave.core.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicVideoTest {
    private val next = Fixtures.json("next_radio")
    private val firstVideoId = requireNotNull(MusicParsers.upNext(next).songs.first().videoId)

    /** ログイン中の応答の形（最初の行を counterpart 付きの包みにする）。合成値。 */
    private fun wrappedFirstRow(segments: Boolean = true): JsonObject {
        fun edit(
            element: kotlinx.serialization.json.JsonElement,
            path: List<String>,
            change: (JsonArray) -> JsonArray,
        ): kotlinx.serialization.json.JsonElement =
            if (path.isEmpty()) {
                change(element as JsonArray)
            } else if (element is JsonArray) {
                JsonArray(element.mapIndexed { i, e -> if (i == 0) edit(e, path, change) else e })
            } else {
                val obj = element as JsonObject
                JsonObject(obj + (path.first() to edit(obj.getValue(path.first()), path.drop(1), change)))
            }
        val path = listOf("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs", "tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer", "contents")
        return edit(next, path) { rows ->
            val first = rows.first() as JsonObject
            val wrapped =
                buildJsonObject {
                    put(
                        "playlistPanelVideoWrapperRenderer",
                        buildJsonObject {
                            put("primaryRenderer", first)
                            put(
                                "counterpart",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("counterpartRenderer", buildJsonObject { put("playlistPanelVideoRenderer", buildJsonObject { put("videoId", "synthetic-omv") }) })
                                            if (segments) {
                                                put(
                                                    "segmentMap",
                                                    buildJsonObject {
                                                        put(
                                                            "segment",
                                                            buildJsonArray {
                                                                add(buildJsonObject { put("primaryVideoStartTimeMilliseconds", "0"); put("counterpartVideoStartTimeMilliseconds", "12000"); put("durationMilliseconds", "60000") })
                                                                add(buildJsonObject { put("primaryVideoStartTimeMilliseconds", "60000"); put("counterpartVideoStartTimeMilliseconds", "80000"); put("durationMilliseconds", "30000") })
                                                            },
                                                        )
                                                    },
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
            JsonArray(listOf(wrapped) + rows.drop(1))
        } as JsonObject
    }

    @Test
    fun audioTrackWithCounterpartMapsTimesThroughTheSegmentMap() {
        val info = requireNotNull(MusicParsers.musicVideoInfo(wrappedFirstRow(), firstVideoId))
        assertEquals(MusicVideoType.Atv, info.type)
        val video = requireNotNull(info.video)
        assertEquals("synthetic-omv", video.videoId)
        assertEquals(12_000L, video.videoPositionOf(0))
        assertEquals(42_000L, video.videoPositionOf(30_000))
        assertEquals(85_000L, video.videoPositionOf(65_000))
        assertNull(video.videoPositionOf(95_000), "MV に無い部分")
    }

    @Test
    fun audioTrackWithoutCounterpartOrMapHasNoVideo() {
        assertEquals(MusicVideoInfo(MusicVideoType.Atv, null), MusicParsers.musicVideoInfo(next, firstVideoId))
        assertEquals(MusicVideoInfo(MusicVideoType.Atv, null), MusicParsers.musicVideoInfo(wrappedFirstRow(segments = false), firstVideoId))
        assertNull(MusicParsers.musicVideoInfo(next, "not-in-panel"))
    }

    @Test
    fun officialVideoShowsItselfAtTheSameTime() {
        val omv = Fixtures.text("next_radio").replace("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV")
        val info = requireNotNull(MusicParsers.musicVideoInfo(kotlinx.serialization.json.Json.parseToJsonElement(omv) as JsonObject, firstVideoId))
        assertEquals(MusicVideoInfo(MusicVideoType.Omv, MusicVideo(firstVideoId, emptyList())), info)
        assertEquals(1234L, info.video?.videoPositionOf(1234))
        assertEquals(MusicVideoType.Other, MusicVideoType.of("MUSIC_VIDEO_TYPE_PODCAST_EPISODE"))
    }
}
