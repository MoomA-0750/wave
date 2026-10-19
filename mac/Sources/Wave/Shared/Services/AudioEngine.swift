import AVFoundation
import Foundation
import UniformTypeIdentifiers
import WaveCore

/// AVPlayer に、範囲取得で読むバイトを渡す（署名付き URL は AVPlayer に渡さない。Windows の DASH の DownloadRequested と同じ考え）。
/// 仮の URL（wave-media://…）を AVURLAsset に渡し、求められた範囲をアプリが RangeStream で取って返す。
final class RangeResourceLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    static let scheme = "wave-media"

    private let bytes: RangeStream
    private let contentType: String
    private let lock = NSLock()
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]
    /// 拒否された（403 など）。再生が止まったときに、解決し直すかを決めるのに使う。
    private(set) var rejected = false

    init(bytes: RangeStream, mimeType: String) {
        self.bytes = bytes
        let base = mimeType.split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces) } ?? mimeType
        self.contentType = UTType(mimeType: base)?.identifier ?? (base.hasPrefix("video") ? UTType.mpeg4Movie.identifier : UTType.mpeg4Audio.identifier)
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        if let info = loadingRequest.contentInformationRequest {
            info.contentType = contentType
            info.contentLength = bytes.length
            info.isByteRangeAccessSupported = true
        }
        guard let data = loadingRequest.dataRequest else {
            loadingRequest.finishLoading()
            return true
        }
        let start = data.currentOffset != 0 ? data.currentOffset : data.requestedOffset
        let end = data.requestsAllDataToEndOfResource ? bytes.length - 1 : min(bytes.length - 1, data.requestedOffset + Int64(data.requestedLength) - 1)
        let key = ObjectIdentifier(loadingRequest)
        let task = Task { [bytes] in
            var offset = start
            do {
                // 区切りごとに渡す（全部そろうのを待たない）
                while offset <= end, !Task.isCancelled {
                    let chunkEnd = min(end, offset + Int64(RangeStream.chunkSize) - 1)
                    let chunk = try await bytes.readRange(start: offset, end: chunkEnd)
                    if chunk.isEmpty { break }
                    data.respond(with: chunk)
                    offset += Int64(chunk.count)
                }
                if !Task.isCancelled { loadingRequest.finishLoading() }
            } catch {
                if error is StreamRejectedError { self.markRejected() }
                if !Task.isCancelled { loadingRequest.finishLoading(with: error) }
            }
            self.remove(key)
        }
        lock.withLock { tasks[key] = task }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        let key = ObjectIdentifier(loadingRequest)
        lock.withLock { tasks.removeValue(forKey: key) }?.cancel()
    }

    private func remove(_ key: ObjectIdentifier) { lock.withLock { _ = tasks.removeValue(forKey: key) } }
    private func markRejected() { lock.withLock { rejected = true } }

    func cancelAll() {
        let all = lock.withLock { () -> [Task<Void, Never>] in
            let list = Array(tasks.values)
            tasks.removeAll()
            return list
        }
        all.forEach { $0.cancel() }
    }
}

/// 再生の部品（AVPlayer）。曲の切り替え・終わり・失敗を知らせる。
@MainActor
final class AudioEngine {
    let player = AVPlayer()
    private var loader: RangeResourceLoader?
    /// 中の受け口に置いた曲（次の曲を読むとき・止めるときに外す）。
    private var servedToken: String?
    private let loaderQueue = DispatchQueue(label: "wave.media.loader")
    private var observers: [NSObjectProtocol] = []
    private var statusObservation: NSKeyValueObservation?
    private var rateObservation: NSKeyValueObservation?

    var onEnded: (() -> Void)?
    var onFailed: ((String) -> Void)?
    var onPlayingChanged: ((Bool) -> Void)?

    init() {
        player.automaticallyWaitsToMinimizeStalling = true
        rateObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            let playing = p.timeControlStatus != .paused
            // 通知は AVPlayer が中のロックを持ったまま（play() の中など）送ってくる。ここで位置（currentTime）を読むと
            // ライブの HLS で止まったままになった（メインスレッドが固まる）ので、AVPlayer の処理が終わってから回す
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.onPlayingChanged?(playing) } }
        }
    }

    var hasSource: Bool { player.currentItem != nil }

    var positionSeconds: Double {
        let t = player.currentTime().seconds
        return t.isFinite ? max(0, t) : 0
    }

    var durationSeconds: Double {
        guard let d = player.currentItem?.duration.seconds, d.isFinite else { return 0 }
        return d
    }

    var volume: Double {
        get { Double(player.volume) }
        set { player.volume = Float(newValue) }
    }

    /// バイトを読む元を渡して、鳴らす準備をする。読み始めに失敗したら false。
    /// 断片化 MP4 で目次（sidx）が読めれば、HLS の一覧にして、この端末の中の受け口から区切りごとに読ませる
    /// （MP4 のまま渡すと、AVPlayer は長さを知るためにファイルを最後まで読んでから鳴り始める）。
    func load(_ bytes: RangeStream, mimeType: String, key: String, dash: DashInfo? = nil) async -> Bool {
        clear()
        if let playlist = await Self.playlist(bytes, mimeType: mimeType, dash: dash),
           let served = await LocalMediaServer.shared.register(bytes: bytes, playlist: playlist, contentType: mimeType.hasPrefix("video") ? "video/mp4" : "audio/mp4")
        {
            servedToken = served.token
            let item = AVPlayerItem(asset: AVURLAsset(url: served.url))
            item.preferredForwardBufferDuration = 30
            if await start(item) { return true }
            // 次の曲に替わった（読み込みを追い越された）ときは、そのまま終える
            guard item.status == .failed, player.currentItem === item else { return false }
            // 中の受け口から読めなかった（OS が 127.0.0.1 へのつなぎを許さないなど）: 前の読み方（丸ごと読む）で鳴らす
            AppPaths.log("media hls failed; reading whole file")
            clear()
        }
        let loader = RangeResourceLoader(bytes: bytes, mimeType: mimeType)
        self.loader = loader
        let url = URL(string: "\(RangeResourceLoader.scheme)://media/\(key)")!
        let asset = AVURLAsset(url: url)
        asset.resourceLoader.setDelegate(loader, queue: loaderQueue)
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 30
        return await start(item)
    }

    /// 目次を取って HLS の一覧を作る。MP4 で無い・目次が読めないときは nil（そのまま MP4 として渡す）。
    private static func playlist(_ bytes: RangeStream, mimeType: String, dash: DashInfo?) async -> String? {
        guard mimeType.hasPrefix("audio/mp4") || mimeType.hasPrefix("video/mp4"), let dash, dash.indexEnd > dash.indexStart else { return nil }
        guard let data = try? await bytes.readRange(start: dash.indexStart, end: dash.indexEnd),
              let index = SegmentIndex.parse(data, indexStart: dash.indexStart) else {
            AppPaths.log("media index unreadable; reading whole file")
            return nil
        }
        return HlsPlaylist.build(index, initEnd: dash.initEnd, mediaUrl: LocalMediaServer.mediaName)
    }

    /// ライブ配信の HLS の媒体プレイリストを鳴らす準備をする（AVPlayer が HLS をそのまま読む。範囲取得の経路は通さない）。
    /// URL は署名を含むので、ログに出さない。読み始めに失敗したら false。
    func loadLive(_ url: URL) async -> Bool {
        clear()
        // 画質は LiveClient が選んだ1本（媒体プレイリスト）に決まっている
        return await start(AVPlayerItem(url: url))
    }

    /// RSS のエピソードの音声（https）をそのまま鳴らす準備をする（ADR-0012。YouTube の解決・範囲取得の経路は通さない）。
    /// cookie は渡さない。URL はログに出さない。読み始めに失敗したら false。
    func loadExternal(_ url: URL, userAgent: String) async -> Bool {
        clear()
        let asset = AVURLAsset(url: url, options: [AVURLAssetHTTPUserAgentKey: userAgent, AVURLAssetHTTPCookiesKey: [HTTPCookie]()])
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 30
        return await start(item)
    }

    private func start(_ item: AVPlayerItem) async -> Bool {
        observers.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.onEnded?() }
        })
        observers.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] n in
            let error = (n.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError).map { "\($0.domain)/\($0.code)" } ?? "unknown"
            MainActor.assumeIsolated { self?.onFailed?(error) }
        })
        player.replaceCurrentItem(with: item)
        // 読み込めるまで待つ（失敗はここで分かる）
        let ready: Bool = await withCheckedContinuation { continuation in
            var resumed = false
            statusObservation = item.observe(\.status, options: [.initial, .new]) { item, _ in
                guard !resumed else { return }
                switch item.status {
                case .readyToPlay:
                    resumed = true
                    continuation.resume(returning: true)
                case .failed:
                    resumed = true
                    continuation.resume(returning: false)
                default:
                    break
                }
            }
        }
        statusObservation = nil
        if !ready {
            let error = (player.currentItem?.error as NSError?).map { "\($0.domain)/\($0.code)" } ?? "unknown"
            AppPaths.log("media failed to open error=\(error)")
        }
        return ready && player.currentItem === item
    }

    var rejected: Bool { loader?.rejected ?? false }

    /// 開発用の命令で状態を記録する（URL は出さない）。
    var debugState: String {
        let item = player.currentItem
        let status = switch item?.status { case .readyToPlay: "ready"; case .failed: "failed"; case .unknown: "unknown"; default: "none" }
        let control = switch player.timeControlStatus { case .playing: "playing"; case .paused: "paused"; default: "waiting" }
        let loaded = item?.loadedTimeRanges.last.map { String(format: "%.1f", CMTimeRangeGetEnd($0.timeRangeValue).seconds) } ?? "-"
        return "item=\(status) control=\(control) rate=\(player.rate) time=\(String(format: "%.1f", positionSeconds)) loadedEnd=\(loaded) " +
            "durationIndefinite=\(item?.duration.isIndefinite ?? false) error=\((item?.error as NSError?).map { "\($0.domain)/\($0.code)" } ?? "-")"
    }

    /// 最後に頼まれたのが再生か（読み込みに失敗してやり直すとき、止めていた曲を鳴らし出さないため）。
    private(set) var wantsPlay = false

    func play() {
        wantsPlay = true
        player.play()
    }

    func pause() {
        wantsPlay = false
        player.pause()
    }

    func seek(_ seconds: Double) {
        player.seek(to: CMTime(seconds: max(0, seconds), preferredTimescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    func clear() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        statusObservation = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        loader?.cancelAll()
        loader = nil
        if let servedToken { LocalMediaServer.shared.unregister(servedToken) }
        servedToken = nil
    }
}
