#if os(iOS)
import AVKit
import Observation
import SwiftUI
import UIKit

/// 映像の PiP（iPhone の小窓）。映像を出す枠は1つの AVPlayerLayer を使い回し（再生画面・全画面のどちらに出ていても同じ枠）、
/// PiP はその枠から始める。映像を出したままホームへ戻ると、自動で PiP になり、wave に戻ると PiP を閉じて再生画面で映像を出す。
@MainActor @Observable
final class VideoPip: NSObject, AVPictureInPictureControllerDelegate {
    static let shared = VideoPip()

    /// PiP で映しているか。
    private(set) var active = false
    var supported: Bool { AVPictureInPictureController.isPictureInPictureSupported() }

    @ObservationIgnored let surface = SurfaceView()
    @ObservationIgnored private var controller: AVPictureInPictureController?
    /// 枠を置ける場所（後から画面に出たものが上）。上の場所が消えたら、下の場所へ枠を戻す。
    @ObservationIgnored private var hosts: [HostView] = []
    /// 裏へ回ったか（戻ったときだけ PiP を閉じる。wave の中で小窓のボタンから出した PiP は、通知の欄を下ろしただけでは閉じない）。
    @ObservationIgnored private var wentBackground = false

    final class SurfaceView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    /// 枠を置く場所（SwiftUI の中に置く）。
    final class HostView: UIView {
        /// 大きいほうへ枠を置く（全画面は、後ろの再生画面が向きの変化で枠を作り直しても、枠を取られない）。
        var priority = 0

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window != nil { VideoPip.shared.attach(self) } else { VideoPip.shared.detach(self) }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            for view in subviews { view.frame = bounds }
        }
    }

    override init() {
        super.init()
        surface.playerLayer.videoGravity = .resizeAspect
        surface.backgroundColor = .black
        // MV を PiP で映している間、PiP の再生・一時停止のボタンは音のほうを動かす（映像は音に合わせて付いてくる）
        MusicVideoPlayer.shared.onExternalPlayPause = { [weak self] playing in
            guard self?.active == true, let player = AppServices.player, player.isPlaying != playing else { return }
            player.togglePlay()
        }
        NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { VideoPip.shared.wentBackground = true }
        }
        // wave に戻ったら PiP を閉じ、再生画面で映像を出す（YouTube と同じ）
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { VideoPip.shared.returnedToApp() }
        }
    }

    private func returnedToApp() {
        defer { wentBackground = false }
        guard wentBackground, active, let controller, controller.isPictureInPictureActive else { return }
        AppPaths.log("video pip back to app")
        IOSShell.shared.showVideo = true
        IOSShell.shared.playerOpen = true
        controller.stopPictureInPicture()
    }

    /// 映す再生器（ライブは鳴らしている再生器、MV は音の無い再生器）。
    func setPlayer(_ player: AVPlayer?) {
        if surface.playerLayer.player !== player { surface.playerLayer.player = player }
        if player != nil { ensureController() }
    }

    /// 開発用: 小窓を始められる状態か。
    var debugState: String {
        "controller=\(controller != nil) possible=\(controller?.isPictureInPicturePossible ?? false) player=\(surface.playerLayer.player != nil) ready=\(surface.playerLayer.isReadyForDisplay) inWindow=\(surface.window != nil) hosts=\(hosts.count)"
    }

    func toggle() {
        if active { controller?.stopPictureInPicture() } else { start() }
    }

    func start() {
        ensureController()
        controller?.startPictureInPicture()
    }

    private func ensureController() {
        guard controller == nil, supported, let controller = AVPictureInPictureController(playerLayer: surface.playerLayer) else { return }
        controller.delegate = self
        // 映像を出したままホームへ戻ったら、自動で PiP にする
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        self.controller = controller
    }

    private func attach(_ host: HostView) {
        hosts.removeAll { $0 === host }
        hosts.append(host)
        place()
    }

    private func detach(_ host: HostView) {
        hosts.removeAll { $0 === host }
        if surface.superview === host { surface.removeFromSuperview() }
        place()
    }

    private func place() {
        // 優先が同じなら、後から画面に出たもの
        guard let top = hosts.enumerated().max(by: { ($0.element.priority, $0.offset) < ($1.element.priority, $1.offset) })?.element,
              surface.superview !== top else { return }
        surface.removeFromSuperview()
        surface.frame = top.bounds
        top.addSubview(surface)
    }

    nonisolated func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated {
            active = true
            AppPaths.log("video pip start")
        }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated {
            active = false
            AppPaths.log("video pip stop")
            // 再生画面を閉じていれば、MV の読み込みもやめる
            if !IOSShell.shared.playerOpen {
                IOSShell.shared.showVideo = false
                MusicVideoPlayer.shared.setActive(nil)
            }
        }
    }

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        MainActor.assumeIsolated { AppPaths.log("video pip failed \(error.localizedDescription)") }
    }

    /// PiP の「元に戻す」: 再生画面を開いて、そこで映像を出す。
    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        MainActor.assumeIsolated {
            IOSShell.shared.playerOpen = true
            completionHandler(true)
        }
    }
}

/// 映像の枠（VideoPip の1つの枠を、ここに置く）。
struct PipSurface: UIViewRepresentable {
    var player: AVPlayer?
    var priority = 0

    func makeUIView(context: Context) -> VideoPip.HostView {
        let view = VideoPip.HostView()
        view.priority = priority
        return view
    }

    func updateUIView(_ view: VideoPip.HostView, context: Context) {
        VideoPip.shared.setPlayer(player)
    }
}
#endif
