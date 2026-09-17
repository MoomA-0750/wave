package xyz.mooma0750.wave.core.queue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.music.SongPage
import xyz.mooma0750.wave.core.music.UpNextPage
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QueueSessionTest {
    private class FakePlayer : QueuePlayer {
        override val items = mutableListOf<QueueItem>()
        override var currentIndex = -1
        override var ended = false
        override var repeatAll = false
        var resumedAt: Int? = null

        override fun replace(
            items: List<QueueItem>,
            startIndex: Int,
        ) {
            this.items.clear()
            this.items += items
            currentIndex = startIndex
            ended = false
        }

        override fun append(items: List<QueueItem>) {
            this.items += items
        }

        override fun insertAfterCurrent(items: List<QueueItem>) {
            this.items.addAll(currentIndex + 1, items)
        }

        var restoredPositionMs: Long? = null
        var replaceCount = 0

        override fun restore(
            items: List<QueueItem>,
            startIndex: Int,
            positionMs: Long,
        ) {
            this.items.clear()
            this.items += items
            currentIndex = startIndex
            restoredPositionMs = positionMs
        }

        override fun replaceAfterCurrent(items: List<QueueItem>) {
            while (this.items.size > currentIndex + 1) this.items.removeAt(this.items.size - 1)
            this.items += items
        }

        override fun remove(itemId: String) {
            val index = items.indexOfFirst { it.itemId == itemId }
            if (index < 0) return
            items.removeAt(index)
            if (index < currentIndex) currentIndex--
        }

        override fun jumpTo(itemId: String) {
            currentIndex = items.indexOfFirst { it.itemId == itemId }
        }

        override fun resumeAt(index: Int) {
            resumedAt = index
            currentIndex = index
            ended = false
        }

        override fun clear() {
            items.clear()
            currentIndex = -1
        }

        fun videoIds() = items.map { it.song.videoId }
    }

    private class FakeContent : QueueContentSource {
        val upNextCalls = mutableListOf<Triple<String?, String?, String?>>()
        val playlistCalls = mutableListOf<Pair<String, String>>()
        var upNextResult: (String?, String?, String?) -> CallResult<UpNextPage> = { _, _, _ -> CallResult.Failed(FailureKind.Network) }
        var playlistResult: (String, String) -> CallResult<SongPage> = { _, _ -> CallResult.Failed(FailureKind.Network) }
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun upNext(
            videoId: String?,
            playlistId: String?,
            continuation: String?,
        ): CallResult<UpNextPage> {
            upNextCalls += Triple(videoId, playlistId, continuation)
            gate?.await()
            return upNextResult(videoId, playlistId, continuation)
        }

        override suspend fun playlistPage(
            playlistId: String,
            continuation: String,
        ): CallResult<SongPage> {
            playlistCalls += playlistId to continuation
            return playlistResult(playlistId, continuation)
        }
    }

    private fun song(id: String) = Song(id, "title-$id", emptyList(), null, 200, null)

    private fun page(
        vararg ids: String,
        continuation: String? = "next",
    ) = CallResult.Ok(UpNextPage(ids.map(::song), continuation, "RDAMVMseed"))

    private fun TestScope.session(
        player: FakePlayer,
        content: FakeContent,
    ): QueueSession {
        var counter = 0
        return QueueSession(player, content, this, newItemId = { "item-${counter++}" }, random = kotlin.random.Random(42))
    }

    @Test
    fun playingASongAddsRadioSuggestionsWithoutDuplicates() =
        runTest {
            val player = FakePlayer()
            val content = FakeContent().apply { upNextResult = { _, _, _ -> page("a", "b", "c", "a", "d", continuation = null) } }
            val queue = session(player, content)

            queue.playSong(song("a"))
            advanceUntilIdle()

            assertEquals(listOf("a", "b", "c", "d"), player.videoIds())
            assertEquals(Triple("a", "RDAMVMa", null), content.upNextCalls.first())
            assertTrue(player.items.drop(1).all { it.origin == ItemOrigin.Suggestion })
        }

    @Test
    fun fetchesContinuationOnlyWhenFewItemsRemain() =
        runTest {
            val player = FakePlayer()
            var call = 0
            val content =
                FakeContent().apply {
                    upNextResult = { _, _, token ->
                        call++
                        if (token == null) page("s", "1", "2", "3", "4", "5", continuation = "c1") else page("6", "7", continuation = "c2")
                    }
                }
            val queue = session(player, content)
            queue.playSong(song("s"))
            advanceUntilIdle()
            assertEquals(1, call, "残りが多いのに続きを取っている")

            player.currentIndex = 3 // 残り2曲
            queue.onPlaybackProgressed()
            advanceUntilIdle()
            assertEquals(2, call)
            assertEquals(listOf("s", "1", "2", "3", "4", "5", "6", "7"), player.videoIds())
            assertEquals("c1", content.upNextCalls[1].third)
        }

    @Test
    fun resultsForAnOldGenerationAreDiscarded() =
        runTest {
            val player = FakePlayer()
            val gate = CompletableDeferred<Unit>()
            val content =
                FakeContent().apply {
                    this.gate = gate
                    upNextResult = { seed, _, _ -> if (seed == "old") page("old-suggestion") else page("new-suggestion") }
                }
            val queue = session(player, content)

            queue.playSong(song("old"))
            advanceUntilIdle() // 取得は gate で止まっている
            queue.playSong(song("new"))
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("new", "new-suggestion"), player.videoIds(), "前の曲のサジェストが混ざった")
        }

    @Test
    fun newSelectionStartsItsOwnFetchEvenWhileTheOldOneIsRunning() =
        runTest {
            val player = FakePlayer()
            val content = FakeContent().apply { gate = CompletableDeferred() }
            val queue = session(player, content)
            queue.playSong(song("old"))
            advanceUntilIdle()
            queue.playSong(song("new"))
            advanceUntilIdle()
            assertEquals(listOf("old", "new"), content.upNextCalls.map { it.first })
            content.gate?.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun stopsFetchingWhenNothingNewOrExhausted() =
        runTest {
            val player = FakePlayer()
            val content = FakeContent().apply { upNextResult = { _, _, _ -> page("a", continuation = "again") } }
            val queue = session(player, content)
            queue.playSong(song("a"))
            advanceUntilIdle()
            repeat(3) {
                queue.onPlaybackProgressed()
                advanceUntilIdle()
            }
            assertEquals(1, content.upNextCalls.size, "新規0件なのに取り続けている")
            assertTrue(queue.snapshot().exhausted)
        }

    @Test
    fun failuresDoNotLoopImmediately() =
        runTest {
            val player = FakePlayer()
            val content = FakeContent()
            val queue = session(player, content)
            queue.playSong(song("a"))
            advanceUntilIdle()
            assertEquals(1, content.upNextCalls.size)
            // 失敗は尽きた扱いにしない。次の曲の遷移で取り直す
            queue.onPlaybackProgressed()
            advanceUntilIdle()
            assertEquals(2, content.upNextCalls.size)
        }

    @Test
    fun appendingAfterPlaybackEndedResumesAtTheNewItems() =
        runTest {
            val player = FakePlayer()
            val gate = CompletableDeferred<Unit>()
            val content = FakeContent().apply {
                this.gate = gate
                upNextResult = { _, _, _ -> page("x", "y", continuation = null) }
            }
            val queue = session(player, content)
            queue.playSong(song("a"))
            advanceUntilIdle()
            player.ended = true
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, player.resumedAt)
        }

    @Test
    fun playlistContinuesWithItsPagesThenRadio() =
        runTest {
            val player = FakePlayer()
            val content =
                FakeContent().apply {
                    playlistResult = { _, _ -> CallResult.Ok(SongPage(listOf(song("p3"), song("p1")), continuation = null)) }
                    upNextResult = { _, _, _ -> page("r1", "p1", continuation = null) }
                }
            val queue = session(player, content)
            queue.playFrom(listOf(song("p1"), song("p2")), startIndex = 1, playlistId = "PL1", playlistContinuation = "page2")
            advanceUntilIdle()

            // プレイリストの続きは重複（p1）でも入れる。最後まで来たら最後の曲からラジオへ。ラジオの重複（p1）は除く
            assertEquals(listOf("p1", "p2", "p3", "p1", "r1"), player.videoIds())
            assertEquals(listOf("PL1" to "page2"), content.playlistCalls)
            assertEquals("p1", content.upNextCalls.single().first)
            assertEquals("RDAMVMp1", content.upNextCalls.single().second)
        }

    @Test
    fun emptyQueueAtTheEndOfAPlaylistDoesNotSpin() =
        runTest {
            val player = FakePlayer()
            val content = FakeContent().apply { playlistResult = { _, _ -> CallResult.Ok(SongPage(emptyList(), continuation = null)) } }
            val queue = session(player, content)
            queue.playFrom(listOf(song("a")), startIndex = 0, playlistId = "PL", playlistContinuation = "c1")
            queue.remove(player.items.single().itemId)
            advanceUntilIdle() // 空回りするとここで終わらない
            assertTrue(queue.snapshot().exhausted)
            assertEquals(emptyList(), content.upNextCalls)
        }

    @Test
    fun userAddedItemsAreAlwaysAllowed() =
        runTest {
            val player = FakePlayer()
            val content = FakeContent()
            val queue = session(player, content)
            queue.playSong(song("a"))
            queue.addNext(song("a"))
            queue.addToEnd(song("a"))
            assertEquals(listOf("a", "a", "a"), player.videoIds())
            assertEquals(3, player.items.map { it.itemId }.toSet().size)
        }

    @Test
    fun resetClearsTheQueueAndDropsInFlightResults() =
        runTest {
            val player = FakePlayer()
            val gate = CompletableDeferred<Unit>()
            val content = FakeContent().apply {
                this.gate = gate
                upNextResult = { _, _, _ -> page("late") }
            }
            val queue = session(player, content)
            queue.playSong(song("a"))
            advanceUntilIdle()
            queue.reset()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(emptyList(), player.videoIds())
        }

    @Test
    fun shuffleReordersOnlyTheItemsAfterTheCurrentOne() =
        runTest {
            val player = FakePlayer()
            val queue = session(player, FakeContent())
            val ids = (0 until 10).map { "s$it" }
            queue.playFrom(ids.map(::song), startIndex = 2, playlistId = "PL", playlistContinuation = null)
            val before = player.items.toList()
            val generation = queue.generation

            queue.shuffleUpcoming()

            assertEquals(before.take(3), player.items.take(3), "今の曲とそれより前が動いた")
            assertEquals(before.drop(3).toSet(), player.items.drop(3).toSet(), "項目が増減した（itemId ごと保つ）")
            assertNotEquals(before.drop(3), player.items.drop(3), "並べ替わっていない")
            assertEquals(generation, queue.generation, "シャッフルで世代が進んだ")
        }

    @Test
    fun shuffleKeepsAnInFlightFetch() =
        runTest {
            val player = FakePlayer()
            val gate = CompletableDeferred<Unit>()
            val content =
                FakeContent().apply {
                    this.gate = gate
                    upNextResult = { _, _, _ -> page("r1", "r2", "r3", continuation = null) }
                }
            val queue = session(player, content)
            queue.playSong(song("a"))
            advanceUntilIdle() // 取得は gate で止まっている
            queue.addToEnd(song("u1"))
            queue.addToEnd(song("u2"))
            queue.addToEnd(song("u3"))
            queue.shuffleUpcoming()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("r1", "r2", "r3"), player.videoIds().takeLast(3), "取得中のサジェストが捨てられた")
        }

    @Test
    fun shufflePlayStartsAtTheTopWithAPermutation() =
        runTest {
            val player = FakePlayer()
            val queue = session(player, FakeContent())
            val ids = (0 until 10).map { "s$it" }
            queue.playFrom(ids.map(::song), startIndex = 5, playlistId = "PL", playlistContinuation = "c", shuffle = true)
            assertEquals(0, player.currentIndex)
            assertEquals(ids.toSet(), player.videoIds().toSet())
            assertNotEquals(ids, player.videoIds())
            assertEquals(QueueSource.Playlist("PL"), queue.snapshot().source)
        }

    @Test
    fun repeatAllStillFetchesTheFirstRadioPageButNotItsContinuation() =
        runTest {
            val player = FakePlayer().apply { repeatAll = true }
            val content =
                FakeContent().apply {
                    upNextResult = { _, _, token -> if (token == null) page("r1", continuation = "c1") else page("r2", continuation = null) }
                }
            val queue = session(player, content)
            queue.playSong(song("a"))
            advanceUntilIdle()
            // 1曲を延々と繰り返さないよう、最初のページは取る
            assertEquals(listOf("a", "r1"), player.videoIds())

            player.currentIndex = 1
            queue.onPlaybackProgressed()
            advanceUntilIdle()
            assertEquals(1, content.upNextCalls.size, "全体リピート中にサジェストの続きを取った")

            player.repeatAll = false
            queue.onRepeatModeChanged()
            advanceUntilIdle()
            assertEquals(listOf("a", "r1", "r2"), player.videoIds(), "リピートを外しても続きを取らない")
        }

    @Test
    fun repeatAllFetchesPlaylistPagesButDoesNotSwitchToRadio() =
        runTest {
            val player = FakePlayer().apply { repeatAll = true }
            val content =
                FakeContent().apply {
                    playlistResult = { _, _ -> CallResult.Ok(SongPage(listOf(song("p2")), continuation = null)) }
                    upNextResult = { _, _, _ -> page("r1", continuation = null) }
                }
            val queue = session(player, content)
            queue.playFrom(listOf(song("p1")), startIndex = 0, playlistId = "PL", playlistContinuation = "page2")
            advanceUntilIdle()
            assertEquals(listOf("p1", "p2"), player.videoIds(), "全体リピート中でもプレイリストの続きは取る")
            assertEquals(emptyList(), content.upNextCalls, "全体リピート中にラジオへ切り替えた")
            assertEquals(QueueSource.Playlist("PL"), queue.snapshot().source)

            player.repeatAll = false
            queue.onRepeatModeChanged()
            advanceUntilIdle()
            assertEquals(listOf("p1", "p2", "r1"), player.videoIds())
        }

    @Test
    fun savedQueueRoundTripsThroughTheSession() =
        runTest {
            val player = FakePlayer()
            val content = FakeContent().apply { upNextResult = { _, _, _ -> page("r1", "r2", continuation = "c9") } }
            val queue = session(player, content)
            queue.playSong(song("a"))
            advanceUntilIdle()
            player.currentIndex = 1
            val saved = requireNotNull(queue.saved(obtainedAtEpochMs = 7, positionMs = 12_345, repeatMode = 2))

            val decoded = requireNotNull(SavedQueue.decode(SavedQueue.encode(saved)))
            assertEquals(saved, decoded)

            val restoredPlayer = FakePlayer()
            val restoredContent = FakeContent()
            val restored = session(restoredPlayer, restoredContent)
            restored.restore(decoded)
            advanceUntilIdle()
            assertEquals(player.items, restoredPlayer.items)
            assertEquals(1, restoredPlayer.currentIndex)
            assertEquals(12_345L, restoredPlayer.restoredPositionMs)
            assertEquals(QueueSource.Radio("a"), restored.snapshot().source)
            assertEquals("c9", restored.snapshot().continuation)
            assertEquals(emptyList(), restoredContent.upNextCalls, "残りが少なくないのに取得した")
        }

    @Test
    fun emptyQueueIsNotSaved() =
        runTest {
            val queue = session(FakePlayer(), FakeContent())
            assertEquals(null, queue.saved(1, 0, 0))
        }

    @Test
    fun brokenOrForeignSavedQueuesAreRejected() {
        val item = QueueItem("i", song("a"), ItemOrigin.Source)
        val valid = SavedQueue(SavedQueue.VERSION, 1, listOf(item), 0, 0, 0, QueueSource.Radio("a"), null, null, false)
        assertEquals(valid, SavedQueue.decode(SavedQueue.encode(valid)))
        assertEquals(null, SavedQueue.decode("{"))
        assertEquals(null, SavedQueue.decode(""))
        assertEquals(null, SavedQueue.decode(SavedQueue.encode(valid.copy(version = SavedQueue.VERSION + 1))))
        assertEquals(null, SavedQueue.decode(SavedQueue.encode(valid.copy(items = emptyList()))))
        assertEquals(null, SavedQueue.decode(SavedQueue.encode(valid.copy(currentIndex = 1))))
        assertEquals(null, SavedQueue.decode(SavedQueue.encode(valid.copy(repeatMode = 3))))
        assertEquals(null, SavedQueue.decode(SavedQueue.encode(valid).replace("\"exhausted\"", "\"unknownKey\":1,\"exhausted\"")))
    }

    @Test
    fun staleRestoredContinuationRestartsRadioFromTheLastItem() =
        runTest {
            val player = FakePlayer()
            val content =
                FakeContent().apply {
                    upNextResult = { seed, _, token -> if (token != null) CallResult.Failed(FailureKind.Http, 400) else page("fresh-$seed", continuation = null) }
                }
            val queue = session(player, content)
            val items = listOf("a", "b").mapIndexed { i, id -> QueueItem("i$i", song(id), ItemOrigin.Source) }
            queue.restore(SavedQueue(SavedQueue.VERSION, 1, items, 1, 0, 0, QueueSource.Radio("a"), "old-token", "RDAMVMa", false))
            queue.onPlaybackProgressed()
            advanceUntilIdle()
            assertEquals(listOf("a", "b", "fresh-b"), player.videoIds())
            assertEquals("old-token", content.upNextCalls[0].third)
            assertEquals(Triple("b", "RDAMVMb", null), content.upNextCalls[1])
        }

    @Test
    fun networkFailureWithARestoredContinuationDoesNotRestart() =
        runTest {
            val player = FakePlayer()
            val content = FakeContent().apply { upNextResult = { _, _, _ -> CallResult.Failed(FailureKind.Network) } }
            val queue = session(player, content)
            val items = listOf(QueueItem("i0", song("a"), ItemOrigin.Source))
            queue.restore(SavedQueue(SavedQueue.VERSION, 1, items, 0, 0, 0, QueueSource.Radio("a"), "token", "RDAMVMa", false))
            queue.onPlaybackProgressed()
            advanceUntilIdle()
            assertEquals(1, content.upNextCalls.size)
            assertEquals("token", queue.snapshot().continuation)
        }
}
