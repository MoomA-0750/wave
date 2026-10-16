#if os(macOS)
    import AppKit
#else
    import UIKit
#endif
import Foundation
import Observation
import WaveCore

/// 端末の連携（Connect v2）の Mac 側の調停役（Windows の ConnectService・Android の ConnectV2Manager と同じ役割）。
/// ConnectClient を1本の作業の列（ConnectWorker）に閉じ込め、中継 v2・再生サービス・画面をつなぐ。再生サービスの操作はメインのスレッドで行う。
/// 画面に出す値と、再生サービスからの知らせ・加入の手順はメインのスレッド（このクラス）で扱う。
/// ログは種類と件数だけ（端末 ID・本文・アカウントの識別子は出さない）。
@MainActor @Observable
final class ConnectService {
    nonisolated static let remotePlaying = "remote_playing"
    nonisolated static let relayUnreachable = "relay_unreachable"
    nonisolated static let notMemberPlay = "not_member_play"
    nonisolated static let accountUnverifiedPlay = "account_unverified_play"
    nonisolated static let householdCreateUnsent = "household_create_unsent"

    private static let cursorDebounceMs = 1_000
    private static let queueDebounceMs = 1_000

    /// キューの doc の handoffId（Android の V2_SNAPSHOT_ID と同じ。中身の同じキューは同じ文字列になる）。
    nonisolated static let snapshotId = "00000000-0000-4000-8000-000000000000"

    @ObservationIgnored let relay: RelayConnection?

    /// 中継の場所が設定されていて、端末の鍵が読めた。
    var configured: Bool { relay != nil }

    /// 端末の鍵か保存が読めない（作り直すまで使わない）。
    private(set) var unreadable = false

    // 画面に出す値（作業の列で作り、メインのスレッドで置き換える）
    private(set) var info = ConnectInfo()
    private(set) var members: [MemberView] = []
    private(set) var remote: RemoteView?
    private(set) var pairingState = PairingView.idle

    /// 知らせ（メインのスレッドで呼ぶ。Windows の Notice。画面の下の知らせに流す）。
    @ObservationIgnored var notice: [(String) -> Void] = []

    /// 画面が前面にあるか（メインのスレッドで呼ぶ）。裏でメディアキーを押したときに、ほかの端末の再生を黙って引き取らない（D-9）。
    /// 既定は、wave が前にあり、窓が最小化されていないか。
    @ObservationIgnored var isForeground: @MainActor () -> Bool = {
        #if os(macOS)
            NSApp.isActive && NSApp.windows.contains { $0.isVisible && !$0.isMiniaturized && $0.canBecomeMain }
        #else
            UIApplication.shared.applicationState == .active
        #endif
    }

    @ObservationIgnored private let player: PlayerService
    @ObservationIgnored private var worker: ConnectWorker?
    @ObservationIgnored private var lastSignificant: SignificantKey?
    @ObservationIgnored private var cursorTimer: Debouncer?
    @ObservationIgnored private var queueTimer: Debouncer?

    // 加入
    @ObservationIgnored private var pairingCancel: CancelSource?
    @ObservationIgnored private var codes: AsyncChannel<String>?
    @ObservationIgnored private var pendingOnB: Pairing.Established?

    init(player: PlayerService, relayUrl: String?, relayHost: String?) {
        self.player = player
        guard let relayUrl, let relayHost, !relayUrl.trimmingCharacters(in: .whitespaces).isEmpty, !relayHost.trimmingCharacters(in: .whitespaces).isEmpty else {
            relay = nil
            return
        }
        let (state, device) = DeviceKeys.loadOrCreate()
        AppPaths.log("connect-v2 keys state=\(state.rawValue)")
        if state == .created {
            // 新しい鍵（端末 ID が変わる）: その端末 ID で入っていた家の記録は使えないので消す（加入し直す）
            ConnectKeychain.delete(ConnectKeychain.stateAccount)
        }
        guard let device else {
            relay = nil
            unreadable = true
            return
        }
        let worker = ConnectWorker(player: player, keys: device, relayUrl: relayUrl, relayHost: relayHost, tube: AppServices.client, music: AppServices.music)
        relay = worker.relay
        self.worker = worker
        worker.owner = self
        notice.append { AppServices.toast.show($0) }
        worker.post { worker.load() }
        player.queueChanged.append { [weak self] in self?.onQueueChanged() }
        player.cursorChanged.append { [weak self] significant in self?.onCursorChanged(significant) }
        player.userStartedPlayback.append { [weak self] newSelection in self?.onUserStartedPlayback(newSelection) }
        player.userPaused.append { worker.playIntent.update { $0 += 1 } }
        player.playOnRemote = { [weak self] target in
            guard let self, worker.loaded.current, worker.relay.status == .connected else { return false }
            // メインのスレッドで手元のキュー（音を出さずに用意したもの）を読んでから渡す
            let snapshot = self.snapshot()
            var cursor = self.player.currentCursor().map(worker.toLocal)
            cursor?.playing = false
            guard let snapshot, let cursor else { return false }
            worker.post {
                guard let engine = worker.client else { return }
                try engine.setLocalQueue(snapshot)
                try engine.setLocalCursor(cursor)
                let sent = try engine.playOn(target)
                AppPaths.log("connect-v2 play on remote sent=\(sent)")
            }
            return true
        }
        player.sendRemoteControl = { action, positionMs in
            guard worker.loaded.current, worker.relay.status == .connected else { return false }
            worker.post {
                let sent = try worker.client?.sendControl(action, positionMs: positionMs)
                AppPaths.log("connect-v2 control action=\(action.rawValue) sent=\(sent.map { String($0) } ?? "")")
            }
            return true
        }
        player.sendRemoteVolume = { percent in
            guard worker.loaded.current, worker.relay.status == .connected else { return false }
            worker.post {
                let sent = try worker.client?.sendVolume(percent)
                AppPaths.log("connect-v2 volume percent=\(percent) sent=\(sent.map { String($0) } ?? "")")
            }
            return true
        }
        player.sendRemoteQueueAdd = { songs, next in
            guard worker.loaded.current, worker.relay.status == .connected else { return false }
            let items = songs.map { ConnectQueue.itemOf($0, QueueItemMeta(itemId: UUID().uuidString.lowercased(), origin: .userAdded)) }
            worker.post {
                let sent = try worker.client?.sendQueueAdd(items, next: next)
                AppPaths.log("connect-v2 queue add items=\(items.count) next=\(next) sent=\(sent.map { String($0) } ?? "")")
            }
            return true
        }
        // 接続に関係なく回す（transfer の期限・アカウントタグの計算）
        worker.startTicking()
        // Mac は画面がある間ずっと中継につなぐ（家に入っていなければ、中継は認証で断る）
        worker.relay.acquire()
    }

    // MARK: 作業の列からの知らせ

    fileprivate func apply(_ next: ConnectInfo, _ nextMembers: [MemberView], _ nextRemote: RemoteView?, _ mirror: RemoteSnapshot?) {
        // 同じ値を置き直すと画面が毎秒描き直されるので、変わったときだけ置く
        if info != next { info = next }
        if unreadable != next.unreadable { unreadable = next.unreadable }
        if members != nextMembers { members = nextMembers }
        if remote != nextRemote { remote = nextRemote }
        var shown = mirror
        shown?.capturedAtMs = player.clockMs
        player.showRemote(shown)
    }

    fileprivate func setPairing(_ view: PairingView) {
        pairingState = view
    }

    fileprivate func notify(_ text: String) {
        notice.forEach { $0(text) }
    }

    // MARK: 再生サービスからの知らせ（メインのスレッド）

    private func onQueueChanged() {
        // 書き手でなければ書き出さない（再生を始めたときに書き出す）。続けて変わるときは1秒に間引く
        guard let worker, worker.writer.current else { return }
        if queueTimer == nil {
            queueTimer = Debouncer(ms: ConnectService.queueDebounceMs) { [weak self] in
                guard let self, let text = self.snapshot() else { return }
                worker.post { try worker.client?.setLocalQueue(text) }
            }
        }
        queueTimer?.start()
    }

    private func onCursorChanged(_ significant: Bool) {
        // ほかの端末を映している間は、手元の再生の変化ではない
        if player.isRemote { return }
        if cursorTimer == nil { cursorTimer = Debouncer(ms: ConnectService.cursorDebounceMs) { [weak self] in self?.pushCursor() } }
        cursorTimer?.stop()
        let key = player.currentCursor().map { SignificantKey(itemId: $0.currentItemId, playing: $0.playing, repeat: ConnectQueue.repeatOf($0.repeatMode)) }
        if significant && key != lastSignificant {
            lastSignificant = key
            pushCursor()
        } else {
            cursorTimer?.start()
        }
    }

    private func pushCursor() {
        // ほかの端末を映している間は手元の再生ではない（間引きのタイマーが映し始めた後に鳴っても、手元の再生を終わらせない）
        guard let worker, !player.isRemote else { return }
        let cursor = player.currentCursor().map(worker.toLocal)
        worker.post {
            guard let engine = worker.client else { return }
            if let cursor { try engine.setLocalCursor(cursor) } else { try engine.endLocal(finalTag: worker.accountTag()) }
            AppPaths.log("connect-v2 cursor playing=\(cursor.map { String($0.playing) } ?? "") writer=\(engine.isWriter)")
        }
    }

    private func onUserStartedPlayback(_ newSelection: Bool) {
        guard let worker else { return }
        let intent = worker.playIntent.update { value -> Int64 in
            value += 1
            return value
        }
        let foreground = isForeground()
        let hasLocalQueue = !player.queue.isEmpty
        worker.post { try worker.userStartedPlayback(newSelection: newSelection, intent: intent, foreground: foreground, hasLocalQueue: hasLocalQueue) }
    }

    // MARK: キューの書き出し

    /// 今のキューの doc の文字列（メインのスレッドで呼ぶ）。中身が同じなら同じ文字列にするため、位置・履歴・時刻は入れない。
    fileprivate func snapshot() -> String? {
        guard AppServices.client.isLoggedIn, let queue = player.exportQueue() else { return nil }
        guard queue.items.contains(where: { $0.meta.itemId == queue.currentItemId }) else { return nil }
        let items = queue.items.filter { ConnectQueue.validVideoId($0.song.videoId) && !$0.song.title.isEmpty }
        guard let index = items.firstIndex(where: { $0.meta.itemId == queue.currentItemId }) else { return nil }
        let (window, windowIndex, truncated) = HandoffCodec.window(items, currentIndex: index)
        let source: HandoffSource?
        if let seed = queue.radioSeed {
            source = .radio(seedVideoId: seed)
        } else if let id = queue.sourceId, ConnectQueue.matches(id, min: 2, max: 128) {
            source = .playlist(playlistId: id)
        } else {
            source = nil
        }
        let snapshot = HandoffSnapshot(
            schemaVersion: HandoffCodec.schemaVersion, handoffId: ConnectService.snapshotId, items: window.map { ConnectQueue.itemOf($0.song, $0.meta) },
            currentItemId: window[windowIndex].meta.itemId, positionMs: 0, playing: false, repeat: .off, source: source,
            continuation: truncated ? nil : queue.continuation, radioPlaylistId: queue.radioPlaylistId,
            // プレイリスト・アルバムは、最後まで行ったら受け取った端末が最後の曲のラジオへ切り替えるので尽きていない（Android と同じ）
            exhausted: QueueRadio.exhausted(radioSeed: queue.radioSeed, playlistSource: source != nil, last: queue.items.last?.song, truncated: truncated),
            listenedMs: 0, historySent: false, capturedAtEpochMs: 0)
        guard case .ok = HandoffCodec.validate(snapshot, expectedHandoffId: ConnectService.snapshotId) else { return nil }
        return HandoffCodec.encode(snapshot)
    }

    // MARK: 画面からの操作

    /// デバイスの一覧で、ほかの端末を選んだ（その端末に再生を移す）。
    func playOnDevice(_ deviceId: String) {
        guard let worker else { return }
        worker.post {
            let requested = try worker.client?.requestRemoteTransfer(deviceId)
            AppPaths.log("connect-v2 remote transfer requested=\(requested.map { String($0) } ?? "")")
        }
    }

    /// 手元のライブラリが変わった（RSS の登録・聴いたところ、ADR-0013）。中継のものと合わせ、違えば書く。
    func publishLibrary(_ body: LibraryBody) {
        guard let worker else { return }
        worker.post { try worker.publishLibrary(body) }
    }

    /// 「この Mac で再生」。
    func transfer() {
        guard let worker else { return }
        worker.post {
            let requested = try worker.client?.requestTransfer()
            AppPaths.log("connect-v2 transfer requested=\(requested.map { String($0) } ?? "")")
        }
    }

    func acceptStoreReset() {
        guard let worker else { return }
        worker.post { try worker.client?.acceptStoreReset() }
    }

    /// 起動時の設定の鍵の端末で、家を作る（家が無いときだけ中継が受ける）。
    func createHousehold() {
        guard let worker else { return }
        worker.post { try worker.createHousehold() }
    }

    /// 家から外された端末で、本人が押したら、この端末の家の記録を消す。
    func forgetHousehold() {
        guard let worker else { return }
        worker.post { worker.forgetHousehold() }
    }

    /// 家から他の端末を外す。@return 外したか
    func removeMember(_ deviceId: String) async -> Bool {
        guard let worker, deviceId != worker.keys.deviceId else { return false }
        let record = try? await worker.onEngine { () -> ChainRecord? in
            guard let chain = worker.client?.verifiedChain(), chain.members[deviceId] != nil else { return nil }
            return try MemberChain.remove(signer: worker.keys, chain: chain, deviceId: deviceId)
        }
        guard let record else { return false }
        let removed = await worker.appendRecord(record) { members in members.allSatisfy { $0.deviceId != deviceId } }
        AppPaths.log("connect-v2 remove member ok=\(removed)")
        return removed
    }

    // MARK: 加入（A: 家の端末が QR を出す、B: 加わる端末が文字列を貼る）

    func cancelPairing() {
        pairingCancel?.cancel()
        pairingCancel = nil
        codes = nil
        if let worker, pendingOnB != nil || worker.joinWaiter.current != nil { worker.post { worker.client?.cancelJoin() } }
        pendingOnB = nil
        setPairing(.idle)
    }

    func enterCode(_ code: String) { codes?.tryWrite(code) }

    /// A: 家の端末として、加わる端末に QR（と文字列）を出す。
    func startOffer() {
        guard let worker else { return }
        cancelPairing()
        let cancel = CancelSource()
        pairingCancel = cancel
        let entered = AsyncChannel<String>()
        codes = entered
        runPairing(cancel) { try await self.runOffer(worker, entered, cancel) }
    }

    /// B: 家の端末が出した文字列（wave-pair:…）で加わる。
    func accept(_ offerText: String) {
        guard let worker else { return }
        cancelPairing()
        let cancel = CancelSource()
        pairingCancel = cancel
        let text = offerText.trimmingCharacters(in: .whitespacesAndNewlines)
        runPairing(cancel) { try await self.runAccept(worker, text, cancel) }
    }

    /// B: 相手に6桁を入力してもらった後の「続ける」。
    func confirmOnB() {
        guard let worker, let established = pendingOnB else { return }
        guard let ext = established.peerExtension else {
            setPairing(.failed(L.t("相手のアプリが古く、加われません", "The other device’s app is too old")))
            return
        }
        pendingOnB = nil
        pairingCancel?.cancel()
        let cancel = CancelSource()
        pairingCancel = cancel
        runPairing(cancel) { try await self.runJoinOnB(worker, established, ext, cancel) }
    }

    private func runPairing(_ cancel: CancelSource, _ block: @escaping @MainActor () async throws -> Void) {
        Task { @MainActor in
            do {
                try await block()
            } catch where cancel.isCancelled {
            } catch {
                AppPaths.log("pairing-v2 error type=\(String(describing: type(of: error)))")
                self.fail(L.t("連携できませんでした", "Couldn’t connect the device"))
            }
        }
    }

    private func fail(_ message: String) {
        codes = nil
        pendingOnB = nil
        setPairing(.failed(message))
        AppPaths.log("pairing-v2 failed")
    }

    private func runOffer(_ worker: ConnectWorker, _ entered: AsyncChannel<String>, _ cancel: CancelSource) async throws {
        setPairing(.working(L.t("中継につないでいます…", "Connecting to the relay…")))
        let relay = worker.relay!
        relay.acquire()
        defer { relay.release() }
        var tries = 0
        while tries < 150 && (relay.status == .connecting || relay.status == .disconnected) {
            try await connectDelay(100, cancel)
            tries += 1
        }
        if relay.status != .connected {
            fail(L.t("中継につながりませんでした（Tailscale につながっているか確かめてください）", "Couldn’t reach the relay (check that Tailscale is connected)"))
            return
        }
        let joinInfo = try await worker.onEngine { worker.client?.currentJoinInfo().map { ConnectJSON.encode($0) } }
        guard let joinInfo else {
            fail(L.t("この Mac はまだ連携していません", "This Mac isn’t set up yet"))
            return
        }
        let verified = try await worker.music.verifiedAccount()
        try cancel.throwIfCancelled()
        guard let account = verified else {
            fail(L.t("アカウントを確かめられませんでした", "Couldn’t verify your account"))
            return
        }
        let responder = Pairing.Responder(identity: worker.keys, deviceName: ConnectWorker.deviceName, accountId: account.id, nowMs: { worker.clockMs },
                                          confirmExtension: joinInfo)
        let tokenHash = B64.encode(responder.tokenHash)
        while worker.pairHellos.tryRead() != nil {}
        if !worker.send(.pairingOpen(.init(tokenHash: tokenHash))) {
            fail(L.t("中継に送れませんでした", "Couldn’t send to the relay"))
            return
        }
        setPairing(.showingOffer(Pairing.encodeOffer(responder.offer)))
        let lifetime = cancel.linked()
        lifetime.cancelAfter(ms: Pairing.lifetimeMs)
        var hello: RelayV2.PairHello?
        do {
            // 中継が切れたら待たない（Android と同じ）
            let observer = worker.addStatusObserver { status in
                if status != .connected { lifetime.cancel() }
            }
            defer { worker.removeStatusObserver(observer) }
            while hello == nil {
                let next = try await worker.pairHellos.read(lifetime)
                if next.tokenHash == tokenHash { hello = next }
            }
        } catch is CancellationError where !cancel.isCancelled {
            fail(relay.status != .connected
                ? L.t("中継との接続が切れました", "Lost the connection to the relay")
                : L.t("時間内に読み取られませんでした", "The code wasn’t used in time"))
            return
        }
        guard let hello, let decoded = Pairing.decodeHello(hello.hello) else {
            fail(L.t("相手から届いた内容を読めませんでした", "Couldn’t read the reply from the other device"))
            return
        }
        let confirm = try responder.onHello(decoded)
        guard case .success(let confirmValue) = confirm else {
            fail(confirm.failure == .accountMismatch
                ? L.t("相手は別のアカウントでログインしています", "The other device is logged in to a different account")
                : L.t("相手を確かめられませんでした", "Couldn’t verify the other device"))
            return
        }
        if !worker.send(.pairConfirm(.init(tokenHash: tokenHash, confirm: Pairing.toJSON(confirmValue)))) {
            fail(L.t("中継に送れませんでした", "Couldn’t send to the relay"))
            return
        }
        setPairing(.enterCode(nil))
        do {
            while true {
                let code = try await entered.read(lifetime)
                let result = responder.onCodeEntered(code)
                guard case .success(let established) = result else {
                    if result.failure == .wrongCode {
                        setPairing(.enterCode(L.t("数字が違います", "That code doesn’t match")))
                        continue
                    }
                    fail(L.t("連携できませんでした", "Couldn’t connect the device"))
                    return
                }
                if worker.tube.generation != account.generation {
                    fail(L.t("途中でログインが変わりました", "Your login changed during setup"))
                    return
                }
                setPairing(.working(L.t("端末を加えています…", "Adding the device…")))
                var added = false
                if let memberKeys = established.peerExtension { added = try await addMember(worker, memberKeys, established.peerPublicKey) }
                if !added {
                    fail(L.t("端末を加えられませんでした", "Couldn’t add the device"))
                    return
                }
                worker.rememberLabel(WaveCrypto.deviceId(established.peerPublicKey), established.peerDeviceName)
                AppPaths.log("pairing-v2 established role=A")
                setPairing(.done(established.peerDeviceName))
                return
            }
        } catch is CancellationError where !cancel.isCancelled {
            fail(L.t("時間切れになりました。もう一度やり直してください", "Timed out. Please try again"))
        }
    }

    /// A: 6桁の照合の後、B を家に加える。
    private func addMember(_ worker: ConnectWorker, _ memberKeysJson: String, _ peerPublicKey: Data) async throws -> Bool {
        let prepared = try await worker.onEngine { () -> (record: ChainRecord?, memberId: String)? in
            guard let engine = worker.client, let chain = engine.verifiedChain() else { return nil }
            guard let member = ConnectJSON.decode(MemberKeys.self, from: memberKeysJson), member.role == .player else { return nil }
            // 6桁で確かめた相手の身元鍵と同じであること
            guard let identity = B64.decode(member.identityPub), identity == peerPublicKey else { return nil }
            // 保存を作り直した端末が同じ鍵で加わり直す: 既にいるので追記しない
            if let existing = chain.members[member.deviceId], existing.keys.kxPub == member.kxPub { return (nil, member.deviceId) }
            let record = try MemberChain.add(signer: worker.keys, chain: chain, target: member)
            // 積む前に、鎖として通ることを確かめる（相手の kx の署名が壊れていると、家の全端末がその鎖を拒んで止まる）
            guard let records = engine.verifiedRecords(), case .ok = MemberChain.verify(records + [record]) else { return nil }
            return (record, member.deviceId)
        }
        guard let added = prepared else { return false }
        guard let record = added.record else { return true }
        return await worker.appendRecord(record) { members in members.contains { $0.deviceId == added.memberId } }
    }

    private func runAccept(_ worker: ConnectWorker, _ offerText: String, _ cancel: CancelSource) async throws {
        guard let offer = Pairing.decodeOffer(offerText) else {
            fail(L.t("wave の連携の文字列ではありません", "That isn’t a wave pairing code"))
            return
        }
        let verified = try await worker.music.verifiedAccount()
        try cancel.throwIfCancelled()
        guard let account = verified else {
            fail(L.t("アカウントを確かめられませんでした（ログインしてください）", "Couldn’t verify your account (log in first)"))
            return
        }
        setPairing(.working(L.t("相手に送っています…", "Sending to the other device…")))
        let initiator = try Pairing.Initiator(offer: offer, identity: worker.keys, deviceName: ConnectWorker.deviceName, accountId: account.id,
                                              helloExtension: ConnectJSON.encode(try worker.keys.memberKeys(role: .player)))
        let hello = try initiator.hello()
        let reply = await worker.relay.pair(RelayV2.encode(.pairHelloIn(.init(token: offer.token, hello: Pairing.toJSON(hello)))))
        try cancel.throwIfCancelled()
        guard let reply, case .pairConfirmed(let confirmed)? = RelayV2.decode(reply) else {
            fail(reply == nil
                ? L.t("中継につながりませんでした（Tailscale につながっているか確かめてください）", "Couldn’t reach the relay (check that Tailscale is connected)")
                : L.t("相手が待っていません。相手でもう一度「端末を追加」を押してください", "The other device isn’t waiting. Tap “Add device” on it again"))
            return
        }
        guard let confirm = Pairing.decodeConfirm(confirmed.confirm) else {
            fail(L.t("相手から届いた内容を読めませんでした", "Couldn’t read the reply from the other device"))
            return
        }
        let result = initiator.onConfirm(confirm)
        guard case .success(let value) = result else {
            fail(result.failure == .accountMismatch
                ? L.t("相手は別のアカウントでログインしています", "The other device is logged in to a different account")
                : L.t("相手を確かめられませんでした", "Couldn’t verify the other device"))
            return
        }
        if worker.tube.generation != account.generation {
            fail(L.t("途中でログインが変わりました", "Your login changed during setup"))
            return
        }
        pendingOnB = value.pair
        setPairing(.showCode(value.code, peer: value.pair.peerDeviceName))
    }

    private func runJoinOnB(_ worker: ConnectWorker, _ established: Pairing.Established, _ ext: String, _ cancel: CancelSource) async throws {
        guard let info = ConnectJSON.decode(ConnectClient.JoinInfo.self, from: ext) else {
            fail(L.t("相手から届いた内容を読めませんでした", "Couldn’t read the reply from the other device"))
            return
        }
        let inviter = WaveCrypto.deviceId(established.peerPublicKey)
        let started = try await worker.onEngine { () -> Bool in
            guard let engine = worker.client, engine.householdId == nil else { return false }
            engine.expectJoin(info, inviterDeviceId: inviter)
            return true
        }
        if !started {
            fail(L.t("この Mac は今は加われません", "This Mac can’t join right now"))
            return
        }
        setPairing(.working(L.t("相手が加えるのを待っています…", "Waiting for the other device to add this Mac…")))
        let waiter = Promise<ClientEvent>()
        worker.joinWaiter.set(waiter)
        var accepted = false
        defer {
            worker.joinWaiter.set(nil)
            if !accepted { worker.post { worker.client?.cancelJoin() } }
        }
        let relay = worker.relay!
        let startedAt = worker.clockMs
        var outcome: ClientEvent?
        for wait: Int64 in [2_000, 5_000, 10_000, 13_000] {
            if try await worker.onEngine({ worker.client?.householdId != nil }) {
                outcome = .joined
                break
            }
            // 家に入れてもらったら、つなぎ直して鎖を取る（認証は中継の鎖の今の端末で行う）
            relay.reconnect()
            let finished = await waiter.wait(timeoutMs: wait, cancel: cancel)
            try cancel.throwIfCancelled()
            if let finished {
                outcome = finished
                break
            }
            if worker.clockMs - startedAt > 30_000 { break }
        }
        accepted = outcome == .joined
        if accepted {
            worker.rememberLabel(inviter, established.peerDeviceName)
            AppPaths.log("pairing-v2 established role=B")
            setPairing(.done(established.peerDeviceName))
        } else {
            fail(outcome == nil ? L.t("加われませんでした（時間切れ）", "Couldn’t join (timed out)") : L.t("端末の一覧を確かめられませんでした", "Couldn’t verify the device list"))
        }
    }
}

/// 手元の再生のうち、変わったらすぐ書くもの（曲・再生中か・リピート）。
private struct SignificantKey: Equatable {
    var itemId: String
    var playing: Bool
    var `repeat`: HandoffRepeat
}

// MARK: - 作業の列の側

/// ConnectClient・中継・保存を扱う側（Windows の ConnectService の作業スレッド `connect-v2` で動く部分）。
/// ConnectClient にはこの列からだけ触る。再生サービスにはメインのスレッドで触る（onUi。メインのスレッドはこの列を待たない）。
final class ConnectWorker: ConnectClientEffects, @unchecked Sendable {
    private static let tickMs: UInt64 = 1_000
    private static let settleWaitMs: Int64 = 10_000
    private static let maxLabelChars = 64

    /// 合わせるとき、手元の位置がこれ以上ずれていればシークする。
    private static let positionToleranceMs: Int64 = 3_000

    private let queue = DispatchQueue(label: "connect-v2")
    private let clockStart = DispatchTime.now().uptimeNanoseconds
    private let labelsUrl = AppPaths.file("connect-v2-labels.json")

    /// 家の端末の種類（表示の絵だけ）。相手は中継につながったときにしか知らせないので、名前と同じくファイルに覚える。
    private let kindsUrl = AppPaths.file("connect-v2-kinds.json")

    weak var owner: ConnectService?
    private let player: PlayerService
    let keys: SoftwareDevice
    let tube: InnerTubeClient
    let music: MusicRepository
    private(set) var relay: RelayConnection!

    /// 作業の列でだけ読み書きする。
    fileprivate var client: ConnectClient?

    /// 手元のライブラリの最新（client を作る前に届いた分も、作ったら渡す。作業の列でだけ読み書きする）。
    private var library: LibraryBody?

    /// client を作った（メインのスレッドから見る）。
    let loaded = Locked(false)

    /// この端末が今の書き手（メインのスレッドから見る）。
    let writer = Locked(false)

    let playIntent = Locked<Int64>(0)
    let pairHellos = AsyncChannel<RelayV2.PairHello>()
    let chainReply = Locked<Promise<RelayV2>?>(nil)
    let joinWaiter = Locked<Promise<ClientEvent>?>(nil)

    private var unreadable = false
    private var labels: [String: String] = [:]
    private var kinds: [String: String] = [:]

    private struct TagState {
        var householdId: String
        var tag: String
        var generation: Int64
    }

    private var tagState: TagState?
    private let tagComputing = Locked(false)
    private var seenGeneration: Int64?
    private var waitCancel: CancelSource?

    /// ほかの端末に「ここで再生」を頼まれた時刻（止められた知らせを抑えるのに使う）。
    private let remoteTransferRequestedAt = Locked<Int64>(.min)

    private let statusObservers = Locked<[UUID: (RelayStatus) -> Void]>([:])

    init(player: PlayerService, keys: SoftwareDevice, relayUrl: String, relayHost: String, tube: InnerTubeClient, music: MusicRepository) {
        self.player = player
        self.keys = keys
        self.tube = tube
        self.music = music
        relay = RelayConnection(
            baseUrl: relayUrl, relayHost: relayHost, identityPub: keys.identityPub,
            sign: { [keys] label, message in try keys.sign(label: label, message: message) },
            deliver: { [unowned self] text in self.deliver(text) },
            onConnectionLost: { [unowned self] in self.post { self.client?.onDisconnected() } },
            log: { AppPaths.log($0) })
        relay.onStatusChanged = { [unowned self] status in
            self.post { self.refreshInfo() }
            self.statusObservers.current.values.forEach { $0(status) }
        }
    }

    var clockMs: Int64 { Int64((DispatchTime.now().uptimeNanoseconds - clockStart) / 1_000_000) }

    static var deviceName: String {
        #if os(macOS)
            let name = Host.current().localizedName ?? ""
            return name.isEmpty ? "Mac" : name
        #else
            // iOS 16 から、アプリは本体に付けた名前を読めない（読めても「iPhone」になる）
            return "iPhone"
        #endif
    }

    // MARK: 作業の列

    func post(_ action: @escaping () throws -> Void) {
        queue.async {
            do {
                try action()
            } catch {
                AppPaths.log("connect-v2 failed type=\(String(describing: type(of: error)))")
            }
        }
    }

    func onEngine<T>(_ body: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    continuation.resume(returning: try body())
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    /// メインのスレッドで走らせて待つ（作業の列からだけ呼ぶ。メインのスレッドはこの列を待たない）。
    private func onUi<T: Sendable>(_ body: @MainActor () -> T) -> T {
        DispatchQueue.main.sync { MainActor.assumeIsolated { body() } }
    }

    /// メインのスレッドへ送る（待たない）。
    private func toUi(_ body: @escaping @MainActor () -> Void) {
        DispatchQueue.main.async { MainActor.assumeIsolated { body() } }
    }

    func startTicking() {
        Task.detached { [self] in
            while true {
                post { [self] in
                    guard let engine = client else { return }
                    try checkLoginGeneration(engine)
                    try engine.tick()
                    refreshInfo()
                    if let household = engine.householdId { refreshAccountTagIfNeeded(household) }
                }
                try? await Task.sleep(nanoseconds: ConnectWorker.tickMs * 1_000_000)
            }
        }
    }

    func addStatusObserver(_ observer: @escaping (RelayStatus) -> Void) -> UUID {
        let id = UUID()
        statusObservers.update { $0[id] = observer }
        return id
    }

    func removeStatusObserver(_ id: UUID) {
        statusObservers.update { _ = $0.removeValue(forKey: id) }
    }

    private func deliver(_ text: String) {
        guard let message = RelayV2.decode(text) else { return }
        switch message {
        case .pairHello(let hello):
            pairHellos.tryWrite(hello)
            return
        // 鎖の追記への返事（state_put への error は requestId が付くので除く）
        case .chainOk, .chainConflict:
            chainReply.current?.trySet(message)
        case .error(let reply) where reply.requestId == nil:
            chainReply.current?.trySet(message)
        default:
            break
        }
        post { [self] in try client?.onMessage(message) }
    }

    @discardableResult
    func send(_ message: RelayV2) -> Bool { relay?.send(RelayV2.encode(message)) == true }

    // MARK: 保存

    func load() {
        loadLabels()
        let initial: ClientPersisted
        switch ConnectKeychain.read(ConnectKeychain.stateAccount) {
        case .missing:
            initial = ClientPersisted()
        case .found(let data):
            guard let decoded = ConnectJSON.decode(ClientPersisted.self, from: data) else {
                unreadable = true
                refreshInfo()
                return
            }
            initial = decoded
        case .failed:
            unreadable = true
            refreshInfo()
            return
        }
        client = ConnectClient(
            signer: keys, opener: keys, role: .player, send: { [unowned self] in self.send($0) }, effects: self, nowMs: { [unowned self] in self.clockMs },
            epochMs: { Int64(Date().timeIntervalSince1970 * 1000) }, newId: { UUID().uuidString.lowercased() }, initial: initial)
        if let library { try? client?.library.publish(library) }
        loaded.set(true)
        seenGeneration = tube.generation
        refreshInfo()
    }

    private func save(_ state: ClientPersisted) {
        if !ConnectKeychain.write(ConnectKeychain.stateAccount, ConnectJSON.encodeToUTF8(state)) { AppPaths.log("connect-v2 persist failed type=keychain") }
    }

    private func loadLabels() {
        labels = Self.readMap(labelsUrl)
        kinds = Self.readMap(kindsUrl)
    }

    private static func readMap(_ url: URL) -> [String: String] {
        guard let data = try? Data(contentsOf: url), let map = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
        return map
    }

    private static func writeMap(_ url: URL, _ map: [String: String]) {
        guard let data = try? JSONEncoder().encode(map) else { return }
        try? data.write(to: url, options: .atomic)
    }

    private func rememberKind(_ deviceId: String, _ kind: String) {
        kinds[deviceId] = kind
        Self.writeMap(kindsUrl, kinds)
    }

    func rememberLabel(_ deviceId: String, _ name: String) {
        post { [self] in
            labels[deviceId] = name.count > ConnectWorker.maxLabelChars ? String(name.prefix(ConnectWorker.maxLabelChars)) : name
            Self.writeMap(labelsUrl, labels)
            refreshInfo()
        }
    }

    private func labelOf(_ deviceId: String) -> String {
        if deviceId == keys.deviceId { return L.t("この Mac", "This Mac") }
        if let name = labels[deviceId] { return name }
        let short = String(deviceId.prefix(6))
        return L.t("端末 \(short)", "Device \(short)")
    }

    // MARK: 画面の値

    private var profileSentForMembers = -1
    private var profileSeenOnline: Set<String> = []
    private var lastRelayStatus = RelayStatus.disconnected

    /// 中継につながったとき・家の端末が増えたときに、この Mac の名前を家に知らせる（作業の列で）。
    private func maybeSendProfile(_ engine: ConnectClient?) {
        let status = relay?.status ?? .disconnected
        if status != lastRelayStatus {
            lastRelayStatus = status
            if status == .connected { profileSentForMembers = -1 }
        }
        guard let engine, engine.householdId != nil, status == .connected else { return }
        let count = engine.members.count
        // 知らせはその時つながっている端末にしか届かないので、ほかの端末が新しくつながってきたときにも知らせる
        let online = engine.online
        let newcomer = online.contains { $0 != keys.deviceId && !profileSeenOnline.contains($0) }
        profileSeenOnline = online
        if count == profileSentForMembers && !newcomer { return }
        do {
            if try engine.sendProfile(ConnectWorker.deviceName, kind: "pc") { profileSentForMembers = count }
        } catch {
            AppPaths.log("connect-v2 failed type=\(String(describing: type(of: error)))")
        }
    }

    func refreshInfo() {
        let engine = client
        maybeSendProfile(engine)
        writer.set(engine?.isWriter == true)
        let online = engine?.online ?? []
        let active = engine?.activeDeviceId
        let info = ConnectInfo(
            configured: true, loaded: engine != nil, unreadable: unreadable, inHousehold: engine?.householdId != nil, isWriter: engine?.isWriter == true,
            retreated: engine?.hasRetreated == true, storeResetPending: engine?.storeResetPending == true, relay: relay?.status ?? .disconnected)
        let members = (engine?.members ?? []).map { m in
            MemberView(deviceId: m.deviceId, label: labelOf(m.deviceId), isSelf: m.deviceId == keys.deviceId, isController: m.role == .controller,
                       online: online.contains(m.deviceId), active: m.deviceId == active, isPc: kinds[m.deviceId] == "pc")
        }
        var remote: RemoteView?
        if let engine, let latest = engine.latestCursorBody(), latest.writer != keys.deviceId {
            let tag = tagState?.tag
            var item: HandoffItem?
            if let queue = engine.latestQueueBody(), engine.latestQueueHash() == latest.cursor.queueRef,
               let snapshot = ConnectJSON.decode(HandoffSnapshot.self, from: queue.queue.snapshot) {
                item = snapshot.items.first { $0.itemId == latest.cursor.currentItemId }
            }
            remote = RemoteView(
                deviceId: latest.writer, deviceLabel: labelOf(latest.writer), title: item?.title, artist: item.map { $0.artists.map(\.name).joined(separator: ", ") },
                thumbnailUrl: item?.thumbnailUrl, durationSeconds: item?.durationSeconds, playing: latest.cursor.playing, positionMs: latest.cursor.positionMs,
                accountMatches: tag != nil && latest.cursor.accountTag == tag, online: online.contains(latest.writer))
        }
        if let engine { stageLatest(engine, play: false) }
        let mirror = mirrorOf(engine, remote)
        toUi { [owner] in owner?.apply(info, members, remote, mirror) }
    }

    /// 前に合わせた家の最新の再生（同じ状態で置き直さない）。
    private var stagedKey: String?

    /// エンジンの列で: この端末が鳴らしていない間、手元のキューと位置を、家で最後に鳴った端末の再生（同じアカウント）に合わせておく
    /// （Android の stageLatest と同じ）。play なら（本人が手元で再生を押した直後）、今の位置まで合わせて鳴らす（この後 claim する）。
    private func stageLatest(_ engine: ConnectClient, play: Bool) {
        guard !engine.isWriter, let state = tagState else { return }
        guard let now = engine.remoteNowPosition(), now.cursor.accountTag == state.tag else { return }
        guard let queue = engine.latestQueueBody(), engine.latestQueueHash() == now.cursor.queueRef else { return }
        // 鳴っている端末の位置は刻々と変わるので、曲が替わったとき・止まったときだけ合わせ直す（鳴らすときは必ず合わせる）
        let key = "\(now.cursor.queueRef)|\(now.cursor.currentItemId)|\(now.cursor.playing ? "playing" : String(now.positionMs / 1000))"
        if !play && key == stagedKey { return }
        guard let decoded = ConnectQueue.decodeQueue(queue.queue.snapshot), let adjusted = ConnectQueue.atCursor(decoded, now.cursor, now.positionMs) else { return }
        let export = ConnectQueue.toExport(adjusted)
        let snapshotText = queue.queue.snapshot
        let done: Bool = onUi { [player, owner, tube] in
            guard let owner, tube.generation == state.generation else { return false }
            let local = player.currentCursor()
            // 同じキューの同じ曲で位置も近ければそのまま。止まっていれば位置だけ合わせる（鳴らし始めた直後は読み込み中なので、読み直す）
            if let local, local.currentItemId == now.cursor.currentItemId, owner.snapshot() == snapshotText {
                if abs(local.positionMs - adjusted.positionMs) <= ConnectWorker.positionToleranceMs { return true }
                if !play {
                    player.connectStagePosition(adjusted.positionMs)
                    return true
                }
            }
            return play
                ? player.connectAdopt(export, positionMs: adjusted.positionMs, repeatMode: ConnectQueue.repeatOf(adjusted.repeat), listened: adjusted.listenedMs,
                                      historySent: adjusted.historySent)
                : player.connectStage(export, positionMs: adjusted.positionMs, repeatMode: ConnectQueue.repeatOf(adjusted.repeat))
        }
        if !done { return }
        stagedKey = key
        AppPaths.log("connect-v2 staged latest play=\(play)")
    }

    private var mirroredSnapshot: String?
    private var mirroredQueue: QueueExport?
    private var mirroredKey: String?

    /// 下のバーに映す、ほかの端末の再生（同じアカウントで、その端末が中継につながっているときだけ）。
    /// capturedAtMs はメインのスレッドで再生サービスの時計に合わせて入れる。
    private func mirrorOf(_ engine: ConnectClient?, _ remote: RemoteView?) -> RemoteSnapshot? {
        guard let engine, !engine.isWriter, let remote, remote.accountMatches, remote.online else { return nil }
        guard let now = engine.remoteNowPosition(), now.writer == remote.deviceId else { return nil }
        // キューは引き取りの後も前の端末が書いた doc を指すので、書き手ではなくカーソルの queueRef で照らす
        guard let queue = engine.latestQueueBody(), engine.latestQueueHash() == now.cursor.queueRef else { return nil }
        if queue.queue.snapshot != mirroredSnapshot {
            mirroredSnapshot = queue.queue.snapshot
            mirroredQueue = ConnectQueue.decodeQueue(queue.queue.snapshot).map(ConnectQueue.toExport)
            mirroredKey = UUID().uuidString.lowercased()
        }
        guard let export = mirroredQueue, let current = export.items.first(where: { $0.meta.itemId == now.cursor.currentItemId }) else { return nil }
        return RemoteSnapshot(
            deviceId: remote.deviceId, deviceLabel: remote.deviceLabel, queueKey: mirroredKey, queue: export, currentItemId: now.cursor.currentItemId,
            positionMs: now.positionMs, capturedAtMs: 0, playing: now.cursor.playing, repeatMode: ConnectQueue.repeatOf(now.cursor.repeat),
            durationSeconds: current.song.durationSeconds, version: now.version, volumePercent: now.cursor.volumePercent)
    }

    private func notify(_ text: String) {
        toUi { [owner] in owner?.notify(text) }
    }

    // MARK: ConnectClient の外の作用

    func pause() -> ConnectClient.LocalCursor? {
        onUi { [player] in
            player.connectPause()
            return player.currentCursor().map(self.toLocal)
        }
    }

    func adopt(snapshot: String, cursor: CursorBody, positionMs: Int64) -> Bool {
        guard let state = tagState, cursor.accountTag == state.tag else { return false }
        guard let decoded = ConnectQueue.decodeQueue(snapshot), let adjusted = ConnectQueue.atCursor(decoded, cursor, positionMs) else { return false }
        let export = ConnectQueue.toExport(adjusted)
        return onUi { [player, tube] in
            tube.generation == state.generation
                && player.connectAdopt(export, positionMs: adjusted.positionMs, repeatMode: ConnectQueue.repeatOf(adjusted.repeat), listened: adjusted.listenedMs,
                                       historySent: adjusted.historySent)
        }
    }

    func event(_ clientEvent: ClientEvent) {
        AppPaths.log("connect-v2 event=\(ConnectWorker.eventName(clientEvent))")
        onEvent(clientEvent)
        refreshInfo()
    }

    func persist(_ state: ClientPersisted) {
        save(state)
        refreshInfo()
    }

    func controlAccepted(_ action: ControlAction, from deviceId: String) {
        AppPaths.log("connect-v2 control from=\(deviceId.prefix(6)) action=\(action.rawValue)")
    }

    func control(_ action: ControlAction, positionMs: Int64?) -> Bool {
        AppPaths.log("connect-v2 control received action=\(action.rawValue)")
        if action == .transfer {
            remoteTransferRequestedAt.set(clockMs)
            return true
        }
        return onUi { [player] in player.applyControl(action, positionMs: positionMs) }
    }

    func volume(_ volumePercent: Int) -> Bool {
        AppPaths.log("connect-v2 volume received percent=\(volumePercent)")
        return onUi { [player] in player.applyVolume(volumePercent) }
    }

    func queueAdd(_ items: [HandoffItem], next: Bool, afterItemId: String?) -> Bool {
        let entries = items.map(ConnectQueue.entryOf)
        let added = onUi { [player] in player.applyQueueAdd(entries, next: next, afterItemId: afterItemId) }
        AppPaths.log("connect-v2 queue add received items=\(items.count) next=\(next) added=\(added)")
        return added
    }

    /// 手元のライブラリを中継と合わせる（作業の列で呼ぶ）。
    func publishLibrary(_ body: LibraryBody) throws {
        library = body
        try client?.library.publish(body)
    }

    /// ほかの端末と合わせたライブラリが届いた（手元の登録・聴いたところに取り込む）。
    func libraryChanged(_ body: LibraryBody) {
        library = body
        AppPaths.log("connect-v2 library feeds=\(body.feeds.count) positions=\(body.positions.count)")
        toUi { AppServices.feeds.apply(body) }
    }

    func accountTag() -> String? {
        guard let state = tagState, state.generation == tube.generation, state.householdId == client?.householdId else { return nil }
        return state.tag
    }

    /// 出来事の種類の名前だけ（中身の端末 ID などはログに出さない）。
    private static func eventName(_ event: ClientEvent) -> String {
        switch event {
        case .stopped: return "Stopped"
        case .remoteChanged: return "RemoteChanged"
        case .chainForked: return "ChainForked"
        case .chainRolledBack: return "ChainRolledBack"
        case .chainInvalid: return "ChainInvalid"
        case .storeReset: return "StoreReset"
        case .stateEquivocation: return "StateEquivocation"
        case .transferFailed: return "TransferFailed"
        case .transferCompleted: return "TransferCompleted"
        case .serverError: return "ServerError"
        case .joined: return "Joined"
        case .profile: return "Profile"
        }
    }

    /// 表示名から、制御文字と書字方向を変える文字を除く。
    private static let bidiControls: Set<UInt32> = [0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200E, 0x200F]

    private func onEvent(_ e: ClientEvent) {
        if case .profile(let deviceId, let rawName, let kind) = e {
            var scalars = String.UnicodeScalarView()
            scalars.append(contentsOf: rawName.unicodeScalars.filter { $0.properties.generalCategory != .control && !ConnectWorker.bidiControls.contains($0.value) })
            let name = String(scalars).trimmingCharacters(in: .whitespacesAndNewlines)
            if let kind, kind == "pc" || kind == "phone", kinds[deviceId] != kind { rememberKind(deviceId, kind) }
            if !name.isEmpty && labels[deviceId] != name { rememberLabel(deviceId, name) } else { refreshInfo() }
            return
        }
        switch e {
        case .joined, .chainForked, .chainRolledBack, .chainInvalid: joinWaiter.current?.trySet(e)
        default: break
        }
        let text: String?
        switch e {
        case .stopped(let by):
            text = L.t("\(labelOf(by))で再生を始めたので、ここでは止めました", "Paused here because \(labelOf(by)) started playing")
        case .transferCompleted(let from):
            text = L.t("\(labelOf(from))から引き継ぎました", "Took over from \(labelOf(from))")
        case .transferFailed:
            text = L.t("引き継げませんでした", "Couldn’t take over playback")
        case .storeReset:
            text = L.t("中継の状態が作り直されました。「端末の連携」で確かめてください", "The relay’s state was reset. Check Devices")
        case .chainForked, .chainRolledBack, .chainInvalid:
            text = L.t("中継から届いた端末の一覧を確かめられませんでした", "Couldn’t verify the device list from the relay")
        case .stateEquivocation:
            text = L.t("中継から食い違った状態が届きました", "The relay sent conflicting state")
        case .serverError(let code) where code == ConnectService.remotePlaying:
            text = L.t("ほかの端末で再生中なので、ここでは鳴らしませんでした", "Another device is playing, so playback didn’t start here")
        case .serverError(let code) where code == ConnectService.relayUnreachable:
            text = L.t("中継につながらず、ほかの端末の再生を確かめられませんでした", "Couldn’t reach the relay to check your other devices")
        case .serverError(let code) where code == ConnectService.notMemberPlay:
            text = L.t("中継がこの Mac を連携した端末として受け付けませんでした", "The relay didn’t accept this Mac as one of your devices")
        case .serverError(let code) where code == ConnectService.accountUnverifiedPlay:
            text = L.t("アカウントを確かめられませんでした", "Couldn’t verify your account")
        case .serverError(let code) where code == ConnectService.householdCreateUnsent:
            text = L.t("中継に送れませんでした（この Mac では新しく始められない設定かもしれません）", "Couldn’t reach the relay (this Mac may not be allowed to start a new group)")
        default:
            text = nil
        }
        guard let text else { return }
        if case .stopped = e {
            // ほかの端末が曲を選んで「この Mac で鳴らして」と頼む流れでは、止められた直後に引き取りを頼まれる。そのときは知らせない
            let stoppedAt = clockMs
            DispatchQueue.global().asyncAfter(deadline: .now() + 2) { [self] in
                if remoteTransferRequestedAt.current < stoppedAt { notify(text) }
            }
            return
        }
        notify(text)
    }

    // MARK: アカウントタグ

    private func refreshAccountTagIfNeeded(_ householdId: String) {
        if let current = tagState, current.householdId == householdId, current.generation == tube.generation { return }
        if !tube.isLoggedIn { return }
        if tagComputing.exchange(true) { return }
        Task.detached { [self] in
            defer { tagComputing.set(false) }
            guard let account = try? await music.verifiedAccount() else { return }
            let next = TagState(householdId: householdId, tag: AccountTag.of(householdId: householdId, accountId: account.id), generation: account.generation)
            post { [self] in try onTagComputed(next) }
        }
    }

    private func checkLoginGeneration(_ engine: ConnectClient) throws {
        let generation = tube.generation
        let previous = seenGeneration
        seenGeneration = generation
        guard let previous, previous != generation else { return }
        let finalTag = tagState.flatMap { $0.generation == previous ? $0.tag : nil }
        tagState = nil
        try engine.endLocal(finalTag: finalTag)
        AppPaths.log("connect-v2 login changed ended local")
    }

    private func onTagComputed(_ state: TagState) throws {
        guard let engine = client else { return }
        try checkLoginGeneration(engine)
        if state.householdId != engine.householdId || state.generation != tube.generation { return }
        tagState = state
        if !engine.isWriter { try loadLocalFromPlayer(engine) }
        try engine.onAccountTagAvailable()
        refreshInfo()
    }

    // MARK: 本人が再生を始めた（作業の列）

    func userStartedPlayback(newSelection: Bool, intent: Int64, foreground: Bool, hasLocalQueue: Bool) throws {
        guard let engine = client else { return }
        waitCancel?.cancel()
        waitCancel = nil
        let decision = PlayButtonPolicy.onPlay(
            newSelection: newSelection, retreated: engine.hasRetreated, inHousehold: engine.householdId != nil, stateSettled: engine.stateSettled,
            hasAccountTag: accountTag() != nil, remotePlaying: engine.remotePlayingNow(), foreground: foreground, hasLocalQueue: hasLocalQueue)
        if decision == .wait {
            toUi { [player] in player.connectPause() }
            let cancel = CancelSource()
            waitCancel = cancel
            Task.detached { [self] in await decideAfterWait(intent: intent, foreground: foreground, cancel: cancel) }
            return
        }
        try apply(engine, decision)
    }

    private func apply(_ engine: ConnectClient, _ decision: PlayButtonPolicy.Decision) throws {
        switch decision {
        case .claim:
            try claimLocal(engine)
        case .syncAndClaim:
            // 家で最後に鳴った再生（今ほかの端末で鳴っていれば、その位置）に合わせてから、この端末の再生にする
            stageLatest(engine, play: true)
            try claimLocal(engine)
        case .resumeAndClaim:
            stageLatest(engine, play: false)
            onUi { [player] in player.connectResume() }
            try claimLocal(engine)
        case .takeOver:
            toUi { [player] in player.connectPause() }
            if try engine.requestTransfer() {
                AppPaths.log("connect-v2 play button became transfer")
            } else {
                onEvent(.serverError(code: ConnectService.remotePlaying))
            }
        case .blockRemotePlaying:
            toUi { [player] in player.connectPause() }
            onEvent(.serverError(code: ConnectService.remotePlaying))
        case .notifyNotMember:
            onEvent(.serverError(code: ConnectService.notMemberPlay))
        case .notifyUnreachable:
            onEvent(.serverError(code: ConnectService.relayUnreachable))
        case .notifyUnverified:
            onEvent(.serverError(code: ConnectService.accountUnverifiedPlay))
        case .wait, .nothing:
            break
        }
    }

    /// 中継の最新の状態とアカウントタグがそろうまで（最大 10 秒）待ってから、D-9 の判断をする。
    private func decideAfterWait(intent: Int64, foreground: Bool, cancel: CancelSource) async {
        let relay = self.relay!
        relay.acquire()
        defer { relay.release() }
        do {
            var outcome: PlayButtonPolicy.WaitOutcome?
            let startedAt = clockMs
            while outcome == nil && clockMs - startedAt < ConnectWorker.settleWaitMs {
                let status = relay.status
                let ready = try await onEngine { [self] in client.map { $0.stateSettled && accountTag() != nil } ?? false }
                outcome = PlayButtonPolicy.waitStep(notMember: status == .notMember, tailnetUnreachable: status == .tailnetUnreachable, settledAndTagged: { ready })
                if outcome == nil { try await connectDelay(100, cancel) }
            }
            if outcome == nil {
                outcome = try await onEngine { [self] in PlayButtonPolicy.timeoutOutcome(settled: client?.stateSettled == true) }
            }
            if cancel.isCancelled { return }
            guard let final = outcome else { return }
            post { [self] in
                guard let engine = client else { return }
                let decision = PlayButtonPolicy.afterWait(final, intentUnchanged: playIntent.current == intent, stillRetreated: engine.hasRetreated,
                                                          remotePlaying: engine.remotePlayingNow(), foreground: foreground)
                try apply(engine, decision)
                AppPaths.log("connect-v2 play button waited outcome=\(final.rawValue) decision=\(decision.rawValue)")
            }
        } catch {
            // 取り消された（本人が別の操作をした）
        }
    }

    private func loadLocalFromPlayer(_ engine: ConnectClient) throws {
        let (snapshot, cursor): (String?, ConnectClient.LocalCursor?) = onUi { [player, owner] in
            (owner?.snapshot(), player.currentCursor().map(self.toLocal))
        }
        if let snapshot { try engine.setLocalQueue(snapshot) }
        if let cursor { try engine.setLocalCursor(cursor) }
    }

    private func claimLocal(_ engine: ConnectClient) throws {
        try loadLocalFromPlayer(engine)
        let claimed = try engine.userStartedPlayback()
        refreshInfo()
        AppPaths.log("connect-v2 user started claimed=\(claimed)")
    }

    func toLocal(_ c: PlaybackCursor) -> ConnectClient.LocalCursor {
        ConnectClient.LocalCursor(
            currentItemId: c.currentItemId, positionMs: c.positionMs, playing: c.playing, repeat: ConnectQueue.repeatOf(c.repeatMode), listenedMs: c.listenedMs,
            historySent: c.historySent, capturedAtMs: clockMs, volumePercent: c.volumePercent)
    }

    // MARK: 家の作成・記録の削除・鎖の追記

    /// 起動時の設定の鍵の端末で、家を作る（家が無いときだけ中継が受ける）。
    func createHousehold() throws {
        guard let engine = client, engine.householdId == nil else { return }
        engine.beginCreateHousehold()
        let selfKeys = try keys.memberKeys(role: .player)
        relay.reconnect()
        let sent = send(.householdCreate(.init(genesis: try MemberChain.genesis(signer: keys, keys: selfKeys)))) && send(.chainGet(.init(fromSeq: 0)))
        if !sent { onEvent(.serverError(code: ConnectService.householdCreateUnsent)) }
        AppPaths.log("connect-v2 household create sent=\(sent)")
    }

    /// 家から外された端末で、本人が押したら、この端末の家の記録を消す。
    func forgetHousehold() {
        client?.clearLocal()
        ConnectKeychain.delete(ConnectKeychain.stateAccount)
        tagState = nil
        seenGeneration = nil
        unreadable = false
        load()
        relay.restart()
        AppPaths.log("connect-v2 household forgotten")
    }

    func appendRecord(_ record: ChainRecord, done: @escaping ([MemberKeys]) -> Bool) async -> Bool {
        let reply = Promise<RelayV2>()
        chainReply.set(reply)
        if !send(.chainAppend(.init(record: record))) { return false }
        if let result = await reply.wait(timeoutMs: 10_000) {
            if case .chainOk = result { return true }
            guard case .error(let error) = result, error.requestId != nil else { return false }
        }
        // 返事が来なかった: 鎖を取り直して、今の端末一覧で済んでいるかを見る（やり直すと二重の記録になる）
        send(.chainGet(.init(fromSeq: 0)))
        for _ in 0..<25 {
            if (try? await onEngine { [self] in client.map { done($0.members) } ?? false }) == true { return true }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        return false
    }
}

// MARK: - キューの書き出し・取り込み

/// キューの doc と再生サービスの形の行き来（Windows の ConnectService の static の部分）。
enum ConnectQueue {
    /// YouTube の曲か、RSS のエピソード（`ext:` + 音声の URL。受け取った側も同じ URL を流せる。ADR-0012）。
    static func validVideoId(_ id: String) -> Bool { (id.utf16.count == 11 && matches(id, min: 11, max: 11)) || HandoffCodec.isItemVideoId(id) }

    /// [A-Za-z0-9_-]{min,max}
    static func matches(_ id: String, min: Int, max: Int) -> Bool {
        let scalars = id.unicodeScalars
        guard scalars.count >= min && scalars.count <= max else { return false }
        return scalars.allSatisfy { s in
            (s.value >= 0x30 && s.value <= 0x39) || (s.value >= 0x41 && s.value <= 0x5A) || (s.value >= 0x61 && s.value <= 0x7A) || s == "_" || s == "-"
        }
    }

    static func validBrowseId(_ id: String?) -> String? {
        guard let id, matches(id, min: 2, max: 64) else { return nil }
        return id
    }

    /// UTF-16 の符号単位で max までに切り詰める（サロゲートの途中では切らない）。
    static func truncated(_ text: String, _ max: Int) -> String {
        if text.utf16.count <= max { return text }
        var result = String.UnicodeScalarView()
        var units = 0
        for scalar in text.unicodeScalars {
            let size = scalar.utf16.count
            if units + size > max { break }
            units += size
            result.append(scalar)
        }
        return String(result)
    }

    static func itemOf(_ song: Song, _ meta: QueueItemMeta) -> HandoffItem {
        let album: HandoffRef?
        if let a = song.album, a.title.utf16.count > 0 && a.title.utf16.count <= 500 {
            album = HandoffRef(name: a.title, browseId: validBrowseId(a.browseId))
        } else {
            album = nil
        }
        let origin: HandoffOrigin = switch meta.origin {
        case .suggestion: .suggestion
        case .userAdded: .userAdded
        case .source: .source
        }
        return HandoffItem(
            itemId: meta.itemId, videoId: song.videoId, title: truncated(song.title, 500),
            artists: song.artists.filter { $0.name.utf16.count > 0 && $0.name.utf16.count <= 500 }.prefix(20).map { HandoffRef(name: $0.name, browseId: validBrowseId($0.browseId)) },
            album: album, durationSeconds: song.durationSeconds.flatMap { $0 >= 0 && $0 <= 24 * 3600 ? $0 : nil }, thumbnailUrl: song.thumbnailUrl, origin: origin)
    }

    /// 受け渡しの項目を、キューの曲と項目の情報へ（toExport と同じ変換）。
    static func entryOf(_ i: HandoffItem) -> (song: Song, meta: QueueItemMeta) {
        let song = Song(videoId: i.videoId, title: i.title, artists: i.artists.map { ArtistRef(name: $0.name, browseId: $0.browseId) },
                        album: i.album.map { AlbumRef(title: $0.name, browseId: $0.browseId) }, durationSeconds: i.durationSeconds, thumbnailUrl: i.thumbnailUrl)
        let origin: QueueOrigin = switch i.origin {
        case .suggestion: .suggestion
        case .userAdded: .userAdded
        case .source: .source
        }
        return (song, QueueItemMeta(itemId: i.itemId, origin: origin))
    }

    static func decodeQueue(_ text: String) -> HandoffSnapshot? {
        guard let id = ConnectJSON.decode(HandoffSnapshot.self, from: text)?.handoffId else { return nil }
        guard case .ok(let snapshot) = HandoffCodec.decode(text, expectedHandoffId: id) else { return nil }
        return snapshot
    }

    /// キューの doc を、カーソルの今の曲・リピート・履歴に合わせる（Android の RemoteQueue.atCursor と同じ）。
    static func atCursor(_ snapshot: HandoffSnapshot, _ cursor: CursorBody, _ positionMs: Int64) -> HandoffSnapshot? {
        guard let index = snapshot.items.firstIndex(where: { $0.itemId == cursor.currentItemId }) else { return nil }
        let (window, _, truncatedTail) = HandoffCodec.window(snapshot.items, currentIndex: index)
        var moved = snapshot
        moved.items = window
        moved.continuation = truncatedTail ? nil : snapshot.continuation
        moved.exhausted = !truncatedTail && snapshot.exhausted
        moved.currentItemId = cursor.currentItemId
        moved.positionMs = max(0, positionMs)
        moved.repeat = cursor.repeat
        moved.listenedMs = max(0, cursor.listenedMs)
        moved.historySent = cursor.historySent
        guard case .ok(let valid) = HandoffCodec.validate(moved, expectedHandoffId: moved.handoffId) else { return nil }
        return valid
    }

    static func toExport(_ snapshot: HandoffSnapshot) -> QueueExport {
        var playlistId: String?
        var radioSeed: String?
        switch snapshot.source {
        case .playlist(let id): playlistId = id
        case .radio(let seed): radioSeed = seed
        case nil: break
        }
        let isPlaylist = playlistId != nil
        return QueueExport(
            items: snapshot.items.map { i in
                let song = Song(videoId: i.videoId, title: i.title, artists: i.artists.map { ArtistRef(name: $0.name, browseId: $0.browseId) },
                                album: i.album.map { AlbumRef(title: $0.name, browseId: $0.browseId) }, durationSeconds: i.durationSeconds, thumbnailUrl: i.thumbnailUrl)
                let origin: QueueOrigin = switch i.origin {
                case .suggestion: .suggestion
                case .userAdded: .userAdded
                case .source: .source
                }
                return (song: song, meta: QueueItemMeta(itemId: i.itemId, origin: origin))
            },
            currentItemId: snapshot.currentItemId, sourceId: playlistId, radioSeed: radioSeed,
            // プレイリストの続き（Android が途中まで読んだもの）は、Mac のラジオの続きとは別物なので使わない
            continuation: isPlaylist || snapshot.exhausted ? nil : snapshot.continuation,
            radioPlaylistId: snapshot.radioPlaylistId)
    }

    static func repeatOf(_ mode: RepeatMode) -> HandoffRepeat {
        switch mode {
        case .one: .one
        case .all: .all
        case .off: .off
        }
    }

    static func repeatOf(_ repeat: HandoffRepeat) -> RepeatMode {
        switch `repeat` {
        case .one: .one
        case .all: .all
        case .off: .off
        }
    }
}

// MARK: - 画面に出す値

struct ConnectInfo: Equatable {
    var configured = false
    var loaded = false
    var unreadable = false
    var inHousehold = false
    var isWriter = false
    var retreated = false
    var storeResetPending = false
    var relay = RelayStatus.disconnected
}

struct MemberView: Equatable, Identifiable {
    var deviceId: String
    var label: String
    var isSelf: Bool
    var isController: Bool
    var online: Bool
    var active: Bool
    var isPc = false

    var id: String { deviceId }
}

struct RemoteView: Equatable {
    var deviceId: String
    var deviceLabel: String
    var title: String?
    var artist: String?
    var thumbnailUrl: String?
    var durationSeconds: Int?
    var playing: Bool
    var positionMs: Int64
    var accountMatches: Bool
    var online: Bool
}

enum PairingKind { case idle, working, showingOffer, enterCode, showCode, done, failed }

struct PairingView: Equatable {
    var kind: PairingKind
    var text: String?
    var offerText: String?
    var code: String?
    var peerName: String?

    static let idle = PairingView(kind: .idle)
    static func working(_ text: String) -> PairingView { PairingView(kind: .working, text: text) }
    static func showingOffer(_ offer: String) -> PairingView { PairingView(kind: .showingOffer, offerText: offer) }
    static func enterCode(_ message: String?) -> PairingView { PairingView(kind: .enterCode, text: message) }
    static func showCode(_ code: String, peer: String) -> PairingView { PairingView(kind: .showCode, code: code, peerName: peer) }
    static func done(_ peer: String) -> PairingView { PairingView(kind: .done, peerName: peer) }
    static func failed(_ message: String) -> PairingView { PairingView(kind: .failed, text: message) }
}
