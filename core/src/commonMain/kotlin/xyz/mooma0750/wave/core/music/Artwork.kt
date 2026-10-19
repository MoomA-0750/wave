package xyz.mooma0750.wave.core.music

/** 絵の URL の大きさ（Android の再生画面・通知・時計と、wave API の再生中の曲で使う）。 */
object Artwork {
    /** i.ytimg.com/vi/<id>/<名前>.jpg の「<名前>.jpg（と後ろの指定）」。 */
    private val YTIMG_THUMBNAIL = Regex("/[a-z0-9_]+\\.jpg.*$")

    /**
     * 一覧のサムネイル（小さい）を、再生画面・通知・時計に耐える大きさにする。
     * googleusercontent / ggpht は `=w…-h…` や `=s…` のサイズ指定を書き換え、指定が無ければ足す。
     * 動画のサムネイル（i.ytimg.com）は、いつでもある大きめの hqdefault.jpg にする（小さいままだと時計に画像が出ない）。
     */
    fun large(url: String): String {
        val withoutQuery = url.substringBefore('?')
        return when {
            "googleusercontent.com" in withoutQuery || "ggpht.com" in withoutQuery -> withoutQuery.substringBefore('=') + "=w544-h544"
            "i.ytimg.com" in withoutQuery && YTIMG_THUMBNAIL.containsMatchIn(withoutQuery) -> withoutQuery.replace(YTIMG_THUMBNAIL, "/hqdefault.jpg")
            else -> url
        }
    }
}
