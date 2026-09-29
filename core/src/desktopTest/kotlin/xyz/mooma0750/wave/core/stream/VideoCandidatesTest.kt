package xyz.mooma0750.wave.core.stream

import com.metrolist.innertubex.models.response.PlayerResponse
import xyz.mooma0750.wave.core.session.WaveSession
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoCandidatesTest {
    private fun format(
        itag: Int,
        mimeType: String,
        height: Int?,
        source: Boolean = true,
    ): PlayerResponse.StreamingData.Format =
        WaveSession.json.decodeFromString(
            PlayerResponse.StreamingData.Format.serializer(),
            listOfNotNull(
                """"itag":$itag""",
                """"mimeType":"${mimeType.replace("\"", "\\\"")}"""",
                """"bitrate":1""",
                height?.let { """"height":$it""" },
                """"url":"https://rr1---sn-synthetic.googlevideo.com/videoplayback?itag=$itag"""".takeIf { source },
            ).joinToString(",", "{", "}"),
        )

    @Test
    fun prefersH264ThenVp9UpTo720AndSkipsAv1AudioAndSabr() {
        val formats =
            listOf(
                format(140, "audio/mp4; codecs=\"mp4a.40.2\"", null),
                format(399, "video/mp4; codecs=\"av01.0.08M.08\"", 1080),
                format(137, "video/mp4; codecs=\"avc1.640028\"", 1080),
                format(248, "video/webm; codecs=\"vp9\"", 1080),
                format(247, "video/webm; codecs=\"vp9\"", 720),
                format(135, "video/mp4; codecs=\"avc1.4d401f\"", 480),
                format(136, "video/mp4; codecs=\"avc1.4d401f\"", 720, source = false),
                format(134, "video/mp4; codecs=\"avc1.4d401e\"", 360),
            )
        assertEquals(listOf(135, 134, 247), StreamResolver.videoCandidates(formats, null).map { it.itag })
        assertEquals(listOf(247), StreamResolver.videoCandidates(formats, 247).map { it.itag })
        // 音声の選び方は映像を拾わない
        assertEquals(listOf(140), StreamResolver.audioCandidates(formats, null).map { it.itag })
    }
}
