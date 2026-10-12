#if os(iOS)
import AVFoundation
import WaveCore

/// ほかの端末の再生を映している間、音の無い音を流し続ける。
/// iOS は、この端末で音を鳴らしているアプリにしかロック画面・コントロールセンターの操作を出さず、
/// 鳴らしていないアプリは裏へ回ると止める（中継とのつながりも切れて、ほかの端末の状態を追えなくなる）。
/// Android の「ほかの端末の再生も通知に出す」と同じにするため。一時停止のまま9分（Android の再生サービスと同じ）置かれたら止める（電池のため）。
@MainActor
final class RemoteKeepAlive {
    static let shared = RemoteKeepAlive()
    /// ほかの端末が一時停止のまま、この長さを過ぎたら止める。
    static let pausedLimit: TimeInterval = 9 * 60

    private var audio: AVAudioPlayer?
    private var timer: Timer?
    private var pausedSince: Date?

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            MainActor.assumeIsolated { RemoteKeepAlive.shared.tick() }
        }
    }

    private func tick() {
        guard let player = AppServices.player else { return }
        let following = player.isRemote && player.current != nil
        if following && !player.isPlaying {
            pausedSince = pausedSince ?? Date()
        } else {
            pausedSince = nil
        }
        let keep = following && (pausedSince.map { Date().timeIntervalSince($0) < RemoteKeepAlive.pausedLimit } ?? true)
        if keep, audio == nil {
            begin()
        } else if !keep, audio != nil {
            end()
        }
    }

    private func begin() {
        do {
            try AVAudioSession.sharedInstance().setActive(true)
            let silent = try AVAudioPlayer(data: RemoteKeepAlive.silentWav())
            silent.numberOfLoops = -1
            silent.play()
            audio = silent
            AppPaths.log("remote keepalive start")
        } catch {
            AppPaths.log("remote keepalive failed")
        }
    }

    private func end() {
        audio?.stop()
        audio = nil
        AppPaths.log("remote keepalive stop")
    }

    /// 1秒の無音（8kHz・1ch・16bit の WAV）。
    private static func silentWav() -> Data {
        let rate: UInt32 = 8000, samples = 8000
        let bytes = UInt32(samples * 2)
        var data = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) } }
        data.append(contentsOf: Array("RIFF".utf8)); u32(36 + bytes)
        data.append(contentsOf: Array("WAVEfmt ".utf8)); u32(16); u16(1); u16(1); u32(rate); u32(rate * 2); u16(2); u16(16)
        data.append(contentsOf: Array("data".utf8)); u32(bytes)
        data.append(Data(count: Int(bytes)))
        return data
    }
}
#endif
