#if os(iOS)
import SwiftUI
import WaveCore

/// 画面の下のミニプレーヤー（Android と同じ並び: ジャケット・曲名・端末・評価・再生、下に進み具合の線）。
/// 押すと再生中の画面を開く。左右に払うと前後の曲へ。
struct MiniPlayer: View {
    var open: () -> Void

    var body: some View {
        let player = AppServices.player!
        let song = player.current
        HStack(spacing: 10) {
            Cover(url: Ui.small(song?.thumbnailUrl), size: 36, radius: 6)
            VStack(alignment: .leading, spacing: 1) {
                Text(song?.title ?? "").font(.subheadline.weight(.semibold)).lineLimit(1)
                if player.isRemote, let device = player.remoteDevice {
                    // ほかの端末で鳴っているときは、アーティストの代わりにその端末名（Android と同じ）
                    Label(device, systemImage: "hifispeaker.2").font(.caption).foregroundStyle(Color.accentColor).lineLimit(1)
                } else {
                    Text(song?.artistsText ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if song != nil { LikeButtonView(size: 20) }
            Button(action: player.togglePlay) {
                Group {
                    if player.isLoading {
                        ProgressView()
                    } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 20))
                    }
                }
                .frame(width: 40, height: 40)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(player.isPlaying ? L.t("一時停止", "Pause") : L.t("再生", "Play"))
        }
        .padding(.horizontal, 12)
        .overlay(alignment: .bottom) {
            // 進み具合の線
            GeometryReader { geo in
                Capsule().fill(Color.primary.opacity(0.85))
                    .frame(width: geo.size.width * progress(player), height: 2)
            }
            .frame(height: 2)
            .padding(.horizontal, 14)
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: open)
        .gesture(DragGesture(minimumDistance: 24).onEnded { value in
            guard abs(value.translation.width) > abs(value.translation.height), abs(value.translation.width) > 60 else { return }
            if value.translation.width < 0 { player.next() } else { player.previous() }
        })
    }

    private func progress(_ player: PlayerService) -> CGFloat {
        guard player.duration > 0 else { return 0 }
        return CGFloat(min(1, max(0, player.position / player.duration)))
    }
}
#endif
