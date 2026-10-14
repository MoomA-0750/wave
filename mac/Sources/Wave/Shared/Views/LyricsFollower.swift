import SwiftUI
import WaveCore

/// 再生位置に合わせて送る歌詞（Spotify の再生中の画面と同じく、歌っている行を明るく、前後の行を薄く）。
/// 時刻付きの歌詞では全文を並べ、歌っているかたまりの1つ前が上端に来るように滑らかに送る。
/// 右の欄の小さな歌詞と、全画面の大きな歌詞で使う（字の大きさだけ違う）。Windows の LyricsFollower と同じ。
struct LyricsFollower: View {
    var lyrics: SongLyrics
    var positionMs: Int64
    var fontSize: CGFloat
    var plainLines = 12
    /// 中で送るか（右の欄・全画面は送る。押せない）。
    var height: CGFloat?
    /// 手でスクロールできるか（全画面）。時刻付きの歌詞は、手で動かしてしばらくは自動で送らない。
    var scrollable = false

    var body: some View {
        switch lyrics {
        case .synced(let s):
            SyncedLyrics(lines: s.lines, positionMs: positionMs, fontSize: fontSize, scrollable: scrollable)
        case .plain(let p):
            let text = VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(p.text.split(separator: "\n", omittingEmptySubsequences: false).prefix(plainLines).enumerated()), id: \.offset) { _, line in
                    Text(String(line)).font(.system(size: fontSize, weight: .semibold)).lineSpacing(fontSize * 0.375)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if scrollable {
                // 枠の高さに収め、はみ出した分はスクロールで読む（並べたままだと高さが窓を超えて、隣の並びを押し出す）
                ScrollView(.vertical, showsIndicators: false) { text.padding(.bottom, 40) }
            } else {
                text
            }
        }
    }
}

private struct SyncedLyrics: View {
    var lines: [LyricLine]
    var positionMs: Int64
    var fontSize: CGFloat
    var scrollable: Bool
    /// 本人が手でスクロールした時刻（その後しばらくは自動で送らない）。
    @State private var touchedAt = Date.distantPast

    var body: some View {
        let last = LyricsTiming.currentIndex(lines, positionMs: positionMs)
        // 同じ時刻の行（原文と読み・訳が並ぶ歌詞）は、まとめて今の行とする
        let i = last < 0 ? last : (lines.firstIndex { $0.startMs == lines[last].startMs } ?? last)
        let anchor = i <= 0 ? 0 : (lines.firstIndex { $0.startMs == lines[max(0, i - 1)].startMs } ?? 0)
        ScrollViewReader { proxy in
            ScrollView(.vertical, showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(lines.enumerated()), id: \.offset) { k, line in
                        // 原文に続く読み・訳は、小さく原文の直下に添える。かたまりの間は空ける
                        let isSub = k > 0 && lines[k - 1].startMs == line.startMs
                        let size = isSub ? fontSize * 0.8 : fontSize
                        Text(line.text.trimmingCharacters(in: .whitespaces).isEmpty ? "♪" : line.text)
                            .font(.system(size: size, weight: isSub ? .regular : .semibold))
                            .lineSpacing(size * 0.375)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .opacity(i >= 0 && k >= i && k <= last ? 1 : k < i ? 0.5 : 0.4)
                            .animation(.easeOut(duration: 0.35), value: i)
                            .padding(.top, k == 0 ? 0 : isSub ? 1 : fontSize * 0.6)
                            .id(k)
                    }
                    Color.clear.frame(height: 400)
                }
            }
            .scrollDisabled(!scrollable)
            .onScrollPhaseChange { _, phase in
                if phase == .interacting || phase == .decelerating { touchedAt = Date() }
            }
            .onChange(of: anchor, initial: true) { _, a in
                // 手で動かしてから 5 秒は、読んでいるところを動かさない
                guard Date().timeIntervalSince(touchedAt) > 5 else { return }
                withAnimation(.easeInOut(duration: 0.5)) { proxy.scrollTo(a, anchor: .top) }
            }
            .onChange(of: lines.count) { _, _ in proxy.scrollTo(0, anchor: .top) }
        }
    }
}
