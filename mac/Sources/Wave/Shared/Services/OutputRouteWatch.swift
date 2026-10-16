import AVFoundation
import Foundation
#if os(macOS)
import CoreAudio
#endif

/// 音の出口（AirPods など）が最後に変わった時刻を覚える。AirPods がほかの機械へ自動で移ると、OS は鳴っているアプリへ一時停止を送る。
/// ほかの端末を映しているときにそれを遠隔の一時停止として送ると、AirPods を持っていった機械の再生を止めてしまうので、見分けに使う。
@MainActor
final class OutputRouteWatch {
    static let shared = OutputRouteWatch()

    /// 出口が変わってから、OS からの一時停止をその変化によるものとみなす時間。
    static let windowMs: Int64 = 2_000

    private var changedAt: Date?

    private init() {
        #if os(macOS)
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice, mScope: kAudioObjectPropertyScopeGlobal,
                                                 mElement: kAudioObjectPropertyElementMain)
        AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, .main) { _, _ in
            MainActor.assumeIsolated { OutputRouteWatch.shared.changed() }
        }
        #else
        NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
            guard reason == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated { OutputRouteWatch.shared.changed() }
        }
        #endif
    }

    /// 見張りを始める（起動のときに1回呼ぶ）。
    func start() {}

    private func changed() {
        changedAt = Date()
        AppPaths.log("output route changed")
    }

    /// 出口が変わったばかりか。
    var justChanged: Bool {
        guard let changedAt else { return false }
        return Date().timeIntervalSince(changedAt) * 1000 < Double(OutputRouteWatch.windowMs)
    }
}
