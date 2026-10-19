#if os(macOS)
import SwiftUI
import WaveCore

/// 歌詞（Spotify と同じく中央の枠いっぱいに、歌っている行を明るく、過ぎた行と先の行は薄く）。行を押すとその位置へ。
struct LyricsPageView: View {
    @State private var backdrop: Color?

    var body: some View {
        let player = AppServices.player!
        VStack(spacing: 0) {
            ZStack {
                if player.current == nil {
                    empty(L.t("曲を再生すると、ここに歌詞が出ます", "Play a song to see its lyrics here"))
                } else if player.isLive {
                    empty(L.t("ライブ配信には歌詞がありません", "Live streams don't have lyrics"))
                } else {
                    switch player.lyrics {
                    case nil:
                        empty(L.t("歌詞を探しています…", "Looking for lyrics…"))
                    case .found(.synced(let synced)):
                        SyncedPage(lines: synced.lines, positionMs: Int64(player.position * 1000))
                    case .found(.plain(let plain)):
                        ScrollView {
                            Text(plain.text).font(.system(size: 26, weight: .semibold)).lineSpacing(14).textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading).padding(40)
                        }
                    case .some(.none):
                        empty(L.t("この曲の歌詞は見つかりませんでした", "No lyrics found for this song"))
                    default:
                        // 読み込みに失敗したときだけ（Android と同じ）
                        VStack(spacing: 8) {
                            Text(L.t("歌詞を読み込めませんでした", "Couldn't load lyrics")).subtitleStyle()
                            Button(L.t("もう一度", "Retry")) { player.reloadLyrics() }
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // 出典は歌詞に重ねず、下の段に置く
            Text(source(player.lyrics)).subtle().frame(maxWidth: .infinity, alignment: .leading).padding(EdgeInsets(top: 8, leading: 40, bottom: 12, trailing: 40))
        }
        .background {
            LinearGradient(colors: [(backdrop ?? .clear).opacity(0.67), (backdrop ?? .clear).opacity(0.16)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .top)
        }
        .task(id: player.current?.videoId) { backdrop = await Artwork.dominantColor(player.current?.thumbnailUrl) }
    }

    private func source(_ result: LyricsService.Result?) -> String {
        guard case .found(let lyrics)? = result else { return "" }
        switch lyrics {
        case .synced(let s): return L.t("歌詞の提供: ", "Lyrics provided by ") + (s.source ?? "")
        case .plain(let p): return p.source ?? ""
        }
    }

    private func empty(_ text: String) -> some View { Text(text).subtitleStyle() }
}

private struct SyncedPage: View {
    var lines: [LyricLine]
    var positionMs: Int64

    var body: some View {
        let last = LyricsTiming.currentIndex(lines, positionMs: positionMs)
        // 同じ時刻の行（原文と読み・訳が並ぶ歌詞）は、まとめて今の行とする（Android と同じ）
        let index = last < 0 ? last : (lines.firstIndex { $0.startMs == lines[last].startMs } ?? last)
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                        // 同じ時刻の行（原文に続く読み・訳）は、前の行に添える小さな行として出す
                        let isSub = i > 0 && lines[i - 1].startMs == line.startMs
                        let now = index >= 0 && i >= index && i <= last
                        Text(line.text.trimmingCharacters(in: .whitespaces).isEmpty ? "♪" : line.text)
                            .font(.system(size: isSub ? 21 : 30, weight: isSub ? .semibold : .bold))
                            .lineSpacing(isSub ? 6 : 9)
                            .opacity(now ? 1 : i < index ? 0.55 : 0.4)
                            .scaleEffect(now ? 1 : 0.96, anchor: .leading)
                            .animation(.easeOut(duration: 0.35), value: now)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.top, isSub ? 2 : 22)
                            .contentShape(Rectangle())
                            .onTapGesture { AppServices.player.seek(Double(line.startMs) / 1000) }
                            .id(i)
                    }
                }
                .padding(EdgeInsets(top: 40, leading: 40, bottom: 240, trailing: 40))
            }
            // 歌っている行を真ん中より少し上に寄せる
            .onChange(of: index, initial: true) { _, i in
                guard i >= 0 else { return }
                withAnimation(.easeInOut(duration: 0.45)) { proxy.scrollTo(i, anchor: UnitPoint(x: 0, y: 0.35)) }
            }
        }
    }
}
#endif
