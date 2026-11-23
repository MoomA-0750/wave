#if os(iOS)
import SwiftUI
import WaveCore

/// 画面の下のミニプレーヤー（Android と同じ並び: ジャケット・曲名・端末・評価・再生、下に進み具合の線）。
/// 押すと再生中の画面を開く。左右に払うと前後の曲へ。
struct MiniPlayer: View {
    static let zoomId = "nowPlaying"
    var open: () -> Void

    var body: some View {
        let player = AppServices.player!
        let song = player.current
        HStack(spacing: Kasane.Space.s3) {
            // 曲の欄（ジャケット・曲名・アーティスト）だけが左右に動く。指に付いてきて、離すと出ていき、隣の曲が反対側から入る（Safari の下の欄のタブ切り替えと同じ）
            ZStack {
                info(song, player)
                    .offset(x: drag)
                    .opacity(1 - min(1, abs(drag) / max(width, 1)) * 0.6)
                if let next = neighbor(player, drag < 0 ? 1 : -1), drag != 0 {
                    info(next, player)
                        .offset(x: drag + (drag < 0 ? width : -width))
                        .opacity(0.4 + min(1, abs(drag) / max(width, 1)) * 0.6)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // 曲の欄の左端を、欄の外（左の余白）まで広げて切る（余白の分だけ手前で切れないよう）。枠の丸い角の外へはみ出さないよう、全体も枠の形で切る
            .padding(.leading, -Kasane.Space.s3)
            .clipped()
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            .gesture(swipe(player))
            if song != nil { LikeButtonView(size: 20) }
            Button(action: player.togglePlay) {
                Group {
                    if player.isLoading {
                        ProgressView()
                    } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 20)) // apple-ui-allow: 記号の大きさ（Windows 版と揃える）
                    }
                }
                .frame(width: 40, height: 40)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(player.isPlaying ? L.t("一時停止", "Pause") : L.t("再生", "Play"))
        }
        .padding(.horizontal, Kasane.Space.s3)
        .overlay(alignment: .bottom) {
            // 進み具合の線
            GeometryReader { geo in // apple-ui-allow: 大きさに合わせて並べる・描く（測るだけではない）
                Capsule().fill(Color.primary.opacity(0.85))
                    .frame(width: geo.size.width * progress(player), height: 2)
            }
            .frame(height: 2)
            .padding(.horizontal, Kasane.Space.s4)
        }
        .clipShape(Capsule())
        .contentShape(Rectangle())
        .onTapGesture(perform: open)
        .gesture(swipe(player))
    }

    @State private var drag: CGFloat = 0
    @State private var settling = false
    @State private var width: CGFloat = 0

    private func info(_ song: Song?, _ player: PlayerService) -> some View {
        HStack(spacing: Kasane.Space.s3) {
            Cover(url: Ui.small(song?.thumbnailUrl), size: 36, radius: Kasane.Radius.sm)
            VStack(alignment: .leading, spacing: 1) {
                Text(song?.title ?? "").font(.subheadline.weight(.semibold)).lineLimit(1)
                if player.isRemote, let device = player.remoteDevice {
                    // ほかの端末で鳴っているときは、アーティストの代わりにその端末名（Android と同じ）
                    Label(device, systemImage: "hifispeaker.2").font(.caption).foregroundStyle(.primary).lineLimit(1)
                } else {
                    Text(song?.artistsText ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.leading, Kasane.Space.s3)
        .frame(width: max(width, 0) > 0 ? width : nil, alignment: .leading)
    }

    /// 前後の曲（ほかの端末で鳴っているときと、端のときは無い）。
    private func neighbor(_ player: PlayerService, _ d: Int) -> Song? {
        guard !player.isRemote, player.index >= 0 else { return nil }
        let i = player.index + d
        return player.queue.indices.contains(i) ? player.queue[i] : nil
    }

    /// 横へ決まった動きだけ曲送りにする（斜めは取らない）。隣が無い方へは少しだけ動いて戻る。
    private func swipe(_ player: PlayerService) -> some Gesture {
        DragGesture(minimumDistance: 10)
            .onChanged { value in
                guard !settling else { return }
                let x = value.translation.width
                guard abs(x) > abs(value.translation.height) * 1.5 else { return }
                drag = neighbor(player, x < 0 ? 1 : -1) == nil ? x / 4 : x
            }
            .onEnded { value in
                guard !settling else { return }
                let x = value.translation.width, predicted = value.predictedEndTranslation.width
                let horizontal = abs(x) > abs(value.translation.height) * 1.5
                let dir = !horizontal ? 0 : (x < -width / 4 || predicted < -width / 2) ? 1 : (x > width / 4 || predicted > width / 2) ? -1 : 0
                guard dir != 0, neighbor(player, dir) != nil, width > 0 else {
                    withAnimation(.snappy) { drag = 0 }
                    return
                }
                settling = true
                withAnimation(.snappy(duration: 0.22)) {
                    drag = -CGFloat(dir) * width
                } completion: {
                    // 曲が移ると隣の欄が真ん中の位置になるので、ずらしを戻しても見た目は動かない
                    var t = Transaction()
                    t.disablesAnimations = true
                    withTransaction(t) {
                        if dir == 1 { player.next() } else { player.previousTrack() }
                        drag = 0
                    }
                    settling = false
                }
            }
    }

    private func progress(_ player: PlayerService) -> CGFloat {
        guard player.duration > 0 else { return 0 }
        return CGFloat(min(1, max(0, player.position / player.duration)))
    }
}
#endif
