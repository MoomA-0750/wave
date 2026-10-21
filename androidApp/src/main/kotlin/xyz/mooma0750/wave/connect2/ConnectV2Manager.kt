package xyz.mooma0750.wave.connect2

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.connect.DeviceIdentity
import xyz.mooma0750.wave.core.queue.toSong
import xyz.mooma0750.wave.core.session.WaveSession
import xyz.mooma0750.wave.handoff.HandoffCodec
import xyz.mooma0750.wave.handoff.HandoffDecodeResult
import xyz.mooma0750.wave.handoff.HandoffRepeat
import xyz.mooma0750.wave.handoff.HandoffSnapshot
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.ChainRecord
import xyz.mooma0750.wave.handoff.v2.ClientEvent
import xyz.mooma0750.wave.handoff.v2.ClientPersisted
import xyz.mooma0750.wave.handoff.v2.ConnectClient
import xyz.mooma0750.wave.handoff.v2.ConnectJson
import xyz.mooma0750.wave.handoff.v2.CursorBody
import xyz.mooma0750.wave.handoff.v2.MemberChain
import xyz.mooma0750.wave.handoff.v2.MemberKeys
import xyz.mooma0750.wave.handoff.v2.RelayV2
import xyz.mooma0750.wave.handoff.v2.Role
import xyz.mooma0750.wave.playback.ConnectV2Host

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
    /** 端末の表示名の既定の文言を引く（端末の言語に従う）。 */
    private val resources: android.content.res.Resources,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "connect-v2") }.asCoroutineDispatcher()
    private val keys = DeviceKeysV2(identity, File(storageFile.parentFile, "connect-v2-kx.bin"))
    private val storage = ConnectV2Storage(storageFile)

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
                        post { client?.onMessage(message) }
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

    /** 他の端末の最新の再生（表示）。 */
    data class RemoteView(
        val writer: String,
        val cursor: CursorBody,
        val accountMatches: Boolean,
    )

    private val mutableRemote = MutableStateFlow<RemoteView?>(null)
    val remote: StateFlow<RemoteView?> = mutableRemote

    private val mutableLibrary = MutableSharedFlow<xyz.mooma0750.wave.handoff.v2.LibraryBody>(extraBufferCapacity = 4)

    /** ほかの端末と合わせたライブラリ（RSS の登録・聴いたところ、ADR-0013）。手元の状態に取り込む。 */
    val libraryUpdates: SharedFlow<xyz.mooma0750.wave.handoff.v2.LibraryBody> = mutableLibrary

    /** 手元のライブラリが変わった。中継のものと合わせ、違えば書く。 */
    fun publishLibrary(body: xyz.mooma0750.wave.handoff.v2.LibraryBody) = post { client?.library?.publish(body) }

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
        /** 中継につながっている（中継の申告。表示だけ）。 */
        val online: Boolean = false,
        /** PC（端末が知らせてきた種類。表示の絵だけ）。 */
        val isPc: Boolean = false,
        /** wave-api（ほかのアプリ向けの API、ADR-0015）。音を出さないので「ここで再生」の行き先にしない。ログインを渡せる。 */
        val isApi: Boolean = false,
    )

    private val mutableMembers = MutableStateFlow<List<MemberView>>(emptyList())
    val members: StateFlow<List<MemberView>> = mutableMembers

    private val labelsJson = Json { ignoreUnknownKeys = true }

    @Volatile
    private var labels: Map<String, String> = emptyMap()

    /** 家の端末の種類（端末が知らせてきたもの。表示の絵だけ）。相手は中継につながったときにしか知らせないので、名前と同じくファイルに覚える。 */
    @Volatile
    private var kinds: Map<String, String> = emptyMap()

    private val kindsFile get() = File(labelsFile.parentFile, "connect-v2-kinds.json")

    private fun refreshInfo() {
        val engine = client
        maybeSendProfile(engine)
        mutableRemotePlayback.value = mirrorOf(engine)
        engine?.let { stageLatest(it, play = false) }
        writer = engine?.isWriter == true
        mutableInfo.value = Info(engine != null, engine?.householdId != null, engine?.members?.size ?: 0, engine?.isWriter == true, engine?.hasRetreated == true, engine?.storeResetPending == true)
        val known = labels
        val online = engine?.onlineDevices().orEmpty()
        mutableMembers.value =
            engine?.members.orEmpty().map { member ->
                val self = member.deviceId == identity.deviceId
                MemberView(member.deviceId, if (self) resources.getString(R.string.connect_v2_member_self) else known[member.deviceId] ?: resources.getString(R.string.connect_v2_member_unknown, member.deviceId.take(6)), self, member.role, member.deviceId in online, kinds[member.deviceId] == "pc", kinds[member.deviceId] == "api")
            }
    }

    /** 画面に映す、ほかの端末の再生（同じアカウントで、その端末が中継につながっているときだけ。この端末が鳴らしていれば null）。 */
    data class RemotePlayback(
        val deviceId: String,
        val deviceLabel: String,
        val items: List<xyz.mooma0750.wave.core.music.Song>,
        val itemIds: List<String>,
        val currentIndex: Int,
        val positionMs: Long,
        /** positionMs を取った時刻（elapsedRealtime）。 */
        val capturedAtMs: Long,
        val playing: Boolean,
        val repeat: HandoffRepeat,
        /** その端末の音量（PC のアプリの中の音量。スマホ・時計は音量を合わせないので載せない・受けない）（0〜100）。前の版の端末は付けない（null）。 */
        val volumePercent: Int? = null,
        /** 項目ごとの、自分で足した曲か（キューの画面の「キューの次の曲」とキューを消去）。 */
        val userAdded: List<Boolean> = emptyList(),
    ) {
        val current: xyz.mooma0750.wave.core.music.Song get() = items[currentIndex]

        /** 今の位置（再生中なら経過を足す。曲の長さで止める）。ライブには位置が無いので 0。 */
        fun positionAt(nowMs: Long): Long {
            if (current.isLive) return 0
            val position = if (playing) positionMs + (nowMs - capturedAtMs).coerceAtLeast(0) else positionMs
            return current.durationSeconds?.let { position.coerceAtMost(it * 1000L) } ?: position
        }
    }

    private val mutableRemotePlayback = MutableStateFlow<RemotePlayback?>(null)
    val remotePlayback: StateFlow<RemotePlayback?> = mutableRemotePlayback

    private var mirroredSnapshot: String? = null
    private var mirroredItems: List<xyz.mooma0750.wave.handoff.HandoffItem>? = null

    /** エンジンのスレッドで: 映す再生を作る（キューの doc は中身が変わったときだけ読み直す）。 */
    private fun mirrorOf(engine: ConnectClient?): RemotePlayback? {
        if (engine == null || engine.isWriter) return null
        val now = engine.remoteNow() ?: return null
        val tag = tagState?.tag ?: return null
        if (now.cursor.accountTag != tag || now.writer !in engine.onlineDevices()) return null
        // キューは引き取りの後も前の端末が書いた doc を指すので、書き手ではなくカーソルの queueRef で照らす
        val (_, queue) = engine.latestQueueBody() ?: return null
        if (engine.latestQueueHash() != now.cursor.queueRef) return null
        if (queue.snapshot !== mirroredSnapshot) {
            mirroredSnapshot = queue.snapshot
            mirroredItems = RemoteQueue.decode(queue.snapshot)?.items
        }
        val items = mirroredItems ?: return null
        val index = items.indexOfFirst { it.itemId == now.cursor.currentItemId }
        if (index < 0) return null
        val songs = items.map { it.toSong() }
        val label = labels[now.writer] ?: resources.getString(R.string.connect_v2_member_unknown, now.writer.take(6))
        return RemotePlayback(now.writer, label, songs, items.map { it.itemId }, index, now.positionMs, SystemClock.elapsedRealtime(), now.cursor.playing, now.cursor.repeat, now.cursor.volumePercent,
            items.map { it.origin == xyz.mooma0750.wave.handoff.HandoffOrigin.UserAdded },
        )
    }

    /** 前に合わせた家の最新の再生（同じ状態で置き直さない）。 */
    private var stagedKey: String? = null

    /**
     * エンジンのスレッドで: この端末が鳴らしていない間、手元のキューと位置を、家で最後に鳴った端末の再生（同じアカウント）に合わせておく。
     * 再生ボタンを押す前から正しいキューが出ていて、押せばそのまま続きから鳴る。
     * play なら（本人が手元で再生を押した直後）、今の位置まで合わせて鳴らしたままにする（この後 claim する）。
     */
    private fun stageLatest(
        engine: ConnectClient,
        play: Boolean,
    ) {
        // 再生サービスが動いていなければ、動き出してから合わせる（情報の更新のたびに呼ばれる）
        if (engine.isWriter || host.endpoint == null) return
        val state = tagState ?: return
        if (state.generation != session.loginGenerationId()) return
        val now = engine.remoteNow() ?: return
        if (now.cursor.accountTag != state.tag) return
        val (_, queue) = engine.latestQueueBody() ?: return
        if (engine.latestQueueHash() != now.cursor.queueRef) return
        // 鳴っている端末の位置は刻々と変わるので、曲が替わったとき・止まったときだけ合わせ直す（鳴らすときは必ず合わせる）
        val key = "${now.cursor.queueRef}|${now.cursor.currentItemId}|${if (now.cursor.playing) "playing" else now.positionMs / 1000}"
        if (!play && key == stagedKey) return
        val decoded = RemoteQueue.decode(queue.snapshot) ?: return
        val adjusted = RemoteQueue.atCursor(decoded, now.cursor, now.positionMs) ?: return
        val done =
            runBlocking(Dispatchers.Main) {
                val endpoint = host.endpoint ?: return@runBlocking false
                val local = endpoint.cursor()
                // 手元で鳴っている間は合わせない（鳴らしたのは本人。claim の判断に任せる）
                if (!play && local?.playing == true) return@runBlocking false
                val sameQueue = local?.currentItemId == now.cursor.currentItemId && endpoint.snapshot()?.let(HandoffCodec::encode) == queue.snapshot
                when {
                    // 同じキューの同じ曲なら、位置だけ合わせる
                    sameQueue -> {
                        if (kotlin.math.abs(local!!.positionMs - adjusted.positionMs) > POSITION_TOLERANCE_MS) endpoint.control(xyz.mooma0750.wave.handoff.v2.ControlAction.Seek, adjusted.positionMs)
                        true
                    }
                    play -> endpoint.adopt(adjusted, adjusted.positionMs, state.generation)
                    else -> endpoint.stage(adjusted, state.generation)
                }
            }
        if (done) {
            stagedKey = key
            Log.i(WaveApplication.LOG_TAG, "connect-v2 staged latest play=$play")
        }
    }

    /** ほかの端末に選ばれて引き取りを頼まれた時刻の期限（この間は画面が裏でも取り込む）。 */
    @Volatile
    private var remoteTransferUntil = 0L

    private var profileSentForMembers = -1
    private var lastRelayStatus: RelayClientV2.Status? = null

    /** 前に見た、中継につながっている端末（新しくつながってきた端末にも名前を知らせるため）。 */
    private var profileSeenOnline: Set<String> = emptySet()

    /**
     * エンジンのスレッドで: 中継につながったとき・家の端末が増えたとき・ほかの端末が新しくつながってきたときに、この端末の名前を家に知らせる
     * （知らせはその時つながっている端末にしか届かない）。
     */
    private fun maybeSendProfile(engine: ConnectClient?) {
        val status = relay.status.value
        if (status != lastRelayStatus) {
            lastRelayStatus = status
            if (status == RelayClientV2.Status.Connected) profileSentForMembers = -1
        }
        if (engine?.householdId == null || status != RelayClientV2.Status.Connected) return
        val count = engine.members.size
        val online = engine.onlineDevices().toSet()
        val newcomer = (online - profileSeenOnline - identity.deviceId).isNotEmpty()
        profileSeenOnline = online
        if (count == profileSentForMembers && !newcomer) return
        if (engine.sendProfile(android.os.Build.MODEL, "phone")) profileSentForMembers = count
    }

    /** デバイスの一覧で、ほかの端末を選んだ（その端末に再生を移す）。 */
    fun playOnDevice(deviceId: String) = post { Log.i(WaveApplication.LOG_TAG, "connect-v2 remote transfer requested=${client?.requestRemoteTransfer(deviceId)}") }

    /**
     * ほかの端末で鳴っている間に曲を選んだ: 再生サービスが音を出さずに用意したキューを家に置き、その端末で鳴らしてもらう。
     * 再生サービスから Main で呼ぶ。
     */
    fun playOnRemote(target: String) {
        val endpoint = host.endpoint ?: return
        val snapshot = endpoint.snapshot()?.let(HandoffCodec::encode) ?: return
        val cursor = endpoint.cursor()?.toLocal()?.copy(playing = false) ?: return
        post {
            val engine = client ?: return@post
            engine.setLocalQueue(snapshot)
            engine.setLocalCursor(cursor)
            Log.i(WaveApplication.LOG_TAG, "connect-v2 play on remote sent=${engine.playOn(target)}")
        }
    }

    /** 画面から: 鳴らしている端末へ遠隔操作を送る。 */
    fun control(
        action: xyz.mooma0750.wave.handoff.v2.ControlAction,
        positionMs: Long? = null,
    ) = post {
        val sent = client?.sendControl(action, positionMs)
        Log.i(WaveApplication.LOG_TAG, "connect-v2 control action=$action sent=$sent")
        if (sent != true) mutableEvents.tryEmit(ClientEvent.ServerError(CONTROL_UNSENT))
    }

    /** 画面・音量ボタンから: 鳴らしている端末の音量（PC のアプリの中の音量。スマホ・時計は音量を合わせないので載せない・受けない）を変える（0〜100）。 */
    fun volume(volumePercent: Int) =
        post {
            val sent = client?.sendVolume(volumePercent)
            Log.i(WaveApplication.LOG_TAG, "connect-v2 volume percent=$volumePercent sent=$sent")
            if (sent != true) mutableEvents.tryEmit(ClientEvent.ServerError(CONTROL_UNSENT))
        }

    /** 画面から: 鳴らしている端末のキューへ曲を足す（ほかの端末を映している間の「次に再生」「キューに追加」）。 */
    fun queueAdd(
        items: List<xyz.mooma0750.wave.handoff.HandoffItem>,
        next: Boolean,
    ) = post {
        val sent = client?.sendQueueAdd(items, next)
        Log.i(WaveApplication.LOG_TAG, "connect-v2 queue add items=${items.size} next=$next sent=$sent")
        if (sent != true) mutableEvents.tryEmit(ClientEvent.ServerError(CONTROL_UNSENT))
    }

    /** 画面から: 鳴らしている端末のキューを並べ替える（ほかの端末を映している間のキューの画面）。 */
    fun queueMove(
        itemId: String,
        afterItemId: String?,
    ) = post {
        val sent = client?.sendQueueMove(itemId, afterItemId)
        Log.i(WaveApplication.LOG_TAG, "connect-v2 queue move sent=$sent")
        if (sent != true) mutableEvents.tryEmit(ClientEvent.ServerError(CONTROL_UNSENT))
    }

    /** 画面から: 鳴らしている端末のキューから外す（「キューから削除」「キューを消去」）。 */
    fun queueRemove(itemIds: List<String>) =
        post {
            val sent = client?.sendQueueRemove(itemIds)
            Log.i(WaveApplication.LOG_TAG, "connect-v2 queue remove items=${itemIds.size} sent=$sent")
            if (sent != true) mutableEvents.tryEmit(ClientEvent.ServerError(CONTROL_UNSENT))
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

    /** 最後に見たログインの世代（エンジンのスレッドだけ）。変わったら手元の再生を終わらせる（再生サービスもキューを消している）。 */
    private var seenGeneration: Long? = null

    /** 本人の再生の意図の番号（再生ボタン・曲の選択のたびに増える）。D-9 の待ちの後に変わっていたら何もしない（再レビュー 中-C）。 */
    private val playIntent = java.util.concurrent.atomic.AtomicLong()

    /** D-9 の待ち（エンジンのスレッドだけで触る。1本だけ）。 */
    private var waitJob: Job? = null

    /** 最後に間引かずに書いたカーソルの、曲・再生中か・リピート（Main だけ）。再バッファのたびに書かない（再レビュー 低-N）。 */
    private var lastSignificant: Triple<String, Boolean, HandoffRepeat>? = null

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
        kinds = runCatching { labelsJson.decodeFromString<Map<String, String>>(kindsFile.readText()) }.getOrDefault(emptyMap())
    }

    private fun rememberKind(
        deviceId: String,
        kind: String,
    ) {
        kinds = kinds + (deviceId to kind)
        runCatching {
            val temp = File(kindsFile.parentFile, kindsFile.name + ".tmp")
            temp.writeText(labelsJson.encodeToString(kinds))
            if (!temp.renameTo(kindsFile)) temp.delete()
        }
    }

    /** 開発用: 家の端末（ID の頭か表示名）の種類を覚え直す（wave-api の行を、wave-api が加わる前に確かめる）。 */
    fun debugSetKind(
        device: String,
        kind: String,
    ) = post {
        val target = client?.members?.firstOrNull { it.deviceId.startsWith(device) || labels[it.deviceId] == device } ?: return@post
        rememberKind(target.deviceId, kind)
        refreshInfo()
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
        seenGeneration = session.loginGenerationId()
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
                val decoded = RemoteQueue.decode(snapshot) ?: return false
                val adjusted = RemoteQueue.atCursor(decoded, cursor, positionMs) ?: return false
                val requested = SystemClock.elapsedRealtime() < remoteTransferUntil
                return runBlocking(Dispatchers.Main) {
                    (uiInForeground() || requested) && session.loginGenerationId() == state.generation && host.endpoint?.adopt(adjusted, adjusted.positionMs, state.generation) == true
                }
            }

            override fun event(event: ClientEvent) {
                if (event is ClientEvent.Profile) {
                    val name = xyz.mooma0750.wave.handoff.crypto.Pairing.sanitizeName(event.name)
                    event.kind?.takeIf { it in KNOWN_KINDS && kinds[event.deviceId] != it }?.let { rememberKind(event.deviceId, it) }
                    if (labels[event.deviceId] != name) rememberLabel(event.deviceId, name) else refreshInfo()
                    return
                }
                if (event is ClientEvent.RemoteChanged) mutableRemote.value = RemoteView(event.writer, event.cursor, event.accountMatches)
                Log.i(WaveApplication.LOG_TAG, "connect-v2 event=${event::class.simpleName}")
                if (event is ClientEvent.Stopped) {
                    // ほかの端末が曲を選んで「この端末で鳴らして」と頼む流れでは、止められた直後に引き取りを頼まれる。
                    // そのときは「止めました」を知らせない（2秒待って、頼まれていなければ知らせる）
                    appScope.launch {
                        delay(STOPPED_NOTICE_DELAY_MS)
                        if (SystemClock.elapsedRealtime() >= remoteTransferUntil) mutableEvents.tryEmit(event)
                    }
                } else {
                    mutableEvents.tryEmit(event)
                }
                refreshInfo()
            }

            override fun persist(state: ClientPersisted) {
                runCatching { storage.save(state) }.onFailure { Log.i(WaveApplication.LOG_TAG, "connect-v2 persist failed type=${it::class.simpleName}") }
                refreshInfo()
            }

            override fun control(
                action: xyz.mooma0750.wave.handoff.v2.ControlAction,
                positionMs: Long?,
            ): Boolean {
                Log.i(WaveApplication.LOG_TAG, "connect-v2 control received action=$action")
                if (action == xyz.mooma0750.wave.handoff.v2.ControlAction.Transfer) {
                    // ほかの端末のデバイスの一覧でこの端末が選ばれた: 画面が裏にあっても、この後の取り込みを許す（15秒だけ）
                    remoteTransferUntil = SystemClock.elapsedRealtime() + REMOTE_TRANSFER_WINDOW_MS
                    return true
                }
                return runBlocking(Dispatchers.Main) { host.endpoint?.control(action, positionMs) == true }
            }

            override fun volume(volumePercent: Int): Boolean {
                Log.i(WaveApplication.LOG_TAG, "connect-v2 volume received percent=$volumePercent")
                return runBlocking(Dispatchers.Main) { host.endpoint?.volume(volumePercent) == true }
            }

            override fun queueAdd(
                items: List<xyz.mooma0750.wave.handoff.HandoffItem>,
                next: Boolean,
                afterItemId: String?,
            ): Boolean {
                val added = runBlocking(Dispatchers.Main) { host.endpoint?.queueAdd(items, next, afterItemId) == true }
                Log.i(WaveApplication.LOG_TAG, "connect-v2 queue add received items=${items.size} next=$next added=$added")
                return added
            }

            override fun queueMove(
                itemId: String,
                afterItemId: String?,
            ): Boolean {
                val moved = runBlocking(Dispatchers.Main) { host.endpoint?.queueMove(itemId, afterItemId) == true }
                Log.i(WaveApplication.LOG_TAG, "connect-v2 queue move received moved=$moved")
                return moved
            }

            override fun queueRemove(itemIds: List<String>): Boolean {
                val removed = runBlocking(Dispatchers.Main) { host.endpoint?.queueRemove(itemIds) == true }
                Log.i(WaveApplication.LOG_TAG, "connect-v2 queue remove received items=${itemIds.size} removed=$removed")
                return removed
            }

            override fun libraryChanged(body: xyz.mooma0750.wave.handoff.v2.LibraryBody) {
                Log.i(WaveApplication.LOG_TAG, "connect-v2 library feeds=${body.feeds.size} positions=${body.positions.size}")
                mutableLibrary.tryEmit(body)
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
                val next = TagState(householdId, AccountTag.of(householdId, account.id.reveal()), account.generationId)
                // 置くのはエンジンのスレッドで、ログインの切り替えの後始末の後（公開してから捨てるまでの間に、前のキューを新しいタグで書かない、再レビュー 中-E）
                post { onTagComputed(next) }
            } finally {
                tagComputing.set(false)
            }
        }
    }

    /** エンジンのスレッドで: ログインの世代が変わっていたら、手元の再生を終わらせる（前のアカウントのタグで一時停止を書いてから捨てる、再レビュー 中-D）。 */
    private fun checkLoginGeneration(engine: ConnectClient) {
        val generation = session.loginGenerationId()
        val previous = seenGeneration
        seenGeneration = generation
        if (previous == null || previous == generation) return
        val finalTag = tagState?.takeIf { it.generation == previous }?.tag
        tagState = null
        engine.endLocal(finalTag)
        Log.i(WaveApplication.LOG_TAG, "connect-v2 login changed ended local")
    }

    /** エンジンのスレッドで: タグを置き、保留した開始を claim する（手元のキューは今のものを置き直す、再レビュー 低-M）。 */
    private fun onTagComputed(state: TagState) {
        val engine = client ?: return
        checkLoginGeneration(engine)
        if (state.householdId != engine.householdId || state.generation != session.loginGenerationId()) return
        tagState = state
        if (!engine.isWriter) loadLocalFromPlayer(engine)
        engine.onAccountTagAvailable()
        // 起動の直後（ログインを確かめる前）に再生が押されると、保留した開始はログインの確かめで捨てられる。
        // 手元で鳴ったままなら、ここで判断し直す（しないと、ほかの端末が鳴っていても、この端末も書き手にならずに鳴り続ける）
        if (!engine.isWriter && runBlocking(Dispatchers.Main) { host.endpoint?.cursor()?.playing == true }) {
            Log.i(WaveApplication.LOG_TAG, "connect-v2 local playing without claim, deciding again")
            // 再生サービスからの知らせと同じく Main で呼ぶ（中で再生器を読む）
            appScope.launch(Dispatchers.Main) { listener.onUserStartedPlayback(newSelection = false) }
        }
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
                // 曲・再生中か・リピートが前と同じなら、再バッファなどなので間引く（再レビュー 低-N）
                val key = host.endpoint?.cursor()?.let { Triple(it.currentItemId, it.playing, it.repeat) }
                if (significant && key != lastSignificant) {
                    lastSignificant = key
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

            override fun onUserPaused() {
                playIntent.incrementAndGet()
            }

            override fun onUserStartedPlayback(newSelection: Boolean) {
                val intent = playIntent.incrementAndGet()
                val foreground = uiInForeground()
                val hasLocalQueue = host.endpoint?.cursor() != null
                post {
                    val engine = client ?: return@post
                    // 新しい操作が来たら、前の D-9 の待ちはやめる（曲を選んだのに、待ちの後で止めない、再レビュー 中-C）
                    waitJob?.cancel()
                    waitJob = null
                    val decision =
                        PlayButtonPolicy.onPlay(
                            newSelection = newSelection,
                            retreated = engine.hasRetreated,
                            inHousehold = engine.householdId != null,
                            stateSettled = engine.stateSettled,
                            hasAccountTag = effects.accountTag() != null,
                            remotePlaying = engine.remotePlayingNow(),
                            foreground = foreground,
                            hasLocalQueue = hasLocalQueue,
                        )
                    if (decision == PlayButtonPolicy.Decision.Wait) {
                        pauseOnMain()
                        waitJob = appScope.launch { decideAfterWait(intent, foreground) }
                        return@post
                    }
                    apply(engine, decision, newSelection)
                }
            }
        }

    /** エンジンのスレッドで: 判断のとおりに動く（Wait は呼ぶ側で扱う）。 */
    private fun apply(
        engine: ConnectClient,
        decision: PlayButtonPolicy.Decision,
        newSelection: Boolean,
    ) {
        when (decision) {
            PlayButtonPolicy.Decision.Claim -> claimLocal(engine, newSelection)
            PlayButtonPolicy.Decision.SyncAndClaim -> {
                // 家で最後に鳴った再生（今ほかの端末で鳴っていれば、その位置）に合わせてから、この端末の再生にする
                stageLatest(engine, play = true)
                claimLocal(engine, newSelection)
            }
            PlayButtonPolicy.Decision.ResumeAndClaim -> {
                // 待ちで止めた再生を戻して、新しい再生として claim する（家で最後に鳴ったキューに合わせてから）
                stageLatest(engine, play = false)
                runBlocking(Dispatchers.Main) { host.endpoint?.resume() }
                claimLocal(engine, newSelection)
            }
            PlayButtonPolicy.Decision.TakeOver -> {
                pauseOnMain()
                // 引き取れなければ（アカウント違い・状態が欠けている）、鳴らさずに知らせる
                if (engine.requestTransfer()) {
                    Log.i(WaveApplication.LOG_TAG, "connect-v2 play button became transfer")
                } else {
                    notifyRemotePlaying()
                }
            }
            PlayButtonPolicy.Decision.BlockRemotePlaying -> {
                pauseOnMain()
                notifyRemotePlaying()
            }
            PlayButtonPolicy.Decision.NotifyNotMember -> effects.event(ClientEvent.ServerError(NOT_MEMBER))
            PlayButtonPolicy.Decision.NotifyUnreachable -> effects.event(ClientEvent.ServerError(RELAY_UNREACHABLE))
            PlayButtonPolicy.Decision.NotifyUnverified -> effects.event(ClientEvent.ServerError(ACCOUNT_UNVERIFIED))
            PlayButtonPolicy.Decision.Wait, PlayButtonPolicy.Decision.Nothing -> Unit
        }
    }

    /**
     * 中継につないで、今の接続の最新の状態とアカウントタグがそろうまで（最大 SETTLE_WAIT_MS）待ってから、D-9 の判断をする。
     * そろわなければ鳴らさずに知らせる。家から外された・tailnet に届かないときは待たない（再レビュー 低-J）。
     */
    private suspend fun decideAfterWait(
        intent: Long,
        foreground: Boolean,
    ) {
        relay.holding {
            val outcome =
                withTimeoutOrNull(SETTLE_WAIT_MS) {
                    var step: PlayButtonPolicy.WaitOutcome? = null
                    while (step == null) {
                        val status = relay.status.value
                        step =
                            PlayButtonPolicy.waitStep(status == RelayClientV2.Status.NotMember, status == RelayClientV2.Status.TailnetUnreachable) {
                                withContext(dispatcher) { client?.let { it.stateSettled && effects.accountTag() != null } == true }
                            }
                        if (step == null) delay(100)
                    }
                    step
                } ?: withContext(dispatcher) { PlayButtonPolicy.timeoutOutcome(client?.stateSettled == true) }
            withContext(dispatcher) {
                val engine = client ?: return@withContext
                val decision =
                    PlayButtonPolicy.afterWait(
                        outcome = outcome,
                        intentUnchanged = playIntent.get() == intent,
                        stillRetreated = engine.hasRetreated,
                        remotePlaying = engine.remotePlayingNow(),
                        foreground = foreground,
                    )
                apply(engine, decision, newSelection = false)
                Log.i(WaveApplication.LOG_TAG, "connect-v2 play button waited outcome=$outcome decision=$decision")
            }
        }
    }

    /**
     * エンジンのスレッドで: 再生サービスの今のキューと位置をエンジンに置く。
     * Main で読んで待つ（Main はエンジンのスレッドを待たないので、ここで待ってよい）。位置を読んだ時刻は Main で取る（再レビュー 低-H）。
     */
    private fun loadLocalFromPlayer(engine: ConnectClient) {
        val (snapshot, cursor) =
            runBlocking(Dispatchers.Main) {
                val endpoint = host.endpoint
                endpoint?.snapshot()?.let(HandoffCodec::encode) to endpoint?.cursor()?.toLocal()
            }
        snapshot?.let(engine::setLocalQueue)
        cursor?.let(engine::setLocalCursor)
    }

    /** 再生サービスを Main で一時停止する（待たない）。 */
    private fun pauseOnMain() {
        appScope.launch(Dispatchers.Main) { host.endpoint?.pause() }
    }

    /** 他の端末で鳴っているので鳴らさなかった、と知らせる。 */
    private fun notifyRemotePlaying() {
        effects.event(ClientEvent.ServerError(REMOTE_PLAYING))
        Log.i(WaveApplication.LOG_TAG, "connect-v2 play button blocked remote playing")
    }

    /** エンジンのスレッドで: 手元のキューと位置を置いて claim する。 */
    private fun claimLocal(
        engine: ConnectClient,
        newSelection: Boolean,
    ) {
        loadLocalFromPlayer(engine)
        val claimed = engine.userStartedPlayback()
        refreshInfo()
        Log.i(WaveApplication.LOG_TAG, "connect-v2 user started claimed=$claimed selection=$newSelection")
    }

    /** Main で: 位置を読んだ時刻で手元のカーソルを作ってから渡す（再レビュー 低-H）。キューが空なら手元の再生を終わらせる（中-D）。 */
    private fun pushCursor() {
        val endpoint = host.endpoint ?: return
        val cursor = endpoint.cursor()?.toLocal()
        post {
            val engine = client ?: return@post
            if (cursor != null) engine.setLocalCursor(cursor) else engine.endLocal(effects.accountTag())
        }
    }

    private fun ConnectV2Host.Cursor.toLocal() = ConnectClient.LocalCursor(currentItemId, positionMs, playing, repeat, listenedMs, historySent, SystemClock.elapsedRealtime(), volumePercent)

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
            seenGeneration = null
            load()
            // 今の接続は前の家の welcome を受けたまま（storeId が分からない）なので、つなぎ直す
            relay.restart()
            Log.i(WaveApplication.LOG_TAG, "connect-v2 household forgotten")
        }

    // --- 加入（PairingV2Controller から） ---

    /** この端末の家の鍵の記述（Hello の拡張）。 */
    suspend fun selfMemberKeysJson(): String =
        kotlinx.coroutines.withContext(dispatcher) {
            ConnectJson.json.encodeToString(
                MemberKeys.serializer(),
                MemberChain.memberKeys(keys, keys.kxPub, Role.Player),
            )
        }

    /** A 側: Confirm の拡張（家に入っていなければ null）。 */
    suspend fun joinInfoJson(): String? =
        kotlinx.coroutines.withContext(dispatcher) {
            client?.joinInfo()?.let { ConnectJson.json.encodeToString(ConnectClient.JoinInfo.serializer(), it) }
        }

    /** A 側: 6桁の照合の後、B を家に加える。中継の返事を待つ。@return 加えたか */
    suspend fun addMember(
        memberKeysJson: String,
        peerPublicKey: ByteArray,
    ): Boolean {
        val added =
            kotlinx.coroutines.withContext(dispatcher) {
                val chain = client?.verifiedChain() ?: return@withContext null
                val member = runCatching { ConnectJson.json.decodeFromString(MemberKeys.serializer(), memberKeysJson) }.getOrNull() ?: return@withContext null
                if (member.role != Role.Player) return@withContext null
                // 6桁で確かめた相手の身元鍵と同じであること（レビュー p6b34 低-3）
                if (!runCatching { Base64.getUrlDecoder().decode(member.identityPub) }.getOrNull().contentEquals(peerPublicKey)) return@withContext null
                // 保存を作り直した端末が同じ鍵で加わり直す: 既にいるので追記しない（B は6桁で確かめた頭の鎖で受け入れる、再レビュー 中-F）
                if (chain.members[member.deviceId]?.keys?.kxPub == member.kxPub) return@withContext (null to member.deviceId)
                val record = MemberChain.add(keys, chain, member)
                // 積む前に、鎖として通ることを確かめる（相手の kx の署名が壊れていると、家の全端末がその鎖を拒んで止まる）
                val records = client?.verifiedRecords() ?: return@withContext null
                if (MemberChain.verify(records + record) !is xyz.mooma0750.wave.handoff.v2.ChainVerification.Ok) return@withContext null
                record to member.deviceId
            } ?: return false
        val (record, memberId) = added
        if (record == null) return true
        return appendRecord(record) { members -> members.any { it.deviceId == memberId } }
    }

    /**
     * 家から他の端末を外す（player だけ、最後の player は外せない。中継はその端末の接続を切る）。@return 外したか
     * この端末自身を外すのは、まだ扱わない。
     */
    /**
     * 家の wave-api にログインを渡す（ADR-0015）。本人が確かめて押したときだけ呼ぶ。宛先の鍵にだけ包む。
     * @return 送ったか（届いたかは分からない）
     */
    suspend fun grantLogin(
        deviceId: String,
        login: xyz.mooma0750.wave.core.auth.ExportedLogin,
    ): Boolean {
        if (deviceId == identity.deviceId) return false
        val sent =
            withContext(dispatcher) {
                client?.sendLoginGrant(deviceId, login.cookie.reveal(), login.visitorData, login.dataSyncId?.reveal(), login.obtainedAtEpochMs) == true
            }
        Log.i(WaveApplication.LOG_TAG, "connect-v2 login grant sent=$sent")
        return sent
    }

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
        record: ChainRecord,
        done: (List<MemberKeys>) -> Boolean,
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
        val info = runCatching { ConnectJson.json.decodeFromString(ConnectClient.JoinInfo.serializer(), joinInfoJson) }.getOrNull() ?: return false
        val inviter = EnvelopeSealer.deviceId(inviterPublicKey)
        return withContext(dispatcher) {
            val engine = client ?: return@withContext false
            if (engine.householdId != null) return@withContext false
            engine.expectJoin(info, inviter)
            true
        }
    }

    /** 加入の待ちをやめた（時間切れ・取り下げ）。 */
    fun cancelJoin() = post { client?.cancelJoin() }

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
                    checkLoginGeneration(engine)
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

        /** 退いた端末の再生ボタンで、中継がこの端末を家の端末として受け付けなかった。 */
        const val NOT_MEMBER = "not_member_play"

        /** 退いた端末の再生ボタンで、アカウントを確かめられず、他の端末で同じアカウントが鳴っているか決められなかった。 */
        const val ACCOUNT_UNVERIFIED = "account_unverified_play"

        /** 家を作る要求を中継に送れなかった。 */
        const val HOUSEHOLD_CREATE_UNSENT = "household_create_unsent"

        /** 遠隔操作を送れなかった。 */
        const val CONTROL_UNSENT = "control_unsent"

        /** ほかの端末に選ばれてから、画面が裏でも取り込んでよい時間。 */
        const val REMOTE_TRANSFER_WINDOW_MS = 15_000L

        /** 止められた知らせを出すまでの待ち（その間に引き取りを頼まれたら出さない）。 */
        const val STOPPED_NOTICE_DELAY_MS = 2_000L

        const val TICK_MS = 1_000L

        /** 合わせるとき、手元の位置がこれ以上ずれていればシークする。 */
        const val POSITION_TOLERANCE_MS = 3_000L
        const val CURSOR_DEBOUNCE_MS = 1_000L
        const val QUEUE_DEBOUNCE_MS = 1_000L
        const val SETTLE_WAIT_MS = 10_000L
        const val MAX_LABEL_CHARS = 64
        val KNOWN_KINDS = setOf("pc", "phone", "api")
    }
}
