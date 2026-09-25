package xyz.mooma0750.wave.debug

import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.session.CallResult

/**
 * debug ビルドだけ: adb から `am start -n xyz.mooma0750.wave/.MainActivity --es debug_action <名前>` で確認の操作を起こす。
 * 画面の文字を読まずに（個人のライブラリを PC に出さずに）通し確認をするため。release ビルドでは何もしない。
 *
 * アカウントへの書き込み・ログアウトは、公開 Activity からは起こさない（他アプリの intent でも起動できるため）。
 * DUMP 権限付きの [DebugReceiver] からだけ受ける: `am broadcast -n xyz.mooma0750.wave/.debug.DebugReceiver --es debug_action <名前>`
 */
object DebugActions {
    private const val EXTRA = "debug_action"

    /** debug の intent から画面を開く（MainActivity が購読する）。 */
    private val navigationChannel = kotlinx.coroutines.channels.Channel<xyz.mooma0750.wave.navigation.Screen>(capacity = 4)

    /** 画面がまだ購読していなくても取りこぼさない（起動直後の intent でも開く）。 */
    val navigation = navigationChannel.receiveAsFlow()

    /**
     * 公開 Activity から受けてよい action（画面の遷移だけ）。再生を始めるものも、再生履歴というアカウントへの書き込みにつながるので
     * DebugReceiver からだけ受ける（F1 outer gate 指摘2）。
     */
    private val ACTIVITY_ACTIONS = setOf("open_artist", "open_album")

    /** 確認用に作ったプレイリスト。プロセスの中だけに持ち、ログに出さない。 */
    private var createdPlaylistId: String? = null

    fun handle(
        intent: Intent?,
        container: AppContainer,
        context: android.content.Context = container.appContext,
        fromPrivilegedReceiver: Boolean = false,
    ) {
        if (!container.isDebuggable) return
        val action = intent?.getStringExtra(EXTRA) ?: return
        intent.removeExtra(EXTRA)
        if (action !in ACTIVITY_ACTIONS && !fromPrivilegedReceiver) {
            Log.w(WaveApplication.LOG_TAG, "debug action=$action rejected (use DebugReceiver)")
            return
        }
        Log.i(WaveApplication.LOG_TAG, "debug action=$action")
        container.appScope.launch(Dispatchers.Main) {
            // 起動直後は保存したログインの復元が終わっていない
            val auth = container.authManager.state.first { it !is AuthState.Restoring }
            // セッションへの反映も待つ（HomeScreen と同じ）
            if (auth is AuthState.LoggedIn) container.session.appliedObtainedAtEpochMs.first { it == auth.obtainedAtEpochMs }
            val player = container.player
            when (action) {
                "search_play" ->
                    when (val result = container.music.searchSongs(intent.getStringExtra("query") ?: "YOASOBI")) {
                        is CallResult.Ok -> result.value.songs.getOrNull(intent.getIntExtra("index", 0))?.let { player.playSong(it) }
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug search failed ${result.kind} status=${result.status}")
                    }
                "add_unplayable_next" -> player.addNext(DebugPlayback.unplayableSong)
                "play_public_playlist", "play_public_playlist_shuffle" ->
                    when (val page = container.music.playlist(DebugPlayback.PUBLIC_PLAYLIST_ID)) {
                        is CallResult.Ok -> player.playFrom(page.value.songs, 0, DebugPlayback.PUBLIC_PLAYLIST_ID, page.value.continuation, shuffle = action.endsWith("shuffle"))
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug playlist failed ${page.kind}")
                    }
                "last_item" -> player.controller().run { seekTo(mediaItemCount - 1, 0L) }
                "near_end" -> player.controller().run { if (duration > 10_000) seekTo(duration - 5_000) }
                "logout" -> {
                    createdPlaylistId = null
                    container.logout()
                }
                "shuffle" -> player.shuffleUpcoming()
                // Phase 6: 暗号の部品とペアリングの手順が、この端末（Conscrypt・Keystore の鍵）で動くかを端末の中だけで確かめる（結果の真偽値だけ）
                "crypto_selftest" -> {
                    val results = linkedMapOf<String, Boolean>()
                    fun check(name: String, block: () -> Boolean) {
                        results[name] = runCatching(block).getOrElse { error -> Log.i(WaveApplication.LOG_TAG, "debug selftest $name error=${error::class.simpleName}"); false }
                    }
                    val identity = container.deviceIdentity
                    check("keystore_key_decodes") { xyz.mooma0750.wave.handoff.crypto.WaveCrypto.decodePublicKey(identity.encodedPublicKey) != null }
                    check("keystore_sign_verify") {
                        val sig = identity.sign("wave-selftest", byteArrayOf(1, 2, 3))
                        xyz.mooma0750.wave.handoff.crypto.WaveCrypto.verify(identity.publicKey, "wave-selftest", byteArrayOf(1, 2, 3), sig)
                    }
                    check("pairing_roundtrip") {
                        val other = xyz.mooma0750.wave.handoff.crypto.WaveCrypto.generateEphemeralKeyPair()
                        val a = xyz.mooma0750.wave.handoff.crypto.Pairing.Responder(identity.privateKey(), identity.publicKey, "self-a", "selftest-account", nowMs = android.os.SystemClock::elapsedRealtime)
                        val offer = xyz.mooma0750.wave.handoff.crypto.Pairing.decodeOffer(xyz.mooma0750.wave.handoff.crypto.Pairing.encodeOffer(a.offer))!!
                        val b = xyz.mooma0750.wave.handoff.crypto.Pairing.Initiator(offer, other.private, other.public, "self-b", "selftest-account")
                        val (code, onB) = b.onConfirm(a.onHello(b.hello()).getOrThrow()).getOrThrow()
                        val onA = a.onCodeEntered(code).getOrThrow()
                        val env = xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer.seal(onB.sendKey, "b", "a", xyz.mooma0750.wave.handoff.EnvelopeType.Request, 0, "h", byteArrayOf(9))
                        xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer.open(env, onA.receiveKey, "a", "b", -1)?.contentEquals(byteArrayOf(9)) == true
                    }
                    check("hkdf_rfc_case3") {
                        val prk = xyz.mooma0750.wave.handoff.crypto.Hkdf.extract(ByteArray(0), ByteArray(22) { 0x0b })
                        prk.joinToString("") { "%02x".format(it) } == "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"
                    }
                    Log.i(WaveApplication.LOG_TAG, "debug selftest sdk=${android.os.Build.VERSION.SDK_INT} $results")
                }
                // Phase 6: ペアリングと引き取りの確認（DebugReceiver からだけ）。QR の文字列と6桁はアプリの中のファイルに書き、ログには出さない
                "connect_info" -> {
                    java.io.File(context.filesDir, "connect-pubkey.txt").writeText(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(container.deviceIdentity.encodedPublicKey))
                    Log.i(WaveApplication.LOG_TAG, "debug connect configured=${container.connectConfigured} pairs=${container.pairStore.all().size} relay=${container.relayClient.status.value}")
                }
                "pair_offer" -> {
                    container.pairing.startOffer()
                    val shown = kotlinx.coroutines.withTimeoutOrNull(20_000) { container.pairing.state.first { it is xyz.mooma0750.wave.connect.PairingController.State.ShowingOffer || it is xyz.mooma0750.wave.connect.PairingController.State.Failed } }
                    (shown as? xyz.mooma0750.wave.connect.PairingController.State.ShowingOffer)?.let { java.io.File(context.filesDir, "pair-offer.txt").writeText(it.offerText) }
                    Log.i(WaveApplication.LOG_TAG, "debug pair offer state=${shown?.let { it::class.simpleName }} message=${(shown as? xyz.mooma0750.wave.connect.PairingController.State.Failed)?.message}")
                }
                "pair_accept" -> {
                    container.pairing.accept(intent.getStringExtra("offer").orEmpty())
                    val next = kotlinx.coroutines.withTimeoutOrNull(30_000) { container.pairing.state.first { it is xyz.mooma0750.wave.connect.PairingController.State.ShowCode || it is xyz.mooma0750.wave.connect.PairingController.State.Failed } }
                    (next as? xyz.mooma0750.wave.connect.PairingController.State.ShowCode)?.let { java.io.File(context.filesDir, "pair-code.txt").writeText(it.code) }
                    Log.i(WaveApplication.LOG_TAG, "debug pair accept state=${next?.let { it::class.simpleName }} message=${(next as? xyz.mooma0750.wave.connect.PairingController.State.Failed)?.message}")
                }
                "pair_code" -> {
                    container.pairing.enterCode(intent.getStringExtra("code").orEmpty())
                    val next = kotlinx.coroutines.withTimeoutOrNull(20_000) { container.pairing.state.first { it is xyz.mooma0750.wave.connect.PairingController.State.Done || it is xyz.mooma0750.wave.connect.PairingController.State.Failed || (it as? xyz.mooma0750.wave.connect.PairingController.State.EnterCode)?.message != null } }
                    Log.i(WaveApplication.LOG_TAG, "debug pair code state=${next?.let { it::class.simpleName }} message=${(next as? xyz.mooma0750.wave.connect.PairingController.State.Failed)?.message}")
                }
                "pair_continue" -> {
                    container.pairing.confirmOnB()
                    val next = kotlinx.coroutines.withTimeoutOrNull(20_000) { container.pairing.state.first { it is xyz.mooma0750.wave.connect.PairingController.State.Done || it is xyz.mooma0750.wave.connect.PairingController.State.ShowCode || it is xyz.mooma0750.wave.connect.PairingController.State.Failed } }
                    Log.i(WaveApplication.LOG_TAG, "debug pair continue state=${next?.let { it::class.simpleName }} pairs=${container.pairStore.all().size}")
                }
                // Phase 6b: Connect v2 の確認（中継 v2 の場所が local.properties に無ければ何もしない）
                "v2_info" -> {
                    val manager = container.connectV2
                    if (manager == null) {
                        Log.i(WaveApplication.LOG_TAG, "debug v2 configured=false")
                    } else {
                        manager.relay.acquire()
                        try {
                            kotlinx.coroutines.delay(5_000)
                            manager.summary { Log.i(WaveApplication.LOG_TAG, "debug v2 $it") }
                            kotlinx.coroutines.delay(500)
                        } finally {
                            manager.relay.release()
                        }
                    }
                }
                "v2_create_household" -> {
                    val manager = container.connectV2 ?: return@launch
                    manager.relay.acquire()
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(15_000) { manager.relay.status.first { it == xyz.mooma0750.wave.connect2.RelayClientV2.Status.Connected } }
                        manager.createHousehold()
                        kotlinx.coroutines.delay(5_000)
                        manager.summary { Log.i(WaveApplication.LOG_TAG, "debug v2 household $it") }
                        kotlinx.coroutines.delay(500)
                    } finally {
                        manager.relay.release()
                    }
                }
                "v2_pair_offer" -> {
                    val pairing = container.pairingV2 ?: return@launch
                    pairing.startOffer()
                    val shown = kotlinx.coroutines.withTimeoutOrNull(20_000) { pairing.state.first { it is xyz.mooma0750.wave.connect2.PairingV2Controller.State.ShowingOffer || it is xyz.mooma0750.wave.connect2.PairingV2Controller.State.Failed } }
                    (shown as? xyz.mooma0750.wave.connect2.PairingV2Controller.State.ShowingOffer)?.let { java.io.File(context.filesDir, "pair-offer-v2.txt").writeText(it.offerText) }
                    Log.i(WaveApplication.LOG_TAG, "debug v2 pair offer state=${shown?.let { it::class.simpleName }}")
                }
                "v2_pair_accept" -> {
                    val pairing = container.pairingV2 ?: return@launch
                    // 加入の第1手は /v2/pair の別のソケット、待ちは PairingV2Controller が自分で参照を取る（再レビュー 低-P）
                    pairing.accept(intent.getStringExtra("offer").orEmpty())
                    val next = kotlinx.coroutines.withTimeoutOrNull(30_000) { pairing.state.first { it is xyz.mooma0750.wave.connect2.PairingV2Controller.State.ShowCode || it is xyz.mooma0750.wave.connect2.PairingV2Controller.State.Failed } }
                    (next as? xyz.mooma0750.wave.connect2.PairingV2Controller.State.ShowCode)?.let { java.io.File(context.filesDir, "pair-code-v2.txt").writeText(it.code) }
                    Log.i(WaveApplication.LOG_TAG, "debug v2 pair accept state=${next?.let { it::class.simpleName }} message=${(next as? xyz.mooma0750.wave.connect2.PairingV2Controller.State.Failed)?.message}")
                }
                "v2_pair_code" -> {
                    val pairing = container.pairingV2 ?: return@launch
                    pairing.enterCode(intent.getStringExtra("code").orEmpty())
                    val next = kotlinx.coroutines.withTimeoutOrNull(20_000) { pairing.state.first { it is xyz.mooma0750.wave.connect2.PairingV2Controller.State.Done || it is xyz.mooma0750.wave.connect2.PairingV2Controller.State.Failed || (it as? xyz.mooma0750.wave.connect2.PairingV2Controller.State.EnterCode)?.message != null } }
                    Log.i(WaveApplication.LOG_TAG, "debug v2 pair code state=${next?.let { it::class.simpleName }} message=${(next as? xyz.mooma0750.wave.connect2.PairingV2Controller.State.Failed)?.message}")
                }
                "v2_pair_continue" -> {
                    val pairing = container.pairingV2 ?: return@launch
                    pairing.confirmOnB()
                    val next = kotlinx.coroutines.withTimeoutOrNull(40_000) { pairing.state.first { it is xyz.mooma0750.wave.connect2.PairingV2Controller.State.Done || it is xyz.mooma0750.wave.connect2.PairingV2Controller.State.Failed } }
                    Log.i(WaveApplication.LOG_TAG, "debug v2 pair continue state=${next?.let { it::class.simpleName }} message=${(next as? xyz.mooma0750.wave.connect2.PairingV2Controller.State.Failed)?.message}")
                    container.connectV2?.summary { Log.i(WaveApplication.LOG_TAG, "debug v2 pair continue $it") }
                    kotlinx.coroutines.delay(500)
                }
                "v2_transfer" -> {
                    val manager = container.connectV2 ?: return@launch
                    player.controller() // 再生サービスを起動しておく
                    manager.relay.acquire()
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(15_000) { manager.relay.status.first { it == xyz.mooma0750.wave.connect2.RelayClientV2.Status.Connected } }
                        kotlinx.coroutines.delay(2_000)
                        manager.transfer()
                        kotlinx.coroutines.delay(15_000)
                        manager.summary { Log.i(WaveApplication.LOG_TAG, "debug v2 transfer $it") }
                        kotlinx.coroutines.delay(500)
                    } finally {
                        manager.relay.release()
                    }
                }
                "v2_accept_reset" -> container.connectV2?.acceptStoreReset()
                "take_over" -> {
                    val peer = container.pairStore.all().firstOrNull()
                    if (peer == null) {
                        Log.i(WaveApplication.LOG_TAG, "debug take over no pair")
                    } else {
                        Log.i(WaveApplication.LOG_TAG, "debug take over started=${container.connect.takeOver(peer.peerDeviceId)} relay=${container.relayClient.status.value}")
                    }
                }
                // Phase 6: 中継を使わず、同じ端末で自分のキューを書き出して検証し、取り込み直す（adopt の確認）
                "handoff_self" -> {
                    player.controller() // サービスを起動しておく
                    val endpoint = container.handoffHost.endpoint
                    val handoffId = java.util.UUID.randomUUID().toString()
                    val captured = endpoint?.capture(handoffId, container.session.loginGenerationId())
                    if (endpoint == null || captured == null) {
                        Log.i(WaveApplication.LOG_TAG, "debug handoff capture failed endpoint=${endpoint != null}")
                    } else {
                        val text = xyz.mooma0750.wave.handoff.HandoffCodec.encode(captured)
                        when (val decoded = xyz.mooma0750.wave.handoff.HandoffCodec.decode(text, handoffId)) {
                            is xyz.mooma0750.wave.handoff.HandoffDecodeResult.Ok -> {
                                val index = decoded.snapshot.items.indexOfFirst { it.itemId == decoded.snapshot.currentItemId }
                                Log.i(WaveApplication.LOG_TAG, "debug handoff bytes=${text.length} items=${decoded.snapshot.items.size} index=$index positionS=${decoded.snapshot.positionMs / 1000} listenedS=${decoded.snapshot.listenedMs / 1000} sent=${decoded.snapshot.historySent} repeat=${decoded.snapshot.repeat}")
                                Log.i(WaveApplication.LOG_TAG, "debug handoff adopt=${endpoint.adopt(decoded.snapshot, container.session.loginGenerationId())}")
                            }
                            else -> Log.i(WaveApplication.LOG_TAG, "debug handoff decode=$decoded")
                        }
                    }
                }
                // Phase 6 の暗号の選択のため、この端末の Android Keystore で使える鍵の種類を測る（真偽値だけ、鍵は作ってすぐ消す）
                "keystore_probe" -> {
                    val results = linkedMapOf<String, Boolean>()
                    fun attempt(name: String, block: () -> Unit) {
                        results[name] = runCatching { block() }.isSuccess
                    }
                    val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    attempt("ecdsa_p256") {
                        val gen = java.security.KeyPairGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                        gen.initialize(android.security.keystore.KeyGenParameterSpec.Builder("wave-probe-sign", android.security.keystore.KeyProperties.PURPOSE_SIGN).setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256).build())
                        val pair = gen.generateKeyPair()
                        java.security.Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(byteArrayOf(1)); sign() }
                    }
                    attempt("ecdh_p256") {
                        val gen = java.security.KeyPairGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                        gen.initialize(android.security.keystore.KeyGenParameterSpec.Builder("wave-probe-agree", android.security.keystore.KeyProperties.PURPOSE_AGREE_KEY).build())
                        val pair = gen.generateKeyPair()
                        val peer = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
                        javax.crypto.KeyAgreement.getInstance("ECDH", "AndroidKeyStore").apply { init(pair.private); doPhase(peer.public, true); generateSecret() }
                    }
                    attempt("x25519_keystore") {
                        val gen = java.security.KeyPairGenerator.getInstance("XDH", "AndroidKeyStore")
                        gen.initialize(android.security.keystore.KeyGenParameterSpec.Builder("wave-probe-x25519", android.security.keystore.KeyProperties.PURPOSE_AGREE_KEY).setAlgorithmParameterSpec(java.security.spec.NamedParameterSpec("X25519")).build())
                        gen.generateKeyPair()
                    }
                    attempt("x25519_software") {
                        val pair = java.security.KeyPairGenerator.getInstance("XDH").apply { initialize(java.security.spec.NamedParameterSpec("X25519")) }.generateKeyPair()
                        javax.crypto.KeyAgreement.getInstance("XDH").apply { init(pair.private); doPhase(pair.public, true); generateSecret() }
                    }
                    attempt("chacha20_poly1305") { javax.crypto.Cipher.getInstance("ChaCha20-Poly1305") }
                    // Phase 6b P6b-S: 鍵交換・署名の時間（ミリ秒、5回の中央値）と、鍵がどこに置かれるか（securityLevel）
                    val timings = linkedMapOf<String, String>()
                    runCatching {
                        val agree = ks.getKey("wave-probe-agree", null) as java.security.PrivateKey
                        val sign = ks.getKey("wave-probe-sign", null) as java.security.PrivateKey
                        val peer = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
                        fun median(block: () -> Unit): Long = List(5) { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1_000_000 }.sorted()[2]
                        timings["ecdh_ms"] = median { javax.crypto.KeyAgreement.getInstance("ECDH", "AndroidKeyStore").apply { init(agree); doPhase(peer.public, true); generateSecret() } }.toString()
                        timings["ecdsa_ms"] = median { java.security.Signature.getInstance("SHA256withECDSA").apply { initSign(sign); update(ByteArray(512)); sign() } }.toString()
                        val info = java.security.KeyFactory.getInstance(agree.algorithm, "AndroidKeyStore").getKeySpec(agree, android.security.keystore.KeyInfo::class.java)
                        timings["agree_security_level"] = info.securityLevel.toString()
                    }.onFailure { timings["timing_error"] = it::class.simpleName ?: "?" }
                    listOf("wave-probe-sign", "wave-probe-agree", "wave-probe-x25519").forEach { runCatching { ks.deleteEntry(it) } }
                    Log.i(WaveApplication.LOG_TAG, "debug keystore sdk=${android.os.Build.VERSION.SDK_INT} $results $timings")
                }
                // 通知・時計に出す画像の元（ホストと大きさの指定だけ。曲名・ID は出さない）
                "artwork_probe" -> {
                    val controller = player.controller()
                    val uri =
                        controller.currentMediaItem?.let { xyz.mooma0750.wave.playback.Media3QueuePlayer.songOf(it) }?.thumbnailUrl
                            ?.let(xyz.mooma0750.wave.playback.Media3QueuePlayer::largeArtwork)?.let(android.net.Uri::parse)
                    val host = uri?.host
                    val size = uri?.toString()?.let { Regex("=w\\d+-h\\d+|/[a-z]+default\\.jpg").find(it)?.value }
                    Log.i(WaveApplication.LOG_TAG, "debug artwork host=$host size=$size hasData=${controller.mediaMetadata.artworkData != null}")
                    Log.i(WaveApplication.LOG_TAG, "debug artwork platform keys=${container.connectV2Host.platformMetadataKeys?.invoke()?.map { it.removePrefix("android.media.metadata.") }}")
                    // 実際に返る画像の大きさ（URL・曲名は出さない）
                    if (uri != null) {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            runCatching {
                                val request = okhttp3.Request.Builder().url(uri.toString()).build()
                                container.mediaHttpClient.newCall(request).execute().use { response ->
                                    val bytes = response.body?.bytes()
                                    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    if (bytes != null) android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                    Log.i(WaveApplication.LOG_TAG, "debug artwork fetched code=${response.code} bytes=${bytes?.size} image=${options.outWidth}x${options.outHeight}")
                                }
                            }.onFailure { Log.i(WaveApplication.LOG_TAG, "debug artwork fetch failed type=${it::class.simpleName}") }
                        }
                    }
                }
                // 再生履歴を読めるか（区切りの数・件数だけ。曲名は出さない）
                "history_probe" -> {
                    when (val result = container.music.history()) {
                        is CallResult.Ok -> {
                            Log.i(WaveApplication.LOG_TAG, "debug history sections=${result.value.sections.size} songs=${result.value.songs.size} continuation=${result.value.continuation != null} headings=${result.value.sections.count { it.title != null }}")
                            val token = result.value.continuation
                            if (token != null) {
                                when (val next = container.music.history(token)) {
                                    is CallResult.Ok -> Log.i(WaveApplication.LOG_TAG, "debug history page2 sections=${next.value.sections.size} songs=${next.value.songs.size} continuation=${next.value.continuation != null}")
                                    is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug history page2 failed=${next.kind}")
                                }
                            }
                        }
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug history failed=${result.kind} renderers=${container.music.lastRendererNames}")
                    }
                }
                // 最後のページと確かめられるか（番号・ページ数・真偽値だけ）
                "playlist_end_probe" ->
                    (container.music.libraryPlaylists() as? CallResult.Ok)?.value?.forEachIndexed { index, summary ->
                        if (!summary.editable && summary.playlistId != xyz.mooma0750.wave.core.music.MusicRepository.LIKED_SONGS) return@forEachIndexed
                        var continuation: String? = null
                        var pages = 0
                        var last: xyz.mooma0750.wave.core.music.PlaylistPage? = null
                        do {
                            val page = container.music.playlist(summary.playlistId, continuation) as? CallResult.Ok ?: break
                            last = page.value
                            continuation = page.value.continuation
                            pages++
                        } while (continuation != null && pages < 50)
                        Log.i(WaveApplication.LOG_TAG, "probe end playlist=$index pages=$pages songsLast=${last?.songs?.size} endConfirmed=${last?.endConfirmed} keys=${if (last?.endConfirmed == false) container.music.lastKeyPaths else emptySet()}")
                    }
                // プレイリストの書き込みの確認（DebugReceiver からだけ）。作った ID はメモリに置くだけで出さない
                "playlist_create" ->
                    when (val result = container.music.createPlaylist(intent.getStringExtra("title") ?: "wave 確認用")) {
                        is xyz.mooma0750.wave.core.session.WriteResult.Done -> {
                            createdPlaylistId = result.value
                            Log.i(WaveApplication.LOG_TAG, "debug playlist created")
                        }
                        else -> Log.i(WaveApplication.LOG_TAG, "debug playlist create result=$result")
                    }
                "playlist_add" -> {
                    val id = createdPlaylistId
                    val videoId = xyz.mooma0750.wave.playback.Media3QueuePlayer.songOf(player.controller().currentMediaItem)?.videoId
                    if (id != null && videoId != null) Log.i(WaveApplication.LOG_TAG, "debug playlist add result=${container.music.addToPlaylist(id, videoId)}")
                }
                "playlist_remove" -> {
                    val id = createdPlaylistId
                    val videoId = xyz.mooma0750.wave.playback.Media3QueuePlayer.songOf(player.controller().currentMediaItem)?.videoId
                    val page = id?.let { container.music.playlist(it) }
                    if (id != null && videoId != null && page is CallResult.Ok) {
                        val index = page.value.songs.indexOfFirst { it.videoId == videoId }
                        val setVideoId = page.value.setVideoIds.getOrNull(index)
                        Log.i(WaveApplication.LOG_TAG, "debug playlist before remove songs=${page.value.songs.size} editable=${page.value.editable} found=${setVideoId != null}")
                        if (setVideoId != null) Log.i(WaveApplication.LOG_TAG, "debug playlist remove result=${container.music.removeFromPlaylist(id, videoId, setVideoId)::class.simpleName}")
                    }
                }
                "playlist_count" -> {
                    val page = createdPlaylistId?.let { container.music.playlist(it) }
                    Log.i(WaveApplication.LOG_TAG, "debug playlist songs=${(page as? CallResult.Ok)?.value?.songs?.size} editable=${(page as? CallResult.Ok)?.value?.editable}")
                }
                // 編集できるプレイリストの見分け方を調べる（決定 E7）。番号・アイコン名・レンダラ名だけを出し、題名と ID は出さない
                "playlist_probe" ->
                    when (val library = container.music.libraryPlaylists()) {
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "probe library failed ${library.kind}")
                        is CallResult.Ok -> {
                            Log.i(WaveApplication.LOG_TAG, "probe cards=${container.music.lastCardMenuIcons.size} playlists=${library.value.size}")
                            container.music.lastCardMenuIcons.forEachIndexed { index, icons -> Log.i(WaveApplication.LOG_TAG, "probe card=$index icons=$icons") }
                            val only = intent.getIntArrayExtra("only")?.toSet()
                            library.value.take(intent.getIntExtra("limit", 8)).forEachIndexed { index, summary ->
                                if (only != null && index !in only) return@forEachIndexed
                                when (val page = container.music.playlist(summary.playlistId)) {
                                    is CallResult.Ok ->
                                        Log.i(WaveApplication.LOG_TAG, "probe playlist=$index editable=${page.value.editable} liked=${summary.playlistId == xyz.mooma0750.wave.core.music.MusicRepository.LIKED_SONGS} headers=${container.music.lastRendererNames.filter { it.contains("Header") }}")
                                    is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "probe playlist=$index failed ${page.kind} status=${page.status} renderers=${container.music.lastRendererNames}")
                                }
                            }
                        }
                    }
                // ミニプレーヤーの再生ボタンと同じ呼び方
                "play" -> player.controller().run { if (playerError != null) prepare(); play() }
                "queue_digest" ->
                    player.controller().run {
                        val ids = (0 until mediaItemCount).map { xyz.mooma0750.wave.playback.Media3QueuePlayer.songOf(getMediaItemAt(it))?.videoId.orEmpty() }
                        // 曲の ID そのものは出さず、並びと集合のハッシュだけ
                        Log.i(WaveApplication.LOG_TAG, "debug queue size=${ids.size} index=$currentMediaItemIndex head=${ids.take(currentMediaItemIndex + 1).hashCode()} order=${ids.hashCode()} set=${ids.sorted().hashCode()} repeat=$repeatMode shuffleMode=$shuffleModeEnabled")
                    }
                "toggle_like" -> player.likeState.value.let { state -> state.videoId?.let { player.setLiked(it, state.status != xyz.mooma0750.wave.core.music.LikeStatus.Like) } }
                "like_state" -> player.likeState.value.let { Log.i(WaveApplication.LOG_TAG, "debug like status=${it.status} pending=${it.pending} message=${it.message != null}") }
                "repeat" -> {
                    player.cycleRepeatMode()
                    Log.i(WaveApplication.LOG_TAG, "debug repeat=${player.controller().repeatMode}")
                }
                "open_artist" -> intent.getStringExtra("browse_id")?.let { navigationChannel.trySend(xyz.mooma0750.wave.navigation.Screen.Artist(it, "")) }
                "open_album" -> intent.getStringExtra("browse_id")?.let { navigationChannel.trySend(xyz.mooma0750.wave.navigation.Screen.Album(it, "")) }
                "play_album" ->
                    intent.getStringExtra("browse_id")?.let { id ->
                        when (val page = container.music.album(id)) {
                            is CallResult.Ok -> player.playFrom(page.value.songs, 0, page.value.playlistId, null)
                            is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug album failed ${page.kind}")
                        }
                    }
                else -> Log.i(WaveApplication.LOG_TAG, "debug action unknown")
            }
        }
    }
}
