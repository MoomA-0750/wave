#if os(iOS)
import AVKit
import SwiftUI
import WaveCore

/// 再生中の画面（Android と同じ並び）。上にジャケットの色のグラデーション、閉じる・曲／動画の切り替え・メニュー、
/// 大きなジャケット（動画のときは映像）、曲名と評価、シークバー、操作の列、端末とキュー、下に歌詞のカード。
struct NowPlayingView: View {
    @State private var shell = IOSShell.shared
    @State private var tint: Color?
    @State private var showVideo = false
    @State private var dragging: Double?
    @State private var devicesOpen = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let player = AppServices.player!
        let song = player.current
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header(player, song)
                // ジャケットか、MV のある曲で「動画」を選んでいれば映像
                ZStack {
                    if showVideo, player.video != nil {
                        VideoSurface()
                    } else {
                        RemoteImage(url: Ui.large(song?.thumbnailUrl))
                    }
                }
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .shadow(radius: 16, y: 8)
                .gesture(DragGesture(minimumDistance: 24).onEnded { value in
                    guard abs(value.translation.width) > abs(value.translation.height), abs(value.translation.width) > 60 else { return }
                    if value.translation.width < 0 { player.next() } else { player.previous() }
                })
                titleRow(player, song)
                seekBar(player)
                controls(player)
                deviceAndQueue(player)
                lyricsCard(player)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
        .background {
            LinearGradient(colors: [(tint ?? Color.gray).opacity(0.85), Color.black], startPoint: .top, endPoint: .center)
                .ignoresSafeArea()
                .animation(.easeOut(duration: 0.4), value: tint)
        }
        .task(id: song?.thumbnailUrl) { tint = await Artwork.dominantColor(song?.thumbnailUrl) }
        .onChange(of: song?.videoId) { _, _ in showVideo = false }
        .onChange(of: showVideo, initial: true) { _, show in MusicVideoPlayer.shared.setActive(show ? player.video : nil) }
        .onChange(of: player.video) { _, v in MusicVideoPlayer.shared.setActive(showVideo ? v : nil) }
        .onDisappear { MusicVideoPlayer.shared.setActive(nil) }
        .sheet(isPresented: $shell.queueOpen) { QueueView() }
        .sheet(isPresented: $devicesOpen) { DevicesSheet() }
        .gesture(DragGesture(minimumDistance: 40).onEnded { value in
            // 下へ払うと閉じる
            if value.translation.height > 120, abs(value.translation.width) < 80 { dismiss() }
        })
    }

    private func header(_ player: PlayerService, _ song: Song?) -> some View {
        HStack {
            Button { dismiss() } label: { Image(systemName: "chevron.down").font(.title3.weight(.semibold)).frame(width: 44, height: 44) }
                .accessibilityLabel(L.t("閉じる", "Close"))
            Spacer()
            if player.video != nil {
                Picker("", selection: $showVideo) {
                    Text(L.t("曲", "Song")).tag(false)
                    Text(L.t("動画", "Video")).tag(true)
                }
                .pickerStyle(.segmented)
                .frame(width: 160)
            } else {
                Text(song?.album?.title ?? L.t("再生中", "Now playing")).font(.subheadline.weight(.semibold)).lineLimit(1)
            }
            Spacer()
            Menu {
                if let song { SongMenuItems(song: song) }
            } label: { Image(systemName: "ellipsis").font(.title3).frame(width: 44, height: 44) }
                .disabled(song == nil)
        }
        .foregroundStyle(.primary)
        .padding(.top, 8)
    }

    private func titleRow(_ player: PlayerService, _ song: Song?) -> some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text(song?.title ?? L.t("何も再生していません", "Nothing playing"))
                    .font(.title2.weight(.bold)).lineLimit(2)
                    .onTapGesture { if let id = song?.album?.browseId { AppServices.nav.album(id) } }
                Text(song?.artistsText ?? "").font(.body).foregroundStyle(.secondary).lineLimit(1)
                    .onTapGesture { if let id = song?.artists.first(where: { $0.browseId != nil })?.browseId { AppServices.nav.artist(id) } }
            }
            Spacer(minLength: 0)
            if song != nil { LikeButtonView(size: 26) }
        }
    }

    private func seekBar(_ player: PlayerService) -> some View {
        VStack(spacing: 4) {
            Slider(value: Binding(get: { dragging ?? min(player.position, max(1, player.duration)) }, set: { dragging = $0 }),
                   in: 0...max(1, player.duration)) { editing in
                // 本人が触り終えたときだけ移る
                if !editing, let value = dragging {
                    player.seek(value)
                    dragging = nil
                }
            }
            .tint(.white)
            HStack {
                Text(Ui.time(dragging ?? player.position))
                Spacer()
                Text(Ui.time(player.duration))
            }
            .font(.caption).monospacedDigit().foregroundStyle(.secondary)
        }
    }

    private func controls(_ player: PlayerService) -> some View {
        HStack {
            Button(action: player.toggleShuffle) {
                Image(systemName: "shuffle").foregroundStyle(player.shuffle ? Color.accentColor : Color.primary)
            }
            .accessibilityLabel(L.t("シャッフル", "Shuffle"))
            Spacer()
            Button(action: player.previous) { Image(systemName: "backward.end.fill").font(.title) }
                .accessibilityLabel(L.t("前へ", "Previous"))
            Spacer()
            Button(action: player.togglePlay) {
                ZStack {
                    Circle().fill(Color.white).frame(width: 72, height: 72)
                    if player.isLoading {
                        ProgressView().tint(.black)
                    } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 28)).foregroundStyle(.black)
                    }
                }
            }
            .accessibilityLabel(player.isPlaying ? L.t("一時停止", "Pause") : L.t("再生", "Play"))
            Spacer()
            Button(action: player.next) { Image(systemName: "forward.end.fill").font(.title) }
                .accessibilityLabel(L.t("次へ", "Next"))
            Spacer()
            Button(action: player.cycleRepeat) {
                Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat")
                    .foregroundStyle(player.repeatMode != .off ? Color.accentColor : Color.primary)
            }
            .accessibilityLabel(L.t("リピート", "Repeat"))
        }
        .font(.title3)
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
    }

    private func deviceAndQueue(_ player: PlayerService) -> some View {
        HStack {
            if AppServices.connect.configured {
                Button { devicesOpen = true } label: {
                    HStack(spacing: 6) {
                        Image(systemName: player.isRemote ? "hifispeaker.2.fill" : "hifispeaker.2")
                        if player.isRemote, let device = player.remoteDevice { Text(device).font(.caption.weight(.semibold)).lineLimit(1) }
                    }
                    .foregroundStyle(player.isRemote ? Color.accentColor : Color.primary)
                    .frame(minHeight: 44)
                }
                .accessibilityLabel(L.t("デバイスに接続", "Connect to a device"))
            }
            Spacer()
            if let song = player.current {
                ShareLink(item: SongActions.url(song)) { Image(systemName: "square.and.arrow.up").frame(width: 44, height: 44) }
            }
            Button { shell.queueOpen = true } label: { Image(systemName: "list.bullet").frame(width: 44, height: 44) }
                .accessibilityLabel(L.t("キュー", "Queue"))
        }
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
    }

    @ViewBuilder
    private func lyricsCard(_ player: PlayerService) -> some View {
        if case .found(let lyrics) = player.lyrics, player.current != nil {
            Button { LyricsSheetState.shared.open = true } label: {
                VStack(alignment: .leading, spacing: 12) {
                    Text(L.t("歌詞", "Lyrics")).font(.headline)
                    LyricsFollower(lyrics: lyrics, positionMs: Int64(player.position * 1000), fontSize: 20)
                        .frame(height: 260, alignment: .top)
                        .clipped()
                        .allowsHitTesting(false)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 14).fill((tint ?? .gray).opacity(0.55)))
            }
            .buttonStyle(.plain)
            .sheet(isPresented: Binding(get: { LyricsSheetState.shared.open }, set: { LyricsSheetState.shared.open = $0 })) { LyricsView() }
        }
    }
}

/// 歌詞の全画面（再生中の画面の上に重ねる）。
@MainActor @Observable
final class LyricsSheetState {
    static let shared = LyricsSheetState()
    var open = false
}

/// MV の映像（音の無い再生器。音は PlayerService が鳴らし、位置は VideoSync で合わせる）。
struct VideoSurface: View {
    var body: some View {
        let video = MusicVideoPlayer.shared
        ZStack {
            Color.black
            if video.showing { PlayerLayer(player: video.player) }
            if !video.showing || video.state != .ready {
                VStack(spacing: 8) {
                    Text(video.note).font(.caption).foregroundStyle(.white).multilineTextAlignment(.center)
                    if video.state == .failed { Button(L.t("もう一度", "Retry")) { video.load(AppServices.player.video) } }
                }
                .padding()
            }
        }
    }
}

/// AVPlayer の映像を出す枠。
struct PlayerLayer: UIViewRepresentable {
    var player: AVPlayer

    final class LayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> LayerView {
        let view = LayerView()
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ view: LayerView, context: Context) {
        if view.playerLayer.player !== player { view.playerLayer.player = player }
    }
}
#endif
