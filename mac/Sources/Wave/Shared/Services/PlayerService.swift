#if os(macOS)
    import AppKit
#else
    import UIKit
#endif
import AVFoundation
import Foundation
import MediaPlayer
import Observation
import WaveCore

enum RepeatMode: Int, Codable { case off = 0, all = 1, one = 2 }

enum QueueOrigin: String, Codable { case source, suggestion, userAdded }

/// キューの項目の ID（UUID）と出どころ。端末の連携で、どの項目が今の曲かを相手と合わせるのに使う。
struct QueueItemMeta: Hashable {
    var itemId: String
    var origin: QueueOrigin
}

/// キューの書き出し（端末の連携）。sourceId はプレイリスト・アルバムの ID、radioSeed はラジオの元の曲。
struct QueueExport {
    var items: [(song: Song, meta: QueueItemMeta)]
    var currentItemId: String
    var sourceId: String?
    var radioSeed: String?
    var continuation: String?
    var radioPlaylistId: String?
}

struct PlaybackCursor {
    var currentItemId: String
    var positionMs: Int64
    var playing: Bool
    var repeatMode: RepeatMode
    var listenedMs: Int64
    var historySent: Bool
}

/// ほかの端末の再生（表示と遠隔操作のため）。queueKey はキューの中身の目印（同じなら置き直さない）。
struct RemoteSnapshot {
    var deviceId: String
    var deviceLabel: String
    var queueKey: String?
    var queue: QueueExport?
    var currentItemId: String
    var positionMs: Int64
    var capturedAtMs: Int64
    var playing: Bool
    var repeatMode: RepeatMode
    var durationSeconds: Int?
    var version: Int64 = 0

    /// 今の位置（再生中なら、取ってからの経過を足す。曲の長さで止める）。
    func positionNow(_ nowMs: Int64) -> Double {
        var ms = playing ? positionMs + max(0, nowMs - capturedAtMs) : positionMs
        if let d = durationSeconds, d > 0 { ms = min(ms, Int64(d) * 1000) }
        return Double(ms) / 1000
    }
}

/// 再生キューと再生（AVPlayer）。メディアキー・コントロールセンターの「再生中」にも曲名と画像を出す。
/// 署名付きの URL は再生の部品に渡さず、ログ・画面に出さない。Windows 版の PlayerService と同じ振る舞い。
@MainActor @Observable
final class PlayerService {
    private static let statsAfterSeconds = 10.0
    private static let freshMarginMs: Int64 = 5 * 60 * 1000
    private static let maxConsecutiveFailures = 3

    @ObservationIgnored let engine = AudioEngine()
    @ObservationIgnored private var prefetched: [String: ResolvedStream] = [:]
    @ObservationIgnored private var prefetchOrder: [String] = []
    /// シャッフルを入れたときの、今の曲より後ろの並び（項目の ID → 位置）。切ると残りをこの並びに戻す（Android の QueueSession と同じ）。
    /// nil ならシャッフルは切れている。シャッフル中に足した曲は、戻すときに今の順のまま最後に付く。
    @ObservationIgnored private var shuffleOrder: [String: Int]?
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var loadToken = 0
    @ObservationIgnored private var currentStream: ResolvedStream?
    @ObservationIgnored private var cpn = ""
    @ObservationIgnored private var statsSent = false
    /// 曲の長さに着いて、終わりの扱いをもう済ませたか（AVPlayer の終わりの知らせと二重にしない）。
    @ObservationIgnored private var endHandled = false
    @ObservationIgnored private var retried = false
    @ObservationIgnored private var radioContinuation: String?
    @ObservationIgnored private var radioPlaylistId: String?
    @ObservationIgnored private var extending = false
    @ObservationIgnored private var consecutiveFailures = 0
    @ObservationIgnored private var radioSeed: String?

    // 端末の連携（Connect v2）: キューの項目ごとの ID と出どころ（queue と同じ並び）、今の曲を聴いた時間
    @ObservationIgnored private(set) var itemMeta: [QueueItemMeta] = []
    @ObservationIgnored private let started = Date()
    @ObservationIgnored private var listenedMs: Int64 = 0
    @ObservationIgnored private var playingSinceMs: Int64?
    @ObservationIgnored private var listenedItemId: String?

    /// キューの並び・中身が変わった（端末の連携が書き出す）。
    @ObservationIgnored var queueChanged: [() -> Void] = []
    /// 位置・再生中か・曲・リピートが変わった。true は再生・一時停止・曲の遷移（すぐ書く）、false はシークなど（間引く）。
    @ObservationIgnored var cursorChanged: [(Bool) -> Void] = []
    /// 本人が再生を始めた・曲を選んだ（true は曲を選んだ、false は再生ボタン）。
    @ObservationIgnored var userStartedPlayback: [(Bool) -> Void] = []
    /// 本人が一時停止した。
    @ObservationIgnored var userPaused: [() -> Void] = []

    private(set) var queue: [Song] = []

    var current: Song? { didSet { if oldValue != current { cursorChanged.forEach { $0(true) } } } }
    var index = -1
    var isPlaying = false { didSet { if oldValue != isPlaying { onIsPlayingChanged() } } }
    var isLoading = false { didSet { if oldValue != isLoading, !isRemote { cursorChanged.forEach { $0(true) } } } }
    var position = 0.0
    var duration = 0.0
    var shuffle = false
    var repeatMode: RepeatMode = .off
    var volume: Double = 0.7 {
        didSet {
            engine.volume = volume
            AppServices.settings.volume = volume
            AppServices.settings.save()
        }
    }
    var like: LikeStatus?
    /// 今の曲がどれかのプレイリストに入っている（先読みで分かったとき。Spotify と同じく評価のボタンに✓を付ける）。
    var inPlaylist = false
    /// 今の曲に重ねられる映像（MV）。無い・まだ分からなければ nil。
    var video: MusicVideo?
    /// 画面の下に一時的に出す知らせ（出したら nil に戻す）。
    var message: String?
    var lyrics: LyricsService.Result?
    /// 今のキューの出どころ（プレイリスト・アルバムの ID。ラジオなら nil）。画面で「再生中」の印に使う。
    var sourceId: String?
    /// ほかの端末で鳴っている（下のバー・右の欄・キューは、その端末の再生を映し、操作はその端末への命令になる）。
    var isRemote = false
    /// 鳴らしている端末の名前（isRemote のとき）。
    var remoteDevice: String?

    /// 手元の音の再生位置（映像を合わせるため。ほかの端末を映している間は使わない）。
    var localPositionMs: Int64 { engine.hasSource ? Int64(engine.positionSeconds * 1000) : 0 }

    /// 手元で選んだキューを、ほかの端末で鳴らす（端末の連携が置く）。端末 ID を渡し、頼めたら true。
    @ObservationIgnored var playOnRemote: ((String) -> Bool)?
    /// ほかの端末への命令を送る（端末の連携が置く）。送れたら true。
    @ObservationIgnored var sendRemoteControl: ((ControlAction, Int64?) -> Bool)?

    @ObservationIgnored private var remote: RemoteSnapshot?
    @ObservationIgnored private var remoteQueueKey: String?
    private var remoteDeviceId: String? { isRemote ? remote?.deviceId : nil }

    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var saveWork: DispatchWorkItem?
    @ObservationIgnored private let store = QueueStore()
    @ObservationIgnored private var lastPositionSaveMs: Int64 = 0
    private static let positionSaveIntervalMs: Int64 = 15_000

    init() {
        let settings = AppServices.settings!
        volume = settings.volume
        // シャッフルはキューごとの状態（Android と同じく、新しい再生では切れる）。設定には持ち越さない
        repeatMode = RepeatMode(rawValue: min(max(settings.repeatMode, 0), 2)) ?? .off
        engine.volume = volume
        engine.onEnded = { [weak self] in self?.onEnded() }
        engine.onFailed = { [weak self] error in self?.onFailed(error) }
        engine.onPlayingChanged = { [weak self] playing in
            guard let self, !self.isRemote else { return }
            AppPaths.log("state playing=\(playing) pos=\(String(format: "%.1f", self.engine.positionSeconds))")
            if self.engine.hasSource { self.isPlaying = playing }
        }
        setUpRemoteCommands()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        // キューを次の起動に持ち越す（変わってから少し待ってまとめて書く。鳴っている間は位置のために時々書く）
        queueChanged.append { [weak self] in self?.scheduleSave() }
        cursorChanged.append { [weak self] _ in self?.scheduleSave() }
    }

    var clockMs: Int64 { Int64(Date().timeIntervalSince(started) * 1000) }

    // MARK: メディアキー・コントロールセンター

    /// メディアキー・コントロールセンターの再生・一時停止も、本人の操作として扱う（端末の連携に知らせる）。
    private func setUpRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { if let self, !self.isPlaying { self.togglePlay() } }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { if let self, self.isPlaying { self.togglePlay() } }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.togglePlay() }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.next() }
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.previous() }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            MainActor.assumeIsolated { self?.seek(e.positionTime) }
            return .success
        }
    }

    @ObservationIgnored private var shownArtworkFor: String?

    /// 「再生中」の表示（コントロールセンター・メディアキーの相手）。ほかの端末を映している間は、その端末の曲を出す。
    private func updateNowPlayingInfo() {
        let center = MPNowPlayingInfoCenter.default()
        guard let song = current else {
            center.nowPlayingInfo = nil
            center.playbackState = .stopped
            return
        }
        var info = center.nowPlayingInfo ?? [:]
        if info[MPMediaItemPropertyTitle] as? String != song.title || shownArtworkFor != song.videoId {
            info = [:]
            info[MPMediaItemPropertyTitle] = song.title
            info[MPMediaItemPropertyArtist] = song.artistsText
            if let album = song.album?.title { info[MPMediaItemPropertyAlbumTitle] = album }
            shownArtworkFor = song.videoId
            loadArtwork(song)
        }
        info[MPMediaItemPropertyPlaybackDuration] = duration
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        center.nowPlayingInfo = info
        center.playbackState = isPlaying ? .playing : .paused
    }

    private func loadArtwork(_ song: Song) {
        guard let url = Ui.resize(song.thumbnailUrl, 256), url.scheme == "https" else { return }
        Task {
            guard let (data, _) = try? await ImageLoader.session.data(from: url), let image = PlatformImage(data: data) else { return }
            guard self.current?.videoId == song.videoId else { return }
            var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }

    // MARK: キューの持ち越し

    private func scheduleSave() {
        saveWork?.cancel()
        let work = DispatchWorkItem { [weak self] in MainActor.assumeIsolated { self?.saveQueue() } }
        saveWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: work)
    }

    /// 今のキューを保存する（ほかの端末を映している間は、手元のキューが無いので書かない）。
    func saveQueue() {
        lastPositionSaveMs = clockMs
        if isRemote { return }
        guard let queue = exportQueue(), let cursor = currentCursor() else {
            if self.queue.isEmpty { store.delete() }
            return
        }
        store.save(SavedQueue(version: SavedQueue.currentVersion, obtainedAtEpochMs: AppServices.account.obtainedAtEpochMs,
                              items: queue.items.map { SavedQueueItem(song: SongRecord($0.song), itemId: $0.meta.itemId, origin: $0.meta.origin) },
                              currentItemId: cursor.currentItemId, positionMs: cursor.positionMs, repeatMode: repeatMode.rawValue, sourceId: sourceId,
                              radioSeed: queue.radioSeed, continuation: queue.continuation, radioPlaylistId: queue.radioPlaylistId, shuffleOrder: shuffleOrder))
    }

    /// 起動時に、前回のキューを再生を始めずに戻す（保存したときと今のログインが違えば消す）。
    func restoreSavedQueue() {
        guard let saved = store.load() else { return }
        if saved.obtainedAtEpochMs != AppServices.account.obtainedAtEpochMs {
            store.delete()
            return
        }
        guard let index = saved.items.firstIndex(where: { $0.itemId == saved.currentItemId }), queue.isEmpty else { return }
        sourceId = saved.sourceId
        radioSeed = saved.radioSeed
        radioContinuation = saved.continuation
        radioPlaylistId = saved.radioPlaylistId
        setShuffleOrder(saved.shuffleOrder)
        replaceAll(saved.items.map { ($0.song.song, QueueItemMeta(itemId: $0.itemId, origin: $0.origin)) })
        setRepeat(RepeatMode(rawValue: saved.repeatMode) ?? .off)
        self.index = index
        current = queue[index]
        position = Double(saved.positionMs) / 1000
        duration = Double(current?.durationSeconds ?? 0)
        let song = queue[index]
        restartSideLoads(song)
        AppPaths.log("queue restored items=\(queue.count) index=\(index)")
    }

    // MARK: キューの変更（項目の ID と出どころを同じ並びで持つ）

    private func insert(_ songs: [Song], at position: Int, origin: QueueOrigin) {
        queue.insert(contentsOf: songs, at: position)
        itemMeta.insert(contentsOf: songs.map { _ in QueueItemMeta(itemId: UUID().uuidString.lowercased(), origin: origin) }, at: position)
        queueChanged.forEach { $0() }
    }

    private func append(_ songs: [Song], origin: QueueOrigin) { insert(songs, at: queue.count, origin: origin) }

    private func removeQueueItem(at position: Int) {
        queue.remove(at: position)
        itemMeta.remove(at: position)
        queueChanged.forEach { $0() }
    }

    /// キューを丸ごと置き換える（項目の ID と出どころは渡したものを使う）。
    private func replaceAll(_ items: [(Song, QueueItemMeta)]) {
        queue = items.map(\.0)
        itemMeta = items.map(\.1)
        queueChanged.forEach { $0() }
    }

    private func replaceQueue(_ songs: [Song], origin: QueueOrigin) {
        replaceAll(songs.map { ($0, QueueItemMeta(itemId: UUID().uuidString.lowercased(), origin: origin)) })
    }

    // MARK: 再生の始め方

    /// 曲の一覧を start から再生する（プレイリスト・アルバム・検索の結果）。shuffleOverride が true なら混ぜて始める。
    func playList(_ songs: [Song], start: Int = 0, source: String? = nil, shuffleOverride: Bool? = nil) {
        guard !songs.isEmpty else { return }
        let target = remoteDeviceId
        leaveRemote()
        radioContinuation = nil
        radioPlaylistId = nil
        sourceId = source
        // 一覧の「シャッフル再生」のときだけシャッフルを入れて始める。それ以外は切る（Android と同じ）
        let shuffled = shuffleOverride == true
        var order = songs
        var first = min(max(start, 0), songs.count - 1)
        if shuffled {
            // 「シャッフル再生」: どの曲から始まってもよい
            order.shuffle()
            first = 0
        }
        radioSeed = nil
        replaceQueue(order, origin: .source)
        // 切ったときに戻す並び: 一覧の並び（同じ曲が2回あっても項目ごとに、一覧での位置を当てる）
        setShuffleOrder(shuffled ? rankBy(songs) : nil)
        startSelection(first, remoteTarget: target)
    }

    /// 曲を選んだ。ほかの端末で鳴っていたら、音を出さずにキューを用意して、その端末で鳴らしてもらう（Spotify と同じ）。
    /// 頼めなければ、この Mac で鳴らす。ほかの端末に頼んだら true。
    @discardableResult
    private func startSelection(_ index: Int, remoteTarget: String?) -> Bool {
        if let remoteTarget, index >= 0, index < queue.count {
            cancelLoad()
            engine.clear()
            self.index = index
            current = queue[index]
            position = 0
            duration = Double(current?.durationSeconds ?? 0)
            isPlaying = false
            isLoading = false
            if playOnRemote?(remoteTarget) == true { return true }
        }
        selectAt(index)
        userStartedPlayback.forEach { $0(true) }
        return false
    }

    /// 1曲から始めて、続きはラジオ（似た曲）にする（Spotify の「曲から再生」と同じ）。
    func playSong(_ song: Song) {
        let target = remoteDeviceId
        leaveRemote()
        sourceId = nil
        setShuffleOrder(nil)
        replaceQueue([song], origin: .source)
        // 曲のラジオの続きは、鳴らす端末が自分で取る（キューには種の曲だけを渡す）
        radioSeed = song.videoId
        radioPlaylistId = "RDAMVM" + song.videoId
        radioContinuation = nil
        if startSelection(0, remoteTarget: target) { return }
        Task {
            guard let page = (try? await AppServices.music.upNext(videoId: song.videoId, playlistId: radioPlaylistId))?.value,
                let first = queue.first, first.videoId == song.videoId
            else { return }
            radioContinuation = page.continuation
            radioPlaylistId = page.playlistId ?? radioPlaylistId
            append(page.songs.filter { $0.videoId != song.videoId }, origin: .suggestion)
        }
    }

    /// あとから読めた残りの曲を、まだキューに無い分だけ足す（長いプレイリストを最初のページから鳴らし始めたとき）。
    func appendFrom(_ songs: [Song]) {
        var known = Set(queue.map(\.videoId))
        var extra = songs.filter { known.insert($0.videoId).inserted }
        if var order = shuffleOrder {
            // シャッフル中に一覧の残りが読めた: 混ぜて足し、戻す並びには一覧の位置で加える
            extra.shuffle()
            let start = queue.count
            append(extra, origin: .source)
            var position: [String: Int] = [:]
            for (i, s) in songs.enumerated() where position[s.videoId] == nil { position[s.videoId] = i }
            for i in start..<queue.count { order[itemMeta[i].itemId] = position[queue[i].videoId] ?? Int.max }
            shuffleOrder = order
        } else {
            append(extra, origin: .source)
        }
    }

    func playNext(_ song: Song) {
        if queue.isEmpty {
            playSong(song)
            return
        }
        insert([song], at: index + 1, origin: .userAdded)
        message = L.t("「\(song.title)」を次に再生します", "\(song.title) will play next")
    }

    /// 一覧（プレイリスト・アルバム）を丸ごと、今の曲の次（next）かキューの最後に入れる（Android の「次に再生」「キューに追加」と同じ）。
    /// キューが空なら、その一覧を再生する。
    func queueAll(_ songs: [Song], next: Bool) {
        guard !songs.isEmpty else { return }
        if queue.isEmpty {
            playList(songs)
            return
        }
        if next { insert(songs, at: index + 1, origin: .userAdded) } else { insertUserItems(songs) }
        message = next ? L.t("\(songs.count) 曲を次に再生します", "\(L.songs(songs.count)) will play next")
            : L.t("\(songs.count) 曲をキューに追加しました", "Added \(L.songs(songs.count)) to queue")
    }

    func addToQueue(_ song: Song) {
        if queue.isEmpty {
            playSong(song)
            return
        }
        insertUserItems([song])
        message = L.t("「\(song.title)」をキューに追加しました", "Added \(song.title) to queue")
    }

    /// 「キューに追加」（Spotify・Android と同じ）。今の曲の直後に続く、自分で足した曲のかたまりの最後に入れる。
    private func insertUserItems(_ songs: [Song]) { insert(songs, at: userQueueEnd, origin: .userAdded) }

    /// 自分で足した曲（「次に再生」「キューに追加」）か。
    func isUserAdded(_ position: Int) -> Bool { position >= 0 && position < itemMeta.count && itemMeta[position].origin == .userAdded }

    /// 今の曲の直後に続く、自分で足した曲のかたまりの次の位置。
    var userQueueEnd: Int {
        var i = index + 1
        while i < queue.count && isUserAdded(i) { i += 1 }
        return i
    }

    /// 今の曲より後ろの、自分で足した曲をまとめて外す（Spotify の「キューを消去」）。
    func clearUserQueue() {
        if isRemote { return }
        var removed = 0
        var i = queue.count - 1
        while i > index {
            if isUserAdded(i) {
                removeQueueItem(at: i)
                removed += 1
            }
            i -= 1
        }
        if removed > 0 { message = L.t("キューから \(removed) 曲を外しました", "Removed \(L.songs(removed)) from the queue") }
    }

    /// キューの次の曲を並べ替える（今の曲より後ろだけ）。
    func moveUpcoming(_ from: Int, _ to: Int) {
        if isRemote || from <= index || to <= index || from >= queue.count || to >= queue.count || from == to { return }
        let song = queue.remove(at: from)
        let meta = itemMeta.remove(at: from)
        queue.insert(song, at: to)
        itemMeta.insert(meta, at: to)
        queueChanged.forEach { $0() }
    }

    func removeAt(_ position: Int) {
        if position <= index || position >= queue.count { return }
        removeQueueItem(at: position)
    }

    /// キューの曲を選んで鳴らす（本人の選択）。
    func playAt(_ position: Int) {
        guard position >= 0, position < queue.count else { return }
        let target = remoteDeviceId
        leaveRemote()
        startSelection(position, remoteTarget: target)
    }

    private func selectAt(_ position: Int, seekTo: Double = 0) {
        guard position >= 0, position < queue.count else { return }
        index = position
        current = queue[position]
        loadCurrent(seekTo: seekTo)
    }

    func togglePlay() {
        if isRemote {
            sendRemote(isPlaying ? .pause : .play)
            return
        }
        guard current != nil else { return }
        if !engine.hasSource {
            // 前回から持ち越したキューは、覚えている位置から鳴らす
            loadCurrent(seekTo: position)
            userStartedPlayback.forEach { $0(false) }
            return
        }
        if isPlaying {
            engine.pause()
            userPaused.forEach { $0() }
        } else {
            engine.play()
            userStartedPlayback.forEach { $0(false) }
        }
    }

    /// 本人の「次へ」（曲を選んだのと同じ扱い）。
    func next() {
        if isRemote {
            sendRemote(.next)
            return
        }
        if advance() { userStartedPlayback.forEach { $0(true) } }
    }

    /// 次の曲へ進む（曲の終わり・失敗の飛ばしにも使う）。進んだら true。
    @discardableResult
    private func advance() -> Bool {
        if queue.isEmpty { return false }
        if index + 1 < queue.count {
            selectAt(index + 1)
        } else if repeatMode == .all {
            selectAt(0)
        } else {
            return false
        }
        return true
    }

    func previous() {
        if isRemote {
            sendRemote(.previous)
            return
        }
        // 3秒より先なら曲の頭へ（Spotify と同じ）
        if position > 3 || index <= 0 {
            seek(0)
            return
        }
        selectAt(index - 1)
        userStartedPlayback.forEach { $0(true) }
    }

    @ObservationIgnored private var pendingRemoteSeek: Int64?
    @ObservationIgnored private var remoteSeekWork: DispatchWorkItem?

    func seek(_ seconds: Double) {
        if isRemote {
            // ドラッグ中は何度も呼ばれるので、動きが止まってから1回だけ送る
            let target = Int64(max(0, seconds) * 1000)
            if var r = remote {
                r.positionMs = target
                r.capturedAtMs = clockMs
                remote = r
            }
            position = max(0, seconds)
            pendingRemoteSeek = target
            holdOptimistic()
            remoteSeekWork?.cancel()
            let work = DispatchWorkItem { [weak self] in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    if self.isRemote, let p = self.pendingRemoteSeek { self.sendRemote(.seek, p) }
                    self.pendingRemoteSeek = nil
                }
            }
            remoteSeekWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: work)
            return
        }
        guard engine.hasSource else { return }
        engine.seek(seconds)
        position = seconds
        cursorChanged.forEach { $0(false) }
        updateNowPlayingInfo()
    }

    /// シャッフルの入り切り（Android と同じ）。入れると今の曲より後ろの並びを覚えて混ぜ、切ると残りを覚えた並びに戻す
    /// （シャッフル中に足した曲は今の順のまま最後に付く。曲を外しはしない）。
    func toggleShuffle() {
        // ほかの端末のキューは並べ替えられない（命令が無い）
        if isRemote || queue.isEmpty || current == nil || index < 0 { return }
        let upcoming = (index + 1..<queue.count).map { (song: queue[$0], meta: itemMeta[$0]) }
        // 自分で足した曲（キュー）はシャッフルせず、そのままの順で先頭に置く（Spotify・Android と同じ）
        var rest: [(song: Song, meta: QueueItemMeta)]
        var user: [(song: Song, meta: QueueItemMeta)]
        if let order = shuffleOrder {
            setShuffleOrder(nil)
            user = upcoming.filter { $0.meta.origin == .userAdded && order[$0.meta.itemId] == nil }
            let userIds = Set(user.map(\.meta.itemId))
            rest = upcoming.filter { !userIds.contains($0.meta.itemId) }.enumerated()
                .sorted { a, b in
                    let ra = order[a.element.meta.itemId] ?? Int.max, rb = order[b.element.meta.itemId] ?? Int.max
                    return ra != rb ? ra < rb : a.offset < b.offset
                }
                .map(\.element)
        } else {
            user = upcoming.filter { $0.meta.origin == .userAdded }
            rest = upcoming.filter { $0.meta.origin != .userAdded }
            var order: [String: Int] = [:]
            for (i, u) in rest.enumerated() { order[u.meta.itemId] = i }
            setShuffleOrder(order)
            rest.shuffle()
        }
        replaceUpcoming(user + rest)
    }

    /// 今の曲より後ろを並べ直す（項目の ID と出どころは保つ）。
    private func replaceUpcoming(_ rest: [(song: Song, meta: QueueItemMeta)]) {
        let head = (0...index).map { (queue[$0], itemMeta[$0]) }
        replaceAll(head + rest.map { ($0.song, $0.meta) })
    }

    private func setShuffleOrder(_ order: [String: Int]?) {
        shuffleOrder = order
        shuffle = order != nil
    }

    /// キューの項目ごとに、元の一覧での位置を当てる（同じ曲が何度も出るときは前から順に）。
    private func rankBy(_ songs: [Song]) -> [String: Int] {
        var slots: [String: [Int]] = [:]
        for (i, s) in songs.enumerated() { slots[s.videoId, default: []].append(i) }
        var rank: [String: Int] = [:]
        for i in 0..<queue.count {
            if var q = slots[queue[i].videoId], !q.isEmpty {
                rank[itemMeta[i].itemId] = q.removeFirst()
                slots[queue[i].videoId] = q
            } else {
                rank[itemMeta[i].itemId] = Int.max
            }
        }
        return rank
    }

    func cycleRepeat() {
        if isRemote { return }
        switch repeatMode {
        case .off: setRepeat(.all)
        case .all: setRepeat(.one)
        case .one: setRepeat(.off)
        }
    }

    private func setRepeat(_ value: RepeatMode) {
        guard value != repeatMode else { return }
        repeatMode = value
        AppServices.settings.repeatMode = value.rawValue
        AppServices.settings.save()
        cursorChanged.forEach { $0(true) }
    }

    /// 今の曲の高評価を切り替える（ライブラリの「高く評価した曲」に入る）。
    func toggleLike() {
        guard let song = current else { return }
        guard AppServices.client.isLoggedIn else {
            message = L.t("高く評価するにはログインしてください", "Log in to like songs")
            return
        }
        let liked = like != .like
        like = liked ? .like : .indifferent
        Task {
            let result = (try? await AppServices.music.setLiked(song.videoId, liked: liked)) ?? .failed(.network)
            if !result.isOk && current?.videoId == song.videoId {
                like = liked ? .indifferent : .like
                message = L.t("高評価を変更できませんでした", "Couldn't update your likes")
            } else {
                message = liked ? L.t("高く評価した曲に追加しました", "Added to Liked Songs") : L.t("高く評価した曲から削除しました", "Removed from Liked Songs")
                AppServices.library.likedChanged(song.videoId, liked)
            }
        }
    }

    // MARK: 端末の連携（Connect v2）の入口。本人の操作として知らせない

    /// 今のキューの書き出しに要る値。キューが空なら nil。
    func exportQueue() -> QueueExport? {
        guard !isRemote, !queue.isEmpty, index >= 0, index < queue.count else { return nil }
        return QueueExport(items: zip(queue, itemMeta).map { ($0, $1) }, currentItemId: itemMeta[index].itemId,
                           sourceId: radioSeed != nil ? nil : sourceId, radioSeed: radioSeed, continuation: radioContinuation, radioPlaylistId: radioPlaylistId)
    }

    /// 今の再生の位置など。キューが空なら nil。
    func currentCursor() -> PlaybackCursor? {
        guard !isRemote, current != nil, index >= 0, index < itemMeta.count else { return nil }
        let positionMs = engine.hasSource ? Int64(engine.positionSeconds * 1000) : 0
        return PlaybackCursor(currentItemId: itemMeta[index].itemId, positionMs: max(0, positionMs), playing: isPlaying || isLoading,
                              repeatMode: repeatMode, listenedMs: listenedNow(), historySent: statsSent)
    }

    private func listenedNow() -> Int64 { listenedMs + (playingSinceMs.map { clockMs - $0 } ?? 0) }

    private func onIsPlayingChanged() {
        updateNowPlayingInfo()
        // ほかの端末を映している間は、この端末で聴いた時間ではない
        if isRemote { return }
        let now = clockMs
        if isPlaying {
            if playingSinceMs == nil { playingSinceMs = now }
        } else if let since = playingSinceMs {
            listenedMs += now - since
            playingSinceMs = nil
        }
        cursorChanged.forEach { $0(true) }
    }

    /// 端末の連携が止める（本人の一時停止として知らせない）。
    func connectPause() {
        cancelLoad()
        isLoading = false
        engine.pause()
    }

    /// 端末の連携が戻す。
    func connectResume() {
        guard current != nil else { return }
        if !engine.hasSource { loadCurrent(seekTo: position) } else { engine.play() }
    }

    @ObservationIgnored private var seedListened: (itemId: String, listenedMs: Int64, historySent: Bool)?

    /// 他の端末から引き取ったキューで置き換え、今の曲の positionMs から鳴らす。
    @discardableResult
    func connectAdopt(_ queue: QueueExport, positionMs: Int64, repeatMode: RepeatMode, listened: Int64, historySent: Bool) -> Bool {
        guard let index = queue.items.firstIndex(where: { $0.meta.itemId == queue.currentItemId }) else { return false }
        leaveRemote()
        sourceId = queue.sourceId
        radioSeed = queue.radioSeed
        radioContinuation = queue.continuation
        radioPlaylistId = queue.radioPlaylistId
        // ほかの端末から受け取ったキューは、シャッフルを切った状態で始める（Android と同じ）
        setShuffleOrder(nil)
        replaceAll(queue.items.map { ($0.song, $0.meta) })
        setRepeat(repeatMode)
        seedListened = (queue.currentItemId, listened, historySent)
        selectAt(index, seekTo: Double(positionMs) / 1000)
        // 曲のラジオを種の曲だけで受け取った（送り手は続きを取る前に渡した）: 続きをこの Mac で取る
        if let seed = queue.radioSeed, radioContinuation == nil, self.queue.count - index <= 3 { Task { await extendFromSeed(seed) } }
        return true
    }

    /// ほかの端末で最後に鳴ったキューと位置に、手元を合わせておく（鳴らさない）。再生ボタンを押せばそのまま続きから鳴る。
    /// ほかの端末を映している間は、映すのをやめたときに戻すキュー（退避した分）を置き換える。手元で鳴っている・読み込み中なら何もしない。
    @discardableResult
    func connectStage(_ queue: QueueExport, positionMs: Int64, repeatMode: RepeatMode) -> Bool {
        guard let index = queue.items.firstIndex(where: { $0.meta.itemId == queue.currentItemId }) else { return false }
        if isRemote {
            localBackup = LocalQueueBackup(items: queue.items, index: index, sourceId: queue.sourceId, radioSeed: queue.radioSeed,
                                           continuation: queue.continuation, radioPlaylistId: queue.radioPlaylistId, shuffleOrder: nil,
                                           positionSeconds: Double(positionMs) / 1000, repeatMode: repeatMode)
            return true
        }
        if isPlaying || isLoading { return false }
        cancelLoad()
        engine.clear()
        sourceId = queue.sourceId
        radioSeed = queue.radioSeed
        radioContinuation = queue.continuation
        radioPlaylistId = queue.radioPlaylistId
        setShuffleOrder(nil)
        replaceAll(queue.items.map { ($0.song, $0.meta) })
        setRepeat(repeatMode)
        self.index = index
        current = self.queue[index]
        position = Double(positionMs) / 1000
        duration = Double(current?.durationSeconds ?? 0)
        restartSideLoads(self.queue[index])
        saveQueue()
        return true
    }

    /// 手元の同じ曲のまま、止まっている位置だけ合わせる（ほかの端末を映している間・鳴っている間は何もしない）。
    func connectStagePosition(_ positionMs: Int64) {
        if isRemote || isPlaying || isLoading || current == nil { return }
        if !engine.hasSource { position = Double(positionMs) / 1000 } else { engine.seek(Double(positionMs) / 1000) }
    }

    /// 曲のラジオの続きを、種の曲から取って足す（まだキューに無い曲だけ）。
    private func extendFromSeed(_ seed: String) async {
        if extending { return }
        extending = true
        defer { extending = false }
        guard let page = (try? await AppServices.music.upNext(videoId: seed, playlistId: radioPlaylistId ?? "RDAMVM" + seed))?.value, radioSeed == seed else { return }
        radioContinuation = page.continuation
        radioPlaylistId = page.playlistId ?? radioPlaylistId
        var known = Set(queue.map(\.videoId))
        append(page.songs.filter { known.insert($0.videoId).inserted }, origin: .suggestion)
    }

    // MARK: ほかの端末の再生を映す

    private func sendRemote(_ action: ControlAction, _ positionMs: Int64? = nil) {
        guard sendRemoteControl?(action, positionMs) == true else {
            message = L.t("ほかの端末に送れませんでした", "Couldn’t reach the other device")
            return
        }
        // 返事（相手の次のカーソル）を待つ間も、押した結果をすぐ見せる
        if var r = remote, action == .play || action == .pause {
            let playing = action == .play
            r.positionMs = Int64(r.positionNow(clockMs) * 1000)
            r.capturedAtMs = clockMs
            r.playing = playing
            remote = r
            isPlaying = playing
            holdOptimistic()
        }
    }

    /// 押した結果を見せている間（相手の新しいカーソルが届くまで、最大3秒）、届いた古いカーソルで表示を戻さない。
    @ObservationIgnored private var optimisticUntilMs: Int64 = 0
    @ObservationIgnored private var optimisticBaseVersion: Int64?

    private func holdOptimistic() {
        optimisticUntilMs = clockMs + 3_000
        optimisticBaseVersion = remote?.version
    }

    /// 映し始める前の手元のキュー（映すのをやめたら戻す。Android と同じく、手元のキューは相手のキューで消さない）。
    private struct LocalQueueBackup {
        var items: [(song: Song, meta: QueueItemMeta)]
        var index: Int
        var sourceId: String?
        var radioSeed: String?
        var continuation: String?
        var radioPlaylistId: String?
        var shuffleOrder: [String: Int]?
        var positionSeconds: Double = 0
        var repeatMode: RepeatMode?
    }

    @ObservationIgnored private var localBackup: LocalQueueBackup?

    /// ほかの端末の再生を映す（nil ならやめる）。端末の連携がメインスレッドで呼ぶ。
    func showRemote(_ next: RemoteSnapshot?) {
        guard let next else {
            if isRemote { leaveRemote(restoreLocal: true) }
            return
        }
        if !isRemote {
            // 手元の再生は止めて、キューを退避する（この端末は鳴らしていない）
            cancelLoad()
            engine.clear()
            isLoading = false
            localBackup = queue.isEmpty ? nil : LocalQueueBackup(items: zip(queue, itemMeta).map { ($0, $1) }, index: index, sourceId: sourceId,
                                                                 radioSeed: radioSeed, continuation: radioContinuation, radioPlaylistId: radioPlaylistId,
                                                                 shuffleOrder: shuffleOrder, positionSeconds: position)
            isRemote = true
        }
        // 押した直後は、相手の新しいカーソルが届くまで表示を保つ
        if clockMs < optimisticUntilMs && next.version == optimisticBaseVersion { return }
        optimisticUntilMs = 0
        remote = next
        remoteDevice = next.deviceLabel
        // キューは中身が変わったときだけ置き直す
        if next.queueKey != remoteQueueKey, let q = next.queue {
            remoteQueueKey = next.queueKey
            replaceAll(q.items.map { ($0.song, $0.meta) })
            sourceId = q.sourceId
        }
        if let i = itemMeta.firstIndex(where: { $0.itemId == next.currentItemId }), index != i || current == nil || current != queue[i] {
            index = i
            current = queue[i]
            like = nil
            video = nil
            lyrics = nil
            // 位置を先に新しい曲のものにしてから長さを替える（逆だと、シークバーの最大値が縮んだ瞬間に前の曲の終わりの位置が
            // 新しい曲の終わりに押し込まれ、本人のシークとして相手に送ってしまう）
            position = next.positionNow(clockMs)
            duration = Double(current?.durationSeconds ?? 0)
            restartSideLoads(queue[i])
        }
        isPlaying = next.playing
        // 相手のリピートを映すだけ（この端末の設定には書かない）
        repeatMode = next.repeatMode
        position = next.positionNow(clockMs)
        updateNowPlayingInfo()
    }

    /// 映すのをやめる。この端末で鳴らし始めたとき（曲を選んだ・引き取った）は、そのままキューを置き換える。
    /// 相手が見えなくなったときは、映す前の手元のキューに戻す。
    private func leaveRemote(restoreLocal: Bool = false) {
        guard isRemote else { return }
        isRemote = false
        remoteDevice = nil
        remote = nil
        remoteQueueKey = nil
        pendingRemoteSeek = nil
        remoteSeekWork?.cancel()
        isPlaying = false
        position = 0
        repeatMode = RepeatMode(rawValue: AppServices.settings.repeatMode) ?? .off
        let backup = localBackup
        localBackup = nil
        guard restoreLocal else { return }
        replaceAll([])
        current = nil
        index = -1
        guard let backup else {
            updateNowPlayingInfo()
            return
        }
        replaceAll(backup.items.map { ($0.song, $0.meta) })
        sourceId = backup.sourceId
        radioSeed = backup.radioSeed
        radioContinuation = backup.continuation
        radioPlaylistId = backup.radioPlaylistId
        setShuffleOrder(backup.shuffleOrder)
        if let r = backup.repeatMode { setRepeat(r) }
        if backup.index >= 0 && backup.index < queue.count {
            index = backup.index
            current = queue[backup.index]
            duration = Double(current?.durationSeconds ?? 0)
            position = backup.positionSeconds
        }
        updateNowPlayingInfo()
        saveQueue()
    }

    // MARK: ほかの端末からの命令に従う（この端末が鳴らしている）

    func applyControl(_ action: ControlAction, positionMs: Int64?) -> Bool {
        if isRemote || current == nil { return false }
        switch action {
        case .play: connectResume()
        case .pause: engine.pause()
        case .next: advance()
        case .previous:
            if position > 3 || index <= 0 { seek(0) } else { selectAt(index - 1) }
        case .seek: seek(Double(positionMs ?? 0) / 1000)
        default: return false
        }
        return true
    }

    // MARK: 読み込みと再生

    private func cancelLoad() {
        loadTask?.cancel()
        loadToken += 1
    }

    /// 曲が変わったときの、評価・映像・歌詞の読み込み（鳴らさないとき）。
    private func restartSideLoads(_ song: Song) {
        cancelLoad()
        let token = loadToken
        loadTask = Task {
            async let info: Void = loadSongInfo(song, token: token)
            async let lyr: Void = loadLyrics(song, token: token)
            _ = await (info, lyr)
        }
    }

    private func loadCurrent(seekTo: Double = 0) {
        cancelLoad()
        let token = loadToken
        guard let song = current else { return }
        isLoading = true
        like = nil
        video = nil
        lyrics = nil
        position = seekTo
        duration = Double(song.durationSeconds ?? 0)
        retried = false
        // 聴いた時間は曲ごと（引き取った曲は相手の値から続ける。同じ曲の読み直しでは数え直さない）
        let itemId = index >= 0 && index < itemMeta.count ? itemMeta[index].itemId : nil
        if let seed = seedListened, seed.itemId == itemId {
            listenedMs = seed.listenedMs
            statsSent = seed.historySent
            listenedItemId = itemId
        } else if itemId != listenedItemId {
            listenedMs = 0
            statsSent = false
            listenedItemId = itemId
        }
        seedListened = nil
        playingSinceMs = isPlaying ? clockMs : nil
        engine.pause()
        updateNowPlayingInfo()
        loadTask = Task {
            guard let stream = await resolve(song.videoId) else {
                if token == loadToken { isLoading = false }
                return
            }
            guard token == loadToken, !Task.isCancelled else { return }
            if !(await startStream(song, stream, seekTo: seekTo, token: token)) {
                guard token == loadToken else { return }
                await recover(song)
                return
            }
            consecutiveFailures = 0
            Task { await loadSongInfo(song, token: token) }
            Task { await loadLyrics(song, token: token) }
            prefetch()
            extendRadioIfNeeded()
        }
    }

    private func resolve(_ videoId: String) async -> ResolvedStream? {
        if let cached = prefetched.removeValue(forKey: videoId), AppServices.resolver.isFresh(cached, marginMs: PlayerService.freshMarginMs),
            cached.generation == AppServices.client.generation
        {
            return cached
        }
        guard let result = try? await AppServices.resolver.resolve(videoId: videoId) else { return nil }
        if let stream = result.stream { return stream }
        AppPaths.log("resolve failed reason=\(String(describing: result.failure))")
        message = switch result.failure {
        case .notLoggedIn: L.t("この曲を再生するにはログインが必要です", "Log in to play this song")
        case .unplayable: L.t("この曲は再生できません", "This song isn't available")
        case .network: L.t("通信できませんでした", "Couldn't connect")
        default: L.t("再生の準備ができませんでした（\(String(describing: result.failure))）", "Couldn't prepare playback (\(String(describing: result.failure)))")
        }
        return nil
    }

    private func startStream(_ song: Song, _ stream: ResolvedStream, seekTo: Double, token: Int) async -> Bool {
        currentStream = stream
        cpn = PlaybackStats.newCpn()
        let bytes: RangeStream
        do {
            bytes = try await RangeStream.open(session: AppServices.mediaSession, stream: stream)
        } catch {
            AppPaths.log("stream open failed type=\(type(of: error))")
            return false
        }
        guard current?.videoId == song.videoId, token == loadToken else { return false }
        guard await engine.load(bytes, mimeType: stream.mimeType, key: "\(song.videoId)/\(stream.itag)") else { return false }
        guard current?.videoId == song.videoId, token == loadToken else { return false }
        if seekTo > 1 { engine.seek(seekTo) }
        engine.play()
        isPlaying = true
        isLoading = false
        updateNowPlayingInfo()
        AppPaths.log("play itag=\(stream.itag) queue=\(queue.count) index=\(index)")
        return true
    }

    private func onEnded() {
        guard !endHandled else { return }
        endHandled = true
        // 曲の途中で「終わった」と来たら、読み込みの失敗とみなす（次々に飛ばさない）
        let known = Double(current?.durationSeconds ?? 0)
        if known > 10 && position < known - 5 {
            AppPaths.log("ended early position=\(String(format: "%.1f", position)) duration=\(known)")
            if let song = current { Task { await recover(song) } }
            return
        }
        if repeatMode == .one {
            engine.seek(0)
            engine.play()
            statsSent = false
            listenedMs = 0
            playingSinceMs = clockMs
            cpn = PlaybackStats.newCpn()
            return
        }
        if index + 1 < queue.count || repeatMode == .all {
            advance()
        } else {
            isPlaying = false
        }
    }

    private func onFailed(_ error: String) {
        AppPaths.log("media failed error=\(error) retried=\(retried)")
        if let song = current { Task { await recover(song) } }
    }

    /// 鳴らせなかったとき。1回だけ解読の準備から取り直してやり直し、だめなら次の曲へ。続けて失敗したら止める
    /// （ログインしていないと、googlevideo は先頭の一部しか返さないので、どの曲も鳴らない）。
    private func recover(_ song: Song) async {
        guard AppServices.client.isLoggedIn else {
            isLoading = false
            message = L.t("曲を再生するにはログインしてください（右上のアカウントから）", "Log in to play songs (from the account button at the top right)")
            return
        }
        if !retried {
            retried = true
            AppServices.resolver.onStreamRejected()
            prefetched.removeValue(forKey: song.videoId)
            let at = position
            let token = loadToken
            if let stream = await resolve(song.videoId), current?.videoId == song.videoId, await startStream(song, stream, seekTo: at, token: token) {
                consecutiveFailures = 0
                return
            }
        }
        isLoading = false
        consecutiveFailures += 1
        if consecutiveFailures >= PlayerService.maxConsecutiveFailures {
            message = L.t("続けて再生できなかったので、止めました", "Stopped after several songs failed to play")
            consecutiveFailures = 0
            return
        }
        message = L.t("再生できなかったので、次の曲に進みます", "Couldn't play this song, skipping to the next one")
        if index + 1 < queue.count { advance() }
    }

    private func tick() {
        if isRemote {
            if let r = remote { position = r.positionNow(clockMs) }
            return
        }
        if isPlaying && clockMs - lastPositionSaveMs > PlayerService.positionSaveIntervalMs { saveQueue() }
        guard engine.hasSource, !isLoading else { return }
        position = engine.positionSeconds
        // 断片の MP4 では AVPlayer の読む長さが実際より長いことがある。曲の長さが分かっていればそちらを使う
        if current?.durationSeconds == nil, engine.durationSeconds > 0 { duration = engine.durationSeconds }
        // 長さを長く読んだときは AVPlayer が終わりを知らせないので、本当の長さ（形式の長さ、無ければ曲の長さ）に着いたら終わりとする
        let realEnd = currentStream?.dash.map { Double($0.durationMs) / 1000 } ?? Double(current?.durationSeconds ?? 0)
        if position < (realEnd > 10 ? realEnd : engine.durationSeconds) - 1 { endHandled = false }
        if isPlaying, !endHandled, realEnd > 10, position >= realEnd - 0.2 { onEnded() }
        if !statsSent, isPlaying, position >= PlayerService.statsAfterSeconds, let stream = currentStream, AppServices.settings.sendHistory,
            AppServices.client.isLoggedIn
        {
            statsSent = true
            let cpn = self.cpn, at = position
            Task {
                let status = await AppServices.stats.report(stream: stream, cpn: cpn, positionSeconds: at)
                AppPaths.log("stats status=\(status.map(String.init) ?? "skipped")")
            }
        }
    }

    /// 曲の評価（ログイン中だけ）と、重ねられる映像。
    private func loadSongInfo(_ song: Song, token: Int) async {
        guard let info = (try? await AppServices.music.songInfo(song.videoId))?.value, current?.videoId == song.videoId else { return }
        if AppServices.client.isLoggedIn { like = info.like }
        video = info.video
    }

    /// 評価を読み直す（読めなかったときの↻）。
    func reloadLike() {
        guard let song = current else { return }
        Task { await loadSongInfo(song, token: loadToken) }
    }

    /// 歌詞を読み直す（読み込みに失敗したときの「もう一度」。失敗は覚えていないので取り直す）。
    func reloadLyrics() {
        guard let song = current else { return }
        lyrics = nil
        Task { await loadLyrics(song, token: loadToken) }
    }

    private func loadLyrics(_ song: Song, token: Int) async {
        guard let result = try? await AppServices.lyrics.lyrics(song: song) else { return }
        if current?.videoId == song.videoId { lyrics = result }
    }

    /// 次の曲の URL を先に解決しておく（曲の切り替えを速くする）。
    private func prefetch() {
        guard index + 1 < queue.count else { return }
        let next = queue[index + 1]
        if prefetched[next.videoId] != nil { return }
        Task {
            guard let stream = (try? await AppServices.resolver.resolve(videoId: next.videoId))?.stream else { return }
            prefetched[next.videoId] = stream
            prefetchOrder.append(next.videoId)
            while prefetched.count > 4, !prefetchOrder.isEmpty { prefetched.removeValue(forKey: prefetchOrder.removeFirst()) }
        }
    }

    /// ラジオの残りが少なくなったら続きを足す。
    private func extendRadioIfNeeded() {
        if extending || queue.count - index > 3 { return }
        // ほかの端末から引き取ったラジオは続きの印を持たないので、種の曲から取り直す
        guard let continuation = radioContinuation else {
            if let seed = radioSeed { Task { await extendFromSeed(seed) } }
            return
        }
        extending = true
        Task {
            defer { extending = false }
            guard let page = (try? await AppServices.music.upNext(videoId: nil, playlistId: radioPlaylistId, continuation: continuation))?.value else { return }
            radioContinuation = page.continuation
            var known = Set(queue.map(\.videoId))
            append(page.songs.filter { known.insert($0.videoId).inserted }, origin: .suggestion)
        }
    }

    /// ログアウトのとき。再生を止めてキューを空にする（別のアカウントの URL を使い続けない）。
    func stop() {
        // ログアウト・別のアカウントでのログイン: 保存したキューも消す
        saveWork?.cancel()
        store.delete()
        leaveRemote()
        cancelLoad()
        engine.clear()
        prefetched.removeAll()
        prefetchOrder.removeAll()
        replaceAll([])
        current = nil
        index = -1
        isPlaying = false
        like = nil
        video = nil
        lyrics = nil
        updateNowPlayingInfo()
    }
}

extension PlayerService {
    /// アーティストのページの再生ボタンと同じ流し方（人気の曲から始め、「すべての曲」の残りを読めたら足す。Android と同じ）。
    func playArtist(_ browseId: String, _ topSongs: [Song], start: Int, shuffle: Bool?, allSongs: Task<ApiResult<PlaylistPage>?, Never>?) async {
        guard !topSongs.isEmpty else { return }
        playList(topSongs, start: start, source: browseId, shuffleOverride: shuffle)
        guard let allSongs else { return }
        let all = await allSongs.value
        AppPaths.log("artist all songs ok=\(all?.isOk ?? false) songs=\(all?.value?.songs.count ?? 0)")
        // 読んでいる間にほかのものを流し始めていたら足さない
        if let page = all?.value, sourceId == browseId { appendFrom(page.songs) }
    }
}
