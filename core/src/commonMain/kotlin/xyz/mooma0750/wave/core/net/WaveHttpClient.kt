package xyz.mooma0750.wave.core.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * WaveSession 用の HttpClient。
 * - リダイレクトは追わない（Ktor はヘッダを付けたまま別ホストへ送り直すため）
 * - HttpCookies・Logging は入れない（cookie は innertubex がリクエストごとに付ける）
 * エンジンの所有権は呼び出し側に残る。
 */
fun createWaveHttpClient(engine: AuthGuardEngine): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
    }
