package xyz.mooma0750.wave.core.music

/**
 * ほかのアプリ（ショートカット・Tasker など）から渡された「鳴らすもの」の YouTube / YouTube Music のリンクか ID。
 * Mac 版も同じ見分け方を Swift で持つので、規則を変えるときは両方そろえる。
 *
 * - 動画: `music.youtube.com/watch?v=`・`youtube.com/watch?v=`・`youtu.be/<id>`・`youtube.com/live/<id>`・`youtube.com/shorts/<id>`、
 *   ID だけなら 11 文字の videoId。曲かライブかは、ここでは決めない（鳴らす側が曲の情報を引いて決める）
 * - プレイリスト: `v=` の無い `list=`（`PL…`・`OLAK5uy_…`・`RD…` など）、ID だけなら `PL`・`OLAK5uy_`・`RD` で始まるもの
 * - アルバム: `browse/MPREb_…`、ID だけなら `MPREb_` で始まるもの
 */
sealed interface PlayLink {
    data class Video(
        val videoId: String,
    ) : PlayLink

    data class Playlist(
        val playlistId: String,
    ) : PlayLink

    data class Album(
        val browseId: String,
    ) : PlayLink

    companion object {
        /** 読めなければ null（YouTube 以外のリンク、形の合わない ID）。 */
        fun parse(text: String): PlayLink? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            return if (looksLikeUrl(trimmed)) url(trimmed) else id(trimmed)
        }

        private fun looksLikeUrl(text: String): Boolean = "://" in text || '/' in text || '?' in text

        private fun url(text: String): PlayLink? {
            val withoutScheme = text.substringAfter("://")
            val hostEnd = withoutScheme.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) withoutScheme.length else it }
            val host = withoutScheme.substring(0, hostEnd).substringAfterLast('@').substringBefore(':').lowercase()
            if (host !in HOSTS) return null
            val rest = withoutScheme.substring(hostEnd).substringBefore('#')
            val segments = rest.substringBefore('?').split('/').filter { it.isNotEmpty() }
            val params =
                rest
                    .substringAfter('?', "")
                    .split('&')
                    .mapNotNull { pair -> pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { it.value.first() }
            if (host == "youtu.be" || host == "www.youtu.be") return segments.firstOrNull()?.let(::video)
            if (segments.firstOrNull() == "watch") params["v"]?.let(::video)?.let { return it }
            if (segments.size >= 2 && segments[0] in VIDEO_PATHS) return video(segments[1])
            if (segments.size >= 2 && segments[0] == "browse" && segments[1].startsWith(ALBUM_PREFIX) && isIdChars(segments[1])) return Album(segments[1])
            return params["list"]?.takeIf { it.isNotEmpty() && isIdChars(it) }?.let(::Playlist)
        }

        private fun id(text: String): PlayLink? =
            when {
                !isIdChars(text) -> null
                text.startsWith(ALBUM_PREFIX) -> Album(text)
                text.length == VIDEO_ID_LENGTH -> Video(text)
                PLAYLIST_PREFIXES.any { text.startsWith(it) } -> Playlist(text)
                else -> null
            }

        private fun video(id: String): Video? = id.takeIf { it.length == VIDEO_ID_LENGTH && isIdChars(it) }?.let(::Video)

        private fun isIdChars(text: String): Boolean = text.isNotEmpty() && text.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }

        private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be")
        private val VIDEO_PATHS = setOf("live", "shorts")
        private val PLAYLIST_PREFIXES = listOf("PL", "OLAK5uy_", "RD")
        private const val ALBUM_PREFIX = "MPREb_"
        private const val VIDEO_ID_LENGTH = 11
    }
}
