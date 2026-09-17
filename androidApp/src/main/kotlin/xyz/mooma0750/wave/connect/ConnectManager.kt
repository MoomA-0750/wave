package xyz.mooma0750.wave.connect

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.session.WaveSession
import xyz.mooma0750.wave.handoff.Envelope
import xyz.mooma0750.wave.handoff.EnvelopeType
import xyz.mooma0750.wave.handoff.HandoffBody
import xyz.mooma0750.wave.handoff.HandoffCodec
import xyz.mooma0750.wave.handoff.HandoffGiver
import xyz.mooma0750.wave.handoff.HandoffTaker
import xyz.mooma0750.wave.handoff.RelayMessage
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.Pairing
import xyz.mooma0750.wave.playback.HandoffHost
import java.util.Base64
import java.util.UUID

/**
 * 端末間の受け渡しの調停役（Phase 6、ADR-0009）。中継からの封筒を開き、引き取る側・引き取られる側の状態機械に渡し、
 * 再生サービスの入口（HandoffHost）を Main で呼ぶ。信頼の根拠は PairStore（中継の登録は見ない）。
 *
 * 状態機械（taker・giver）は flowLock の中でだけ触る。snapshot の取り込みの間も持ち続け、期限のタイマーと行き違わない。
 * 画面から呼ぶ入口（takeOver・unpair）は appScope で動かし、画面を離れても中継の参照を戻す。
 * 中継の返事（offline など）はペアの鍵で認証されていないので、再生を始める・再開する根拠にしない（ADR-0008）。
 */
class ConnectManager(
    private val identity: DeviceIdentity,
    private val pairs: PairStore,
    val relay: RelayClient,
    private val session: WaveSession,
    private val host: HandoffHost,
    private val scope: CoroutineScope,
    /** 手元の画面が前面にあるか（引き取った曲の再生を始めてよいか）。 */
    private val uiInForeground: () -> Boolean,
    /** 再生サービスを起動しておく（引き取る前に）。 */
    private val ensurePlaybackService: suspend () -> Unit,
) {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()
    private val taker = HandoffTaker(nowMs = SystemClock::elapsedRealtime)
    private val giver = HandoffGiver(nowMs = SystemClock::elapsedRealtime)
    private val flowLock = Mutex()

    /** つながったままの間に解除が残ったとき、送り直しの繰り返しを始め直す。 */
    private val unpairRetryKick = MutableStateFlow(0)

    /** 相手から UnpairAck を受けた端末 ID（解除の画面の結果を待つ）。 */
    private val unpairAcks = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 画面に出す短い文言。 */
    val events: SharedFlow<String> = mutableEvents

    private val mutablePendingTakeOver = MutableStateFlow<String?>(null)

    /** 引き取りを頼んで返事を待っている相手の名前（画面の「頼んでいます」とボタンの無効化）。 */
    val pendingTakeOver: StateFlow<String?> = mutablePendingTakeOver

    /** 中継からの、ペアリングの第1手（A の画面が受ける）。 */
    val pairHellos = MutableSharedFlow<RelayMessage.PairHello>(extraBufferCapacity = 2)

    init {
        scope.launch {
            relay.incoming.collect { message ->
                // 1通の失敗で受信の処理を終わらせない（以後の封筒・Cancel を受けられなくなる）
                try {
                    when (message) {
                        is RelayMessage.Delivered -> onEnvelope(message.envelope)
                        is RelayMessage.PairHello -> pairHellos.tryEmit(message)
                        is RelayMessage.Error -> onRelayError(message)
                        else -> Unit
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Log.i(WaveApplication.LOG_TAG, "connect receive failed type=${error::class.simpleName}")
                }
            }
        }
        // 中継につながっている間、相手が受け取っていない解除を知らせ直す。相手がつながるのは、こちらがつながった後のこともあるので、
        // つながった時だけでなく、残っている間は一定の間隔で送り直す（中継は相手がつながったことを知らせない）
        scope.launch {
            combine(relay.status, unpairRetryKick) { status, _ -> status }.collectLatest { status ->
                while (status == RelayClient.Status.Connected) {
                    val pending = runCatching { pairs.unpairedPending() }.getOrDefault(emptyList())
                    if (pending.isEmpty()) break
                    pending.forEach { runCatching { sendBody(it, EnvelopeType.Unpair, null, HandoffBody.Unpair) } }
                    delay(UNPAIR_RETRY_INTERVAL_MS)
                }
            }
        }
    }

    /** 手元の端末（B）: peerDeviceId の端末から引き取る。@return 要求を送れたか（結果は events で知らせる） */
    suspend fun takeOver(peerDeviceId: String): Boolean = scope.async { requestTakeOver(peerDeviceId) }.await()

    /**
     * ペアリングを解除する。手元では一覧からすぐ消し、相手に Unpair を送る。相手の UnpairAck を受けるまで鍵を残し、
     * 中継につながっている間は送り直す。中継からの締め出し（revoke）は別の操作（決定 K-11）。
     */
    suspend fun unpair(peerDeviceId: String) = scope.async { requestUnpair(peerDeviceId) }.await()

    private suspend fun requestTakeOver(peerDeviceId: String): Boolean {
        val pair = pairs.find(peerDeviceId) ?: return false
        val proof = accountProof(pair) ?: return notify("書き込み先のアカウントを確かめられないので、引き取れません")
        val handoffId = UUID.randomUUID().toString()
        val abandoned =
            flowLock.withLock {
                if (!taker.request(peerDeviceId, handoffId)) null else taker.abandonedFor(peerDeviceId).orEmpty()
            } ?: return notify("ほかの引き取りを待っています")
        mutablePendingTakeOver.value = pair.peerName
        relay.acquire()
        var releaseHere = true
        try {
            val status = awaitRelay()
            if (status != RelayClient.Status.Connected) return abandon(PairingController.relayMessage(status))
            ensurePlaybackService()
            val request = HandoffBody.Request(handoffId, proof.hmac, abandoned.ifEmpty { null })
            if (!sendBody(pair, EnvelopeType.Request, handoffId, request)) return abandon("中継に届きませんでした")
            Log.i(WaveApplication.LOG_TAG, "connect request sent")
            releaseHere = false
            scope.launch {
                try {
                    delay(HandoffTaker.DEFAULT_TIMEOUT_MS + 500)
                    val expired = flowLock.withLock { taker.expire().also { syncPending() } }
                    if (expired != null) {
                        // 相手が一時停止したままにならないよう、落ちる場合に備えて2回送る（次の要求にも載せる）
                        pairs.find(expired.peerId)?.let { runCatching { sendCancelTwice(it, expired.handoffId, "timeout") } }
                        notify("${pair.peerName} から返事がありませんでした（再生中か、一時停止から9分以内のときに引き取れます）")
                    }
                    // 取り込んだ後の Ack の再送（3秒後）を待ってから離す
                    delay(ACK_RESEND_GRACE_MS)
                } finally {
                    relay.release()
                }
            }
            return true
        } catch (cancellation: CancellationException) {
            flowLock.withLock { resetTaker() }
            throw cancellation
        } catch (error: Exception) {
            Log.i(WaveApplication.LOG_TAG, "connect request failed type=${error::class.simpleName}")
            return abandon("引き取りを始められませんでした")
        } finally {
            if (releaseHere) relay.release()
        }
    }

    private suspend fun abandon(message: String): Boolean {
        flowLock.withLock { resetTaker() }
        return notify(message)
    }

    private fun resetTaker() {
        if (taker.state is HandoffTaker.State.Requested) taker.reset()
        syncPending()
    }

    private fun syncPending() {
        if (!taker.busy) mutablePendingTakeOver.value = null
    }

    private suspend fun sendCancelTwice(
        pair: PairStore.PairRecord,
        handoffId: String,
        reason: String,
    ) {
        sendBody(pair, EnvelopeType.Cancel, handoffId, HandoffBody.Cancel(handoffId, reason))
        delay(CANCEL_RESEND_DELAY_MS)
        sendBody(pair, EnvelopeType.Cancel, handoffId, HandoffBody.Cancel(handoffId, reason))
    }

    private suspend fun requestUnpair(peerDeviceId: String) {
        val pair = pairs.find(peerDeviceId) ?: return
        pairs.markUnpaired(peerDeviceId)
        relay.acquire()
        try {
            val acknowledged =
                awaitRelay() == RelayClient.Status.Connected &&
                    coroutineScope {
                        // 相手が受け取ったと返すまで、鍵を消さない（中継が offline を返さないことは、届いた証拠にならない）
                        val ack = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(UNPAIR_ACK_WAIT_MS) { unpairAcks.first { it == peerDeviceId } } }
                        if (!sendBody(pair, EnvelopeType.Unpair, null, HandoffBody.Unpair)) {
                            ack.cancel()
                            false
                        } else {
                            ack.await() != null
                        }
                    }
            if (acknowledged) {
                notify("${pair.peerName} とのペアリングを解除しました")
            } else {
                unpairRetryKick.value++
                notify("${pair.peerName} とのペアリングを解除しました。${pair.peerName} がつながっていないので、次に両方がつながったときに知らせます（すぐに外すには ${pair.peerName} でも解除してください）")
            }
        } finally {
            relay.release()
        }
    }

    private suspend fun awaitRelay(): RelayClient.Status? =
        withTimeoutOrNull(RELAY_CONNECT_TIMEOUT_MS) { relay.status.first { it != RelayClient.Status.Connecting && it != RelayClient.Status.Disconnected } }

    /**
     * 中継の返事のエラー。offline には、どの封筒が届かなかったかが付く。認証されていないので、待ちを取り下げて知らせるだけにする。
     * 渡す側（Snapshot の offline）で再開すると、中継が snapshot を届けたうえで offline を偽れば、2台で鳴る
     */
    private suspend fun onRelayError(error: RelayMessage.Error) {
        Log.i(WaveApplication.LOG_TAG, "connect relay error code=${error.code.take(32)} type=${error.type}")
        val to = error.to
        if (error.code != "offline" || to == null) return
        flowLock.withLock {
            when (error.type) {
                EnvelopeType.Request ->
                    if (taker.onUndeliverable(to, error.handoffId)) {
                        syncPending()
                        val name = pairs.find(to)?.peerName ?: "相手の端末"
                        notify("$name は中継につながっていません（再生中か、一時停止から9分以内のときに引き取れます）")
                    }
                EnvelopeType.Snapshot ->
                    if ((giver.state as? HandoffGiver.State.Given)?.let { it.peerId == to && it.handoffId == error.handoffId } == true) {
                        notify("引き取ろうとした端末に届かなかったかもしれません（一時停止しています）")
                    }
                else -> Unit
            }
        }
    }

    private suspend fun onEnvelope(envelope: Envelope) {
        val pair = pairs.find(envelope.from, includeInactive = true)
        if (pair == null) {
            Log.i(WaveApplication.LOG_TAG, "connect envelope from unknown device dropped")
            return
        }
        val plaintext =
            EnvelopeSealer.open(
                envelope,
                receiveKey = unb64.decode(pair.receiveKey),
                self = identity.deviceId,
                expectedFrom = pair.peerDeviceId,
                lastReceivedSeq = pair.lastReceivedSeq,
            )
        if (plaintext == null) {
            Log.i(WaveApplication.LOG_TAG, "connect envelope rejected type=${envelope.type}")
            return
        }
        if (!pairs.acceptReceivedSeq(pair.peerDeviceId, envelope.seq)) return
        val body = HandoffBody.decode(plaintext.decodeToString()) ?: return
        Log.i(WaveApplication.LOG_TAG, "connect received type=${envelope.type}")
        when (body) {
            HandoffBody.Unpair -> onUnpair(pair)
            HandoffBody.UnpairAck ->
                if (pair.unpaired && pairs.removeUnpaired(pair.peerDeviceId, pair.sendKey)) {
                    unpairAcks.tryEmit(pair.peerDeviceId)
                    Log.i(WaveApplication.LOG_TAG, "connect unpair acknowledged")
                }
            else ->
                when {
                    // 手元では解除済み: 相手はまだ知らないので、知らせ直す（受信の処理を止めないよう別に送る）
                    pair.unpaired -> scope.launch { runCatching { sendBody(pair, EnvelopeType.Unpair, null, HandoffBody.Unpair) } }
                    // 相手が解除した後に届いた古い封筒は捨てる
                    !pair.active -> Unit
                    else -> flowLock.withLock { onActiveBody(pair, envelope, body) }
                }
        }
    }

    private suspend fun onUnpair(pair: PairStore.PairRecord) {
        // 手元でも解除済みなら、こちらの Unpair への Ack は待ったまま、相手の Unpair には Ack を返す
        if (pair.active) {
            pairs.markRemovedByPeer(pair.peerDeviceId, System.currentTimeMillis())
            notify("${pair.peerName} がペアリングを解除しました")
        }
        sendBody(pair, EnvelopeType.UnpairAck, null, HandoffBody.UnpairAck)
    }

    private suspend fun onActiveBody(
        pair: PairStore.PairRecord,
        envelope: Envelope,
        body: HandoffBody,
    ) {
        when (body) {
            is HandoffBody.Request -> onRequest(pair, envelope, body)
            is HandoffBody.Snapshot -> onSnapshot(pair, envelope, body)
            is HandoffBody.Ack ->
                if (giver.onAck(pair.peerDeviceId, body)) {
                    onMain { host.endpoint?.markHandedOff() }
                    notify("${pair.peerName} が引き取りました")
                }
            is HandoffBody.Cancel ->
                if (giver.onCancel(pair.peerDeviceId, body)) {
                    onMain { host.endpoint?.resumeAfterCancel() }
                } else if (taker.onCancel(pair.peerDeviceId, body)) {
                    syncPending()
                    Log.i(WaveApplication.LOG_TAG, "connect refused reason=${body.reason.take(32)}")
                    notify(
                        when (body.reason) {
                            "account" -> "${pair.peerName} は別のアカウントでログインしています"
                            "busy" -> "${pair.peerName} も引き取りを頼んでいます。少し待ってもう一度試してください"
                            else -> "${pair.peerName} には引き取れる再生がありません"
                        },
                    )
                }
            HandoffBody.Unpair, HandoffBody.UnpairAck -> Unit
        }
    }

    private suspend fun onRequest(
        pair: PairStore.PairRecord,
        envelope: Envelope,
        body: HandoffBody.Request,
    ) {
        if (envelope.handoffId != body.handoffId) return
        // 相手が前の要求を取り込まなかったと言っている（Cancel が落ちた）: その要求で一時停止したままなら取り下げる
        val released = body.abandonedHandoffId?.let { giver.onCancel(pair.peerDeviceId, HandoffBody.Cancel(it, "abandoned")) } == true
        if (released) onMain { host.endpoint?.forgetHandedOff() }
        val proof = accountProof(pair)
        val accountMatches = proof != null && xyz.mooma0750.wave.handoff.crypto.WaveCrypto.constantTimeEquals(proof.hmac.encodeToByteArray(), body.accountHmac.encodeToByteArray())
        // こちらも引き取りを頼んでいる・取り込んでいる間は応じない（互いに同時に引き取ると、両方が相手のキューで鳴る）
        val busy = taker.busy
        val canGive = !busy && accountMatches && onMain { host.endpoint?.hasQueue() == true }
        val decision = giver.onRequest(pair.peerDeviceId, body, canGive)
        if (released && decision != HandoffGiver.RequestDecision.Capture) onMain { host.endpoint?.resumeAfterCancel() }
        when (decision) {
            HandoffGiver.RequestDecision.Capture -> {
                var sent = false
                var captured = false
                try {
                    val snapshot = proof?.let { onMain { host.endpoint?.capture(body.handoffId, it.generationId) } }
                    captured = snapshot != null
                    if (snapshot == null || proof == null) {
                        sendBody(pair, EnvelopeType.Cancel, body.handoffId, HandoffBody.Cancel(body.handoffId, "no_queue"))
                        return
                    }
                    sent = sendBody(pair, EnvelopeType.Snapshot, body.handoffId, HandoffBody.Snapshot(proof.hmac, HandoffCodec.encode(snapshot)))
                    if (sent) Log.i(WaveApplication.LOG_TAG, "connect snapshot sent items=${snapshot.items.size}")
                } finally {
                    // 送り出せなかった（例外・送信の失敗・キューが無い）: 窓を閉じ、一時停止したなら再開する
                    if (!sent && giver.onUndeliverable(pair.peerDeviceId, body.handoffId) && captured) onMain { host.endpoint?.resumeAfterCancel() }
                }
                scope.launch {
                    delay(HandoffGiver.DEFAULT_WINDOW_MS + 500)
                    if (flowLock.withLock { giver.expire() }) notify("引き取られなかったかもしれません（一時停止しています）")
                }
            }
            HandoffGiver.RequestDecision.Duplicate -> Unit
            HandoffGiver.RequestDecision.Refuse -> {
                val reason =
                    when {
                        busy -> "busy"
                        !accountMatches -> "account"
                        else -> "no_queue"
                    }
                sendBody(pair, EnvelopeType.Cancel, body.handoffId, HandoffBody.Cancel(body.handoffId, reason))
            }
        }
    }

    private suspend fun onSnapshot(
        pair: PairStore.PairRecord,
        envelope: Envelope,
        body: HandoffBody.Snapshot,
    ) {
        val proof = accountProof(pair)
        val accountMatches = proof != null && xyz.mooma0750.wave.handoff.crypto.WaveCrypto.constantTimeEquals(proof.hmac.encodeToByteArray(), body.accountHmac.encodeToByteArray())
        // 状態機械は判断と同時に待ちを取り下げるので、Cancel の宛先は先に取っておく。封筒の handoffId は AAD で認証済み
        val waiting = (taker.state as? HandoffTaker.State.Requested)?.takeIf { it.peerId == pair.peerDeviceId }
        val cancelTarget = envelope.handoffId ?: waiting?.handoffId
        val alreadyMine = envelope.handoffId != null && envelope.handoffId == taker.currentHandoffId && waiting == null
        when (val decision = taker.onSnapshot(pair.peerDeviceId, body, accountMatches)) {
            is HandoffTaker.SnapshotDecision.Adopt -> {
                val handoffId = decision.snapshot.handoffId
                val adopted =
                    try {
                        // 確かめたアカウントの世代で取り込む（確かめた後に切り替わっていたら取り込まない）
                        uiInForeground() && proof != null && onMain { host.endpoint?.adopt(decision.snapshot, proof.generationId) == true }
                    } catch (cancellation: CancellationException) {
                        taker.onAdoptFailed(handoffId)
                        throw cancellation
                    } catch (error: Exception) {
                        Log.i(WaveApplication.LOG_TAG, "connect adopt failed type=${error::class.simpleName}")
                        false
                    }
                if (adopted) {
                    taker.onAdopted(handoffId)
                    // Ack が中継で落ちた場合に備えて、新しい seq でもう一度送る（相手は handoffId ごとに冪等）。受信の処理は止めない
                    scope.launch {
                        runCatching { sendBody(pair, EnvelopeType.Ack, handoffId, HandoffBody.Ack(handoffId)) }
                        delay(ACK_RESEND_DELAY_MS)
                        runCatching { sendBody(pair, EnvelopeType.Ack, handoffId, HandoffBody.Ack(handoffId)) }
                    }
                    notify("${pair.peerName} から引き取りました")
                } else {
                    taker.onAdoptFailed(handoffId)
                    scope.launch { runCatching { sendCancelTwice(pair, handoffId, "not_adopted") } }
                    notify("引き取れませんでした（wave を前面に出してもう一度試してください）")
                }
            }
            is HandoffTaker.SnapshotDecision.Ignore -> {
                Log.i(WaveApplication.LOG_TAG, "connect snapshot ignored reason=${decision.reason}")
                // 待っていない snapshot でも、ペアの端末が一時停止して送ってきたものなので、取り込んだ要求でなければ Cancel を返して再開させる
                // （中継が要求を遅らせて届けた・こちらのプロセスが入れ替わった場合も、相手を止めたままにしない）
                if (!alreadyMine) cancelTarget?.let { sendBody(pair, EnvelopeType.Cancel, it, HandoffBody.Cancel(it, decision.reason)) }
                if (decision.reason == "account_mismatch") notify("${pair.peerName} は別のアカウントでログインしています")
            }
            is HandoffTaker.SnapshotDecision.Incompatible -> {
                cancelTarget?.let { sendBody(pair, EnvelopeType.Cancel, it, HandoffBody.Cancel(it, "incompatible")) }
                notify("${pair.peerName} の wave を更新してください")
            }
        }
        syncPending()
    }

    private suspend fun sendBody(
        pair: PairStore.PairRecord,
        type: EnvelopeType,
        handoffId: String?,
        body: HandoffBody,
    ): Boolean {
        val seq = pairs.reserveSendSeq(pair.peerDeviceId) ?: return false
        val envelope =
            EnvelopeSealer.seal(
                sendKey = unb64.decode(pair.sendKey),
                from = identity.deviceId,
                to = pair.peerDeviceId,
                type = type,
                seq = seq,
                handoffId = handoffId,
                plaintext = HandoffBody.encode(body).encodeToByteArray(),
            )
        return relay.sendEnvelope(envelope)
    }

    private class AccountProof(
        val hmac: String,
        val generationId: Long,
    )

    /** 確認済みのアカウントの、このペアの鍵での HMAC と、確かめたログインの世代。 */
    private suspend fun accountProof(pair: PairStore.PairRecord): AccountProof? {
        val account = session.verifiedAccount() ?: return null
        return AccountProof(b64.encodeToString(Pairing.accountHmac(unb64.decode(pair.accountKey), account.id.reveal())), account.generationId)
    }

    private fun notify(message: String): Boolean {
        mutableEvents.tryEmit(message)
        return false
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    /** 受け渡しの窓の間は接続を保つ（再生サービスが待ち受けの規則を決めるときに見る）。 */
    val holdingConnection: Boolean
        get() = giver.holdingConnection

    private companion object {
        const val RELAY_CONNECT_TIMEOUT_MS = 15_000L
        const val ACK_RESEND_DELAY_MS = 3_000L
        const val CANCEL_RESEND_DELAY_MS = 3_000L
        const val ACK_RESEND_GRACE_MS = 5_000L
        const val UNPAIR_ACK_WAIT_MS = 5_000L
        const val UNPAIR_RETRY_INTERVAL_MS = 60_000L
    }
}
