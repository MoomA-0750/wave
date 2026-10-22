package xyz.mooma0750.wave.handoff.v2

import xyz.mooma0750.wave.handoff.HandoffRepeat
import kotlin.test.Test
import kotlin.test.assertEquals

/** 遠隔のシャッフル・繰り返しの形（C# の ConnectSimulationTests・Swift の ConnectSimulationTests が同じ文字列を読む）。 */
class ModeControlJsonTest {
    @Test
    fun shuffleAndRepeatCommandsHaveAFixedShape() {
        val shuffle = CommandBody.Control("c1", "dev-b", "dev-a", ControlAction.Shuffle, null, 7, "tag", shuffle = true)
        assertEquals(SHUFFLE, ConnectJson.json.encodeToString(CommandBody.serializer(), shuffle))
        val repeat = CommandBody.Control("c2", "dev-b", "dev-a", ControlAction.Repeat, null, 7, "tag", repeat = HandoffRepeat.One)
        assertEquals(REPEAT, ConnectJson.json.encodeToString(CommandBody.serializer(), repeat))
        assertEquals(shuffle, ConnectJson.json.decodeFromString(CommandBody.serializer(), SHUFFLE))
    }

    @Test
    fun anOlderReaderDropsTheUnknownAction() {
        // 前の版は知らない action を読めずに捨てる（受け手は runCatching で読む）
        val old = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
        val read = runCatching { old.decodeFromString(CommandBody.serializer(), SHUFFLE.replace("\"shuffle\",", "\"zzz\",")) }
        assertEquals(true, read.isFailure)
    }

    companion object {
        const val SHUFFLE = """{"type":"control","commandId":"c1","from":"dev-b","to":"dev-a","action":"shuffle","positionMs":null,"basedOnVersion":7,"accountTag":"tag","onItemId":null,"volumePercent":null,"shuffle":true,"repeat":null}"""
        const val REPEAT = """{"type":"control","commandId":"c2","from":"dev-b","to":"dev-a","action":"repeat","positionMs":null,"basedOnVersion":7,"accountTag":"tag","onItemId":null,"volumePercent":null,"shuffle":null,"repeat":"one"}"""
    }
}
