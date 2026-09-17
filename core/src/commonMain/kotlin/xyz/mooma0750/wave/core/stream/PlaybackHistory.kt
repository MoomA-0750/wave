package xyz.mooma0750.wave.core.stream

import xyz.mooma0750.wave.core.session.StatsOutcome
import xyz.mooma0750.wave.core.session.WaveSession
import kotlin.random.Random

/** 再生履歴（playback 統計）の送信（ADR-0006）。いつ送るかは呼び出し側（再生サービス）が決める。 */
class PlaybackHistory(
    private val session: WaveSession,
) {
    /**
     * 解決済みのストリームが持つ統計 URL で、1回の再生を報告する。統計 URL が無ければ送らない（統計のために解決しない）。
     * @param positionSeconds 送る時点の再生位置（秒）
     */
    suspend fun report(
        stream: ResolvedStream,
        cpn: String,
        positionSeconds: Double,
    ): StatsOutcome {
        val url = stream.revealPlaybackStatsUrl() ?: return StatsOutcome.ShapeRejected
        val cmt = ((positionSeconds.coerceAtLeast(0.0) * 1000).toLong() / 1000.0).toString()
        return session.sendPlaybackStats(url, stream.sessionGeneration, cpn, cmt)
    }

    companion object {
        private const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /** 再生1回ごとの ID（16文字）。random には暗号学的乱数を渡す。 */
        fun newCpn(random: Random): String = String(CharArray(16) { CPN_ALPHABET[random.nextInt(CPN_ALPHABET.length)] })
    }
}
