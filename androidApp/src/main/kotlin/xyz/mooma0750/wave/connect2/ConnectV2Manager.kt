package xyz.mooma0750.wave.connect2

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.connect.DeviceIdentity
import xyz.mooma0750.wave.core.session.WaveSession
import xyz.mooma0750.wave.handoff.HandoffCodec
import xyz.mooma0750.wave.handoff.HandoffDecodeResult
import xyz.mooma0750.wave.handoff.HandoffSnapshot
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.ClientEvent
import xyz.mooma0750.wave.handoff.v2.ClientPersisted
import xyz.mooma0750.wave.handoff.v2.ConnectClient
import xyz.mooma0750.wave.handoff.v2.CursorBody
import xyz.mooma0750.wave.handoff.v2.MemberChain
import xyz.mooma0750.wave.handoff.v2.RelayV2
import xyz.mooma0750.wave.handoff.v2.Role
import xyz.mooma0750.wave.playback.ConnectV2Host
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Connect v2 の Android 側の調停役（Phase 6b）。ConnectClient を1本のスレッドに閉じ込め、中継 v2・再生サービス・画面をつなぐ。
 * 再生サービスの入口は Main で呼ぶ。ログは種類と件数だけ（端末 ID・本文・アカウントの識別子は出さない）。
 */
class ConnectV2Manager(
    private val identity: DeviceIdentity,
    private val session: WaveSession,
    private val host: ConnectV2Host,
    private val appScope: CoroutineScope,
    baseUrl: String,
    relayHost: String,
    storageFile: File,
    /** 画面が前面にあるか（引き取った曲の再生を始めてよいか）。 */
    private val uiInForeground: () -> Boolean,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "connect-v2") }.asCoroutineDispatcher()
    private val keys = DeviceKeysV2(identity)
    private val storage = ConnectV2Storage(storageFile)
    private val snapshotJson = Json { ignoreUnknownKeys = true }

    val relay =
        RelayClientV2(
            identity,
            baseUrl,
            relayHost,
            appScope,
            deliver = { message -> post { client?.onMessage(message) } },
            onConnectionLost = { post { client?.onDisconnected() } },
        )

    private var client: ConnectClient? = null

    /** 他の端末の最新の再生（表示）。 */
    data class RemoteView(
        val writer: String,
        val cursor: CursorBody,
        val accountMatches: Boolean,
    )

    private val mutableRemote = MutableStateFlow<RemoteView?>(null)
    val remote: StateFlow<RemoteView?> = mutableRemote

    private val mutableEvents = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ClientEvent> = mutableEvents

    private val mutableUnreadable = MutableStateFlow(false)

    /** 端末の保存が読めない（作り直すまで Connect v2 を使わない）。 */
    val unreadable: StateFlow<Boolean> = mutableUnreadable

    @Volatile
    private var accountTag: String? = null

    @Volatile
    private var tagGeneration: Long? = null

    private var cursorJob: Job? = null

    private fun post(block: () -> Unit) {
        appScope.launch(dispatcher) {
            try {
                block()
            } catch (error: Exception) {
                Log.i(WaveApplication.LOG_TAG, "connect-v2 failed type=${error::class.simpleName}")
            }
        }
    }

    private fun load() {
        val initial =
            when (val loaded = storage.load()) {
                is ConnectV2Storage.Loaded.Ok -> loaded.state
                ConnectV2Storage.Loaded.Empty -> ClientPersisted()
                ConnectV2Storage.Loaded.Unreadable -> {
                    mutableUnreadable.value = true
                    return
                }
            }
        client =
            ConnectClient(
                signer = keys,
                opener = keys,
                role = Role.Player,
                send = { message -> relay.send(message) },
                effects = effects,
                nowMs = SystemClock::elapsedRealtime,
                epochMs = System::currentTimeMillis,
                newId = { UUID.randomUUID().toString() },
                initial = initial,
            )
        host.listener = listener
        refreshAccountTag()
    }

    private val effects =
        object : ConnectClient.Effects {
            override fun pause() {
                appScope.launch(Dispatchers.Main) { host.endpoint?.pause() }
            }

            override fun adopt(
                snapshot: String,
                cursor: CursorBody,
                positionMs: Long,
            ): Boolean {
                val generation = tagGeneration ?: return false
                val decoded = decodeSnapshot(snapshot) ?: return false
                return runBlocking(Dispatchers.Main) {
                    uiInForeground() && session.loginGenerationId() == generation && host.endpoint?.adopt(decoded, positionMs, generation) == true
                }
            }

            override fun event(event: ClientEvent) {
                if (event is ClientEvent.RemoteChanged) mutableRemote.value = RemoteView(event.writer, event.cursor, event.accountMatches)
                Log.i(WaveApplication.LOG_TAG, "connect-v2 event=${event::class.simpleName}")
                mutableEvents.tryEmit(event)
            }

            override fun persist(state: ClientPersisted) {
                runCatching { storage.save(state) }.onFailure { Log.i(WaveApplication.LOG_TAG, "connect-v2 persist failed type=${it::class.simpleName}") }
            }

            override fun accountTag(): String? = accountTag?.takeIf { tagGeneration == session.loginGenerationId() }
        }

    /** 本人の Verified のアカウントから、家ごとのアカウントタグを作る（D-3）。値はログに出さない。 */
    fun refreshAccountTag() {
        appScope.launch {
            val householdId = kotlinx.coroutines.withContext(dispatcher) { client?.householdId } ?: return@launch
            val account = session.verifiedAccount() ?: return@launch
            val digest = WaveCrypto.sha256(WaveCrypto.lengthPrefixed(TAG_LABEL.encodeToByteArray(), householdId.encodeToByteArray(), account.id.reveal().encodeToByteArray()))
            accountTag = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            tagGeneration = account.generationId
        }
    }

    private val listener =
        object : ConnectV2Host.Listener {
            override fun onQueueChanged() {
                val snapshot = host.endpoint?.snapshot() ?: return
                val text = HandoffCodec.encode(snapshot)
                post { client?.setLocalQueue(text) }
            }

            override fun onCursorChanged(significant: Boolean) {
                cursorJob?.cancel()
                if (significant) {
                    pushCursor()
                } else {
                    // シークなどは間引く（D-6）
                    cursorJob =
                        appScope.launch(Dispatchers.Main) {
                            delay(CURSOR_DEBOUNCE_MS)
                            pushCursor()
                        }
                }
            }

            override fun onUserStartedPlayback() {
                val snapshot = host.endpoint?.snapshot()?.let(HandoffCodec::encode)
                val cursor = host.endpoint?.cursor()
                post {
                    val engine = client ?: return@post
                    snapshot?.let(engine::setLocalQueue)
                    cursor?.let { engine.setLocalCursor(it.toLocal()) }
                    val claimed = engine.userStartedPlayback()
                    Log.i(WaveApplication.LOG_TAG, "connect-v2 user started claimed=$claimed")
                }
            }
        }

    private fun pushCursor() {
        val cursor = host.endpoint?.cursor() ?: return
        post { client?.setLocalCursor(cursor.toLocal()) }
    }

    private fun ConnectV2Host.Cursor.toLocal() = ConnectClient.LocalCursor(currentItemId, positionMs, playing, repeat, listenedMs, historySent)

    private fun decodeSnapshot(text: String): HandoffSnapshot? {
        val id = runCatching { snapshotJson.decodeFromString(HandoffSnapshot.serializer(), text).handoffId }.getOrNull() ?: return null
        return (HandoffCodec.decode(text, id) as? HandoffDecodeResult.Ok)?.snapshot
    }

    // --- 画面・debug からの操作 ---

    /** 「ここで再生」。 */
    fun transfer() = post { Log.i(WaveApplication.LOG_TAG, "connect-v2 transfer requested=${client?.requestTransfer()}") }

    fun acceptStoreReset() = post { client?.acceptStoreReset() }

    /** 起動時の設定の鍵の端末で、家を作る（L-13。家が無いときだけ中継が受ける）。 */
    fun createHousehold() =
        post {
            if (client?.householdId != null) return@post
            val self = MemberChain.memberKeys(keys, keys.kxPub, Role.Player)
            relay.send(RelayV2.HouseholdCreate(MemberChain.genesis(keys, self)))
            relay.send(RelayV2.ChainGet(0))
            Log.i(WaveApplication.LOG_TAG, "connect-v2 household create sent")
        }

    /** 読めない保存を捨てて、家に加わり直す。 */
    fun resetUnreadable() =
        post {
            storage.reset()
            mutableUnreadable.value = false
            load()
        }

    /** 表示・debug 用の要約（件数と真偽値だけ）。 */
    fun summary(onResult: (String) -> Unit) =
        post {
            val engine = client
            onResult(
                "configured=true loaded=${engine != null} household=${engine?.householdId != null} members=${engine?.members?.size} " +
                    "writer=${engine?.isWriter} active_is_me=${engine?.activeDeviceId == identity.deviceId} retreated=${engine?.hasRetreated} relay=${relay.status.value} tag=${accountTag != null}",
            )
        }

    // 状態（effects・listener）を作ってから始める（別のスレッドで load が先に走らないように）
    init {
        post { load() }
        appScope.launch {
            while (true) {
                delay(TICK_MS)
                if (relay.status.value == RelayClientV2.Status.Connected) {
                    post {
                        client?.tick()
                        // 家の ID が分かった・ログインが変わったら、アカウントタグを計算し直す
                        if (client?.householdId != null && (accountTag == null || tagGeneration != session.loginGenerationId())) refreshAccountTag()
                    }
                }
            }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val CURSOR_DEBOUNCE_MS = 1_000L
        const val TAG_LABEL = "wave-v2-account-tag"
    }
}
