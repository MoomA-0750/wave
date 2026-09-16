package xyz.mooma0750.wave.core.auth

import kotlin.jvm.JvmInline

/** 秘密の文字列。toString・ログ・例外文に値を出さない。値を取り出す場所は reveal() の呼び出しで grep できる。 */
@JvmInline
value class Secret(private val value: String) {
    fun reveal(): String = value

    override fun toString(): String = "Secret(***)"
}

/** ログインで得た認証情報。data class にしない（copy・componentN・自動 toString を作らない）。 */
class Credentials private constructor(
    val cookie: Secret,
    /** "||" の前だけに切ってある。 */
    val dataSyncId: Secret?,
    /** 追跡用の ID で、認証情報ではない。 */
    val visitorData: String?,
    val obtainedAtEpochMs: Long,
) {
    override fun toString(): String =
        "Credentials(cookie=present, dataSyncId=${dataSyncId != null}, visitorData=${visitorData != null})"

    companion object {
        private val SAPISID_COOKIE_NAMES = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

        /**
         * WebView から取った生の値で作る。制御文字を含むとき（innertubex が例外を投げるため）と、
         * SAPISID 系の cookie が無いときは null。
         */
        fun fromLogin(
            rawCookie: String,
            visitorData: String?,
            rawDataSyncId: String?,
            nowEpochMs: Long,
        ): Credentials? {
            if (!isAcceptable(rawCookie, visitorData, rawDataSyncId)) return null
            val cookie = rawCookie.trim()
            if (!hasSapisid(cookie)) return null
            return Credentials(
                cookie = Secret(cookie),
                dataSyncId = normalizeDataSyncId(rawDataSyncId)?.let(::Secret),
                visitorData = visitorData?.takeIf { it.isNotBlank() },
                obtainedAtEpochMs = nowEpochMs,
            )
        }

        /** 保存形式からの復元。検査は fromLogin と同じ。 */
        internal fun restore(
            cookie: String,
            dataSyncId: String?,
            visitorData: String?,
            obtainedAtEpochMs: Long,
        ): Credentials? = fromLogin(cookie, visitorData, dataSyncId, obtainedAtEpochMs)

        private fun isAcceptable(vararg values: String?): Boolean =
            values.all { value -> value == null || value.none { it.isISOControl() } }

        private fun hasSapisid(cookie: String): Boolean =
            cookie
                .split(';')
                .map { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
                .any { (name, value) -> name in SAPISID_COOKIE_NAMES && value.isNotEmpty() }
    }
}

/** DATASYNC_ID は "||" の前だけを onBehalfOfUser に使う（丸ごと渡すと 500 になる）。空になったら null。 */
internal fun normalizeDataSyncId(raw: String?): String? = raw?.substringBefore("||")?.trim()?.takeIf { it.isNotEmpty() }
