package xyz.mooma0750.wave.connect2

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.connect.DeviceIdentity
import xyz.mooma0750.wave.core.session.WaveSession
import xyz.mooma0750.wave.handoff.crypto.Pairing
import xyz.mooma0750.wave.handoff.v2.ClientEvent
import xyz.mooma0750.wave.handoff.v2.RelayV2
import java.util.Base64

/**
 * Connect v2 の加入（Phase 6b、D-3）。A（家の端末、QR を出して6桁を入力）と B（QR を読んで6桁を表示）。
 * v1 の PairingController と同じ形（状態と手順は Main、手順は1つの job、中継の参照は job の中で取って戻す）。
 * A は6桁の一致の後に B を家に加え、B は A の Confirm の床で加入を待つ。
 */
class PairingV2Controller(
    private val identity: DeviceIdentity,
    private val manager: ConnectV2Manager,
    private val session: WaveSession,
    private val scope: CoroutineScope,
    private val deviceName: String,
) {
    sealed interface State {
        data object Idle : State

        data class Working(
            val message: String,
        ) : State

        data class ShowingOffer(
            val offerText: String,
        ) : State

        data class EnterCode(
            val message: String? = null,
        ) : State

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
    private var codes: Channel<String>? = null
    private var pendingOnB: Pairing.Established? = null

    fun startOffer() {
        cancel()
        val entered = Channel<String>(Channel.CONFLATED)
        codes = entered
        job = launchJob { runOffer(entered) }
    }

    fun enterCode(code: String) {
        codes?.trySend(code)
    }

    fun accept(offerText: String) {
        cancel()
        job = launchJob { runAccept(offerText) }
    }

    /**
     * B: A に数字を入力してもらったあと「続ける」。A の Confirm の床で加入を待ち、エンジンが加入の鎖を受け入れたら完了にする（レビュー p6b34 中-3）。
     * A の追記が中継に入る前につなぐと認証で断られる（NotMember）ので、間を空けてつなぎ直す。
     */
    fun confirmOnB() {
        val established = pendingOnB ?: return
        val extension = established.peerExtension ?: return run { mutableState.value = State.Failed("相手の端末が Connect v2 に対応していません") }
        pendingOnB = null
        job?.cancel()
        job = launchJob { runJoinOnB(established, extension) }
    }

    private suspend fun runJoinOnB(
        established: Pairing.Established,
        extension: String,
    ) {
        if (!manager.expectJoin(extension, established.peerPublicKey)) return fail("この端末の Connect v2 の状態では加われません（既に家に入っているか、保存を読めません）")
        mutableState.value = State.Working("家に加わるのを待っています")
        var accepted = false
        val joined =
            try {
                awaitJoin().also { accepted = it == ClientEvent.Joined }
            } finally {
                // 時間切れ・失敗・取り下げでは、エンジンの待ちも消す（後でつながったときに黙って家に入らない、再レビュー 低-L）
                if (!accepted) manager.cancelJoin()
            }
        when {
            joined == ClientEvent.Joined -> {
                manager.rememberLabel(xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer.deviceId(established.peerPublicKey), established.peerDeviceName)
                mutableState.value = State.Done(established.peerDeviceName)
                Log.i(WaveApplication.LOG_TAG, "pairing-v2 established role=B")
            }
            joined == null -> fail("家に加われませんでした（相手の端末で加える操作が済んでいるか確かめて、もう一度やり直してください）")
            else -> fail("中継の端末一覧を確かめられませんでした")
        }
    }

    /** 加入の鎖を受け入れるまで待つ。断られたら間を空けてつなぎ直す。@return Joined・鎖の問題の出来事、時間切れなら null */
    private suspend fun awaitJoin(): ClientEvent? =
        coroutineScope {
            val outcome =
                async(start = CoroutineStart.UNDISPATCHED) {
                    manager.events.first { it is ClientEvent.Joined || it is ClientEvent.ChainForked || it is ClientEvent.ChainRolledBack || it is ClientEvent.ChainInvalid }
                }
            try {
                withTimeoutOrNull(JOIN_WAIT_MS) {
                    for (wait in JOIN_RETRY_MS) {
                        if (manager.inHousehold()) return@withTimeoutOrNull ClientEvent.Joined
                        // 家に入ったので、つなぎ直して鎖を取る（認証は中継の鎖の今の端末で行う）
                        manager.relay.acquire()
                        try {
                            val result =
                                withTimeoutOrNull(wait) {
                                    merge<ClientEvent?>(flow { emit(outcome.await()) }, manager.relay.status.filter { it == RelayClientV2.Status.NotMember }.map { null }).first()
                                }
                            if (result != null) return@withTimeoutOrNull result
                            // 断られた: A の追記が中継に入るのを待ってから、つなぎ直す
                            if (manager.relay.status.value == RelayClientV2.Status.NotMember) delay(wait)
                        } finally {
                            manager.relay.release()
                        }
                    }
                    null
                }
            } finally {
                outcome.cancel()
            }
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
        manager.relay.acquire()
        try {
            val status = withTimeoutOrNull(15_000) { manager.relay.status.first { it != RelayClientV2.Status.Connecting && it != RelayClientV2.Status.Disconnected } }
            if (status != RelayClientV2.Status.Connected) return fail("中継 v2 に接続できませんでした")
            val joinInfo = manager.joinInfoJson() ?: return fail("この端末はまだ家に入っていません")
            val account = session.verifiedAccount() ?: return fail("書き込み先のアカウントを確かめられません")
            val responder = Pairing.Responder(identity.privateKey(), identity.publicKey, deviceName, account.id.reveal(), nowMs = android.os.SystemClock::elapsedRealtime, confirmExtension = joinInfo)
            val tokenHash = b64.encodeToString(responder.tokenHash)
            if (!manager.relay.send(RelayV2.PairingOpen(tokenHash))) return fail("中継に届きませんでした")
            mutableState.value = State.ShowingOffer(Pairing.encodeOffer(responder.offer))
            val hello =
                withTimeoutOrNull(Pairing.LIFETIME_MS) {
                    merge(
                        manager.pairHellos.filter { it.tokenHash == tokenHash },
                        manager.relay.status.filter { it != RelayClientV2.Status.Connected }.map { null },
                    ).first()
                } ?: return fail("時間内に読み取られなかったか、中継との接続が切れました")
            val decoded = runCatching { json.decodeFromJsonElement(Pairing.Hello.serializer(), hello.hello) }.getOrNull() ?: return fail("相手の応答を読めませんでした")
            val confirm = responder.onHello(decoded).getOrElse { return fail("相手の端末を確かめられませんでした") }
            if (!manager.relay.send(RelayV2.PairConfirm(tokenHash, json.encodeToJsonElement(Pairing.Confirm.serializer(), confirm)))) return fail("中継に届きませんでした")
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
                                fail("ペアリングできませんでした")
                                return@withTimeoutOrNull true
                            }
                        if (session.loginGenerationId() != account.generationId) {
                            fail("ログインが切り替わったので、もう一度やり直してください")
                            return@withTimeoutOrNull true
                        }
                        val memberKeys = established.peerExtension
                        mutableState.value = State.Working("家に加えています")
                        if (memberKeys == null || !manager.addMember(memberKeys, established.peerPublicKey)) {
                            fail("家に加えられませんでした。もう一度やり直してください")
                        } else {
                            manager.rememberLabel(xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer.deviceId(established.peerPublicKey), established.peerDeviceName)
                            Log.i(WaveApplication.LOG_TAG, "pairing-v2 established role=A")
                            mutableState.value = State.Done(established.peerDeviceName)
                        }
                        return@withTimeoutOrNull true
                    }
                    true
                }
            if (done == null) fail("時間切れです。もう一度やり直してください")
        } finally {
            manager.relay.release()
        }
    }

    private suspend fun runAccept(offerText: String) {
        val offer = Pairing.decodeOffer(offerText) ?: return fail("wave のペアリングの QR ではありません")
        val account = session.verifiedAccount() ?: return fail("書き込み先のアカウントを確かめられません")
        mutableState.value = State.Working("相手の端末に送っています")
        val initiator = Pairing.Initiator(offer, identity.privateKey(), identity.publicKey, deviceName, account.id.reveal(), helloExtension = manager.selfMemberKeysJson())
        val reply = manager.relay.pair(offer.token, json.encodeToJsonElement(Pairing.Hello.serializer(), initiator.hello()))
        val confirmed = reply as? RelayV2.PairConfirmed ?: return fail(if (reply == null) "中継 v2 に届きませんでした（Tailscale を確かめてください）" else "相手の端末が待っていません")
        val confirm = runCatching { json.decodeFromJsonElement(Pairing.Confirm.serializer(), confirmed.confirm) }.getOrNull() ?: return fail("相手の応答を読めませんでした")
        val (code, established) = initiator.onConfirm(confirm).getOrElse { return fail("相手の端末を確かめられませんでした") }
        if (session.loginGenerationId() != account.generationId) return fail("ログインが切り替わったので、もう一度やり直してください")
        pendingOnB = established
        mutableState.value = State.ShowCode(code, established.peerDeviceName)
    }

    private fun launchJob(block: suspend () -> Unit): Job =
        scope.launch(Dispatchers.Main.immediate) {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.i(WaveApplication.LOG_TAG, "pairing-v2 error type=${error::class.simpleName}")
                fail("ペアリングできませんでした")
            }
        }

    private companion object {
        const val JOIN_WAIT_MS = 30_000L

        /** つなぎ直すまでの待ち（NotMember で断られたら、次の待ちでつなぎ直す）。 */
        val JOIN_RETRY_MS = listOf(2_000L, 5_000L, 10_000L, 13_000L)
    }

    private fun fail(message: String) {
        codes = null
        pendingOnB = null
        mutableState.value = State.Failed(message)
        Log.i(WaveApplication.LOG_TAG, "pairing-v2 failed")
    }
}
