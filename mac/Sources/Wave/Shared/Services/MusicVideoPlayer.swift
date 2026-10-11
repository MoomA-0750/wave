import AVFoundation
import Foundation
import Observation
import WaveCore

/// 曲に重ねる映像（Android の MusicVideos と同じ考え）。音は PlayerService の再生器が鳴らし続け、映像はこの音の無い再生器で出す。
/// 位置は VideoSync で音に合わせる。映像の読み込みに失敗しても、音の再生には関わらない。
@MainActor @Observable
final class MusicVideoPlayer {
    static let shared = MusicVideoPlayer()

    enum State { case idle, loading, ready, failed }

    @ObservationIgnored let player: AVPlayer = {
        let p = AVPlayer()
        p.isMuted = true
        p.automaticallyWaitsToMinimizeStalling = false
        return p
    }()

    /// 映像に当たる部分があり、映っているか（MV に無い区間では隠す）。
    private(set) var showing = false
    private(set) var state: State = .idle
    private(set) var note = ""

    @ObservationIgnored private var loader: RangeResourceLoader?
    @ObservationIgnored private let loaderQueue = DispatchQueue(label: "wave.video.loader")
    @ObservationIgnored private var loadedVideoId: String?
    @ObservationIgnored private var active: MusicVideo?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var timer: Timer?

    /// 映す映像を決める（nil なら外す）。
    func setActive(_ video: MusicVideo?) {
        guard video != active else { return }
        active = video
        guard let video else {
            stop()
            return
        }
        load(video)
    }

    /// 映像を読み込む（同じ映像なら何もしない）。
    func load(_ video: MusicVideo?) {
        guard let video else { return }
        if loadedVideoId == video.videoId, player.currentItem != nil, state == .ready { return }
        stop()
        active = video
        generation += 1
        let mine = generation
        state = .loading
        note = L.t("動画を読み込んでいます…", "Loading video…")
        Task {
            guard let resolved = try? await AppServices.resolver.resolve(videoId: video.videoId, kind: .video), let stream = resolved.stream, mine == generation else {
                if mine == generation { fail("resolve") }
                return
            }
            guard let bytes = try? await RangeStream.open(session: AppServices.mediaSession, stream: stream), mine == generation else {
                if mine == generation { fail("open") }
                return
            }
            let loader = RangeResourceLoader(bytes: bytes, mimeType: stream.mimeType)
            self.loader = loader
            let asset = AVURLAsset(url: URL(string: "\(RangeResourceLoader.scheme)://video/\(video.videoId)/\(stream.itag)")!)
            asset.resourceLoader.setDelegate(loader, queue: loaderQueue)
            let item = AVPlayerItem(asset: asset)
            player.replaceCurrentItem(with: item)
            let ready = await waitReady(item)
            guard mine == generation else { return }
            guard ready else {
                fail("item")
                return
            }
            loadedVideoId = video.videoId
            state = .ready
            AppPaths.log("video itag=\(stream.itag)")
            startTimer()
        }
    }

    private func fail(_ stage: String) {
        AppPaths.log("video load failed stage=\(stage)")
        state = .failed
        note = L.t("動画を読み込めませんでした", "Couldn't load the video")
        showing = false
    }

    private func waitReady(_ item: AVPlayerItem) async -> Bool {
        for _ in 0..<300 {
            switch item.status {
            case .readyToPlay: return true
            case .failed: return false
            default: try? await Task.sleep(for: .milliseconds(100))
            }
        }
        return false
    }

    private func startTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.sync() }
        }
    }

    /// 音の位置に合わせる（ほかの端末を映している間は、その再生の位置の見積もりに合わせる）。
    private func sync() {
        guard let video = active, state == .ready, player.currentItem != nil else { return }
        let p = AppServices.player!
        let audioMs = p.isRemote ? Int64(p.position * 1000) : p.localPositionMs
        let videoMs = Int64(max(0, player.currentTime().seconds.isFinite ? player.currentTime().seconds : 0) * 1000)
        let ready = player.currentItem?.isPlaybackLikelyToKeepUp ?? false || player.timeControlStatus == .playing
        switch VideoSync.decide(targetMs: video.videoPositionOf(audioMs), videoMs: videoMs, playing: p.isPlaying, videoReady: ready) {
        case .hide:
            if player.timeControlStatus != .paused { player.pause() }
            // MV に無い区間（前奏など）は、黒地にその旨を出す（Android と同じ）
            showing = false
            note = L.t("この部分には動画がありません", "There's no video for this part")
            return
        case .seek(let toMs, let play):
            player.seek(to: CMTime(value: toMs, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
            run(play, rate: 1)
        case .run(let speed, let play):
            run(play, rate: Float(speed))
        }
        showing = true
    }

    private func run(_ play: Bool, rate: Float) {
        if play {
            if player.timeControlStatus == .paused || abs(player.rate - rate) > 0.005 { player.rate = rate }
        } else if player.timeControlStatus != .paused {
            player.pause()
        }
    }

    /// 映像を外す（曲が変わった・曲の表示に戻した）。
    func stop() {
        generation += 1
        timer?.invalidate()
        timer = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        loader?.cancelAll()
        loader = nil
        loadedVideoId = nil
        showing = false
        state = .idle
    }
}
