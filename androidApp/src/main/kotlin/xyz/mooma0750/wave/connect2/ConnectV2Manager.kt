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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
import java.util.concurrent.atomic.AtomicBoolean

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
    /** 家の端末の表示名（加入のときに相手から聞いた名前）。鎖には名前を入れないので、この端末だけが知っている。 */
    private val labelsFile: File,
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
            deliver = { message ->
                when (message) {
                    // 加入の第1手は画面（PairingV2Controller）が受ける
                    is RelayV2.PairHello -> pairHellos.tryEmit(message)
                    else -> {
                        if (message is RelayV2.ChainOk || message is RelayV2.ChainConflict || message is RelayV2.Error) chainReplies.tryEmit(message)
                        post {
                            client?.onMessage(message)
                            // 最新の状態（welcome に同梱）を受けた（D-9 の待ちが見る）
                            if (message is RelayV2.Welcome) welcomes.value++
                        }
                    }
                }
            },
            onConnectionLost = { post { client?.onDisconnected() } },
        )

    private var client: ConnectClient? = null

    /** 中継 v2 からの、加入の第1手（A の画面が受ける）。 */
    val pairHellos = MutableSharedFlow<RelayV2.PairHello>(extraBufferCapacity = 2)

    /** 鎖の追記への返事（加入の手順が待つ）。 */
    private val chainReplies = MutableSharedFlow<RelayV2>(extraBufferCapacity = 4)

    /** 受けて処理した welcome の数（つながり直して最新の状態をそろえたか）。 */
    private val welcomes = MutableStateFlow(0)

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

    /** 画面の表示（件数と真偽値だけ）。 */
    data class Info(
        val loaded: Boolean = false,
        val inHousehold: Boolean = false,
        val members: Int = 0,
        val isWriter: Boolean = false,
        val retreated: Boolean = false,
        val storeResetPending: Boolean = false,
    )

    private val mutableInfo = MutableStateFlow(Info())
    val info: StateFlow<Info> = mutableInfo

    /** 家の端末（表示）。名前を知らない端末は ID の頭だけ。 */
    data class MemberView(
        val deviceId: String,
        val label: String,
        val isSelf: Boolean,
        val role: Role,
    )

    private val mutableMembers = MutableStateFlow<List<MemberView>>(emptyList())
    val members: StateFlow<List<MemberView>> = mutableMembers

    private val labelsJson = Json { ignoreUnknownKeys = true }

    @Volatile
    private var labels: Map<String, String> = emptyMap()

    private fun refreshInfo() {
        val engine = client
        writer = engine?.isWriter == true
        mutableInfo.value = Info(engine != null, engine?.householdId != null, engine?.members?.size ?: 0, engine?.isWriter == true, engine?.hasRetreated == true, engine?.storeResetPending == true)
        val known = labels
        mutableMembers.value =
            engine?.members.orEmpty().map { member ->
                val self = member.deviceId == identity.deviceId
                MemberView(member.deviceId, if (self) "この端末" else known[member.deviceId] ?: "端末 ${member.deviceId.take(6)}", self, member.role)
            }
    }

    private val mutableUnreadable = MutableStateFlow(false)

    /** 端末の保存が読めない（作り直すまで Connect v2 を使わない）。 */
    val unreadable: StateFlow<Boolean> = mutableUnreadable

    /** 家ごとのアカウントタグと、確かめたログインの世代（1つの参照で置き換える、レビュー p6b34 低-1）。 */
    private data class TagState(
        val householdId: String,
        val tag: String,
        val generation: Long,
    )

    @Volatile
    private var tagState: TagState? = null

    private val tagComputing = AtomicBoolean(false)

    /** 最後に使ったアカウントのタグ（エンジンのスレッドだけ）。別のアカウントに変わったら手元の再生を捨てる。 */
    private var lastTag: String? = null

    private var cursorJob: Job? = null
    private var queueJob: Job? = null

    /** 家の再生中の端末か（キューの書き出しを書き手のときだけにする、レビュー p6b34 中-1）。 */
    @Volatile
    private var writer = false

    private fun post(block: () -> Unit) {
        appScope.launch(dispatcher) {
            try {
                block()
            } catch (error: Exception) {
                Log.i(WaveApplication.LOG_TAG, "connect-v2 failed type=${error::class.simpleName}")
            }
        }
    }

    private fun loadLabels() {
        labels = runCatching { labelsJson.decodeFromString<Map<String, String>>(labelsFile.readText()) }.getOrDefault(emptyMap())
    }

    /** 加入の相手の名前を覚える（表示だけ）。 */
    fun rememberLabel(
        deviceId: String,
        name: String,
    ) = post {
        labels = labels + (deviceId to name.take(MAX_LABEL_CHARS))
        runCatching {
            val temp = File(labelsFile.parentFile, labelsFile.name + ".tmp")
            temp.writeText(labelsJson.encodeToString(labels))
            if (!temp.renameTo(labelsFile)) temp.delete()
        }
        refreshInfo()
    }

    private fun load() {
        loadLabels()
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
        refreshInfo()
    }

    private val effects =
        object : ConnectClient.Effects {
            // 止めた時点の位置を取る（yielded のカーソル、レビュー p6b34 高-3）。Main はエンジンのスレッドを待たないので、ここで待ってよい
            override fun pause(): ConnectClient.LocalCursor? =
                runBlocking(Dispatchers.Main) {
                    val endpoint = host.endpoint ?: return@runBlocking null
                    endpoint.pause()
                    endpoint.cursor()?.toLocal()
                }

            override fun adopt(
                snapshot: String,
                cursor: CursorBody,
                positionMs: Long,
            ): Boolean {
                val state = tagState ?: return false
                // 待っている間にログインが切り替わっていないこと（レビュー p6b34 低-2）
                if (cursor.accountTag != state.tag) return false
                val decoded = decodeSnapshot(snapshot) ?: return false
                val adjusted = atCursor(decoded, cursor, positionMs) ?: return false
                return runBlocking(Dispatchers.Main) {
                    uiInForeground() && session.loginGenerationId() == state.generation && host.endpoint?.adopt(adjusted, adjusted.positionMs, state.generation) == true
                }
            }

            override fun event(event: ClientEvent) {
                if (event is ClientEvent.RemoteChanged) mutableRemote.value = RemoteView(event.writer, event.cursor, event.accountMatches)
                Log.i(WaveApplication.LOG_TAG, "connect-v2 event=${event::class.simpleName}")
                mutableEvents.tryEmit(event)
                refreshInfo()
            }

            override fun persist(state: ClientPersisted) {
                runCatching { storage.save(state) }.onFailure { Log.i(WaveApplication.LOG_TAG, "connect-v2 persist failed type=${it::class.simpleName}") }
                refreshInfo()
            }

            override fun accountTag(): String? = tagState?.takeIf { it.generation == session.loginGenerationId() && it.householdId == client?.householdId }?.tag
        }

    /**
     * 本人の Verified のアカウントから、家ごとのアカウントタグを作る（D-3）。値はログに出さない。
     * 中継の接続に関係なく、家の ID が分かっていて、ログインの世代が変わったときに作り直す（レビュー p6b34 高-4）。重ねて走らせない。
     */
    private fun refreshAccountTagIfNeeded(householdId: String) {
        val current = tagState
        if (current != null && current.householdId == householdId && current.generation == session.loginGenerationId()) return
        if (!tagComputing.compareAndSet(false, true)) return
        appScope.launch {
            try {
                val account = session.verifiedAccount() ?: return@launch
                val digest = WaveCrypto.sha256(WaveCrypto.lengthPrefixed(TAG_LABEL.encodeToByteArray(), householdId.encodeToByteArray(), account.id.reveal().encodeToByteArray()))
                val next = TagState(householdId, Base64.getUrlEncoder().withoutPadding().encodeToString(digest), account.generationId)
                tagState = next
                post { onTagComputed(next) }
            } finally {
                tagComputing.set(false)
            }
        }
    }

    /** エンジンのスレッドで: 別のアカウントに変わっていたら、前のアカウントのキュー・再生を捨ててから、保留した開始を claim する。 */
    private fun onTagComputed(state: TagState) {
        val engine = client ?: return
        if (state.householdId != engine.householdId) return
        val previous = lastTag
        lastTag = state.tag
        if (previous != null && previous != state.tag) {
            // 前のアカウントのキューを新しいアカウントのタグで書かない（レビュー p6b34 高-5）
            engine.clearLocal()
            Log.i(WaveApplication.LOG_TAG, "connect-v2 account changed cleared local")
        }
        engine.onAccountTagAvailable()
        refreshInfo()
    }

    private val listener =
        object : ConnectV2Host.Listener {
            override fun onQueueChanged() {
                // 書き手でなければ書き出さない（再生を始めたときに書き出す）。続けて変わるときは1秒に間引く（D-6、レビュー p6b34 中-1）
                if (!writer) return
                queueJob?.cancel()
                queueJob =
                    appScope.launch(Dispatchers.Main) {
                        delay(QUEUE_DEBOUNCE_MS)
                        val snapshot = host.endpoint?.snapshot() ?: return@launch
                        val text = HandoffCodec.encode(snapshot)
                        post { client?.setLocalQueue(text) }
                    }
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

            override fun onUserStartedPlayback(newSelection: Boolean) {
                val foreground = uiInForeground()
                post {
                    val engine = client ?: return@post
                    // D-9: 引き取られた・止まった端末で再生ボタンを押したとき、他の端末で鳴っていれば、古い手元のキューで奪わない。
                    // 起動の直後などで最新の状態をまだ受けていなければ、一時停止してつなぎ、受けてから決める（レビュー p6b34 中-6）。曲を選んだのは新しい再生（L-3）
                    if (!newSelection && engine.hasRetreated && engine.householdId != null) {
                        if (engine.latestCursorBody() == null) {
                            appScope.launch(Dispatchers.Main) { host.endpoint?.pause() }
                            appScope.launch { decideAfterWelcome(foreground) }
                            return@post
                        }
                        if (retreatedPlayButton(engine, foreground)) return@post
                    }
                    claimLocal(engine, newSelection)
                }
            }
        }

    /** エンジンのスレッドで: 退いた端末の再生ボタン。他の端末で鳴っていれば、前面なら引き取り、裏なら止めて知らせる。@return 処理した */
    private fun retreatedPlayButton(
        engine: ConnectClient,
        foreground: Boolean,
    ): Boolean {
        if (!engine.remotePlayingNow()) return false
        appScope.launch(Dispatchers.Main) { host.endpoint?.pause() }
        if (foreground && engine.requestTransfer()) {
            Log.i(WaveApplication.LOG_TAG, "connect-v2 play button became transfer")
        } else {
            effects.event(ClientEvent.ServerError(REMOTE_PLAYING))
            Log.i(WaveApplication.LOG_TAG, "connect-v2 play button blocked remote playing")
        }
        return true
    }

    /** 最新の状態を受けるまで（最大 WELCOME_WAIT_MS）待ってから、D-9 の判断をする。つなげなければ再生しないで知らせる。 */
    private suspend fun decideAfterWelcome(foreground: Boolean) {
        val before = welcomes.value
        relay.acquire()
        try {
            // 既につながっていて welcome を処理済みなら、待たない（中継に最新のカーソルが無いだけ）
            val welcomed = (before > 0 && relay.status.value == RelayClientV2.Status.Connected) || withTimeoutOrNull(WELCOME_WAIT_MS) { welcomes.first { it > before } } != null
            if (welcomed) {
                // welcome の状態は鎖をそろえてから開く（取り直しを待つ）
                withTimeoutOrNull(STATE_WAIT_MS) {
                    while (withContext(dispatcher) { client?.latestCursorBody() } == null) delay(100)
                }
            }
            withContext(dispatcher) {
                val engine = client ?: return@withContext
                if (retreatedPlayButton(engine, foreground)) return@withContext
                if (!welcomed) {
                    effects.event(ClientEvent.ServerError(RELAY_UNREACHABLE))
                    Log.i(WaveApplication.LOG_TAG, "connect-v2 play button relay unreachable")
                    return@withContext
                }
                // 他の端末で鳴っていない: 止めた再生を戻して、新しい再生として claim する
                runBlocking(Dispatchers.Main) { host.endpoint?.resume() }
                claimLocal(engine, newSelection = false)
            }
        } finally {
            relay.release()
        }
    }

    /** エンジンのスレッドで: 手元のキューと位置を置いて claim する。 */
    private fun claimLocal(
        engine: ConnectClient,
        newSelection: Boolean,
    ) {
        val (snapshot, cursor) =
            runBlocking(Dispatchers.Main) {
                val endpoint = host.endpoint
                endpoint?.snapshot()?.let(HandoffCodec::encode) to endpoint?.cursor()?.toLocal()
            }
        snapshot?.let(engine::setLocalQueue)
        cursor?.let(engine::setLocalCursor)
        val claimed = engine.userStartedPlayback()
        refreshInfo()
        Log.i(WaveApplication.LOG_TAG, "connect-v2 user started claimed=$claimed selection=$newSelection")
    }

    private fun pushCursor() {
        val cursor = host.endpoint?.cursor() ?: return
        post { client?.setLocalCursor(cursor.toLocal()) }
    }

    private fun ConnectV2Host.Cursor.toLocal() = ConnectClient.LocalCursor(currentItemId, positionMs, playing, repeat, listenedMs, historySent, SystemClock.elapsedRealtime())

    /**
     * キューの doc（置いた時点の曲）を、カーソルの今の曲・リピート・履歴に合わせる（レビュー p6b34 高-2）。
     * 今の曲がキューに無ければ取り込まない。窓（前20件・後300件）は今の曲で切り直し、位置は曲の長さで切り詰める（validate）。
     */
    private fun atCursor(
        snapshot: HandoffSnapshot,
        cursor: CursorBody,
        positionMs: Long,
    ): HandoffSnapshot? {
        val index = snapshot.items.indexOfFirst { it.itemId == cursor.currentItemId }
        if (index < 0) return null
        val start = (index - HandoffCodec.MAX_PLAYED_ITEMS).coerceAtLeast(0)
        val end = (index + 1 + HandoffCodec.MAX_UPCOMING_ITEMS).coerceAtMost(snapshot.items.size)
        val moved =
            snapshot.copy(
                items = snapshot.items.subList(start, end),
                currentItemId = cursor.currentItemId,
                positionMs = positionMs.coerceAtLeast(0),
                repeat = cursor.repeat,
                listenedMs = cursor.listenedMs.coerceAtLeast(0),
                historySent = cursor.historySent,
            )
        return (HandoffCodec.validate(moved, moved.handoffId) as? HandoffDecodeResult.Ok)?.snapshot
    }

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
            val engine = client ?: return@post
            if (engine.householdId != null) return@post
            // 次に受ける最初の鎖が自分の genesis なら受け入れる（レビュー p6b34 高-6）
            engine.beginCreateHousehold()
            val self = MemberChain.memberKeys(keys, keys.kxPub, Role.Player)
            val sent = relay.send(RelayV2.HouseholdCreate(MemberChain.genesis(keys, self))) && relay.send(RelayV2.ChainGet(0))
            // 送れなかった（中継に登録した鍵の端末でない・つながっていない）ことを画面に出す（レビュー p6b34 低-6）
            if (!sent) effects.event(ClientEvent.ServerError(HOUSEHOLD_CREATE_UNSENT))
            Log.i(WaveApplication.LOG_TAG, "connect-v2 household create sent=$sent")
        }

    /** 読めない保存を捨てて、家に加わり直す。 */
    fun resetUnreadable() =
        post {
            storage.reset()
            mutableUnreadable.value = false
            load()
        }

    /** 家から外された（中継が接続を断る）端末で、本人が押したら、この端末の家の記録を消して加わり直せるようにする。 */
    fun forgetHousehold() =
        post {
            client?.clearLocal()
            storage.reset()
            tagState = null
            lastTag = null
            load()
            Log.i(WaveApplication.LOG_TAG, "connect-v2 household forgotten")
        }

    // --- 加入（PairingV2Controller から） ---

    /** この端末の家の鍵の記述（Hello の拡張）。 */
    suspend fun selfMemberKeysJson(): String =
        kotlinx.coroutines.withContext(dispatcher) {
            xyz.mooma0750.wave.handoff.v2.ConnectJson.json.encodeToString(
                xyz.mooma0750.wave.handoff.v2.MemberKeys.serializer(),
                MemberChain.memberKeys(keys, keys.kxPub, Role.Player),
            )
        }

    /** A 側: Confirm の拡張（家に入っていなければ null）。 */
    suspend fun joinInfoJson(): String? =
        kotlinx.coroutines.withContext(dispatcher) {
            client?.joinInfo()?.let { xyz.mooma0750.wave.handoff.v2.ConnectJson.json.encodeToString(ConnectClient.JoinInfo.serializer(), it) }
        }

    /** A 側: 6桁の照合の後、B を家に加える。中継の返事を待つ。@return 加えたか */
    suspend fun addMember(
        memberKeysJson: String,
        peerPublicKey: ByteArray,
    ): Boolean {
        val added =
            kotlinx.coroutines.withContext(dispatcher) {
                val chain = client?.verifiedChain() ?: return@withContext null
                val member = runCatching { xyz.mooma0750.wave.handoff.v2.ConnectJson.json.decodeFromString(xyz.mooma0750.wave.handoff.v2.MemberKeys.serializer(), memberKeysJson) }.getOrNull() ?: return@withContext null
                if (member.role != Role.Player) return@withContext null
                // 6桁で確かめた相手の身元鍵と同じであること（レビュー p6b34 低-3）
                if (!runCatching { Base64.getUrlDecoder().decode(member.identityPub) }.getOrNull().contentEquals(peerPublicKey)) return@withContext null
                MemberChain.add(keys, chain, member) to member.deviceId
            } ?: return false
        val (record, memberId) = added
        return appendRecord(record) { members -> members.any { it.deviceId == memberId } }
    }

    /**
     * 家から他の端末を外す（player だけ、最後の player は外せない。中継はその端末の接続を切る）。@return 外したか
     * この端末自身を外すのは、まだ扱わない。
     */
    suspend fun removeMember(deviceId: String): Boolean {
        if (deviceId == identity.deviceId) return false
        val record =
            withContext(dispatcher) {
                val chain = client?.verifiedChain() ?: return@withContext null
                if (deviceId !in chain.members) return@withContext null
                MemberChain.remove(keys, chain, deviceId)
            } ?: return false
        val removed = appendRecord(record) { members -> members.none { it.deviceId == deviceId } }
        Log.i(WaveApplication.LOG_TAG, "connect-v2 remove member ok=$removed")
        return removed
    }

    /**
     * 鎖に追記して中継の返事を待つ。返事が来なかったら、鎖を取り直して done（今の端末一覧で済んだか）を見る
     * （中継では通っているのにやり直すと、二重の記録で必ず失敗する、レビュー p6b34 低-3）。
     */
    private suspend fun appendRecord(
        record: xyz.mooma0750.wave.handoff.v2.ChainRecord,
        done: (List<xyz.mooma0750.wave.handoff.v2.MemberKeys>) -> Boolean,
    ): Boolean {
        val reply =
            kotlinx.coroutines.coroutineScope {
                val reply =
                    async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(10_000) { chainReplies.first { it !is RelayV2.Error || it.requestId == null } }
                    }
                if (!relay.send(RelayV2.ChainAppend(record))) {
                    reply.cancel()
                    return@coroutineScope RelayV2.Error("unsent")
                }
                reply.await()
            }
        if (reply is RelayV2.ChainOk) return true
        if (reply != null) return false
        relay.send(RelayV2.ChainGet(0))
        return withTimeoutOrNull(5_000) {
            while (!withContext(dispatcher) { client?.members?.let(done) == true }) delay(200)
            true
        } == true
    }

    /** B 側: 6桁の照合の後、A の Confirm の床で加入を待つ。@return 待ち始めたか（保存が読めない・既に別の家に入っている等は false） */
    suspend fun expectJoin(
        joinInfoJson: String,
        inviterPublicKey: ByteArray,
    ): Boolean {
        val info = runCatching { xyz.mooma0750.wave.handoff.v2.ConnectJson.json.decodeFromString(ConnectClient.JoinInfo.serializer(), joinInfoJson) }.getOrNull() ?: return false
        val inviter = xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer.deviceId(inviterPublicKey)
        return withContext(dispatcher) {
            val engine = client ?: return@withContext false
            if (engine.householdId != null) return@withContext false
            engine.expectJoin(info, inviter)
            true
        }
    }

    /** 家に入っているか（加入の待ちが見る）。 */
    suspend fun inHousehold(): Boolean = withContext(dispatcher) { client?.householdId != null }

    /** 表示・debug 用の要約（件数と真偽値だけ）。 */
    fun summary(onResult: (String) -> Unit) =
        post {
            val engine = client
            onResult(
                "configured=true loaded=${engine != null} household=${engine?.householdId != null} members=${engine?.members?.size} " +
                    "writer=${engine?.isWriter} active_is_me=${engine?.activeDeviceId == identity.deviceId} retreated=${engine?.hasRetreated} relay=${relay.status.value} tag=${tagState != null}",
            )
        }

    // 状態（effects・listener）を作ってから始める（別のスレッドで load が先に走らないように）
    init {
        post { load() }
        // 接続に関係なく回す（transfer の期限・アカウントタグの計算、レビュー p6b34 中-4・高-4）
        appScope.launch {
            while (true) {
                post {
                    val engine = client ?: return@post
                    engine.tick()
                    refreshInfo()
                    engine.householdId?.let(::refreshAccountTagIfNeeded)
                }
                delay(TICK_MS)
            }
        }
    }

    companion object {
        /** 他の端末で再生中なので、再生ボタンで鳴らさなかった（D-9）。 */
        const val REMOTE_PLAYING = "remote_playing"

        /** 退いた端末の再生ボタンで、中継につなげず最新の状態を確かめられなかった（D-9）。 */
        const val RELAY_UNREACHABLE = "relay_unreachable"

        /** 家を作る要求を中継に送れなかった。 */
        const val HOUSEHOLD_CREATE_UNSENT = "household_create_unsent"

        const val TICK_MS = 1_000L
        const val CURSOR_DEBOUNCE_MS = 1_000L
        const val QUEUE_DEBOUNCE_MS = 1_000L
        const val WELCOME_WAIT_MS = 8_000L
        const val STATE_WAIT_MS = 2_000L
        const val TAG_LABEL = "wave-v2-account-tag"
        const val MAX_LABEL_CHARS = 64
    }
}
