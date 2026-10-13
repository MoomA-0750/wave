package xyz.mooma0750.wave.relay.v2

import xyz.mooma0750.wave.handoff.v2.ConnectClient
import xyz.mooma0750.wave.handoff.v2.LibraryBody
import xyz.mooma0750.wave.handoff.v2.LibraryFeed
import xyz.mooma0750.wave.handoff.v2.LibraryPosition
import xyz.mooma0750.wave.handoff.v2.MemberChain
import xyz.mooma0750.wave.handoff.v2.RelayV2
import xyz.mooma0750.wave.handoff.v2.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ライブラリの同期（ADR-0013）を、本物の家の状態と ConnectClient でつないで確かめる。 */
class LibrarySyncSimulationTest {
    private var now = 1_000_000L
    private lateinit var relay: FakeRelay
    private val a = SimDevice("A", { relay }, { now })
    private val b = SimDevice("B", { relay }, { now })
    private val pc = SimDevice("PC", { relay }, { now }, role = Role.Controller)

    private fun setUp() {
        relay = FakeRelay(listOf(a.publicKeyB64), nowMs = { now })
        val genesis = MemberChain.genesis(a, a.keys)
        relay.store.createHousehold(a.deviceId, genesis)
        a.client.beginCreateHousehold()
        val genesisInfo = ConnectClient.JoinInfo(MemberChain.hash(genesis), 0, MemberChain.hash(genesis), relay.store.storeId, 0, 0)
        listOf(b, pc).forEach { it.client.expectJoin(genesisInfo, a.deviceId) }
        var records = listOf(genesis)
        for (device in listOf(b, pc)) {
            val chain = (MemberChain.verify(records) as xyz.mooma0750.wave.handoff.v2.ChainVerification.Ok).chain
            val add = MemberChain.add(a, chain, device.keys)
            relay.store.append(a.deviceId, add)
            records = records + add
        }
        a.connect()
        b.connect()
        relay.run()
    }

    private fun feed(
        title: String,
        at: Long,
        removed: Boolean = false,
    ) = LibraryFeed(title, updatedAtMs = at, removed = removed)

    @Test
    fun subscriptionReachesTheOtherDeviceAndRemovalWins() {
        setUp()
        a.client.library.publish(LibraryBody(feeds = mapOf("https://x/f" to feed("X", 10))))
        relay.run()
        assertEquals("X", b.library?.feeds?.get("https://x/f")?.title)
        // B が外す（後の時刻）→ A でも外れる
        b.client.library.publish(LibraryBody(feeds = mapOf("https://x/f" to feed("X", 20, removed = true))))
        relay.run()
        assertTrue(a.library?.feeds?.get("https://x/f")?.removed == true)
        assertEquals(2L, relay.store.library()?.version)
    }

    @Test
    fun concurrentWritesAreMergedAfterConflict() {
        setUp()
        // 両方が同じ版の上に書こうとする（届く前に）
        a.client.library.publish(LibraryBody(feeds = mapOf("https://a/f" to feed("A", 10))))
        b.client.library.publish(LibraryBody(feeds = mapOf("https://b/f" to feed("B", 11)), positions = mapOf("https://b/1.mp3" to LibraryPosition(60, 600, false, 11))))
        relay.run()
        val stored = relay.store.library()
        assertEquals(2L, stored?.version, "食い違った方が合わせて書き直していない")
        assertEquals(setOf("https://a/f", "https://b/f"), a.library?.feeds?.keys)
        assertEquals(setOf("https://a/f", "https://b/f"), b.library?.feeds?.keys)
        assertEquals(60, a.library?.positions?.get("https://b/1.mp3")?.positionSeconds)
    }

    @Test
    fun aDeviceThatWasOfflineCatchesUpAndControllersCanWrite() {
        setUp()
        a.client.library.publish(LibraryBody(feeds = mapOf("https://x/f" to feed("X", 10))))
        relay.run()
        // PC（操作だけの端末）は後からつながる。持っていた登録は合わせて書く
        pc.client.library.publish(LibraryBody(feeds = mapOf("https://p/f" to feed("P", 5))))
        pc.connect()
        relay.run()
        assertEquals(setOf("https://x/f", "https://p/f"), pc.library?.feeds?.keys)
        assertEquals(setOf("https://x/f", "https://p/f"), a.library?.feeds?.keys)
    }

    @Test
    fun sameContentIsNotWrittenAgain() {
        setUp()
        val body = LibraryBody(feeds = mapOf("https://x/f" to feed("X", 10)))
        a.client.library.publish(body)
        relay.run()
        b.client.library.publish(body)
        a.client.library.publish(body)
        relay.run()
        assertEquals(1L, relay.store.library()?.version)
    }

    @Test
    fun aRelayThatLostTheLibraryIsWrittenFromTheStartWithoutLooping() {
        setUp()
        a.client.library.publish(LibraryBody(feeds = mapOf("https://x/f" to feed("X", 10))))
        relay.run()
        // 中継がライブラリだけを失う（同じ保存のまま）
        val saved = kotlinx.serialization.json.Json.parseToJsonElement(relay.file.readText()).let { it as kotlinx.serialization.json.JsonObject }
        relay.file.writeText(kotlinx.serialization.json.JsonObject(saved - "library").toString())
        relay.store = HouseholdStore(relay.file, listOf(a.publicKeyB64), { now })
        assertNull(relay.store.library())
        var puts = 0
        relay.dropIncoming = { _, message ->
            if (message is RelayV2.LibraryPut) puts++
            false
        }
        // 覚えた版（1）のまま書くと食い違いが返る。中継に無いと分かったら 0 から書き直す
        a.client.library.publish(LibraryBody(feeds = mapOf("https://x/f" to feed("X", 10), "https://y/f" to feed("Y", 11))))
        relay.run()
        assertEquals(1L, relay.store.library()?.version)
        assertEquals(2, puts)
        assertEquals(setOf("https://x/f", "https://y/f"), b.library?.feeds?.keys)
    }

    @Test
    fun oldClientsThatNeverAskGetNoLibraryMessages() {
        setUp()
        val seen = ArrayList<RelayV2>()
        val old = SimDevice("old", { relay }, { now })
        relay.connect("not-member") { seen += it }
        a.client.library.publish(LibraryBody(feeds = mapOf("https://x/f" to feed("X", 10))))
        relay.run()
        assertTrue(seen.none { it is RelayV2.Library })
        assertNull(old.library)
    }
}
