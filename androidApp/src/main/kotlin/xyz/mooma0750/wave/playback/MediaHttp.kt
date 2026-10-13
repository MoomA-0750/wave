package xyz.mooma0750.wave.playback

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 音声バイト・サムネイルの取得用の HTTP クライアント。認証情報を一切持たない（ADR-0003）。
 * - CookieJar・Authenticator・Interceptor を足さない（OkHttp の既定は cookie を持たない）
 * - リダイレクトは追わない。3xx は失敗として扱い、ストリームを解決し直す
 * - ディスクキャッシュを持たない（署名付き URL の応答を残さない）
 */
object MediaHttp {
    fun create(): OkHttpClient =
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cache(null)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /**
     * RSS のポッドキャストのフィードと音声の取得用（ADR-0012）。認証情報を持たないのは [create] と同じ。
     * 計測用の転送（podtrac など）を通るので、リダイレクトは追う。https から http へは追わない（followSslRedirects = false）
     */
    fun createForFeeds(): OkHttpClient =
        OkHttpClient
            .Builder()
            .followRedirects(true)
            .followSslRedirects(false)
            .cache(null)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
}
