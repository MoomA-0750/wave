package xyz.mooma0750.wave.core.session

import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.models.response.AccountMenuResponse
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.Serializable

/** ログイン中のアカウント。ヘッダが無い（ログアウト相当の応答）なら null。 */
internal suspend fun parseActiveAccount(response: HttpResponse): AccountResult.Ok? {
    val body =
        WaveSession.json.decodeFromString(
            AccountMenuResponse.serializer(),
            response.bodyAsTextLimited(WaveSession.MAX_ACCOUNT_MENU_BYTES),
        )
    return activeAccountOf(body)
}

internal fun activeAccountOf(body: AccountMenuResponse): AccountResult.Ok? {
    val header =
        body.actions
            .asSequence()
            .mapNotNull { it.openPopupAction?.popup }
            .mapNotNull { popup -> (popup.multiPageMenuRenderer ?: popup.popupContent?.multiPageMenuRenderer)?.header?.activeAccountHeaderRenderer }
            .firstOrNull() ?: return null
    val info = header.toAccountInfo()
    return AccountResult.Ok(
        // innertubex は名前が欠けていると "Unknown" を返す
        name = header.accountName?.runs?.firstOrNull()?.text,
        handle = info.channelHandle,
        thumbnailUrl = info.thumbnailUrl,
    )
}

/** Premium 判定に要る部分だけ。 */
@Serializable
internal class PlayerProbeResponse(
    val playabilityStatus: PlayabilityStatus? = null,
    val streamingData: StreamingData? = null,
) {
    @Serializable
    class PlayabilityStatus(
        val status: String? = null,
    )

    @Serializable
    class StreamingData(
        val adaptiveFormats: List<Format> = emptyList(),
    )

    @Serializable
    class Format(
        val itag: Int = -1,
    )
}

internal val PREMIUM_ITAGS: Set<Int> = setOf(141, 774)

internal fun premiumStatusOf(body: PlayerProbeResponse): PremiumStatus {
    if (body.playabilityStatus?.status != "OK") return PremiumStatus.Unknown
    val itags = body.streamingData?.adaptiveFormats.orEmpty().map { it.itag }
    return if (itags.any { it in PREMIUM_ITAGS }) PremiumStatus.Available else PremiumStatus.Unavailable
}
