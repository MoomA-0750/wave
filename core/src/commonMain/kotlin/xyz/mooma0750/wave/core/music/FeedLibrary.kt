package xyz.mooma0750.wave.core.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.handoff.v2.LibraryBody
import xyz.mooma0750.wave.handoff.v2.LibraryFeed
import xyz.mooma0750.wave.handoff.v2.LibraryPosition

/** RSS で登録した番組と、エピソードの聴いたところ（端末ごと、ADR-0012）。 */
@Serializable
data class FeedLibraryState(
    /** 登録した順（新しいものが先）。 */
    val subscriptions: List<FeedSubscription> = emptyList(),
    /** 音声の URL ごとの聴いたところ。 */
    val positions: Map<String, EpisodePosition> = emptyMap(),
    /** 外した番組と、外した時刻（ほかの端末へ「外した」を伝える印、ADR-0013）。 */
    val removed: Map<String, Long> = emptyMap(),
) {
    fun isSubscribed(feedUrl: String): Boolean = subscriptions.any { it.feedUrl == feedUrl }

    /** 登録する（[FeedSubscription.addedAtMs] が登録した時刻）。 */
    fun subscribe(subscription: FeedSubscription): FeedLibraryState =
        copy(subscriptions = listOf(subscription) + subscriptions.filterNot { it.feedUrl == subscription.feedUrl }, removed = removed - subscription.feedUrl)

    fun unsubscribe(
        feedUrl: String,
        atMs: Long = 0,
    ): FeedLibraryState = copy(subscriptions = subscriptions.filterNot { it.feedUrl == feedUrl }, removed = removed + (feedUrl to atMs))

    /** 端末の間で合わせる形（ADR-0013）。 */
    fun toBody(): LibraryBody =
        LibraryBody(
            feeds =
                removed.mapValues { (_, at) -> LibraryFeed(title = "", removed = true, updatedAtMs = at) } +
                    subscriptions.associate { it.feedUrl to LibraryFeed(it.title, it.author, it.thumbnailUrl, it.addedAtMs, removed = false, updatedAtMs = it.addedAtMs) },
            positions = positions.mapValues { (_, p) -> LibraryPosition(p.positionSeconds, p.durationSeconds, p.finished, p.updatedAtMs) },
        )

    /** ほかの端末と合わせた結果を取り込む。番組・エピソードごとに、手元より新しいものだけを当てる。 */
    fun apply(body: LibraryBody): FeedLibraryState {
        var next = this
        for ((url, feed) in body.feeds) {
            if (!Feed.isAllowedFeedUrl(url)) continue
            val localAt = next.subscriptions.firstOrNull { it.feedUrl == url }?.addedAtMs ?: next.removed[url]
            if (localAt != null && localAt >= feed.updatedAtMs) continue
            next =
                if (feed.removed) {
                    next.unsubscribe(url, feed.updatedAtMs)
                } else {
                    next.subscribe(FeedSubscription(url, feed.title, feed.author, feed.thumbnailUrl?.takeIf(Feed::isAllowedFeedUrl), feed.updatedAtMs))
                }
        }
        // 登録の並びは登録した時刻の新しい順
        next = next.copy(subscriptions = next.subscriptions.sortedByDescending { it.addedAtMs })
        var positions = next.positions
        for ((url, p) in body.positions) {
            val local = positions[url]
            if (local != null && local.updatedAtMs >= p.updatedAtMs) continue
            positions = positions + (url to EpisodePosition(p.positionSeconds, p.durationSeconds, p.finished, p.updatedAtMs))
        }
        return next.copy(positions = positions).trimmed()
    }

    private fun trimmed(): FeedLibraryState =
        if (positions.size <= MAX_POSITIONS) this else copy(positions = positions.entries.sortedByDescending { it.value.updatedAtMs }.take(MAX_POSITIONS).associate { it.key to it.value })

    /** 番組の名前・画像が変わっていたら、登録の表示を合わせる（登録の順は変えない）。 */
    fun refresh(subscription: FeedSubscription): FeedLibraryState =
        copy(subscriptions = subscriptions.map { if (it.feedUrl == subscription.feedUrl && it.copy(addedAtMs = 0) != subscription.copy(addedAtMs = 0)) subscription.copy(addedAtMs = it.addedAtMs) else it })

    /** 位置を覚える。多くなりすぎたら古いものから捨てる。 */
    fun remember(
        audioUrl: String,
        position: EpisodePosition,
    ): FeedLibraryState {
        val next = positions + (audioUrl to position)
        return copy(positions = if (next.size <= MAX_POSITIONS) next else next.entries.sortedByDescending { it.value.updatedAtMs }.take(MAX_POSITIONS).associate { it.key to it.value })
    }

    companion object {
        const val MAX_POSITIONS = 1000
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = false
            }

        fun decode(text: String): FeedLibraryState? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        fun encode(state: FeedLibraryState): String = json.encodeToString(serializer(), state)
    }
}

/**
 * [FeedLibraryState] を持ち、変わるたびに [save] で書く。
 * 書き込みの順は呼ぶ側の1本の流れ（コルーチンの1つのスコープなど）に任せる。
 */
class FeedLibrary(
    initial: FeedLibraryState,
    private val nowMs: () -> Long,
    private val save: suspend (FeedLibraryState) -> Unit,
) {
    private val mutable = MutableStateFlow(initial)
    val state: StateFlow<FeedLibraryState> = mutable.asStateFlow()

    suspend fun subscribe(show: FeedSubscription) = change { it.subscribe(show.copy(addedAtMs = nowMs())) }

    suspend fun unsubscribe(feedUrl: String) = change { it.unsubscribe(feedUrl, nowMs()) }

    /** ほかの端末と合わせた結果を取り込む（ADR-0013）。 */
    suspend fun apply(body: LibraryBody) = change { it.apply(body) }

    suspend fun refresh(show: FeedShow) {
        if (state.value.isSubscribed(show.feedUrl)) change { it.refresh(show.subscription) }
    }

    fun position(audioUrl: String): EpisodePosition? = state.value.positions[audioUrl]

    /** 聴いたところを覚える（[durationSeconds] が分からなければ null）。 */
    suspend fun remember(
        audioUrl: String,
        positionSeconds: Int,
        durationSeconds: Int?,
    ) = change { it.remember(audioUrl, EpisodePosition.of(positionSeconds, durationSeconds, nowMs())) }

    private suspend fun change(block: (FeedLibraryState) -> FeedLibraryState) {
        val before = mutable.value
        mutable.update(block)
        val after = mutable.value
        if (after != before) save(after)
    }
}
