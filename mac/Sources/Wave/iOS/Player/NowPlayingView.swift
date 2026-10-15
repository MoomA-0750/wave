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
    @State private var fullVideo = false
    @Environment(\.dismiss) private var dismiss
    /// 横向き（縦が狭い）なら、ジャケット・映像と操作を左右に並べる。
    @Environment(\.verticalSizeClass) private var verticalSize

    var body: some View {
        let player = AppServices.player!
        let song = player.current
        Group {
            if verticalSize == .compact {
                // 横向き: 左にジャケット・映像（高さいっぱい）、右に操作から下（右だけ送る）
                HStack(alignment: .center, spacing: 28) {
                    media(player)
                        .frame(maxHeight: .infinity)
                        .padding(.vertical, 12)
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) { details(player, song) }
                            .padding(.vertical, 12)
                    }
                    .scrollIndicators(.hidden)
                }
                .padding(.horizontal, 24)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        header(player, song)
                        media(player)
                        details(player, song, withHeader: false)
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
        }
        .fullScreenCover(isPresented: $fullVideo) { VideoFullScreen() }
        .background {
            LinearGradient(colors: [(tint ?? Color.gray).opacity(0.85), Color.black], startPoint: .top, endPoint: .center)
                .ignoresSafeArea()
                .animation(.easeOut(duration: 0.4), value: tint)
        }
        .task(id: song?.thumbnailUrl) { tint = await Artwork.dominantColor(song?.thumbnailUrl) }
        .onChange(of: song?.videoId) { _, _ in showVideo = false }
        .onChange(of: showVideo, initial: true) { _, show in
            MusicVideoPlayer.shared.setActive(show ? player.video : nil)
            // ライブは「動画」にしたときだけ、映像を見るための画質で読み直す
            player.setLiveVideo(show)
        }
        .onChange(of: player.video) { _, v in MusicVideoPlayer.shared.setActive(showVideo ? v : nil) }
        .onDisappear { MusicVideoPlayer.shared.setActive(nil) }
        .sheet(isPresented: $shell.queueOpen) { QueueView().modifier(DialogSheets(layer: .queue)) }
        .sheet(isPresented: $devicesOpen) { DevicesSheet() }
        // 曲のメニューの「プレイリストに追加」は、この画面の上に出す
        .modifier(DialogSheets(layer: .player))
    }

    private func header(_ player: PlayerService, _ song: Song?) -> some View {
        HStack {
            Button { dismiss() } label: { Image(systemName: "chevron.down").font(.title3.weight(.semibold)).frame(width: 44, height: 44) }
                .accessibilityLabel(L.t("閉じる", "Close"))
            Spacer()
            if player.hasVideo {
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
        .contentShape(Rectangle())
        // 上の帯を下へ払っても閉じる
        .gesture(DragGesture(minimumDistance: 20).onEnded { value in
            if value.translation.height > 60, abs(value.translation.width) < value.translation.height { dismiss() }
        })
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

    /// ジャケット（左右に払うと前後の曲へ）か、MV のある曲・ライブで「動画」を選んでいれば映像（右下のボタンで全画面）。
    @ViewBuilder
    private func media(_ player: PlayerService) -> some View {
        if showVideo, player.hasVideo {
            VideoSurface()
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(alignment: .bottomTrailing) {
                    Button { fullVideo = true } label: {
                        Image(systemName: "arrow.up.left.and.arrow.down.right").font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                            .frame(width: 40, height: 40).background(Circle().fill(Color.black.opacity(0.45)))
                    }
                    .padding(8)
                    .accessibilityLabel(L.t("全画面で見る", "Full screen"))
                }
                .shadow(radius: 16, y: 8)
        } else {
            CoverCarousel()
        }
    }

    /// 曲名から下（縦向きはジャケットの下、横向きは右の列）。
    @ViewBuilder
    private func details(_ player: PlayerService, _ song: Song?, withHeader: Bool = true) -> some View {
        if withHeader { header(player, song) }
        titleRow(player, song)
        seekBar(player)
        controls(player)
        // ほかの端末で鳴っている間は、その端末の音量。この iPhone で鳴らしていて、アプリの中の音量が下がっている
        // （ほかの端末から下げられた等）ときは、この iPhone のアプリの中の音量（ふだんは音量ボタンだけで変える）
        if player.isRemote || player.volume < 0.995 { remoteVolume(player) }
        deviceAndQueue(player)
        lyricsCard(player)
    }

    @ViewBuilder
    private func seekBar(_ player: PlayerService) -> some View {
        if player.isLive {
            // ライブ配信: 長さも位置も無く、シークしない
            LiveBadge(font: .subheadline.weight(.bold), dot: 9).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        } else {
            seekSlider(player)
        }
    }

    private func seekSlider(_ player: PlayerService) -> some View {
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

    /// アプリの中の音量のゲージ。ほかの端末で鳴っている間はその端末のもの（iPhone の音量ボタンは横取りできないので、画面で変える）。
    /// アプリの中の音量だけを変える。この iPhone・その端末そのものの音量は変えない。
    private func remoteVolume(_ player: PlayerService) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "speaker.fill").font(.caption)
            Slider(value: Binding(get: { player.volume }, set: { player.volume = $0 }), in: 0...1)
                .tint(.white)
                .accessibilityLabel(player.isRemote ? L.t("\(player.remoteDevice ?? "ほかの端末") の音量", "Volume on \(player.remoteDevice ?? "the other device")")
                    : L.t("wave の音量", "wave volume"))
            Image(systemName: "speaker.wave.3.fill").font(.caption)
        }
        .foregroundStyle(.secondary)
        .frame(minHeight: 44)
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
            // RSS のエピソードには YouTube の共有のリンクが無い（ADR-0012）
            if let song = player.current, !Feed.isExternal(song.videoId) {
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
            .sheet(isPresented: Binding(get: { LyricsSheetState.shared.open }, set: { LyricsSheetState.shared.open = $0 })) {
                LyricsView().modifier(DialogSheets(layer: .lyrics))
            }
        }
    }
}

/// 再生中の画面のジャケット。前後の曲のジャケットを横に並べておき（先に読んでおく）、指について動く。
/// 払いきると隣のジャケットが真ん中へ滑ってきて、その曲へ移る（前へ払うと、再生した長さによらず前の曲へ。Android と同じ）。
private struct CoverCarousel: View {
    @State private var drag: CGFloat = 0
    @State private var settling = false

    var body: some View {
        let player = AppServices.player!
        GeometryReader { geo in
            let step = geo.size.width + 24
            ZStack {
                ForEach(slots(player), id: \.index) { slot in
                    cover(slot.song)
                        .offset(x: CGFloat(slot.index - player.index) * step + drag)
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 16)
                .onChanged { value in
                    guard !settling, abs(value.translation.width) > abs(value.translation.height) else { return }
                    // 隣が無い方へは、少しだけ動かして止める
                    let x = value.translation.width
                    drag = neighbor(player, x < 0 ? 1 : -1) == nil ? x / 4 : x
                }
                .onEnded { value in
                    guard !settling else { return }
                    let x = value.translation.width, predicted = value.predictedEndTranslation.width
                    let dir = (x < -60 || predicted < -step / 2) ? 1 : (x > 60 || predicted > step / 2) ? -1 : 0
                    let moves = dir != 0 && neighbor(player, dir) != nil
                    guard moves else {
                        withAnimation(.snappy) { drag = 0 }
                        if dir == 1 { player.next() } else if dir == -1 { player.previousTrack() }
                        return
                    }
                    settling = true
                    withAnimation(.snappy(duration: 0.25)) {
                        drag = -CGFloat(dir) * step
                    } completion: {
                        // 曲が移ると隣のジャケットが真ん中の位置になるので、ずらしを戻しても見た目は動かない
                        var t = Transaction()
                        t.disablesAnimations = true
                        withTransaction(t) {
                            if dir == 1 { player.next() } else { player.previousTrack() }
                            drag = 0
                        }
                        settling = false
                    }
                })
        }
        .aspectRatio(1, contentMode: .fit)
    }

    private func cover(_ song: Song) -> some View {
        ZStack {
            // 大きい絵を読むまで、ミニプレーヤーなどで読んである小さい絵を引き伸ばして出しておく
            RemoteImage(url: Ui.small(song.thumbnailUrl))
            RemoteImage(url: Ui.large(song.thumbnailUrl))
        }
        .background(Color.white.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .shadow(radius: 16, y: 8)
    }

    private struct Slot {
        let index: Int
        let song: Song
    }

    /// 真ん中と前後の曲（ほかの端末で鳴っているときは、真ん中だけ）。
    private func slots(_ player: PlayerService) -> [Slot] {
        guard let current = player.current else { return [] }
        guard !player.isRemote, player.index >= 0 else { return [Slot(index: max(0, player.index), song: current)] }
        return (-1...1).compactMap { d in
            d == 0 ? Slot(index: player.index, song: current) : neighbor(player, d).map { Slot(index: player.index + d, song: $0) }
        }
    }

    private func neighbor(_ player: PlayerService, _ d: Int) -> Song? {
        guard !player.isRemote, player.index >= 0 else { return nil }
        let i = player.index + d
        return player.queue.indices.contains(i) ? player.queue[i] : nil
    }
}

/// 歌詞の全画面（再生中の画面の上に重ねる）。
@MainActor @Observable
final class LyricsSheetState {
    static let shared = LyricsSheetState()
    var open = false
}

/// MV の映像（音の無い再生器。音は PlayerService が鳴らし、位置は VideoSync で合わせる）。
/// 映像の全画面（横向きに回す。閉じると元の向きに戻す）。
private struct VideoFullScreen: View {
    @Environment(\.dismiss) private var dismiss
    @State private var before: UIInterfaceOrientationMask?

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            VideoSurface().ignoresSafeArea()
            Button { dismiss() } label: {
                Image(systemName: "arrow.down.right.and.arrow.up.left").font(.system(size: 16, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 44, height: 44).background(Circle().fill(Color.black.opacity(0.45)))
            }
            .padding(16)
            .accessibilityLabel(L.t("全画面を終える", "Exit full screen"))
        }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .onAppear {
            guard let scene = scene else { return }
            if scene.interfaceOrientation.isPortrait { before = .portrait }
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight))
        }
        .onDisappear {
            // 縦で見ていたなら縦に戻す
            if let before, let scene { scene.requestGeometryUpdate(.iOS(interfaceOrientations: before)) }
        }
    }

    private var scene: UIWindowScene? { UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first }
}

struct VideoSurface: View {
    var body: some View {
        let video = MusicVideoPlayer.shared
        ZStack {
            Color.black
            if let live = AppServices.player.liveVideoPlayer {
                // ライブは鳴らしている再生器の映像をそのまま出す
                PlayerLayer(player: live)
            } else {
                if video.showing { PlayerLayer(player: video.player) }
            }
            if AppServices.player.liveVideoPlayer == nil && (!video.showing || video.state != .ready) {
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
