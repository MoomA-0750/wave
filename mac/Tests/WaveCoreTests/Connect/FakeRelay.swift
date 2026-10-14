import Foundation
import XCTest

@testable import WaveCore

/// 端末なしの多端末テストの中継（relay の HouseholdStore・HouseholdRouter を最小に写したもの。家は1つ、メモリだけ）。
/// 届けるのは run() のときだけ（決まった順）。送受信はどちらも JSON の文字列を通す（ワイヤの形も確かめる）。
final class FakeRelay {
    static let pendingMs: Int64 = 10_000

    private struct StoredDoc {
        let version: Int64
        let doc: SealedDoc
        let receivedAtServerMs: Int64
    }

    private struct Pending {
        let claimId: String
        let owner: String
        let previousActive: String?
        let untilMs: Int64
    }

    private let nowMs: () -> Int64
    let storeId = B64.encode(WaveCrypto.randomBytes(16))
    private var records: [ChainRecord] = []
    private var chain: VerifiedChain?
    private var cursor: StoredDoc?
    private var queue: StoredDoc?
    private var active: String?
    private var claimOrder: Int64 = 0
    private var claimOrders: [(String, Int64)] = []
    private var pending: Pending?

    /// つながっている端末（つないだ順）。
    private var connected: [(id: String, deliver: (RelayV2) throws -> Void)] = []
    private var outbox: [(to: String, text: String)] = []
    private var held: [(to: String, text: String)] = []

    /// true を返すと、releaseHeld まで届けない。
    var hold: (String, RelayV2) -> Bool = { _, _ in false }

    var activeDeviceId: String? { active }

    /// ライブラリの命令を知っている新しい中継か（false なら古い中継と同じく unsupported を返す、ADR-0013）。
    var supportsLibrary = false
    private var library: StoredDoc?
    /// library_get を送った接続（ライブラリはこの接続にだけ配る）。
    private var librarySubscribers: Set<String> = []
    /// 中継がライブラリだけを失った（同じ保存のまま）。
    func dropLibrary() { library = nil }
    /// 中継が持つ最新のライブラリ（試験で見る）。
    var libraryState: LibraryState? { library.map { LibraryState(version: $0.version, doc: $0.doc, storeId: storeId) } }

    init(nowMs: @escaping () -> Int64) { self.nowMs = nowMs }

    private var onlineIds: [String] { connected.map(\.id) }

    func connect(_ deviceId: String, deliver: @escaping (RelayV2) throws -> Void) {
        connected.removeAll { $0.id == deviceId }
        librarySubscribers.remove(deviceId)
        connected.append((deviceId, deliver))
        post(deviceId, .welcome(welcome(deviceId)))
        broadcast(.presence(.init(online: onlineIds)))
    }

    func disconnect(_ deviceId: String) {
        connected.removeAll { $0.id == deviceId }
        librarySubscribers.remove(deviceId)
        broadcastExpired(expirePending(deviceId))
        broadcast(.presence(.init(online: onlineIds)))
    }

    func tick() { broadcastExpired(expirePending(nil)) }

    func releaseHeld() {
        outbox.append(contentsOf: held)
        held.removeAll()
    }

    func run() throws {
        var guardCount = 0
        while !outbox.isEmpty {
            guardCount += 1
            if guardCount > 10_000 { throw NSError(domain: "FakeRelay", code: 1, userInfo: [NSLocalizedDescriptionKey: "メッセージが止まらない"]) }
            let (to, text) = outbox.removeFirst()
            guard let deliver = connected.first(where: { $0.id == to })?.deliver else { continue }
            guard let message = RelayV2.decode(text) else { throw NSError(domain: "FakeRelay", code: 2, userInfo: [NSLocalizedDescriptionKey: "読めない: " + text]) }
            if hold(to, message) {
                held.append((to, text))
                continue
            }
            try deliver(message)
        }
    }

    /// 端末から届いたものを覗く（テストで、敵対的な中継が取っておく命令を得る）。
    var onFromClient: ((String, RelayV2) -> Void)?

    /// 敵対的な中継: 端末へ任意のメッセージを差し込む。
    func inject(_ to: String, _ message: RelayV2) { post(to, message) }

    /// - Returns: 中継に届いたか（つながっていなければ false）
    @discardableResult
    func fromClient(_ deviceId: String, _ message: RelayV2) -> Bool {
        if !connected.contains(where: { $0.id == deviceId }) { return false }
        onFromClient?(deviceId, message)
        let text = RelayV2.encode(message)
        precondition(text.count <= RelayV2.maxFrameChars, "大きすぎる")
        guard let decoded = RelayV2.decode(text) else { preconditionFailure("読めない: " + text) }
        route(deviceId, decoded)
        return true
    }

    private func post(_ to: String, _ message: RelayV2) { outbox.append((to, RelayV2.encode(message))) }

    private func broadcast(_ message: RelayV2, except: String? = nil) {
        for id in onlineIds where id != except { post(id, message) }
    }

    private func welcome(_ deviceId: String) -> RelayV2.Welcome {
        RelayV2.Welcome(
            deviceId: deviceId, storeId: storeId, householdId: chain?.householdId, headSeq: chain?.headSeq, headHash: chain?.headHash,
            activeDeviceId: active, online: onlineIds, states: latest(), claimOrders: claimOrders)
    }

    private func latest() -> [StoredState] { [stateOf(.cursor), stateOf(.queue)].compactMap { $0 } }

    private func stateOf(_ kind: StateKind) -> StoredState? {
        guard let stored = kind == .cursor ? cursor : queue else { return nil }
        return StoredState(
            kind: kind, version: stored.version, doc: stored.doc, storeId: storeId, receivedAtServerMs: stored.receivedAtServerMs, serverNowMs: nowMs(),
            activeDeviceId: active)
    }

    private func setClaimOrder(_ device: String, _ order: Int64) {
        if let i = claimOrders.firstIndex(where: { $0.0 == device }) { claimOrders[i].1 = order } else { claimOrders.append((device, order)) }
    }

    private func route(_ sender: String, _ message: RelayV2) {
        func reply(_ m: RelayV2) { post(sender, m) }
        switch message {
        case .householdCreate(let create):
            guard chain == nil, create.genesis.signerId == sender, case .ok(let created) = MemberChain.verify([create.genesis]) else {
                reply(.error(.init(code: "household_exists")))
                return
            }
            records.append(create.genesis)
            chain = created
            reply(.chainOk(.init(headSeq: created.headSeq, headHash: created.headHash)))
        case .chainGet(let get):
            let from = Int(min(max(get.fromSeq, 0), Int64(records.count)))
            reply(.chain(.init(records: Array(records[from...]))))
        case .chainAppend(let append):
            guard let current = chain, current.members[sender] != nil, append.record.signerId == sender else {
                reply(.error(.init(code: "not_member")))
                return
            }
            if append.record.seq != current.headSeq + 1 || append.record.prevHash != current.headHash {
                reply(.chainConflict(.init(headSeq: current.headSeq, headHash: current.headHash)))
                return
            }
            guard case .ok(let ok) = MemberChain.verify(records + [append.record], trusted: MemberChain.Head(seq: current.headSeq, hash: current.headHash))
            else {
                reply(.error(.init(code: "invalid_record")))
                return
            }
            records.append(append.record)
            chain = ok
            reply(.chainOk(.init(headSeq: ok.headSeq, headHash: ok.headHash)))
            broadcast(.chainChanged(.init(headSeq: ok.headSeq, headHash: ok.headHash)), except: sender)
        case .stateGet:
            for state in latest() { reply(.state(.init(stored: state))) }
        case .statePut(let put):
            onPut(sender, put)
        case .claim(let claim):
            onClaim(sender, claim.claimId, claim.yieldDoc, transferBasedOn: nil)
        case .transfer(let transfer):
            onClaim(sender, transfer.claimId, transfer.yieldDoc, transferBasedOn: transfer.basedOnVersion)
        case .yielded(let yielded):
            onYielded(sender, yielded)
        case .control(let control):
            // 書き手が送り主で、命令の doc であるときだけ配る（中継の HouseholdRouter と同じ）
            if control.doc.header.writerId == sender && control.doc.header.kind == .command {
                broadcast(message, except: sender)
            } else {
                reply(.error(.init(code: "bad_control")))
            }
        case .release(let release):
            if let waiting = pending, waiting.claimId == release.claimId, waiting.owner == sender { broadcast(.activeChanged(dropPending())) }
        case .libraryGet where supportsLibrary:
            guard chain?.members[sender] != nil else {
                reply(.error(.init(code: "not_member")))
                return
            }
            librarySubscribers.insert(sender)
            reply(.library(.init(state: libraryState)))
        case .libraryPut(let put) where supportsLibrary:
            onLibraryPut(sender, put)
        default:
            reply(.error(.init(code: "unsupported")))
        }
    }

    /// 中継の HouseholdStore.putLibrary と同じ: 家のどの端末も書ける。版の CAS。
    private func onLibraryPut(_ sender: String, _ put: RelayV2.LibraryPut) {
        func reply(_ m: RelayV2) { post(sender, m) }
        guard let current = chain else { return reply(.error(.init(code: "no_household", requestId: put.requestId))) }
        guard current.members[sender] != nil else { return reply(.error(.init(code: "not_member", requestId: put.requestId))) }
        let header = put.doc.header
        let problem: String? =
            if header.writerId != sender { "writer_mismatch" }
            else if header.householdId != current.householdId { "household" }
            else if header.kind != .library { "kind" }
            else if header.version != put.expectVersion + 1 { "version" }
            else if header.headSeq > current.headSeq { "future_head" }
            else if header.headSeq < current.headSeq { "stale_head" }
            else { nil }
        if let problem { return reply(.error(.init(code: problem, requestId: put.requestId))) }
        if (library?.version ?? 0) != put.expectVersion { return reply(.libraryConflict(.init(requestId: put.requestId, latest: libraryState))) }
        library = StoredDoc(version: put.expectVersion + 1, doc: put.doc, receivedAtServerMs: nowMs())
        reply(.libraryOk(.init(requestId: put.requestId, version: put.expectVersion + 1)))
        for id in onlineIds where id != sender && librarySubscribers.contains(id) { post(id, .library(.init(state: libraryState))) }
    }

    private func headerProblem(_ sender: String, _ current: VerifiedChain, _ kind: StateKind, _ expectVersion: Int64, _ doc: SealedDoc) -> String? {
        let header = doc.header
        if header.writerId != sender { return "writer_mismatch" }
        if header.householdId != current.householdId { return "household" }
        if header.kind != kind.docKind { return "kind" }
        if header.version != expectVersion + 1 { return "version" }
        if header.headSeq > current.headSeq { return "future_head" }
        if header.headSeq < current.headSeq { return "stale_head" }
        return nil
    }

    private func onPut(_ sender: String, _ put: RelayV2.StatePut) {
        var problem: String?
        if let current = chain {
            if let member = current.members[sender], member.keys.role == .player {
                if active != sender && pending?.owner != sender {
                    problem = "not_active"
                } else {
                    problem = headerProblem(sender, current, put.kind, put.expectVersion, put.doc)
                }
            } else {
                problem = "not_player"
            }
        } else {
            problem = "no_household"
        }
        if let problem {
            post(sender, .error(.init(code: problem, requestId: put.requestId)))
            return
        }
        let stored = put.kind == .cursor ? cursor : queue
        if (stored?.version ?? 0) != put.expectVersion {
            post(sender, .stateConflict(.init(requestId: put.requestId, kind: put.kind, latest: stateOf(put.kind))))
            return
        }
        let doc = StoredDoc(version: put.expectVersion + 1, doc: put.doc, receivedAtServerMs: nowMs())
        if put.kind == .cursor { cursor = doc } else { queue = doc }
        var activeChanged: RelayV2.ActiveChanged?
        if put.kind == .cursor, let waiting = pending, waiting.owner == sender {
            active = sender
            pending = nil
            activeChanged = .init(activeDeviceId: sender, claimId: waiting.claimId, claimOrder: claimOrder)
        }
        post(sender, .stateOk(.init(requestId: put.requestId, kind: put.kind, version: doc.version)))
        broadcast(.state(.init(stored: stateOf(put.kind)!)), except: sender)
        if let activeChanged { broadcast(.activeChanged(activeChanged)) }
    }

    private func onClaim(_ sender: String, _ claimId: String, _ yieldDoc: SealedDoc, transferBasedOn: Int64?) {
        var problem: String?
        if let current = chain {
            if let member = current.members[sender], member.keys.role == .player {
                if yieldDoc.header.writerId != sender || yieldDoc.header.kind != .command || yieldDoc.header.householdId != current.householdId
                    || claimId.count < 16 || claimId.count > 64
                {
                    problem = "bad_command"
                } else if transferBasedOn != nil && cursor == nil {
                    problem = "no_state"
                }
            } else {
                problem = "not_player"
            }
        } else {
            problem = "no_household"
        }
        if let problem {
            post(sender, .error(.init(code: problem, requestId: claimId)))
            return
        }
        if let based = transferBasedOn, cursor!.version != based {
            post(sender, .stateConflict(.init(requestId: claimId, kind: .cursor, latest: stateOf(.cursor))))
            return
        }
        let dropped = pending?.claimId
        claimOrder += 1
        setClaimOrder(sender, claimOrder)
        let changed: RelayV2.ActiveChanged
        if transferBasedOn == nil {
            pending = nil
            active = sender
            changed = .init(activeDeviceId: sender, claimId: claimId, claimOrder: claimOrder)
        } else {
            pending = Pending(claimId: claimId, owner: sender, previousActive: active, untilMs: nowMs() + FakeRelay.pendingMs)
            changed = .init(activeDeviceId: active, claimId: claimId, claimOrder: claimOrder, pendingOwner: sender)
        }
        if let dropped { broadcast(.pendingExpired(.init(claimId: dropped))) }
        broadcast(.activeChanged(changed))
        // yield は家の全端末に配る（各端末は宛先で判断する）
        broadcast(.yieldDelivery(.init(claimId: claimId, yieldDoc: yieldDoc)), except: sender)
    }

    private func onYielded(_ sender: String, _ message: RelayV2.Yielded) {
        var problem: String?
        if let current = chain {
            if let waiting = pending {
                if waiting.claimId != message.claimId || waiting.previousActive != sender {
                    problem = "not_pending_previous"
                } else {
                    problem = headerProblem(sender, current, .cursor, message.expectVersion, message.doc)
                }
            } else {
                problem = "no_pending"
            }
        } else {
            problem = "no_household"
        }
        if let problem {
            post(sender, .error(.init(code: problem, requestId: message.claimId)))
            return
        }
        if (cursor?.version ?? 0) != message.expectVersion {
            post(sender, .stateConflict(.init(requestId: message.claimId, kind: .cursor, latest: stateOf(.cursor))))
            return
        }
        let stored = StoredDoc(version: message.expectVersion + 1, doc: message.doc, receivedAtServerMs: nowMs())
        cursor = stored
        post(sender, .stateOk(.init(requestId: message.claimId, kind: .cursor, version: stored.version)))
        broadcast(.state(.init(stored: stateOf(.cursor)!)), except: sender)
    }

    private func expirePending(_ disconnected: String?) -> (claimId: String, active: RelayV2.ActiveChanged)? {
        guard let waiting = pending else { return nil }
        if waiting.untilMs >= nowMs() && waiting.owner != disconnected { return nil }
        return (waiting.claimId, dropPending())
    }

    /// 新しい端末が引き取れなかった: 前の再生中の端末に戻す。
    private func dropPending() -> RelayV2.ActiveChanged {
        let previous = pending?.previousActive
        pending = nil
        active = previous
        return .init(activeDeviceId: previous, claimOrder: claimOrder)
    }

    private func broadcastExpired(_ expired: (claimId: String, active: RelayV2.ActiveChanged)?) {
        guard let expired else { return }
        broadcast(.pendingExpired(.init(claimId: expired.claimId)))
        broadcast(.activeChanged(expired.active))
    }
}

/// シミュレーションの端末: 鍵・ConnectClient・偽の再生。
final class SimDevice: ConnectClientEffects {
    private let relay: () -> FakeRelay
    private let clock: () -> Int64
    let keys = SoftwareDevice.generate()
    private var playingValue = false
    private var positionAnchor: Int64 = 0
    private var anchorAt: Int64 = 0

    let name: String
    let role: Role
    var deviceId: String { keys.deviceId }
    var memberKeys: MemberKeys { try! keys.memberKeys(role: role) }
    var tag: String? = "tag-account-1"
    var queue: String?
    var currentItemId = "item-0"
    var events: [ClientEvent] = []
    private(set) var persisted = ClientPersisted()
    private(set) var client: ConnectClient!

    init(_ name: String, relay: @escaping () -> FakeRelay, clock: @escaping () -> Int64, role: Role = .player) {
        self.name = name
        self.relay = relay
        self.clock = clock
        self.role = role
        anchorAt = clock()
        client = newClient(ClientPersisted())
    }

    var playing: Bool {
        get { playingValue }
        set {
            let current = positionMs
            playingValue = newValue
            positionAnchor = current
            anchorAt = clock()
        }
    }

    var positionMs: Int64 {
        get { playingValue ? positionAnchor + (clock() - anchorAt) : positionAnchor }
        set {
            positionAnchor = newValue
            anchorAt = clock()
        }
    }

    /// プロセスが落ちて起き直した（保存から読み直す。JSON を通す）。
    func restart() { client = newClient(ConnectJSON.decode(ClientPersisted.self, from: ConnectJSON.encode(persisted))!) }

    private func newClient(_ initial: ClientPersisted) -> ConnectClient {
        let id = keys.deviceId
        return ConnectClient(
            signer: keys, opener: keys, role: role, send: { [relay] message in relay().fromClient(id, message) }, effects: self, nowMs: clock,
            epochMs: clock, newId: { UUID().uuidString.lowercased() }, initial: initial)
    }

    func connect() { relay().connect(deviceId) { [unowned self] in try self.client.onMessage($0) } }

    func disconnect() {
        relay().disconnect(deviceId)
        client.onDisconnected()
    }

    func startPlaying(_ snapshot: String, item: String = "item-0", position: Int64 = 0) throws {
        queue = snapshot
        currentItemId = item
        positionMs = position
        playing = true
        try client.setLocalQueue(snapshot)
        try pushCursor()
        try client.userStartedPlayback()
    }

    func pushCursor() throws { try client.setLocalCursor(localCursor()) }

    func localCursor() -> ConnectClient.LocalCursor {
        ConnectClient.LocalCursor(
            currentItemId: currentItemId, positionMs: positionMs, playing: playing, repeat: .off, listenedMs: 0, historySent: false, capturedAtMs: clock())
    }

    func stoppedBy(_ other: SimDevice) -> Bool { events.contains(.stopped(by: other.deviceId)) }

    // MARK: Effects

    func pause() -> ConnectClient.LocalCursor? {
        playing = false
        return localCursor()
    }

    func adopt(snapshot: String, cursor: CursorBody, positionMs: Int64) -> Bool {
        queue = snapshot
        currentItemId = cursor.currentItemId
        self.positionMs = positionMs
        playing = true
        return true
    }

    func event(_ clientEvent: ClientEvent) { events.append(clientEvent) }

    func persist(_ state: ClientPersisted) { persisted = state }

    /// ほかの端末と合わせて届いたライブラリ（ADR-0013）。
    var library: LibraryBody?

    func libraryChanged(_ body: LibraryBody) { library = body }

    func accountTag() -> String? { tag }

    /// 従った遠隔操作。
    var controls: [(action: ControlAction, positionMs: Int64?)] = []
    /// 従ったキューへの追加（項目の ID・次に再生か・どの曲の後ろか）。
    var queueAdds: [(ids: [String], next: Bool, after: String?)] = []

    func queueAdd(_ items: [HandoffItem], next: Bool, afterItemId: String?) -> Bool {
        queueAdds.append((items.map(\.itemId), next, afterItemId))
        return true
    }

    func control(_ action: ControlAction, positionMs: Int64?) -> Bool {
        controls.append((action, positionMs))
        switch action {
        case .play: playing = true
        case .pause: playing = false
        case .seek: self.positionMs = positionMs ?? 0
        default: break
        }
        return true
    }
}
