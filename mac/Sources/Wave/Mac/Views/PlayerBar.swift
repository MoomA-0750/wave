#if os(macOS)
import AVKit
import SwiftUI
import WaveCore

/// 再生の操作（Spotify の下の帯: 左に曲、中央にボタンと位置、右に歌詞・キュー・音量）。
struct PlayerBar: View {
    @State private var model = MainWindowModel.shared
    @State private var volumeBeforeMute = 0.7
    @State private var devicesOpen = false

    var body: some View {
        let player = AppServices.player!
        GeometryReader { geo in
            let side = geo.size.width * 0.3
            HStack(spacing: 16) {
                // 左: 曲
                HStack(spacing: 12) {
                    Cover(url: Ui.small(player.current?.thumbnailUrl), size: 56)
                        .onTapGesture { model.setRightPane(open: !(model.rightPaneOpen && model.rightView == .nowPlaying), queue: false) }
                    // 評価のボタンは曲名のすぐ右（Spotify と同じ）。曲名が長いときだけ省略する
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            LinkText(text: player.current?.title ?? "", action: SongLinks.openAlbum).font(.body.weight(.semibold)).lineLimit(1)
                            ArtistLink(font: .caption, color: .secondary)
                        }
                        if player.current != nil { LikeButton.view(player, size: 18) }
                    }
                    .frame(maxWidth: 300, alignment: .leading)
                    Spacer(minLength: 0)
                }
                .frame(width: side)
                // 中央: ボタンと位置
                TransportControls()
                    .frame(maxWidth: 720)
                // 右: 歌詞・キュー・デバイス・再生中の画面・全画面・音量
                HStack(spacing: 4) {
                    Spacer(minLength: 0)
                    IconToggle(isOn: isLyrics, help: L.t("歌詞", "Lyrics"), action: toggleLyrics) {
                        Image(systemName: "quote.bubble").font(.system(size: 15))
                    }
                    IconToggle(isOn: model.queueToggleOn, help: L.t("キュー", "Queue"), action: model.toggleQueue) {
                        Image(systemName: "list.bullet").font(.system(size: 15))
                    }
                    if AppServices.connect.configured {
                        Button { devicesOpen.toggle() } label: {
                            HStack(spacing: 6) {
                                Image(systemName: player.isRemote ? "iphone.radiowaves.left.and.right" : "hifispeaker.2").font(.system(size: 15))
                                    .foregroundStyle(player.isRemote ? Color.accentColor : Color.primary)
                                // ほかの端末で鳴っているときは、アイコンの右にその端末名（Spotify と同じく色を付ける）
                                if player.isRemote, let device = player.remoteDevice {
                                    // 名前の長さだけの幅にする（最大幅の枠のままだと短い名前も 140 点に広がり、真ん中に浮く）
                                    Text(device).font(.caption.weight(.semibold)).foregroundStyle(Color.accentColor).lineLimit(1)
                                        .frame(maxWidth: 140, alignment: .leading).fixedSize(horizontal: true, vertical: false)
                                }
                            }
                        }
                        .buttonStyle(IconButtonStyle())
                        .help(L.t("デバイスに接続", "Connect to a device"))
                        .popover(isPresented: $devicesOpen, arrowEdge: .top) { DevicesPopover(isOpen: $devicesOpen) }
                    }
                    IconToggle(isOn: model.paneToggleOn, help: L.t("再生中の画面", "Now playing view"), action: model.togglePane) {
                        Image(systemName: "sidebar.right").font(.system(size: 15))
                    }
                    IconToggle(isOn: model.fullScreen, help: L.t("全画面 (F11)", "Full screen (F11)"), action: { model.setFullScreen(!model.fullScreen) }) {
                        Image(systemName: "arrow.up.left.and.arrow.down.right").font(.system(size: 15))
                    }
                    Button(action: mute) { Image(systemName: volumeSymbol(player.volume)).font(.system(size: 15)).frame(width: 20) }
                        .buttonStyle(IconButtonStyle()).help(L.t("ミュート", "Mute"))
                    Slider(value: Binding(get: { player.volume }, set: { player.volume = $0 }), in: 0...1).frame(width: 110).controlSize(.small)
                }
                .frame(width: side)
            }
            .padding(.horizontal, 16)
            .frame(maxHeight: .infinity)
        }
        .frame(height: 88)
    }

    private var isLyrics: Bool { if case .lyrics = AppServices.nav.current.route { return true } else { return false } }

    private func toggleLyrics() {
        if isLyrics && AppServices.nav.canGoBack { AppServices.nav.goBack() } else { AppServices.nav.lyrics() }
    }

    private func volumeSymbol(_ v: Double) -> String {
        switch v {
        case ...0: "speaker.slash.fill"
        case ..<0.34: "speaker.wave.1.fill"
        case ..<0.67: "speaker.wave.2.fill"
        default: "speaker.wave.3.fill"
        }
    }

    private func mute() {
        let player = AppServices.player!
        if player.volume > 0 {
            volumeBeforeMute = player.volume
            player.volume = 0
        } else {
            player.volume = volumeBeforeMute
        }
    }
}

/// 再生のボタン（シャッフル・前へ・再生・次へ・リピート）と位置のバー。下の帯とメニューバーの小窓で使う。
struct TransportControls: View {
    @State private var dragging: Double?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let player = AppServices.player!
        VStack(spacing: 2) {
            HStack(spacing: 12) {
                IconToggle(isOn: player.shuffle, help: L.t("シャッフル", "Shuffle"), action: player.toggleShuffle) {
                    Image(systemName: "shuffle").font(.system(size: 15))
                }
                Button(action: player.previous) { Image(systemName: "backward.end.fill").font(.system(size: 15)) }
                    .buttonStyle(IconButtonStyle()).help(L.t("前へ", "Previous"))
                Button(action: player.togglePlay) {
                    // primary などの色は、メニューバーの小窓のガラスの上だと透けて灰色になり、押せないように見える。
                    // 決まった色で塗る（暗い表示は白い丸に黒い記号、明るい表示はその逆）
                    let fill: Color = scheme == .dark ? .white : .black
                    let mark: Color = scheme == .dark ? .black : .white
                    ZStack {
                        Circle().fill(fill).frame(width: 36, height: 36)
                        if player.isLoading {
                            ProgressView().controlSize(.small).tint(mark)
                        } else {
                            Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 15))
                                .foregroundStyle(mark)
                        }
                    }
                }
                .buttonStyle(.plain)
                .help(L.t("再生/一時停止", "Play/Pause"))
                Button(action: player.next) { Image(systemName: "forward.end.fill").font(.system(size: 15)) }
                    .buttonStyle(IconButtonStyle()).help(L.t("次へ", "Next"))
                IconToggle(isOn: player.repeatMode != .off, help: L.t("リピート", "Repeat"), action: player.cycleRepeat) {
                    Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat").font(.system(size: 15))
                }
            }
            if player.isLive {
                // ライブ配信: 長さも位置も無く、シークしない
                LiveBadge().frame(height: 20).frame(maxWidth: .infinity)
            } else {
                positionBar(player)
            }
        }
    }

    private func positionBar(_ player: PlayerService) -> some View {
        Group {
            HStack(spacing: 8) {
                Text(Ui.time(dragging ?? player.position)).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                    .frame(width: 44, alignment: .trailing)
                Slider(value: Binding(get: { dragging ?? min(player.position, max(1, player.duration)) }, set: { dragging = $0 }),
                       in: 0...max(1, player.duration)) { editing in
                    // 本人がシークバーを触り終えたときだけ移る（曲が変わって最大値が縮んだなどではシークしない）
                    if !editing, let value = dragging {
                        player.seek(value)
                        dragging = nil
                    }
                }
                .controlSize(.small)
                Text(Ui.time(player.duration)).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                    .frame(width: 44, alignment: .leading)
            }
        }
    }
}

/// ジャケットか、動画を選んでいれば MV（音の無い映像を音に合わせる）。
struct VideoOrCover: View {
    var size: CGFloat?
    var inFullView: Bool
    @State private var model = MainWindowModel.shared

    var body: some View {
        let player = AppServices.player!
        let video = MusicVideoPlayer.shared
        ZStack {
            if model.showVideo, let live = player.liveVideoPlayer {
                // ライブは鳴らしている再生器の映像をそのまま出す
                Color.black
                PlayerLayerView(player: live)
            } else if model.showVideo, player.video != nil {
                Color.black
                if video.showing {
                    PlayerLayerView(player: video.player)
                }
                if !video.showing || video.state != .ready {
                    VStack(spacing: 8) {
                        Text(video.note).font(.caption).foregroundStyle(.white).multilineTextAlignment(.center)
                        if video.state == .failed { Button(L.t("もう一度", "Retry")) { video.load(player.video) } }
                    }
                    .padding()
                }
            } else {
                Color.primary.opacity(0.08)
                RemoteImage(url: Ui.large(player.current?.thumbnailUrl), contentMode: inFullView ? .fit : .fill)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: inFullView ? 0 : 8))
        .onChange(of: model.showVideo, initial: true) { _, show in video.setActive(show ? player.video : nil) }
        .onChange(of: player.video) { _, v in if model.showVideo { video.setActive(v) } else { video.setActive(nil) } }
    }
}

/// AVPlayer の映像を出す枠。
struct PlayerLayerView: NSViewRepresentable {
    var player: AVPlayer

    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.controlsStyle = .none
        view.videoGravity = .resizeAspect
        view.player = player
        return view
    }

    func updateNSView(_ view: AVPlayerView, context: Context) {
        if view.player !== player { view.player = player }
    }
}

/// 全画面の再生中の画面（Spotify のデスクトップの全画面表示）。上の2段を覆い、下の操作の帯は出したまま。
struct FullView: View {
    @State private var model = MainWindowModel.shared

    var body: some View {
        let player = AppServices.player!
        let song = player.current
        ZStack(alignment: .topTrailing) {
            Color(nsColor: .windowBackgroundColor)
            RemoteImage(url: Ui.large(song?.thumbnailUrl)).opacity(0.25)
            LinearGradient(colors: [.black.opacity(0.2), .black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
            GeometryReader { outer in
            // 左に曲（3）、右に歌詞（2）。Windows と同じ割り付け
            let left = (outer.size.width - 56) * 0.6
            HStack(alignment: .bottom, spacing: 56) {
                VStack(alignment: .leading, spacing: 20) {
                    if player.hasVideo { SongVideoSwitch() }
                    // ジャケットは正方形、映像は枠いっぱいの大きさ（縦横比はそのまま）
                    GeometryReader { geo in
                        if model.showVideo && player.hasVideo {
                            VideoOrCover(size: nil, inFullView: true)
                        } else {
                            let side = min(geo.size.width, geo.size.height)
                            RemoteImage(url: Ui.large(song?.thumbnailUrl))
                                .frame(width: side, height: side)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                        }
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        LinkText(text: song?.title ?? "", action: SongLinks.openAlbum).font(.system(size: 44, weight: .bold)).lineLimit(1)
                            .foregroundStyle(.white)
                        ArtistLink(font: .system(size: 22), color: .white.opacity(0.75))
                    }
                }
                .frame(width: left)
                if case .found(let lyrics) = player.lyrics {
                    LyricsFollower(lyrics: lyrics, positionMs: Int64(player.position * 1000), fontSize: 30, plainLines: 200, scrollable: true)
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        .clipped()
                } else {
                    Spacer().frame(maxWidth: .infinity)
                }
            }
            // 中身が何でも枠の大きさに収める（はみ出すと、下揃えの左の曲が窓の外へ押し出される）
            .frame(width: outer.size.width, height: outer.size.height)
            }
            .padding(EdgeInsets(top: 56, leading: 56, bottom: 32, trailing: 56))
            Button { model.setFullScreen(false) } label: { Image(systemName: "arrow.down.right.and.arrow.up.left").font(.system(size: 15)) }
                .buttonStyle(IconButtonStyle())
                .foregroundStyle(.white)
                .help(L.t("全画面を終える (Esc)", "Exit full screen (Esc)"))
                .padding(16)
        }
        .environment(\.colorScheme, .dark)
    }
}
#endif
