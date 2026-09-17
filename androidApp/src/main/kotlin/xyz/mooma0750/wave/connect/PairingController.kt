package xyz.mooma0750.wave.connect

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.session.WaveSession
import xyz.mooma0750.wave.handoff.RelayMessage
import xyz.mooma0750.wave.handoff.crypto.Pairing
import java.util.Base64

/**
 * ペアリングの画面の裏側（Phase 6、ADR-0008）。A（再生中の端末、QR を出して6桁を入力）と B（QR を読んで6桁を表示）の両方。
 * 確定するまでペアを保存しない。A は確定したら B を中継に登録する。
 */
class PairingController(
    private val identity: DeviceIdentity,
    private val pairs: PairStore,
    private val relay: RelayClient,
    private val connect: ConnectManager,
    private val session: WaveSession,
    private val scope: CoroutineScope,
    private val deviceName: String,
) {
    sealed interface State {
        data object Idle : State

        data class Working(
            val message: String,
        ) : State

        /** A: QR を出して待っている。 */
        data class ShowingOffer(
            val offerText: String,
        ) : State

        /** A: B の画面の6桁の入力を待っている。 */
        data object EnterCode : State

        /** B: この6桁を A に入力してもらう。 */
        data class ShowCode(
            val code: String,
            val peerName: String,
        ) : State

        data class Done(
            val peerName: String,
        ) : State

        data class Failed(
            val message: String,
        ) : State
    }

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState
    private var job: Job? = null
    private var responder: Pairing.Responder? = null
    private var pendingOnB: Pairing.Established? = null
    private var relayAcquired = false

    /** A: QR を出す。中継に登録済み（接続できる）でなければ始められない。 */
    fun startOffer() {
        cancel()
        job =
            scope.launch {
                mutableState.value = State.Working("中継に接続しています")
                acquireRelay()
                val status = withTimeoutOrNull(15_000) { relay.status.first { it != RelayClient.Status.Connecting && it != RelayClient.Status.Disconnected } }
                if (status != RelayClient.Status.Connected) {
                    return@launch fail(relayMessage(status))
                }
                val accountId = session.verifiedAccountId()?.reveal() ?: return@launch fail("書き込み先のアカウントを確かめられません")
                val r = Pairing.Responder(identity.privateKey(), identity.publicKey, deviceName, accountId, nowMs = android.os.SystemClock::elapsedRealtime)
                responder = r
                val tokenHash = b64.encodeToString(r.tokenHash)
                relay.send(RelayMessage.PairingOpen(tokenHash))
                mutableState.value = State.ShowingOffer(Pairing.encodeOffer(r.offer))
                Log.i(WaveApplication.LOG_TAG, "pairing offer shown")
                val hello = withTimeoutOrNull(Pairing.LIFETIME_MS) { connect.pairHellos.first { it.tokenHash == tokenHash } } ?: return@launch fail("時間内に読み取られませんでした")
                val decoded = runCatching { json.decodeFromJsonElement(Pairing.Hello.serializer(), hello.hello) }.getOrNull() ?: return@launch fail("相手の応答を読めませんでした")
                val confirm = r.onHello(decoded).getOrElse { return@launch fail(failureMessage(it)) }
                relay.send(RelayMessage.PairConfirm(tokenHash, json.encodeToJsonElement(Pairing.Confirm.serializer(), confirm)))
                mutableState.value = State.EnterCode
            }
    }

    /** A: 本人が B の画面の6桁を入力した。 */
    fun enterCode(code: String) {
        val r = responder ?: return
        scope.launch {
            val established = r.onCodeEntered(code).getOrElse { error ->
                val reason = (error as? Pairing.PairingFailure)?.reason
                if (reason == Pairing.Failure.WrongCode) {
                    mutableState.value = State.Failed("数字が違います。もう一度入力してください")
                    mutableState.value = State.EnterCode
                } else {
                    fail(failureMessage(error))
                }
                return@launch
            }
            pairs.add(established)
            relay.register(established.peerPublicKey)
            responder = null
            Log.i(WaveApplication.LOG_TAG, "pairing established role=A")
            mutableState.value = State.Done(established.peerDeviceName)
            releaseRelay()
        }
    }

    /** B: 読み取った QR の文字列で始める。 */
    fun accept(offerText: String) {
        cancel()
        job =
            scope.launch {
                val offer = Pairing.decodeOffer(offerText) ?: return@launch fail("wave のペアリングの QR ではありません")
                val accountId = session.verifiedAccountId()?.reveal() ?: return@launch fail("書き込み先のアカウントを確かめられません")
                mutableState.value = State.Working("相手の端末に送っています")
                val initiator = Pairing.Initiator(offer, identity.privateKey(), identity.publicKey, deviceName, accountId)
                val reply = relay.pair(offer.token, json.encodeToJsonElement(Pairing.Hello.serializer(), initiator.hello()))
                val confirmed = reply as? RelayMessage.PairConfirmed ?: return@launch fail(if (reply == null) "中継に届きませんでした（Tailscale を確かめてください）" else "相手の端末が待っていません")
                val confirm = runCatching { json.decodeFromJsonElement(Pairing.Confirm.serializer(), confirmed.confirm) }.getOrNull() ?: return@launch fail("相手の応答を読めませんでした")
                val (code, established) = initiator.onConfirm(confirm).getOrElse { return@launch fail(failureMessage(it)) }
                pendingOnB = established
                mutableState.value = State.ShowCode(code, established.peerDeviceName)
                Log.i(WaveApplication.LOG_TAG, "pairing code shown role=B")
            }
    }

    /** B: A に数字を入力してもらったあと「続ける」。 */
    fun confirmOnB() {
        val established = pendingOnB ?: return
        scope.launch {
            // A が承認していなくても、片側だけに残ったペアは無害（相手から封筒が届かない）
            pairs.add(established)
            pendingOnB = null
            Log.i(WaveApplication.LOG_TAG, "pairing established role=B")
            mutableState.value = State.Done(established.peerDeviceName)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        responder = null
        pendingOnB = null
        releaseRelay()
        mutableState.value = State.Idle
    }

    private fun acquireRelay() {
        if (!relayAcquired) {
            relayAcquired = true
            relay.acquire()
        }
    }

    private fun releaseRelay() {
        if (relayAcquired) {
            relayAcquired = false
            relay.release()
        }
    }

    private fun fail(message: String) {
        responder = null
        pendingOnB = null
        releaseRelay()
        mutableState.value = State.Failed(message)
        Log.i(WaveApplication.LOG_TAG, "pairing failed")
    }

    private fun failureMessage(error: Throwable): String =
        when ((error as? Pairing.PairingFailure)?.reason) {
            Pairing.Failure.AccountMismatch -> "相手の端末は別のアカウントでログインしています"
            Pairing.Failure.Expired -> "時間切れです。もう一度やり直してください"
            Pairing.Failure.TooManyAttempts -> "数字を3回間違えたので、やり直してください"
            Pairing.Failure.AlreadyUsed -> "この QR は使用済みです"
            Pairing.Failure.BadSignature, Pairing.Failure.Malformed -> "相手の端末を確かめられませんでした"
            else -> "ペアリングできませんでした"
        }

    companion object {
        fun relayMessage(status: RelayClient.Status?): String =
            when (status) {
                RelayClient.Status.TailnetUnreachable -> "中継に届きません。Tailscale に接続し、wave が Tailscale の対象になっているか確かめてください"
                RelayClient.Status.NotRegistered -> "この端末は中継に登録されていません。ペアリング済みの端末から QR を出してください"
                else -> "中継に接続できませんでした"
            }
    }
}
