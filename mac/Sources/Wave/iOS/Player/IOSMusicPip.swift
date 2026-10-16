#if os(iOS)
import AVKit
import CoreMedia
import SwiftUI
import UIKit
import WaveCore

/// 音楽の PiP（映像を出していない曲でも、ホームへ戻ると小窓に曲名・アーティスト・ジャケットを出す）。
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
    /// 絵にした曲（同じ曲・同じジャケットなら描き直さない）と、読み込んだジャケット。
    private var drawnKey: String?
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
        // wave に戻ったら小窓は閉じる（画面に同じ曲が出ているので）
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { MusicPip.shared.stop() }
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
        draw(song)
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

    private func draw(_ song: Song) {
        if artworkFor != song.videoId {
            artworkFor = song.videoId
            artwork = nil
            loadArtwork(song)
        }
        let key = "\(song.videoId)|\(song.title)|\(song.artistsText)|\(artwork != nil)"
        guard key != drawnKey else { return }
        drawnKey = key
        guard let buffer = Self.render(title: song.title, artist: song.artistsText, artwork: artwork) else { return }
        let renderer = surface.displayLayer.sampleBufferRenderer
        renderer.flush(removingDisplayedImage: false, completionHandler: nil)
        renderer.enqueue(buffer)
    }

    private func loadArtwork(_ song: Song) {
        guard let url = Ui.resize(song.thumbnailUrl, 544), url.scheme == "https" else { return }
        Task {
            guard let (data, _) = try? await ImageLoader.session.data(from: url), let image = UIImage(data: data) else { return }
            guard artworkFor == song.videoId else { return }
            artwork = image
            if let current = AppServices.player.current, current.videoId == song.videoId { draw(current) }
        }
    }

    /// 16:9 の絵（ぼかしたジャケットを下に敷き、左にジャケット、右に曲名とアーティスト）を、PiP に渡す形にする。
    private static func render(title: String, artist: String, artwork: UIImage?) -> CMSampleBuffer? {
        let width = 960, height = 540
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let image = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { context in
            let rect = CGRect(x: 0, y: 0, width: width, height: height)
            UIColor.black.setFill()
            context.fill(rect)
            if let artwork {
                // 下敷き: ジャケットを大きく広げて暗くする
                let side = CGFloat(width)
                artwork.draw(in: CGRect(x: 0, y: (CGFloat(height) - side) / 2, width: side, height: side), blendMode: .normal, alpha: 0.45)
                UIColor(white: 0, alpha: 0.45).setFill()
                context.fill(rect)
            }
            let art = CGRect(x: 60, y: 90, width: 360, height: 360)
            let clip = UIBezierPath(roundedRect: art, cornerRadius: 24)
            context.cgContext.saveGState()
            clip.addClip()
            if let artwork {
                artwork.draw(in: art)
            } else {
                UIColor(white: 0.2, alpha: 1).setFill()
                context.fill(art)
                let note = UIImage(systemName: "music.note", withConfiguration: UIImage.SymbolConfiguration(pointSize: 140, weight: .medium))?
                    .withTintColor(UIColor(white: 0.6, alpha: 1), renderingMode: .alwaysOriginal)
                if let note { note.draw(at: CGPoint(x: art.midX - note.size.width / 2, y: art.midY - note.size.height / 2)) }
            }
            context.cgContext.restoreGState()

            let paragraph = NSMutableParagraphStyle()
            paragraph.lineBreakMode = .byTruncatingTail
            let textX = art.maxX + 44
            let textWidth = CGFloat(width) - textX - 50
            let titleAttrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 52, weight: .bold), .foregroundColor: UIColor.white, .paragraphStyle: paragraph,
            ]
            let artistAttrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 38, weight: .regular), .foregroundColor: UIColor(white: 1, alpha: 0.7), .paragraphStyle: paragraph,
            ]
            // 曲名は3行まで、アーティストは2行まで。2つをまとめて縦の真ん中に置く
            let titleSize = (title as NSString).boundingRect(with: CGSize(width: textWidth, height: 62 * 3), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], attributes: titleAttrs, context: nil).size
            let artistSize = (artist as NSString).boundingRect(with: CGSize(width: textWidth, height: 46 * 2), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], attributes: artistAttrs, context: nil).size
            let gap: CGFloat = artist.isEmpty ? 0 : 14
            var y = art.midY - (titleSize.height + gap + artistSize.height) / 2
            (title as NSString).draw(with: CGRect(x: textX, y: y, width: textWidth, height: titleSize.height), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], attributes: titleAttrs, context: nil)
            y += titleSize.height + gap
            (artist as NSString).draw(with: CGRect(x: textX, y: y, width: textWidth, height: artistSize.height), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], attributes: artistAttrs, context: nil)
        }
        return sampleBuffer(image, width: width, height: height)
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
