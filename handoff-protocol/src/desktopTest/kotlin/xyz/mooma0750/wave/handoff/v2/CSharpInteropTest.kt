package xyz.mooma0750.wave.handoff.v2

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.mooma0750.wave.handoff.HandoffCodec
import xyz.mooma0750.wave.handoff.HandoffDecodeResult
import xyz.mooma0750.wave.handoff.HandoffItem
import xyz.mooma0750.wave.handoff.HandoffOrigin
import xyz.mooma0750.wave.handoff.HandoffRef
import xyz.mooma0750.wave.handoff.HandoffRepeat
import xyz.mooma0750.wave.handoff.HandoffSnapshot
import xyz.mooma0750.wave.handoff.HandoffSource
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.Hkdf
import xyz.mooma0750.wave.handoff.crypto.Hpke
import xyz.mooma0750.wave.handoff.crypto.Pairing
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Windows 版（C#、windows/Wave.Core/Connect）との相互運用の成果物（Phase 7）。
 * - 環境変数 WAVE_WRITE_CONNECT_FIXTURES=1 のとき、固定のテスト鍵で作った鎖・SealedDoc・Pairing の Hello/Confirm・HandoffCodec の文字列・中継の JSON を
 *   windows/Wave.Core.Tests/Fixtures/connect/kotlin.json に書き出す（C# のテストが検証・開く）
 * - C# が書き出した csharp.json があれば、Kotlin で検証・開く
 * 鍵はテスト専用（ここに書いた値は秘密ではない）。
 */
class CSharpInteropTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()
    private val fixtureDir = File("../windows/Wave.Core.Tests/Fixtures/connect")

    /** 固定のテスト鍵（PKCS#8、OpenSSL で作ったもの。末尾 65 バイトが公開鍵の非圧縮点）。 */
    private val keys =
        mapOf(
            "a" to
                (
                    "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgnRY4vVClbG2f1MzC_0d4x5_DtJBWwIEKp0hgPpyyZWChRANCAAQY7Q2jOGfd9Jb44Tcr8MNhck0KH-aqxtvBxmIqTIux32NUuFUKCMWazdK6VN7iyUYfewf1Zx7vydb2v7wRG0uT" to
                        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg-eGyhlXZ4mMnV1N7VZJ02qyJFvUBDY7QwNNqg2pdL1KhRANCAATmlruHbXuSXGVQhGOVuMkBEXp_rcuqoPHOxIH8e1p5Hpo9uyvbNA7Yg6Nhmh30Fz_6YDR9VCHAlEXZGJi5uqPZ"
                ),
            "b" to
                (
                    "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg6CtM5MxNpGqUx1NE_Or_7dD5dZZ4WpIgnts9BGYqcRChRANCAAREV2etb2GojOyjx2mduvYjTtZDZVXxUQjnO2NgtfwP8WUNftWqZptJNJhU18LB7ldoDPO2Cm06p0bTgFOoBsgr" to
                        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgHXfIzQ1h1FcVEr2VUMMCcroQZH6X6GCRi1xc8fONFDyhRANCAAQsYPLQIEaiwOI7sCrzExnGYMGGJJgraaeB6_eja7p1ivp52myW3CQBXquyVZT3RJnlwO2X1ZsOsBoL2SFGvLUQ"
                ),
            "pc" to
                (
                    "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQguqQZ0eJM5m0q0EN6-WHxGnwh4ECo-uzqMkhK-Xfok_ShRANCAATmrKY7vfec0OoMrdjfUnlRoy8o9Z0EPwOYt5OavLKDFWJdNTDJY595Q4u_k9iAqKWTbGoaDZpLXVQOd3ZCnBP4" to
                        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg3dnVOD8hlXCn0P9J2QWV9OBY69pNVXvrfFrQZOGkfmyhRANCAAT17_giJL13BntEIz9Gg1zqYxx1R5EcAOJLHSeOSxtESFU7TnEmkgTsuwibvOPcPPF1TSLEqD9wNcOaCcyx2V-D"
                ),
        )

    private class FixedDevice(
        identityPkcs8: ByteArray,
        kxPkcs8: ByteArray,
        val role: Role,
    ) : DeviceSigner,
        KxOpener {
        val identityPrivate: PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(identityPkcs8))
        val identityPublic: ECPublicKey = Hpke.deserializePublicKey(identityPkcs8.copyOfRange(identityPkcs8.size - 65, identityPkcs8.size))!!
        private val kxPrivate: PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(kxPkcs8))
        override val kxPub: ECPublicKey = Hpke.deserializePublicKey(kxPkcs8.copyOfRange(kxPkcs8.size - 65, kxPkcs8.size))!!
        override val identityPub: ByteArray = WaveCrypto.encodePublicKey(identityPublic)
        override val deviceId: String = EnvelopeSealer.deviceId(identityPub)

        override fun sign(
            label: String,
            message: ByteArray,
        ): ByteArray = WaveCrypto.sign(identityPrivate, label, message)

        override fun dh(peer: ECPublicKey): ByteArray = WaveCrypto.ecdh(kxPrivate, peer)

        val memberKeys: MemberKeys by lazy { MemberChain.memberKeys(this, kxPub, role) }
    }

    private fun device(name: String) = keys.getValue(name).let { (id, kx) -> FixedDevice(unb64.decode(id), unb64.decode(kx), if (name == "pc") Role.Controller else Role.Player) }

    private val a = device("a")
    private val b = device("b")
    private val pc = device("pc")

    private val handoffId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private fun sampleSnapshot() =
        HandoffSnapshot(
            schemaVersion = HandoffCodec.SCHEMA_VERSION,
            handoffId = handoffId,
            items =
                listOf(
                    HandoffItem("00000000-0000-4000-8000-000000000001", "abcdefghijA", "夜に駆ける \"quoted\" 🎵", listOf(HandoffRef("YOASOBI", "UCsynthetic000000000000000")), HandoffRef("THE BOOK", null), 261, "https://lh3.googleusercontent.com/x=w120-h120", HandoffOrigin.Source),
                    HandoffItem("00000000-0000-4000-8000-000000000002", "abcdefghijB", "second", emptyList(), null, null, null, HandoffOrigin.UserAdded),
                ),
            currentItemId = "00000000-0000-4000-8000-000000000001",
            positionMs = 42_000,
            playing = true,
            repeat = HandoffRepeat.All,
            source = HandoffSource.Radio("abcdefghijA"),
            continuation = null,
            radioPlaylistId = "RDsynthetic",
            exhausted = false,
            listenedMs = 30_000,
            historySent = true,
            capturedAtEpochMs = 1_700_000_000_000,
        )

    /** C# の InteropTests.SampleLibrary と同じもの。 */
    private fun sampleLibrary() =
        LibraryBody(
            feeds =
                mapOf(
                    "https://feeds.example/show" to LibraryFeed("番組 \"A\" 🎙", "作者", "https://img.example/a.jpg", 1_700_000_000_000, false, 1_700_000_000_000),
                    "https://feeds.example/old" to LibraryFeed("", removed = true, updatedAtMs = 1_700_000_000_010),
                ),
            positions = mapOf("https://feeds.example/1.mp3" to LibraryPosition(60, 600, false, 1_700_000_000_020), "https://feeds.example/2.mp3" to LibraryPosition(5, null, true, 1_700_000_000_030)),
        )

    /** C# の InteropTests.SampleLoginGrant と同じもの（値は合成）。 */
    private fun sampleLoginGrant(
        from: String,
        to: String,
    ) = CommandBody.LoginGrant("grant-0000000000000001", from, to, "SAPISID=synthetic-sapisid; HSID=synthetic", "synthetic-visitor", "synthetic-sync||x", 1_700_000_000_000)

    private fun accountTag(
        householdId: String,
        accountId: String,
    ): String = b64.encodeToString(WaveCrypto.sha256(WaveCrypto.lengthPrefixed("wave-v2-account-tag".encodeToByteArray(), householdId.encodeToByteArray(), accountId.encodeToByteArray())))

    private fun relayElement(message: RelayV2): JsonElement = JsonPrimitive(RelayV2.encode(message))

    private fun <T> field(
        target: Any,
        name: String,
    ): T {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(target) as T
    }

    private fun setField(
        target: Any,
        name: String,
        value: Any?,
    ) {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }

    @Test
    fun writeKotlinFixtures() {
        if (System.getenv("WAVE_WRITE_CONNECT_FIXTURES") != "1") return
        val genesis = MemberChain.genesis(a, a.memberKeys)
        val addB = MemberChain.add(a, listOf(genesis).verified(), b.memberKeys)
        val addPc = MemberChain.add(b, listOf(genesis, addB).verified(), pc.memberKeys)
        val records = listOf(genesis, addB, addPc)
        val chain = records.verified()
        val tag = accountTag(chain.householdId, "account-1")

        val snapshotText = HandoffCodec.encode(sampleSnapshot())
        val queueBody = QueueBody(tag, snapshotText)
        val queueDoc = DocSealer.seal(a, chain, DocKind.Queue, 1, null, 1_700_000_000_001, ConnectJson.json.encodeToString(QueueBody.serializer(), queueBody).encodeToByteArray())
        val cursorBody = CursorBody(DocSealer.hash(queueDoc), "00000000-0000-4000-8000-000000000001", 42_000, true, HandoffRepeat.One, 3, null, 7, 30_000, false, tag, 1_700_000_000_002)
        val cursorDoc = DocSealer.seal(a, chain, DocKind.Cursor, 4, "prev-hash", 1_700_000_000_003, ConnectJson.json.encodeToString(CursorBody.serializer(), cursorBody).encodeToByteArray())
        val command: CommandBody = CommandBody.Yield("claim-0000000000000001", b.deviceId, a.deviceId, 4, tag)
        val commandDoc = DocSealer.seal(b, chain, DocKind.Command, 1, null, 1_700_000_000_004, ConnectJson.json.encodeToString(CommandBody.serializer(), command).encodeToByteArray())
        val profileDoc = DocSealer.seal(pc, chain, DocKind.Profile, 1, null, 1_700_000_000_005, ConnectJson.json.encodeToString(ProfileBody.serializer(), ProfileBody("Windows PC")).encodeToByteArray())
        // ライブラリ（ADR-0013）は操作だけの端末（PC）も書ける
        val libraryBody = sampleLibrary()
        val libraryDoc = DocSealer.seal(pc, chain, DocKind.Library, 1, null, 1_700_000_000_006, ConnectJson.json.encodeToString(LibraryBody.serializer(), libraryBody).encodeToByteArray())
        // ログインを渡す命令（ADR-0015）: 宛先（B）の鍵にだけ包む。値は合成
        val grant: CommandBody = sampleLoginGrant(a.deviceId, b.deviceId)
        val grantText = ConnectJson.json.encodeToString(CommandBody.serializer(), grant)
        val grantDoc = DocSealer.seal(a, chain, DocKind.Command, 1, null, 1_700_000_000_007, grantText.encodeToByteArray(), only = setOf(b.deviceId))

        fun docEntry(
            name: String,
            doc: SealedDoc,
            plaintext: String,
            openers: List<String>? = null,
        ) = buildJsonObject {
            put("name", name)
            put("doc", ConnectJson.json.encodeToJsonElement(SealedDoc.serializer(), doc))
            put("plaintext", plaintext)
            put("hash", DocSealer.hash(doc))
            // 開ける端末（無ければ家の全端末）
            openers?.let { list -> put("openers", buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }) }
        }

        // ペアリング（A = Responder、B = Initiator）。使い捨ての鍵を書き出し、C# が同じ鍵で相手の側を再現する
        val joinInfo = ConnectClient.JoinInfo(chain.householdId, chain.headSeq, chain.headHash, "store-1", 4, 1)
        val joinInfoJson = ConnectJson.json.encodeToString(ConnectClient.JoinInfo.serializer(), joinInfo)
        val memberKeysJson = ConnectJson.json.encodeToString(MemberKeys.serializer(), b.memberKeys)
        val responder = Pairing.Responder(a.identityPrivate, a.identityPublic, "Pixel 3", "account-1", nowMs = { 0 }, confirmExtension = joinInfoJson)
        val offerText = Pairing.encodeOffer(responder.offer)
        val initiator = Pairing.Initiator(Pairing.decodeOffer(offerText)!!, b.identityPrivate, b.identityPublic, "Windows ‮test", "account-1", helloExtension = memberKeysJson)
        val hello = initiator.hello()
        val confirm = responder.onHello(hello).getOrThrow()
        val (code, pairOnB) = initiator.onConfirm(confirm).getOrThrow()
        val pairOnA = responder.onCodeEntered(code).getOrThrow()
        val responderEphemeral = field<KeyPair>(responder, "ephemeral")
        val initiatorEphemeral = field<KeyPair>(initiator, "ephemeral")
        val pairingJson = Pairing::class.java.getDeclaredField("json").let { it.isAccessible = true; it.get(Pairing) as kotlinx.serialization.json.Json }
        // ログインをまだ持たない B（wave-api、ADR-0015）: A は印で照合して受ける
        val responderAccountless = Pairing.Responder(a.identityPrivate, a.identityPublic, "Pixel 3", "account-1", nowMs = { 0 }, confirmExtension = joinInfoJson)
        val accountlessOfferText = Pairing.encodeOffer(responderAccountless.offer)
        val initiatorAccountless = Pairing.Initiator(Pairing.decodeOffer(accountlessOfferText)!!, b.identityPrivate, b.identityPublic, "wave API", null, helloExtension = memberKeysJson)
        val accountlessHello = initiatorAccountless.hello()
        val (accountlessCode, _) = initiatorAccountless.onConfirm(responderAccountless.onHello(accountlessHello).getOrThrow()).getOrThrow()
        check(responderAccountless.onCodeEntered(accountlessCode).getOrThrow().peerAccountless)

        val stored = StoredState(StateKind.Cursor, 4, cursorDoc, "store-1", 1_700_000_000_100, 1_700_000_000_200, a.deviceId)
        val relay =
            listOf(
                RelayV2.Challenge("relay.example", "bm9uY2U"),
                RelayV2.Auth(b64.encodeToString(a.identityPub), "c2ln"),
                RelayV2.Welcome(a.deviceId, "store-1"),
                RelayV2.Welcome(a.deviceId, "store-1", chain.householdId, chain.headSeq, chain.headHash, a.deviceId, listOf(a.deviceId, b.deviceId), listOf(stored), mapOf(a.deviceId to 3L, b.deviceId to 2L)),
                RelayV2.HouseholdCreate(genesis),
                RelayV2.ChainGet(),
                RelayV2.ChainGet(2),
                RelayV2.Chain(records),
                RelayV2.ChainAppend(addPc),
                RelayV2.ChainOk(2, chain.headHash),
                RelayV2.ChainConflict(2, chain.headHash),
                RelayV2.ChainChanged(2, chain.headHash),
                RelayV2.StateGet,
                RelayV2.StatePut("req-1", StateKind.Queue, 0, queueDoc),
                RelayV2.StateOk("req-1", StateKind.Queue, 1),
                RelayV2.StateConflict("req-2", StateKind.Cursor),
                RelayV2.StateConflict("req-2", StateKind.Cursor, stored),
                RelayV2.State(stored),
                RelayV2.Claim("claim-0000000000000001", commandDoc),
                RelayV2.Transfer("claim-0000000000000001", 4, commandDoc),
                RelayV2.YieldDelivery("claim-0000000000000001", commandDoc),
                RelayV2.Yielded("claim-0000000000000001", 4, cursorDoc),
                RelayV2.Release("claim-0000000000000001"),
                RelayV2.ActiveChanged(null, null, 5),
                RelayV2.ActiveChanged(a.deviceId, "claim-0000000000000001", 6, b.deviceId),
                RelayV2.PendingExpired("claim-0000000000000001"),
                RelayV2.Presence(listOf(a.deviceId)),
                RelayV2.PairingOpen(b64.encodeToString(responder.tokenHash)),
                RelayV2.PairHelloIn(responder.offer.token, pairingJson.encodeToJsonElement(Pairing.Hello.serializer(), hello)),
                RelayV2.PairHello(b64.encodeToString(responder.tokenHash), pairingJson.encodeToJsonElement(Pairing.Hello.serializer(), hello)),
                RelayV2.PairConfirm(b64.encodeToString(responder.tokenHash), pairingJson.encodeToJsonElement(Pairing.Confirm.serializer(), confirm)),
                RelayV2.PairConfirmed(pairingJson.encodeToJsonElement(Pairing.Confirm.serializer(), confirm)),
                RelayV2.Error("not_active"),
                RelayV2.Error("stale_head", "req-1"),
                RelayV2.LibraryGet,
                RelayV2.LibraryPut("req-3", 0, libraryDoc),
                RelayV2.LibraryOk("req-3", 1),
                RelayV2.LibraryConflict("req-4"),
                RelayV2.LibraryConflict("req-4", LibraryState(1, libraryDoc, "store-1")),
                RelayV2.Library(),
                RelayV2.Library(LibraryState(1, libraryDoc, "store-1")),
            )

        val fixture =
            buildJsonObject {
                put(
                    "keys",
                    buildJsonObject {
                        keys.forEach { (name, pair) ->
                            put(name, buildJsonObject { put("identity", pair.first); put("kx", pair.second) })
                        }
                    },
                )
                put("deviceIds", buildJsonObject { put("a", a.deviceId); put("b", b.deviceId); put("pc", pc.deviceId) })
                put("memberKeys", buildJsonObject { put("a", ConnectJson.json.encodeToJsonElement(MemberKeys.serializer(), a.memberKeys)) })
                put("chain", buildJsonArray { records.forEach { add(ConnectJson.json.encodeToJsonElement(ChainRecord.serializer(), it)) } })
                put("chainHashes", buildJsonArray { records.forEach { add(JsonPrimitive(MemberChain.hash(it))) } })
                put("householdId", chain.householdId)
                put("accountTag", buildJsonObject { put("householdId", chain.householdId); put("accountId", "account-1"); put("tag", tag) })
                put(
                    "docs",
                    buildJsonArray {
                        add(docEntry("queue", queueDoc, ConnectJson.json.encodeToString(QueueBody.serializer(), queueBody)))
                        add(docEntry("cursor", cursorDoc, ConnectJson.json.encodeToString(CursorBody.serializer(), cursorBody)))
                        add(docEntry("command", commandDoc, ConnectJson.json.encodeToString(CommandBody.serializer(), command)))
                        add(docEntry("profile", profileDoc, ConnectJson.json.encodeToString(ProfileBody.serializer(), ProfileBody("Windows PC"))))
                        add(docEntry("library", libraryDoc, ConnectJson.json.encodeToString(LibraryBody.serializer(), libraryBody)))
                        add(docEntry("login_grant", grantDoc, grantText, openers = listOf("b")))
                    },
                )
                put("handoff", buildJsonObject { put("handoffId", handoffId); put("text", snapshotText) })
                put("relay", buildJsonArray { relay.forEach { add(relayElement(it)) } })
                put(
                    "pairing",
                    buildJsonObject {
                        put("offer", offerText)
                        put("responderEphemeral", b64.encodeToString(responderEphemeral.private.encoded))
                        put("initiatorEphemeral", b64.encodeToString(initiatorEphemeral.private.encoded))
                        put("token", responder.offer.token)
                        put("accountId", "account-1")
                        put("responderName", "Pixel 3")
                        put("initiatorName", "Windows ‮test")
                        put("hello", pairingJson.encodeToJsonElement(Pairing.Hello.serializer(), hello))
                        put("confirm", pairingJson.encodeToJsonElement(Pairing.Confirm.serializer(), confirm))
                        put("code", code)
                        put("helloExtension", memberKeysJson)
                        put("confirmExtension", joinInfoJson)
                        put("aSendKey", b64.encodeToString(pairOnA.sendKey))
                        put("aReceiveKey", b64.encodeToString(pairOnA.receiveKey))
                        put("accountKey", b64.encodeToString(pairOnA.accountKey))
                        put("bSendKey", b64.encodeToString(pairOnB.sendKey))
                        put(
                            "accountless",
                            buildJsonObject {
                                put("offer", accountlessOfferText)
                                put("responderEphemeral", b64.encodeToString(field<KeyPair>(responderAccountless, "ephemeral").private.encoded))
                                put("hello", pairingJson.encodeToJsonElement(Pairing.Hello.serializer(), accountlessHello))
                                put("code", accountlessCode)
                            },
                        )
                    },
                )
            }
        fixtureDir.mkdirs()
        File(fixtureDir, "kotlin.json").writeText(prettyJson.encodeToString(JsonElement.serializer(), fixture) + "\n")
    }

    private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

    @Test
    fun verifyCSharpFixtures() {
        val file = File(fixtureDir, "csharp.json")
        if (!file.exists()) return
        val fixture = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()).jsonObject
        fun str(
            obj: JsonObject,
            key: String,
        ) = obj.getValue(key).jsonPrimitive.content

        // 鎖: C# が作った記録を検証し、ハッシュが一致する
        val records = fixture.getValue("chain").jsonArray.map { ConnectJson.json.decodeFromJsonElement(ChainRecord.serializer(), it) }
        val chain = assertIs<ChainVerification.Ok>(MemberChain.verify(records)).chain
        assertEquals(setOf(a.deviceId, b.deviceId, pc.deviceId), chain.members.keys)
        assertEquals(fixture.getValue("chainHashes").jsonArray.map { it.jsonPrimitive.content }, records.map(MemberChain::hash))
        assertEquals(str(fixture, "householdId"), chain.householdId)
        // C# が JSON にした鍵の記述（加入の Hello の拡張）を読める
        assertEquals(b.memberKeys.copy(kxSignature = ""), ConnectJson.json.decodeFromString(MemberKeys.serializer(), str(fixture, "memberKeysJson")).copy(kxSignature = ""))
        val tag = fixture.getValue("accountTag").jsonObject
        assertEquals(str(tag, "tag"), accountTag(str(tag, "householdId"), str(tag, "accountId")))

        // SealedDoc: 家の全端末で開け、平文とハッシュが一致する
        for (entry in fixture.getValue("docs").jsonArray.map { it.jsonObject }) {
            val doc = ConnectJson.json.decodeFromJsonElement(SealedDoc.serializer(), entry.getValue("doc"))
            assertEquals(str(entry, "hash"), DocSealer.hash(doc), str(entry, "name"))
            val openers = entry["openers"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: setOf("a", "b", "pc")
            for ((name, device) in listOf("a" to a, "b" to b, "pc" to pc)) {
                if (name !in openers) {
                    assertEquals(DocOpen.Rejected("not_for_me"), DocSealer.open(doc, chain, device), str(entry, "name"))
                    continue
                }
                val opened = assertIs<DocOpen.Ok>(DocSealer.open(doc, chain, device), str(entry, "name"))
                assertEquals(str(entry, "plaintext"), opened.plaintext.decodeToString())
            }
            when (str(entry, "name")) {
                "queue" -> {
                    val body = ConnectJson.json.decodeFromString(QueueBody.serializer(), str(entry, "plaintext"))
                    val snapshot = assertIs<HandoffDecodeResult.Ok>(HandoffCodec.decode(body.snapshot, handoffId)).snapshot
                    assertEquals(sampleSnapshot(), snapshot)
                }
                "cursor" -> assertEquals(HandoffRepeat.One, ConnectJson.json.decodeFromString(CursorBody.serializer(), str(entry, "plaintext")).repeat)
                "command" -> assertIs<CommandBody.Yield>(ConnectJson.json.decodeFromString(CommandBody.serializer(), str(entry, "plaintext")))
                "login_grant" -> {
                    val grant = assertIs<CommandBody.LoginGrant>(ConnectJson.json.decodeFromString(CommandBody.serializer(), str(entry, "plaintext")))
                    assertEquals(sampleLoginGrant(a.deviceId, b.deviceId), grant)
                    assertEquals(listOf(b.deviceId), doc.recipients.map { it.deviceId })
                }
                "library" -> {
                    val body = ConnectJson.json.decodeFromString(LibraryBody.serializer(), str(entry, "plaintext"))
                    assertEquals(sampleLibrary(), body)
                    assertEquals(str(entry, "plaintext"), ConnectJson.json.encodeToString(LibraryBody.serializer(), body))
                }
            }
        }

        // 中継の JSON: Kotlin で読み、書き直すと同じ文字列になる
        for (text in fixture.getValue("relay").jsonArray.map { it.jsonPrimitive.content }) {
            val message = assertNotNull(RelayV2.decode(text), text)
            assertEquals(text, RelayV2.encode(message))
        }
        // HandoffCodec の文字列（C# の Encode）を読める
        val handoff = fixture.getValue("handoff").jsonObject
        assertEquals(sampleSnapshot(), assertIs<HandoffDecodeResult.Ok>(HandoffCodec.decode(str(handoff, "text"), handoffId)).snapshot)

        // ペアリング: C# の Hello を Kotlin の Responder（同じ使い捨ての鍵）で受け、C# の Confirm を Kotlin の Initiator（同じ使い捨ての鍵）で受ける
        val pairing = fixture.getValue("pairing").jsonObject
        val offer = assertNotNull(Pairing.decodeOffer(str(pairing, "offer")))
        val eAPkcs8 = unb64.decode(str(pairing, "responderEphemeral"))
        val eA = KeyPair(Hpke.deserializePublicKey(eAPkcs8.copyOfRange(eAPkcs8.size - 65, eAPkcs8.size)), KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(eAPkcs8)))
        val responder = Pairing.Responder(a.identityPrivate, a.identityPublic, "Pixel 3", str(pairing, "accountId"), nowMs = { 0 }, confirmExtension = str(pairing, "confirmExtension"))
        setField(responder, "ephemeral", eA)
        setField(responder, "eA", unb64.decode(offer.eA))
        setField(responder, "token", unb64.decode(offer.token))
        val pairingJson = Pairing::class.java.getDeclaredField("json").let { it.isAccessible = true; it.get(Pairing) as kotlinx.serialization.json.Json }
        val hello = pairingJson.decodeFromJsonElement(Pairing.Hello.serializer(), pairing.getValue("hello"))
        responder.onHello(hello).getOrThrow()
        val pairOnA = responder.onCodeEntered(str(pairing, "code")).getOrThrow()
        assertEquals(str(pairing, "aSendKey"), b64.encodeToString(pairOnA.sendKey))
        assertEquals(str(pairing, "helloExtension"), pairOnA.peerExtension)
        assertEquals(str(pairing, "initiatorName").let(Pairing::sanitizeName), pairOnA.peerDeviceName)

        val eBPkcs8 = unb64.decode(str(pairing, "initiatorEphemeral"))
        val eBPublic = Hpke.deserializePublicKey(eBPkcs8.copyOfRange(eBPkcs8.size - 65, eBPkcs8.size))!!
        val eBPrivate = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(eBPkcs8))
        val initiator = Pairing.Initiator(offer, b.identityPrivate, b.identityPublic, "Windows", str(pairing, "accountId"))
        // 使い捨ての鍵から導く値を、C# が使った鍵で作り直す（Initiator は作るときに導くので、反射で入れ替える）
        val pkA = unb64.decode(offer.pkA)
        val eAEncoded = unb64.decode(offer.eA)
        val token = unb64.decode(offer.token)
        val eBEncoded = WaveCrypto.encodePublicKey(eBPublic)
        val transcript0 = WaveCrypto.lengthPrefixed("wave-pair-v1".encodeToByteArray(), pkA, eAEncoded, token, eBEncoded)
        val transcript = WaveCrypto.lengthPrefixed(transcript0, b.identityPub)
        val prk = Hkdf.extract(token, WaveCrypto.ecdh(eBPrivate, WaveCrypto.decodePublicKey(eAEncoded)!!))
        val schedule = Pairing::class.java.getDeclaredMethod("schedule", ByteArray::class.java, ByteArray::class.java, ByteArray::class.java, ByteArray::class.java).also { it.isAccessible = true }
        setField(initiator, "transcript0", transcript0)
        setField(initiator, "transcript", transcript)
        setField(initiator, "prk", prk)
        setField(initiator, "keys", schedule.invoke(Pairing, prk, transcript, b.identityPub, pkA))
        val confirm = pairingJson.decodeFromJsonElement(Pairing.Confirm.serializer(), pairing.getValue("confirm"))
        val (code, pairOnB) = initiator.onConfirm(confirm).getOrThrow()
        assertEquals(str(pairing, "code"), code)
        assertContentEquals(pairOnA.sendKey, pairOnB.receiveKey)
        assertEquals(str(pairing, "confirmExtension"), pairOnB.peerExtension)
        assertTrue(code.matches(Regex("^[0-9]{6}$")))
        assertFalse(pairOnA.sendKey.contentEquals(pairOnA.receiveKey))
    }
}
