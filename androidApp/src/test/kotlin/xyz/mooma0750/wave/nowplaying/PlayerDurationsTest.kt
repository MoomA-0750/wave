package xyz.mooma0750.wave.nowplaying

import org.junit.Test
import kotlin.test.assertEquals

class PlayerDurationsTest {
    @Test
    fun durationFallsBackToTheSongLengthBeforeThePlayerHasLoadedIt() {
        // 戻したキューで再生を押す前は、プレーヤーの長さが分からない（TIME_UNSET）。曲の情報の長さを使う
        assertEquals(245_000L, PlayerDurations.of(androidx.media3.common.C.TIME_UNSET, 245_000L))
        // プレーヤーが読んだら、プレーヤーの長さを使う
        assertEquals(244_812L, PlayerDurations.of(244_812L, 245_000L))
        // どちらも無ければ 0（シークバーを使えなくする）
        assertEquals(0L, PlayerDurations.of(androidx.media3.common.C.TIME_UNSET, null))
    }
}
