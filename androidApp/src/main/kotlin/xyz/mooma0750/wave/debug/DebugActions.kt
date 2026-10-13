package xyz.mooma0750.wave.debug

import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.connect.PairingController
import xyz.mooma0750.wave.connect2.PairingV2Controller
import xyz.mooma0750.wave.connect2.RelayClientV2
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.LikeStatus
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.WriteResult
import xyz.mooma0750.wave.handoff.EnvelopeType
import xyz.mooma0750.wave.handoff.HandoffCodec
import xyz.mooma0750.wave.handoff.HandoffDecodeResult
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.Hkdf
import xyz.mooma0750.wave.handoff.crypto.Pairing
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.navigation.Screen
import xyz.mooma0750.wave.playback.Media3QueuePlayer
import xyz.mooma0750.wave.playback.playFromButton

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
    private val navigationChannel = kotlinx.coroutines.channels.Channel<Screen>(capacity = 4)

    /** 画面がまだ購読していなくても取りこぼさない（起動直後の intent でも開く）。 */
    val navigation = navigationChannel.receiveAsFlow()

    /** 確認用: 今の画面をログに出し直す（時計のテストが、どの画面にいるかを確かめる）。 */
    val navLogRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /**
     * 公開 Activity から受けてよい action（画面の遷移だけ）。再生を始めるものも、再生履歴というアカウントへの書き込みにつながるので
     * DebugReceiver からだけ受ける（F1 outer gate 指摘2）。
     */
    private val ACTIVITY_ACTIONS = setOf("open_artist", "open_album", "open_podcast", "notice")

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
                // ライブ配信: 検索して index 番目を流す。件数と、流したものがライブかだけを記録する
                "live_play" ->
                    when (val result = container.music.searchLive(intent.getStringExtra("query") ?: "lofi girl")) {
                        is CallResult.Ok -> {
                            Log.i(WaveApplication.LOG_TAG, "debug live results=${result.value.songs.size} live=${result.value.songs.count { it.isLive }} more=${result.value.continuation != null}")
                            result.value.songs.getOrNull(intent.getIntExtra("index", 0))?.let { player.playSong(it) }
                        }
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug live search failed ${result.kind} status=${result.status}")
                    }
                // ライブの印を付けずに流す（WEB_REMIX の /player がライブと知らせたら HLS に切り替わるかの確認）
                "live_play_unflagged" ->
                    when (val result = container.music.searchLive(intent.getStringExtra("query") ?: "lofi girl")) {
                        is CallResult.Ok -> result.value.songs.firstOrNull()?.let { player.playSong(it.copy(isLive = false)) }
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug live search failed ${result.kind}")
                    }
                "live_state" ->
                    player.controller().run {
                        Log.i(WaveApplication.LOG_TAG, "debug live state isLive=$isCurrentMediaItemLive flagged=${Media3QueuePlayer.songOf(currentMediaItem)?.isLive} playing=$isPlaying state=$playbackState error=${playerError?.errorCodeName} offsetMs=$currentLiveOffset")
                    }
                "lofi_page" ->
                    when (val page = container.music.moodCategory(xyz.mooma0750.wave.core.music.LofiGenre.PARAMS)) {
                        is CallResult.Ok -> Log.i(WaveApplication.LOG_TAG, "debug lofi shelves=${page.value.shelves.map { "${it.title}:${it.items.size}" }}")
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug lofi failed ${page.kind}")
                    }
                "video_probe" -> VideoProbe.run(container, intent.getStringExtra("video_id") ?: player.controller().currentMediaItem?.let { Media3QueuePlayer.songOf(it)?.videoId })
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
                "shuffle" -> player.toggleShuffle()
                // Phase 6: 暗号の部品とペアリングの手順が、この端末（Conscrypt・Keystore の鍵）で動くかを端末の中だけで確かめる（結果の真偽値だけ）
                "crypto_selftest" -> {
                    val results = linkedMapOf<String, Boolean>()
                    fun check(name: String, block: () -> Boolean) {
                        results[name] = runCatching(block).getOrElse { error -> Log.i(WaveApplication.LOG_TAG, "debug selftest $name error=${error::class.simpleName}"); false }
                    }
                    val identity = container.deviceIdentity
                    check("keystore_key_decodes") { WaveCrypto.decodePublicKey(identity.encodedPublicKey) != null }
                    check("keystore_sign_verify") {
                        val sig = identity.sign("wave-selftest", byteArrayOf(1, 2, 3))
                        WaveCrypto.verify(identity.publicKey, "wave-selftest", byteArrayOf(1, 2, 3), sig)
                    }
                    check("pairing_roundtrip") {
                        val other = WaveCrypto.generateEphemeralKeyPair()
                        val a = Pairing.Responder(identity.privateKey(), identity.publicKey, "self-a", "selftest-account", nowMs = android.os.SystemClock::elapsedRealtime)
                        val offer = Pairing.decodeOffer(Pairing.encodeOffer(a.offer))!!
                        val b = Pairing.Initiator(offer, other.private, other.public, "self-b", "selftest-account")
                        val (code, onB) = b.onConfirm(a.onHello(b.hello()).getOrThrow()).getOrThrow()
                        val onA = a.onCodeEntered(code).getOrThrow()
                        val env = EnvelopeSealer.seal(onB.sendKey, "b", "a", EnvelopeType.Request, 0, "h", byteArrayOf(9))
                        EnvelopeSealer.open(env, onA.receiveKey, "a", "b", -1)?.contentEquals(byteArrayOf(9)) == true
                    }
                    check("hkdf_rfc_case3") {
                        val prk = Hkdf.extract(ByteArray(0), ByteArray(22) { 0x0b })
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
                    val shown = kotlinx.coroutines.withTimeoutOrNull(20_000) { container.pairing.state.first { it is PairingController.State.ShowingOffer || it is PairingController.State.Failed } }
                    (shown as? PairingController.State.ShowingOffer)?.let { java.io.File(context.filesDir, "pair-offer.txt").writeText(it.offerText) }
                    Log.i(WaveApplication.LOG_TAG, "debug pair offer state=${shown?.let { it::class.simpleName }} message=${(shown as? xyz.mooma0750.wave.connect.PairingController.State.Failed)?.message}")
                }
                "pair_accept" -> {
                    container.pairing.accept(intent.getStringExtra("offer").orEmpty())
                    val next = kotlinx.coroutines.withTimeoutOrNull(30_000) { container.pairing.state.first { it is PairingController.State.ShowCode || it is PairingController.State.Failed } }
                    (next as? PairingController.State.ShowCode)?.let { java.io.File(context.filesDir, "pair-code.txt").writeText(it.code) }
                    Log.i(WaveApplication.LOG_TAG, "debug pair accept state=${next?.let { it::class.simpleName }} message=${(next as? xyz.mooma0750.wave.connect.PairingController.State.Failed)?.message}")
                }
                "pair_code" -> {
                    container.pairing.enterCode(intent.getStringExtra("code").orEmpty())
                    val next = kotlinx.coroutines.withTimeoutOrNull(20_000) { container.pairing.state.first { it is PairingController.State.Done || it is PairingController.State.Failed || (it as? PairingController.State.EnterCode)?.message != null } }
                    Log.i(WaveApplication.LOG_TAG, "debug pair code state=${next?.let { it::class.simpleName }} message=${(next as? xyz.mooma0750.wave.connect.PairingController.State.Failed)?.message}")
                }
                "pair_continue" -> {
                    container.pairing.confirmOnB()
                    val next = kotlinx.coroutines.withTimeoutOrNull(20_000) { container.pairing.state.first { it is PairingController.State.Done || it is PairingController.State.ShowCode || it is PairingController.State.Failed } }
                    Log.i(WaveApplication.LOG_TAG, "debug pair continue state=${next?.let { it::class.simpleName }} pairs=${container.pairStore.all().size}")
                }
                // Phase 6b: Connect v2 の確認（中継 v2 の場所が local.properties に無ければ何もしない）
                // Keystore の操作の枠が返らない件の切り分け（Pixel 3、2026-10-06）。op=ecdh|sign|aes を n 回、本番と同じ使い方で行う。
                // 1回ごとに3秒で打ち切り、何回目で詰まったかだけをログに出す（鍵・値は出さない）
                "keystore_probe" -> {
                    val op = intent.getStringExtra("op").orEmpty()
                    val count = intent.getIntExtra("n", 40)
                    val result = kotlinx.coroutines.withContext(Dispatchers.IO) { KeystoreProbe.run(container, op, count) }
                    Log.i(WaveApplication.LOG_TAG, "debug keystore_probe op=$op $result")
                }
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
                        kotlinx.coroutines.withTimeoutOrNull(15_000) { manager.relay.status.first { it == RelayClientV2.Status.Connected } }
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
                    val shown = kotlinx.coroutines.withTimeoutOrNull(20_000) { pairing.state.first { it is PairingV2Controller.State.ShowingOffer || it is PairingV2Controller.State.Failed } }
                    (shown as? PairingV2Controller.State.ShowingOffer)?.let { java.io.File(context.filesDir, "pair-offer-v2.txt").writeText(it.offerText) }
                    Log.i(WaveApplication.LOG_TAG, "debug v2 pair offer state=${shown?.let { it::class.simpleName }}")
                }
                "v2_pair_accept" -> {
                    val pairing = container.pairingV2 ?: return@launch
                    // 加入の第1手は /v2/pair の別のソケット、待ちは PairingV2Controller が自分で参照を取る（再レビュー 低-P）
                    pairing.accept(intent.getStringExtra("offer").orEmpty())
                    val next = kotlinx.coroutines.withTimeoutOrNull(30_000) { pairing.state.first { it is PairingV2Controller.State.ShowCode || it is PairingV2Controller.State.Failed } }
                    (next as? PairingV2Controller.State.ShowCode)?.let { java.io.File(context.filesDir, "pair-code-v2.txt").writeText(it.code) }
                    Log.i(WaveApplication.LOG_TAG, "debug v2 pair accept state=${next?.let { it::class.simpleName }} message=${(next as? xyz.mooma0750.wave.connect2.PairingV2Controller.State.Failed)?.message}")
                }
                "v2_pair_code" -> {
                    val pairing = container.pairingV2 ?: return@launch
                    pairing.enterCode(intent.getStringExtra("code").orEmpty())
                    val next = kotlinx.coroutines.withTimeoutOrNull(20_000) { pairing.state.first { it is PairingV2Controller.State.Done || it is PairingV2Controller.State.Failed || (it as? PairingV2Controller.State.EnterCode)?.message != null } }
                    Log.i(WaveApplication.LOG_TAG, "debug v2 pair code state=${next?.let { it::class.simpleName }} message=${(next as? xyz.mooma0750.wave.connect2.PairingV2Controller.State.Failed)?.message}")
                }
                "v2_pair_continue" -> {
                    val pairing = container.pairingV2 ?: return@launch
                    pairing.confirmOnB()
                    val next = kotlinx.coroutines.withTimeoutOrNull(40_000) { pairing.state.first { it is PairingV2Controller.State.Done || it is PairingV2Controller.State.Failed } }
                    Log.i(WaveApplication.LOG_TAG, "debug v2 pair continue state=${next?.let { it::class.simpleName }} message=${(next as? xyz.mooma0750.wave.connect2.PairingV2Controller.State.Failed)?.message}")
                    container.connectV2?.summary { Log.i(WaveApplication.LOG_TAG, "debug v2 pair continue $it") }
                    kotlinx.coroutines.delay(500)
                }
                "v2_transfer" -> {
                    val manager = container.connectV2 ?: return@launch
                    player.controller() // 再生サービスを起動しておく
                    manager.relay.acquire()
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(15_000) { manager.relay.status.first { it == RelayClientV2.Status.Connected } }
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
                // 中継の家を作り直した後に、この端末の家の記録を消す（家を作った端末は中継に断られないので、画面の「消す」が出ない）
                "v2_forget" -> container.connectV2?.forgetHousehold()
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
                        val text = HandoffCodec.encode(captured)
                        when (val decoded = HandoffCodec.decode(text, handoffId)) {
                            is HandoffDecodeResult.Ok -> {
                                val index = decoded.snapshot.items.indexOfFirst { it.itemId == decoded.snapshot.currentItemId }
                                Log.i(WaveApplication.LOG_TAG, "debug handoff bytes=${text.length} items=${decoded.snapshot.items.size} index=$index positionS=${decoded.snapshot.positionMs / 1000} listenedS=${decoded.snapshot.listenedMs / 1000} sent=${decoded.snapshot.historySent} repeat=${decoded.snapshot.repeat}")
                                Log.i(WaveApplication.LOG_TAG, "debug handoff adopt=${endpoint.adopt(decoded.snapshot, container.session.loginGenerationId())}")
                            }
                            else -> Log.i(WaveApplication.LOG_TAG, "debug handoff decode=$decoded")
                        }
                    }
                }
                // プレイリストの書き込みの確認（DebugReceiver からだけ）。作った ID はメモリに置くだけで出さない
                "playlist_create" ->
                    when (val result = container.music.createPlaylist(intent.getStringExtra("title") ?: "wave 確認用")) {
                        is WriteResult.Done -> {
                            createdPlaylistId = result.value
                            Log.i(WaveApplication.LOG_TAG, "debug playlist created")
                        }
                        else -> Log.i(WaveApplication.LOG_TAG, "debug playlist create result=$result")
                    }
                // 名前の一致する自分のプレイリストを消す（確かめで作ったものの後片付け。Windows 版の playlist-delete と同じ）
                "nav_log" -> navLogRequests.tryEmit(Unit)
                "playlist_delete_named" -> {
                    val title = intent.getStringExtra("title") ?: "wave 確認用"
                    val playlists = (container.music.libraryPlaylists() as? CallResult.Ok)?.value.orEmpty().filter { it.editable && it.title == title }
                    playlists.forEach { Log.i(WaveApplication.LOG_TAG, "debug playlist delete result=${container.music.deletePlaylist(it.playlistId)::class.simpleName}") }
                    Log.i(WaveApplication.LOG_TAG, "debug playlist delete matched=${playlists.size}")
                    container.libraryCache.clear()
                    container.onPlaylistsChanged()
                }
                "playlist_add" -> {
                    val id = createdPlaylistId
                    val videoId = Media3QueuePlayer.songOf(player.controller().currentMediaItem)?.videoId
                    if (id != null && videoId != null) Log.i(WaveApplication.LOG_TAG, "debug playlist add result=${container.music.addToPlaylist(id, videoId)}")
                }
                "playlist_remove" -> {
                    val id = createdPlaylistId
                    val videoId = Media3QueuePlayer.songOf(player.controller().currentMediaItem)?.videoId
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
                // ミニプレーヤーの再生ボタンと同じ呼び方
                "play" -> player.controller().playFromButton()
                "queue_digest" ->
                    player.controller().run {
                        val ids = (0 until mediaItemCount).map { Media3QueuePlayer.songOf(getMediaItemAt(it))?.videoId.orEmpty() }
                        // 曲の ID そのものは出さず、並びと集合のハッシュだけ
                        Log.i(WaveApplication.LOG_TAG, "debug queue size=${ids.size} index=$currentMediaItemIndex head=${ids.take(currentMediaItemIndex + 1).hashCode()} order=${ids.hashCode()} set=${ids.sorted().hashCode()} repeat=$repeatMode shuffleMode=$shuffleModeEnabled")
                    }
                "toggle_like" -> player.likeState.value.let { state -> state.videoId?.let { player.setLiked(it, state.status != LikeStatus.Like) } }
                "like_state" -> player.likeState.value.let { Log.i(WaveApplication.LOG_TAG, "debug like status=${it.status} pending=${it.pending} message=${it.message != null}") }
                "repeat" -> {
                    player.cycleRepeatMode()
                    Log.i(WaveApplication.LOG_TAG, "debug repeat=${player.controller().repeatMode}")
                }
                // 知らせ（スナックバー）の見た目の確認。表示だけで、何も書き込まない
                "notice" -> xyz.mooma0750.wave.ui.Notices.show(intent.getStringExtra("text") ?: "wave debug notice")
                // Claude の中継への接続（QR の中身と同じ waveai://pair?u=&t=&n=）と、頼みの確認（答えの長さと道具の行だけを記録する）
                "ai_pair" -> intent.getStringExtra("uri")?.let { uri -> xyz.mooma0750.wave.settings.AiBridgeConfig.parse(uri)?.let { container.settings.setAiBridge(it) }.also { Log.i(WaveApplication.LOG_TAG, "debug ai pair ok=${it != null}") } }
                "ai_ask" ->
                    intent.getStringExtra("text")?.let { text ->
                        val chat = container.aiChat
                        chat.send(text)
                        kotlinx.coroutines.delay(500)
                        val done = chat.state.first { !it.running }
                        done.messages.forEach { message ->
                            Log.i(
                                WaveApplication.LOG_TAG,
                                "debug ai " +
                                    when (message) {
                                        is xyz.mooma0750.wave.ai.AiMessage.User -> "user"
                                        is xyz.mooma0750.wave.ai.AiMessage.Assistant -> "assistant chars=${message.text.length} stamps=${xyz.mooma0750.wave.core.ai.AiText.timestamps(message.text).map { it.label }} synced=${message.videoId != null}"
                                        is xyz.mooma0750.wave.ai.AiMessage.Action -> "action ${message.text}"
                                        is xyz.mooma0750.wave.ai.AiMessage.Songs -> "songs n=${message.songs.size}"
                                        is xyz.mooma0750.wave.ai.AiMessage.Failure -> "failure ${message.text}"
                                    },
                            )
                        }
                    }
                // Claude の会話の履歴: 一覧（件数・題名の長さ・発言の数）・新しい会話・開く（index）・消す（index）・答えの中の時刻へ飛ぶ（index 番目の時刻）
                "ai_history" -> container.aiChat.history.value.forEachIndexed { i, c -> Log.i(WaveApplication.LOG_TAG, "debug ai history $i title_chars=${c.title.length} messages=${c.messages.size} current=${c.id == container.aiChat.state.value.conversationId}") }.also { Log.i(WaveApplication.LOG_TAG, "debug ai history n=${container.aiChat.history.value.size}") }
                "ai_new" -> container.aiChat.clear()
                "ai_open" -> container.aiChat.history.value.getOrNull(intent.getIntExtra("index", 0))?.let { container.aiChat.open(it.id) }.also { Log.i(WaveApplication.LOG_TAG, "debug ai open messages=${container.aiChat.state.value.messages.size}") }
                "ai_delete" -> container.aiChat.history.value.getOrNull(intent.getIntExtra("index", 0))?.let { container.aiChat.delete(it.id) }
                "ai_seek" -> {
                    val message = container.aiChat.state.value.messages.filterIsInstance<xyz.mooma0750.wave.ai.AiMessage.Assistant>().lastOrNull { xyz.mooma0750.wave.core.ai.AiText.timestamps(it.text).isNotEmpty() }
                    val stamp = message?.let { xyz.mooma0750.wave.core.ai.AiText.timestamps(it.text).getOrNull(intent.getIntExtra("index", 0)) }
                    Log.i(WaveApplication.LOG_TAG, "debug ai seek stamp=${stamp?.label} synced=${message?.videoId != null} playing=${message?.videoId == container.aiChat.playingVideoId.value}")
                    if (message?.videoId != null && stamp != null) container.aiChat.seek(message.videoId, stamp.positionMs)
                }
                // 登録したアーティストの一覧（件数と、ページが読めるか）と、新作の確かめを1回走らせる
                "subscriptions" ->
                    when (val result = container.music.subscribedArtists()) {
                        is CallResult.Ok -> Log.i(WaveApplication.LOG_TAG, "debug subscriptions n=${result.value.size} ids=${result.value.take(3).map { it.browseId.take(6) }}")
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug subscriptions failed ${result.kind} renderers=${container.music.lastRendererNames}")
                    }
                "releases_check" -> Log.i(WaveApplication.LOG_TAG, "debug releases done=${xyz.mooma0750.wave.releases.ReleaseWatcher.check(context, container)}")
                "artist_probe" ->
                    when (val result = container.music.artist(intent.getStringExtra("browse_id") ?: "")) {
                        is CallResult.Ok -> result.value.albumShelves.forEach { shelf -> shelf.albums.forEach { Log.i(WaveApplication.LOG_TAG, "debug artist album ${it.browseId} ${it.title} | ${it.subtitle}") } }
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug artist failed ${result.kind}")
                    }
                "playlist_top" ->
                    when (val result = container.music.playlist(intent.getStringExtra("playlist_id") ?: "LM")) {
                        is CallResult.Ok -> result.value.songs.take(intent.getIntExtra("n", 3)).forEach { Log.i(WaveApplication.LOG_TAG, "debug playlist top ${it.title} | ${it.subtitle}") }
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug playlist top failed ${result.kind}")
                    }
                "album_probe" ->
                    when (val result = container.music.album(intent.getStringExtra("browse_id") ?: "")) {
                        is CallResult.Ok -> Log.i(WaveApplication.LOG_TAG, "debug album artists=${result.value.artists} subtitle=${result.value.subtitle} songs=${result.value.songs.map { s -> s.artists }}")
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug album failed ${result.kind}")
                    }
                "open_artist" -> intent.getStringExtra("browse_id")?.let { navigationChannel.trySend(Screen.Artist(it, "")) }
                "open_album" -> intent.getStringExtra("browse_id")?.let { navigationChannel.trySend(Screen.Album(it, "")) }
                // ポッドキャストの読み取りを確かめる（件数と形だけを記録する。題などの中身は出さない）
                "podcast_probe" -> {
                    val music = container.music
                    val library = music.libraryPodcasts()
                    Log.i(WaveApplication.LOG_TAG, "debug podcast library=${(library as? CallResult.Ok)?.value?.size ?: library} renderers=${music.lastRendererNames}")
                    when (val page = music.podcast(xyz.mooma0750.wave.core.music.MusicRepository.NEW_EPISODES_BROWSE)) {
                        is CallResult.Ok -> Log.i(WaveApplication.LOG_TAG, "debug podcast new episodes=${page.value.episodes.size} shows=${page.value.episodes.map { it.song.artists }.distinct().size} showIds=${page.value.episodes.count { it.showBrowseId != null }} saved=${page.value.saved}")
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug podcast new episodes failed ${page.kind} renderers=${music.lastRendererNames}")
                    }
                    val show = (library as? CallResult.Ok)?.value?.firstOrNull()?.browseId ?: intent.getStringExtra("browse_id") ?: "MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv"
                    when (val page = music.podcast(show)) {
                        is CallResult.Ok -> Log.i(WaveApplication.LOG_TAG, "debug podcast page episodes=${page.value.episodes.size} saved=${page.value.saved} progress=${page.value.episodes.count { it.progressPercent != null }} more=${page.value.continuation != null}")
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug podcast page failed ${page.kind} renderers=${music.lastRendererNames}")
                    }
                    val search = music.searchEpisodes("rebuild")
                    Log.i(WaveApplication.LOG_TAG, "debug podcast search episodes=${(search as? CallResult.Ok)?.value?.items?.size ?: search} shows=${(music.searchPodcasts("rebuild") as? CallResult.Ok)?.value?.items?.size}")
                }
                // 確認用: 番組を保存して（または外して）、ライブラリと新しいエピソードの件数を記録する
                "podcast_save" -> {
                    val music = container.music
                    val saved = intent.getBooleanExtra("saved", true)
                    val playlistId = (intent.getStringExtra("browse_id") ?: "MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv").removePrefix("MPSP")
                    val result = music.setPodcastSaved(playlistId, saved)
                    val library = music.libraryPodcasts()
                    Log.i(WaveApplication.LOG_TAG, "debug podcast save=$saved result=${result::class.simpleName} library=${(library as? CallResult.Ok)?.value?.size}")
                }
                "open_podcast" -> intent.getStringExtra("browse_id")?.let { navigationChannel.trySend(Screen.Podcast(it, "")) }
                // 確認用: RSS の番組を目録で探し、最初の番組のフィードを読む。play なら最新のエピソードを流す（件数と形だけを記録する）
                "feed_probe" -> {
                    val feeds = container.podcastFeeds
                    val found = feeds.search(intent.getStringExtra("query") ?: "rebuild")
                    Log.i(WaveApplication.LOG_TAG, "debug feed search=${found?.size} rss=${found?.count { xyz.mooma0750.wave.core.music.Feed.isFeedShow(it.browseId) }}")
                    val url = found?.firstOrNull()?.browseId?.let(xyz.mooma0750.wave.core.music.Feed::feedUrlOf) ?: return@launch
                    val started = android.os.SystemClock.elapsedRealtime()
                    val read = feeds.show(url)
                    Log.i(WaveApplication.LOG_TAG, "debug feed read ms=${android.os.SystemClock.elapsedRealtime() - started}")
                    when (val show = read) {
                        is xyz.mooma0750.wave.core.music.FeedResult.Ok -> {
                            val page = show.show.toPage(saved = null)
                            Log.i(WaveApplication.LOG_TAG, "debug feed episodes=${page.episodes.size} durations=${page.episodes.count { it.durationText != null }} dates=${page.episodes.count { it.publishedText != null }} image=${page.thumbnailUrl != null}")
                            if (intent.getBooleanExtra("play", false)) player.playFrom(page.episodes.map { it.song }, 0, null, null)
                            // save なら登録する（画面の Save と同じ。端末の間の同期を画面に触れずに確かめる、ADR-0013）
                            if (intent.hasExtra("save")) {
                                if (intent.getBooleanExtra("save", true)) container.feedLibrary.subscribe(show.show.subscription) else container.feedLibrary.unsubscribe(show.show.feedUrl)
                                Log.i(WaveApplication.LOG_TAG, "debug feed saved=${container.feedLibrary.state.value.isSubscribed(show.show.feedUrl)}")
                            }
                        }
                        is xyz.mooma0750.wave.core.music.FeedResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug feed failed ${show.failure}")
                    }
                }
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

/** Keystore の操作を種類ごとに試す（debug だけ）。 */
private object KeystoreProbe {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "keystore-probe").apply { isDaemon = true } }

    fun run(
        container: AppContainer,
        op: String,
        count: Int,
    ): String {
        val keys = xyz.mooma0750.wave.connect2.DeviceKeysV2(container.deviceIdentity, java.io.File(container.appContext.noBackupFilesDir, "connect-v2-kx.bin"))
        val action: () -> Unit =
            when (op) {
                "ecdh" -> {
                    {
                        val peer = xyz.mooma0750.wave.handoff.crypto.WaveCrypto.generateEphemeralKeyPair().public as java.security.interfaces.ECPublicKey
                        keys.dh(peer)
                    }
                }
                "sign" -> {
                    { container.deviceIdentity.sign("wave-probe", ByteArray(64)) }
                }
                "aes" -> {
                    {
                        javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
                            init(javax.crypto.Cipher.ENCRYPT_MODE, probeKey())
                            doFinal(ByteArray(64))
                        }
                    }
                }
                else -> return "unknown"
            }
        var slowest = 0L
        for (i in 1..count) {
            val started = android.os.SystemClock.elapsedRealtime()
            val future = executor.submit(action)
            try {
                future.get(3, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                return "stuck_at=$i done=${i - 1} slowest_ms=$slowest"
            } catch (error: java.util.concurrent.ExecutionException) {
                return "failed_at=$i type=${error.cause?.let { it::class.simpleName }}"
            }
            slowest = maxOf(slowest, android.os.SystemClock.elapsedRealtime() - started)
        }
        return "ok done=$count slowest_ms=$slowest"
    }

    private fun probeKey(): javax.crypto.SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("wave-probe-aes", null) as? javax.crypto.SecretKey)?.let { return it }
        return javax.crypto.KeyGenerator
            .getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    android.security.keystore.KeyGenParameterSpec
                        .Builder("wave-probe-aes", android.security.keystore.KeyProperties.PURPOSE_ENCRYPT)
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
    }
}
