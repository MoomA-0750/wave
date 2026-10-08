package xyz.mooma0750.wave.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.lyrics.Lyrics
import xyz.mooma0750.wave.core.lyrics.LyricsRepository
import xyz.mooma0750.wave.core.music.AddToPlaylistResult
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.WriteResult
import xyz.mooma0750.wave.playback.Media3QueuePlayer

/**
 * Claude が使う音楽の道具（tools/ai-bridge/wave_agent_mcp.py の道具を、手元で行う）。
 * 曲は video_id で受け渡す。この会話で出てきた曲（今の曲・キュー・検索・高評価）だけを覚えていて、それ以外の id は断る
 * （Claude が作った id で知らない曲を入れない）。
 */
class AiTools(
    private val container: AppContainer,
    /** 画面の文言（端末の言語）。 */
    private val text: Texts,
) {
    /** 道具の結果。shown は会話に出す1行（null なら出さない）。songs は会話に並べる曲。 */
    data class Outcome(
        val result: JsonElement = JsonPrimitive("ok"),
        val error: String? = null,
        val shown: String? = null,
        val songs: List<Song>? = null,
    )

    /** 画面に出す文言。 */
    interface Texts {
        fun searched(query: String): String

        fun played(count: Int): String

        fun queued(count: Int): String

        fun created(title: String, count: Int): String

        fun added(title: String, count: Int): String

        fun readLiked(): String
    }

    data class Context(
        val song: Song?,
        val lyrics: String?,
    )

    private val known = HashMap<String, Song>()

    /** 頼みに付ける、今の曲と歌詞。 */
    suspend fun context(): Context {
        val song = currentSong() ?: return Context(null, null)
        val lyrics =
            when (val result = container.lyrics.lyrics(song)) {
                is LyricsRepository.Result.Found ->
                    when (val l = result.lyrics) {
                        is Lyrics.Synced -> l.lines.joinToString("\n") { it.text }
                        is Lyrics.Plain -> l.text
                    }
                else -> null
            }
        return Context(song, lyrics)
    }

    private suspend fun currentSong(): Song? {
        // ほかの端末で鳴っていれば、その曲
        container.connectV2?.remotePlayback?.value?.let { return it.current.also(::remember) }
        val controller = container.player.controller()
        return withContext(Dispatchers.Main) { Media3QueuePlayer.songOf(controller.currentMediaItem) }?.also(::remember)
    }

    private fun remember(song: Song) {
        known[song.videoId] = song
    }

    suspend fun call(
        name: String,
        arguments: JsonObject,
    ): Outcome =
        try {
            Log.i(WaveApplication.LOG_TAG, "ai tool=$name")
            when (name) {
                "now_playing" -> nowPlaying()
                "get_queue" -> queue()
                "search_songs" -> search(arguments.string("query").orEmpty(), (arguments.int("limit") ?: 5).coerceIn(1, 20))
                "liked_songs" -> liked((arguments.int("limit") ?: 50).coerceIn(1, 200))
                "show_songs" -> songsOf(arguments).let { (songs, missing) -> if (songs.isEmpty()) unknown(missing) else Outcome(JsonPrimitive("shown ${songs.size}"), songs = songs, shown = "") }
                "play_songs" -> play(arguments)
                "add_to_queue" -> addToQueue(arguments)
                "list_playlists" -> playlists()
                "create_playlist" -> createPlaylist(arguments.string("title").orEmpty(), arguments)
                "add_to_playlist" -> addToPlaylist(arguments.string("playlist_id").orEmpty(), arguments)
                else -> Outcome(error = "unknown tool: $name")
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Outcome(error = "failed: ${error::class.simpleName}")
        }

    private suspend fun nowPlaying(): Outcome {
        val context = context()
        val song = context.song ?: return Outcome(JsonPrimitive("nothing is playing"))
        return Outcome(
            buildJsonObject {
                put("song", songJson(song))
                context.lyrics?.let { put("lyrics", it) }
            },
        )
    }

    private suspend fun queue(): Outcome {
        val controller = container.player.controller()
        val (songs, current) =
            withContext(Dispatchers.Main) {
                (0 until controller.mediaItemCount).mapNotNull { Media3QueuePlayer.songOf(controller.getMediaItemAt(it)) } to controller.currentMediaItemIndex
            }
        songs.forEach(::remember)
        return Outcome(
            buildJsonObject {
                put("current_index", current)
                put("songs", buildJsonArray { songs.drop((current - 20).coerceAtLeast(0)).take(100).forEach { add(songJson(it)) } })
            },
        )
    }

    private suspend fun search(
        query: String,
        limit: Int,
    ): Outcome {
        if (query.isBlank()) return Outcome(error = "query is empty")
        return when (val result = container.music.searchSongs(query)) {
            is CallResult.Ok -> {
                val songs = result.value.songs.take(limit)
                songs.forEach(::remember)
                Outcome(buildJsonArray { songs.forEach { add(songJson(it)) } }, shown = text.searched(query))
            }
            is CallResult.Failed -> Outcome(error = "search failed: ${result.kind}")
        }
    }

    private suspend fun liked(limit: Int): Outcome {
        val songs = container.likedSongs.songs(container.likedSongsVersion.value).songs.take(limit)
        songs.forEach(::remember)
        return Outcome(buildJsonArray { songs.forEach { add(songJson(it)) } }, shown = text.readLiked())
    }

    private suspend fun play(arguments: JsonObject): Outcome {
        val (songs, missing) = songsOf(arguments)
        if (songs.isEmpty()) return unknown(missing)
        container.player.playFrom(songs, 0, null, null)
        return Outcome(JsonPrimitive("playing ${songs.size} songs"), shown = text.played(songs.size))
    }

    private suspend fun addToQueue(arguments: JsonObject): Outcome {
        val (songs, missing) = songsOf(arguments)
        if (songs.isEmpty()) return unknown(missing)
        if (arguments.bool("next") == true) container.player.addNext(songs) else container.player.addToEnd(songs)
        return Outcome(JsonPrimitive("queued ${songs.size} songs"), shown = text.queued(songs.size))
    }

    private suspend fun playlists(): Outcome =
        when (val result = container.music.libraryPlaylists()) {
            is CallResult.Ok ->
                Outcome(
                    buildJsonArray {
                        result.value.filter { it.editable }.forEach {
                            add(
                                buildJsonObject {
                                    put("playlist_id", it.playlistId)
                                    put("title", it.title)
                                },
                            )
                        }
                    },
                )
            is CallResult.Failed -> Outcome(error = "failed: ${result.kind}")
        }

    private suspend fun createPlaylist(
        title: String,
        arguments: JsonObject,
    ): Outcome {
        if (title.isBlank()) return Outcome(error = "title is empty")
        val (songs, missing) = songsOf(arguments)
        val generation = container.music.currentLoginGeneration()
        return when (val created = container.music.createPlaylist(title, generation)) {
            is WriteResult.Done -> {
                // 作ったばかりで空と分かっているので、最初の1曲は重複の確認を省く
                var added = 0
                songs.forEachIndexed { index, song ->
                    val result = container.music.addToPlaylist(created.value, song.videoId, generation, knownEmpty = index == 0)
                    if (result == AddToPlaylistResult.Added) added++
                }
                container.onPlaylistsChanged()
                Outcome(
                    buildJsonObject {
                        put("playlist_id", created.value)
                        put("added", added)
                        if (missing.isNotEmpty()) put("unknown_video_ids", buildJsonArray { missing.forEach { add(JsonPrimitive(it)) } })
                    },
                    shown = text.created(title, added),
                )
            }
            is WriteResult.Failed -> Outcome(error = "create failed: ${created.kind}")
            WriteResult.Unknown -> Outcome(error = "create result unknown")
        }
    }

    private suspend fun addToPlaylist(
        playlistId: String,
        arguments: JsonObject,
    ): Outcome {
        val (songs, missing) = songsOf(arguments)
        if (songs.isEmpty()) return unknown(missing)
        val title = (container.music.libraryPlaylists() as? CallResult.Ok)?.value?.firstOrNull { it.playlistId == playlistId }?.title ?: return Outcome(error = "unknown playlist_id")
        var added = 0
        var already = 0
        songs.forEach { song ->
            when (container.music.addToPlaylist(playlistId, song.videoId)) {
                AddToPlaylistResult.Added -> {
                    added++
                    container.playlistMembership.noteAdded(playlistId, song.videoId)
                }
                AddToPlaylistResult.AlreadyInPlaylist -> already++
                else -> Unit
            }
        }
        container.onPlaylistsChanged()
        return Outcome(
            buildJsonObject {
                put("added", added)
                put("already_in_playlist", already)
            },
            shown = text.added(title, added),
        )
    }

    /** video_id の並びから、知っている曲を引く（知らない id は別に返す）。 */
    private fun songsOf(arguments: JsonObject): Pair<List<Song>, List<String>> {
        val ids = (arguments["video_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        val songs = ids.mapNotNull { known[it] }
        return songs to ids.filter { it !in known }
    }

    private fun unknown(missing: List<String>) = Outcome(error = "unknown video_ids (use search_songs first): ${missing.joinToString(",")}")

    private fun songJson(song: Song) =
        buildJsonObject {
            put("video_id", song.videoId)
            put("title", song.title)
            put("artists", song.artists.joinToString(", ") { it.name })
            song.album?.let { put("album", it.title) }
            song.durationSeconds?.let { put("duration_seconds", it) }
            song.kind?.let { put("kind", it.name) }
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
