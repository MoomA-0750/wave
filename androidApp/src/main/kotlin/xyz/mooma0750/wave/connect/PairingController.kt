package xyz.mooma0750.wave.connect

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.session.WaveSession
import xyz.mooma0750.wave.handoff.RelayMessage
import xyz.mooma0750.wave.handoff.crypto.Pairing
import java.util.Base64

/**
 * ペアリングの画面の裏側（Phase 6、ADR-0008）。A（登録済みの端末、QR を出して6桁を入力）と B（QR を読んで6桁を表示）の両方。
 * 確定するまでペアを保存しない。A は中継に B を登録できたらペアを保存する。
 *
 * 状態と進行中の手順は Main だけで触る（画面と debug の入口は Main から呼ぶ）。手順は1つの job にまとめ、
 * 中継の参照はその job の中で取って finally で戻す（取り消しても二重に戻さない）。
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

        /** A: B の画面の6桁の入力を待っている。間違えたときは message に理由を出す。 */
        data class EnterCode(
            val message: String? = null,
        ) : State

        /** B: この6桁を A に入力してもらう。「続ける」で承認を確かめられなかったときは message に理由を出す。 */
        data class ShowCode(
            val code: String,
            val peerName: String,
            val message: String? = null,
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
    private var codes: Channel<String>? = null
    /** B: 表示している6桁、確定を待っているペア、アカウントを確かめたログインの世代。 */
    private var pendingOnB: Triple<String, Pairing.Established, Long>? = null

    /** A: QR を出す。中継に登録済み（接続できる）でなければ始められない。 */
    fun startOffer() {
        cancel()
        val entered = Channel<String>(Channel.CONFLATED)
        codes = entered
        job = launchJob { runOffer(entered) }
    }

    /** A: 本人が B の画面の6桁を入力した。 */
    fun enterCode(code: String) {
        codes?.trySend(code)
    }

    /** B: 読み取った QR の文字列で始める。 */
    fun accept(offerText: String) {
        cancel()
        job = launchJob { runAccept(offerText) }
    }

    /** B: A に数字を入力してもらったあと「続ける」。中継に登録されたか（= A が承認したか）を確かめてから保存する。 */
    fun confirmOnB() {
        val (code, established, generation) = pendingOnB ?: return
        job?.cancel()
        job = launchJob { runConfirmOnB(code, established, generation) }
    }

    fun cancel() {
        job?.cancel()
        job = null
        codes = null
        pendingOnB = null
        mutableState.value = State.Idle
    }

    private suspend fun runOffer(entered: Channel<String>) {
        mutableState.value = State.Working("中継に接続しています")
        relay.acquire()
        try {
            val status = awaitRelay()
            if (status != RelayClient.Status.Connected) return fail(relayMessage(status))
            val account = session.verifiedAccount() ?: return fail("書き込み先のアカウントを確かめられません")
            val responder = Pairing.Responder(identity.privateKey(), identity.publicKey, deviceName, account.id.reveal(), nowMs = android.os.SystemClock::elapsedRealtime)
            val tokenHash = b64.encodeToString(responder.tokenHash)
            if (!relay.send(RelayMessage.PairingOpen(tokenHash))) return fail("中継に届きませんでした")
            mutableState.value = State.ShowingOffer(Pairing.encodeOffer(responder.offer))
            Log.i(WaveApplication.LOG_TAG, "pairing offer shown")
            // 中継がつなぎ直すと預けた枠が消えるので、切れたら待たずに失敗にする
            val hello =
                withTimeoutOrNull(Pairing.LIFETIME_MS) {
                    merge(
                        connect.pairHellos.filter { it.tokenHash == tokenHash },
                        relay.status.filter { it != RelayClient.Status.Connected }.map { null },
                    ).first()
                }
            if (hello == null) return fail(if (relay.status.value != RelayClient.Status.Connected) "中継との接続が切れました。もう一度 QR を出してください" else "時間内に読み取られませんでした")
            val decoded = runCatching { json.decodeFromJsonElement(Pairing.Hello.serializer(), hello.hello) }.getOrNull() ?: return fail("相手の応答を読めませんでした")
            val confirm = responder.onHello(decoded).getOrElse { return fail(failureMessage(it)) }
            if (!relay.send(RelayMessage.PairConfirm(tokenHash, json.encodeToJsonElement(Pairing.Confirm.serializer(), confirm)))) return fail("中継に届きませんでした")
            mutableState.value = State.EnterCode()
            val done =
                withTimeoutOrNull(Pairing.LIFETIME_MS) {
                    for (code in entered) {
                        val established =
                            responder.onCodeEntered(code).getOrElse { error ->
                                if ((error as? Pairing.PairingFailure)?.reason == Pairing.Failure.WrongCode) {
                                    mutableState.value = State.EnterCode("数字が違います。もう一度入力してください")
                                    continue
                                }
                                fail(failureMessage(error))
                                return@withTimeoutOrNull true
                            }
                        // 確かめた後にアカウントが切り替わっていたら、前のアカウントで確かめたペアを保存しない
                        if (session.loginGenerationId() != account.generationId) {
                            fail("ログインが切り替わったので、もう一度ペアリングしてください")
                            return@withTimeoutOrNull true
                        }
                        mutableState.value = State.Working("中継に登録しています")
                        if (!register(established.peerPublicKey)) {
                            fail("中継に登録できませんでした。もう一度ペアリングしてください")
                        } else {
                            pairs.add(established)
                            Log.i(WaveApplication.LOG_TAG, "pairing established role=A")
                            mutableState.value = State.Done(established.peerDeviceName)
                        }
                        return@withTimeoutOrNull true
                    }
                    true
                }
            if (done == null) fail("時間切れです。もう一度やり直してください")
        } finally {
            relay.release()
        }
    }

    /** B を中継に登録し、中継の返事（成功は空の OnlineResult、失敗は register_failed）を待つ。 */
    private suspend fun register(peerPublicKey: ByteArray): Boolean =
        coroutineScope {
            val reply =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(REGISTER_REPLY_TIMEOUT_MS) {
                        relay.incoming.first { it is RelayMessage.OnlineResult || (it is RelayMessage.Error && it.code == "register_failed") }
                    }
                }
            if (!relay.register(peerPublicKey)) {
                reply.cancel()
                return@coroutineScope false
            }
            reply.await() is RelayMessage.OnlineResult
        }

    private suspend fun runAccept(offerText: String) {
        val offer = Pairing.decodeOffer(offerText) ?: return fail("wave のペアリングの QR ではありません")
        val account = session.verifiedAccount() ?: return fail("書き込み先のアカウントを確かめられません")
        mutableState.value = State.Working("相手の端末に送っています")
        val initiator = Pairing.Initiator(offer, identity.privateKey(), identity.publicKey, deviceName, account.id.reveal())
        val reply = relay.pair(offer.token, json.encodeToJsonElement(Pairing.Hello.serializer(), initiator.hello()))
        val confirmed =
            reply as? RelayMessage.PairConfirmed
                ?: return fail(if (reply == null) "中継に届きませんでした（Tailscale を確かめてください）" else "相手の端末が待っていません。QR を出し直してもらってください")
        val confirm = runCatching { json.decodeFromJsonElement(Pairing.Confirm.serializer(), confirmed.confirm) }.getOrNull() ?: return fail("相手の応答を読めませんでした")
        val (code, established) = initiator.onConfirm(confirm).getOrElse { return fail(failureMessage(it)) }
        pendingOnB = Triple(code, established, account.generationId)
        mutableState.value = State.ShowCode(code, established.peerDeviceName)
        Log.i(WaveApplication.LOG_TAG, "pairing code shown role=B")
    }

    private suspend fun runConfirmOnB(
        code: String,
        established: Pairing.Established,
        generation: Long,
    ) {
        if (session.loginGenerationId() != generation) return fail("ログインが切り替わったので、もう一度ペアリングしてください")
        mutableState.value = State.Working("承認されたか確かめています")
        relay.acquire()
        try {
            when (val status = awaitRelay()) {
                // すでに別のペアで登録済みの端末では、つながることは承認の証拠にならない（文言で補う）
                RelayClient.Status.Connected -> {
                    pairs.add(established)
                    pendingOnB = null
                    Log.i(WaveApplication.LOG_TAG, "pairing established role=B")
                    mutableState.value = State.Done(established.peerDeviceName)
                }
                RelayClient.Status.NotRegistered ->
                    mutableState.value = State.ShowCode(code, established.peerDeviceName, "まだ承認されていません。${established.peerDeviceName} に数字を入力し、「ペアリングしました」と出てから押してください")
                else -> mutableState.value = State.ShowCode(code, established.peerDeviceName, relayMessage(status))
            }
        } finally {
            relay.release()
        }
    }

    private fun launchJob(block: suspend () -> Unit): Job =
        scope.launch(Dispatchers.Main.immediate) {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.i(WaveApplication.LOG_TAG, "pairing error type=${error::class.simpleName}")
                fail("ペアリングできませんでした")
            }
        }

    private suspend fun awaitRelay(): RelayClient.Status? =
        withTimeoutOrNull(RELAY_CONNECT_TIMEOUT_MS) { relay.status.first { it != RelayClient.Status.Connecting && it != RelayClient.Status.Disconnected } }

    private fun fail(message: String) {
        codes = null
        pendingOnB = null
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
        private const val RELAY_CONNECT_TIMEOUT_MS = 15_000L
        private const val REGISTER_REPLY_TIMEOUT_MS = 10_000L

        fun relayMessage(status: RelayClient.Status?): String =
            when (status) {
                RelayClient.Status.TailnetUnreachable -> "中継に届きません。Tailscale に接続し、wave が Tailscale の対象になっているか確かめてください"
                RelayClient.Status.NotRegistered -> "この端末は中継に登録されていません。ペアリング済みの端末から QR を出してください"
                else -> "中継に接続できませんでした"
            }
    }
}
