package xyz.mooma0750.wave.relay

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import xyz.mooma0750.wave.relay.v2.HouseholdStore
import xyz.mooma0750.wave.relay.v2.RelayV2Config
import xyz.mooma0750.wave.relay.v2.relayV2Module
import java.io.File

/**
 * 環境変数:
 * - WAVE_RELAY_PROTOCOL: `v2` であること（必須）。v1 は消した（2026-10-14）。v1 のコンテナが新しい配布物で起き直したときに、
 *   v1 の登録ファイルを家のファイルとして読みにいかず、はっきり止まるように残している
 * - WAVE_RELAY_HOST: 署名の対象に入れるホスト名（例 <tailnet のホスト>:<ポート>。端末の wave.relayV2Host と同じ値）
 * - WAVE_RELAY_DATA: 家（鎖・最新の状態・storeId）を保存するファイル（Docker のボリューム）
 * - WAVE_RELAY_BOOTSTRAP_KEYS: 家を作れる端末の署名公開鍵（base64url の SPKI、カンマ区切り）
 * - WAVE_RELAY_PORT: 待ち受けのポート（既定 8791）。tailscale serve からだけ届くよう 127.0.0.1 に置く
 *
 * 引数 `household-reset`: 家（鎖・状態・storeId）を消す（L-13。アプリからは消せない）。中継を止めてから打つ
 */
fun main(args: Array<String>) {
    val protocol = System.getenv("WAVE_RELAY_PROTOCOL")
    require(protocol == "v2") { "WAVE_RELAY_PROTOCOL=v2 を設定すること（v1 の中継は消した。値: ${protocol ?: "なし"}）" }
    val data = File(System.getenv("WAVE_RELAY_DATA") ?: "relay-household.json")
    if (args.firstOrNull() == "household-reset") {
        println(if (data.delete()) "household deleted" else "no household file")
        return
    }
    val host = System.getenv("WAVE_RELAY_HOST") ?: error("WAVE_RELAY_HOST")
    val bootstrap = System.getenv("WAVE_RELAY_BOOTSTRAP_KEYS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val port = System.getenv("WAVE_RELAY_PORT")?.toIntOrNull() ?: 8791
    val bind = System.getenv("WAVE_RELAY_BIND") ?: "127.0.0.1"
    val store = HouseholdStore(data, bootstrap)
    println("relay-v2 starting members=${store.members().size}")
    embeddedServer(CIO, port = port, host = bind) { relayV2Module(RelayV2Config(host, store)) }.start(wait = true)
}
