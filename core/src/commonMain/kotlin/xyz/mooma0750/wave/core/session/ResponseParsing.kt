package xyz.mooma0750.wave.core.session

import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.models.response.AccountMenuResponse
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.Serializable

internal suspend fun parseAccountMenu(response: HttpResponse): AccountMenuResponse =
    WaveSession.json.decodeFromString(
        AccountMenuResponse.serializer(),
        response.bodyAsTextLimited(WaveSession.MAX_ACCOUNT_MENU_BYTES),
    )

/**
 * accountMenu の応答が、ログアウトしていることを示しているか。
 * 中身の分からない応答（`{}` など）で全員を失効扱いにしないよう、actions があるのにアカウントのヘッダが無い場合だけ true にする。
 * 実際のログアウト時の応答の形は未検証（M8 の実機確認で確かめる）。
 */
internal fun showsLoggedOut(body: AccountMenuResponse): Boolean = body.actions.isNotEmpty() && activeAccountOf(body) == null

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
