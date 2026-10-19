package xyz.mooma0750.wave.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.core.music.HomeItem
import xyz.mooma0750.wave.core.music.HomeShelf
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.handoff.HandoffItem
import xyz.mooma0750.wave.handoff.HandoffRepeat
import xyz.mooma0750.wave.handoff.HandoffSnapshot
import xyz.mooma0750.wave.handoff.v2.CursorBody

/** API の応答の形（docs/wave-api.md）。home-dashboard の MusicProvider の形に合わせる。 */
@Serializable
data class TrackView(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artUrl: String?,
    val durationMs: Long?,
    val isLive: Boolean,
)

@Serializable
data class DeviceView(
    val id: String,
    val name: String,
    val kind: String?,
    val online: Boolean,
    /** 家の最後の再生を書いた端末（鳴っているとは限らない。isPlaying を見る）。 */
    val active: Boolean,
    /** wave-api 自身。行き先には選べない。 */
    val self: Boolean = false,
)

@Serializable
data class PlaybackView(
    /** wave-api が家に入っている。 */
    val joined: Boolean,
    /** wave-api にログインが渡されている（プレイリスト・検索・鳴らす・キューに足すに要る）。 */
    val loggedIn: Boolean,
    val isPlaying: Boolean,
    val repeat: String,
    val track: TrackView?,
    val progressMs: Long?,
    /** progressMs を読んだ wave-api の時刻（ISO 8601）。画面はここから位置を進める。 */
    val observedAt: String,
    val device: DeviceView?,
    val volumePercent: Int?,
)

@Serializable
data class PlaylistView(
    val id: String,
    val name: String,
    val subtitle: String?,
    val artUrl: String?,
)

@Serializable
data class ShelfView(
    val title: String,
    val tracks: List<TrackView>,
    val playlists: List<PlaylistView>,
)

@Serializable
data class ErrorView(
    val error: String,
)

/** 家の状態から応答を組み立てる（ConnectClient に依らない純粋な関数。テストで確かめる）。 */
object Views {
    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
        }

    fun track(item: HandoffItem) =
        TrackView(item.videoId, item.title, item.artists.joinToString(", ") { it.name }, item.album?.name, item.thumbnailUrl, item.durationSeconds?.let { it * 1000L }?.takeUnless { item.isLive }, item.isLive)

    fun track(song: Song) =
        TrackView(song.videoId, song.title, song.artistsText, song.album?.title, song.thumbnailUrl, song.durationSeconds?.let { it * 1000L }?.takeUnless { song.isLive }, song.isLive)

    fun playlist(summary: PlaylistSummary) = PlaylistView(summary.playlistId, summary.title, summary.subtitle, summary.thumbnailUrl)

    fun shelf(shelf: HomeShelf) =
        ShelfView(
            shelf.title,
            shelf.items.mapNotNull { (it as? HomeItem.SongItem)?.song?.let(::track) },
            shelf.items.mapNotNull { (it as? HomeItem.PlaylistItem)?.playlist?.let(::playlist) },
        )

    fun repeat(value: HandoffRepeat) =
        when (value) {
            HandoffRepeat.Off -> "off"
            HandoffRepeat.One -> "track"
            HandoffRepeat.All -> "context"
        }

    /** キューの doc の文字列を読む（検証は鳴らしている端末が済ませているので、形だけ）。読めなければ null。 */
    fun snapshot(text: String): HandoffSnapshot? = runCatching { json.decodeFromString(HandoffSnapshot.serializer(), text) }.getOrNull()

    /** 今の曲（カーソルの曲をキューから引く）。 */
    fun currentItem(
        snapshot: HandoffSnapshot?,
        cursor: CursorBody,
    ): HandoffItem? = snapshot?.items?.firstOrNull { it.itemId == cursor.currentItemId }

    /** 今の曲より後の曲。 */
    fun upcoming(
        snapshot: HandoffSnapshot?,
        cursor: CursorBody,
    ): List<HandoffItem> {
        val items = snapshot?.items ?: return emptyList()
        val index = items.indexOfFirst { it.itemId == cursor.currentItemId }
        return if (index < 0) emptyList() else items.drop(index + 1)
    }

    /**
     * 「鳴らす」の行き先。指定があればそれ（家の player で、自分でなく、中継につながっている）、
     * 無ければ家の最後の再生を書いた端末（つながっているとき）。どれも無ければ null。
     */
    fun target(
        requested: String?,
        self: String,
        players: Set<String>,
        online: Set<String>,
        lastWriter: String?,
    ): String? {
        fun usable(id: String?) = id != null && id != self && id in players && id in online
        if (requested != null) return requested.takeIf(::usable)
        return lastWriter.takeIf(::usable)
    }
}
