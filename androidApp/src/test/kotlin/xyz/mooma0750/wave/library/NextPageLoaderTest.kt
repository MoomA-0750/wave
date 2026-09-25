package xyz.mooma0750.wave.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextPageLoaderTest {
    @Test
    fun twoCallersNeverReadTheSameContinuation() =
        runBlocking {
            // 続きは t1 → t2 → 終わり。1回目の読み込みが返る前に2人目が来ても、2人目は t2 を読む
            val next = mapOf("t1" to "t2", "t2" to null)
            var token: String? = "t1"
            val fetched = mutableListOf<String>()
            val gate = CompletableDeferred<Unit>()
            val loader = NextPageLoader()
            val first =
                async {
                    loader.next({ token }, { t ->
                        fetched += t
                        gate.await()
                        next.getValue(t)
                    }) { token = it }
                }
            yield()
            val second = async { loader.next({ token }, { t -> fetched += t; next.getValue(t) }) { token = it } }
            yield()
            gate.complete(Unit)
            first.await()
            second.await()
            assertEquals(listOf("t1", "t2"), fetched)
            assertNull(token)
        }

    @Test
    fun nothingIsReadWhenThereIsNoContinuation() =
        runBlocking {
            var called = false
            assertNull(NextPageLoader().next({ null }, { called = true }) {})
            assertEquals(false, called)
        }
}
