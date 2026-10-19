package xyz.mooma0750.wave.api

import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.handoff.HandoffCodec
import xyz.mooma0750.wave.handoff.HandoffItem
import xyz.mooma0750.wave.handoff.HandoffOrigin
import xyz.mooma0750.wave.handoff.HandoffRepeat
import xyz.mooma0750.wave.handoff.HandoffSnapshot
import xyz.mooma0750.wave.handoff.HandoffSource
import java.util.UUID

/** 「鳴らす」「キューに足す」のキューを作る（曲・プレイリスト・アルバムを、渡されたログインで読む）。 */
class QueueBuilder(
    private val music: MusicRepository,
) {
    sealed interface Built {
        data class Ok(
            val snapshot: HandoffSnapshot,
        ) : Built

        data class Failed(
            val kind: FailureKind?,
        ) : Built
    }

    /** 曲から始めるラジオ（Android で曲を1つ選んだときと同じ）。 */
    suspend fun radio(videoId: String): Built {
        val page = music.upNext(videoId, "RDAMVM$videoId")
        if (page !is CallResult.Ok) return Built.Failed((page as CallResult.Failed).kind)
        // 選んだ曲を先頭に、続きをラジオで並べる（Android で曲を選んだときと同じ）。ラジオの一覧に選んだ曲が無いこと（MV と
        // 音源で ID が違うなど）があるので、そのときは曲だけを引いて先頭に置く
        val first =
            page.value.songs.firstOrNull { it.videoId == videoId }
                ?: when (val single = music.upNext(videoId, null)) {
                    is CallResult.Ok -> single.value.songs.firstOrNull { it.videoId == videoId }
                    is CallResult.Failed -> return Built.Failed(single.kind)
                }
                ?: return Built.Failed(null)
        val songs = listOf(first) + page.value.songs.filter { it.videoId != videoId }
        return Built.Ok(snapshot(songs, 0, HandoffSource.Radio(videoId), page.value.continuation, page.value.playlistId))
    }

    /** プレイリスト（startVideoId があればそこから。shuffle なら混ぜて、startVideoId を頭に）。 */
    suspend fun playlist(
        playlistId: String,
        startVideoId: String?,
        shuffle: Boolean,
    ): Built {
        val songs = ArrayList<Song>()
        var continuation: String? = null
        var pages = 0
        do {
            val page = music.playlist(playlistId, continuation)
            if (page !is CallResult.Ok) {
                if (pages == 0) return Built.Failed((page as CallResult.Failed).kind)
                break
            }
            songs += page.value.songs
            continuation = page.value.continuation
            pages++
        } while (continuation != null && songs.size < MAX_READ)
        return fromList(songs, startVideoId, shuffle, HandoffSource.Playlist(playlistId), continuation)
    }

    suspend fun album(
        browseId: String,
        startVideoId: String?,
        shuffle: Boolean,
    ): Built {
        val page = music.album(browseId)
        if (page !is CallResult.Ok) return Built.Failed((page as CallResult.Failed).kind)
        return fromList(page.value.songs, startVideoId, shuffle, page.value.playlistId?.let(HandoffSource::Playlist), null)
    }

    /** キューに足す曲（動画 ID から曲の情報を引く）。引けない曲は落とす。 */
    suspend fun items(videoIds: List<String>): List<HandoffItem>? {
        val items = ArrayList<HandoffItem>()
        for (videoId in videoIds.distinct().take(MAX_ADD)) {
            val page = music.upNext(videoId, null)
            if (page !is CallResult.Ok) {
                if ((page as CallResult.Failed).kind == FailureKind.NotLoggedIn) return null
                continue
            }
            page.value.songs.firstOrNull { it.videoId == videoId }?.let { items += item(it, HandoffOrigin.UserAdded) }
        }
        return items
    }

    private fun fromList(
        all: List<Song>,
        startVideoId: String?,
        shuffle: Boolean,
        source: HandoffSource?,
        continuation: String?,
    ): Built {
        val songs = all.filter { HandoffCodec.isItemVideoId(it.videoId) && it.title.isNotEmpty() }.distinctBy { it.videoId }
        if (songs.isEmpty()) return Built.Failed(null)
        // 最初の曲を選んでいないシャッフルは、最初の曲も混ぜる
        val start = startVideoId?.let { id -> songs.indexOfFirst { it.videoId == id } }?.takeIf { it >= 0 } ?: if (shuffle) songs.indices.random() else 0
        if (!shuffle) return Built.Ok(snapshot(songs, start, source, continuation, null))
        val first = songs[start]
        val rest = (songs - first).shuffled()
        // 混ぜたキューは続きのページを読まない（読み込み済みの末尾を指さなくなる）
        return Built.Ok(snapshot(listOf(first) + rest, 0, source, null, null))
    }

    companion object {
        /** プレイリストを読む上限（送れるのは今の曲の後ろ 300 曲まで）。 */
        const val MAX_READ = 400
        const val MAX_ADD = 50

        fun item(
            song: Song,
            origin: HandoffOrigin,
        ) = HandoffItem(
            itemId = UUID.randomUUID().toString(),
            videoId = song.videoId,
            title = song.title.take(500),
            artists = song.artists.mapNotNull { HandoffCodec.ref(it.name, it.browseId) }.take(20),
            album = song.album?.let { HandoffCodec.ref(it.title, it.browseId) },
            durationSeconds = song.durationSeconds,
            thumbnailUrl = song.thumbnailUrl,
            origin = origin,
            isLive = song.isLive,
        )

        fun snapshot(
            songs: List<Song>,
            startIndex: Int,
            source: HandoffSource?,
            continuation: String?,
            radioPlaylistId: String?,
        ): HandoffSnapshot {
            val (window, current, cut) = HandoffCodec.window(songs.map { item(it, HandoffOrigin.Source) }, startIndex)
            return HandoffSnapshot(
                schemaVersion = HandoffCodec.SCHEMA_VERSION,
                handoffId = UUID.randomUUID().toString(),
                items = window,
                currentItemId = window[current].itemId,
                positionMs = 0,
                playing = false,
                repeat = HandoffRepeat.Off,
                source = source,
                continuation = continuation.takeUnless { cut },
                radioPlaylistId = radioPlaylistId,
                exhausted = continuation == null || cut,
                listenedMs = 0,
                historySent = false,
                capturedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }
}
