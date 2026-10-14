#if os(macOS)
import AppKit
import SwiftUI
import WaveCore

/// メニューバーの再生中の曲（「**曲名** - アーティスト名」）。押すと、右の欄の再生中の画面と同じ並びの小窓（Liquid Glass の板）を出し、そこで操作できる。
/// 右クリックで再生/一時停止。カーソルを乗せて2本指で横に払うと曲送り・戻し、縦に払うと音量。
@MainActor
final class MenuBarController: NSObject {
    static let shared = MenuBarController()

    /// メニューバーに出す長さ（長い曲名で右側のアイコンを押し出さない）。
    private static let maxTitle = 32
    private static let maxArtist = 24
    /// 小窓とメニューバーのすきま。
    private static let gap: CGFloat = 6

    private var item: NSStatusItem?
    private var panel: GlassPanel?
    private var clickMonitors: [Any] = []
    /// 音量を変えている間だけ、曲名の代わりに音量を出す。
    private var volumeShownUntil: Date?
    private var labelTimer: Timer?
    /// 曲名の上の受け口。スクロールは見張りから回す（下の watchScroll）。
    private var catcher: StatusGestureView?
    private var scrollMonitors: [Any] = []
    /// 曲名の上で始まった払いの途中か。音量を出すと項目の幅と位置が変わり、カーソルが外れるので、払いが終わるまでは回し続ける。
    private var inGesture = false

    func start() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        self.item = item
        if let button = item.button {
            let catcher = StatusGestureView(frame: button.bounds)
            catcher.autoresizingMask = [.width, .height]
            catcher.onClick = { [weak self] in self?.toggle() }
            catcher.onRightClick = { AppServices.player.togglePlay() }
            catcher.onSwipe = { forward in if forward { AppServices.player.next() } else { AppServices.player.previous() } }
            catcher.onVolume = { [weak self] delta in self?.changeVolume(delta) }
            button.addSubview(catcher)
            self.catcher = catcher
        }
        watchScroll()
        render()
    }

    /// メニューバーの項目の上のスクロール（2本指の払い・ホイール）は、項目の view まで届かない。
    /// アプリ宛て（ローカル）とほかのアプリ宛て（グローバル。メニューバーの上は他の処理が受けることがある）を見張り、
    /// カーソルが曲名の上にあるものだけを受け口へ回す。
    private func watchScroll() {
        if let local = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel, handler: { [weak self] event in
            (self?.routeScroll(event, via: "local") ?? false) ? nil : event
        }) { scrollMonitors.append(local) }
        if let global = NSEvent.addGlobalMonitorForEvents(matching: .scrollWheel, handler: { [weak self] event in
            _ = self?.routeScroll(event, via: "global")
        }) { scrollMonitors.append(global) }
    }

    /// カーソルが曲名の上か、曲名の上で始まった払いの途中なら、受け口へ回して true。
    private func routeScroll(_ event: NSEvent, via: String) -> Bool {
        guard let button = item?.button, let window = button.window, let catcher else { return false }
        let frame = window.convertToScreen(button.convert(button.bounds, to: nil))
        let inside = frame.contains(NSEvent.mouseLocation)
        if event.phase == .began || event.phase == .mayBegin { inGesture = inside }
        let continuing = inGesture && (event.phase != [] || event.momentumPhase != [])
        guard inside || continuing else { return false }
        // 1回の払いに1行（phase の無いものは届くたび）
        if event.phase == .began || event.phase == [] && event.momentumPhase == [] { AppPaths.log("menubar scroll via=\(via)") }
        catcher.scrollWheel(with: event)
        return true
    }

    /// 曲が変わるたびに書き直す（Observation で見張る）。
    private func render() {
        let player = AppServices.player!
        let title = withObservationTracking {
            player.current.map { (title: $0.title, artist: $0.artistsText) }
        } onChange: { [weak self] in
            Task { @MainActor in self?.render() }
        }
        guard let button = item?.button else { return }
        if let until = volumeShownUntil, until > Date() {
            button.image = NSImage(systemSymbolName: MenuBarController.volumeSymbol(player.volume), accessibilityDescription: nil)
            button.imagePosition = .imageLeading
            button.attributedTitle = NSAttributedString(string: " \(Int((player.volume * 100).rounded()))%", attributes: [.font: NSFont.monospacedDigitSystemFont(ofSize: NSFont.systemFontSize, weight: .semibold)])
        } else if let title {
            button.image = nil
            button.attributedTitle = MenuBarController.label(title.title, title.artist)
        } else {
            button.attributedTitle = NSAttributedString()
            button.image = NSImage(systemSymbolName: "music.note", accessibilityDescription: "wave")
        }
    }

    static func label(_ title: String, _ artist: String) -> NSAttributedString {
        let size = NSFont.systemFontSize
        let text = NSMutableAttributedString(string: shorten(title, maxTitle), attributes: [.font: NSFont.boldSystemFont(ofSize: size)])
        if !artist.isEmpty {
            text.append(NSAttributedString(string: " - " + shorten(artist, maxArtist), attributes: [.font: NSFont.systemFont(ofSize: size)]))
        }
        return text
    }

    private static func shorten(_ text: String, _ max: Int) -> String {
        text.count > max ? String(text.prefix(max - 1)) + "…" : text
    }

    private static func volumeSymbol(_ v: Double) -> String {
        v <= 0 ? "speaker.slash.fill" : v < 0.34 ? "speaker.wave.1.fill" : v < 0.67 ? "speaker.wave.2.fill" : "speaker.wave.3.fill"
    }

    /// 縦の払いで音量を変え、少しの間メニューバーに音量を出す。
    private func changeVolume(_ delta: Double) {
        let player = AppServices.player!
        player.volume = min(1, max(0, player.volume + delta))
        volumeShownUntil = Date().addingTimeInterval(1.2)
        render()
        labelTimer?.invalidate()
        labelTimer = Timer.scheduledTimer(withTimeInterval: 1.3, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.volumeShownUntil = nil
                AppServices.settings.volume = AppServices.player.volume
                AppServices.settings.save()
                self?.render()
            }
        }
    }

    func toggle() {
        if let panel, panel.isVisible { close() } else { open() }
    }

    private func open() {
        guard let button = item?.button, let buttonWindow = button.window else { return }
        let panel = self.panel ?? makePanel()
        self.panel = panel
        let anchor = buttonWindow.convertToScreen(button.convert(button.bounds, to: nil))
        place(panel, size: panel.contentView?.fittingSize ?? NSSize(width: 352, height: 600), under: anchor)
        panel.makeKeyAndOrderFront(nil)
        // 小窓の外（ほかのアプリ・wave の主画面）を押したら閉じる
        clickMonitors.append(NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] _ in
            MainActor.assumeIsolated { self?.close() }
        } as Any)
        clickMonitors.append(NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown, .keyDown]) { [weak self] event in
            let isKey = event.type == .keyDown, isEsc = event.keyCode == 53, window = event.window
            let swallow = MainActor.assumeIsolated { () -> Bool in
                guard let self, let panel = self.panel else { return false }
                if isKey {
                    guard isEsc, window === panel else { return false }
                    self.close()
                    return true
                }
                // メニューバーの曲名を押したときは、そちらの開け閉めに任せる
                if window !== panel, window !== self.item?.button?.window { self.close() }
                return false
            }
            return swallow ? nil : event
        } as Any)
    }

    func close() {
        clickMonitors.forEach { NSEvent.removeMonitor($0) }
        clickMonitors = []
        panel?.orderOut(nil)
    }

    private func makePanel() -> GlassPanel {
        let panel = GlassPanel(contentRect: NSRect(x: 0, y: 0, width: 352, height: 600), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.level = .popUpMenu
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        let root = MenuBarNowPlaying(close: { [weak self] in self?.close() }, resized: { [weak self] size in
            guard let self, let panel = self.panel, panel.isVisible, let button = self.item?.button, let window = button.window else { return }
            self.place(panel, size: size, under: window.convertToScreen(button.convert(button.bounds, to: nil)))
        })
        let host = NSHostingView(rootView: root)
        host.sizingOptions = [.intrinsicContentSize]
        panel.contentView = host
        return panel
    }

    /// メニューバーの曲名の真下に、画面からはみ出さないよう置く（中身の高さが変わっても上端は動かさない）。
    private func place(_ panel: NSPanel, size: NSSize, under anchor: NSRect) {
        let screen = (NSScreen.screens.first { $0.frame.intersects(anchor) } ?? NSScreen.main)?.visibleFrame ?? .zero
        var x = anchor.midX - size.width / 2
        x = min(max(x, screen.minX + 8), screen.maxX - size.width - 8)
        let top = anchor.minY - MenuBarController.gap
        panel.setFrame(NSRect(x: x, y: top - size.height, width: size.width, height: size.height), display: true)
    }

    /// 主画面を前に出す（小窓からアルバム・アーティストへ移ったとき）。
    static func showMainWindow() {
        NSApp.activate(ignoringOtherApps: true)
        let main = NSApp.windows.first { $0.identifier?.rawValue.hasPrefix("main") == true }
            ?? NSApp.windows.first { $0.canBecomeMain && $0.identifier?.rawValue != "login" }
        if let main {
            if main.isMiniaturized { main.deminiaturize(nil) }
            main.makeKeyAndOrderFront(nil)
        }
    }
}

/// 小窓の窓（枠なし。wave を前に出さずに文字やキーを受けられる）。
final class GlassPanel: NSPanel {
    override var canBecomeKey: Bool { true }
}

/// メニューバーの曲名の上に重ねて、押す・右クリック・2本指の払いを受ける。
final class StatusGestureView: NSView {
    var onClick: () -> Void = {}
    var onRightClick: () -> Void = {}
    /// true で次の曲、false で前の曲。
    var onSwipe: (Bool) -> Void = { _ in }
    /// 音量の増減（0〜1 の幅で）。
    var onVolume: (Double) -> Void = { _ in }

    /// 1回の払いで横にこれだけ動いたら曲を送る（点）。
    private static let swipeDistance: CGFloat = 40
    private var swipeX: CGFloat = 0
    private var swipeY: CGFloat = 0
    /// この払いで曲を送ったか・向きが決まったか（1回の払いで何曲も飛ばさない。横と縦を混ぜない）。
    private var swiped = false
    private enum Axis { case none, horizontal, vertical }
    private var axis = Axis.none

    override func mouseDown(with event: NSEvent) {
        (superview as? NSButton)?.highlight(true)
    }

    override func mouseUp(with event: NSEvent) {
        (superview as? NSButton)?.highlight(false)
        if bounds.contains(convert(event.locationInWindow, from: nil)) { onClick() }
    }

    override func rightMouseDown(with event: NSEvent) {
        onRightClick()
    }

    override func scrollWheel(with event: NSEvent) {
        // トラックパッドなど細かく届くもの（phase あり）と、マウスのホイール（1目盛りずつ）を分けて扱う
        guard event.hasPreciseScrollingDeltas else {
            AppPaths.log("menubar wheel y=\(event.scrollingDeltaY)")
            if event.scrollingDeltaY != 0 { onVolume(event.scrollingDeltaY > 0 ? 0.05 : -0.05) }
            return
        }
        if event.phase == .ended { AppPaths.log("menubar swipe x=\(Int(swipeX)) y=\(Int(swipeY)) axis=\(axis)") }
        if event.phase == .began || event.phase == .mayBegin {
            swipeX = 0
            swipeY = 0
            swiped = false
            axis = .none
        }
        // 指の動いた向き（「ナチュラル」なスクロールの設定によらない）
        let sign: CGFloat = event.isDirectionInvertedFromDevice ? 1 : -1
        let fingerX = event.scrollingDeltaX * sign
        let fingerUp = -event.scrollingDeltaY * sign
        swipeX += fingerX
        swipeY += fingerUp
        if axis == .none, max(abs(swipeX), abs(swipeY)) > 6 { axis = abs(swipeX) > abs(swipeY) ? .horizontal : .vertical }
        switch axis {
        case .horizontal:
            // 左へ払うと次の曲、右へ払うと前の曲（写真やページをめくるのと同じ）
            if !swiped, abs(swipeX) >= StatusGestureView.swipeDistance {
                swiped = true
                onSwipe(swipeX < 0)
            }
        case .vertical:
            if event.momentumPhase == [] || event.momentumPhase == .changed { onVolume(Double(fingerUp) * 0.004) }
        case .none:
            break
        }
    }
}

/// メニューバーの小窓（右の欄の再生中の画面と同じ並び: ジャケット・曲名・評価・操作・次の曲・歌詞）。
struct MenuBarNowPlaying: View {
    var close: () -> Void
    /// 中身の大きさが変わった（歌詞の有無など）。窓の大きさを合わせる。
    var resized: (CGSize) -> Void = { _ in }

    var body: some View {
        let player = AppServices.player!
        let song = player.current
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(song?.album?.title.isEmpty == false ? song!.album!.title : L.t("再生中", "Now playing"))
                    .font(.body.weight(.semibold)).lineLimit(1)
                Spacer()
                Button {
                    close()
                    MenuBarController.showMainWindow()
                } label: { Image(systemName: "macwindow") }
                    .buttonStyle(IconButtonStyle()).help(L.t("wave を開く", "Open wave"))
            }
            ZStack(alignment: .top) {
                VideoOrCover(size: 288, inFullView: false)
                if player.video != nil { SongVideoSwitch().padding(.top, 10) }
            }
            .frame(height: 288)
            HStack(alignment: .top, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    LinkText(text: song?.title ?? L.t("何も再生していません", "Nothing playing"), action: SongLinks.openAlbum)
                        .font(.title3.weight(.semibold))
                        .lineLimit(2)
                    // ガラスの上ではアクセントの青が沈んで読みにくいので、白寄りの文字にする
                    ArtistLink(font: .body, color: .primary.opacity(0.75))
                }
                Spacer(minLength: 0)
                if song != nil { LikeButton.view(player, size: 20) }
            }
            TransportControls()
            if player.index + 1 < player.queue.count {
                let next = player.queue[player.index + 1]
                VStack(alignment: .leading, spacing: 8) {
                    Text(L.t("次の曲", "Next up")).font(.body.weight(.semibold))
                    HStack(spacing: 12) {
                        Cover(url: Ui.small(next.thumbnailUrl), size: 40, radius: 4)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(next.title).lineLimit(1)
                            Text(next.artistsText).subtle()
                        }
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.05)))
            }
            if case .found(let lyrics) = player.lyrics, song != nil {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L.t("歌詞", "Lyrics")).font(.body.weight(.semibold))
                    LyricsFollower(lyrics: lyrics, positionMs: Int64(player.position * 1000), fontSize: 15)
                        .frame(height: 120, alignment: .top)
                        .clipped()
                        .allowsHitTesting(false)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.05)))
            }
        }
        .padding(16)
        .frame(width: 320)
        .fixedSize(horizontal: false, vertical: true)
        .modifier(GlassBackground())
        .onGeometryChange(for: CGSize.self) { $0.size } action: { resized($0) }
        // 小窓の中のリンク（アルバム・アーティスト）で移ったら、主画面を前に出して小窓を閉じる
        .onChange(of: AppServices.nav.current) { _, _ in
            close()
            MenuBarController.showMainWindow()
        }
    }
}

/// 小窓の地（macOS 26 以降は Liquid Glass、それより前はすりガラス）。
private struct GlassBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(macOS 26.0, *) {
            content.glassEffect(.regular, in: .rect(cornerRadius: 22))
        } else {
            content.background(.regularMaterial, in: RoundedRectangle(cornerRadius: 22))
        }
    }
}
#endif
