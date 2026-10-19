import Foundation

/// 端末が保存しておくもの（D-2 の頭、D-4 の storeId と版の床、D-9 の退いた印、処理した claimId）。ConnectJSON で JSON にできる。
public struct ClientPersisted: Equatable, Sendable, ConnectCodable {
    public var chain: [ChainRecord]
    public var storeId: String?
    public var cursorFloor: Int64
    public var queueFloor: Int64
    public var retreated: Bool
    public var handledClaimIds: [String]

    /// 加入を待っている（Confirm の床と招いた端末）。プロセスが落ちても、無条件に最初の鎖を信じない。
    public var expectedJoin: ConnectClient.JoinInfo?

    public var expectedInviter: String?

    /// 家を作ると押した（最初の鎖が自分の genesis なら受け入れる）。
    public var creatingHousehold: Bool

    public init(
        chain: [ChainRecord] = [], storeId: String? = nil, cursorFloor: Int64 = 0, queueFloor: Int64 = 0, retreated: Bool = false,
        handledClaimIds: [String] = [], expectedJoin: ConnectClient.JoinInfo? = nil, expectedInviter: String? = nil, creatingHousehold: Bool = false
    ) {
        self.chain = chain
        self.storeId = storeId
        self.cursorFloor = cursorFloor
        self.queueFloor = queueFloor
        self.retreated = retreated
        self.handledClaimIds = handledClaimIds
        self.expectedJoin = expectedJoin
        self.expectedInviter = expectedInviter
        self.creatingHousehold = creatingHousehold
    }

    public func toJSON() -> JSON {
        .obj([
            "chain": JSON(items: chain), "storeId": JSON(storeId), "cursorFloor": JSON(cursorFloor), "queueFloor": JSON(queueFloor),
            "retreated": .bool(retreated), "handledClaimIds": JSON(strings: handledClaimIds), "expectedJoin": JSON(expectedJoin),
            "expectedInviter": JSON(expectedInviter), "creatingHousehold": .bool(creatingHousehold),
        ])
    }

    public static func fromJSON(_ json: JSON) throws -> ClientPersisted {
        let r = try FieldReader(json)
        return ClientPersisted(
            chain: try r.array("chain", ChainRecord.self, default: []), storeId: try r.optString("storeId"),
            cursorFloor: try r.long("cursorFloor", default: 0), queueFloor: try r.long("queueFloor", default: 0),
            retreated: try r.bool("retreated", default: false), handledClaimIds: try r.strings("handledClaimIds", default: []),
            expectedJoin: try r.optValue("expectedJoin", ConnectClient.JoinInfo.self), expectedInviter: try r.optString("expectedInviter"),
            creatingHousehold: try r.bool("creatingHousehold", default: false))
    }
}

public enum ClientEvent: Equatable, Sendable {
    /// ほかの端末の再生で、この端末が止まった。
    case stopped(by: String)

    /// ほかの端末の状態が変わった（表示）。accountMatches が false なら、別のアカウントの端末（表示だけ）。
    case remoteChanged(writer: String, cursor: CursorBody, accountMatches: Bool)

    /// 中継が、検証した一覧と同じ seq で別の記録を見せた。
    case chainForked

    case chainRolledBack

    /// 受けた記録が検証を通らなかった（改ざん・不正な追記）。
    case chainInvalid(reason: String)

    /// サーバーの状態が作り直された。acceptStoreReset を呼ぶまで、その状態で止まらない・取り込まない（L-12）。
    case storeReset(newStoreId: String)

    /// 同じ書き手・同じ版で中身が違う状態を見た（サーバーの不正か不具合）。
    case stateEquivocation

    case transferFailed(reason: String)

    case transferCompleted(from: String)

    case serverError(code: String)

    /// 家に入った（加入の鎖を受け入れた、または自分の genesis の鎖を受け入れた）。
    case joined

    /// 家の端末が表示名を知らせてきた（表示だけ）。
    case profile(deviceId: String, name: String, kind: String?)
}

/// ConnectClient が再生そのものと通知・保存を任せる先（Windows の ConnectClient.IEffects）。
public protocol ConnectClientEffects: AnyObject {
    /// 一時停止し、止めた時点の手元の再生を返す（最後のカーソルの位置を今に合わせる）。
    func pause() -> ConnectClient.LocalCursor?

    /// 取り込んで positionMs から再生を始める。受け付けなければ false（画面が前面でない・ログインが変わった等）。
    func adopt(snapshot: String, cursor: CursorBody, positionMs: Int64) -> Bool

    func event(_ clientEvent: ClientEvent)

    func persist(_ state: ClientPersisted)

    /// 今のログインの accountTag（Verified でなければ nil）。
    func accountTag() -> String?

    /// 遠隔操作に従う（この端末が鳴らしている）。受け付けなければ false。
    func control(_ action: ControlAction, positionMs: Int64?) -> Bool

    /// 遠隔操作を受け付けた（従う前に、送り主を記録するため）。
    func controlAccepted(_ action: ControlAction, from deviceId: String)

    /// 遠隔の音量に従う（この端末が鳴らしている）。アプリの中の音量を volumePercent（0〜100）にする。受け付けなければ false。
    func volume(_ volumePercent: Int) -> Bool

    /// 遠隔のキューへの追加に従う（この端末が鳴らしている）。afterItemId の曲があればその直後、無ければ next なら今の曲の直後、
    /// そうでなければ自分で足した曲のかたまりの最後へ、items の ID のまま入れる。受け付けなければ false。
    func queueAdd(_ items: [HandoffItem], next: Bool, afterItemId: String?) -> Bool

    /// ほかの端末と合わせたライブラリが届いた（手元に取り込む、ADR-0013）。
    func libraryChanged(_ body: LibraryBody)
}

public extension ConnectClientEffects {
    func control(_ action: ControlAction, positionMs: Int64?) -> Bool { false }

    func controlAccepted(_ action: ControlAction, from deviceId: String) {}

    func queueAdd(_ items: [HandoffItem], next: Bool, afterItemId: String?) -> Bool { false }

    func volume(_ volumePercent: Int) -> Bool { false }

    /// ほかの端末と合わせたライブラリが届いた（手元に取り込む、ADR-0013）。
    func libraryChanged(_ body: LibraryBody) {}
}

/// Connect v2 の端末側（Android 版の ConnectClient と同じ状態機械、D-2・D-6〜D-9）。
/// 受信・画面の操作・時間の経過（tick）を1本ずつ呼ぶこと（スレッドを跨いで同時に呼ばない。呼ぶ側が1本にそろえる）。
/// 暗号と署名はここで行い、再生そのものは effects に任せる。端末の時計は順序の判断に使わない（L-10）。
/// 署名・封じるのに失敗したとき（署名鍵が使えない・家から外された書き手が書こうとした）は、その呼び出しが投げる（Windows 版の例外と同じく、途中でやめる）。
public final class ConnectClient {
    public static let transferWaitMs: Int64 = 3_000
    public static let floorPersistMs: Int64 = 30_000
    public static let maxExtrapolationMs: Int64 = 9 * 60 * 1000
    private static let maxHandledClaims = 64

    /// 遠隔操作の送り手が見ていた版と、受け手の最新の書き込みの版の差の上限（それより古い操作には従わない）。
    private static let maxControlLag: Int64 = 20

    /// 遠隔操作の鮮度の上限（命令の見出しの時刻と受け手の時計の差）。
    private static let maxControlAgeMs: Int64 = 5 * 60 * 1000

    /// 開いた遠隔操作の doc のハッシュ（同じ doc の再送を開く前に捨てる）。
    private var seenControlDocs: [String] = []

    /// 表示用: ほかの端末の最新のカーソルと、今の時刻に合わせた位置。
    public struct RemoteNow: Equatable, Sendable {
        public let writer: String
        public let cursor: CursorBody
        public let positionMs: Int64
        public let version: Int64
    }

    /// 手元の再生の、カーソルに入れる値。
    /// capturedAtMs: positionMs を取った時刻（nowMs と同じ単調時計）。再生中なら、書くときに経過を足す。
    public struct LocalCursor: Equatable, Sendable {
        public var currentItemId: String
        public var positionMs: Int64
        public var playing: Bool
        public var `repeat`: HandoffRepeat
        public var listenedMs: Int64
        public var historySent: Bool
        public var capturedAtMs: Int64
        /// アプリの中の音量（0〜100）。
        public var volumePercent: Int?

        public init(
            currentItemId: String, positionMs: Int64, playing: Bool, repeat: HandoffRepeat, listenedMs: Int64, historySent: Bool, capturedAtMs: Int64,
            volumePercent: Int? = nil
        ) {
            self.volumePercent = volumePercent
            self.currentItemId = currentItemId
            self.positionMs = positionMs
            self.playing = playing
            self.repeat = `repeat`
            self.listenedMs = listenedMs
            self.historySent = historySent
            self.capturedAtMs = capturedAtMs
        }
    }

    /// 加入の Confirm で受け取った床（D-3）。6桁の照合で確かめた相手（inviter）から、認証された通信路で届いた値。
    public struct JoinInfo: Equatable, Sendable, ConnectCodable {
        public var householdId: String
        public var headSeq: Int64
        public var headHash: String
        public var storeId: String
        public var cursorFloor: Int64
        public var queueFloor: Int64

        public init(householdId: String, headSeq: Int64, headHash: String, storeId: String, cursorFloor: Int64, queueFloor: Int64) {
            self.householdId = householdId
            self.headSeq = headSeq
            self.headHash = headHash
            self.storeId = storeId
            self.cursorFloor = cursorFloor
            self.queueFloor = queueFloor
        }

        public func toJSON() -> JSON {
            .obj([
                "householdId": .string(householdId), "headSeq": JSON(headSeq), "headHash": .string(headHash), "storeId": .string(storeId),
                "cursorFloor": JSON(cursorFloor), "queueFloor": JSON(queueFloor),
            ])
        }

        public static func fromJSON(_ json: JSON) throws -> JoinInfo {
            let r = try FieldReader(json)
            return JoinInfo(
                householdId: try r.string("householdId"), headSeq: try r.long("headSeq"), headHash: try r.string("headHash"),
                storeId: try r.string("storeId"), cursorFloor: try r.long("cursorFloor"), queueFloor: try r.long("queueFloor"))
        }
    }

    private final class Opened<T> {
        let stored: StoredState
        let body: T
        let hash: String
        let receivedAtLocalMs: Int64

        init(_ stored: StoredState, _ body: T, _ hash: String, _ receivedAtLocalMs: Int64) {
            self.stored = stored
            self.body = body
            self.hash = hash
            self.receivedAtLocalMs = receivedAtLocalMs
        }

        var writer: String { stored.doc.header.writerId }
        var version: Int64 { stored.version }
    }

    /// sessionId: 送ったときのセッション（前のセッションの返事で、新しいセッションの startVersion を決めない）。
    private struct Inflight {
        let kind: StateKind
        let doc: SealedDoc
        let hash: String
        let cursor: CursorBody?
        let queue: QueueBody?
        let sessionId: Int64
    }

    private final class TransferState {
        let claimId: String
        let previousWriter: String
        let startedAtMs: Int64
        let retried: Bool
        var adopting = false

        init(claimId: String, previousWriter: String, startedAtMs: Int64, retried: Bool) {
            self.claimId = claimId
            self.previousWriter = previousWriter
            self.startedAtMs = startedAtMs
            self.retried = retried
        }
    }

    private final class Session {
        let id: Int64
        var arbiter: PlaybackArbiter.Session
        var claimId: String?
        var claimOrder: Int64?

        init(id: Int64, arbiter: PlaybackArbiter.Session, claimId: String?) {
            self.id = id
            self.arbiter = arbiter
            self.claimId = claimId
        }
    }

    private let signer: DeviceSigner
    private let opener: KxOpener
    private let role: Role
    private let send: (RelayV2) -> Bool
    private let effects: ConnectClientEffects
    private let nowMs: () -> Int64
    private let epochMs: () -> Int64
    private let newId: () -> String

    private var records: [ChainRecord]
    private var chain: VerifiedChain?
    private var storeId: String?
    private var pendingStoreId: String?
    private var cursorFloor: Int64
    private var queueFloor: Int64
    private var retreated: Bool
    private var handledClaims: [String]
    private var expectedJoin: (info: JoinInfo, inviter: String)?
    private var creatingHousehold: Bool

    /// タグ（Verified のアカウント）が無い間に本人が再生を始めた。タグができたら claim する。
    private var startWaitingForTag = false

    private var latestCursor: Opened<CursorBody>?
    private var latestQueue: Opened<QueueBody>?
    private var deferred: [StoredState] = []
    private var serverActive: String?
    private var pendingOwner: String?
    private var claimOrders: [String: Int64] = [:]

    /// 中継につながっている家の端末（中継の申告。表示と D-9 の判断だけに使い、止まる根拠にしない）。
    public private(set) var online: Set<String> = []

    /// 今の接続で welcome を処理した（切れたら false。メモリに残った古いカーソルを最新とみなさない）。
    private var welcomed = false

    /// 同時に始めた相手の証拠で、claimOrder を待って保留しているもの（D-8）。
    private var deferredEvidence: [PlaybackArbiter.Evidence] = []

    // 手元の再生
    private var localQueue: String?
    private var localQueueRef: String?
    private var local: LocalCursor?
    private var session: Session?
    private var sessionCounter: Int64 = 0
    private var deviceSeq: Int64 = 0
    private var lastOwnCursorHash: String?
    private var inflight: [String: Inflight] = [:]
    private var dirtyCursor = false
    private var dirtyQueue = false
    private var transfer: TransferState?

    /// つながり直した後、最新の状態と鎖をそろえてから書き込みを再開する。
    private var resumePending = false

    /// 版の床が上がるたびに保存しない。落ちても床が少し低いだけ（巻き戻しの窓が広がる）。
    private var floorDirty = false

    private var lastPersistMs: Int64?

    private var handOff: (target: String, startedAtMs: Int64)?
    private static let handOffWaitMs: Int64 = 10_000

    /// - Parameters:
    ///   - send: 中継へ送る。つながっていなくて送れなければ false
    ///   - nowMs: 単調時計（ミリ秒）。transfer の3秒と外挿の足し算にだけ使う
    ///   - epochMs: 表示用の時刻（startedAtEpochMs）
    public init(
        signer: DeviceSigner, opener: KxOpener, role: Role, send: @escaping (RelayV2) -> Bool, effects: ConnectClientEffects,
        nowMs: @escaping () -> Int64, epochMs: @escaping () -> Int64, newId: @escaping () -> String, initial: ClientPersisted? = nil
    ) {
        let initial = initial ?? ClientPersisted()
        self.signer = signer
        self.opener = opener
        self.role = role
        self.send = send
        self.effects = effects
        self.nowMs = nowMs
        self.epochMs = epochMs
        self.newId = newId
        records = initial.chain
        if records.isEmpty {
            chain = nil
        } else if case .ok(let verified) = MemberChain.verify(records) {
            chain = verified
        } else {
            chain = nil
        }
        storeId = initial.storeId
        cursorFloor = initial.cursorFloor
        queueFloor = initial.queueFloor
        retreated = initial.retreated
        handledClaims = initial.handledClaimIds
        if let info = initial.expectedJoin, let inviter = initial.expectedInviter {
            expectedJoin = (info, inviter)
        } else {
            expectedJoin = nil
        }
        creatingHousehold = initial.creatingHousehold
    }

    public var isWriter: Bool { session != nil }
    public var activeDeviceId: String? { serverActive }
    public var hasRetreated: Bool { retreated }

    /// 検証した家の ID（アカウントタグの計算に使う）。家に入っていなければ nil。
    public var householdId: String? { chain?.householdId }

    /// 検証した端末一覧（表示、加えた順）。
    public var members: [MemberKeys] { chain?.orderedMembers.map(\.keys) ?? [] }

    /// サーバーの状態が作り直されたと気づき、本人の確認を待っている（L-12）。
    public var storeResetPending: Bool { pendingStoreId != nil }

    /// 加入の手順で、家の鎖を使う（A が B を加えるときの add の署名に使う）。
    public func verifiedChain() -> VerifiedChain? { chain }

    /// 検証した鎖の記録（追記する前に、追記後の鎖が通るかを確かめるのに使う）。家に入っていなければ nil。
    public func verifiedRecords() -> [ChainRecord]? { chain == nil ? nil : records }

    /// 表示用の最新のカーソル（別アカウントのものを含む）。
    public func latestCursorBody() -> (writer: String, cursor: CursorBody)? { latestCursor.map { ($0.writer, $0.body) } }

    /// 表示用の最新のキュー（別アカウントのものを含む）。snapshot は HandoffCodec の文字列。カーソルの currentItemId を引くのに使う。
    public func latestQueueBody() -> (writer: String, queue: QueueBody)? { latestQueue.map { ($0.writer, $0.body) } }

    /// 表示用: 最新のキューの doc のハッシュ（カーソルの queueRef と照らす）。
    public func latestQueueHash() -> String? { latestQueue?.hash }

    /// 今の接続で最新の状態をそろえた（welcome を処理し、鎖の取り直しを待っている状態が無い）。D-9 の判断はこれが true のときだけ行う。
    public var stateSettled: Bool { welcomed && deferred.isEmpty }

    /// 同じアカウントの他の端末が今鳴っているか（D-9 の再生ボタンの判断）。最後のカーソルが再生中で、その端末が中継につながっているとき。
    public func remotePlayingNow() -> Bool {
        guard let cursor = latestCursor else { return false }
        guard let tag = effects.accountTag() else { return false }
        return cursor.writer != signer.deviceId && cursor.body.playing && cursor.body.accountTag == tag && online.contains(cursor.writer)
    }

    /// A 側: Confirm に入れる床（家に入っていなければ nil）。
    public func currentJoinInfo() -> JoinInfo? {
        guard let current = chain, let store = storeId else { return nil }
        return JoinInfo(
            householdId: current.householdId, headSeq: current.headSeq, headHash: current.headHash, storeId: store, cursorFloor: cursorFloor,
            queueFloor: queueFloor)
    }

    /// B 側: 加入を待つ。次に受ける鎖が、この家の ID・頭を含み、その後に inviter の署名で自分を加える記録があるときだけ受け入れる。
    /// storeId と版の床もここで決まる（最初に受けた welcome の storeId を無条件に信じない）。
    public func expectJoin(_ info: JoinInfo, inviterDeviceId: String) {
        if chain != nil { return }
        expectedJoin = (info, inviterDeviceId)
        creatingHousehold = false
        storeId = info.storeId
        cursorFloor = info.cursorFloor
        queueFloor = info.queueFloor
        persist()
    }

    // MARK: 受信

    /// ライブラリの同期（ADR-0013）。手元が変わったら library.publish を呼ぶ。
    public lazy var library: LibrarySync = LibrarySync(
        signer: signer, opener: opener, send: send, chain: { [unowned self] in self.chain },
        storeId: { [unowned self] in self.pendingStoreId == nil ? self.storeId : nil }, epochMs: epochMs, newId: newId,
        onRemote: { [unowned self] in self.effects.libraryChanged($0) })

    public func onMessage(_ message: RelayV2) throws {
        switch message {
        case .library, .libraryOk, .libraryConflict: try library.onMessage(message)
        case .welcome(let welcome): try onWelcome(welcome)
        case .chain(let c): try onChain(c.records)
        // 自分の追記の返事（chain_ok）は、他の端末への chain_changed を受けないので、同じく取り直す（宛先を書き直すため）
        case .chainChanged(let changed): onHeadAnnounced(changed.headSeq, changed.headHash)
        case .chainOk(let ok): onHeadAnnounced(ok.headSeq, ok.headHash)
        case .state(let state): try onState(state.stored)
        case .stateOk(let ok): try onStateOk(ok)
        case .stateConflict(let conflict): try onStateConflict(conflict)
        case .error(let error): try onError(error)
        case .yieldDelivery(let delivery): try onYield(delivery)
        case .control(let control): try onControl(control)
        case .activeChanged(let changed): try onActiveChanged(changed)
        case .pendingExpired(let expired): try onPendingExpired(expired.claimId)
        case .presence(let presence): online = Set(presence.online)
        default: break
        }
    }

    /// 接続が切れた。返事を待っていた書き込みは、つながり直した後に最新の版へ積み直す。
    public func onDisconnected() {
        resetConnection()
        library.onDisconnected()
        // 取り込む前に切れた引き取りは捨てる（つながり直した後に、古い状態から突然取り込まない）
        if let waiting = transfer, !waiting.adopting { failTransfer("disconnected") }
    }

    private func resetConnection() {
        for sent in inflight.values { markUnsent(sent.kind) }
        inflight.removeAll()
        serverActive = nil
        pendingOwner = nil
        online = []
        welcomed = false
    }

    /// 家を作ると押した。次に受ける最初の鎖が自分の genesis なら受け入れる。
    public func beginCreateHousehold() {
        if chain != nil { return }
        creatingHousehold = true
        // 途中でやめた加入の待ちを残さない
        expectedJoin = nil
        persist()
    }

    /// 加入の待ちをやめた（時間切れ・取り下げ）。後でつながったときに、黙って家に入らない。
    public func cancelJoin() {
        if chain != nil || expectedJoin == nil { return }
        expectedJoin = nil
        persist()
    }

    /// ログアウト・別のログインに変わった: 手元のキュー・再生・セッションを捨てる。
    public func clearLocal() {
        session = nil
        localQueue = nil
        localQueueRef = nil
        local = nil
        lastOwnCursorHash = nil
        dirtyCursor = false
        dirtyQueue = false
        inflight.removeAll()
        deferredEvidence.removeAll()
        startWaitingForTag = false
        resumePending = false
        let waiting = transfer
        transfer = nil
        if let waiting { _ = send(.release(.init(claimId: waiting.claimId))) }
    }

    /// 手元の再生が無くなった（ログインの世代が変わった・キューが空になった）。書き手なら、finalTag（その再生のアカウントのタグ）で
    /// 一時停止のカーソルを1回だけ送ってから、手元を捨てる。返事は待たない。
    public func endLocal(finalTag: String?) throws {
        if let current = chain, session != nil, local != nil, localQueueRef != nil, let finalTag, pendingStoreId == nil {
            if var body = cursorBodyFor(nil, tagOverride: finalTag) {
                body.playing = false
                let expect = max(latestCursor?.version ?? 0, cursorFloor)
                let doc = try DocSealer.seal(
                    signer: signer, chain: current, kind: .cursor, version: expect + 1, prevHash: lastOwnCursorHash, issuedAtEpochMs: epochMs(),
                    plaintext: ConnectJSON.encodeToUTF8(body))
                _ = send(.statePut(.init(requestId: newId(), kind: .cursor, expectVersion: expect, doc: doc)))
            }
        }
        clearLocal()
    }

    /// アカウントタグができた（Verified になった）。タグが無い間に始めた再生が続いていれば claim する。
    public func onAccountTagAvailable() throws {
        if !startWaitingForTag { return }
        startWaitingForTag = false
        if local?.playing == true && session == nil { _ = try userStartedPlayback() }
    }

    private func onHeadAnnounced(_ headSeq: Int64, _ headHash: String?) {
        if let current = chain, headSeq <= current.headSeq {
            if current.hashAt(headSeq) != headHash { effects.event(.chainForked) }
        } else {
            _ = send(.chainGet(.init(fromSeq: Int64(records.count))))
        }
    }

    private func onWelcome(_ welcome: RelayV2.Welcome) throws {
        // 前の接続の書き込みは届いていないかもしれない（返事を待っていたものは積み直す）。transfer は捨てない
        resetConnection()
        welcomed = true
        serverActive = welcome.activeDeviceId
        online = Set(welcome.online)
        for (device, order) in welcome.claimOrders { claimOrders[device] = order }
        if let known = storeId {
            if known != welcome.storeId {
                pendingStoreId = welcome.storeId
                effects.event(.storeReset(newStoreId: welcome.storeId))
            }
        } else {
            storeId = welcome.storeId
            persist()
        }
        resumePending = isWriter
        if let headSeq = welcome.headSeq { onHeadAnnounced(headSeq, welcome.headHash) }
        // 最新の状態を先に見る（通信を失ったまま鳴っていた端末は、ここで止まる）
        for state in welcome.states { try onState(state) }
        try maybeResume()
        library.onConnected()
    }

    /// 本人が「この状態から始める」を押した（L-12）。床を捨てて新しい storeId を受け入れる。
    public func acceptStoreReset() throws {
        guard let next = pendingStoreId else { return }
        storeId = next
        pendingStoreId = nil
        cursorFloor = 0
        queueFloor = 0
        latestCursor = nil
        latestQueue = nil
        persist()
        // 書き手なら、新しい保存に最初から書き直す
        if let mine = session {
            mine.arbiter = PlaybackArbiter.Session(base: 0, startVersion: nil)
            try sendClaimFor(mine)
            markDirty()
        }
        _ = send(.stateGet)
        try flush()
        library.onConnected()
    }

    private func onChain(_ newRecords: [ChainRecord]) throws {
        // 取得が重なって同じ記録が2回届いても、分岐にしない（seq で並べ、既に持っている分はハッシュを比べる）
        var fresh: [ChainRecord] = []
        for record in newRecords {
            let index = record.seq
            if index < 0 {
                effects.event(.chainInvalid(reason: "seq"))
                return
            }
            if index < Int64(records.count) {
                if MemberChain.hash(record) != MemberChain.hash(records[Int(index)]) {
                    effects.event(.chainForked)
                    return
                }
            } else if index == Int64(records.count + fresh.count) {
                fresh.append(record)
            } else {
                effects.event(.chainInvalid(reason: "gap"))
                return
            }
        }
        if fresh.isEmpty {
            try flush()
            return
        }
        let candidate = records + fresh
        let join = expectedJoin
        let trusted: MemberChain.Head?
        if let c = chain {
            trusted = MemberChain.Head(seq: c.headSeq, hash: c.headHash)
        } else if let j = join {
            trusted = MemberChain.Head(seq: j.info.headSeq, hash: j.info.headHash)
        } else {
            trusted = nil
        }
        switch MemberChain.verify(candidate, trusted: trusted) {
        case .ok(let result):
            let joining = chain == nil
            if joining {
                if let expected = join {
                    // 加入: 家の ID が Confirm と同じで、頭の後に inviter が自分を加えた記録があること
                    if result.householdId != expected.info.householdId {
                        effects.event(.chainForked)
                        return
                    }
                    // 招いた端末が自分を加えた記録があるか、6桁で確かめた A の頭の時点で自分が既に同じ鍵でいる
                    let existing = result.members[signer.deviceId]
                    let addedByInviter = candidate.dropFirst(Int(expected.info.headSeq + 1)).contains {
                        $0.type == .add && $0.target?.deviceId == signer.deviceId && $0.signerId == expected.inviter
                    }
                    let alreadyMember =
                        existing.map { $0.addedSeq <= expected.info.headSeq && Hpke.serializePublicKey($0.kx) == Hpke.serializePublicKey(opener.kxPub) }
                        ?? false
                    // まだ加えられていない（A の追記の前に取った）: 受け入れずに次の chain_changed を待つ
                    if !(addedByInviter || alreadyMember) { return }
                    expectedJoin = nil
                } else if creatingHousehold && candidate[0].target?.deviceId == signer.deviceId && candidate[0].type == .genesis {
                    // 家を作ると押した端末: 最初の記録が自分の genesis であること
                    creatingHousehold = false
                } else {
                    // 加入も家を作るのも待っていない: 中継が勝手に作った家を信じない（D-12）
                    effects.event(.chainInvalid(reason: "unexpected_household"))
                    return
                }
            }
            let headChanged = result.headHash != chain?.headHash
            records = candidate
            chain = result
            persist()
            // 書き手は、外した端末を宛先から外すために、次の書き込みを待たずに書き直す（D-2）
            if headChanged && isWriter { markDirty() }
            if joining { effects.event(.joined) }
            let pending = deferred
            deferred.removeAll()
            for state in pending { try onState(state) }
            try maybeResume()
            try flush()
            // 家に入ったばかりなら、つながったときの library_get は家の外として断られている。頼み直す
            if joining { library.onConnected() } else { try library.onChainUpdated() }
        case .fork:
            effects.event(.chainForked)
        case .rollback:
            effects.event(.chainRolledBack)
        case .invalid(_, let reason):
            effects.event(.chainInvalid(reason: reason))
        }
    }

    private func onState(_ stored: StoredState) throws {
        if let pending = pendingStoreId, stored.storeId == pending { return }
        if stored.storeId != storeId { return }
        guard let current = chain else {
            deferred.append(stored)
            return
        }
        // 開く前に、開いても捨てるもの（床より古い・持っている版より古い・持っているものと同じ）を除く（鍵交換の回数を減らす）
        if alreadyHave(stored) { return }
        let header: DocHeader
        let plaintext: Data
        switch DocSealer.open(stored.doc, chain: current, me: opener) {
        case .ok(let h, let p, _):
            header = h
            plaintext = p
        case .needChain:
            deferred.append(stored)
            _ = send(.chainGet(.init(fromSeq: Int64(records.count))))
            return
        case .fork:
            effects.event(.chainForked)
            return
        case .rejected:
            return
        }
        if header.version != stored.version || header.kind != stored.kind.docKind { return }
        let hash = DocSealer.hash(stored.doc)
        switch stored.kind {
        case .cursor:
            if stored.version < cursorFloor { return }
            if let existing = latestCursor, existing.version == stored.version {
                if existing.hash != hash && existing.writer == stored.doc.header.writerId { effects.event(.stateEquivocation) }
                return
            }
            if let existing = latestCursor, existing.version > stored.version { return }
            guard let body = ConnectJSON.decode(CursorBody.self, from: plaintext) else { return }
            let cursor = Opened(stored, body, hash, nowMs())
            latestCursor = cursor
            cursorFloor = stored.version
            persistFloor()
            try onCursor(cursor)
        case .queue:
            if stored.version < queueFloor { return }
            if (latestQueue?.version ?? -1) >= stored.version { return }
            guard let body = ConnectJSON.decode(QueueBody.self, from: plaintext) else { return }
            latestQueue = Opened(stored, body, hash, nowMs())
            queueFloor = stored.version
            persistFloor()
        }
    }

    /// 開いても捨てるだけの状態か（同じ版で中身が違うものは、署名を確かめて食い違いを知らせるために開く）。
    private func alreadyHave(_ stored: StoredState) -> Bool {
        switch stored.kind {
        case .cursor:
            if stored.version < cursorFloor { return true }
            guard let existing = latestCursor else { return false }
            return existing.version > stored.version || (existing.version == stored.version && existing.hash == DocSealer.hash(stored.doc))
        case .queue:
            return stored.version < queueFloor || (latestQueue?.version ?? -1) >= stored.version
        }
    }

    private func onCursor(_ cursor: Opened<CursorBody>) throws {
        if cursor.writer == signer.deviceId { return }
        let accountMatches = cursor.body.accountTag == effects.accountTag()
        effects.event(.remoteChanged(writer: cursor.writer, cursor: cursor.body, accountMatches: accountMatches))
        if let waiting = transfer, !waiting.adopting, cursor.writer == waiting.previousWriter, !cursor.body.playing {
            // 前の再生中の端末が最後のカーソル（一時停止）を置いた → すぐ取り込む
            try completeTransfer()
            return
        }
        if !accountMatches || !cursor.body.playing { return }
        _ = try decide(.playingCursor(from: cursor.writer, sessionBase: cursor.body.sessionBase, sessionStartVersion: cursor.body.sessionStartVersion))
    }

    private func onYield(_ delivery: RelayV2.YieldDelivery) throws {
        guard let current = chain else { return }
        guard case .ok(let header, let plaintext, _) = DocSealer.open(delivery.yieldDoc, chain: current, me: opener) else { return }
        guard case .yield(let command)? = ConnectJSON.decode(CommandBody.self, from: plaintext) else { return }
        if command.to != signer.deviceId || command.from != header.writerId || command.claimId != delivery.claimId { return }
        // yield を出せるのは player だけ
        guard let from = current.members[command.from], from.keys.role == .player else { return }
        if handledClaims.contains(command.claimId) { return }
        remember(command.claimId)
        // 別のアカウントの端末の再生では止まらない（L-11）
        if command.accountTag != effects.accountTag() { return }
        let wasWriter = isWriter
        let stopped = try decide(.yield(from: command.from, sessionBase: command.sessionBase))
        // 引き取り（transfer）なら、最後のカーソルを置く（相手はそれを待って取り込む）
        if stopped && wasWriter && pendingOwner == command.from { try sendYielded(command.claimId) }
    }

    private func onActiveChanged(_ message: RelayV2.ActiveChanged) throws {
        serverActive = message.activeDeviceId
        pendingOwner = message.pendingOwner
        let claimer = message.pendingOwner ?? message.activeDeviceId
        if message.claimId != nil, let claimer {
            claimOrders[claimer] = message.claimOrder
            if let mine = session, mine.claimId == message.claimId { mine.claimOrder = message.claimOrder }
        }
        // 順番が分かったので、保留していた証拠で決め直す
        if !deferredEvidence.isEmpty {
            let pending = deferredEvidence
            deferredEvidence.removeAll()
            for evidence in pending {
                if try decide(evidence) { break }
            }
        }
        // 自分が再生中になったら、断られていた書き込みを送る
        if message.activeDeviceId == signer.deviceId || message.pendingOwner == signer.deviceId { try flush() }
    }

    private func onPendingExpired(_ claimId: String) throws {
        guard let waiting = transfer, waiting.claimId == claimId else { return }
        transfer = nil
        if waiting.adopting && isWriter {
            // 取り込んで鳴っているのに、最初のカーソルが期限に間に合わなかった: 新しく始めたものとして claim し直す
            try restartSession()
        } else {
            effects.event(.transferFailed(reason: "expired"))
        }
    }

    /// - Returns: 書き手でなくなったか
    private func decide(_ evidence: PlaybackArbiter.Evidence) throws -> Bool {
        guard let mine = session else { return false }
        let otherOrder = claimOrders[evidence.from]
        let result = PlaybackArbiter.decide(myId: signer.deviceId, mine: mine.arbiter, evidence: evidence, myClaimOrder: mine.claimOrder, otherClaimOrder: otherOrder)
        switch result.decision {
        case .keep:
            return false
        case .defer:
            deferredEvidence.removeAll { $0.from == evidence.from }
            deferredEvidence.append(evidence)
            return false
        case .stop:
            break
        }
        let wasPlaying = local?.playing == true
        endSession()
        // 止めた時点の位置を取る（引き取られるときの最後のカーソルを、最後の出来事の位置にしない）
        if let paused = effects.pause() {
            local = paused
        } else if var l = local {
            l.positionMs = currentPosition(l)
            l.playing = false
            l.capturedAtMs = nowMs()
            local = l
        } else {
            local = nil
        }
        // 一時停止中なら鳴らしていないので、書き手でなくなるだけ（知らせない）
        if wasPlaying { effects.event(.stopped(by: evidence.from)) }
        return true
    }

    private func endSession() {
        session = nil
        retreated = true
        deferredEvidence.removeAll()
        dirtyCursor = false
        dirtyQueue = false
        persist()
    }

    private func sendYielded(_ claimId: String) throws {
        guard let current = chain else { return }
        guard let body = cursorBodyFor(nil) else { return }
        let expect = max(latestCursor?.version ?? 0, cursorFloor)
        let doc = try DocSealer.seal(
            signer: signer, chain: current, kind: .cursor, version: expect + 1, prevHash: lastOwnCursorHash, issuedAtEpochMs: epochMs(),
            plaintext: ConnectJSON.encodeToUTF8(body))
        _ = send(.yielded(.init(claimId: claimId, expectVersion: expect, doc: doc)))
    }

    private func onStateOk(_ ok: RelayV2.StateOk) throws {
        guard let sent = inflight.removeValue(forKey: ok.requestId) else { return }
        let stored = StoredState(
            kind: sent.kind, version: ok.version, doc: sent.doc, storeId: storeId ?? "", receivedAtServerMs: 0, serverNowMs: 0,
            activeDeviceId: serverActive)
        switch ok.kind {
        case .cursor:
            if let cursor = sent.cursor { latestCursor = Opened(stored, cursor, sent.hash, nowMs()) }
            cursorFloor = max(cursorFloor, ok.version)
            if let mine = session, mine.id == sent.sessionId, mine.arbiter.startVersion == nil {
                mine.arbiter.startVersion = ok.version
            }
            if let adopted = transfer, adopted.adopting {
                transfer = nil
                effects.event(.transferCompleted(from: adopted.previousWriter))
            }
        case .queue:
            if let queue = sent.queue { latestQueue = Opened(stored, queue, sent.hash, nowMs()) }
            queueFloor = max(queueFloor, ok.version)
        }
        persist()
        try flush()
        try maybeHandOff()
    }

    private func onStateConflict(_ conflict: RelayV2.StateConflict) throws {
        if let waiting = transfer, conflict.requestId == waiting.claimId {
            if let latest = conflict.latest { try onState(latest) }
            transfer = nil
            if !waiting.retried {
                _ = try requestTransfer(retried: true)
            } else {
                effects.event(.transferFailed(reason: "conflict"))
            }
            return
        }
        guard let sent = inflight[conflict.requestId] else { return }
        let latest = conflict.latest
        // 返事が落ちて送り直した自分の書き込みと同じものがサーバーにあるなら、成功とみなす
        if let latest, latest.doc.header.writerId == signer.deviceId, DocSealer.hash(latest.doc) == sent.hash {
            try onStateOk(RelayV2.StateOk(requestId: conflict.requestId, kind: sent.kind, version: latest.version))
            return
        }
        inflight.removeValue(forKey: conflict.requestId)
        if let latest { try onState(latest) }
        // 止まっていなければ、最新の版に積み直す
        if isWriter { markUnsent(sent.kind) }
        try flush()
    }

    private func onError(_ error: RelayV2.ErrorReply) throws {
        var sent: Inflight?
        if let requestId = error.requestId, let removed = inflight.removeValue(forKey: requestId) { sent = removed }
        switch error.code {
        // 書き込みを断られた: 持っている最新の証拠で止まる理由が無く、自分の claim が一番後（または誰も再生中でない）なら、すぐ claim し直す。
        // 他の端末の claim の方が後なら、その端末の証拠を待つ（断られるたびに奪い合わない）
        case "not_active":
            if let sent, isWriter {
                markUnsent(sent.kind)
                var stopped = false
                if let latest = latestCursor, latest.writer != signer.deviceId, latest.body.playing, latest.body.accountTag == effects.accountTag() {
                    stopped = try decide(
                        .playingCursor(from: latest.writer, sessionBase: latest.body.sessionBase, sessionStartVersion: latest.body.sessionStartVersion))
                }
                let mine = session
                let latestOther = claimOrders.filter { $0.key != signer.deviceId }.map(\.value).max()
                var mineIsLatest = false
                if let myOrder = mine?.claimOrder { mineIsLatest = latestOther == nil || myOrder > latestOther! }
                if !stopped && isWriter && (serverActive == nil || mineIsLatest) && mine?.claimOrder != nil { try restartSession() }
            }
        case "stale_head":
            if let sent, isWriter { markUnsent(sent.kind) }
            _ = send(.chainGet(.init(fromSeq: Int64(records.count))))
        default:
            if let requestId = error.requestId, requestId == transfer?.claimId {
                failTransfer(error.code)
            } else {
                effects.event(.serverError(code: error.code))
            }
        }
        // 断られた書き込みは、ここで送り直さない（次の状態・active_changed・鎖の取得で送る）
    }

    // MARK: 手元の操作

    /// 手元のキューが変わった（HandoffCodec.encode の文字列）。書き手なら置く。
    public func setLocalQueue(_ snapshot: String) throws {
        // 中身が同じなら置き直さない
        if snapshot == localQueue { return }
        localQueue = snapshot
        if isWriter {
            dirtyQueue = true
            // キューを置き直すので、カーソルも新しいキューを指し直す
            dirtyCursor = true
            try flush()
        }
    }

    /// 手元の再生の位置・再生中か等が変わった。書き手なら置く（間引きは呼ぶ側）。
    public func setLocalCursor(_ cursor: LocalCursor) throws {
        local = cursor
        if isWriter {
            dirtyCursor = true
            try flush()
        }
    }

    /// 本人が手元で新しく再生を始めた・再開した（L-3）。新しいセッションとして claim し、キューとカーソルを置く。
    /// - Returns: claim を送ったか（家に入っていない・Verified でない・controller・家の再生が別のアカウントなら false。呼ぶ側は手元だけで再生する）
    @discardableResult
    public func userStartedPlayback() throws -> Bool {
        if role != .player || chain == nil || storeId == nil || pendingStoreId != nil { return false }
        guard let tag = effects.accountTag() else {
            startWaitingForTag = true
            return false
        }
        // 家の最新の再生が別のアカウントなら、claim しない
        if let latest = latestCursor, latest.writer != signer.deviceId, latest.body.accountTag != tag { return false }
        retreated = false
        try restartSession()
        return true
    }

    /// 新しいセッションを始めて claim する（手元で始めた・書き込みを断られた・取り込みが期限に遅れた）。
    private func restartSession() throws {
        sessionCounter += 1
        let next = Session(id: sessionCounter, arbiter: PlaybackArbiter.Session(base: max(latestCursor?.version ?? 0, cursorFloor), startVersion: nil), claimId: nil)
        session = next
        deferredEvidence.removeAll()
        // 前のセッションの書き込みの返事は待たない（新しいセッションで積み直す）
        inflight.removeAll()
        persist()
        try sendClaimFor(next)
        markDirty()
        try flush()
    }

    private func sendClaimFor(_ target: Session) throws {
        guard let current = chain else { return }
        guard let tag = effects.accountTag() else { return }
        let claimId = newId()
        remember(claimId)
        target.claimId = claimId
        target.claimOrder = nil
        let to: String
        if let w = latestCursor?.writer, w != signer.deviceId { to = w } else { to = "" }
        let command = CommandBody.yield(.init(claimId: claimId, from: signer.deviceId, to: to, sessionBase: target.arbiter.base, accountTag: tag))
        _ = send(.claim(.init(claimId: claimId, yieldDoc: try sealCommand(current, command))))
    }

    /// つながり直した後: 最新の状態と鎖がそろっていて、まだ書き手なら、claim し直して積み直す。
    private func maybeResume() throws {
        if !resumePending || !deferred.isEmpty || chain == nil { return }
        resumePending = false
        guard let mine = session else { return }
        if serverActive != signer.deviceId {
            // 切れている間に始めた再生（claim が届いていない）や、切れている間に再生中でなくなった場合
            try sendClaimFor(mine)
        }
        markDirty()
        try flush()
    }

    /// 送った書き込みが届いていない・断られた: その種類を積み直す。
    private func markUnsent(_ kind: StateKind) {
        if kind == .cursor { dirtyCursor = true } else { dirtyQueue = true }
    }

    private func markDirty() {
        dirtyCursor = local != nil
        dirtyQueue = localQueue != nil
    }

    /// 「ここで再生」（D-7）。最新のカーソルとキューを開けていて、アカウントが一致するときだけ送る。
    /// 取り込みは、前の端末の最後のカーソルが届いたとき、または3秒後（tick）に行う。
    @discardableResult
    public func requestTransfer() throws -> Bool { try requestTransfer(retried: false) }

    /// 家の端末 target に「ここで再生（引き取り）」を頼む（デバイスの一覧で端末を選んだ）。
    @discardableResult
    public func requestRemoteTransfer(_ target: String) throws -> Bool {
        if pendingStoreId != nil || target == signer.deviceId { return false }
        guard let current = chain, let member = current.members[target], member.keys.role == .player else { return false }
        guard let tag = effects.accountTag(), let cursor = latestCursor, cursor.body.accountTag == tag else { return false }
        let command = CommandBody.control(
            .init(
                commandId: newId(), from: signer.deviceId, to: target, action: .transfer, positionMs: nil, basedOnVersion: cursor.version,
                accountTag: tag))
        return send(.control(.init(doc: try sealCommand(current, command))))
    }

    /// 手元のキューで家の再生を引き受け（音は出さない）、それを書き終えたら target に引き取ってもらう
    /// （ほかの端末で鳴っている間に曲を選んだら、その端末で鳴らす）。呼ぶ前に setLocalQueue・setLocalCursor（再生中でない）を置く。
    @discardableResult
    public func playOn(_ target: String) throws -> Bool {
        if target == signer.deviceId { return false }
        if try !userStartedPlayback() { return false }
        handOff = (target, nowMs())
        return true
    }

    private func maybeHandOff() throws {
        guard let waiting = handOff, session != nil else { return }
        guard let cursor = latestCursor, let queue = latestQueue else { return }
        if cursor.writer != signer.deviceId || queue.writer != signer.deviceId || cursor.body.queueRef != queue.hash { return }
        handOff = nil
        _ = try requestRemoteTransfer(waiting.target)
    }

    /// この端末の表示名を家のほかの端末に知らせる。名前は 64 文字（UTF-16 の符号単位）まで。
    @discardableResult
    public func sendProfile(_ name: String, kind: String? = nil) throws -> Bool {
        guard let current = chain, current.members[signer.deviceId] != nil else { return false }
        let short = name.utf16.count > 64 ? String(decoding: Array(name.utf16.prefix(64)), as: UTF16.self) : name
        return send(.control(.init(doc: try sealCommand(current, .profile(.init(from: signer.deviceId, name: short, kind: kind))))))
    }

    /// 遠隔操作を送る（鳴っていない端末から、今鳴らしている同じアカウントの端末へ）。
    /// - Returns: 送ったか（家の再生が自分・別のアカウント・最新の状態をまだ見ていないなら false）
    @discardableResult
    public func sendControl(_ action: ControlAction, positionMs: Int64? = nil) throws -> Bool {
        if isWriter || pendingStoreId != nil { return false }
        guard let current = chain else { return false }
        guard let tag = effects.accountTag() else { return false }
        guard let cursor = latestCursor, cursor.writer != signer.deviceId, cursor.body.accountTag == tag else { return false }
        let onItem: String?
        switch action {
        case .next, .previous, .seek: onItem = cursor.body.currentItemId
        default: onItem = nil
        }
        let command = CommandBody.control(
            .init(
                commandId: newId(), from: signer.deviceId, to: cursor.writer, action: action, positionMs: positionMs.map { max(0, $0) },
                basedOnVersion: cursor.version, accountTag: tag, onItemId: onItem))
        return send(.control(.init(doc: try sealCommand(current, command))))
    }

    /// 遠隔の音量を送る（鳴っていない端末から、今鳴らしている同じアカウントの端末へ。そのアプリの中の音量を 0〜100 にする）。
    @discardableResult
    public func sendVolume(_ volumePercent: Int) throws -> Bool {
        if isWriter || pendingStoreId != nil { return false }
        guard let current = chain else { return false }
        guard let tag = effects.accountTag() else { return false }
        guard let cursor = latestCursor, cursor.writer != signer.deviceId, cursor.body.accountTag == tag else { return false }
        let command = CommandBody.control(
            .init(
                commandId: newId(), from: signer.deviceId, to: cursor.writer, action: .volume, basedOnVersion: cursor.version, accountTag: tag,
                volumePercent: min(max(volumePercent, 0), 100)))
        return send(.control(.init(doc: try sealCommand(current, command))))
    }

    /// キューへの追加の命令1件の平文の上限（詰め物の1ブロック 4096 から長さの欄の 4 バイトを引き、余裕を持たせる。Android 版と同じ）。
    static let maxQueueAddPlaintext = 3800

    /// 遠隔のキューへの追加を送る（鳴っていない端末から、今鳴らしている同じアカウントの端末へ）。
    /// 1件の平文が詰め物の1ブロックに収まるよう分けて送り、2件目からは前の件の最後の曲の後ろに入れてもらう。
    /// - Returns: 全部送ったか（家の再生が自分・別のアカウント・最新の状態をまだ見ていない・1曲でも収まらないなら false）
    @discardableResult
    public func sendQueueAdd(_ items: [HandoffItem], next: Bool) throws -> Bool {
        if items.isEmpty || isWriter || pendingStoreId != nil { return false }
        guard let current = chain else { return false }
        guard let tag = effects.accountTag() else { return false }
        guard let cursor = latestCursor, cursor.writer != signer.deviceId, cursor.body.accountTag == tag else { return false }
        // 大きさは、ID の欄を UUID の長さで埋めた形で量る（実際の ID は送るときに振る）
        let placeholder = "00000000-0000-0000-0000-000000000000"
        func size(_ list: [HandoffItem]) -> Int {
            ConnectJSON.encodeToUTF8(
                CommandBody.queueAdd(
                    .init(
                        commandId: placeholder, from: signer.deviceId, to: cursor.writer, items: list, next: next, afterItemId: placeholder,
                        basedOnVersion: cursor.version, accountTag: tag))
            ).count
        }
        var chunks: [[HandoffItem]] = []
        var chunk: [HandoffItem] = []
        for item in items {
            if size([item]) > ConnectClient.maxQueueAddPlaintext { return false }
            if !chunk.isEmpty && size(chunk + [item]) > ConnectClient.maxQueueAddPlaintext {
                chunks.append(chunk)
                chunk = []
            }
            chunk.append(item)
        }
        chunks.append(chunk)
        var after: String?
        for part in chunks {
            let command = CommandBody.queueAdd(
                .init(
                    commandId: newId(), from: signer.deviceId, to: cursor.writer, items: part, next: next, afterItemId: after,
                    basedOnVersion: cursor.version, accountTag: tag))
            if !send(.control(.init(doc: try sealCommand(current, command)))) { return false }
            after = part.last?.itemId
        }
        return true
    }

    /// ログインを渡す命令の平文の上限（詰め物の2ブロック、中継の 16KB に収まる。Android 版と同じ）。
    static let maxLoginGrantPlaintext = 8000

    /// 家の端末 target（wave-api）にログインを渡す（ADR-0015）。本人が押したときだけ呼ぶ。target の受け取りの鍵にだけ包む。
    /// - Returns: 送ったか（target が家の player でない・自分・大きすぎるなら false）
    @discardableResult
    public func sendLoginGrant(to target: String, cookie: String, visitorData: String?, dataSyncId: String?, obtainedAtEpochMs: Int64) throws -> Bool {
        if pendingStoreId != nil || target == signer.deviceId { return false }
        guard let current = chain, current.members[signer.deviceId] != nil, current.members[target]?.keys.role == .player else { return false }
        let grant = CommandBody.loginGrant(
            .init(
                commandId: newId(), from: signer.deviceId, to: target, cookie: cookie, visitorData: visitorData, dataSyncId: dataSyncId,
                obtainedAtEpochMs: obtainedAtEpochMs))
        let plaintext = ConnectJSON.encodeToUTF8(grant)
        if plaintext.count > ConnectClient.maxLoginGrantPlaintext { return false }
        let doc = try DocSealer.seal(
            signer: signer, chain: current, kind: .command, version: 1, prevHash: nil, issuedAtEpochMs: epochMs(), plaintext: plaintext, only: [target])
        return send(.control(.init(doc: doc)))
    }

    /// 表示用: ほかの端末の最新のカーソル（別アカウントのものを含む）と、今の時刻に合わせた位置。
    public func remoteNowPosition() -> RemoteNow? {
        guard let cursor = latestCursor, cursor.writer != signer.deviceId else { return nil }
        return RemoteNow(writer: cursor.writer, cursor: cursor.body, positionMs: extrapolatedPosition(cursor), version: cursor.version)
    }

    /// 遠隔操作を受けた。自分宛て・送り主が署名した端末・処理していない ID・この端末が書き手・同じアカウント・
    /// 送り手が見ていた版がこの端末の最新の書き込みに近いときだけ従う（中継が取っておいた古い操作を後から流しても、版が離れていれば従わない）。
    private func onControl(_ message: RelayV2.Control) throws {
        guard let current = chain, message.doc.header.kind == .command else { return }
        // 中継が同じ命令を流し直しても、開く（鍵交換をする）前に捨てる。署名の対象のハッシュなので、再送なら必ず同じ値
        let docHash = DocSealer.hash(message.doc)
        if seenControlDocs.contains(docHash) { return }
        seenControlDocs.append(docHash)
        while seenControlDocs.count > ConnectClient.maxHandledClaims { seenControlDocs.removeFirst() }
        guard case .ok(let header, let plaintext, _) = DocSealer.open(message.doc, chain: current, me: opener) else { return }
        let body = ConnectJSON.decode(CommandBody.self, from: plaintext)
        if case .profile(let profile)? = body {
            if profile.from == header.writerId && profile.from != signer.deviceId && current.members[profile.from] != nil {
                effects.event(.profile(deviceId: profile.from, name: profile.name, kind: profile.kind))
            }
            return
        }
        if case .queueAdd(let add)? = body {
            onQueueAdd(add, header: header, chain: current)
            return
        }
        guard case .control(let command)? = body else { return }
        if command.to != signer.deviceId || command.from != header.writerId || command.from == signer.deviceId { return }
        // 出してから5分を超えた命令には従わない（中継が取っておいて後から届けるのを、時間でも縛る。順序の判断には時計を使わない）
        if abs(epochMs() - header.issuedAtEpochMs) > ConnectClient.maxControlAgeMs { return }
        // 遠隔操作を出せるのは player だけ（yield と同じ）
        guard let from = current.members[command.from], from.keys.role == .player else { return }
        if handledClaims.contains(command.commandId) { return }
        remember(command.commandId)
        if command.accountTag != effects.accountTag() { return }
        if command.action == .transfer {
            // 「ここで再生」を頼まれた: 書き手でなく、送り手が見ていた家の最新のカーソルが自分の見ているものに近いときだけ
            guard !isWriter, let latest = latestCursor else { return }
            if command.basedOnVersion > latest.version || latest.version - command.basedOnVersion > ConnectClient.maxControlLag { return }
            if effects.control(.transfer, positionMs: nil) { _ = try requestTransfer() }
            return
        }
        guard let mine = session else { return }
        guard let own = latestCursor, own.writer == signer.deviceId else { return }
        if command.basedOnVersion > own.version || own.version - command.basedOnVersion > ConnectClient.maxControlLag { return }
        // 今のセッションより前の見え方に基づく命令には従わない
        guard let sessionStart = mine.arbiter.startVersion, command.basedOnVersion >= sessionStart else { return }
        if command.action == .seek && command.positionMs == nil { return }
        if command.action == .volume {
            if let percent = command.volumePercent, (0...100).contains(percent) { _ = effects.volume(percent) }
            return
        }
        // 送り手が見ていた曲から、もう次の曲に変わっていたら従わない（遅れて届いた「次へ」で2曲飛ばさない）
        // 比べるのは手元で今鳴っている曲（中継に書いた最新より先に進んでいることがある）
        if let onItem = command.onItemId, onItem != (local?.currentItemId ?? own.body.currentItemId) { return }
        effects.controlAccepted(command.action, from: command.from)
        _ = effects.control(command.action, positionMs: command.positionMs)
    }

    /// 遠隔のキューへの追加を受けた。従う条件は再生・一時停止などの遠隔操作と同じ。
    private func onQueueAdd(_ command: CommandBody.QueueAdd, header: DocHeader, chain current: VerifiedChain) {
        if command.to != signer.deviceId || command.from != header.writerId || command.from == signer.deviceId { return }
        if abs(epochMs() - header.issuedAtEpochMs) > ConnectClient.maxControlAgeMs { return }
        guard let from = current.members[command.from], from.keys.role == .player else { return }
        if handledClaims.contains(command.commandId) { return }
        remember(command.commandId)
        if command.accountTag != effects.accountTag() || command.items.isEmpty { return }
        guard let mine = session else { return }
        guard let own = latestCursor, own.writer == signer.deviceId else { return }
        if command.basedOnVersion > own.version || own.version - command.basedOnVersion > ConnectClient.maxControlLag { return }
        guard let sessionStart = mine.arbiter.startVersion, command.basedOnVersion >= sessionStart else { return }
        _ = effects.queueAdd(command.items, next: command.next, afterItemId: command.afterItemId)
    }

    private func requestTransfer(retried: Bool) throws -> Bool {
        if role != .player || transfer != nil || pendingStoreId != nil { return false }
        guard let current = chain else { return false }
        guard let tag = effects.accountTag() else { return false }
        guard let cursor = latestCursor, let queue = latestQueue else { return false }
        if cursor.writer == signer.deviceId || cursor.body.accountTag != tag || queue.body.accountTag != tag || cursor.body.queueRef != queue.hash {
            return false
        }
        let claimId = newId()
        remember(claimId)
        transfer = TransferState(claimId: claimId, previousWriter: cursor.writer, startedAtMs: nowMs(), retried: retried)
        let command = CommandBody.yield(.init(claimId: claimId, from: signer.deviceId, to: cursor.writer, sessionBase: cursor.version, accountTag: tag))
        let doc: SealedDoc
        do {
            doc = try sealCommand(current, command)
        } catch {
            transfer = nil
            throw error
        }
        if !send(.transfer(.init(claimId: claimId, basedOnVersion: cursor.version, yieldDoc: doc))) {
            transfer = nil
            return false
        }
        return true
    }

    /// 時間の経過。transfer で前の端末から返事が無ければ、最後の状態から取り込む。
    public func tick() throws {
        if floorDirty && (lastPersistMs.map { nowMs() - $0 >= ConnectClient.floorPersistMs } ?? true) { persist() }
        // 引き受けたものを書けないまま10秒たったら、頼むのをやめる
        if let h = handOff, nowMs() - h.startedAtMs > ConnectClient.handOffWaitMs { handOff = nil }
        guard let waiting = transfer else { return }
        if !waiting.adopting && nowMs() - waiting.startedAtMs >= ConnectClient.transferWaitMs { try completeTransfer() }
    }

    private func completeTransfer() throws {
        guard let waiting = transfer else { return }
        guard let cursor = latestCursor, let queue = latestQueue, cursor.body.queueRef == queue.hash else {
            failTransfer("no_state", release: waiting.claimId)
            return
        }
        let position = extrapolatedPosition(cursor)
        waiting.adopting = true
        if !effects.adopt(snapshot: queue.body.snapshot, cursor: cursor.body, positionMs: position) {
            failTransfer("not_adopted", release: waiting.claimId)
            return
        }
        localQueue = queue.body.snapshot
        localQueueRef = queue.hash
        sessionCounter += 1
        let next = Session(id: sessionCounter, arbiter: PlaybackArbiter.Session(base: cursor.version, startVersion: nil), claimId: waiting.claimId)
        session = next
        if let order = claimOrders[signer.deviceId] { next.claimOrder = order }
        deferredEvidence.removeAll()
        retreated = false
        local = LocalCursor(
            currentItemId: cursor.body.currentItemId, positionMs: position, playing: true, repeat: cursor.body.repeat, listenedMs: cursor.body.listenedMs,
            historySent: cursor.body.historySent, capturedAtMs: nowMs())
        persist()
        // B の最初のカーソルで再生中が確定する（D-7）。キューは前の端末の doc をそのまま指す
        dirtyCursor = true
        try flush()
    }

    /// 引き取りをやめる（取り下げるときは中継に release を送る）。
    private func failTransfer(_ reason: String, release: String? = nil) {
        transfer = nil
        if let release { _ = send(.release(.init(claimId: release))) }
        effects.event(.transferFailed(reason: reason))
    }

    /// 経過はサーバーの時計で測り、手元で受けてからの時間を足す。9分を超える経過は一時停止として扱う（D-5）。
    private func extrapolatedPosition(_ cursor: Opened<CursorBody>) -> Int64 {
        if !cursor.body.playing { return cursor.body.positionMs }
        let elapsed = (cursor.stored.serverNowMs - cursor.stored.receivedAtServerMs) + (nowMs() - cursor.receivedAtLocalMs)
        if elapsed < 0 || elapsed > ConnectClient.maxExtrapolationMs { return cursor.body.positionMs }
        return cursor.body.positionMs + elapsed
    }

    // MARK: 書き込み

    private func flush() throws {
        guard let current = chain, let mine = session else { return }
        if resumePending { return }
        if dirtyQueue && !inflight.values.contains(where: { $0.kind == .queue }) {
            if let snapshot = localQueue, let tag = effects.accountTag() {
                let body = QueueBody(accountTag: tag, snapshot: snapshot)
                let expect = max(latestQueue?.version ?? 0, queueFloor)
                let doc = try DocSealer.seal(
                    signer: signer, chain: current, kind: .queue, version: expect + 1, prevHash: nil, issuedAtEpochMs: epochMs(),
                    plaintext: ConnectJSON.encodeToUTF8(body))
                let requestId = newId()
                let hash = DocSealer.hash(doc)
                if send(.statePut(.init(requestId: requestId, kind: .queue, expectVersion: expect, doc: doc))) {
                    dirtyQueue = false
                    localQueueRef = hash
                    inflight[requestId] = Inflight(kind: .queue, doc: doc, hash: hash, cursor: nil, queue: body, sessionId: mine.id)
                }
            }
        }
        // カーソルは、指すキューが置けてから書く（キューの送信中は待つ）
        if dirtyCursor && !inflight.values.contains(where: { $0.kind == .cursor || $0.kind == .queue }) && !dirtyQueue {
            guard let body = cursorBodyFor(mine) else { return }
            let expect = max(latestCursor?.version ?? 0, cursorFloor)
            let doc = try DocSealer.seal(
                signer: signer, chain: current, kind: .cursor, version: expect + 1, prevHash: lastOwnCursorHash, issuedAtEpochMs: epochMs(),
                plaintext: ConnectJSON.encodeToUTF8(body))
            let hash = DocSealer.hash(doc)
            let requestId = newId()
            if send(.statePut(.init(requestId: requestId, kind: .cursor, expectVersion: expect, doc: doc))) {
                dirtyCursor = false
                lastOwnCursorHash = hash
                inflight[requestId] = Inflight(kind: .cursor, doc: doc, hash: hash, cursor: body, queue: nil, sessionId: mine.id)
            }
        }
    }

    private func cursorBodyFor(_ mine: Session?, tagOverride: String? = nil) -> CursorBody? {
        guard let cursor = local else { return nil }
        guard let tag = tagOverride ?? effects.accountTag() else { return nil }
        guard let queueRef = localQueueRef else { return nil }
        deviceSeq += 1
        return CursorBody(
            queueRef: queueRef,
            currentItemId: cursor.currentItemId,
            positionMs: currentPosition(cursor),
            playing: cursor.playing && mine != nil,
            repeat: cursor.repeat,
            sessionBase: mine?.arbiter.base ?? 0,
            sessionStartVersion: mine?.arbiter.startVersion,
            deviceSeq: deviceSeq,
            listenedMs: cursor.listenedMs,
            historySent: cursor.historySent,
            accountTag: tag,
            startedAtEpochMs: epochMs(),
            volumePercent: cursor.volumePercent.map { min(max($0, 0), 100) })
    }

    /// 再生中なら、位置を取ってからの経過を足す（書くのが遅れても位置を戻さない）。
    private func currentPosition(_ cursor: LocalCursor) -> Int64 {
        cursor.playing ? cursor.positionMs + min(max(nowMs() - cursor.capturedAtMs, 0), ConnectClient.maxExtrapolationMs) : cursor.positionMs
    }

    private func sealCommand(_ current: VerifiedChain, _ command: CommandBody) throws -> SealedDoc {
        try DocSealer.seal(
            signer: signer, chain: current, kind: .command, version: 1, prevHash: nil, issuedAtEpochMs: epochMs(),
            plaintext: ConnectJSON.encodeToUTF8(command))
    }

    private func remember(_ claimId: String) {
        handledClaims.append(claimId)
        while handledClaims.count > ConnectClient.maxHandledClaims { handledClaims.removeFirst() }
    }

    private func persistFloor() {
        if let last = lastPersistMs, nowMs() - last < ConnectClient.floorPersistMs {
            floorDirty = true
            return
        }
        persist()
    }

    private func persist() {
        floorDirty = false
        lastPersistMs = nowMs()
        effects.persist(
            ClientPersisted(
                chain: records,
                storeId: storeId,
                cursorFloor: cursorFloor,
                queueFloor: queueFloor,
                retreated: retreated,
                handledClaimIds: handledClaims,
                expectedJoin: expectedJoin?.info,
                expectedInviter: expectedJoin?.inviter,
                creatingHousehold: creatingHousehold))
    }
}
