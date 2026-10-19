package xyz.mooma0750.wave.playback

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.core.music.Song

/** 曲を JSON にして画面・再生サービス・通知の間で受け渡す（Bundle・Intent・MediaItem の extras に載せる）。読めなければ null。 */
object SongJson {
    val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Song.serializer())

    fun encode(song: Song): String = json.encodeToString(Song.serializer(), song)

    fun decode(text: String?): Song? = text?.let { runCatching { json.decodeFromString(Song.serializer(), it) }.getOrNull() }

    fun encodeList(songs: List<Song>): String = json.encodeToString(listSerializer, songs)

    fun decodeList(text: String?): List<Song>? = text?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }
}
