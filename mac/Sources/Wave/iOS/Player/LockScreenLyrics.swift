#if os(iOS)
import Foundation
import WaveCore

/// ロック画面に出す、今歌っている歌詞の行と次の行（時刻付きの歌詞があるときだけ）。
/// 行が変わったら、再生中の情報（アーティスト名の行に今の行を出す）とロック画面の再生中（Live Activity）を知らせ直す。
///
/// 見張るのは鳴っている間だけ、4 回／秒。止めている間は行が動かないので見ない。
/// 普通の Timer は画面が消えている間に間引かれて行が止まるので、間引かれない GCD のタイマー（strict）で回す。
@MainActor final class LockScreenLyrics {
    static let shared = LockScreenLyrics()

    /// 今の行（間奏の「♪」と、最初の行より前は nil）と次の行。
    private(set) var current: String?
    private(set) var next: String?

    private var timer: DispatchSourceTimer?

    /// 再生・一時停止・曲・歌詞・設定が変わったら呼ぶ（見張るかを決め直し、すぐ今の行を求める）。
    func refresh() {
        let on = AppServices.settings.lockScreenLyrics && syncedLines() != nil
        let playing = AppServices.player?.isPlaying ?? false
        setTicking(on && playing)
        tick()
    }

    private func syncedLines() -> [LyricLine]? {
        guard let player = AppServices.player, let song = player.current, !song.isLive,
              case .found(.synced(let s))? = player.lyrics, !s.lines.isEmpty else { return nil }
        return s.lines
    }

    private func tick() {
        var line: (current: String?, next: String?) = (nil, nil)
        if AppServices.settings.lockScreenLyrics, let lines = syncedLines(), let player = AppServices.player {
            let positionMs = player.isRemote ? Int64(player.position * 1000) : player.localPositionMs
            line = LyricsTiming.lockScreen(lines, positionMs: positionMs)
        }
        guard line.current != current || line.next != next else { return }
        current = line.current
        next = line.next
        AppServices.player?.lockScreenLineChanged()
        WidgetSync.shared.lockScreenLineChanged()
    }

    private func setTicking(_ on: Bool) {
        guard on != (timer != nil) else { return }
        guard on else {
            timer?.cancel()
            timer = nil
            return
        }
        let t = DispatchSource.makeTimerSource(flags: .strict, queue: .main)
        t.schedule(deadline: .now() + 0.25, repeating: 0.25, leeway: .milliseconds(10))
        t.setEventHandler { MainActor.assumeIsolated { LockScreenLyrics.shared.tick() } }
        t.resume()
        timer = t
    }
}
#endif
