#if os(iOS)
import AVFoundation
import MediaPlayer
import SwiftUI
import UIKit

/// iPhone の本体の音量（音量ボタンと同じもの）。アプリからは、画面に置いた MPVolumeView のつまみを通してだけ変えられるので、
/// 見えない MPVolumeView を置き、そのつまみを動かす。今の値は AVAudioSession の outputVolume を見張る。
@MainActor @Observable
final class SystemVolume {
    static let shared = SystemVolume()

    private(set) var value: Float = AVAudioSession.sharedInstance().outputVolume
    @ObservationIgnored private var observation: NSKeyValueObservation?
    @ObservationIgnored fileprivate weak var slider: UISlider?

    private init() {
        observation = AVAudioSession.sharedInstance().observe(\.outputVolume, options: [.new]) { _, change in
            guard let v = change.newValue else { return }
            DispatchQueue.main.async { MainActor.assumeIsolated { SystemVolume.shared.value = v } }
        }
    }

    var hasSlider: Bool { slider != nil }

    func set(_ v: Float) {
        let clamped = min(max(v, 0), 1)
        value = clamped
        slider?.setValue(clamped, animated: false)
        slider?.sendActions(for: .valueChanged)
    }
}

/// 見えない MPVolumeView（これが画面にある間は、つまみで本体の音量を変えられ、OS の音量の表示も出ない）。
struct SystemVolumeAnchor: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        let view = MPVolumeView(frame: CGRect(x: -100, y: -100, width: 10, height: 10))
        view.alpha = 0.01
        view.isUserInteractionEnabled = false
        SystemVolume.shared.slider = view.subviews.compactMap { $0 as? UISlider }.first
        return view
    }

    func updateUIView(_ view: MPVolumeView, context: Context) {
        if SystemVolume.shared.slider == nil { SystemVolume.shared.slider = view.subviews.compactMap { $0 as? UISlider }.first }
    }
}
#endif
