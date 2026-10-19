package xyz.mooma0750.wave.api

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.system.exitProcess

/**
 * wave-api（ADR-0015、docs/wave-api.md）。
 *   api            常駐する（中継につなぎ、HTTP の口を開ける）
 *   api pair <文字>  家の端末が出した QR の文字（wave-pair:…）で家に加わる（常駐しているものに頼む）。6桁を出す
 *   api token      トークンを出す（home-dashboard の設定に入れる）
 */
fun main(args: Array<String>) {
    val data = DataDir(File(System.getenv("WAVE_API_DATA") ?: "/data"))
    val port = System.getenv("WAVE_API_PORT")?.toIntOrNull() ?: 8793
    when (args.firstOrNull()) {
        null, "serve" -> serve(data, port)
        "pair" -> pairCommand(data, port, args.getOrNull(1) ?: usage())
        "token" -> println(token(data))
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println("使い方: api [serve] | api pair '<wave-pair:…>' | api token")
    exitProcess(2)
}

/** トークン（無ければ作る）。ほかの人が読めない権限で置く。 */
fun token(data: DataDir): String =
    data.read(DataDir.TOKEN)?.trim()?.takeIf { it.length >= 32 }
        ?: Base64.getUrlEncoder().withoutPadding().encodeToString(WaveCrypto.randomBytes(32)).also { data.write(DataDir.TOKEN, it) }

private fun serve(
    data: DataDir,
    port: Int,
) {
    val relayUrl = System.getenv("WAVE_RELAY_URL") ?: error("WAVE_RELAY_URL が要る（例: ws://wave-relay-v2:8791）")
    val relayHost = System.getenv("WAVE_RELAY_HOST") ?: error("WAVE_RELAY_HOST が要る（中継の WAVE_RELAY_HOST と同じ値）")
    val bind = System.getenv("WAVE_API_BIND") ?: "127.0.0.1"
    val keys = ApiKeys.loadOrCreate(data)
    val token = token(data)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hub = ApiHub(data, keys, relayUrl, relayHost, scope)
    hub.start()
    log("listening port=$port")
    embeddedServer(CIO, port = port, host = bind) { apiModule(HubBackend(hub), token) }.start(wait = true)
}

/** 常駐している wave-api に加入を頼み、6桁を出して、加わるまで待つ。 */
private fun pairCommand(
    data: DataDir,
    port: Int,
    offer: String,
) {
    val token = token(data)
    val http = HttpClient.newHttpClient()
    val base = "http://127.0.0.1:$port/v1/admin/pair"
    val json = Json { ignoreUnknownKeys = true }

    fun request(builder: HttpRequest.Builder): PairView {
        val response = http.send(builder.header("Authorization", "Bearer $token").build(), HttpResponse.BodyHandlers.ofString())
        return json.decodeFromString(PairView.serializer(), response.body())
    }
    var view = request(HttpRequest.newBuilder(URI(base)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(PairRequest.serializer(), PairRequest(offer)))))
    if (view.state != "show_code") {
        System.err.println("加われませんでした: ${view.reason ?: view.state}")
        exitProcess(1)
    }
    println("「${view.peerName}」の画面に、この6桁を入れてください: ${view.code}")
    while (view.state == "show_code") {
        Thread.sleep(2_000)
        view = request(HttpRequest.newBuilder(URI(base)).GET())
    }
    if (view.state == "joined") {
        println("家に加わりました。家の端末の「端末の連携」で wave API に「ログインを渡す」を押すと、プレイリストや検索も使えます")
    } else {
        System.err.println("加われませんでした: ${view.reason ?: view.state}")
        exitProcess(1)
    }
}
