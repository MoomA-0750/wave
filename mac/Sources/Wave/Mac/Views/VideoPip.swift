#if os(macOS)
import AppKit
import SwiftUI

/// 映像の小窓（ピクチャ・イン・ピクチャ）。ほかのアプリや全画面の上にも浮かぶ小さな窓で、映像を出し続ける。
/// macOS の PiP の窓は使わない（映像の枠を画面ごとに作り直すので、PiP の元の枠を保てない）。Windows の小窓と同じ作り。
@MainActor
final class VideoPip: NSObject, NSWindowDelegate {
    static let shared = VideoPip()
    private var panel: NSPanel?

    func toggle() { MainWindowModel.shared.pipOpen ? close() : open() }

    func open() {
        let model = MainWindowModel.shared
        model.showVideo = true
        // 窓の中で映像だけを出していたら、そちらは閉じる（同じ映像を2か所に大きく出さない）
        if model.videoOnly { model.toggleVideoOnly() }
        let panel = panel ?? makePanel()
        self.panel = panel
        if !model.pipOpen { place(panel) }
        model.pipOpen = true
        panel.orderFrontRegardless()
    }

    func close() {
        MainWindowModel.shared.pipOpen = false
        panel?.orderOut(nil)
    }

    /// 小窓を閉じて、主の窓で映像を大きく出す。
    func backToApp() {
        close()
        MainWindowModel.shared.expandVideo()
    }

    func windowWillClose(_ notification: Notification) {
        MainWindowModel.shared.pipOpen = false
    }

    private func makePanel() -> NSPanel {
        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 400, height: 225),
            styleMask: [.titled, .closable, .resizable, .fullSizeContentView, .nonactivatingPanel],
            backing: .buffered, defer: false)
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.standardWindowButton(.miniaturizeButton)?.isHidden = true
        panel.standardWindowButton(.zoomButton)?.isHidden = true
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.isMovableByWindowBackground = true
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.contentAspectRatio = NSSize(width: 16, height: 9)
        panel.minSize = NSSize(width: 240, height: 135)
        panel.backgroundColor = .black
        panel.delegate = self
        panel.contentView = NSHostingView(rootView: PipView())
        return panel
    }

    /// 画面の右下に置く（Spotify・YouTube の PiP と同じ）。
    private func place(_ panel: NSPanel) {
        guard let screen = NSScreen.main?.visibleFrame else { return }
        let size = panel.frame.size
        panel.setFrameOrigin(NSPoint(x: screen.maxX - size.width - 20, y: screen.minY + 20))
    }
}

/// 小窓の中身。映像（無い曲はジャケット）と、指を乗せたときだけ出る操作。
private struct PipView: View {
    @State private var hover = false

    var body: some View {
        let player = AppServices.player!
        ZStack {
            Color.black
            VideoOrCover(size: nil, inFullView: true)
            if hover {
                Color.black.opacity(0.35)
                HStack(spacing: 18) {
                    Button { player.previous() } label: { Image(systemName: "backward.fill").font(.system(size: 18)) }
                        .help(L.t("前へ", "Previous"))
                    Button { player.togglePlay() } label: { Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 26)) }
                        .help(player.isPlaying ? L.t("一時停止", "Pause") : L.t("再生", "Play"))
                    Button { player.next() } label: { Image(systemName: "forward.fill").font(.system(size: 18)) }
                        .help(L.t("次へ", "Next"))
                }
                .buttonStyle(IconButtonStyle(size: 40))
                Button { VideoPip.shared.backToApp() } label: { Image(systemName: "arrow.up.backward.and.arrow.down.forward").font(.system(size: 13, weight: .semibold)) }
                    .buttonStyle(IconButtonStyle(size: 28))
                    .help(L.t("wave に戻して大きく見る", "Back to wave"))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    .padding(6)
            }
        }
        .foregroundStyle(.white)
        .environment(\.colorScheme, .dark)
        // 小窓は wave を前に出さないので、どのアプリが前でも指の出入りを受ける見張りを使う（onHover は wave が前のときだけ）
        .background(HoverTracker(hover: $hover))
        .onTapGesture(count: 2) { VideoPip.shared.backToApp() }
        .ignoresSafeArea()
    }
}

/// どのアプリが前でも、指の出入りを知らせる。
private struct HoverTracker: NSViewRepresentable {
    @Binding var hover: Bool

    final class TrackingView: NSView {
        var onChange: ((Bool) -> Void)?

        override func updateTrackingAreas() {
            super.updateTrackingAreas()
            trackingAreas.forEach(removeTrackingArea)
            addTrackingArea(NSTrackingArea(rect: bounds, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect], owner: self))
        }

        override func mouseEntered(with event: NSEvent) { onChange?(true) }
        override func mouseExited(with event: NSEvent) { onChange?(false) }
    }

    func makeNSView(context: Context) -> TrackingView {
        let view = TrackingView()
        view.onChange = { hover = $0 }
        return view
    }

    func updateNSView(_ view: TrackingView, context: Context) {
        view.onChange = { hover = $0 }
    }
}
#endif
