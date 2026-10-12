#if os(iOS)
import SwiftUI
import WaveCore

/// 歌詞の全画面（Android の LyricsPanel・Mac の LyricsPage と同じ）。ジャケットの色の地に大きな字で、
/// 時刻付きなら歌っている行を明るくして追い、行を押すとその位置へ。本人が送ったら、しばらく自動で追わない。
/// 出典（LRCLIB など）は歌詞の最後に小さく出す（Android と同じ）。下に小さな再生の操作。
struct LyricsView: View {
    @State private var tint: Color?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let player = AppServices.player!
        let song = player.current
        VStack(spacing: 0) {
            header(song)
            content(player)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            LyricsMiniControls()
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
        }
        .background {
            LinearGradient(colors: [(tint ?? .gray).opacity(0.95), (tint ?? .gray).opacity(0.55), .black], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .animation(.easeOut(duration: 0.4), value: tint)
        }
        .environment(\.colorScheme, .dark)
        .task(id: song?.thumbnailUrl) { tint = await Artwork.dominantColor(song?.thumbnailUrl) }
        .presentationDragIndicator(.visible)
    }

    private func header(_ song: Song?) -> some View {
        HStack(spacing: 12) {
            Cover(url: Ui.small(song?.thumbnailUrl), size: 44, radius: 4)
            VStack(alignment: .leading, spacing: 2) {
                Text(song?.title ?? "").font(.headline).lineLimit(1)
                Text(song?.artistsText ?? "").subtle()
            }
            Spacer(minLength: 0)
            Button { dismiss() } label: {
                Image(systemName: "chevron.down").font(.body.weight(.semibold)).frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .glassCard(22, interactive: true)
            .accessibilityLabel(L.t("閉じる", "Close"))
        }
        .padding(.horizontal, 20)
        .padding(.top, 16)
        .padding(.bottom, 8)
    }

    @ViewBuilder
    private func content(_ player: PlayerService) -> some View {
        if player.current == nil {
            message(L.t("曲を再生すると、ここに歌詞が出ます", "Play a song to see its lyrics here"))
        } else if player.isLive {
            message(L.t("ライブ配信には歌詞がありません", "Live streams don't have lyrics"))
        } else {
            switch player.lyrics {
            case nil:
                message(L.t("歌詞を探しています…", "Looking for lyrics…"))
            case .found(.synced(let synced)):
                LyricsSyncedPage(lines: synced.lines, source: synced.source)
            case .found(.plain(let plain)):
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(plain.text).font(.system(size: 24, weight: .bold)).lineSpacing(10).textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        LyricsSourceNote(source: plain.source)
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 16)
                }
            case .some(.none):
                message(L.t("この曲の歌詞は見つかりませんでした", "No lyrics found for this song"))
            default:
                // 読み込みに失敗したときだけ（Android と同じ）
                VStack(spacing: 12) {
                    Text(L.t("歌詞を読み込めませんでした", "Couldn't load lyrics")).font(.headline)
                    Button(L.t("もう一度", "Retry"), systemImage: "arrow.clockwise") { player.reloadLyrics() }
                        .buttonStyle(.bordered)
                }
            }
        }
    }

    private func message(_ text: String) -> some View {
        Text(text).font(.headline).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(24)
    }
}

/// 出典（「LRCLIB」「Source: Musixmatch」など。Android と同じく文字のまま）。
private struct LyricsSourceNote: View {
    var source: String?

    var body: some View {
        if let source, !source.isEmpty {
            Text(source).font(.caption).foregroundStyle(.secondary).padding(.vertical, 16)
        }
    }
}

/// 時刻付きの歌詞。歌っている行を表示の上から3分の1あたりに置き、行を押すとその位置へ。
private struct LyricsSyncedPage: View {
    var lines: [LyricLine]
    var source: String?
    /// 本人が送った時刻（この後しばらくは自動で追わない。Android と同じ3秒）。
    @State private var userScrolledAt = Date.distantPast

    var body: some View {
        let player = AppServices.player!
        let last = LyricsTiming.currentIndex(lines, positionMs: Int64(player.position * 1000))
        // 同じ時刻の行（原文と読み・訳が並ぶ歌詞）は、まとめて今の行とする
        let index = last < 0 ? last : (lines.firstIndex { $0.startMs == lines[last].startMs } ?? last)
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                        // 原文に続く読み・訳は、小さく原文の直下に添える
                        let isSub = i > 0 && lines[i - 1].startMs == line.startMs
                        let now = index >= 0 && i >= index && i <= last
                        Text(line.text.trimmingCharacters(in: .whitespaces).isEmpty ? "♪" : line.text)
                            .font(.system(size: isSub ? 19 : 28, weight: isSub ? .semibold : .bold))
                            .lineSpacing(isSub ? 4 : 8)
                            .foregroundStyle(.white)
                            .opacity(now ? 1 : i < index ? 0.55 : 0.4)
                            .scaleEffect(now ? 1 : 0.96, anchor: .leading)
                            .animation(.easeOut(duration: 0.35), value: now)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.top, isSub ? 2 : i == 0 ? 4 : 18)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                player.seek(Double(line.startMs) / 1000)
                                userScrolledAt = .distantPast
                            }
                            .id(i)
                    }
                    LyricsSourceNote(source: source)
                    Color.clear.frame(height: 200)
                }
                .padding(.horizontal, 24)
                .padding(.top, 16)
            }
            .scrollIndicators(.hidden)
            .simultaneousGesture(DragGesture(minimumDistance: 8).onChanged { _ in userScrolledAt = Date() })
            .onChange(of: index, initial: true) { _, i in
                guard i >= 0, Date().timeIntervalSince(userScrolledAt) > 3 else { return }
                withAnimation(.easeInOut(duration: 0.45)) { proxy.scrollTo(i, anchor: UnitPoint(x: 0, y: 0.33)) }
            }
        }
    }
}

/// 下の小さな再生の操作（進み具合・前へ・再生/一時停止・次へ）。
private struct LyricsMiniControls: View {
    @State private var dragging: Double?

    var body: some View {
        let player = AppServices.player!
        VStack(spacing: 6) {
            // ライブ配信は長さも位置も無く、シークしない
            if !player.isLive {
                Slider(value: Binding(get: { dragging ?? min(player.position, max(1, player.duration)) }, set: { dragging = $0 }),
                       in: 0...max(1, player.duration)) { editing in
                    if !editing, let value = dragging {
                        player.seek(value)
                        dragging = nil
                    }
                }
                .tint(.white)
            }
            HStack {
                Text(player.isLive ? "" : Ui.time(dragging ?? player.position)).foregroundStyle(.secondary).frame(width: 52, alignment: .leading)
                Spacer()
                Button(action: player.previous) { Image(systemName: "backward.end.fill").font(.title3).frame(width: 44, height: 44) }
                    .accessibilityLabel(L.t("前へ", "Previous"))
                Button(action: player.togglePlay) {
                    Group {
                        if player.isLoading {
                            ProgressView()
                        } else {
                            Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.title2)
                        }
                    }
                    .frame(width: 56, height: 56)
                    .glassCard(28, interactive: true)
                }
                .accessibilityLabel(player.isPlaying ? L.t("一時停止", "Pause") : L.t("再生", "Play"))
                Button(action: player.next) { Image(systemName: "forward.end.fill").font(.title3).frame(width: 44, height: 44) }
                    .accessibilityLabel(L.t("次へ", "Next"))
                Spacer()
                Text(player.isLive ? "" : Ui.time(player.duration)).foregroundStyle(.secondary).frame(width: 52, alignment: .trailing)
            }
            .font(.caption.monospacedDigit())
            .buttonStyle(.plain)
        }
        .foregroundStyle(.primary)
    }
}
#endif
