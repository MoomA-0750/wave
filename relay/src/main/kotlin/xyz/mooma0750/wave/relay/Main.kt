package xyz.mooma0750.wave.relay

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File

/**
 * 環境変数:
 * - WAVE_RELAY_HOST: 署名の対象に入れるホスト名（例 <tailnet のホスト>:<ポート>。端末の wave.relayHost と同じ値）
 * - WAVE_RELAY_DATA: 登録を保存するファイル（Docker のボリューム）
 * - WAVE_RELAY_BOOTSTRAP_KEYS: 最初に登録する署名公開鍵（base64url の SPKI、カンマ区切り）。取り消せない
 * - WAVE_RELAY_PORT: 待ち受けのポート（既定 8791）。tailscale serve からだけ届くよう 127.0.0.1 に置く
 */
fun main() {
    val host = System.getenv("WAVE_RELAY_HOST") ?: error("WAVE_RELAY_HOST")
    val data = File(System.getenv("WAVE_RELAY_DATA") ?: "relay-registry.json")
    val bootstrap = System.getenv("WAVE_RELAY_BOOTSTRAP_KEYS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val registry = Registry(data, bootstrap)
    println("relay starting devices=${registry.size()}")
    embeddedServer(CIO, port = System.getenv("WAVE_RELAY_PORT")?.toIntOrNull() ?: 8791, host = System.getenv("WAVE_RELAY_BIND") ?: "127.0.0.1") {
        relayModule(RelayConfig(host, registry))
    }.start(wait = true)
}
