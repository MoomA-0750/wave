#if os(iOS)
import AVKit
import SwiftUI
import WaveCore

/// 再生中の画面（Apple Music の並び）。縦向きは上にジャケット（動画のときは映像）、下に曲名と評価・シーク・操作・音量・下の列。
/// 横向きは左にジャケット、右に同じ部品。歌詞・キューはどちらの向きもジャケットの場所を切り替えて出す。
struct NowPlayingView: View {
    @State private var shell = IOSShell.shared
    @State private var dragging: Double?
    @State private var devicesOpen = false
    /// Claude に聞く（下のタブの「Claude」と同じ会話。Android の再生画面の ✨ と同じ）。
    @State private var aiOpen = false
    /// 指でスクロールしている間か（一番上からさらに下へ引っ張ったら閉じるため）。
    @State private var scrollTouching = false
    /// 縦向きの下の操作の欄の高さ（ジャケットをその上まで伸ばす）。
    @State private var controlsHeight: CGFloat = 320
    /// 映像の縦横比（幅 / 高さ）。
    @State private var videoAspect: CGFloat = 16 / 9
    @Environment(\.dismiss) private var dismiss
    /// 歌詞・キューを開け閉めするとき、曲名・評価のボタン・ジャケットを下の大きな行と上の小さな行の間で滑らせる
    @Namespace private var titleSpace
    /// 閉じている途中も出しておく歌詞・キュー（消える部品にすると、消え始めに中身が横へ跳んだ。薄くし終えてから外す）
    @State private var closingPanel: PortraitPanel?
    /// 横向き（縦が狭い）なら、ジャケット・映像と操作を左右に並べる。
    @Environment(\.verticalSizeClass) private var verticalSize

    var body: some View {
        let player = AppServices.player!
        let song = player.current
        Group {
            if verticalSize == .compact {
                landscape(player, song)
            } else {
                portrait(player, song)
            }
        }
        .fullScreenCover(isPresented: $shell.videoFullScreen) { VideoFullScreen() }
        // ジャケットを左右に払っている間は、ズームで開いた画面を指で引いてしまう動きを止める（横の払いで画面が消えていた）
        .interactiveDismissDisabled(shell.coverSwiping)
        // ジャケットを大きくぼかして画面の端まで敷く（Apple Music の再生中の画面と同じ）
        .background { ArtworkBackdrop(url: song?.thumbnailUrl) }
        // 曲が変わったら曲の表示に戻す（PiP で映している間は動画のまま）
        .onChange(of: song?.videoId) { _, _ in if !VideoPip.shared.active { shell.showVideo = false } }
        .onChange(of: shell.showVideo, initial: true) { _, show in
            MusicVideoPlayer.shared.setActive(show ? player.video : nil)
            // ライブは「動画」にしたときだけ、映像を見るための画質で読み直す
            player.setLiveVideo(show)
        }
        .onChange(of: player.video) { _, v in MusicVideoPlayer.shared.setActive(shell.showVideo ? v : nil) }
        // 再生画面を閉じたら映像の読み込みをやめる（PiP で映している間は続ける）
        // ほかの端末で鳴っているライブの映像（この iPhone で別に読んでいる）も捨てる。開き直すと onChange が読み直す
        .onDisappear {
            if !VideoPip.shared.active {
                MusicVideoPlayer.shared.setActive(nil)
                if player.isRemote { player.setLiveVideo(false) }
            }
        }
        // 歌詞・キューは、縦向きも横向きもジャケットの場所を切り替えて出す（Apple Music と同じ）
        .sheet(isPresented: $devicesOpen) { DevicesSheet() }
        .sheet(isPresented: $aiOpen) { AiSheet() }
        // 曲のメニューの「プレイリストに追加」は、この画面の上に出す
        .modifier(DialogSheets(layer: .player))
    }

    /// 再生画面から開く Claude（下のタブと同じ会話をシートで出す）。
    private struct AiSheet: View {
        @Environment(\.dismiss) private var dismiss

        var body: some View {
            NavigationStack {
                AiTabView()
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button(L.t("閉じる", "Close")) { dismiss() } }
                    }
                    // 「設定を開く」は、シートと再生画面を閉じてから設定へ
                    .environment(\.aiOpenSettings) {
                        dismiss()
                        IOSShell.shared.playerOpen = false
                        AppServices.nav.settings()
                    }
            }
            .presentationDragIndicator(.visible)
        }
    }

    // MARK: 横向き（縦向きと同じ部品を左右に並べる）

    /// 横向き: 左にジャケット・映像（歌詞・キューを開いたらその場所に歌詞・キュー）、右に縦向きと同じ曲名・シーク・操作・音量・下の列。
    private func landscape(_ player: PlayerService, _ song: Song?) -> some View {
        let panel = landscapePanel
        return ZStack {
            // ジャケットは、並びの外の層に置いて画面の左・上・下の端まで伸ばす（並びの中では安全領域の内側までしか伸びず、左と下に地が残った）
            if panel == nil && !(shell.showVideo && player.hasVideo) {
                landscapeCover(player)
                    // 画面の左下を基準に、閉じると広がりながら出て、開くと縮みながら消える（左と下の端は画面の端に付けたまま。
                    // ずらすと下の端が見えた）。上と右の端は溶かしてあるので、縮んでも境目は出ない
                    .transition(.scale(scale: 0.88, anchor: .bottomLeading).combined(with: .opacity))
                    .simultaneousGesture(DragGesture(minimumDistance: 20).onEnded { value in
                        if value.translation.height > 70, value.translation.height > abs(value.translation.width) { dismiss() }
                    })
            }
            landscapeColumns(player, song, panel: panel)
        }
        .animation(.timingCurve(0.2, 0.9, 0.25, 1, duration: 0.5), value: panel)
        .onChange(of: panel, initial: true) { old, new in holdWhileClosing(old: old, new: new) { landscapePanel } }
    }

    private func landscapeColumns(_ player: PlayerService, _ song: Song?, panel: PortraitPanel?) -> some View {
        HStack(alignment: .center, spacing: 28) {
            ZStack {
                // 歌詞・キューは、閉じる間も残して薄くしながら下げ、消えてから外す（開くときの逆の動きにそろえる。
                // 消える部品にすると、消え始めに中身が跳んで開くときと違う動きに見えた）
                if let shown = panel ?? closingPanel {
                    Group {
                        switch shown {
                        case .lyrics: LyricsView(embedded: true)
                        case .queue: QueueView(embedded: true)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .mask {
                        LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.82), .init(color: .clear, location: 1)],
                                       startPoint: .top, endPoint: .bottom)
                    }
                    .opacity(panel != nil ? 1 : 0)
                    .offset(y: panel != nil ? 0 : 60)
                    .allowsHitTesting(panel != nil)
                    .transition(.asymmetric(insertion: .offset(y: 60).combined(with: .opacity), removal: .identity))
                }
                if panel == nil, shell.showVideo, player.hasVideo {
                    media(player)
                        .transition(.offset(y: -60).combined(with: .opacity))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.vertical, 12)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Capsule().fill(.white.opacity(0.5)).frame(width: 36, height: 5)
                        .frame(maxWidth: .infinity, minHeight: 20)
                        .contentShape(Rectangle())
                        .onTapGesture { dismiss() }
                        .accessibilityLabel(L.t("閉じる", "Close"))
                        .accessibilityAddTraits(.isButton)
                    portraitTitleRow(player, song)
                    seekBar(player)
                    portraitControls(player)
                    if player.canChangeVolume { volumeBar(player) }
                    portraitBottomRow(player)
                }
                .padding(.vertical, 8)
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize)
            // 収まるときは上下の中央に置く（上に寄って下が大きく空いていた）
            .defaultScrollAnchor(.center)
            .modifier(PullDownToDismiss(touching: $scrollTouching) { dismiss() })
            // 幅は画面の半分ほどに決める（長い曲名の1行の幅に引かれて、右へはみ出していた）
            .containerRelativeFrame(.horizontal) { width, _ in min(440, (width - 76) / 2) }
        }
        .padding(.horizontal, 24)
    }

    /// 開いた歌詞・キューを覚えておき、閉じたら薄くし終えるまで出しておく（外すときは見えていないので、動きは付けない）。
    /// 開いたときに覚えておく（閉じてから覚えると、閉じた最初のコマで一度外れて、歌詞がぱっと消えた）。
    private func holdWhileClosing(old: PortraitPanel?, new: PortraitPanel?, current: @escaping () -> PortraitPanel?) {
        if let new { closingPanel = new; return }
        guard old != nil else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(550))
            if current() == nil {
                var t = Transaction()
                t.disablesAnimations = true
                withTransaction(t) { closingPanel = nil }
            }
        }
    }

    /// 横向きのジャケット: 枠を付けず、画面の左・上・下の端まで伸ばして、右の操作の列の側を地へ溶かす（縦向きと同じく境目を作らない）。
    /// 間にぼかした同じ絵を挟んで、くっきり → ぼかし → 地 と溶かす。幅は右の操作の列の手前まで。
    private func landscapeCover(_ player: PlayerService) -> some View {
        GeometryReader { geo in
            let insets = geo.safeAreaInsets
            let inner = geo.size.width - insets.leading - insets.trailing
            let column = min(440, (inner - 76) / 2)
            // 右の列の左の端（と少し重ねて、そこで溶かしきる）
            let width = geo.size.width - insets.trailing - 24 - column + 24
            ZStack {
                RemoteImage(url: Ui.large(player.current?.thumbnailUrl))
                    .blur(radius: 40, opaque: true)
                    .saturation(1.15)
                    .mask {
                        LinearGradient(stops: [.init(color: .clear, location: 0.35), .init(color: .black, location: 0.6),
                                               .init(color: .black.opacity(0.6), location: 0.82), .init(color: .black.opacity(0.2), location: 0.94),
                                               .init(color: .clear, location: 1)],
                                       startPoint: .leading, endPoint: .trailing)
                    }
                    .allowsHitTesting(false)
                CoverCarousel(fullBleed: true, fadeEdge: .trailing)
            }
            .frame(width: max(0, width), height: geo.size.height)
            .clipped()
            // 上の端も地へ薄く溶かす（開け閉めで縮んだとき、上に境目が出ないように）
            .mask {
                LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.08)], startPoint: .top, endPoint: .bottom)
            }
        }
        .ignoresSafeArea()
    }

    /// 横向きで、ジャケットの場所に出している中身（歌詞・キュー）。
    private var landscapePanel: PortraitPanel? {
        guard verticalSize == .compact else { return nil }
        if shell.queueOpen { return .queue }
        if LyricsSheetState.shared.open { return .lyrics }
        return nil
    }

    // MARK: 縦向き（Apple Music の並び）

    /// 縦向き: 上にジャケット（画面の幅いっぱいに、時刻の帯の裏まで。動画のときは映像）、操作は下に寄せる。
    /// 上には閉じるためのつまみだけを置き、ボタンはジャケットに重ねない（Apple Music の再生中の画面と同じ）。
    private func portrait(_ player: PlayerService, _ song: Song?) -> some View {
        let video = shell.showVideo && player.hasVideo
        let panel = portraitPanel
        return ZStack(alignment: .top) {
            if !video && panel == nil {
                // ジャケットは操作の欄の少し下まで縦に伸ばす（左右ははみ出して切れる）。下の方は、ぼかした同じ絵を挟んで
                // くっきり → ぼかし → 地 と溶かし、ジャケットと操作の間に隙間を作らない
                VStack(spacing: 0) {
                    ZStack {
                        // ぼかした層は、下へ行くほど地（ArtworkBackdrop: 彩度を上げて暗くしたもの）と同じ色味・暗さに寄せてから、
                        // 緩やかな曲線で消す（明るいまま暗い地へ短く切り替えると、明るいジャケットで切れ目が目立った）
                        RemoteImage(url: Ui.large(song?.thumbnailUrl))
                            .blur(radius: 40, opaque: true)
                            .saturation(1.15)
                            .overlay {
                                LinearGradient(stops: [.init(color: .black.opacity(0), location: 0.45), .init(color: .black.opacity(0.25), location: 0.75),
                                                       .init(color: .black.opacity(0.5), location: 1)],
                                               startPoint: .top, endPoint: .bottom)
                            }
                            .mask {
                                LinearGradient(stops: [.init(color: .clear, location: 0.3), .init(color: .black, location: 0.55),
                                                       .init(color: .black, location: 0.7), .init(color: .black.opacity(0.75), location: 0.8),
                                                       .init(color: .black.opacity(0.4), location: 0.89), .init(color: .black.opacity(0.12), location: 0.96),
                                                       .init(color: .clear, location: 1)],
                                               startPoint: .top, endPoint: .bottom)
                            }
                            .allowsHitTesting(false)
                        CoverCarousel(fullBleed: true)
                    }
                    Color.clear.frame(height: max(0, controlsHeight - 120))
                }
                .ignoresSafeArea(edges: .top)
                // 歌詞・キューを開くと上へ抜け、閉じると上から戻る（曲名が上へ滑るのに合わせる）
                .transition(.offset(y: -160).combined(with: .opacity))
            }
            VStack(spacing: 0) {
                Capsule().fill(.white.opacity(0.5)).frame(width: 36, height: 5)
                    .frame(maxWidth: .infinity, minHeight: 24)
                    .contentShape(Rectangle())
                    .onTapGesture { dismiss() }
                    .accessibilityLabel(L.t("閉じる", "Close"))
                    .accessibilityAddTraits(.isButton)
                // ジャケットの場所。大きさは開け閉めで変えない（中身の並びが組み変わると、消えていく歌詞が横へずれて見えた）
                ZStack(alignment: .top) {
                    // 歌詞・キュー: 上に小さな曲の行、真ん中に中身、下に操作
                    // 左右の端は、キューの並び（List の行と区切りの見出し）にそろえる
                    VStack(spacing: 0) {
                        if let shown = panel ?? closingPanel {
                            // 曲名・ボタン・ジャケットは下の大きな行との間を滑る（matchedGeometryEffect）。閉じる間は行を残して薄くする
                            compactTitleRow(player, song, active: panel != nil)
                                .padding(.horizontal, 20)
                                .padding(.vertical, 8)
                                .opacity(panel != nil ? 1 : 0)
                                .transition(.opacity)
                            Group {
                                switch shown {
                                case .lyrics: LyricsView(embedded: true)
                                case .queue: QueueView(embedded: true)
                                }
                            }
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            // 下の端は薄くして消す（操作の欄の上ですっぱり切れて見えないように。上の端は歌詞・キューの中で薄くする）
                            .mask {
                                // 下は操作の欄の手前で長めに溶かす（境目を見せない）
                                LinearGradient(stops: [.init(color: .black, location: 0),
                                                       .init(color: .black, location: 0.7), .init(color: .black.opacity(0.35), location: 0.88),
                                                       .init(color: .clear, location: 1)],
                                               startPoint: .top, endPoint: .bottom)
                            }
                            // 曲名が上へ滑るのに合わせて下から上がって出て、閉じると下へ下がって消える。
                            // 閉じるときは部品を残したまま動かす（消える部品にすると、消え始めに中身が左へ跳んだ）
                            .opacity(panel != nil ? 1 : 0)
                            .offset(y: panel != nil ? 0 : 140)
                            .allowsHitTesting(panel != nil)
                            .transition(.asymmetric(insertion: .offset(y: 140).combined(with: .opacity), removal: .identity))
                        }
                    }
                    if panel == nil && video {
                        media(player).padding(.horizontal, 24).padding(.top, 16)
                            .transition(.opacity)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                VStack(alignment: .leading, spacing: 14) {
                    if panel == nil { portraitTitleRow(player, song) }
                    seekBar(player)
                    portraitControls(player)
                    if player.canChangeVolume { volumeBar(player) }
                    portraitBottomRow(player)
                }
                .padding(.horizontal, 28)
                .padding(.bottom, 4)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { if panel == nil { controlsHeight = $0 } }
            }
        }
        // 初めに速く動き、終わりは慣性で緩やかに止まる
        .animation(.timingCurve(0.2, 0.9, 0.25, 1, duration: 0.5), value: panel)
        .onChange(of: panel, initial: true) { old, new in holdWhileClosing(old: old, new: new) { portraitPanel } }
        // ジャケットや曲名の辺りを下へ払ったら閉じる（つまみ・シークバーの横の動きは通す）。歌詞・キューは中を送るので、払っても閉じない
        .simultaneousGesture(DragGesture(minimumDistance: 20).onEnded { value in
            if value.translation.height > 70, value.translation.height > abs(value.translation.width) * 1.5 { dismiss() }
        }, including: panel == nil ? .all : .subviews)
    }

    enum PortraitPanel { case lyrics, queue }

    /// 縦向きで、ジャケットの場所に出している中身（歌詞・キュー）。
    private var portraitPanel: PortraitPanel? {
        guard verticalSize != .compact else { return nil }
        if shell.queueOpen { return .queue }
        if LyricsSheetState.shared.open { return .lyrics }
        return nil
    }

    /// 歌詞・キューのときの上の曲の行（小さなジャケットを押すとジャケットの画面に戻る）。
    /// active: 開いている間（閉じる途中は false にして、曲名などを下の大きな行の位置へ滑らせる）
    private func compactTitleRow(_ player: PlayerService, _ song: Song?, active: Bool) -> some View {
        HStack(spacing: 12) {
            Button { closePanels() } label: { Cover(url: Ui.small(song?.thumbnailUrl), size: 56, radius: 6) }
                .buttonStyle(.plain)
                .accessibilityLabel(L.t("ジャケットに戻る", "Back to artwork"))
                .matchedGeometryEffect(id: "cover", in: titleSpace, isSource: active)
            VStack(alignment: .leading, spacing: 2) {
                Text(song?.title ?? "").font(.headline).modifier(FadeTail())
                Text(song?.artistsText ?? "").font(.subheadline).foregroundStyle(.secondary).modifier(FadeTail())
            }
            .matchedGeometryEffect(id: "title", in: titleSpace, anchor: .leading, isSource: active)
            Spacer(minLength: 0)
            if song != nil {
                HStack(spacing: 12) {
                    LikeButtonView(size: 17, box: 40).modifier(RoundButtonBackground())
                    songMenu(song).modifier(RoundButtonBackground())
                }
                .matchedGeometryEffect(id: "actions", in: titleSpace, anchor: .trailing, isSource: active)
            }
        }
        .foregroundStyle(.primary)
    }

    private func closePanels() {
        shell.queueOpen = false
        LyricsSheetState.shared.open = false
    }

    /// 「…」のメニュー（Claude に聞く・共有・曲のメニュー）。
    private func songMenu(_ song: Song?) -> some View {
        Menu {
            Button { aiOpen = true } label: { Label(L.t("Claude に聞く", "Ask Claude"), systemImage: "sparkles") }
            // RSS のエピソードには YouTube の共有のリンクが無い（ADR-0012）
            if let song, !Feed.isExternal(song.videoId) {
                ShareLink(item: SongActions.url(song)) { Label(L.t("共有", "Share"), systemImage: "square.and.arrow.up") }
            }
            if let song { SongMenuItems(song: song) }
        } label: {
            Image(systemName: "ellipsis").font(.system(size: 17, weight: .semibold)).frame(width: 40, height: 40)
        }
    }

    /// 曲名とアーティスト、右に評価と「…」（Claude に聞く・共有・曲のメニュー）。
    private func portraitTitleRow(_ player: PlayerService, _ song: Song?) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(song?.title ?? L.t("何も再生していません", "Nothing playing"))
                    .font(.title3.weight(.semibold)).modifier(FadeTail())
                    .onTapGesture { if let id = song?.album?.browseId { AppServices.nav.album(id) } }
                Text(song?.artistsText ?? "").font(.title3).foregroundStyle(.secondary).modifier(FadeTail())
                    .onTapGesture { if let id = song?.artists.first(where: { $0.browseId != nil })?.browseId { AppServices.nav.artist(id) } }
            }
            .matchedGeometryEffect(id: "title", in: titleSpace, anchor: .leading)
            // 小さなジャケットは、開くとここ（曲名の左の端）から上の行へ滑って大きくなり、閉じるとここへ戻って消える
            .background(alignment: .leading) {
                Color.clear.frame(width: 1, height: 56).matchedGeometryEffect(id: "cover", in: titleSpace).allowsHitTesting(false)
            }
            Spacer(minLength: 0)
            if song != nil {
                HStack(spacing: 12) {
                    LikeButtonView(size: 17, box: 40).modifier(RoundButtonBackground())
                    songMenu(song)
                    .modifier(RoundButtonBackground())
                }
                .matchedGeometryEffect(id: "actions", in: titleSpace, anchor: .trailing)
            }
        }
        .foregroundStyle(.primary)
    }

    /// 前・再生・次を大きく、両端にシャッフルとリピートを小さく。
    private func portraitControls(_ player: PlayerService) -> some View {
        HStack {
            Button(action: player.toggleShuffle) {
                Image(systemName: "shuffle").font(.body.weight(.semibold))
                    .foregroundStyle(player.shuffle ? Color.accentColor : Color.secondary)
                    .frame(width: 44, height: 44)
            }
            .disabled(!player.canChangeModes)
            .accessibilityLabel(L.t("シャッフル", "Shuffle"))
            Spacer()
            Button(action: player.previous) { Image(systemName: "backward.fill").font(.system(size: 34)).frame(width: 64, height: 64) }
                .accessibilityLabel(L.t("前へ", "Previous"))
            Spacer()
            Button(action: player.togglePlay) {
                ZStack {
                    if player.isLoading {
                        ProgressView().controlSize(.large)
                    } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 46))
                    }
                }
                .frame(width: 72, height: 72)
                .contentShape(Rectangle())
            }
            .accessibilityLabel(player.isPlaying ? L.t("一時停止", "Pause") : L.t("再生", "Play"))
            Spacer()
            Button(action: player.next) { Image(systemName: "forward.fill").font(.system(size: 34)).frame(width: 64, height: 64) }
                .accessibilityLabel(L.t("次へ", "Next"))
            Spacer()
            Button(action: player.cycleRepeat) {
                Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat").font(.body.weight(.semibold))
                    .foregroundStyle(player.repeatMode != .off ? Color.accentColor : Color.secondary)
                    .frame(width: 44, height: 44)
            }
            .disabled(!player.canChangeModes)
            .accessibilityLabel(L.t("リピート", "Repeat"))
        }
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
    }

    /// 下の段: 歌詞・動画（MV のある曲とライブ）・端末・キュー（Apple Music の歌詞・AirPlay・次に再生と同じ置き場）。
    private func portraitBottomRow(_ player: PlayerService) -> some View {
        let hasLyrics = if case .found = player.lyrics { true } else { false }
        return HStack {
            Button {
                // ジャケットの場所を歌詞に切り替える（もう一度押すとジャケットに戻る。縦向きも横向きも）
                let open = !LyricsSheetState.shared.open
                shell.queueOpen = false
                LyricsSheetState.shared.open = open
            } label: {
                Image(systemName: LyricsSheetState.shared.open ? "quote.bubble.fill" : "quote.bubble")
                    .foregroundStyle(LyricsSheetState.shared.open ? Color.accentColor : Color.primary)
                    .frame(width: 44, height: 44)
            }
            .disabled(!hasLyrics)
            .accessibilityLabel(L.t("歌詞", "Lyrics"))
            if player.hasVideo {
                Spacer()
                Button { shell.showVideo.toggle() } label: {
                    Image(systemName: shell.showVideo ? "play.rectangle.fill" : "play.rectangle")
                        .foregroundStyle(shell.showVideo ? Color.accentColor : Color.primary)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel(shell.showVideo ? L.t("曲にする", "Show song") : L.t("動画にする", "Show video"))
            }
            if AppServices.connect.configured {
                Spacer()
                Button { devicesOpen = true } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "airplay.audio")
                        if player.isRemote, let device = player.remoteDevice { Text(device).font(.caption.weight(.semibold)).lineLimit(1) }
                    }
                    .foregroundStyle(.primary)
                    .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel(L.t("端末に接続", "Connect to a device"))
            }
            Spacer()
            Button {
                let open = !shell.queueOpen
                LyricsSheetState.shared.open = false
                shell.queueOpen = open
            } label: {
                Image(systemName: "list.bullet")
                    .foregroundStyle(shell.queueOpen ? Color.accentColor : Color.primary)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(L.t("キュー", "Queue"))
        }
        .font(.title3.weight(.semibold))
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
        .padding(.horizontal, 12)
    }

    /// ジャケット（左右に払うと前後の曲へ）か、MV のある曲・ライブで「動画」を選んでいれば映像（右下のボタンで全画面）。
    @ViewBuilder
    private func media(_ player: PlayerService) -> some View {
        if shell.showVideo, player.hasVideo {
            VideoSurface()
                // 枠は映像の縦横比に合わせる（分かるまでは 16:9）
                .aspectRatio(videoAspect, contentMode: .fit)
                .task(id: shell.showVideo) {
                    while !Task.isCancelled {
                        let item = (player.liveVideoPlayer ?? MusicVideoPlayer.shared.player).currentItem
                        if let size = item?.presentationSize, size.width > 0, size.height > 0 {
                            let aspect = size.width / size.height
                            if abs(aspect - videoAspect) > 0.01 { withAnimation(.snappy) { videoAspect = aspect } }
                        }
                        try? await Task.sleep(for: .milliseconds(500))
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(alignment: .bottomTrailing) {
                    HStack(spacing: 8) {
                        PipButton()
                        Button { shell.videoFullScreen = true } label: {
                            Image(systemName: "arrow.up.left.and.arrow.down.right").font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                                .frame(width: 40, height: 40).background(Circle().fill(Color.black.opacity(0.45)))
                        }
                        .accessibilityLabel(L.t("全画面", "Full screen"))
                    }
                    .padding(8)
                }
                .shadow(radius: 16, y: 8)
        } else if verticalSize == .compact {
            // 横向きでは、前後の曲のジャケットが右の操作の欄の下や画面の端にはみ出すので、払っている間だけ出す
            CoverCarousel(isolated: true)
        } else {
            CoverCarousel()
        }
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

    /// 音量のゲージ。この iPhone で鳴らしている間は本体の音量（PlayerService.volume が本体の音量を映す）。
    /// PC で鳴っている間はその PC のアプリの中の音量（iPhone の音量ボタンは横取りできないので、画面で変える）。
    private func volumeBar(_ player: PlayerService) -> some View {
        let value = Binding<Double>(get: { player.volume }, set: { player.volume = $0 })
        return HStack(spacing: 10) {
            Image(systemName: "speaker.fill").font(.caption)
            Slider(value: value, in: 0...1)
                .tint(.white)
                .accessibilityLabel(player.isRemote ? L.t("\(player.remoteDevice ?? "ほかの端末") の音量", "Volume on \(player.remoteDevice ?? "the other device")")
                    : L.t("音量", "Volume"))
            Image(systemName: "speaker.wave.3.fill").font(.caption)
        }
        .foregroundStyle(.secondary)
        .frame(minHeight: 44)
        // 本体の音量を動かすための見えない部品。うっすら透けないよう、画面の外に置く
        .background { SystemVolumeAnchor().frame(width: 1, height: 1).offset(x: -2000).allowsHitTesting(false) }
    }

}

/// 再生中の画面のジャケット。前後の曲のジャケットを横に並べておき（先に読んでおく）、指について動く。
/// 払いきると隣のジャケットが真ん中へ滑ってきて、その曲へ移る（前へ払うと、再生した長さによらず前の曲へ。Android と同じ）。
/// fullBleed でなければ正方形にする。
private struct SquareUnless: ViewModifier {
    var fullBleed: Bool
    init(_ fullBleed: Bool) { self.fullBleed = fullBleed }
    func body(content: Content) -> some View {
        if fullBleed { content } else { content.aspectRatio(1, contentMode: .fit) }
    }
}

/// 1行に収まらない文字は「…」で切らず、右の端を薄く消す（大きさが変わる動きの途中で「…」が出たり消えたりして跳ばないように）。
/// 幅は渡された幅だけを使い、文字の長さで広がらない（長い曲名の分だけ、再生画面の操作の欄が画面より広がっていた）。
private struct FadeTail: ViewModifier {
    @State private var natural: CGFloat = 0
    @State private var box: CGFloat = 0

    func body(content: Content) -> some View {
        let overflows = natural > box + 0.5
        FillWidth {
            content
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { natural = $0 }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { box = $0 }
        .clipped()
        .mask {
            HStack(spacing: 0) {
                Rectangle()
                LinearGradient(colors: [.black, .clear], startPoint: .leading, endPoint: .trailing).frame(width: overflows ? 28 : 0)
            }
            .animation(.easeOut(duration: 0.2), value: overflows)
        }
    }
}

/// 渡された幅いっぱいを取り、中身の幅では広がらない入れ物（中身は左に寄せ、はみ出した分は外で切る）。
private struct FillWidth: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let child = subviews.first?.sizeThatFits(.unspecified) ?? .zero
        let width = proposal.width.map { $0.isFinite ? $0 : child.width } ?? 0
        return CGSize(width: width, height: child.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading, proposal: .unspecified)
    }
}

/// 曲名の横の丸いボタンの地（iOS 26 からはガラス、それより前は薄い丸）。
private struct RoundButtonBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular.interactive(), in: .circle)
        } else {
            content.background(Circle().fill(.white.opacity(0.15)))
        }
    }
}

/// 再生画面（とアーティスト・アルバム・プレイリストのページ）の地: ジャケットを画面いっぱいに引き伸ばして大きくぼかし、文字が読めるよう暗くしたもの。曲が替わったら重ねて替える。
struct ArtworkBackdrop: View {
    var url: String?

    var body: some View {
        ZStack {
            Color.black
            // ぼかすので小さい絵で足りる。縁がぼけて透けないよう、少し大きくしてから不透明のままぼかす
            RemoteImage(url: Ui.resize(url, 240))
                .scaleEffect(1.4)
                .blur(radius: 60, opaque: true)
                .saturation(1.3)
                .id(url)
                .transition(.opacity)
            LinearGradient(colors: [.black.opacity(0.2), .black.opacity(0.45), .black.opacity(0.6)], startPoint: .top, endPoint: .bottom)
        }
        .animation(.easeInOut(duration: 0.6), value: url)
        .ignoresSafeArea()
    }
}

private struct CoverCarousel: View {
    /// 横向き: 前後の曲のジャケットは払っている間だけ出し、間を広く取って、影が収まる広さより外は見せない
    /// （右の操作の欄の下や画面の端にはみ出さず、真ん中のジャケットの影も切れない）。
    var isolated = false
    /// 画面の幅いっぱいに、角を丸めず影も付けずに出し、下の端を地へ溶かす（縦向きの再生画面）。
    var fullBleed = false
    /// fullBleed のとき、地へ溶かす端（縦向きは下、横向きは右の操作の列の側）。
    var fadeEdge: Edge = .bottom
    @State private var drag: CGFloat = 0
    @State private var settling = false

    private var gap: CGFloat { isolated ? 64 : fullBleed ? 0 : 24 }

    var body: some View {
        let player = AppServices.player!
        GeometryReader { geo in
            let step = geo.size.width + gap
            ZStack {
                ForEach(slots(player), id: \.index) { slot in
                    cover(slot.song)
                        .offset(x: CGFloat(slot.index - player.index) * step + drag)
                        .opacity(!isolated || slot.index == player.index || drag != 0 || settling ? 1 : 0)
                }
            }
            .mask {
                if fullBleed && fadeEdge == .trailing {
                    // 右の方は、後ろのぼかした絵へ溶かす（横向き）
                    // 緩やかな曲線で消す（直線で消すと、消え始めと消え終わりに縦の段が見える）
                    LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.42), .init(color: .black.opacity(0.75), location: 0.6),
                                           .init(color: .black.opacity(0.4), location: 0.75), .init(color: .black.opacity(0.12), location: 0.88), .init(color: .clear, location: 0.97)],
                                   startPoint: .leading, endPoint: .trailing)
                } else if fullBleed {
                    // 下の方は、後ろのぼかした絵へ溶かす
                    LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.5), .init(color: .clear, location: 0.78)],
                                   startPoint: .top, endPoint: .bottom)
                } else if isolated {
                    Rectangle().padding(.horizontal, -40).padding(.vertical, -200)
                } else {
                    Rectangle().padding(-10_000)
                }
            }
            // 上の端は少し暗くして、重ねた閉じる・メニューを読めるようにする
            .overlay(alignment: .top) {
                if fullBleed && fadeEdge == .bottom {
                    LinearGradient(colors: [.black.opacity(0.35), .clear], startPoint: .top, endPoint: .bottom)
                        .frame(height: 140)
                        .allowsHitTesting(false)
                }
            }
            .contentShape(Rectangle())
            // 横へ動き始めたらすぐ、画面をしまう指の動きを止める（下の払いより先に決める）
            .simultaneousGesture(DragGesture(minimumDistance: 3)
                .onChanged { value in
                    let horizontal = abs(value.translation.width) > abs(value.translation.height)
                    if horizontal && !IOSShell.shared.coverSwiping { IOSShell.shared.coverSwiping = true }
                }
                .onEnded { _ in IOSShell.shared.coverSwiping = false })
            // 縦の払い（スクロール・下へ払って閉じる）も通す
            .simultaneousGesture(DragGesture(minimumDistance: 16)
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
        // 画面の幅いっぱいのときは、渡された高さまで伸ばす（絵ははみ出した左右が切れる）。縦横比を付けると正方形のまま真ん中に置かれ、上に境目が出た
        .modifier(SquareUnless(fullBleed))
    }

    private func cover(_ song: Song) -> some View {
        ZStack {
            // 大きい絵を読むまで、ミニプレーヤーなどで読んである小さい絵を引き伸ばして出しておく
            RemoteImage(url: Ui.small(song.thumbnailUrl))
            RemoteImage(url: Ui.large(song.thumbnailUrl))
        }
        .background(Color.white.opacity(fullBleed ? 0 : 0.08))
        .clipShape(RoundedRectangle(cornerRadius: fullBleed ? 0 : 10))
        .shadow(radius: fullBleed ? 0 : 16, y: fullBleed ? 0 : 8)
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

/// 一番上までスクロールしてから、さらに下へ引っ張ったら閉じる（ジャケット以外のどこから払っても閉じられる）。
private struct PullDownToDismiss: ViewModifier {
    @Binding var touching: Bool
    var dismiss: () -> Void
    @State private var done = false

    func body(content: Content) -> some View {
        content
            .onScrollPhaseChange { _, phase in touching = phase == .interacting }
            .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y + $0.contentInsets.top } action: { _, y in
                guard touching, !done, y < -70 else { return }
                done = true
                dismiss()
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
/// 映像の全画面（横向きに回す。閉じると元の向きに戻す）。
/// YouTube と同じく、映像を押すと曲名・前後と再生・シークバー・小窓と全画面を終えるボタンを重ねて出し、もう一度押すか、
/// 鳴らしている間に触らず 3 秒たつと隠す。
private struct VideoFullScreen: View {
    @Environment(\.dismiss) private var dismiss
    @State private var before: UIInterfaceOrientationMask?
    @State private var shown = true
    /// 触るたびに増やし、隠すまでの 3 秒を数え直す。
    @State private var touched = 0
    @State private var dragging: Double?

    var body: some View {
        let player = AppServices.player!
        ZStack {
            Color.black.ignoresSafeArea()
            VideoSurface(priority: 1).ignoresSafeArea()
            if shown { controls(player).transition(.opacity) }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation(.easeOut(duration: 0.2)) { shown.toggle() }
            touched += 1
        }
        .task(id: "\(shown)-\(touched)-\(player.isPlaying)-\(dragging != nil)") {
            guard shown, player.isPlaying, dragging == nil else { return }
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.3)) { shown = false }
        }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .task {
            guard let scene = scene else { return }
            if scene.interfaceOrientation.isPortrait { before = .portrait }
            // 全画面が出てくる途中は縦しか受け付けない（断られる）ので、横になるまで少しずつ待って頼み直す
            for _ in 0..<8 {
                try? await Task.sleep(for: .milliseconds(250))
                if Task.isCancelled || scene.interfaceOrientation.isLandscape { return }
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight)) { error in
                    AppPaths.log("video full rotate retry \(error.localizedDescription)")
                }
            }
        }
        .onDisappear {
            // 縦で見ていたなら縦に戻す
            if let before, let scene { scene.requestGeometryUpdate(.iOS(interfaceOrientations: before)) }
        }
    }

    private func controls(_ player: PlayerService) -> some View {
        let song = player.current
        return ZStack {
            // 白い文字とボタンが明るい映像に埋もれないよう、全体を少し暗くし、上下はさらに暗くする（YouTube と同じ）
            Color.black.opacity(0.3).ignoresSafeArea().allowsHitTesting(false)
            LinearGradient(stops: [.init(color: .black.opacity(0.5), location: 0), .init(color: .clear, location: 0.3),
                                   .init(color: .clear, location: 0.65), .init(color: .black.opacity(0.6), location: 1)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .allowsHitTesting(false)
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(song?.title ?? "").font(.title3.weight(.semibold)).lineLimit(1)
                    Text(song?.artistsText ?? "").font(.subheadline).foregroundStyle(.white.opacity(0.75)).lineLimit(1)
                }
                Spacer(minLength: 0)
                HStack(spacing: 12) {
                    if player.isLive {
                        LiveBadge(font: .subheadline.weight(.bold), dot: 9)
                        Spacer(minLength: 0)
                    } else {
                        Text(Ui.time(dragging ?? player.position)).font(.caption).monospacedDigit()
                        Slider(value: Binding(get: { dragging ?? min(player.position, max(1, player.duration)) }, set: { dragging = $0 }),
                               in: 0...max(1, player.duration)) { editing in
                            // 指を離したときだけ移る
                            if !editing, let value = dragging {
                                player.seek(value)
                                dragging = nil
                                touched += 1
                            }
                        }
                        .tint(.white)
                        Text(Ui.time(player.duration)).font(.caption).monospacedDigit()
                    }
                    PipButton()
                    Button { dismiss() } label: {
                        Image(systemName: "arrow.down.right.and.arrow.up.left").font(.system(size: 16, weight: .semibold))
                            .frame(width: 44, height: 44).background(Circle().fill(Color.black.opacity(0.45)))
                    }
                    .accessibilityLabel(L.t("全画面を終える", "Exit full screen"))
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            HStack(spacing: 56) {
                Button { player.previous(); touched += 1 } label: {
                    Image(systemName: "backward.end.fill").font(.system(size: 24))
                        .frame(width: 56, height: 56).background(Circle().fill(Color.black.opacity(0.45)))
                }
                    .accessibilityLabel(L.t("前へ", "Previous"))
                Button { player.togglePlay(); touched += 1 } label: {
                    ZStack {
                        Circle().fill(Color.black.opacity(0.45)).frame(width: 72, height: 72)
                        if player.isLoading {
                            ProgressView().tint(.white)
                        } else {
                            Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 30))
                        }
                    }
                }
                .accessibilityLabel(player.isPlaying ? L.t("一時停止", "Pause") : L.t("再生", "Play"))
                Button { player.next(); touched += 1 } label: {
                    Image(systemName: "forward.end.fill").font(.system(size: 24))
                        .frame(width: 56, height: 56).background(Circle().fill(Color.black.opacity(0.45)))
                }
                    .accessibilityLabel(L.t("次へ", "Next"))
            }
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
    }

    private var scene: UIWindowScene? { UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first }
}

struct VideoSurface: View {
    /// 枠を取り合ったときの優先（全画面は 1）。
    var priority = 0

    var body: some View {
        let video = MusicVideoPlayer.shared
        ZStack {
            // 枠は PiP と共通の1つを使い回す。ライブは鳴らしている再生器の映像をそのまま出す
            PipSurface(player: AppServices.player.liveVideoPlayer ?? video.player, priority: priority)
            // MV に無い区間・読み込み中は黒地にする
            if AppServices.player.liveVideoPlayer == nil && !video.showing { Color.black }
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

/// 映像の PiP（小窓）を始める・やめるボタン（PiP を使えない機種では出さない）。
private struct PipButton: View {
    @State private var pip = VideoPip.shared

    var body: some View {
        if pip.supported {
            Button { pip.toggle() } label: {
                Image(systemName: pip.active ? "pip.exit" : "pip.enter").font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 40, height: 40).background(Circle().fill(Color.black.opacity(0.45)))
            }
            .accessibilityLabel(pip.active ? L.t("小窓を閉じる", "Close picture in picture") : L.t("小窓で見る", "Picture in picture"))
        }
    }
}

#endif
