package xyz.mooma0750.wave.handoff.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** LibrarySync を、版の CAS だけをする中継（library のところだけ）とつないで確かめる。 */
class LibrarySyncTest {
    private class Relay(
        var stored: LibraryState?,
    ) {
        val inbox = ArrayDeque<RelayV2>()
        var puts = 0

        fun handle(message: RelayV2) {
            when (message) {
                is RelayV2.LibraryGet -> inbox += RelayV2.Library(stored)
                is RelayV2.LibraryPut -> {
                    puts++
                    val current = stored?.version ?: 0
                    if (current != message.expectVersion) {
                        inbox += RelayV2.LibraryConflict(message.requestId, stored)
                        return
                    }
                    stored = LibraryState(current + 1, message.doc, STORE)
                    inbox += RelayV2.LibraryOk(message.requestId, current + 1)
                }
                else -> Unit
            }
        }
    }

    private fun feed(
        title: String,
        at: Long,
    ) = LibraryFeed(title, updatedAtMs = at)

    private fun body(vararg feeds: Pair<String, LibraryFeed>) = LibraryBody(feeds = mapOf(*feeds))

    private fun plaintext(body: LibraryBody) = ConnectJson.json.encodeToString(LibraryBody.serializer(), body).encodeToByteArray()

    /** device が chain で中継につながり、届いたものが無くなるまで回す。 */
    private fun connect(
        device: TestDevice,
        chain: VerifiedChain,
        relay: Relay,
    ): LibrarySync {
        val outbox = ArrayDeque<RelayV2>()
        var ids = 0
        val sync =
            LibrarySync(device, device, { outbox += it; true }, { chain }, { STORE }, { 1 }, { "req-${++ids}" }) {}
        sync.onConnected()
        pump(sync, outbox, relay)
        drains += { pump(sync, outbox, relay) }
        return sync
    }

    private val drains = ArrayList<() -> Unit>()

    private fun run() = drains.forEach { it() }

    private fun pump(
        sync: LibrarySync,
        outbox: ArrayDeque<RelayV2>,
        relay: Relay,
    ) {
        while (outbox.isNotEmpty() || relay.inbox.isNotEmpty()) {
            while (outbox.isNotEmpty()) relay.handle(outbox.removeFirst())
            relay.inbox.removeFirstOrNull()?.let(sync::onMessage)
        }
    }

    @Test
    fun anUnreadableRemoteDocumentIsNeverOverwritten() {
        // 中継の最新の文書を、この端末が開けない（別の一覧で書かれた・宛先にいない）
        val other = TestDevice()
        val otherChain = listOf(MemberChain.genesis(other, other.keys)).verified()
        val doc = DocSealer.seal(other, otherChain, DocKind.Library, 5, null, 1, plaintext(body("https://feeds.example/x" to feed("X", 5))))
        val relay = Relay(LibraryState(5, doc, STORE))
        val a = TestDevice()
        val sync = connect(a, listOf(MemberChain.genesis(a, a.keys)).verified(), relay)
        sync.publish(body("https://feeds.example/a" to feed("A", 10)))
        run()
        // 書かない（書けば開けなかった登録を消し、食い違いが返り続ける）
        assertEquals(0, relay.puts)
        assertNotNull(sync.unreadable)
        assertEquals(5L, relay.stored?.version)
    }

    @Test
    fun aDocumentMissingAMemberIsRewrappedEvenWithTheSameContent() {
        val a = TestDevice()
        val pc = TestDevice(Role.Controller)
        val genesis = MemberChain.genesis(a, a.keys)
        val chain = listOf(genesis, MemberChain.add(a, listOf(genesis).verified(), pc.keys)).verified()
        // PC が加わる前に書かれた（宛先が A だけ）
        val same = body("https://feeds.example/x" to feed("X", 5))
        val doc = DocSealer.seal(a, chain, DocKind.Library, 3, null, 1, plaintext(same), only = setOf(a.deviceId))
        val relay = Relay(LibraryState(3, doc, STORE))
        val sync = connect(a, chain, relay)
        assertNull(sync.unreadable)
        // 中身が同じでも、全員に包み直して1回だけ書く
        assertEquals(1, relay.puts)
        assertEquals(4L, relay.stored?.version)
        assertEquals(setOf(a.deviceId, pc.deviceId), relay.stored?.doc?.recipients?.map { it.deviceId }?.toSet())
        sync.publish(same)
        run()
        assertEquals(1, relay.puts)
    }

    private companion object {
        const val STORE = "store-1"
    }
}
