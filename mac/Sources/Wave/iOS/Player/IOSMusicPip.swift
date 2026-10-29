#if os(iOS)
import AVKit
import CoreImage
import CoreMedia
import SwiftUI
import UIKit
import WaveCore

/// 音楽の PiP（映像を出していない曲でも、ホームへ戻ると小窓に曲名・アーティスト・ジャケットを出す）。
/// 時刻付きの歌詞があれば、歌っている行を大きく、次の行を薄く出す（行が変わるたびに絵を描き直す）。
/// 1枚の絵を AVSampleBufferDisplayLayer に入れて PiP にし、PiP の再生・一時停止は曲の再生・一時停止、
/// 送り・戻しのボタンは次の曲・前の曲に回す。設定の「音楽の再生中も小窓を出す」がオンのときだけ。
@MainActor
final class MusicPip: NSObject, AVPictureInPictureControllerDelegate, AVPictureInPictureSampleBufferPlaybackDelegate {
    static let shared = MusicPip()

    /// 小窓で出しているか。
    private(set) var active = false

    let surface = SurfaceView()
    private var controller: AVPictureInPictureController?
    private let timebase: CMTimebase? = {
        var timebase: CMTimebase?
        CMTimebaseCreateWithSourceClock(allocator: kCFAllocatorDefault, sourceClock: CMClockGetHostTimeClock(), timebaseOut: &timebase)
        return timebase
    }()
    /// 読み込んだジャケット。
    private var artwork: UIImage?
    private var artworkFor: String?

    final class SurfaceView: UIView {
        override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }
        var displayLayer: AVSampleBufferDisplayLayer { layer as! AVSampleBufferDisplayLayer }
    }

    override init() {
        super.init()
        surface.displayLayer.videoGravity = .resizeAspect
        surface.displayLayer.controlTimebase = timebase
        surface.isUserInteractionEnabled = false
        // wave に戻ったら小窓は閉じる（画面に同じ曲が出ているので）。裏にいる間に枠の絵が捨てられていることがあり、
        // 絵の無い枠からは次に裏へ回っても小窓が始まらないので、描き直しておく
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated {
                MusicPip.shared.stop()
                MusicPip.shared.redraw()
            }
        }
        observe()
    }

    /// 曲・再生中か・位置・設定・映像の表示が変わるたびに、絵と小窓の状態を合わせる。
    private func observe() {
        withObservationTracking {
            sync()
        } onChange: {
            DispatchQueue.main.async { MainActor.assumeIsolated { MusicPip.shared.observe() } }
        }
    }

    private var enabled: Bool { AppServices.settings.musicPip }

    /// 映像の PiP を使う場面（再生画面で動画を選んでいる・映像の PiP を出している）では、音楽の PiP は出さない。
    private var videoInUse: Bool {
        let player = AppServices.player!
        return VideoPip.shared.active || (IOSShell.shared.showVideo && player.hasVideo)
    }

    private func sync() {
        let player = AppServices.player!
        let song = player.current
        let playing = player.isPlaying
        let position = player.position
        _ = player.duration
        _ = IOSShell.shared.showVideo
        _ = VideoPip.shared.active
        guard enabled, let song, controller != nil || AVPictureInPictureController.isPictureInPictureSupported() else {
            controller?.canStartPictureInPictureAutomaticallyFromInline = false
            if active { stop() }
            return
        }
        ensureController()
        if videoInUse {
            controller?.canStartPictureInPictureAutomaticallyFromInline = false
            if active { stop() }
        } else {
            // 鳴っている間だけ、ホームへ戻ったら自動で小窓にする
            controller?.canStartPictureInPictureAutomaticallyFromInline = playing
        }
        if let timebase {
            CMTimebaseSetTime(timebase, time: CMTime(seconds: song.isLive ? 0 : position, preferredTimescale: 600))
            CMTimebaseSetRate(timebase, rate: playing ? 1 : 0)
        }
        draw(song, lyric: lyricLine(song, positionMs: Int64(position * 1000)))
        controller?.invalidatePlaybackState()
    }

    private func ensureController() {
        guard controller == nil else { return }
        let source = AVPictureInPictureController.ContentSource(sampleBufferDisplayLayer: surface.displayLayer, playbackDelegate: self)
        let controller = AVPictureInPictureController(contentSource: source)
        controller.delegate = self
        // 送り・戻しのボタンを出す（次の曲・前の曲に使う）
        controller.requiresLinearPlayback = false
        self.controller = controller
    }

    /// 動かさずに描き直す。
    private func redraw() {
        shown = nil
        sync()
    }

    /// 設定を切り替えたとき（設定は見張れないので、ここから合わせ直す）。
    func settingChanged() { sync() }

    /// 手で小窓を出す（開発用の命令）。
    func start() {
        guard enabled, !videoInUse else { return }
        ensureController()
        controller?.startPictureInPicture()
    }

    func stop() {
        if controller?.isPictureInPictureActive == true { controller?.stopPictureInPicture() }
    }

    // MARK: 絵

    /// 時刻付きの歌詞があるときの、歌っている行と次の行。無ければ nil（曲名の絵のまま）。
    private func lyricLine(_ song: Song, positionMs: Int64) -> LyricPair? {
        guard !song.isLive, case .found(.synced(let synced)) = AppServices.player.lyrics, !synced.lines.isEmpty else { return nil }
        let (current, next) = LyricsTiming.currentAndNext(synced.lines, positionMs: positionMs)
        return LyricPair(current: current, next: next)
    }

    struct LyricPair: Equatable {
        var current: String?
        var next: String?
    }

    /// 小窓に出す中身（変わったら描き直す）。
    private struct Scene: Equatable {
        var videoId: String
        var title: String
        var artist: String
        var hasArtwork: Bool
        var lyric: LyricPair?
    }

    /// 前の絵から次の絵への移り方。
    private enum Motion {
        /// 前の絵を薄めながら次の絵を重ねる（曲が変わったとき・ジャケットが届いたとき・行が飛んだとき）。
        case crossfade(UIImage)
        /// 曲名の絵と歌詞の絵の間: ジャケットが大きさと場所を変え、文字を入れ替える。lyric は歌詞の絵の側の行。
        case morph(lyric: LyricPair, toLyric: Bool)
        /// 行が次へ進んだ: 歌っていた行が上へ抜け、次の行が上がって大きくなり、その次の行が下から出る。
        case scroll(from: LyricPair)
    }

    /// 出している（か移っている先の）中身と、最後に渡した絵。
    private var shown: Scene?
    private var lastFrame: UIImage?
    private var animation: Task<Void, Never>?

    /// 移り変わりの長さ（ばねがほぼ止まるまで）と、絵を送る間隔（30 コマ）。
    private static let motionDuration: CFTimeInterval = 0.55
    private static let frameInterval: Duration = .milliseconds(33)

    private func draw(_ song: Song, lyric: LyricPair?) {
        if artworkFor != song.videoId {
            artworkFor = song.videoId
            artwork = nil
            loadArtwork(song)
        }
        let scene = Scene(videoId: song.videoId, title: song.title, artist: song.artistsText, hasArtwork: artwork != nil, lyric: lyric)
        guard scene != shown else { return }
        let from = shown
        shown = scene
        let interrupted = animation != nil
        animation?.cancel()
        animation = nil
        // 小窓を出していないとき（wave の画面にいるとき）は動かさずに描く
        guard active, let from, let lastFrame, let motion = Self.motion(from: from, to: scene, lastFrame: lastFrame, interrupted: interrupted) else {
            enqueue(Self.frame(scene, artwork: artwork, motion: nil, progress: 1))
            return
        }
        let artwork = artwork
        animation = Task { @MainActor [weak self] in
            let start = CACurrentMediaTime()
            while !Task.isCancelled {
                let elapsed = CACurrentMediaTime() - start
                let done = elapsed >= Self.motionDuration
                self?.enqueue(Self.frame(scene, artwork: artwork, motion: done ? nil : motion, progress: Self.spring(elapsed)))
                if done { break }
                try? await Task.sleep(for: Self.frameInterval)
            }
            if !Task.isCancelled { self?.animation = nil }
        }
    }

    /// 前の中身から次の中身への移り方。動かさずに描くなら nil。
    private static func motion(from: Scene, to: Scene, lastFrame: UIImage, interrupted: Bool) -> Motion? {
        // 動きを減らす設定のとき・前の移り変わりの途中で次が来たときは、見えている絵から薄めて重ねる
        let fade = Motion.crossfade(lastFrame)
        if UIAccessibility.isReduceMotionEnabled || interrupted { return fade }
        guard from.videoId == to.videoId, from.title == to.title, from.artist == to.artist, from.hasArtwork == to.hasArtwork else { return fade }
        switch (from.lyric, to.lyric) {
        case let (old?, new?):
            // 次の行が歌っている行になった（ふつうに進んだ）ときだけ送る。シークで飛んだときは重ねる
            if let current = new.current, current == old.next { return .scroll(from: old) }
            return fade
        case let (nil, new?): return .morph(lyric: new, toLyric: true)
        case let (old?, nil): return .morph(lyric: old, toLyric: false)
        case (nil, nil): return fade
        }
    }

    /// 減衰のちょうどよいばね（行き過ぎない）。0 から始まり 1 に寄っていく。
    private static func spring(_ t: CFTimeInterval) -> CGFloat {
        let omega = 2 * Double.pi / 0.4
        return CGFloat(min(1, 1 - (1 + omega * t) * exp(-omega * t)))
    }

    private func enqueue(_ image: UIImage) {
        lastFrame = image
        guard let buffer = Self.sampleBuffer(image, width: Self.width, height: Self.height) else { return }
        let renderer = surface.displayLayer.sampleBufferRenderer
        renderer.flush(removingDisplayedImage: renderer.requiresFlushToResumeDecoding, completionHandler: nil)
        renderer.enqueue(buffer)
    }

    private func loadArtwork(_ song: Song) {
        guard let url = Ui.large(song.thumbnailUrl), url.scheme == "https" else { return }
        Task {
            guard let (data, _) = try? await ImageLoader.session.data(from: url), let image = UIImage(data: data) else { return }
            guard artworkFor == song.videoId else { return }
            artwork = image
            // 届いたジャケットは、音符の絵から薄めて重ねる
            if let current = AppServices.player.current, current.videoId == song.videoId { sync() }
        }
    }

    private static let width = 960, height = 540

    #if WAVE_DEBUG
    /// 開発用: 今の曲で小窓に渡す絵を PNG で返す。
    func debugImage() -> Data? {
        guard let song = AppServices.player.current else { return nil }
        let scene = Scene(videoId: song.videoId, title: song.title, artist: song.artistsText, hasArtwork: artwork != nil,
                          lyric: lyricLine(song, positionMs: Int64(AppServices.player.position * 1000)))
        return Self.frame(scene, artwork: artwork, motion: nil, progress: 1).pngData()
    }

    /// 開発用: 今の絵から、次の行へ送る・曲名の絵へ移る途中の絵を、progress（0〜1）の所で PNG にする。
    func debugMotionImage(_ kind: String, progress: Double) -> Data? {
        guard let song = AppServices.player.current,
              let lyric = lyricLine(song, positionMs: Int64(AppServices.player.position * 1000)) else { return nil }
        var scene = Scene(videoId: song.videoId, title: song.title, artist: song.artistsText, hasArtwork: artwork != nil, lyric: lyric)
        let motion: Motion
        switch kind {
        case "scroll":
            // 今の行から次の行へ進んだつもりで、次の次は空にする
            motion = .scroll(from: lyric)
            scene.lyric = LyricPair(current: lyric.next, next: nil)
        default:
            motion = .morph(lyric: lyric, toLyric: true)
        }
        return Self.frame(scene, artwork: artwork, motion: motion, progress: CGFloat(progress)).pngData()
    }
    #endif

    // 曲名の絵のジャケットと、歌詞の絵の小さなジャケット。
    private static let titleArt = CGRect(x: 60, y: 90, width: 360, height: 360)
    private static let lyricArt = CGRect(x: 56, y: 44, width: 112, height: 112)
    // 歌詞の行の置き場
    private static let lyricX: CGFloat = 56
    private static var lyricWidth: CGFloat { CGFloat(width) - lyricX * 2 }
    private static var lyricTop: CGFloat { lyricArt.maxY + 36 }
    private static let lineGap: CGFloat = 18
    private static let options: NSStringDrawingOptions = [.usesLineFragmentOrigin, .truncatesLastVisibleLine]

    private static let truncating: NSParagraphStyle = {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byTruncatingTail
        return paragraph
    }()

    // （byTruncatingTail は1行に切ってしまうので、歌詞は折り返して最後の行だけを「…」にする）
    private static let wrapping: NSParagraphStyle = {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byWordWrapping
        return paragraph
    }()

    // 歌っている行は3行まで、次の行は2行まで。小さい小窓でも読めるよう大きめに
    private static let currentSize: CGFloat = 60, nextSize: CGFloat = 42
    private static let currentAttrs: [NSAttributedString.Key: Any] = [
        .font: UIFont.systemFont(ofSize: currentSize, weight: .bold), .foregroundColor: UIColor.white, .paragraphStyle: wrapping,
    ]
    private static let nextAttrs: [NSAttributedString.Key: Any] = [
        .font: UIFont.systemFont(ofSize: nextSize, weight: .semibold), .foregroundColor: UIColor(white: 1, alpha: 0.45), .paragraphStyle: wrapping,
    ]

    /// 中身を1枚の絵にする。motion があれば、前の絵から progress（0〜1）まで移った所の絵。
    private static func frame(_ scene: Scene, artwork: UIImage?, motion: Motion?, progress p: CGFloat) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let rect = CGRect(x: 0, y: 0, width: width, height: height)
        return UIGraphicsImageRenderer(size: rect.size, format: format).image { context in
            let cg = context.cgContext
            switch motion {
            case .crossfade(let old)?:
                old.draw(in: rect)
                cg.setAlpha(p)
                cg.beginTransparencyLayer(auxiliaryInfo: nil)
                paint(context, scene, artwork: artwork)
                cg.endTransparencyLayer()
            case .morph(let lyric, let toLyric)?:
                // q: 0 が曲名の絵、1 が歌詞の絵
                let q = toLyric ? p : 1 - p
                drawBackdrop(context, artwork: artwork)
                drawArtwork(context, artwork: artwork, in: lerp(titleArt, lyricArt, q), cornerRadius: lerp(24, 12, q), notePoint: lerp(140, 48, q))
                cg.setAlpha(1 - q)
                drawTitleText(title: scene.title, artist: scene.artist)
                cg.setAlpha(q)
                drawLyricHeader(title: scene.title, artist: scene.artist)
                drawLines(lyric)
            case .scroll(let old)?:
                drawBackdrop(context, artwork: artwork)
                drawArtwork(context, artwork: artwork, in: lyricArt, cornerRadius: 12, notePoint: 48)
                drawLyricHeader(title: scene.title, artist: scene.artist)
                drawScroll(cg, from: old, to: scene.lyric ?? LyricPair(), progress: p)
            case nil:
                paint(context, scene, artwork: artwork)
            }
        }
    }

    /// 止まっている絵。時刻付きの歌詞があれば歌詞の絵（上に小さなジャケットと曲名・アーティスト、その下に歌っている行を大きく、次の行を薄く）、
    /// 無ければ曲名の絵（ぼかしたジャケットを下に敷き、左にジャケット、右に曲名とアーティスト）。
    private static func paint(_ context: UIGraphicsImageRendererContext, _ scene: Scene, artwork: UIImage?) {
        drawBackdrop(context, artwork: artwork)
        if let lyric = scene.lyric {
            drawArtwork(context, artwork: artwork, in: lyricArt, cornerRadius: 12, notePoint: 48)
            drawLyricHeader(title: scene.title, artist: scene.artist)
            drawLines(lyric)
        } else {
            drawArtwork(context, artwork: artwork, in: titleArt, cornerRadius: 24, notePoint: 140)
            drawTitleText(title: scene.title, artist: scene.artist)
        }
    }

    /// 曲名の絵の文字: 曲名は3行まで、アーティストは2行まで。2つをまとめてジャケットの縦の真ん中に置く。
    private static func drawTitleText(title: String, artist: String) {
        let textX = titleArt.maxX + 44
        let textWidth = CGFloat(width) - textX - 50
        let titleAttrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 52, weight: .bold), .foregroundColor: UIColor.white, .paragraphStyle: truncating,
        ]
        let artistAttrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 38, weight: .regular), .foregroundColor: UIColor(white: 1, alpha: 0.7), .paragraphStyle: truncating,
        ]
        let titleSize = (title as NSString).boundingRect(with: CGSize(width: textWidth, height: 62 * 3), options: options, attributes: titleAttrs, context: nil).size
        let artistSize = (artist as NSString).boundingRect(with: CGSize(width: textWidth, height: 46 * 2), options: options, attributes: artistAttrs, context: nil).size
        let gap: CGFloat = artist.isEmpty ? 0 : 14
        var y = titleArt.midY - (titleSize.height + gap + artistSize.height) / 2
        (title as NSString).draw(with: CGRect(x: textX, y: y, width: textWidth, height: titleSize.height), options: options, attributes: titleAttrs, context: nil)
        y += titleSize.height + gap
        (artist as NSString).draw(with: CGRect(x: textX, y: y, width: textWidth, height: artistSize.height), options: options, attributes: artistAttrs, context: nil)
    }

    /// 歌詞の絵の上の、小さなジャケットの右の曲名とアーティスト。
    private static func drawLyricHeader(title: String, artist: String) {
        let textX = lyricArt.maxX + 28
        let headWidth = CGFloat(width) - textX - 56
        let titleAttrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 36, weight: .bold), .foregroundColor: UIColor.white, .paragraphStyle: truncating,
        ]
        let artistAttrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 28, weight: .regular), .foregroundColor: UIColor(white: 1, alpha: 0.7), .paragraphStyle: truncating,
        ]
        (title as NSString).draw(with: CGRect(x: textX, y: lyricArt.minY + 14, width: headWidth, height: 44), options: options, attributes: titleAttrs, context: nil)
        (artist as NSString).draw(with: CGRect(x: textX, y: lyricArt.minY + 62, width: headWidth, height: 36), options: options, attributes: artistAttrs, context: nil)
    }

    /// 歌っている行の高さ（3行まで）。
    private static func currentHeight(_ line: String) -> CGFloat {
        (line as NSString).boundingRect(with: CGSize(width: lyricWidth, height: 72 * 3), options: options, attributes: currentAttrs, context: nil).height
    }

    /// 次の行を置ける高さ（2行まで。下の端に近すぎれば置かない）。
    private static func nextRoom(at y: CGFloat) -> CGFloat? {
        y < CGFloat(height) - 60 ? min(52 * 2, CGFloat(height) - 30 - y) : nil
    }

    /// 次の行の上の端（歌っている行の下）。
    private static func nextTop(below current: String?) -> CGFloat {
        current.map { lyricTop + currentHeight($0) + lineGap } ?? lyricTop
    }

    private static func drawLines(_ lyric: LyricPair) {
        if let current = lyric.current {
            (current as NSString).draw(with: CGRect(x: lyricX, y: lyricTop, width: lyricWidth, height: currentHeight(current)), options: options, attributes: currentAttrs, context: nil)
        }
        let y = nextTop(below: lyric.current)
        if let next = lyric.next, let room = nextRoom(at: y) {
            (next as NSString).draw(with: CGRect(x: lyricX, y: y, width: lyricWidth, height: room), options: options, attributes: nextAttrs, context: nil)
        }
    }

    /// 行を送る途中の絵（progress が 0 で前の行の絵、1 で次の行の絵と同じになる）。
    private static func drawScroll(_ cg: CGContext, from old: LyricPair, to new: LyricPair, progress p: CGFloat) {
        cg.saveGState()
        // 上の曲名に重ならないよう、行はジャケットの下だけに描く
        cg.clip(to: CGRect(x: 0, y: lyricArt.maxY + 12, width: CGFloat(width), height: CGFloat(height) - lyricArt.maxY - 12))
        let rise = nextTop(below: old.current) - lyricTop
        // 歌っていた行は上へ抜けながら早めに消える
        if let current = old.current {
            cg.setAlpha(max(0, 1 - p * 1.6))
            (current as NSString).draw(with: CGRect(x: lyricX, y: lyricTop - rise * p, width: lyricWidth, height: currentHeight(current)), options: options, attributes: currentAttrs, context: nil)
        }
        // 次の行が上がりながら大きくなる（薄い字から濃い字へ重ねて入れ替える）
        if let line = new.current {
            let y = lyricTop + rise * (1 - p)
            let startRoom = nextRoom(at: lyricTop + rise) ?? 52 * 2
            let grow = currentSize / nextSize
            drawScaled(cg, line, attrs: nextAttrs, at: CGPoint(x: lyricX, y: y), scale: 1 + (grow - 1) * p, height: startRoom, alpha: 1 - p)
            drawScaled(cg, line, attrs: currentAttrs, at: CGPoint(x: lyricX, y: y), scale: 1 / grow + (1 - 1 / grow) * p, height: currentHeight(line), alpha: p)
        }
        // その次の行は下から浮かび上がる
        let y = nextTop(below: new.current)
        if let next = new.next, let room = nextRoom(at: y) {
            cg.setAlpha(p)
            (next as NSString).draw(with: CGRect(x: lyricX, y: y + 48 * (1 - p), width: lyricWidth, height: room), options: options, attributes: nextAttrs, context: nil)
        }
        cg.restoreGState()
    }

    /// 左上を軸に scale 倍した行（折り返しは scale 前の幅で決める）。
    private static func drawScaled(_ cg: CGContext, _ line: String, attrs: [NSAttributedString.Key: Any], at origin: CGPoint, scale: CGFloat, height: CGFloat, alpha: CGFloat) {
        guard alpha > 0 else { return }
        cg.saveGState()
        cg.setAlpha(alpha)
        cg.translateBy(x: origin.x, y: origin.y)
        cg.scaleBy(x: scale, y: scale)
        (line as NSString).draw(with: CGRect(x: 0, y: 0, width: lyricWidth, height: height), options: options, attributes: attrs, context: nil)
        cg.restoreGState()
    }

    private static func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat { a + (b - a) * t }

    private static func lerp(_ a: CGRect, _ b: CGRect, _ t: CGFloat) -> CGRect {
        CGRect(x: lerp(a.minX, b.minX, t), y: lerp(a.minY, b.minY, t), width: lerp(a.width, b.width, t), height: lerp(a.height, b.height, t))
    }

    /// 黒の地に、ぼかして暗くしたジャケットを敷く。
    private static func drawBackdrop(_ context: UIGraphicsImageRendererContext, artwork: UIImage?) {
        let rect = CGRect(x: 0, y: 0, width: width, height: height)
        UIColor.black.setFill()
        context.fill(rect)
        guard let artwork, let blurred = blurred(artwork) else { return }
        // 下敷き: ジャケットをガウスぼかしで滑らかにぼかし、横いっぱいに広げて暗くする
        // （小さく縮めてから広げるぼかしは、升目が見えてドットのようになった）
        let side = CGFloat(width)
        UIImage(cgImage: blurred).draw(in: CGRect(x: 0, y: (CGFloat(height) - side) / 2, width: side, height: side), blendMode: .normal, alpha: 0.6)
        UIColor(white: 0, alpha: 0.45).setFill()
        // fill(_:) だけだと置き換え（copy）で塗られ、下のジャケットが消える
        context.fill(rect, blendMode: .normal)
    }

    private static let ciContext = CIContext()

    /// ジャケットを小さめ（240px）にしてからガウスぼかしをかける（端は伸ばして、縁が暗く抜けないように）。
    private static var lastBlur: (source: UIImage, blurred: CGImage)?

    private static func blurred(_ artwork: UIImage) -> CGImage? {
        // 歌詞の行が変わるたびに描き直すので、同じジャケットのぼかしは使い回す
        if let lastBlur, lastBlur.source === artwork { return lastBlur.blurred }
        guard let cg = artwork.cgImage else { return nil }
        let size: CGFloat = 240
        var image = CIImage(cgImage: cg)
        image = image.transformed(by: CGAffineTransform(scaleX: size / image.extent.width, y: size / image.extent.height))
        let extent = image.extent
        let blur = image.clampedToExtent().applyingGaussianBlur(sigma: 18).cropped(to: extent)
        guard let result = ciContext.createCGImage(blur, from: extent) else { return nil }
        lastBlur = (artwork, result)
        return result
    }

    /// 角を丸めたジャケット（無ければ音符）。
    private static func drawArtwork(_ context: UIGraphicsImageRendererContext, artwork: UIImage?, in art: CGRect, cornerRadius: CGFloat, notePoint: CGFloat) {
        context.cgContext.saveGState()
        UIBezierPath(roundedRect: art, cornerRadius: cornerRadius).addClip()
        if let artwork {
            artwork.draw(in: art)
        } else {
            UIColor(white: 0.2, alpha: 1).setFill()
            context.fill(art)
            let note = UIImage(systemName: "music.note", withConfiguration: UIImage.SymbolConfiguration(pointSize: notePoint, weight: .medium))?
                .withTintColor(UIColor(white: 0.6, alpha: 1), renderingMode: .alwaysOriginal)
            if let note { note.draw(at: CGPoint(x: art.midX - note.size.width / 2, y: art.midY - note.size.height / 2)) }
        }
        context.cgContext.restoreGState()
    }

    private static func sampleBuffer(_ image: UIImage, width: Int, height: Int) -> CMSampleBuffer? {
        guard let cgImage = image.cgImage else { return nil }
        let attrs: [CFString: Any] = [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary, kCVPixelBufferCGImageCompatibilityKey: true]
        var pixelBuffer: CVPixelBuffer?
        guard CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, attrs as CFDictionary, &pixelBuffer) == kCVReturnSuccess,
              let pixelBuffer else { return nil }
        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        let context = CGContext(data: CVPixelBufferGetBaseAddress(pixelBuffer), width: width, height: height, bitsPerComponent: 8,
                                bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer), space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        context?.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        CVPixelBufferUnlockBaseAddress(pixelBuffer, [])

        var formatDescription: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pixelBuffer, formatDescriptionOut: &formatDescription) == noErr,
              let formatDescription else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: .zero, decodeTimeStamp: .invalid)
        var sampleBuffer: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pixelBuffer, formatDescription: formatDescription,
                                                       sampleTiming: &timing, sampleBufferOut: &sampleBuffer) == noErr, let sampleBuffer else { return nil }
        // 時刻に関わらず、すぐ出す
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: true), CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(), Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sampleBuffer
    }

    // MARK: PiP の操作

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, setPlaying playing: Bool) {
        MainActor.assumeIsolated {
            guard let player = AppServices.player, player.isPlaying != playing else { return }
            player.togglePlay()
        }
    }

    nonisolated func pictureInPictureControllerTimeRangeForPlayback(_ controller: AVPictureInPictureController) -> CMTimeRange {
        MainActor.assumeIsolated {
            let player = AppServices.player!
            // ライブ配信は長さが無い（PiP は「ライブ」の扱いになり、位置の棒を出さない）
            guard player.current?.isLive != true, player.duration > 0 else { return CMTimeRange(start: .negativeInfinity, duration: .positiveInfinity) }
            return CMTimeRange(start: .zero, duration: CMTime(seconds: player.duration, preferredTimescale: 600))
        }
    }

    nonisolated func pictureInPictureControllerIsPlaybackPaused(_ controller: AVPictureInPictureController) -> Bool {
        MainActor.assumeIsolated { !AppServices.player.isPlaying }
    }

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, didTransitionToRenderSize newRenderSize: CMVideoDimensions) {}

    /// 送り・戻しのボタン: 秒で動かす代わりに、次の曲・前の曲へ（ロック画面の前の曲と同じく、3秒より後なら曲の頭へ戻る）。
    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, skipByInterval skipInterval: CMTime, completion completionHandler: @escaping () -> Void) {
        MainActor.assumeIsolated {
            let player = AppServices.player!
            if skipInterval.seconds >= 0 { player.next() } else { player.previous() }
            completionHandler()
        }
    }

    nonisolated func pictureInPictureControllerShouldProhibitBackgroundAudioPlayback(_ controller: AVPictureInPictureController) -> Bool { false }

    nonisolated func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated {
            active = true
            AppPaths.log("music pip start")
        }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated {
            active = false
            AppPaths.log("music pip stop")
        }
    }

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        MainActor.assumeIsolated { AppPaths.log("music pip failed \(error.localizedDescription)") }
    }

    /// PiP の「元に戻す」: 再生画面を開く。
    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        MainActor.assumeIsolated {
            IOSShell.shared.playerOpen = true
            completionHandler(true)
        }
    }
}

/// 音楽の PiP の元になる枠。PiP は画面に置かれた枠からしか始められないので、画面の枠の後ろに小さく置く（見えない）。
struct MusicPipAnchor: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let host = UIView()
        host.isUserInteractionEnabled = false
        let surface = MusicPip.shared.surface
        surface.frame = host.bounds
        surface.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.addSubview(surface)
        return host
    }

    func updateUIView(_ view: UIView, context: Context) {}
}
#endif
