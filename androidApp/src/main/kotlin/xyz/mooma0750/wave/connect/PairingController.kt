package xyz.mooma0750.wave.connect

import android.content.res.Resources
import android.util.Log
import androidx.annotation.StringRes
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
import xyz.mooma0750.wave.R
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
    /** 画面に出す文言を引く（端末の言語に従う）。 */
    private val resources: Resources,
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
        mutableState.value = State.Working(str(R.string.connect_status_connecting_relay))
        relay.acquire()
        try {
            val status = awaitRelay()
            if (status != RelayClient.Status.Connected) return fail(str(relayMessage(status)))
            val account = session.verifiedAccount() ?: return fail(str(R.string.connect_account_unverified))
            val responder = Pairing.Responder(identity.privateKey(), identity.publicKey, deviceName, account.id.reveal(), nowMs = android.os.SystemClock::elapsedRealtime)
            val tokenHash = b64.encodeToString(responder.tokenHash)
            if (!relay.send(RelayMessage.PairingOpen(tokenHash))) return fail(str(R.string.connect_relay_send_failed))
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
            if (hello == null) return fail(str(if (relay.status.value != RelayClient.Status.Connected) R.string.connect_pairing_relay_lost else R.string.connect_pairing_not_scanned))
            val decoded = runCatching { json.decodeFromJsonElement(Pairing.Hello.serializer(), hello.hello) }.getOrNull() ?: return fail(str(R.string.connect_pairing_bad_reply))
            val confirm = responder.onHello(decoded).getOrElse { return fail(failureMessage(it)) }
            if (!relay.send(RelayMessage.PairConfirm(tokenHash, json.encodeToJsonElement(Pairing.Confirm.serializer(), confirm)))) return fail(str(R.string.connect_relay_send_failed))
            mutableState.value = State.EnterCode()
            val done =
                withTimeoutOrNull(Pairing.LIFETIME_MS) {
                    for (code in entered) {
                        val established =
                            responder.onCodeEntered(code).getOrElse { error ->
                                if ((error as? Pairing.PairingFailure)?.reason == Pairing.Failure.WrongCode) {
                                    mutableState.value = State.EnterCode(str(R.string.connect_pairing_wrong_code))
                                    continue
                                }
                                fail(failureMessage(error))
                                return@withTimeoutOrNull true
                            }
                        // 確かめた後にアカウントが切り替わっていたら、前のアカウントで確かめたペアを保存しない
                        if (session.loginGenerationId() != account.generationId) {
                            fail(str(R.string.connect_pairing_login_changed))
                            return@withTimeoutOrNull true
                        }
                        mutableState.value = State.Working(str(R.string.connect_status_registering))
                        if (!register(established.peerPublicKey)) {
                            fail(str(R.string.connect_pairing_register_failed))
                        } else {
                            pairs.add(established)
                            Log.i(WaveApplication.LOG_TAG, "pairing established role=A")
                            mutableState.value = State.Done(established.peerDeviceName)
                        }
                        return@withTimeoutOrNull true
                    }
                    true
                }
            if (done == null) fail(str(R.string.connect_pairing_timed_out))
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
        val offer = Pairing.decodeOffer(offerText) ?: return fail(str(R.string.connect_pairing_not_wave_qr))
        val account = session.verifiedAccount() ?: return fail(str(R.string.connect_account_unverified))
        mutableState.value = State.Working(str(R.string.connect_status_sending_peer))
        val initiator = Pairing.Initiator(offer, identity.privateKey(), identity.publicKey, deviceName, account.id.reveal())
        val reply = relay.pair(offer.token, json.encodeToJsonElement(Pairing.Hello.serializer(), initiator.hello()))
        val confirmed =
            reply as? RelayMessage.PairConfirmed
                ?: return fail(str(if (reply == null) R.string.connect_pairing_relay_unreachable_tailscale else R.string.connect_pairing_peer_not_waiting_reshow))
        val confirm = runCatching { json.decodeFromJsonElement(Pairing.Confirm.serializer(), confirmed.confirm) }.getOrNull() ?: return fail(str(R.string.connect_pairing_bad_reply))
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
        if (session.loginGenerationId() != generation) return fail(str(R.string.connect_pairing_login_changed))
        mutableState.value = State.Working(str(R.string.connect_status_checking_approval))
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
                    mutableState.value = State.ShowCode(code, established.peerDeviceName, str(R.string.connect_pairing_not_approved, established.peerDeviceName))
                else -> mutableState.value = State.ShowCode(code, established.peerDeviceName, str(relayMessage(status)))
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
                fail(str(R.string.connect_pairing_failed))
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

    private fun str(
        @StringRes id: Int,
        vararg args: Any,
    ): String = resources.getString(id, *args)

    private fun failureMessage(error: Throwable): String =
        str(
            when ((error as? Pairing.PairingFailure)?.reason) {
                Pairing.Failure.AccountMismatch -> R.string.connect_pairing_account_mismatch
                Pairing.Failure.Expired -> R.string.connect_pairing_timed_out
                Pairing.Failure.TooManyAttempts -> R.string.connect_pairing_too_many_attempts
                Pairing.Failure.AlreadyUsed -> R.string.connect_pairing_qr_used
                Pairing.Failure.BadSignature, Pairing.Failure.Malformed -> R.string.connect_pairing_peer_unverified
                else -> R.string.connect_pairing_failed
            },
        )

    companion object {
        private const val RELAY_CONNECT_TIMEOUT_MS = 15_000L
        private const val REGISTER_REPLY_TIMEOUT_MS = 10_000L

        /** 中継につながらなかったときの文言（文字列リソースの ID）。 */
        @StringRes
        fun relayMessage(status: RelayClient.Status?): Int =
            when (status) {
                RelayClient.Status.TailnetUnreachable -> R.string.connect_relay_tailnet_unreachable
                RelayClient.Status.NotRegistered -> R.string.connect_relay_not_registered
                else -> R.string.connect_relay_connect_failed
            }
    }
}
