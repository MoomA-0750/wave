package xyz.mooma0750.wave.relay

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import xyz.mooma0750.wave.relay.v2.HouseholdStore
import xyz.mooma0750.wave.relay.v2.RelayV2Config
import xyz.mooma0750.wave.relay.v2.relayV2Module
import java.io.File

/**
 * 環境変数:
 * - WAVE_RELAY_HOST: 署名の対象に入れるホスト名（例 <tailnet のホスト>:<ポート>。端末の wave.relayHost と同じ値）
 * - WAVE_RELAY_DATA: 保存するファイル（Docker のボリューム）。v1 は登録、v2 は家（鎖・最新の状態・storeId）
 * - WAVE_RELAY_BOOTSTRAP_KEYS: 最初の端末の署名公開鍵（base64url の SPKI、カンマ区切り）。v1 は取り消せない登録、v2 は家を作れる端末
 * - WAVE_RELAY_PORT: 待ち受けのポート（既定 8791）。tailscale serve からだけ届くよう 127.0.0.1 に置く
 * - WAVE_RELAY_PROTOCOL: `v2` で Connect v2（Phase 6b）。無ければ v1（L-9: v2 の通しが済むまで並べて動かす）
 *
 * 引数 `household-reset`（v2 だけ）: 家（鎖・状態・storeId）を消す（L-13。アプリからは消せない）。中継を止めてから打つ
 */
fun main(args: Array<String>) {
    val data = File(System.getenv("WAVE_RELAY_DATA") ?: "relay-registry.json")
    val v2 = System.getenv("WAVE_RELAY_PROTOCOL") == "v2"
    if (args.firstOrNull() == "household-reset") {
        require(v2) { "household-reset は WAVE_RELAY_PROTOCOL=v2 のときだけ" }
        println(if (data.delete()) "household deleted" else "no household file")
        return
    }
    val host = System.getenv("WAVE_RELAY_HOST") ?: error("WAVE_RELAY_HOST")
    val bootstrap = System.getenv("WAVE_RELAY_BOOTSTRAP_KEYS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val port = System.getenv("WAVE_RELAY_PORT")?.toIntOrNull() ?: 8791
    val bind = System.getenv("WAVE_RELAY_BIND") ?: "127.0.0.1"
    if (v2) {
        val store = HouseholdStore(data, bootstrap)
        println("relay-v2 starting members=${store.members().size}")
        embeddedServer(CIO, port = port, host = bind) { relayV2Module(RelayV2Config(host, store)) }.start(wait = true)
    } else {
        val registry = Registry(data, bootstrap)
        println("relay starting devices=${registry.size()}")
        embeddedServer(CIO, port = port, host = bind) { relayModule(RelayConfig(host, registry)) }.start(wait = true)
    }
}
