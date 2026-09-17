package xyz.mooma0750.wave.core.stream

import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mooma0750.wave.core.net.isAllowedMediaUrl
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.core.session.ParseOutcome
import xyz.mooma0750.wave.core.session.WaveSession

/** 解決済みのストリーム。署名付き URL を含むので、toString に出さない。 */
class ResolvedStream internal constructor(
    val videoId: String,
    val itag: Int,
    val mimeType: String,
    val contentLength: Long?,
    /** 同じ itag でも、再エンコードなどで中身が変わったことを見分けるための値。 */
    val lastModified: String?,
    private val url: String,
    /** 単調時計（StreamResolver の nowMs）のミリ秒。 */
    val expiresAtMs: Long,
) {
    /** 音声を取得する DataSource だけが呼ぶ。ログ・例外文・UI に渡さない。 */
    fun revealUrl(): String = url

    /** debug の確認用: 署名の対象の expire を変えた（取得すると 403 になる）写しを作る。再解決の経路を通すためだけに使う。 */
    fun corruptedForDebug(): ResolvedStream = ResolvedStream(videoId, itag, mimeType, contentLength, lastModified, url.replace("expire=", "expire=1"), expiresAtMs)

    override fun toString(): String = "ResolvedStream(videoId=$videoId, itag=$itag, mimeType=$mimeType, contentLength=$contentLength)"
}

enum class StreamFailure {
    NotLoggedIn,
    Expired,
    Suspect,
    Network,
    Http,
    Parse,
    PlayerConfigUnavailable,

    /** playabilityStatus が OK でない（地域制限・削除など）。 */
    Unplayable,

    /** 求めた itag が応答に無い（再解決で別の itag に切り替えないため）。 */
    ItagUnavailable,

    /** 同じ itag だが中身（長さ・更新日時）が変わった。途中位置から継ぎ足せない。 */
    StreamChanged,

    /** 使える音声フォーマットが無い。 */
    NoAudioFormat,

    /** URL も signatureCipher も無いフォーマットしか無い。SABR だけの応答で、ADR-0001 が覆る兆候。 */
    SabrOnly,
    DecipherFailed,

    /** 解読後の URL が googlevideo の /videoplayback ではない。 */
    RejectedUrl,
}

sealed interface ResolveResult {
    class Ok(
        val stream: ResolvedStream,
    ) : ResolveResult

    data class Failed(
        val reason: StreamFailure,
    ) : ResolveResult
}

/** signatureCipher と n の解読。テストで差し替えるための境界。 */
interface FormatDecipherer {
    /** 入力と同じ順・同じ長さで返す。解けたものは url が入り、signatureCipher・cipher が消える。 */
    suspend fun decipher(
        playerUrl: String,
        formats: List<PlayerResponse.StreamingData.Format>,
    ): List<PlayerResponse.StreamingData.Format>

    /** 解読した URL が 403 などで拒否されたとき。faraday の設定を取り直す。 */
    suspend fun onStreamRejected()
}

/**
 * WEB_REMIX の /player から音声のストリーム URL を解決する（ADR-0001）。InnerTubeExtractor は使わない（ADR-0003）。
 */
class StreamResolver internal constructor(
    private val session: WaveSession,
    private val configParser: YtConfigParser,
    private val decipherer: FormatDecipherer,
    private val nowMs: () -> Long,
) {
    private class PlayerConfigCache(
        val playerUrl: String,
        val signatureTimestamp: Int,
        val fetchedAtMs: Long,
    )

    private val configMutex = Mutex()
    private var cachedConfig: PlayerConfigCache? = null

    /**
     * @param sameAs 再解決のとき、前に使ったストリーム。同じ itag・長さ・更新日時のものだけを使う
     *   （途中位置から別ファイルを継ぎ足さないため）
     */
    suspend fun resolve(
        videoId: String,
        sameAs: ResolvedStream? = null,
    ): ResolveResult = resolve(videoId, sameAs?.itag, sameAs)

    /** @param requiredItag 指定したら、その itag だけを使う */
    suspend fun resolve(
        videoId: String,
        requiredItag: Int?,
    ): ResolveResult = resolve(videoId, requiredItag, sameAs = null)

    private suspend fun resolve(
        videoId: String,
        requiredItag: Int?,
        sameAs: ResolvedStream?,
    ): ResolveResult {
        val config = playerConfig() ?: return ResolveResult.Failed(StreamFailure.PlayerConfigUnavailable)
        // 期限は /player を呼ぶ前の時刻から数える（解読にかかった時間の分だけ短く見積もる）
        val requestedAtMs = nowMs()

        val player =
            session.call(
                operation = "player",
                block = { it.player(YouTubeClient.WEB_REMIX, videoId, signatureTimestamp = config.signatureTimestamp) },
            ) { response ->
                val body = WaveSession.json.decodeFromString(PlayerResponse.serializer(), response.bodyAsTextLimited(WaveSession.MAX_PLAYER_BYTES))
                if (body.playabilityStatus.status == "LOGIN_REQUIRED") ParseOutcome.LoginRequired else ParseOutcome.Parsed(body)
            }
        val body =
            when (player) {
                is CallResult.Ok -> player.value
                is CallResult.Failed -> return ResolveResult.Failed(player.kind.toStreamFailure())
            }
        if (body.playabilityStatus.status != "OK") return ResolveResult.Failed(StreamFailure.Unplayable)
        val streaming = body.streamingData ?: return ResolveResult.Failed(StreamFailure.NoAudioFormat)

        val candidates = audioCandidates(streaming.adaptiveFormats, requiredItag)
        if (candidates.isEmpty()) {
            return ResolveResult.Failed(
                when {
                    requiredItag != null && streaming.adaptiveFormats.none { it.itag == requiredItag } -> StreamFailure.ItagUnavailable
                    streaming.adaptiveFormats.isNotEmpty() &&
                        streaming.adaptiveFormats.all { it.url == null && it.signatureCipher == null && it.cipher == null } -> StreamFailure.SabrOnly
                    else -> StreamFailure.NoAudioFormat
                },
            )
        }

        val deciphered =
            try {
                decipherer.decipher(config.playerUrl, candidates)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
        val chosen = deciphered.firstOrNull { it.url != null && it.signatureCipher == null && it.cipher == null }
        if (chosen == null) {
            invalidatePlayerConfig(config)
            return ResolveResult.Failed(StreamFailure.DecipherFailed)
        }
        if (sameAs != null && (chosen.contentLength != sameAs.contentLength || chosen.lastModified != sameAs.lastModified)) {
            return ResolveResult.Failed(StreamFailure.StreamChanged)
        }
        val url = removeQueryParameters(requireNotNull(chosen.url), UNWANTED_PARAMETERS)
        if (!isAllowedMediaUrl(url)) return ResolveResult.Failed(StreamFailure.RejectedUrl)

        val lifetimeMs = (streaming.expiresInSeconds?.takeIf { it > 0 }?.toLong() ?: DEFAULT_LIFETIME_SECONDS) * 1000
        return ResolveResult.Ok(
            ResolvedStream(
                videoId = videoId,
                itag = chosen.itag,
                mimeType = chosen.mimeType,
                contentLength = chosen.contentLength,
                lastModified = chosen.lastModified,
                url = url,
                expiresAtMs = requestedAtMs + lifetimeMs - EXPIRY_SAFETY_MS,
            ),
        )
    }

    /** 解決した URL が拒否された（403 など）。プレイヤーの設定を捨て、faraday の設定を取り直す。 */
    suspend fun onStreamRejected() {
        invalidatePlayerConfig(used = null)
        try {
            decipherer.onStreamRejected()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
    }

    /** 期限まで marginMs 以上残っているか。解決と同じ時計で測る。 */
    fun isFresh(
        stream: ResolvedStream,
        marginMs: Long,
    ): Boolean = stream.expiresAtMs - nowMs() > marginMs

    /** debug の確認用。キャッシュ中のプレイヤーの URL（公開のプレイヤー JS の場所で、秘密ではない）。 */
    internal fun currentPlayerUrl(): String? = cachedConfig?.playerUrl

    /** ログイン後などに、プレイヤーの設定を先に取っておく。 */
    suspend fun prewarm() {
        playerConfig()
    }

    private suspend fun playerConfig(): PlayerConfigCache? =
        configMutex.withLock {
            cachedConfig?.takeIf { nowMs() - it.fetchedAtMs < PLAYER_CONFIG_TTL_MS }?.let { return@withLock it }
            val fetched =
                try {
                    // STS とプレイヤーは曲に依らないので、固定の曲のページから取る。cookie は送らない
                    configParser.fetchConfig(WaveSession.PROBE_VIDEO_ID, useLoginCookies = false)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return@withLock null
                }
            val sts = fetched.signatureTimestamp ?: return@withLock null
            PlayerConfigCache(normalizePlayerUrl(fetched.playerUrl), sts, nowMs()).also { cachedConfig = it }
        }

    /** used を渡したら、それがまだキャッシュにあるときだけ捨てる（古い設定での失敗が、取り直した新しい設定を捨てないように）。 */
    private suspend fun invalidatePlayerConfig(used: PlayerConfigCache?) {
        configMutex.withLock { if (used == null || cachedConfig === used) cachedConfig = null }
    }

    internal companion object {
        /** 優先順。opus 約 256kbps → AAC 256kbps → opus 160 → AAC 128 → opus 70 → opus 50。 */
        val AUDIO_ITAG_PREFERENCE = listOf(774, 141, 251, 140, 250, 249)
        val UNWANTED_PARAMETERS = setOf("pot", "ump")
        const val PLAYER_CONFIG_TTL_MS = 60 * 60 * 1000L
        const val DEFAULT_LIFETIME_SECONDS = 60 * 60L
        const val EXPIRY_SAFETY_MS = 60 * 1000L

        fun audioCandidates(
            formats: List<PlayerResponse.StreamingData.Format>,
            requiredItag: Int?,
        ): List<PlayerResponse.StreamingData.Format> =
            formats
                .filter { it.isAudio && !it.isDrc && it.itag in AUDIO_ITAG_PREFERENCE }
                // 吹き替えなど音声トラックが複数ある曲では、既定のトラックだけを使う
                .filter { it.audioTrack == null || it.audioTrack?.audioIsDefault == true }
                .filter { requiredItag == null || it.itag == requiredItag }
                .filter { it.url != null || it.signatureCipher != null || it.cipher != null }
                .sortedBy { AUDIO_ITAG_PREFERENCE.indexOf(it.itag) }

        /** クエリの並びと他の値の符号化を変えずに、指定した名前のパラメータだけを外す。 */
        fun removeQueryParameters(
            url: String,
            names: Set<String>,
        ): String {
            val queryStart = url.indexOf('?')
            if (queryStart < 0) return url
            val fragmentStart = url.indexOf('#', queryStart).takeIf { it >= 0 } ?: url.length
            val kept =
                url
                    .substring(queryStart + 1, fragmentStart)
                    .split('&')
                    .filter { it.isNotEmpty() && it.substringBefore('=') !in names }
            val base = url.substring(0, queryStart)
            return (if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")) + url.substring(fragmentStart)
        }

        /** faraday の表と EJS で同じプレイヤーを二重に取らないよう、ロケール部分を揃える。 */
        fun normalizePlayerUrl(playerUrl: String): String {
            val hash = PLAYER_HASH.find(playerUrl)?.groupValues?.get(1) ?: return playerUrl
            return "https://www.youtube.com/s/player/$hash/player_ias.vflset/en_GB/base.js"
        }

        private val PLAYER_HASH = Regex("/s/player/([A-Za-z0-9_-]+)/")

        private fun FailureKind.toStreamFailure(): StreamFailure =
            when (this) {
                FailureKind.NotLoggedIn -> StreamFailure.NotLoggedIn
                FailureKind.Expired -> StreamFailure.Expired
                FailureKind.Suspect -> StreamFailure.Suspect
                FailureKind.Network -> StreamFailure.Network
                FailureKind.Http -> StreamFailure.Http
                FailureKind.Parse -> StreamFailure.Parse
            }
    }
}

/** faraday の設定（公開データ）をメモリ上に持つ。取得先は ADR-0005。 */
internal class InMemoryPlayerConfigRepository : PlayerConfigRepository {
    override val enabled: Boolean = true
    override val sourceUrl: String = FARADAY_CONFIG_URL
    override val defaultSourceUrl: String = FARADAY_CONFIG_URL
    override var cachedJson: String = ""
    override var cachedAtMs: Long = 0L
    override var cachedSourceUrl: String = ""
    override var cachedEtag: String = ""

    companion object {
        const val FARADAY_CONFIG_URL = "https://raw.githubusercontent.com/MetrolistGroup/faraday/master/registry/player_configs.json"
    }
}

/** faraday の設定の取得状況（debug の確認用。値は出さない）。 */
class FaradayStatus internal constructor(
    val tableLoaded: Boolean,
    val playerInTable: Boolean?,
)

/** WaveSession の HTTP クライアント（認証ヘッダのガード付き）で、ストリームの解決に要る部品を組み立てる。 */
class StreamComponents internal constructor(
    val resolver: StreamResolver,
    private val repository: InMemoryPlayerConfigRepository,
    /** debug の確認用。create で trackCipherPath を指定したときだけ。 */
    val cipherPath: CipherPathStats?,
) {
    fun faradayStatus(): FaradayStatus {
        val playerUrl = resolver.currentPlayerUrl()
        val loaded = repository.cachedJson.isNotBlank()
        val hash = playerUrl?.let { Regex("/s/player/([A-Za-z0-9_-]+)/").find(it)?.groupValues?.get(1) }
        // 表のキーとして完全一致するか（部分一致で誤判定しない）
        return FaradayStatus(loaded, if (loaded && hash != null) repository.cachedJson.contains("\"$hash\"") else null)
    }

    companion object {
        /**
         * @param nowMs スリープ中も進む時計（Android では SystemClock.elapsedRealtime）。URL の期限の判定に使う
         * @param trackCipherPath debug の確認用に、解読がどの経路を通ったかを数える
         */
        fun create(
            session: WaveSession,
            nowMs: () -> Long,
            trackCipherPath: Boolean = false,
        ): StreamComponents {
            val cipherPath = if (trackCipherPath) CipherPathStats() else null
            val logger = cipherPath?.logger ?: InnerTubeLogger.NONE
            val repository = InMemoryPlayerConfigRepository()
            val store = RemotePlayerConfigStore(session.httpClient, repository, logger)
            val cipher = YouTubeCipherService(session.httpClient, store, logger)
            val decipherer =
                object : FormatDecipherer {
                    override suspend fun decipher(
                        playerUrl: String,
                        formats: List<PlayerResponse.StreamingData.Format>,
                    ) = cipher.processFormats(playerUrl, formats)

                    override suspend fun onStreamRejected() {
                        cipher.refreshAfterStreamRejection()
                    }
                }
            val parser = YtConfigParserImpl(session.httpClient, session.innerTube, store, logger)
            return StreamComponents(StreamResolver(session, parser, decipherer, nowMs), repository, cipherPath)
        }
    }
}
