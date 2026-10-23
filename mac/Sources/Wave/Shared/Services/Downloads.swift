import Foundation
import Network
import Observation
import WaveCore

/// ダウンロードとスマートダウンロード（ADR-0016）。何を取るかの目録（downloads.json）を持ち、取っていない曲を1つずつ取る。
/// 使う通信は設定に従い（従量制は OS に聞く）、合わない通信の間は待つ。プレイリスト・アルバム・高く評価した曲は、
/// 起動したとき・ページを開いたとき・1日1回読み直して合わせる。動いている間は6時間おきに、1日1回の時かを確かめる。
@MainActor @Observable
final class Downloads {
    private static var manifestURL: URL { AppPaths.file("downloads.json") }
    private static var countsURL: URL { AppPaths.file("play-counts.json") }
    private static let checkInterval: TimeInterval = 6 * 60 * 60
    /// 通信の失敗のあと、取り直すまで待つ秒数（通信が変わったら待たずに）。
    private static let retryDelay: TimeInterval = 60
    /// 一度に取るバイト数（このたびに進み具合を書き、通信が合うかを確かめる）。
    private static let stepBytes: Int64 = 2 * 1024 * 1024

    nonisolated let store: DownloadStore
    private(set) var manifest = DownloadManifest()
    /// 取り終えた曲。
    private(set) var completed: Set<String> = []
    /// 今取っている曲と、その進み（0〜1）。
    private(set) var active: String?
    private(set) var activeProgress = 0.0
    private(set) var totalBytes: Int64 = 0
    /// つながっているか・従量制（高い・節約の通信）か。
    private(set) var connected = true
    private(set) var metered = false
    /// スマートダウンロードを選び直している。
    private(set) var refreshingSmart = false
    /// 開発用: 通信できないことにする。
    var debugOffline = false {
        didSet { if !debugOffline { kick() } }
    }

    @ObservationIgnored private var counts = PlayCounts()
    /// 取れないと分かった曲（拒否・再生できない）。次の起動か、スマートダウンロードの選び直しまで取らない。
    @ObservationIgnored private var blocked: Set<String> = []
    /// 通信の失敗で、この時刻まで待つ曲。
    @ObservationIgnored private var retryAt: [String: Date] = [:]
    @ObservationIgnored private var retryTask: Task<Void, Never>?
    @ObservationIgnored private var worker: Task<Void, Never>?
    /// 取り手の世代（すべて削除で止めた取り手が、後の取り手の印を消さないように）。
    @ObservationIgnored private var workerGeneration = 0
    @ObservationIgnored private let monitor = NWPathMonitor()
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var syncing = false

    nonisolated private static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    init() {
        store = DownloadStore(directory: AppPaths.data.appendingPathComponent("downloads", isDirectory: true), nowMs: { Downloads.nowMs })
        if let data = try? Data(contentsOf: Downloads.manifestURL), let m = DownloadManifest.decode(data) { manifest = m }
        if let data = try? Data(contentsOf: Downloads.countsURL), let c = PlayCounts.decode(data) { counts = c }
        completed = store.completedIds
        // 目録に無いファイルと、取り終えていないファイル（途中で閉じた分。続きからは取らず、頭から取り直す）を消す
        let owned = Set(manifest.wanted)
        for id in store.allIds where !owned.contains(id) || !completed.contains(id) { store.remove(id) }
        totalBytes = store.totalBytes
    }

    // MARK: 状態

    var settings: Settings { AppServices.settings }
    var policy: DownloadNetworkPolicy { DownloadNetworkPolicy(rawValue: settings.downloadNetwork) ?? .unmetered }
    /// 通信できない（取っていない曲は飛ばす）。
    var offline: Bool { !connected || debugOffline }
    /// 今の通信で取ってよい。
    var allowed: Bool { !debugOffline && policy.allows(connected: connected, metered: metered) }
    /// まだ取っていない曲。
    var pending: [String] { manifest.wanted.filter { !completed.contains($0) } }
    /// 取る曲があるのに、通信が合わず待っている。
    var waitingForNetwork: Bool { !pending.isEmpty && !allowed }

    func isDownloaded(_ id: String) -> Bool { completed.contains(id) }
    func isWanted(_ id: String) -> Bool { manifest.isOwned(id) }
    /// 曲のメニューの「ダウンロードを削除」を出すか（1曲ずつか、スマートダウンロードで取った曲）。丸ごとのものは、そのページで外す。
    func canRemove(_ id: String) -> Bool { manifest.isSingle(id) || manifest.smart.contains { $0.videoId == id } }

    /// 鳴らせる取り終えた曲。
    func playable(_ id: String) -> DownloadedFile? { completed.contains(id) ? store.completed(id) : nil }

    func song(_ id: String) -> Song? { manifest.songs[id]?.song }

    enum CollectionState: Equatable {
        case none
        case downloading(done: Int, total: Int)
        case done
    }

    func state(_ collectionId: String) -> CollectionState {
        guard let c = manifest.collection(collectionId) else { return .none }
        let done = c.trackIds.filter { completed.contains($0) }.count
        return done >= c.trackIds.count ? .done : .downloading(done: done, total: c.trackIds.count)
    }

    // MARK: 始める

    func start() {
        monitor.pathUpdateHandler = { path in
            // VPN（Tailscale など）だけでつながって見えるときは、通信できないとみなす（機内モードでも VPN の口は「つながっている」になる）
            let physical = path.usesInterfaceType(.wifi) || path.usesInterfaceType(.cellular) || path.usesInterfaceType(.wiredEthernet)
            let connected = path.status == .satisfied && physical
            let metered = path.isExpensive || path.isConstrained
            Task { @MainActor in AppServices.downloads.networkChanged(connected: connected, metered: metered) }
        }
        monitor.start(queue: DispatchQueue(label: "wave.downloads.network"))
        // 起動の少し後に一覧を読み直し、その後は6時間おきに1日1回の時かを確かめる（Mac。iPhone は動いている間と、裏の処理で）
        timer = Timer.scheduledTimer(withTimeInterval: 20, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                Task { await self.refresh(collections: true) }
                self.timer = Timer.scheduledTimer(withTimeInterval: Downloads.checkInterval, repeats: true) { [weak self] _ in
                    MainActor.assumeIsolated { if let self { Task { await self.refresh(collections: false) } } }
                }
            }
        }
        kick()
    }

    private func networkChanged(connected: Bool, metered: Bool) {
        let changed = self.connected != connected || self.metered != metered
        self.connected = connected
        self.metered = metered
        // 通信が変わったら、通信の失敗で待っている曲を待たずに取り直す（変わらない知らせは記録にも書かない）
        guard changed else { return }
        retryAt = [:]
        AppPaths.log("downloads network connected=\(connected) metered=\(metered) allowed=\(allowed)")
        if allowed { kick() }
    }

    /// 一覧の読み直し（collections が true なら必ず、ほかは1日1回の時だけ）とスマートダウンロードの選び直し（1日1回）をして、取る。
    func refresh(collections: Bool) async {
        if collections || manifest.collectionsDue(nowMs: Downloads.nowMs) { await syncCollections() }
        await refreshSmart(force: false)
        kick()
    }

    // MARK: 選ぶ

    func download(_ song: Song, episode: Bool = false) {
        guard !song.isLive else { return }
        manifest.addSingle(DownloadSong(song, episode: episode))
        changed()
    }

    /// 1曲ずつ取った印（とスマートダウンロードの印）を外す。丸ごとのものに入っている曲は残る。
    func remove(_ id: String) {
        manifest.singles.removeAll { $0 == id }
        manifest.smart.removeAll { $0.videoId == id }
        changed()
    }

    func downloadCollection(_ kind: DownloadCollectionKind, id: String, title: String, thumbnailUrl: String?, songs: [Song]) {
        manifest.setCollection(kind, id: id, title: title, thumbnailUrl: thumbnailUrl, songs: songs.filter { !$0.isLive }.map { DownloadSong($0) },
                               nowMs: Downloads.nowMs)
        AppPaths.log("downloads add collection kind=\(kind.rawValue) songs=\(songs.count)")
        changed()
    }

    func removeCollection(_ id: String) {
        manifest.removeCollection(id)
        changed()
    }

    /// ページで読み込んだ中身に合わせる（取っているものだけ）。
    func pageLoaded(_ id: String, title: String?, thumbnailUrl: String?, songs: [Song]) {
        guard let c = manifest.collection(id), !songs.isEmpty else { return }
        let diff = manifest.setCollection(c.kind, id: id, title: title ?? c.title, thumbnailUrl: thumbnailUrl,
                                          songs: songs.filter { !$0.isLive }.map { DownloadSong($0) }, nowMs: Downloads.nowMs)
        if !diff.added.isEmpty || !diff.removed.isEmpty { AppPaths.log("downloads page sync added=\(diff.added.count) removed=\(diff.removed.count)") }
        changed()
    }

    /// 取っているものの、通信なしで出す曲（取り終えたものだけ）。
    func offlineSongs(_ collectionId: String) -> [Song]? {
        guard let c = manifest.collection(collectionId) else { return nil }
        return c.trackIds.filter { completed.contains($0) }.compactMap { manifest.songs[$0]?.song }
    }

    // MARK: 設定

    func setPolicy(_ policy: DownloadNetworkPolicy) {
        settings.downloadNetwork = policy.rawValue
        settings.save()
        kick()
    }

    func setSmart(_ on: Bool) {
        settings.smartDownloads = on
        settings.save()
        if on {
            Task { await refreshSmart(force: true) }
        } else {
            manifest.clearSmart()
            changed()
        }
    }

    func setSmartCount(_ count: Int) {
        guard count != settings.smartDownloadCount else { return }
        settings.smartDownloadCount = count
        settings.save()
        if settings.smartDownloads { Task { await refreshSmart(force: true) } }
    }

    /// 「ダウンロードをすべて削除」。スマートダウンロードの入り切りは残し、選び直しは次の1日1回の時にする。
    func deleteAll() {
        worker?.cancel()
        worker = nil
        workerGeneration += 1
        active = nil
        store.clear()
        manifest = DownloadManifest()
        manifest.smartRefreshedAtMs = Downloads.nowMs
        manifest.collectionsSyncedAtMs = Downloads.nowMs
        completed = []
        save()
        totalBytes = 0
        AppPaths.log("downloads deleted all")
    }

    /// ログアウト: 取った曲も、この端末の再生回数も消す（ほかのアカウントに残さない。ADR-0007）。
    func clearAccount() {
        deleteAll()
        manifest = DownloadManifest()
        save()
        counts = PlayCounts()
        try? FileManager.default.removeItem(at: Downloads.countsURL)
    }

    // MARK: 再生回数

    /// 1回聴いた（再生履歴を送るのと同じだけ聴いた）。
    func recordPlay(_ song: Song) {
        guard !song.isLive, !Feed.isExternal(song.videoId) else { return }
        counts.record(DownloadSong(song), nowMs: Downloads.nowMs)
        if let data = counts.encode() { try? data.write(to: Downloads.countsURL, options: .atomic) }
    }

    // MARK: 一覧の読み直し

    func syncCollections() async {
        guard !syncing, AppServices.client.isLoggedIn, !offline, !manifest.collections.isEmpty else { return }
        syncing = true
        defer { syncing = false }
        for c in manifest.collections {
            var songs: [Song]?
            switch c.kind {
            case .album: songs = (try? await AppServices.music.album(c.id))?.value?.songs
            case .playlist, .liked:
                // 最後まで読めなかった一覧では合わせない（読めなかった曲を外して消してしまわない）
                let page = (try? await AppServices.music.fullPlaylist(c.id))?.value
                songs = page?.continuation == nil ? page?.songs : nil
            }
            // 読めなかった（通信の失敗など）ときは、今の中身のまま
            guard let songs, !songs.isEmpty, manifest.collection(c.id) != nil else { continue }
            let diff = manifest.setCollection(c.kind, id: c.id, title: c.title, thumbnailUrl: nil, songs: songs.filter { !$0.isLive }.map { DownloadSong($0) },
                                              nowMs: Downloads.nowMs)
            AppPaths.log("downloads sync kind=\(c.kind.rawValue) songs=\(songs.count) added=\(diff.added.count) removed=\(diff.removed.count)")
        }
        manifest.collectionsSyncedAtMs = Downloads.nowMs
        changed()
    }

    // MARK: スマートダウンロード

    /// 3つの分類の候補を集めて選び直す（1日1回。force なら今）。集められなければ前のまま。
    func refreshSmart(force: Bool) async {
        guard settings.smartDownloads, AppServices.client.isLoggedIn, !offline, !refreshingSmart,
              force || manifest.smartDue(nowMs: Downloads.nowMs) else { return }
        refreshingSmart = true
        defer { refreshingSmart = false }
        let now = Downloads.nowMs
        let count = settings.smartDownloadCount
        let history = (try? await AppServices.music.history())?.value?.sections.flatMap(\.songs) ?? []
        let frequent = SmartDownloads.frequent(counts: counts, history: history)
        let (liked, _) = await AppServices.library.likedSongs()
        let known = Set(counts.entries.keys).union(history.map(\.videoId)).union(liked.map(\.videoId))
        var radios: [[Song]] = []
        for seed in frequent.prefix(SmartDownloads.seedCount) {
            if let page = (try? await AppServices.music.upNext(videoId: seed.videoId, playlistId: QueueRadio.playlistId(seed.videoId)))?.value {
                radios.append(page.songs)
            }
        }
        let discover = SmartDownloads.discover(radios: radios, known: known)
        let shelves = (try? await AppServices.music.home())?.value?.shelves ?? []
        var recommended = SmartDownloads.recommended(shelves: shelves)
        // 足りなければ、ホームの最初のミックスの曲で埋める
        if recommended.count < (count + 2) / 3, let mix = SmartDownloads.firstMix(shelves: shelves),
           let page = (try? await AppServices.music.playlist(mix.playlistId))?.value
        {
            var seen = Set(recommended.map(\.videoId))
            recommended += page.songs.filter { seen.insert($0.videoId).inserted }
        }
        let picked = SmartDownloads.pick([.frequent: frequent, .discover: discover, .recommended: recommended], count: count)
        AppPaths.log("downloads smart frequent=\(frequent.count) discover=\(discover.count) recommended=\(recommended.count) picked=\(picked.count)")
        guard !picked.isEmpty, settings.smartDownloads else { return }
        manifest.setSmart(picked.map { (DownloadSong($0.song), $0.category) }, nowMs: now)
        // 選び直したら、取れなかった曲ももう一度試す
        blocked = []
        retryAt = [:]
        changed()
    }

    // MARK: 取る

    /// 目録が変わった: 持ち主のいなくなった曲を消し、書き、取る。
    private func changed() {
        for id in manifest.prune() where id != active {
            store.remove(id)
            completed.remove(id)
        }
        for id in completed where !manifest.isOwned(id) && id != active {
            store.remove(id)
            completed.remove(id)
        }
        save()
        totalBytes = store.totalBytes
        kick()
    }

    private func save() {
        if let data = manifest.encode() { try? data.write(to: Downloads.manifestURL, options: .atomic) }
    }

    /// 取る曲があり、通信が合えば、取り始める（1つずつ）。
    func kick() {
        guard worker == nil, allowed, !pending.isEmpty else { return }
        workerGeneration += 1
        let mine = workerGeneration
        worker = Task { await run(mine) }
    }

    /// 取り手が動いている。
    var isWorking: Bool { worker != nil }

    /// 取るのを止める（iPhone の裏の処理の時間切れ）。取れた分は残り、次に続きから取る。
    func pause() { worker?.cancel() }

    private enum Outcome { case done, failed, paused, dropped, removed }

    private func run(_ mine: Int) async {
        defer {
            if mine == workerGeneration {
                worker = nil
                active = nil
                activeProgress = 0
                totalBytes = store.totalBytes
            }
        }
        while !Task.isCancelled, allowed {
            let loggedIn = AppServices.client.isLoggedIn
            // YouTube の曲はログインしているときだけ取れる（RSS のエピソードは配信元から）
            let now = Date()
            guard let id = pending.first(where: { !blocked.contains($0) && (retryAt[$0].map { $0 <= now } ?? true) && (loggedIn || Feed.isExternal($0)) }),
                  let song = manifest.songs[id] else { break }
            active = id
            activeProgress = 0
            let outcome = Feed.isExternal(id) ? await fetchEpisode(song) : await fetchSong(song)
            // すべて削除で止められた
            guard mine == workerGeneration else { return }
            AppPaths.log("downloads fetch outcome=\(outcome) pending=\(pending.count)")
            switch outcome {
            case .done:
                completed.insert(id)
                retryAt.removeValue(forKey: id)
            case .failed: retryAt[id] = Date().addingTimeInterval(Downloads.retryDelay)
            case .dropped: blocked.insert(id)
            case .paused: return
            case .removed: break
            }
            // 取っている間に外された
            if !manifest.isOwned(id) {
                store.remove(id)
                completed.remove(id)
            }
            totalBytes = store.totalBytes
        }
        scheduleRetry()
    }

    /// 通信の失敗で待っている曲があれば、待ち終わる頃にもう一度取り始める。
    private func scheduleRetry() {
        guard let first = retryAt.values.min(), retryTask == nil else { return }
        retryTask = Task {
            try? await Task.sleep(for: .seconds(max(1, first.timeIntervalSinceNow) + 1))
            retryTask = nil
            kick()
        }
    }

    /// YouTube の曲を頭から最後まで取る（再生と同じ形式。拒否されたら URL を調べ直して続きから）。
    private func fetchSong(_ song: DownloadSong) async -> Outcome {
        let id = song.videoId
        store.prepare(song)
        for attempt in 0..<3 {
            guard let result = try? await AppServices.resolver.resolve(videoId: id) else { return .failed }
            guard let stream = result.stream else {
                AppPaths.log("downloads resolve failed reason=\(String(describing: result.failure))")
                // 通信の失敗は少し待って取り直す。ほか（ライブ配信・再生できない・ログインの確認など）は次の起動まで取らない
                return result.failure == .network || result.failure == .http ? .failed : .dropped
            }
            guard let length = stream.contentLength, length > 0 else { return .dropped }
            do {
                let reader = try await RangeStream.open(session: AppServices.mediaSession, stream: stream, cache: store)
                while true {
                    let have = store.entry(id)?.cached ?? 0
                    activeProgress = Double(have) / Double(length)
                    if have >= length { break }
                    if !manifest.isOwned(id) { return .removed }
                    if Task.isCancelled || !allowed { return .paused }
                    try await reader.warm(upTo: min(length, have + Downloads.stepBytes))
                }
                return store.finish(id) ? .done : .failed
            } catch is StreamRejectedError {
                AppPaths.log("downloads stream rejected attempt=\(attempt)")
                AppServices.resolver.onStreamRejected()
                if attempt == 2 { return .dropped }
            } catch {
                return Task.isCancelled ? .paused : .failed
            }
        }
        return .failed
    }

    /// RSS のエピソードを配信元から丸ごと取る（cookie は渡さない）。
    private func fetchEpisode(_ song: DownloadSong) async -> Outcome {
        guard let audio = Feed.audioUrlOf(song.videoId), let url = URL(string: audio) else { return .dropped }
        var request = URLRequest(url: url)
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.1.0"
        request.setValue("wave/\(version)", forHTTPHeaderField: "User-Agent")
        do {
            let (temp, response) = try await AppServices.mediaSession.download(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                try? FileManager.default.removeItem(at: temp)
                return .failed
            }
            try store.storeWhole(song, mimeType: http.mimeType ?? "audio/mpeg", from: temp)
            return .done
        } catch {
            return Task.isCancelled ? .paused : .failed
        }
    }
}
