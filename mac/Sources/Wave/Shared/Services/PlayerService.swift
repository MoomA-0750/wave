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
    /// この端末のアプリの中の音量（0〜100）。PC だけが載せる。スマホは載せない（nil。ほかの端末から音量を変えられないことの印。Spotify と同じ）。
    var volumePercent: Int?
    /// シャッフル中か（ほかの端末はこれを見てシャッフル・繰り返しの操作を出す）。
    var shuffle = false
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
    /// 今の曲がライブ配信（位置も長さも無い）。
    var isLive = false
    var version: Int64 = 0
    /// その端末のアプリの中の音量（0〜100）。スマホ・前の版の端末は付けない（nil。そのときはほかの端末から音量を変えられない）。
    var volumePercent: Int?
    /// その端末のシャッフル。前の版の端末は付けない（nil。そのときはほかの端末からシャッフル・繰り返しを変えられない）。
    var shuffle: Bool?

    /// 今の位置（再生中なら、取ってからの経過を足す。曲の長さで止める）。ライブ配信には位置が無いので 0（経過で伸ばさない）。
    func positionNow(_ nowMs: Int64) -> Double {
        if isLive { return 0 }
        var ms = playing ? positionMs + max(0, nowMs - capturedAtMs) : positionMs
        if let d = durationSeconds, d > 0 { ms = min(ms, Int64(d) * 1000) }
        return Double(ms) / 1000
    }
}

/// 再生キューと再生（AVPlayer）。メディアキー・コントロールセンターの「再生中」にも曲名と画像を出す。
/// 署名付きの URL は再生の部品に渡さず、ログ・画面に出さない。Windows 版の PlayerService と同じ振る舞い。
@MainActor @Observable
final class PlayerService {
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
    @ObservationIgnored fileprivate(set) var currentStream: ResolvedStream?
    @ObservationIgnored private var cpn = ""
    @ObservationIgnored private var statsSent = false
    /// 曲の長さに着いて、終わりの扱いをもう済ませたか（AVPlayer の終わりの知らせと二重にしない）。
    @ObservationIgnored private var endHandled = false
    @ObservationIgnored private var retried = false
    @ObservationIgnored private var radioContinuation: String?
    @ObservationIgnored private var radioPlaylistId: String?
    @ObservationIgnored private var extending = false
    @ObservationIgnored private var consecutiveFailures = 0
    /// 解決し直しても再生できなかった曲（2分の間は解決し直さずに飛ばす。Android と同じ）。
    @ObservationIgnored private var rejected = RejectionCooldown()
    @ObservationIgnored private var radioSeed: String?
    /// 長い一覧を最初のページから鳴らし始め、残りをまだ読んでいる（appendFrom で足すまで、ラジオへ切り替えない）。
    @ObservationIgnored private var restPending = false
    /// 最後の曲を流し終えて止まっている（あとから曲が足されたら、足した先頭から続ける。Android の appendItems と同じ）。
    @ObservationIgnored private var endedAtLast = false

    // 端末の連携（Connect v2）: キューの項目ごとの ID と出どころ（queue と同じ並び）、今の曲を聴いた時間
    @ObservationIgnored private(set) var itemMeta: [QueueItemMeta] = []
    @ObservationIgnored private let started = Date()
    /// 今の曲を聴いた時間（再生中の区間だけを足す。シークでは増えない）。再生履歴を送るときと、連携の listenedMs に使う
    @ObservationIgnored private var listen = ListenCounter()
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
    /// 音量のつまみ。ほかの端末を映している間はその端末のアプリの中の音量を映し、動かすとその端末へ送る（Spotify と同じ）。
    /// 映している端末がスマホ（音量を知らせない）なら動かせない（remoteVolumeKnown が false。つまみは灰色）。
    /// 手元で鳴らしている間は、Mac ではアプリの中の音量、iPhone では本体の音量（音量ボタンと同じ。アプリの中の音量はいつも 100）。
    var volume: Double = 0.7 {
        didSet {
            if isRemote {
                guard !applyingRemoteVolume else { return }
                if remoteVolumeKnown {
                    sendVolumeToRemote()
                } else {
                    // スマホの音量はそのスマホでだけ変える（遠隔で本体の音量を上げると、あとで別のアプリが大きな音で鳴る）
                    applyingRemoteVolume = true
                    volume = oldValue
                    applyingRemoteVolume = false
                }
                return
            }
            #if os(iOS)
            if !applyingSystemVolume { SystemVolume.shared.set(Float(volume)) }
            #else
            localVolume = volume
            engine.volume = volume
            AppServices.settings.volume = volume
            AppServices.settings.save()
            // 鳴らしている端末の音量は、ほかの端末のつまみに映す
            cursorChanged.forEach { $0(false) }
            #endif
        }
    }
    /// 映している端末が音量を知らせている（PC）。スマホ・前の版の端末なら false で、音量のつまみは灰色（Spotify と同じ）。
    private(set) var remoteVolumeKnown = false
    /// 音量のつまみを動かせる（手元で鳴らしている、または映している端末が PC）。
    var canChangeVolume: Bool { !isRemote || remoteVolumeKnown }
    /// この端末で鳴らすときの音量（Mac は設定に残すアプリの中の音量、iPhone は本体の音量）。
    private var localVolume: Double {
        get {
            #if os(iOS)
            Double(SystemVolume.shared.value)
            #else
            storedLocalVolume
            #endif
        }
        set { storedLocalVolume = newValue }
    }
    @ObservationIgnored private var storedLocalVolume = 0.7
    /// 本体の音量が音量ボタンなどで変わったのを、つまみへ映している間（本体へ送り返さない）。
    @ObservationIgnored private var applyingSystemVolume = false
    @ObservationIgnored private var applyingRemoteVolume = false
    /// 本人が映している端末の音量を動かした直後は、相手の古い値でつまみを戻さない。
    @ObservationIgnored private var remoteVolumeHoldUntilMs: Int64 = 0
    @ObservationIgnored private var remoteVolumeWork: DispatchWorkItem?
    /// ほかの端末の音量（0〜100）を変えてもらう（端末の連携が置く）。送れなければ false。
    @ObservationIgnored var sendRemoteVolume: ((Int) -> Bool)?
    /// 映している端末がシャッフルを知らせている（遠隔のシャッフル・繰り返しに従う版）。前の版の端末なら false で、シャッフル・リピートは押せない。
    private(set) var remoteModesKnown = false
    /// シャッフル・リピートを変えられる（手元で鳴らしている、または映している端末が従える版）。
    var canChangeModes: Bool { !isRemote || remoteModesKnown }
    /// ほかの端末のシャッフル・繰り返しを変えてもらう（端末の連携が置く）。送れなければ false。
    @ObservationIgnored var sendRemoteShuffle: ((Bool) -> Bool)?
    @ObservationIgnored var sendRemoteRepeat: ((RepeatMode) -> Bool)?
    /// 本人が映している端末のシャッフル・繰り返しを変えた直後は、相手の古い値で戻さない（新しいカーソルが届くまで、最大3秒）。
    @ObservationIgnored private var remoteModesHoldUntilMs: Int64 = 0
    @ObservationIgnored private var wantedRemoteShuffle: Bool?
    @ObservationIgnored private var wantedRemoteRepeat: RepeatMode?
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

    /// 今の曲がライブ配信（長さ・シーク・歌詞・映像が無い。HLS で流す）。
    var isLive: Bool { current?.isLive == true }

    /// 映像を出せるか（曲の MV、またはライブ配信）。この端末で鳴らしているライブは鳴らしている HLS に映像も入っている。
    /// ほかの端末で鳴っているライブは、この端末で同じライブを音を出さずに読んで映す（remoteLivePlayer）。
    var hasVideo: Bool { video != nil || isLive }

    /// ライブで映像を出しているか（映像を見るための画質で流す。出していない間は音のための小さい画質で通信量を抑える）。
    /// 映像にした曲（liveVideoFor）でだけ効く。曲が変われば音のための画質に戻る。
    private var liveVideo: Bool { liveVideoFor != nil && liveVideoFor == current?.videoId }
    @ObservationIgnored private var liveVideoFor: String?

    /// ライブの映像を出す・やめる。今ライブを鳴らしていれば、その画質で読み直す（ライブは今の時点から流れるので、続きの位置は無い）。
    func setLiveVideo(_ on: Bool) {
        guard liveVideo != on else { return }
        liveVideoFor = on ? current?.videoId : nil
        if isRemote {
            syncRemoteLiveVideo()
            return
        }
        guard isLive, let song = current, engine.hasSource else { return }
        // 鳴っていたかは再生器そのものから取る（一時停止の直後は isPlaying がまだ変わっていないことがある）
        let wasPlaying = engine.player.timeControlStatus != .paused
        // 止まっている間に映像をやめるなら読み直さない（次に鳴らすときも今の流れのまま。画質だけの違い）
        if !on && !wasPlaying { return }
        cancelLoad()
        let token = loadToken
        isLoading = true
        loadTask = Task {
            guard await startLive(song, token: token) else {
                guard token == loadToken else { return }
                await recover(song)
                return
            }
            if !wasPlaying { engine.pause() }
        }
    }

    /// ライブの映像を出す枠に渡す再生器（ライブのときだけ）。この端末で鳴らしていれば鳴らしている再生器、
    /// ほかの端末で鳴っていれば映像だけの再生器（読み込むまでは黒）。
    var liveVideoPlayer: AVPlayer? {
        guard isLive else { return nil }
        return isRemote ? remoteLivePlayer : engine.player
    }

    /// ほかの端末で鳴っているライブの映像を出す、音の無い再生器（MusicVideoPlayer と同じく音は出さない）。
    /// ライブは今の時点から流れるので、鳴らしている端末と位置は合わせない。再生・一時停止だけをその端末に合わせる。
    @ObservationIgnored let remoteLivePlayer: AVPlayer = {
        let p = AVPlayer()
        p.isMuted = true
        return p
    }()
    /// remoteLivePlayer に読んでいる（読もうとしている）ライブの videoId。
    @ObservationIgnored private var remoteLiveFor: String?
    @ObservationIgnored private var remoteLiveTask: Task<Void, Never>?

    /// ほかの端末で鳴っているライブの映像を、今の状態に合わせる（「動画」を選んだライブを映している間だけ読み、曲が変わった・
    /// 映像を閉じた・映すのをやめたら捨てる）。
    private func syncRemoteLiveVideo() {
        let want = isRemote && isLive && liveVideo ? current?.videoId : nil
        if want != remoteLiveFor {
            stopRemoteLiveVideo()
            if let want { startRemoteLiveVideo(want) }
            return
        }
        applyRemoteLivePlaying()
    }

    /// 同じライブの HLS を映像を見るための画質で解決して読む（手元でライブを鳴らすときと同じ解決を使う）。
    private func startRemoteLiveVideo(_ videoId: String) {
        remoteLiveFor = videoId
        remoteLiveTask = Task {
            let result = try? await AppServices.live.resolve(videoId: videoId, video: true)
            guard !Task.isCancelled, remoteLiveFor == videoId else { return }
            guard let stream = result?.stream, let url = URL(string: stream.revealUrl()) else {
                AppPaths.log("remote live video resolve failed reason=\(String(describing: result?.failure))")
                return
            }
            remoteLivePlayer.replaceCurrentItem(with: AVPlayerItem(url: url))
            AppPaths.log("remote live video loaded")
            applyRemoteLivePlaying()
        }
    }

    /// 鳴らしている端末が再生中なら映像も流し、一時停止中なら止める。止めたあと流し直すときは、今の時点（ライブの先頭）へ戻る。
    private func applyRemoteLivePlaying() {
        let p = remoteLivePlayer
        guard remoteLiveFor != nil, let item = p.currentItem else { return }
        if isPlaying {
            guard p.timeControlStatus == .paused else { return }
            if let edge = item.seekableTimeRanges.last?.timeRangeValue.end, edge.isNumeric { p.seek(to: edge) }
            p.play()
        } else if p.timeControlStatus != .paused {
            p.pause()
        }
    }

    private func stopRemoteLiveVideo() {
        remoteLiveTask?.cancel()
        remoteLiveTask = nil
        remoteLiveFor = nil
        remoteLivePlayer.pause()
        remoteLivePlayer.replaceCurrentItem(with: nil)
    }

    /// 今の曲が RSS のエピソード（`ext:`。ADR-0012）。音声の URL をそのまま流し、YouTube のこと（評価・歌詞・MV・ラジオ・履歴・共有）をしない。
    var isExternal: Bool { current.map { Feed.isExternal($0.videoId) } ?? false }

    /// 流している RSS のエピソードの聴いたところ（音声の URL・最後に拾った位置と長さ）。sampled は鳴ってから位置を拾ったか
    /// （拾う前に曲が変わったら、覚えている続きの位置を 0 で上書きしない）。
    private struct EpisodeTrack {
        var url: String
        var position: Double
        var duration: Double?
        var sampled: Bool
    }

    @ObservationIgnored private var episode: EpisodeTrack?
    @ObservationIgnored private var lastEpisodeSaveMs: Int64 = 0
    private static let episodeSaveIntervalMs: Int64 = 15_000
    /// 覚えている位置がこれより後なら、流し始めにそこへ飛ぶ。
    private static let episodeResumeAtLeastSeconds = 5.0

    /// 手元の音の再生位置（映像を合わせるため。ほかの端末を映している間は使わない）。
    var localPositionMs: Int64 { engine.hasSource ? Int64(engine.positionSeconds * 1000) : 0 }

    /// 手元で選んだキューを、ほかの端末で鳴らす（端末の連携が置く）。端末 ID を渡し、頼めたら true。
    @ObservationIgnored var playOnRemote: ((String) -> Bool)?
    /// ほかの端末への命令を送る（端末の連携が置く）。送れたら true。
    @ObservationIgnored var sendRemoteControl: ((ControlAction, Int64?) -> Bool)?
    /// ほかの端末で鳴っている間の「次に再生」（true）・「キューに追加」（false）を、その端末のキューへ送る。送れなければ false。
    @ObservationIgnored var sendRemoteQueueAdd: (([Song], Bool) -> Bool)?
    /// ほかの端末で鳴っている間のキューの並べ替え（動かす曲の項目の ID・どの曲の後ろか。nil なら今の曲の直後）を、その端末へ送る。送れなければ false。
    @ObservationIgnored var sendRemoteQueueMove: ((String, String?) -> Bool)?
    /// ほかの端末で鳴っている間のキューから外す（項目の ID）を、その端末へ送る。送れなければ false。
    @ObservationIgnored var sendRemoteQueueRemove: (([String]) -> Bool)?

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
        #if os(iOS)
        // iPhone はアプリの中の音量を使わない（前の版で下げていても 100 にする）。つまみは本体の音量
        volume = Double(SystemVolume.shared.value)
        engine.volume = 1
        #else
        volume = settings.volume
        engine.volume = volume
        #endif
        // シャッフルはキューごとの状態（Android と同じく、新しい再生では切れる）。設定には持ち越さない
        repeatMode = RepeatMode(rawValue: min(max(settings.repeatMode, 0), 2)) ?? .off
        engine.onEnded = { [weak self] in self?.onEnded() }
        engine.onFailed = { [weak self] error in self?.onFailed(error) }
        engine.onPlayingChanged = { [weak self] playing in
            guard let self, !self.isRemote else { return }
            AppPaths.log("state playing=\(playing) pos=\(String(format: "%.1f", self.engine.positionSeconds))")
            if self.engine.hasSource { self.isPlaying = playing }
        }
        setUpRemoteCommands()
        OutputRouteWatch.shared.start()
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
    /// OS（メディアキー・コントロールセンター・AirPods）からの一時停止。ほかの端末を映している間は、少し待ってから、
    /// 音の出口が変わった前後（AirPods がほかの機械へ移った）なら送らない。この端末は鳴らしていないので、送ると AirPods を持っていった
    /// 機械の再生が止まる。出口の変化の知らせと一時停止のどちらが先に来るかは決まっていないので、待ってから見る。
    private func pauseFromOS() {
        guard isRemote else { return togglePlay() }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(400))
            if OutputRouteWatch.shared.justChanged {
                AppPaths.log("remote pause ignored: output route changed")
                return
            }
            if isRemote, isPlaying { togglePlay() }
        }
    }

    private func setUpRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { if let self, !self.isPlaying { self.togglePlay() } }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { if let self, self.isPlaying { self.pauseFromOS() } }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { if let self { if self.isPlaying { self.pauseFromOS() } else { self.togglePlay() } } }
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
            return MainActor.assumeIsolated {
                // ライブ配信はシークしない
                guard let self, !self.isLive else { return .commandFailed }
                self.seek(e.positionTime)
                return .success
            }
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
        // ライブ配信は長さも位置も無い（ロック画面・コントロールセンターでシークさせない）
        let live = song.isLive
        info[MPNowPlayingInfoPropertyIsLiveStream] = live
        info[MPMediaItemPropertyPlaybackDuration] = live ? nil : duration
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = live ? nil : position
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        MPRemoteCommandCenter.shared().changePlaybackPositionCommand.isEnabled = !live
        center.nowPlayingInfo = info
        center.playbackState = isPlaying ? .playing : .paused
    }

    private func loadArtwork(_ song: Song) {
        // ロック画面・コントロールセンターの画像は大きいもの（Android の largeArtwork と同じ）
        guard let url = Ui.large(song.thumbnailUrl), url.scheme == "https" else { return }
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

    /// 一覧の残り・ラジオの続きを自動で足す。最後の曲で止まっていたら、足した先頭から続ける
    /// （途中の曲で一時的な失敗で止めたものは勝手に再開しない。Android の appendItems と同じ）。
    private func appendContinuing(_ songs: [Song], origin: QueueOrigin) {
        guard !songs.isEmpty else { return }
        let wasEnded = endedAtLast && !isRemote && !isPlaying && !isLoading && index == queue.count - 1
        let first = queue.count
        append(songs, origin: origin)
        if wasEnded {
            AppPaths.log("queue resumed after end added=\(songs.count)")
            selectAt(first)
        }
    }

    private func removeQueueItem(at position: Int) {
        queue.remove(at: position)
        itemMeta.remove(at: position)
        queueChanged.forEach { $0() }
    }

    /// キューを丸ごと置き換える（項目の ID と出どころは渡したものを使う）。
    /// reorder は同じキューの並べ直し（シャッフル）で、一覧の残りの読み込み中・最後で止まっているかを保つ。
    private func replaceAll(_ items: [(Song, QueueItemMeta)], reorder: Bool = false) {
        if !reorder {
            restPending = false
            endedAtLast = false
        }
        queue = items.map(\.0)
        itemMeta = items.map(\.1)
        queueChanged.forEach { $0() }
    }

    private func replaceQueue(_ songs: [Song], origin: QueueOrigin) {
        replaceAll(songs.map { ($0, QueueItemMeta(itemId: UUID().uuidString.lowercased(), origin: origin)) })
    }

    // MARK: 再生の始め方

    /// 曲の一覧を start から再生する（プレイリスト・アルバム・検索の結果）。shuffleOverride が true なら混ぜて始める。
    /// restPending は、長い一覧を最初のページから鳴らし始め、残りをあとで appendFrom で足すとき（読み終えるまでラジオへ切り替えない）。
    /// 一覧を流し終えたら、最後の曲から始まるラジオにつなぐ（Android の QueueSession と同じ。決定 E4）。
    func playList(_ songs: [Song], start: Int = 0, source: String? = nil, shuffleOverride: Bool? = nil, restPending: Bool = false) {
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
        // 出どころ（プレイリスト・アルバム）の無い一覧（検索の結果など）は、初めから最後の曲のラジオを続きにする
        // （Android の playFrom で playlistId が無いときと同じ。端末の連携でもラジオとして渡す）。
        // 出どころがあれば、終わりが近づいたときに extendRadioIfNeeded がラジオへ切り替える
        radioSeed = nil
        if source == nil, let last = order.last, QueueRadio.canSeed(last) {
            radioSeed = last.videoId
            radioPlaylistId = QueueRadio.playlistId(last.videoId)
        }
        replaceQueue(order, origin: .source)
        self.restPending = restPending
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
        // ライブ配信は終わらずに流れ続けるので、後ろにラジオのおすすめを足さない（Android と同じ）。
        // RSS のエピソードは YouTube のラジオが作れない（ADR-0012）
        if song.isLive || Feed.isExternal(song.videoId) {
            radioSeed = nil
            radioPlaylistId = nil
            radioContinuation = nil
            startSelection(0, remoteTarget: target)
            return
        }
        // 曲のラジオの続きは、鳴らす端末が自分で取る（キューには種の曲だけを渡す）
        radioSeed = song.videoId
        radioPlaylistId = QueueRadio.playlistId(song.videoId)
        radioContinuation = nil
        if startSelection(0, remoteTarget: target) { return }
        Task {
            guard let page = (try? await AppServices.music.upNext(videoId: song.videoId, playlistId: radioPlaylistId))?.value,
                let first = queue.first, first.videoId == song.videoId
            else { return }
            radioContinuation = page.continuation
            radioPlaylistId = page.playlistId ?? radioPlaylistId
            appendContinuing(page.songs.filter { $0.videoId != song.videoId }, origin: .suggestion)
        }
    }

    /// あとから読めた残りの曲を、まだキューに無い分だけ足す（長いプレイリストを最初のページから鳴らし始めたとき）。
    /// 残りを読めなかったときも空で呼ぶ（読み終えたことにして、一覧の終わりでラジオへ切り替えられるようにする）。
    func appendFrom(_ songs: [Song]) {
        restPending = false
        var known = Set(queue.map(\.videoId))
        var extra = songs.filter { known.insert($0.videoId).inserted }
        if var order = shuffleOrder {
            // シャッフル中に一覧の残りが読めた: 混ぜて足し、戻す並びには一覧の位置で加える
            extra.shuffle()
            let start = queue.count
            appendContinuing(extra, origin: .source)
            var position: [String: Int] = [:]
            for (i, s) in songs.enumerated() where position[s.videoId] == nil { position[s.videoId] = i }
            for i in start..<queue.count { order[itemMeta[i].itemId] = position[queue[i].videoId] ?? Int.max }
            shuffleOrder = order
        } else {
            appendContinuing(extra, origin: .source)
        }
        // 残りが無かった・少なかった: もう一覧の終わりが近ければラジオへ切り替える
        extendRadioIfNeeded()
    }

    func playNext(_ song: Song) {
        if isRemote {
            queueOnRemote([song], next: true)
            return
        }
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
        if isRemote {
            queueOnRemote(songs, next: next)
            return
        }
        if queue.isEmpty {
            playList(songs)
            return
        }
        if next { insert(songs, at: index + 1, origin: .userAdded) } else { insertUserItems(songs) }
        message = next ? L.t("\(songs.count) 曲を次に再生します", "\(L.songs(songs.count)) will play next")
            : L.t("\(songs.count) 曲をキューに追加しました", "Added \(L.songs(songs.count)) to queue")
    }

    func addToQueue(_ song: Song) {
        if isRemote {
            queueOnRemote([song], next: false)
            return
        }
        if queue.isEmpty {
            playSong(song)
            return
        }
        insertUserItems([song])
        message = L.t("「\(song.title)」をキューに追加しました", "Added \(song.title) to queue")
    }

    /// ほかの端末で鳴っている間の追加は、鳴らしている端末のキューへ送る（手元のキューに入れても、その端末は使わない）。
    /// 映しているキューは、その端末が書き直したものが届いたときに変わる。
    private func queueOnRemote(_ songs: [Song], next: Bool) {
        guard sendRemoteQueueAdd?(songs, next) == true else {
            message = L.t("ほかの端末に送れませんでした", "Couldn’t reach the other device")
            return
        }
        let device = remoteDevice ?? L.t("ほかの端末", "the other device")
        if songs.count == 1, let song = songs.first {
            message = next ? L.t("「\(song.title)」を \(device) で次に再生します", "\(song.title) will play next on \(device)")
                : L.t("「\(song.title)」を \(device) のキューに追加しました", "Added \(song.title) to the queue on \(device)")
        } else {
            message = next ? L.t("\(songs.count) 曲を \(device) で次に再生します", "\(L.songs(songs.count)) will play next on \(device)")
                : L.t("\(songs.count) 曲を \(device) のキューに追加しました", "Added \(L.songs(songs.count)) to the queue on \(device)")
        }
    }

    /// ほかの端末で足した曲を、送り手が振った項目の ID のまま入れる（この端末が鳴らしている。Android の QueueSession.addFromRemote と同じ）。
    /// afterItemId の曲があればその直後（分けて届いた続き）、無ければ next なら今の曲の直後、そうでなければ自分で足した曲のかたまりの最後へ。
    func applyQueueAdd(_ entries: [(song: Song, meta: QueueItemMeta)], next: Bool, afterItemId: String?) -> Bool {
        if isRemote || queue.isEmpty || entries.isEmpty { return false }
        let existing = Set(itemMeta.map(\.itemId))
        if entries.contains(where: { existing.contains($0.meta.itemId) }) { return false }
        let at: Int
        if let afterItemId, let found = itemMeta.firstIndex(where: { $0.itemId == afterItemId }) {
            at = found + 1
        } else {
            at = next ? index + 1 : userQueueEnd
        }
        queue.insert(contentsOf: entries.map(\.song), at: at)
        itemMeta.insert(contentsOf: entries.map { QueueItemMeta(itemId: $0.meta.itemId, origin: .userAdded) }, at: at)
        queueChanged.forEach { $0() }
        return true
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
        let positions = itemMeta.indices.filter { $0 > index && isUserAdded($0) }
        guard removeUpcoming(positions) else { return }
        if !positions.isEmpty { message = L.t("キューから \(positions.count) 曲を外しました", "Removed \(L.songs(positions.count)) from the queue") }
    }

    /// キューの次の曲を並べ替える（今の曲より後ろだけ）。
    /// ほかの端末で鳴っている間は、その端末へ送ってから映しているキューも先に動かす（その端末が書き直したキューが届けば置き換わる）。
    func moveUpcoming(_ from: Int, _ to: Int) {
        if from <= index || to <= index || from >= queue.count || to >= queue.count || from == to { return }
        if isRemote {
            // 動かした後の並びで直前に来る曲の後ろへ、と頼む（今の曲の直後なら nil）。位置でなく項目の ID で伝える
            let itemId = itemMeta[from].itemId
            var ids = itemMeta.map(\.itemId)
            ids.remove(at: from)
            ids.insert(itemId, at: to)
            guard sendRemoteQueueMove?(itemId, to - 1 > index ? ids[to - 1] : nil) == true else {
                message = L.t("ほかの端末に送れませんでした", "Couldn’t reach the other device")
                return
            }
        }
        let song = queue.remove(at: from)
        let meta = itemMeta.remove(at: from)
        queue.insert(song, at: to)
        itemMeta.insert(meta, at: to)
        queueChanged.forEach { $0() }
    }

    func removeAt(_ position: Int) { removeUpcoming([position]) }

    /// キューの次の曲をまとめて外す（今の曲より後ろだけ）。ほかの端末で鳴っている間は、その端末へ1件の命令で送ってから
    /// 映しているキューからも先に外す。送れなければ何もせず false。
    @discardableResult
    func removeUpcoming(_ positions: [Int]) -> Bool {
        let targets = Set(positions.filter { $0 > index && $0 < queue.count }).sorted(by: >)
        if targets.isEmpty { return true }
        if isRemote {
            guard sendRemoteQueueRemove?(targets.reversed().map { itemMeta[$0].itemId }) == true else {
                message = L.t("ほかの端末に送れませんでした", "Couldn’t reach the other device")
                return false
            }
        }
        for position in targets { removeQueueItem(at: position) }
        return true
    }

    /// ほかの端末のキューの画面で並べ替えた曲を動かす（この端末が鳴らしている。Android の QueueSession と同じ）。
    /// itemId の曲（今の曲より後ろ）を afterItemId の曲の直後へ、afterItemId が無ければ今の曲の直後へ。
    /// 自分で足した曲かどうかは手元で並べ替えたときと同じく変えない。どちらかの曲が無い・今の曲より前なら false。
    func applyQueueMove(_ itemId: String, afterItemId: String?) -> Bool {
        if isRemote { return false }
        guard let from = itemMeta.firstIndex(where: { $0.itemId == itemId }), from > index else { return false }
        var after = index
        if let afterItemId {
            guard let found = itemMeta.firstIndex(where: { $0.itemId == afterItemId }), found >= index, found != from else { return false }
            after = found
        }
        // 外してから入れるので、動かす曲より後ろの曲の後ろへは1つ前の位置になる
        let to = after < from ? after + 1 : after
        if to != from { moveUpcoming(from, to) }
        return true
    }

    /// ほかの端末のキューの画面で外した曲を外す（この端末が鳴らしている）。今の曲より後ろの曲だけで、手元に無い ID は無視する。
    func applyQueueRemove(_ itemIds: [String]) -> Bool {
        if isRemote { return false }
        let ids = Set(itemIds)
        let positions = itemMeta.indices.filter { $0 > index && ids.contains(itemMeta[$0].itemId) }
        if positions.isEmpty { return false }
        removeUpcoming(positions)
        return true
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
            pauseEngine()
            userPaused.forEach { $0() }
        } else {
            engine.play()
            userStartedPlayback.forEach { $0(false) }
        }
    }

    /// 止める。読み込み中（引き取った直後など）は AVPlayer がまだ止まったままで再生状態の知らせが来ないので、印もここで下ろし、
    /// 家のカーソルも書き直す（印が前から下りていると変化が起きず、手元は鳴っていないのに家には「再生中」と書いたままになっていた）。
    private func pauseEngine() {
        engine.pause()
        isPlaying = false
        if !isRemote { cursorChanged.forEach { $0(true) } }
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
        // 3秒より先なら曲の頭へ（Spotify と同じ）。ライブ配信には頭が無いので前の曲へ
        if (position > 3 && !isLive) || index <= 0 {
            seek(0)
            return
        }
        selectAt(index - 1)
        userStartedPlayback.forEach { $0(true) }
    }

    /// 前の曲へ（再生した長さによらない。ジャケットを払ったとき。Android の seekToPreviousMediaItem と同じ）。
    func previousTrack() {
        if isRemote || index <= 0 {
            previous()
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
        guard engine.hasSource, !isLive else { return }
        engine.seek(seconds)
        position = seconds
        cursorChanged.forEach { $0(false) }
        updateNowPlayingInfo()
    }

    /// シャッフルの入り切り（Android と同じ）。入れると今の曲より後ろの並びを覚えて混ぜ、切ると残りを覚えた並びに戻す
    /// （シャッフル中に足した曲は今の順のまま最後に付く。曲を外しはしない）。
    func toggleShuffle() {
        // ほかの端末を映している間は、その端末のシャッフルを入れ切りしてもらう（前の版の端末は変えられない）
        if isRemote {
            sendModes(shuffle: !shuffle, repeat: nil)
            return
        }
        if queue.isEmpty || current == nil || index < 0 { return }
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
        replaceAll(head + rest.map { ($0.song, $0.meta) }, reorder: true)
    }

    private func setShuffleOrder(_ order: [String: Int]?) {
        shuffleOrder = order
        let changed = shuffle != (order != nil)
        shuffle = order != nil
        // ほかの端末にシャッフルの印を知らせる（カーソルに載る）
        if changed && !isRemote { cursorChanged.forEach { $0(true) } }
    }

    /// 映している端末へシャッフル・繰り返しを送り、届くまでの間は押した値を見せる。
    private func sendModes(shuffle on: Bool?, repeat mode: RepeatMode?) {
        guard remoteModesKnown else { return }
        let sent = on.map { sendRemoteShuffle?($0) } ?? mode.map { sendRemoteRepeat?($0) } ?? false
        guard sent == true else {
            message = L.t("ほかの端末に送れませんでした", "Couldn’t reach the other device")
            return
        }
        remoteModesHoldUntilMs = clockMs + 3_000
        if let on {
            wantedRemoteShuffle = on
            shuffle = on
        }
        if let mode {
            wantedRemoteRepeat = mode
            repeatMode = mode
        }
    }

    /// シャッフルを入れる・切る（同じなら何もしない。ほかの端末を映している間はその端末へ送る）。
    func setShuffle(_ on: Bool) {
        if shuffle != on { toggleShuffle() }
    }

    /// ほかの端末からシャッフルを入れた・切った（この端末が鳴らしている）。
    func applyShuffle(_ on: Bool) -> Bool {
        if isRemote || current == nil { return false }
        if shuffle != on { toggleShuffle() }
        return true
    }

    /// ほかの端末から繰り返しを変えた（この端末が鳴らしている）。
    func applyRepeat(_ mode: RepeatMode) -> Bool {
        if isRemote { return false }
        setRepeat(mode)
        extendRadioIfNeeded()
        return true
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
        if isRemote {
            sendModes(shuffle: nil, repeat: { switch repeatMode { case .off: .all; case .all: .one; case .one: .off } }())
            return
        }
        switch repeatMode {
        case .off: setRepeat(.all)
        case .all: setRepeat(.one)
        case .one: setRepeat(.off)
        }
        // 全体リピートを切ったら、一覧の終わりが近ければラジオへ切り替える
        extendRadioIfNeeded()
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
        setLiked(song, like != .like)
    }

    /// 曲が高く評価されているか（再生中の曲は再生画面の印と同じ値、ほかの曲は高く評価した曲の一覧から）。
    func isLiked(_ song: Song) -> Bool {
        current?.videoId == song.videoId ? like == .like : AppServices.library.isLiked(song.videoId)
    }

    /// 曲を高く評価する・評価を外す。曲のメニューから、再生中でない曲にも使う（再生中の曲なら再生画面の印も変える）。
    func setLiked(_ song: Song, _ liked: Bool) {
        // RSS のエピソードは YouTube の曲ではない（評価できない）
        guard !Feed.isExternal(song.videoId) else { return }
        guard AppServices.client.isLoggedIn else {
            message = L.t("高く評価するにはログインしてください", "Log in to like songs")
            return
        }
        if current?.videoId == song.videoId { like = liked ? .like : .indifferent }
        Task {
            let result = (try? await AppServices.music.setLiked(song.videoId, liked: liked)) ?? .failed(.network)
            if !result.isOk {
                if current?.videoId == song.videoId { like = liked ? .indifferent : .like }
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
                              repeatMode: repeatMode, listenedMs: listenedNow(), historySent: statsSent,
                              volumePercent: Self.sharesVolume ? Int((localVolume * 100).rounded()) : nil, shuffle: shuffle)
    }

    private func listenedNow() -> Int64 { listen.listenedMs(clockMs) }

    private func onIsPlayingChanged() {
        updateNowPlayingInfo()
        // ほかの端末を映している間は、この端末で聴いた時間ではない（ライブの映像だけ、再生・一時停止を合わせる）
        if isRemote {
            syncRemoteLiveVideo()
            return
        }
        // RSS のエピソードは、止めたらその位置を覚える
        if !isPlaying {
            sampleEpisode()
            saveEpisode()
        }
        if isPlaying { listen.start(clockMs) } else { listen.stop(clockMs) }
        cursorChanged.forEach { $0(true) }
    }

    /// 端末の連携が止める（本人の一時停止として知らせない）。
    func connectPause() {
        cancelLoad()
        isLoading = false
        pauseEngine()
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
        appendContinuing(page.songs.filter { known.insert($0.videoId).inserted }, origin: .suggestion)
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
            finishEpisode()
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
        // 相手のリピート・シャッフルを映すだけ（この端末の設定には書かない）。本人が変えた直後は、それが届くまで古い値で戻さない
        let holding = clockMs < remoteModesHoldUntilMs
        if let wanted = wantedRemoteRepeat, !holding || next.repeatMode == wanted { wantedRemoteRepeat = nil }
        if let wanted = wantedRemoteShuffle, !holding || next.shuffle == wanted { wantedRemoteShuffle = nil }
        if wantedRemoteRepeat == nil { repeatMode = next.repeatMode }
        remoteModesKnown = next.shuffle != nil
        if wantedRemoteShuffle == nil { shuffle = next.shuffle == true }
        // 相手の音量をつまみに映す（本人が動かした直後は、届いた古い値で戻さない）
        remoteVolumeKnown = next.volumePercent != nil
        if let percent = next.volumePercent, clockMs >= remoteVolumeHoldUntilMs {
            applyingRemoteVolume = true
            volume = Double(percent) / 100
            applyingRemoteVolume = false
        }
        position = next.positionNow(clockMs)
        updateNowPlayingInfo()
        syncRemoteLiveVideo()
    }

    /// 映すのをやめる。この端末で鳴らし始めたとき（曲を選んだ・引き取った）は、そのままキューを置き換える。
    /// 相手が見えなくなったときは、映す前の手元のキューに戻す。
    private func leaveRemote(restoreLocal: Bool = false) {
        guard isRemote else { return }
        isRemote = false
        stopRemoteLiveVideo()
        remoteDevice = nil
        remote = nil
        remoteQueueKey = nil
        pendingRemoteSeek = nil
        remoteSeekWork?.cancel()
        isPlaying = false
        position = 0
        repeatMode = RepeatMode(rawValue: AppServices.settings.repeatMode) ?? .off
        // シャッフルの印を手元のものに戻す
        remoteModesKnown = false
        remoteModesHoldUntilMs = 0
        wantedRemoteShuffle = nil
        wantedRemoteRepeat = nil
        shuffle = shuffleOrder != nil
        // つまみを手元の音量に戻す
        remoteVolumeWork?.cancel()
        remoteVolumeHoldUntilMs = 0
        remoteVolumeKnown = false
        applyingSystemVolume = true
        volume = localVolume
        applyingSystemVolume = false
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

    /// ほかの端末から音量を変えられた（この端末が鳴らしている）。Mac はアプリの中の音量を変える。
    /// iPhone は受けない（本体の音量はこの iPhone でだけ変える。Spotify と同じ）。
    func applyVolume(_ percent: Int) -> Bool {
        if isRemote || !Self.sharesVolume { return false }
        volume = Double(min(max(percent, 0), 100)) / 100
        return true
    }

    /// 鳴らしている間の音量をほかの端末と合わせる（PC だけ。スマホの本体の音量はほかの端末から変えない）。
    #if os(iOS)
    static let sharesVolume = false
    #else
    static let sharesVolume = true
    #endif

    #if os(iOS)
    /// 本体の音量が変わった（音量ボタン・コントロールセンター）。手元で鳴らしている間は、つまみへ映す。
    func systemVolumeChanged(_ value: Float) {
        guard !isRemote, abs(volume - Double(value)) >= 0.005 else { return }
        applyingSystemVolume = true
        volume = Double(value)
        applyingSystemVolume = false
    }
    #endif

    /// 映している端末の音量を、つまみの値にしてもらう（動かしている間は 150ms に1回まで）。
    private func sendVolumeToRemote() {
        remoteVolumeHoldUntilMs = clockMs + 2_000
        remoteVolumeWork?.cancel()
        let percent = Int((volume * 100).rounded())
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            if self.sendRemoteVolume?(percent) != true { self.message = L.t("ほかの端末に送れませんでした", "Couldn’t reach the other device") }
        }
        remoteVolumeWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15, execute: work)
    }

    func applyControl(_ action: ControlAction, positionMs: Int64?) -> Bool {
        if isRemote || current == nil { return false }
        switch action {
        case .play: connectResume()
        case .pause: pauseEngine()
        case .next: advance()
        case .previous:
            if (position > 3 && !isLive) || index <= 0 { seek(0) } else { selectAt(index - 1) }
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
        // 前の RSS のエピソードの聴いたところを書く
        finishEpisode()
        cancelLoad()
        let token = loadToken
        guard let song = current else { return }
        endedAtLast = false
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
            listen.reset(listenedMs: seed.listenedMs, playing: isPlaying, nowMs: clockMs)
            statsSent = seed.historySent
            listenedItemId = itemId
        } else if itemId != listenedItemId {
            listen.reset(listenedMs: 0, playing: isPlaying, nowMs: clockMs)
            statsSent = false
            listenedItemId = itemId
        } else {
            // 同じ曲の読み直し: それまで聴いた時間は残し、今から数え直す
            listen.stop(clockMs)
            if isPlaying { listen.start(clockMs) }
        }
        seedListened = nil
        engine.pause()
        updateNowPlayingInfo()
        loadTask = Task {
            var song = song
            if Feed.isExternal(song.videoId) {
                // RSS のエピソード: YouTube の解決を通さず、音声の URL をそのまま流す（ADR-0012）
                if !(await startExternal(song, seekTo: seekTo, token: token)) {
                    guard token == loadToken else { return }
                    await recover(song)
                    return
                }
            } else if song.isLive {
                if !(await startLive(song, token: token)) {
                    guard token == loadToken else { return }
                    await recover(song)
                    return
                }
            } else if let cached = cachedStart(song.videoId) {
                // 残してある曲: URL を調べ直さずに、残した分から鳴らし始める（続きは鳴らしながら取る）
                guard token == loadToken, !Task.isCancelled else { return }
                if !(await startCached(song, cached, seekTo: seekTo, token: token)) {
                    guard token == loadToken else { return }
                    await recover(song)
                    return
                }
            } else if rejected.recentlyRejected(song.videoId, nowMs: clockMs) {
                // 少し前に解決し直しても再生できなかった曲: /player を連打しないよう、解決し直さずに飛ばす
                AppPaths.log("resolve skipped recently rejected")
                guard token == loadToken else { return }
                retried = true
                await recover(song)
                return
            } else {
                switch await resolve(song.videoId) {
                case .failed:
                    if token == loadToken { isLoading = false }
                    return
                case .live:
                    // ライブの印の無い曲がライブ配信だった: 印を付けて HLS で流す
                    guard token == loadToken, !Task.isCancelled else { return }
                    song = markLive(song)
                    if !(await startLive(song, token: token)) {
                        guard token == loadToken else { return }
                        await recover(song)
                        return
                    }
                case .stream(let stream):
                    guard token == loadToken, !Task.isCancelled else { return }
                    if !(await startStream(song, stream, seekTo: seekTo, token: token)) {
                        guard token == loadToken else { return }
                        await recover(song)
                        return
                    }
                }
            }
            consecutiveFailures = 0
            Task { [song] in await loadSongInfo(song, token: token) }
            Task { [song] in await loadLyrics(song, token: token) }
            prefetch()
            extendRadioIfNeeded()
        }
    }

    private enum Resolved {
        case stream(ResolvedStream)
        /// ライブ配信だった（HLS で流し直す）。
        case live
        case failed
    }

    private func resolve(_ videoId: String) async -> Resolved {
        if let cached = prefetched.removeValue(forKey: videoId), AppServices.resolver.isFresh(cached, marginMs: PlayerService.freshMarginMs),
            cached.generation == AppServices.client.generation
        {
            return .stream(cached)
        }
        guard let result = try? await AppServices.resolver.resolve(videoId: videoId) else { return .failed }
        if let stream = result.stream { return .stream(stream) }
        if result.failure == .liveStream { return .live }
        AppPaths.log("resolve failed reason=\(String(describing: result.failure))")
        message = switch result.failure {
        case .notLoggedIn: L.t("この曲を再生するにはログインが必要です", "Log in to play this song")
        case .unplayable: L.t("この曲は再生できません", "This song isn't available")
        case .songLoginRequired: L.t("この曲はログインの確認（年齢の確認など）が要ります", "This song needs a sign-in check (such as age verification)")
        case .network: L.t("通信できませんでした", "Couldn't connect")
        default: L.t("再生の準備ができませんでした（\(String(describing: result.failure))）", "Couldn't prepare playback (\(String(describing: result.failure)))")
        }
        return .failed
    }

    /// キューの中の同じ曲に、ライブ配信の印を付ける（長さは持たない）。
    private func markLive(_ song: Song) -> Song {
        var live = song
        live.isLive = true
        live.durationSeconds = nil
        var changed = false
        for i in queue.indices where queue[i].videoId == song.videoId && !queue[i].isLive {
            queue[i] = live
            changed = true
        }
        if current?.videoId == song.videoId { current = live }
        if changed { queueChanged.forEach { $0() } }
        AppPaths.log("live detected queue=\(queue.count) index=\(index)")
        return live
    }

    /// 鳴り始めたライブ配信（その時刻）。長く鳴ったあとの失敗（URL の期限切れなど）は、もう一度解決し直してよい。
    @ObservationIgnored private var liveStartedAtMs: Int64?

    /// ライブ配信を HLS で鳴らす（ANDROID の /player → マスター → 選んだ1本の媒体プレイリストを AVPlayer にそのまま渡す）。
    /// 範囲取得の経路は通さない。再生の統計は送らない。
    private func startLive(_ song: Song, token: Int) async -> Bool {
        currentStream = nil
        liveStartedAtMs = nil
        guard let result = try? await AppServices.live.resolve(videoId: song.videoId, video: liveVideo) else { return false }
        guard current?.videoId == song.videoId, token == loadToken else { return false }
        guard let stream = result.stream, let url = URL(string: stream.revealUrl()) else {
            AppPaths.log("live resolve failed reason=\(String(describing: result.failure))")
            return false
        }
        guard await engine.loadLive(url) else { return false }
        guard current?.videoId == song.videoId, token == loadToken else { return false }
        engine.play()
        position = 0
        duration = 0
        isPlaying = true
        isLoading = false
        liveStartedAtMs = clockMs
        updateNowPlayingInfo()
        AppPaths.log("play live queue=\(queue.count) index=\(index)")
        return true
    }

    /// RSS のエピソードを流す。続きの位置（引き取った・戻したキューの位置、無ければ覚えている位置）から始める。
    private func startExternal(_ song: Song, seekTo: Double, token: Int) async -> Bool {
        currentStream = nil
        liveStartedAtMs = nil
        guard let audio = Feed.audioUrlOf(song.videoId), let url = URL(string: audio) else {
            AppPaths.log("episode url rejected")
            return false
        }
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.1.0"
        guard await engine.loadExternal(url, userAgent: "wave/\(version)") else { return false }
        guard current?.videoId == song.videoId, token == loadToken else { return false }
        // 覚えている位置が5秒より後で、今の位置が1秒未満なら、そこへ飛ぶ（Android の EpisodePositions と同じ）
        let stored = Double(AppServices.feeds.position(audio)?.resumeSeconds ?? 0)
        let target = seekTo >= 1 ? seekTo : (stored > PlayerService.episodeResumeAtLeastSeconds ? stored : 0)
        if target > 0 && engine.positionSeconds < 1 { engine.seek(target) }
        episode = EpisodeTrack(url: audio, position: target, duration: song.durationSeconds.map(Double.init), sampled: false)
        lastEpisodeSaveMs = clockMs
        engine.play()
        position = target
        if engine.durationSeconds > 0 { duration = engine.durationSeconds }
        isPlaying = true
        isLoading = false
        updateNowPlayingInfo()
        AppPaths.log("play episode resume=\(Int(target)) stored=\(Int(stored)) queue=\(queue.count) index=\(index)")
        return true
    }

    /// 流している RSS のエピソードの位置と長さを拾う（音声の部品が鳴らしている曲のときだけ）。
    private func sampleEpisode() {
        guard var e = episode, !isRemote, !isLoading, engine.hasSource, let song = current, Feed.audioUrlOf(song.videoId) == e.url else { return }
        e.position = engine.positionSeconds
        let d = engine.durationSeconds
        e.duration = d > 0 ? d : song.durationSeconds.map(Double.init)
        e.sampled = true
        episode = e
    }

    /// 拾った位置を書く。finished なら最後まで聴いたとする（自然に次へ進んだ・1曲リピートで頭へ戻った）。
    private func saveEpisode(finished: Bool = false) {
        lastEpisodeSaveMs = clockMs
        guard let e = episode, e.sampled || finished else { return }
        let end = finished ? (e.duration ?? e.position) : e.position
        AppServices.feeds.remember(e.url, positionSeconds: Int(end), durationSeconds: e.duration.map { Int($0) })
    }

    /// 曲が変わる・止める前に、今のエピソードの位置を書いて手放す。
    private func finishEpisode() {
        sampleEpisode()
        saveEpisode()
        episode = nil
    }

    /// 残してある曲で、鳴らし始めに足りる分（頭の区切りまで）が残っていれば、その形式。
    private func cachedStart(_ videoId: String) -> CachedFormat? {
        guard let entry = AppServices.audioCache.entry(videoId) else { return nil }
        let need = entry.format.dash.map { $0.indexEnd + 1 } ?? 0
        return entry.cached >= max(need, PlayerService.cachedStartBytes) || entry.cached >= entry.format.length ? entry.format : nil
    }

    /// 鳴らし始めに残っていてほしいバイト数（256kbps で 15 秒ほど）。
    private static let cachedStartBytes: Int64 = 512 * 1024
    /// 次の曲を先に取っておくバイト数（256kbps で 30 秒ほど）。
    private static let warmBytes: Int64 = 1024 * 1024

    private func startCached(_ song: Song, _ format: CachedFormat, seekTo: Double, token: Int) async -> Bool {
        currentStream = nil
        cpn = PlaybackStats.newCpn()
        let videoId = song.videoId
        // 続きが要ったとき・再生履歴を送るときに、URL を調べる
        let bytes = RangeStream.openCached(session: AppServices.mediaSession, format: format, cache: AppServices.audioCache) { @Sendable in
            guard let stream = try await AppServices.resolver.resolve(videoId: videoId).stream else { throw StreamRejectedError(status: 0) }
            await MainActor.run {
                if AppServices.player.current?.videoId == videoId { AppServices.player.currentStream = stream }
            }
            return stream
        }
        guard await engine.load(bytes, mimeType: format.mimeType, key: "\(videoId)/\(format.itag)", dash: format.dash) else { return false }
        guard current?.videoId == videoId, token == loadToken else { return false }
        if seekTo > 1 { engine.seek(seekTo) }
        engine.play()
        isPlaying = true
        isLoading = false
        updateNowPlayingInfo()
        AppPaths.log("play cached itag=\(format.itag) queue=\(queue.count) index=\(index)")
        Task { try? await bytes.prepareSource() }
        return true
    }

    private func startStream(_ song: Song, _ stream: ResolvedStream, seekTo: Double, token: Int) async -> Bool {
        currentStream = stream
        cpn = PlaybackStats.newCpn()
        let bytes: RangeStream
        do {
            bytes = try await RangeStream.open(session: AppServices.mediaSession, stream: stream, cache: AppServices.audioCache)
        } catch {
            AppPaths.log("stream open failed type=\(type(of: error))")
            return false
        }
        guard current?.videoId == song.videoId, token == loadToken else { return false }
        guard await engine.load(bytes, mimeType: stream.mimeType, key: "\(song.videoId)/\(stream.itag)", dash: stream.dash) else { return false }
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
        // 曲の途中で「終わった」と来たら、読み込みの失敗とみなす（次々に飛ばさない）。
        // RSS のエピソードは配信元の長さがずれていることが多いので、AVPlayer の終わりの知らせを信じる
        let external = isExternal
        let known = Double(current?.durationSeconds ?? 0)
        if !external && known > 10 && position < known - 5 {
            AppPaths.log("ended early position=\(String(format: "%.1f", position)) duration=\(known)")
            if let song = current { Task { await recover(song) } }
            return
        }
        if external, let e = episode {
            // 最後まで聴いた（続きは頭から）。同じエピソードを流し直すなら、拾い直すまで書かない
            sampleEpisode()
            saveEpisode(finished: true)
            episode = EpisodeTrack(url: e.url, position: 0, duration: episode?.duration ?? e.duration, sampled: false)
        }
        if repeatMode == .one {
            engine.seek(0)
            engine.play()
            statsSent = false
            listen.reset(listenedMs: 0, playing: true, nowMs: clockMs)
            cpn = PlaybackStats.newCpn()
            return
        }
        if index + 1 < queue.count || repeatMode == .all {
            advance()
        } else {
            isPlaying = false
            endedAtLast = true
            // ラジオの続きを取れずに最後まで来た: もう一度取ってみる（足せたら appendContinuing が続きから鳴らす）
            extendRadioIfNeeded()
        }
    }

    private func onFailed(_ error: String) {
        AppPaths.log("media failed error=\(error) retried=\(retried)")
        let resume = engine.wantsPlay
        if let song = current { Task { await recover(song, resume: resume) } }
    }

    /// 鳴らせなかったとき。1回だけ解読の準備から取り直してやり直し、だめなら次の曲へ。続けて失敗したら止める
    /// （ログインしていないと、googlevideo は先頭の一部しか返さないので、どの曲も鳴らない）。
    /// resume が false なら（止めていた曲が裏で読めなくなったなど）、やり直した後も止めたままにする。
    private func recover(_ song: Song, resume: Bool = true) async {
        // ライブ配信はログインなしで鳴る（www.youtube.com の匿名の HLS）。URL は IP に結び付き約6時間で切れるので、解決し直して1回やり直す
        let live = current?.videoId == song.videoId ? isLive : song.isLive
        // RSS のエピソードはログインなしで鳴る（配信元から直接取る）
        let external = Feed.isExternal(song.videoId)
        if live, let started = liveStartedAtMs, clockMs - started > 60_000 { retried = false }
        if !live && !external {
            guard AppServices.client.isLoggedIn else {
                isLoading = false
                message = L.t("曲を再生するにはログインしてください（右上のアカウントから）", "Log in to play songs (from the account button at the top right)")
                return
            }
        }
        if !retried {
            retried = true
            let token = loadToken
            if live {
                AppPaths.log("live retry")
                isLoading = true
                if let song = current, song.isLive, await startLive(song, token: token) {
                    consecutiveFailures = 0
                    if !resume { holdPaused() }
                    return
                }
            } else if external {
                AppPaths.log("episode retry")
                isLoading = true
                let at = position
                if current?.videoId == song.videoId, await startExternal(song, seekTo: at, token: token) {
                    consecutiveFailures = 0
                    if !resume { holdPaused() }
                    return
                }
            } else {
                AppServices.resolver.onStreamRejected()
                prefetched.removeValue(forKey: song.videoId)
                let at = position
                if case .stream(let stream) = await resolve(song.videoId), current?.videoId == song.videoId,
                    await startStream(song, stream, seekTo: at, token: token)
                {
                    consecutiveFailures = 0
                    if !resume { holdPaused() }
                    return
                }
            }
        }
        isLoading = false
        // 解決し直しても再生できなかった曲は、しばらく解決し直さない（もう覚えていれば、時刻は延ばさない）
        if !live && !external && !rejected.recentlyRejected(song.videoId, nowMs: clockMs) { rejected.markRejected(song.videoId, nowMs: clockMs) }
        consecutiveFailures += 1
        if consecutiveFailures >= PlayerService.maxConsecutiveFailures {
            message = L.t("続けて再生できなかったので、止めました", "Stopped after several songs failed to play")
            consecutiveFailures = 0
            return
        }
        if external {
            message = index + 1 < queue.count ? L.t("エピソードを再生できなかったので、次に進みます", "Couldn't play this episode, skipping to the next one")
                : L.t("エピソードを再生できませんでした", "Couldn't play this episode")
        } else if live {
            message = index + 1 < queue.count ? L.t("ライブ配信を再生できなかったので、次の曲に進みます", "Couldn't play this live stream, skipping to the next one")
                : L.t("ライブ配信を再生できませんでした", "Couldn't play this live stream")
        } else {
            message = L.t("再生できなかったので、次の曲に進みます", "Couldn't play this song, skipping to the next one")
        }
        if index + 1 < queue.count { advance() }
    }

    /// やり直しで鳴り始めた曲を、止めていた状態に戻す。
    private func holdPaused() {
        engine.pause()
        isPlaying = false
        updateNowPlayingInfo()
    }

    private func tick() {
        if isRemote {
            if let r = remote { position = r.positionNow(clockMs) }
            return
        }
        if isPlaying && clockMs - lastPositionSaveMs > PlayerService.positionSaveIntervalMs { saveQueue() }
        guard engine.hasSource, !isLoading else { return }
        position = engine.positionSeconds
        let external = isExternal
        // 断片の MP4 では AVPlayer の読む長さが実際より長いことがある。曲の長さが分かっていればそちらを使う。
        // RSS のエピソードは配信元の長さ（itunes:duration）がずれていることがあるので、音声から読んだ長さを使う
        if current?.durationSeconds == nil || external, engine.durationSeconds > 0 { duration = engine.durationSeconds }
        // 長さを長く読んだときは AVPlayer が終わりを知らせないので、本当の長さ（形式の長さ、無ければ曲の長さ）に着いたら終わりとする。
        // RSS のエピソードは AVPlayer の終わりの知らせに任せる
        let realEnd = external ? 0 : currentStream?.dash.map { Double($0.durationMs) / 1000 } ?? Double(current?.durationSeconds ?? 0)
        // RSS のエピソードの聴いたところを拾い、時々書く
        if external && isPlaying {
            sampleEpisode()
            if clockMs - lastEpisodeSaveMs >= PlayerService.episodeSaveIntervalMs { saveEpisode() }
        }
        if position < (realEnd > 10 ? realEnd : engine.durationSeconds) - 1 { endHandled = false }
        if isPlaying, !endHandled, realEnd > 10, position >= realEnd - 0.2 { onEnded() }
        // 1回の再生につき1回、実際に聴いた時間が 30 秒（短い曲は長さの半分）に達したら送る（ADR-0006。Android の ListenCounter と同じ）
        let lengthMs = realEnd > 0 ? Int64(realEnd * 1000) : duration > 0 ? Int64(duration * 1000) : nil
        if isPlaying, listen.shouldSend(nowMs: clockMs, durationMs: lengthMs, alreadySent: statsSent), let stream = currentStream,
            AppServices.settings.sendHistory, AppServices.client.isLoggedIn
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
        // RSS のエピソードには YouTube の評価も MV も無い（問い合わせない）
        if Feed.isExternal(song.videoId) {
            if current?.videoId == song.videoId {
                like = nil
                video = nil
            }
            return
        }
        guard let info = (try? await AppServices.music.songInfo(song.videoId))?.value, current?.videoId == song.videoId else { return }
        if AppServices.client.isLoggedIn { like = info.like }
        // ライブ配信には重ねる映像（MV）を出さない
        video = song.isLive || isLive ? nil : info.video
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
        // ライブ配信には歌詞が無い（探さない）
        // RSS のエピソードにも無い（ADR-0012）
        if song.isLive || Feed.isExternal(song.videoId) || (current?.videoId == song.videoId && isLive) {
            if current?.videoId == song.videoId { lyrics = LyricsService.Result.none }
            return
        }
        guard let result = try? await AppServices.lyrics.lyrics(song: song) else { return }
        if current?.videoId == song.videoId { lyrics = result }
    }

    /// 次の曲の URL を先に解決しておく（曲の切り替えを速くする）。
    private func prefetch() {
        guard index + 1 < queue.count else { return }
        let next = queue[index + 1]
        if prefetched[next.videoId] != nil || next.isLive || Feed.isExternal(next.videoId) || rejected.recentlyRejected(next.videoId, nowMs: clockMs) { return }
        // 頭を残してある曲は、URL も調べない（鳴らすときに残した分から始める）
        if cachedStart(next.videoId) != nil { return }
        Task {
            guard let stream = (try? await AppServices.resolver.resolve(videoId: next.videoId))?.stream else { return }
            prefetched[next.videoId] = stream
            prefetchOrder.append(next.videoId)
            while prefetched.count > 4, !prefetchOrder.isEmpty { prefetched.removeValue(forKey: prefetchOrder.removeFirst()) }
            // 頭の 30 秒ほどを取って残す（曲送りで、すぐ鳴らせる）
            let warmed = try? await RangeStream.open(session: AppServices.mediaSession, stream: stream, cache: AppServices.audioCache)
            try? await warmed?.warm(upTo: PlayerService.warmBytes)
            AppPaths.log("prefetch warmed=\(AppServices.audioCache.entry(next.videoId)?.cached ?? 0)")
        }
    }

    /// 残りが少なくなったら続きを足す。ラジオなら続きのページを、一覧（プレイリスト・アルバム・アーティスト）なら
    /// 最後の曲から始まるラジオに切り替えて、その最初のページを足す（Android の QueueSession と同じ。決定 E4）。
    private func extendRadioIfNeeded() {
        if extending || isRemote || !QueueRadio.nearEnd(count: queue.count, index: index) { return }
        if let seed = QueueRadio.switchSeed(queue: queue, index: index, radioSeed: radioSeed, radioContinuation: radioContinuation,
                                            repeatAll: repeatMode == .all, restPending: restPending)
        {
            // 出どころ（sourceId）は「再生中」の表示のために残す。連携へはラジオとして書き出す（exportQueue）
            radioSeed = seed
            radioPlaylistId = QueueRadio.playlistId(seed)
            radioContinuation = nil
            AppPaths.log("queue switched to radio items=\(queue.count) index=\(index)")
            queueChanged.forEach { $0() }
            Task { await extendFromSeed(seed) }
            return
        }
        // ほかの端末から引き取ったラジオは続きの印を持たないので、種の曲から取り直す
        guard let continuation = radioContinuation else {
            if let seed = radioSeed { Task { await extendFromSeed(seed) } }
            return
        }
        // 全体リピート中は今のキューを繰り返すので、ラジオの続きは取らない（Android の blockedByRepeat と同じ）
        if repeatMode == .all { return }
        extending = true
        Task {
            defer { extending = false }
            guard let page = (try? await AppServices.music.upNext(videoId: nil, playlistId: radioPlaylistId, continuation: continuation))?.value else { return }
            radioContinuation = page.continuation
            var known = Set(queue.map(\.videoId))
            appendContinuing(page.songs.filter { known.insert($0.videoId).inserted }, origin: .suggestion)
        }
    }

    /// ログアウトのとき。再生を止めてキューを空にする（別のアカウントの URL を使い続けない）。
    func stop() {
        // ログアウト・別のアカウントでのログイン: 保存したキューも消す
        saveWork?.cancel()
        store.delete()
        // RSS のエピソードの聴いたところはアカウントに結びつかないので、書いてから止める（消さない）
        finishEpisode()
        leaveRemote()
        cancelLoad()
        engine.clear()
        prefetched.removeAll()
        prefetchOrder.removeAll()
        rejected.clear()
        // 聴いた曲の音も残さない
        AppServices.audioCache.clear()
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
        // 「すべての曲」を読み終えるまでは、人気の曲の終わりでラジオへ切り替えない
        playList(topSongs, start: start, source: browseId, shuffleOverride: shuffle, restPending: allSongs != nil)
        guard let allSongs else { return }
        let all = await allSongs.value
        AppPaths.log("artist all songs ok=\(all?.isOk ?? false) songs=\(all?.value?.songs.count ?? 0)")
        // 読んでいる間にほかのものを流し始めていたら足さない。読めなくても、読み終えたことにする（ラジオへ切り替えられるように）
        if sourceId == browseId { appendFrom(all?.value?.songs ?? []) }
    }
}
