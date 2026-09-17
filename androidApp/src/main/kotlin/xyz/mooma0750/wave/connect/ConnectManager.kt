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
    private val unpairFlush = Mutex()

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
                when (message) {
                    is RelayMessage.Delivered -> onEnvelope(message.envelope)
                    is RelayMessage.PairHello -> pairHellos.tryEmit(message)
                    is RelayMessage.Error -> onRelayError(message)
                    else -> Unit
                }
            }
        }
        // 中継につながるたびに、相手に届いていない解除を知らせ直す
        scope.launch {
            relay.status.collect { status -> if (status == RelayClient.Status.Connected) scope.launch { flushUnpairs() } }
        }
    }

    /** 手元の端末（B）: peerDeviceId の端末から引き取る。@return 要求を送れたか（結果は events で知らせる） */
    suspend fun takeOver(peerDeviceId: String): Boolean = scope.async { requestTakeOver(peerDeviceId) }.await()

    /**
     * ペアリングを解除する。手元では一覧からすぐ消し、相手に Unpair を送る。届かなければ鍵を残し、
     * 中継につながったとき・相手から封筒が届いたときに送り直す。中継からの締め出し（revoke）は別の操作（決定 K-11）。
     */
    suspend fun unpair(peerDeviceId: String) = scope.async { requestUnpair(peerDeviceId) }.await()

    private suspend fun requestTakeOver(peerDeviceId: String): Boolean {
        val pair = pairs.find(peerDeviceId) ?: return false
        val accountHmac = accountHmac(pair) ?: return notify("書き込み先のアカウントを確かめられないので、引き取れません")
        val handoffId = UUID.randomUUID().toString()
        if (!flowLock.withLock { taker.request(peerDeviceId, handoffId) }) return notify("ほかの引き取りを待っています")
        mutablePendingTakeOver.value = pair.peerName
        relay.acquire()
        var releaseHere = true
        try {
            val status = awaitRelay()
            if (status != RelayClient.Status.Connected) return abandon(PairingController.relayMessage(status))
            ensurePlaybackService()
            if (!sendBody(pair, EnvelopeType.Request, handoffId, HandoffBody.Request(handoffId, accountHmac))) return abandon("中継に届きませんでした")
            Log.i(WaveApplication.LOG_TAG, "connect request sent")
            releaseHere = false
            scope.launch {
                try {
                    delay(HandoffTaker.DEFAULT_TIMEOUT_MS + 500)
                    val expired = flowLock.withLock { taker.expire().also { syncPending() } }
                    if (expired != null) {
                        pairs.find(expired.peerId)?.let { sendBody(it, EnvelopeType.Cancel, expired.handoffId, HandoffBody.Cancel(expired.handoffId, "timeout")) }
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
        if (taker.state !is HandoffTaker.State.Requested && taker.state !is HandoffTaker.State.Adopting) mutablePendingTakeOver.value = null
    }

    private suspend fun requestUnpair(peerDeviceId: String) {
        val pair = pairs.find(peerDeviceId) ?: return
        pairs.markUnpaired(peerDeviceId)
        relay.acquire()
        try {
            val delivered = awaitRelay() == RelayClient.Status.Connected && sendUnpair(pair)
            if (delivered) {
                pairs.remove(peerDeviceId)
                notify("${pair.peerName} とのペアリングを解除しました")
            } else {
                notify("${pair.peerName} とのペアリングを解除しました。${pair.peerName} がつながっていないので、次に両方がつながったときに知らせます（すぐに外すには ${pair.peerName} でも解除してください）")
            }
        } finally {
            relay.release()
        }
    }

    /** Unpair を送り、中継が「オフライン」と返さなければ届いたとみなす。 */
    private suspend fun sendUnpair(pair: PairStore.PairRecord): Boolean =
        coroutineScope {
            val offline =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(UNPAIR_OFFLINE_WAIT_MS) {
                        relay.incoming.first { it is RelayMessage.Error && it.code == "offline" && it.to == pair.peerDeviceId && it.type == EnvelopeType.Unpair }
                    }
                }
            if (!sendBody(pair, EnvelopeType.Unpair, null, HandoffBody.Unpair)) {
                offline.cancel()
                return@coroutineScope false
            }
            offline.await() == null
        }

    private suspend fun flushUnpairs() {
        if (!unpairFlush.tryLock()) return
        try {
            pairs.unpairedPending().forEach { pair ->
                if (relay.status.value != RelayClient.Status.Connected) return
                if (sendUnpair(pair)) {
                    pairs.remove(pair.peerDeviceId)
                    Log.i(WaveApplication.LOG_TAG, "connect unpair delivered later")
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.i(WaveApplication.LOG_TAG, "connect unpair flush failed type=${error::class.simpleName}")
        } finally {
            unpairFlush.unlock()
        }
    }

    private suspend fun awaitRelay(): RelayClient.Status? =
        withTimeoutOrNull(RELAY_CONNECT_TIMEOUT_MS) { relay.status.first { it != RelayClient.Status.Connecting && it != RelayClient.Status.Disconnected } }

    /** 中継の返事のエラー。offline には、どの封筒が届かなかったかが付く。 */
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
                // 渡そうとした相手が抜けていた: 一時停止したままにしない
                EnvelopeType.Snapshot -> if (giver.onUndeliverable(to, error.handoffId)) onMain { host.endpoint?.resumeAfterCancel() }
                else -> Unit
            }
        }
    }

    private suspend fun onEnvelope(envelope: Envelope) {
        val pair = pairs.find(envelope.from, includeUnpaired = true)
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
        if (pair.unpaired) {
            // 手元では解除済み: 相手はまだ知らないので、ここで知らせ直す（受信の処理を止めないよう別に送る）
            if (body == HandoffBody.Unpair) {
                pairs.remove(pair.peerDeviceId)
            } else {
                scope.launch { if (sendUnpair(pair)) pairs.remove(pair.peerDeviceId) }
            }
            return
        }
        flowLock.withLock {
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
                        notify(if (body.reason == "account") "${pair.peerName} は別のアカウントでログインしています" else "${pair.peerName} には引き取れる再生がありません")
                    }
                HandoffBody.Unpair -> {
                    pairs.remove(pair.peerDeviceId)
                    notify("${pair.peerName} がペアリングを解除しました")
                }
            }
        }
    }

    private suspend fun onRequest(
        pair: PairStore.PairRecord,
        envelope: Envelope,
        body: HandoffBody.Request,
    ) {
        if (envelope.handoffId != body.handoffId) return
        val mine = accountHmac(pair)
        val accountMatches = mine != null && xyz.mooma0750.wave.handoff.crypto.WaveCrypto.constantTimeEquals(mine.encodeToByteArray(), body.accountHmac.encodeToByteArray())
        val canGive = accountMatches && onMain { host.endpoint?.hasQueue() == true }
        when (giver.onRequest(pair.peerDeviceId, body, canGive)) {
            HandoffGiver.RequestDecision.Capture -> {
                val snapshot = onMain { host.endpoint?.capture(body.handoffId) }
                if (snapshot == null || mine == null) {
                    if (giver.onUndeliverable(pair.peerDeviceId, body.handoffId)) onMain { host.endpoint?.resumeAfterCancel() }
                    sendBody(pair, EnvelopeType.Cancel, body.handoffId, HandoffBody.Cancel(body.handoffId, "no_queue"))
                    return
                }
                if (!sendBody(pair, EnvelopeType.Snapshot, body.handoffId, HandoffBody.Snapshot(mine, HandoffCodec.encode(snapshot)))) {
                    // 送れなかった: 一時停止したままにしない
                    if (giver.onUndeliverable(pair.peerDeviceId, body.handoffId)) onMain { host.endpoint?.resumeAfterCancel() }
                    return
                }
                Log.i(WaveApplication.LOG_TAG, "connect snapshot sent items=${snapshot.items.size}")
                scope.launch {
                    delay(HandoffGiver.DEFAULT_WINDOW_MS + 500)
                    if (flowLock.withLock { giver.expire() }) notify("引き取られなかったかもしれません（一時停止しています）")
                }
            }
            HandoffGiver.RequestDecision.Duplicate -> Unit
            HandoffGiver.RequestDecision.Refuse -> sendBody(pair, EnvelopeType.Cancel, body.handoffId, HandoffBody.Cancel(body.handoffId, if (accountMatches) "no_queue" else "account"))
        }
    }

    private suspend fun onSnapshot(
        pair: PairStore.PairRecord,
        envelope: Envelope,
        body: HandoffBody.Snapshot,
    ) {
        val mine = accountHmac(pair)
        val accountMatches = mine != null && xyz.mooma0750.wave.handoff.crypto.WaveCrypto.constantTimeEquals(mine.encodeToByteArray(), body.accountHmac.encodeToByteArray())
        // 状態機械は判断と同時に待ちを取り下げるので、Cancel の宛先は先に取っておく。封筒の handoffId は AAD で認証済み
        val waiting = (taker.state as? HandoffTaker.State.Requested)?.takeIf { it.peerId == pair.peerDeviceId }
        val cancelTarget = envelope.handoffId ?: waiting?.handoffId
        when (val decision = taker.onSnapshot(pair.peerDeviceId, body, accountMatches)) {
            is HandoffTaker.SnapshotDecision.Adopt -> {
                val handoffId = decision.snapshot.handoffId
                val adopted = uiInForeground() && onMain { host.endpoint?.adopt(decision.snapshot) == true }
                if (adopted) {
                    taker.onAdopted(handoffId)
                    // Ack が中継で落ちた場合に備えて、新しい seq でもう一度送る（相手は handoffId ごとに冪等）。受信の処理は止めない
                    scope.launch {
                        sendBody(pair, EnvelopeType.Ack, handoffId, HandoffBody.Ack(handoffId))
                        delay(ACK_RESEND_DELAY_MS)
                        sendBody(pair, EnvelopeType.Ack, handoffId, HandoffBody.Ack(handoffId))
                    }
                    notify("${pair.peerName} から引き取りました")
                } else {
                    taker.onAdoptFailed(handoffId)
                    sendBody(pair, EnvelopeType.Cancel, handoffId, HandoffBody.Cancel(handoffId, "not_adopted"))
                    notify("引き取れませんでした（wave を前面に出してもう一度試してください）")
                }
            }
            is HandoffTaker.SnapshotDecision.Ignore -> {
                Log.i(WaveApplication.LOG_TAG, "connect snapshot ignored reason=${decision.reason}")
                // 待っていない snapshot でも、自分が最近出した要求への返事なら（取り下げた後に届いた）、A を再開させる
                val target =
                    when {
                        decision.sendCancel -> cancelTarget
                        taker.wasIssued(envelope.handoffId) -> envelope.handoffId
                        else -> null
                    }
                target?.let { sendBody(pair, EnvelopeType.Cancel, it, HandoffBody.Cancel(it, decision.reason)) }
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

    private suspend fun accountHmac(pair: PairStore.PairRecord): String? {
        val id = session.verifiedAccountId()?.reveal() ?: return null
        return b64.encodeToString(Pairing.accountHmac(unb64.decode(pair.accountKey), id))
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
        const val ACK_RESEND_GRACE_MS = 5_000L
        const val UNPAIR_OFFLINE_WAIT_MS = 3_000L
    }
}
