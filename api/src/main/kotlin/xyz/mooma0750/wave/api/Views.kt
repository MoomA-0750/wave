package xyz.mooma0750.wave.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.core.music.Artwork
import xyz.mooma0750.wave.core.music.HomeItem
import xyz.mooma0750.wave.core.music.HomeShelf
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.handoff.HandoffCodec
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
    /** 鳴っている端末のシャッフル。前の版の端末なら null（シャッフル・繰り返しを変えられない）。 */
    val shuffle: Boolean?,
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

    /** 再生中の曲（大きく出すので、絵は一覧用の小さい大きさから再生画面の大きさにする）。 */
    fun nowPlaying(item: HandoffItem) = track(item).let { it.copy(artUrl = it.artUrl?.let(Artwork::large)) }

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

    /** `/v1/control` の repeat の名前から（知らない名前は null）。 */
    fun repeatOf(name: String?) =
        when (name) {
            "off" -> HandoffRepeat.Off
            "track" -> HandoffRepeat.One
            "context" -> HandoffRepeat.All
            else -> null
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
     * キューの曲へ飛ぶ: 今のキューを、選んだ曲を今の曲にして作り直す（Android でほかの端末を映している間にキューの曲を選んだときと同じく、
     * 鳴らしている端末に引き取り直してもらう）。index は [upcoming] の何番目か（0 が次の曲）、videoId はその曲。
     * 両方あれば両方が合うとき、videoId だけなら後ろの最初のその曲。見つからなければ null。
     */
    fun jump(
        snapshot: HandoffSnapshot?,
        cursor: CursorBody,
        index: Int?,
        videoId: String?,
        handoffId: String,
        nowEpochMs: Long,
    ): HandoffSnapshot? {
        val items = snapshot?.items ?: return null
        val upcoming = upcoming(snapshot, cursor)
        val chosen =
            when {
                index != null -> upcoming.getOrNull(index)?.takeIf { videoId == null || it.videoId == videoId }
                videoId != null -> upcoming.firstOrNull { it.videoId == videoId }
                else -> null
            } ?: return null
        // 選んだ曲より前が増えるので、受け手の検査（前は 20 曲まで）に収まるよう切り直す
        val (window, current, cut) = HandoffCodec.window(items, items.indexOf(chosen))
        return snapshot.copy(
            handoffId = handoffId,
            items = window,
            currentItemId = window[current].itemId,
            positionMs = 0,
            playing = true,
            repeat = cursor.repeat,
            continuation = snapshot.continuation.takeUnless { cut },
            exhausted = snapshot.exhausted || cut,
            listenedMs = 0,
            historySent = false,
            capturedAtEpochMs = nowEpochMs,
        )
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
        // 最後に鳴った端末がつながっていなければ（wave-api を起動し直して覚えていないときも）、つながっている家の端末のどれか
        return lastWriter.takeIf(::usable) ?: players.sorted().firstOrNull(::usable)
    }
}
