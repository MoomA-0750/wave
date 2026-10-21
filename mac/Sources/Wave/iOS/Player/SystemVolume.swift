#if os(iOS)
import AVFoundation
import MediaPlayer
import SwiftUI
import UIKit

/// iPhone の本体の音量（音量ボタンと同じもの）。アプリからは、画面に置いた MPVolumeView のつまみを通してだけ変えられるので、
fileprivate struct WeakSlider {
    weak var value: UISlider?
}

/// 見えない MPVolumeView を置き、そのつまみを動かす。今の値は AVAudioSession の outputVolume を見張る。
/// この iPhone で鳴らしている間は、ほかの端末から届いた音量もここへ入れ、変わった値はほかの端末のつまみへ映す（PlayerService.systemVolumeChanged）。
@MainActor @Observable
final class SystemVolume {
    static let shared = SystemVolume()

    private(set) var value: Float = AVAudioSession.sharedInstance().outputVolume
    @ObservationIgnored private var observation: NSKeyValueObservation?
    /// 置いた MPVolumeView のつまみ。再生画面は全画面で重ねるので、その間は元の画面が窓から外れる。窓に載っているものを使う
    @ObservationIgnored fileprivate var sliders: [WeakSlider] = []
    private var slider: UISlider? {
        sliders.removeAll { $0.value == nil }
        return sliders.first { $0.value?.window != nil }?.value ?? sliders.first?.value
    }

    fileprivate func attach(_ slider: UISlider?) {
        guard let slider, !sliders.contains(where: { $0.value === slider }) else { return }
        sliders.append(WeakSlider(value: slider))
    }

    private init() {
        observation = AVAudioSession.sharedInstance().observe(\.outputVolume, options: [.new]) { _, change in
            guard let v = change.newValue else { return }
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    SystemVolume.shared.value = v
                    AppServices.player?.systemVolumeChanged(v)
                }
            }
        }
    }

    var hasSlider: Bool { slider != nil }

    @ObservationIgnored private var settle: Task<Void, Never>?

    func set(_ v: Float) {
        // 端の近くは端にする（指で左端まで寄せても 0 ちょうどにならないことがある）
        let clamped = v < 0.01 ? 0 : v > 0.99 ? 1 : min(max(v, 0), 1)
        value = clamped
        apply(clamped)
        // つまみを速く動かすと OS が途中の値を間引き、最後の値（左端の 0 など）が届かずに 1% ほど残ることがある。
        // 動かし終えて少し待ち、OS の音量が違っていれば送り直す
        settle?.cancel()
        settle = Task { [weak self] in
            for _ in 0..<3 {
                try? await Task.sleep(for: .milliseconds(250))
                guard !Task.isCancelled, let self else { return }
                if abs(AVAudioSession.sharedInstance().outputVolume - clamped) < 0.001 { return }
                self.apply(clamped)
            }
        }
    }

    private func apply(_ v: Float) {
        slider?.setValue(v, animated: false)
        slider?.sendActions(for: .valueChanged)
    }
}

/// 見えない MPVolumeView（これが画面にある間は、つまみで本体の音量を変えられ、OS の音量の表示も出ない）。
struct SystemVolumeAnchor: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        let view = MPVolumeView(frame: CGRect(x: -100, y: -100, width: 10, height: 10))
        view.alpha = 0.01
        view.isUserInteractionEnabled = false
        SystemVolume.shared.attach(view.subviews.compactMap { $0 as? UISlider }.first)
        return view
    }

    func updateUIView(_ view: MPVolumeView, context: Context) {
        SystemVolume.shared.attach(view.subviews.compactMap { $0 as? UISlider }.first)
    }
}
#endif
