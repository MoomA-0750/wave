package xyz.mooma0750.wave.core.stream

import com.metrolist.innertubex.InnerTubeLogEvent
import com.metrolist.innertubex.InnerTubeLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * debug の確認用: 解読がどの経路を通ったかを数える（ADR-0005）。
 * innertubex のログのメッセージは保持も出力もせず、決まった先頭の一致だけを数える。
 */
class CipherPathStats internal constructor() {
    private val faradayTable = MutableStateFlow(0)
    private val faradayTableFailed = MutableStateFlow(0)
    private val preprocessedPlayer = MutableStateFlow(0)

    internal val logger =
        InnerTubeLogger { event: InnerTubeLogEvent ->
            val message = event.message
            when {
                message.startsWith("Faraday zemer config done") -> faradayTable.update { it + 1 }
                message.startsWith("Faraday zemer config failed") -> faradayTableFailed.update { it + 1 }
                message.startsWith("GitHub preprocessed-player response") -> preprocessedPlayer.update { it + 1 }
            }
        }

    override fun toString(): String =
        "cipherPath faradayTable=${faradayTable.value} faradayTableFailed=${faradayTableFailed.value} preprocessedPlayer=${preprocessedPlayer.value}"
}
