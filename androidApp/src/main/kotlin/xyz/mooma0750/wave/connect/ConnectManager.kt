package xyz.mooma0750.wave.connect

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 画面に出す短い文言。 */
    val events: SharedFlow<String> = mutableEvents

    /** 中継からの、ペアリングの第1手（A の画面が受ける）。 */
    val pairHellos = MutableSharedFlow<RelayMessage.PairHello>(extraBufferCapacity = 2)

    init {
        scope.launch {
            relay.incoming.collect { message ->
                when (message) {
                    is RelayMessage.Delivered -> onEnvelope(message.envelope)
                    is RelayMessage.PairHello -> pairHellos.tryEmit(message)
                    is RelayMessage.Error -> Log.i(WaveApplication.LOG_TAG, "connect relay error code=${message.code}")
                    else -> Unit
                }
            }
        }
    }

    /** 手元の端末（B）: peerDeviceId の端末から引き取る。 */
    suspend fun takeOver(peerDeviceId: String): Boolean {
        val pair = pairs.find(peerDeviceId) ?: return false
        val accountHmac = accountHmac(pair) ?: return notify("書き込み先のアカウントを確かめられないので、引き取れません")
        val handoffId = UUID.randomUUID().toString()
        if (!taker.request(peerDeviceId, handoffId)) return notify("ほかの引き取りを待っています")
        ensurePlaybackService()
        if (!sendBody(pair, EnvelopeType.Request, handoffId, HandoffBody.Request(handoffId, accountHmac))) {
            taker.reset()
            return notify("中継に届きませんでした")
        }
        Log.i(WaveApplication.LOG_TAG, "connect request sent")
        scope.launch {
            delay(HandoffTaker.DEFAULT_TIMEOUT_MS + 500)
            taker.expire()?.let { expired ->
                pairs.find(expired.peerId)?.let { sendBody(it, EnvelopeType.Cancel, expired.handoffId, HandoffBody.Cancel(expired.handoffId, "timeout")) }
                notify("相手の端末から返事がありませんでした（再生中の画面か、一時停止から10分以内のときに引き取れます）")
            }
        }
        return true
    }

    private suspend fun onEnvelope(envelope: Envelope) {
        val pair = pairs.find(envelope.from)
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
            is HandoffBody.Request -> onRequest(pair, envelope, body)
            is HandoffBody.Snapshot -> onSnapshot(pair, body)
            is HandoffBody.Ack -> if (giver.onAck(pair.peerDeviceId, body)) onMain { host.endpoint?.markHandedOff() }.also { notify("${pair.peerName} が引き取りました") }
            is HandoffBody.Cancel -> if (giver.onCancel(pair.peerDeviceId, body)) onMain { host.endpoint?.resumeAfterCancel() }
            HandoffBody.Unpair -> {
                pairs.remove(pair.peerDeviceId)
                notify("${pair.peerName} がペアリングを解除しました")
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
                    sendBody(pair, EnvelopeType.Cancel, body.handoffId, HandoffBody.Cancel(body.handoffId, "no_queue"))
                    return
                }
                sendBody(pair, EnvelopeType.Snapshot, body.handoffId, HandoffBody.Snapshot(mine, HandoffCodec.encode(snapshot)))
                Log.i(WaveApplication.LOG_TAG, "connect snapshot sent items=${snapshot.items.size}")
                scope.launch {
                    delay(HandoffGiver.DEFAULT_WINDOW_MS + 500)
                    if (giver.expire()) notify("引き取られなかったかもしれません（一時停止しています）")
                }
            }
            HandoffGiver.RequestDecision.Duplicate -> Unit
            HandoffGiver.RequestDecision.Refuse -> sendBody(pair, EnvelopeType.Cancel, body.handoffId, HandoffBody.Cancel(body.handoffId, if (accountMatches) "no_queue" else "account"))
        }
    }

    private suspend fun onSnapshot(
        pair: PairStore.PairRecord,
        body: HandoffBody.Snapshot,
    ) {
        val mine = accountHmac(pair)
        val accountMatches = mine != null && xyz.mooma0750.wave.handoff.crypto.WaveCrypto.constantTimeEquals(mine.encodeToByteArray(), body.accountHmac.encodeToByteArray())
        when (val decision = taker.onSnapshot(pair.peerDeviceId, body, accountMatches)) {
            is HandoffTaker.SnapshotDecision.Adopt -> {
                val handoffId = decision.snapshot.handoffId
                val adopted = uiInForeground() && onMain { host.endpoint?.adopt(decision.snapshot) == true }
                if (adopted) {
                    taker.onAdopted(handoffId)
                    // Ack が中継で落ちた場合に備えて、新しい seq でもう一度送る（相手は handoffId ごとに冪等）
                    repeat(2) { attempt ->
                        if (attempt > 0) delay(3_000)
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
                val requested = taker.state as? HandoffTaker.State.Requested
                if (decision.sendCancel) {
                    // 期限切れ・アカウント不一致: 送り手の handoffId は snapshot の中にしかないので、検証前の値は使わず、待っていた ID に向けて送る
                    requested?.let { sendBody(pair, EnvelopeType.Cancel, it.handoffId, HandoffBody.Cancel(it.handoffId, decision.reason)) }
                }
                if (decision.reason == "account_mismatch") notify("${pair.peerName} は別のアカウントでログインしています")
            }
            is HandoffTaker.SnapshotDecision.Incompatible -> notify("${pair.peerName} の wave を更新してください")
        }
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
}
