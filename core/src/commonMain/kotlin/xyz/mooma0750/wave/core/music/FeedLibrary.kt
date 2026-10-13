package xyz.mooma0750.wave.core.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** RSS で登録した番組と、エピソードの聴いたところ（端末ごと、ADR-0012）。 */
@Serializable
data class FeedLibraryState(
    /** 登録した順（新しいものが先）。 */
    val subscriptions: List<FeedSubscription> = emptyList(),
    /** 音声の URL ごとの聴いたところ。 */
    val positions: Map<String, EpisodePosition> = emptyMap(),
) {
    fun isSubscribed(feedUrl: String): Boolean = subscriptions.any { it.feedUrl == feedUrl }

    fun subscribe(subscription: FeedSubscription): FeedLibraryState =
        copy(subscriptions = listOf(subscription) + subscriptions.filterNot { it.feedUrl == subscription.feedUrl })

    fun unsubscribe(feedUrl: String): FeedLibraryState = copy(subscriptions = subscriptions.filterNot { it.feedUrl == feedUrl })

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

    suspend fun unsubscribe(feedUrl: String) = change { it.unsubscribe(feedUrl) }

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
