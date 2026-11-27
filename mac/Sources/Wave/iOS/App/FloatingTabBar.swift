#if os(iOS)
import SwiftUI
import UIKit
import WaveCore

/// 自前のタブバーとミニプレーヤーの寸法（下の安全な余白からの高さ）。タブバーもミニプレーヤーも同じ表で動かすので、ずれない。
enum FloatingBarMetrics {
    /// 広げたタブバー・丸の高さ
    static let barHeight: CGFloat = 56
    /// 畳んだときの丸・ミニプレーヤーの高さ
    static let compactHeight: CGFloat = 48
    /// タブバーの下の端（安全な余白より下へ 6pt はみ出す。OS のタブバーと同じ）
    static let bottom: CGFloat = -6
    /// 要素どうしのすき間
    static let gap: CGFloat = 8
    /// 広げたタブバーの上の端
    static var tabTop: CGFloat { bottom + barHeight }
    /// 広げたミニプレーヤー（52pt）の上の端
    static var miniTop: CGFloat { tabTop + gap + 52 }
    /// 畳んだ一列の上の端
    static var compactTop: CGFloat { bottom + compactHeight }
}

/// 自前の画面の枠（iOS 26 から）。OS の `TabView` を使わず、タブの中身・タブバー・ミニプレーヤーを1つの状態（広げる／一列に畳む）で動かす。
/// 畳む・広げる: 下へ送り始めると畳み、一番上に戻る・畳んだ丸をタップする・タブを替えると広げる（OS のタブバーと同じ決まり）。
@available(iOS 26.0, *)
struct CustomTabShell: View {
    var zoom: Namespace.ID
    var hasSong: Bool
    var open: () -> Void
    @State private var shell = IOSShell.shared
    @State private var visited: Set<IOSTab> = [IOSShell.shared.tab]

    var body: some View {
        ZStack {
            // 一度開いたタブは残す（スクロール位置を覚えておく）。見えていないタブは画面の外へ退ける
            ForEach(Self.all, id: \.self) { tab in
                if visited.contains(tab) || shell.tab == tab {
                    let on = shell.tab == tab
                    TabStack(tab: tab)
                        .offset(x: on ? 0 : 10_000)
                        .allowsHitTesting(on)
                        .accessibilityHidden(!on)
                }
            }
        }
        // 中身の下に、広げたときの高さだけ空ける（畳み・広げで変えない。変えるとスクロールがずれる）
        .safeAreaInset(edge: .bottom) {
            Color.clear.frame(height: FloatingBarMetrics.tabTop + (hasSong ? FloatingBarMetrics.gap + 52 : 0)).allowsHitTesting(false)
        }
        .overlay(alignment: .bottom) {
            FloatingBar(zoom: zoom, hasSong: hasSong, open: open)
                .padding(.bottom, FloatingBarMetrics.bottom)
                .ignoresSafeArea(.keyboard)
        }
        .onChange(of: shell.tab) { _, tab in
            visited.insert(tab)
            setCompact(false)
        }
        .task { await watchScroll() }
    }

    static let all: [IOSTab] = [.home, .search, .ai, .library]

    private func setCompact(_ value: Bool) {
        guard shell.barCompact != value else { return }
        withAnimation(.snappy(duration: 0.32)) { shell.barCompact = value }
    }

    /// 見えている縦のスクロール面の位置を見て、畳む・広げるを決める。指の動きでなくスクロールの位置を見るので、少しの動きでも拾う。
    private func watchScroll() async {
        var sv: UIScrollView?
        var tick = 0
        var last: CGFloat = 0
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(40))
            tick += 1
            guard shell.topLayer == .root else { continue }
            if sv == nil || sv?.window == nil || tick % 12 == 0 { sv = ScrollProbe.main() }
            guard let view = sv else { continue }
            let y = view.contentOffset.y + view.adjustedContentInset.top   // 0 が一番上
            if shell.barCompact {
                if y <= 2 { setCompact(false) }
            } else if y > 6, y - last >= 1 {
                setCompact(true)
            }
            last = y
        }
    }
}

/// 下に浮かぶ、タブバー・ミニプレーヤー・右の丸。広げた形は「ミニプレーヤー／タブの欄＋右の丸」の2段、畳んだ形は「今のタブの丸＋ミニプレーヤー＋右の丸」の一列。
@available(iOS 26.0, *)
struct FloatingBar: View {
    var zoom: Namespace.ID
    var hasSong: Bool
    var open: () -> Void
    @State private var shell = IOSShell.shared
    @Namespace private var glass

    private var compact: Bool { shell.barCompact }
    private var pinned: IOSTab { shell.pinnedTab }
    /// 欄に並べるタブ（右に離して置くタブを除く）
    private var inBar: [IOSTab] { [.home, .search, .ai, .library].filter { $0 != pinned } }

    var body: some View {
        GlassEffectContainer(spacing: FloatingBarMetrics.gap) {
            if compact {
                HStack(spacing: FloatingBarMetrics.gap) {
                    // 畳んだタブバーは、今のタブの丸。押すと広げる
                    Button { expand() } label: { icon(shell.tab, size: FloatingBarMetrics.compactHeight, selected: true) }
                        .buttonStyle(.plain)
                        .accessibilityLabel(shell.tab.title)
                    if hasSong {
                        MiniPlayerCard(zoom: zoom, open: open, compact: true)
                    } else {
                        Spacer(minLength: 0)
                    }
                    Button { shell.select(pinned) } label: { icon(pinned, size: FloatingBarMetrics.compactHeight, selected: shell.tab == pinned) }
                        .buttonStyle(.plain)
                        .accessibilityLabel(pinned.title)
                }
                .padding(.horizontal, 28)
                .transition(.opacity)
            } else {
                VStack(spacing: FloatingBarMetrics.gap) {
                    if hasSong {
                        MiniPlayerCard(zoom: zoom, open: open, compact: false)
                            .padding(.horizontal, Kasane.Space.s3)
                    }
                    HStack(spacing: FloatingBarMetrics.gap) {
                        HStack(spacing: 0) {
                            ForEach(inBar, id: \.self) { tab in item(tab) }
                        }
                        .padding(4)
                        .frame(height: FloatingBarMetrics.barHeight)
                        .glassEffect(.regular.interactive(), in: Capsule())
                        Button { shell.select(pinned) } label: { icon(pinned, size: FloatingBarMetrics.barHeight, selected: shell.tab == pinned) }
                            .buttonStyle(.plain)
                            .accessibilityLabel(pinned.title)
                    }
                    .padding(.horizontal, 21)
                }
                .transition(.opacity)
            }
        }
        .animation(.snappy(duration: 0.32), value: compact)
    }

    private func expand() {
        withAnimation(.snappy(duration: 0.32)) { shell.barCompact = false }
    }

    /// 欄の中の1つのタブ（上に記号、下に名前。選んでいるものは色が付き、薄い丸い面が敷かれる）
    private func item(_ tab: IOSTab) -> some View {
        let on = shell.tab == tab
        return Button { shell.select(tab) } label: {
            VStack(spacing: 2) {
                Image(systemName: tab.symbol).font(.system(size: 22)) // apple-ui-allow: タブの記号の大きさ（OS のタブバーに合わせる）
                Text(tab.title).font(.caption2.weight(.medium))
            }
            .foregroundStyle(on ? Color.accentColor : Color.primary)
            .frame(maxWidth: .infinity)
            .frame(height: FloatingBarMetrics.barHeight - 8)
            .background { if on { Capsule().fill(Color.primary.opacity(0.14)) } }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tab.title)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    /// 丸（右に離して置くタブ・畳んだときの今のタブ）
    private func icon(_ tab: IOSTab, size: CGFloat, selected: Bool) -> some View {
        Image(systemName: tab.symbol)
            .font(.system(size: size > 50 ? 22 : 20)) // apple-ui-allow: 丸の記号の大きさ（OS のタブバーに合わせる）
            .foregroundStyle(selected ? Color.accentColor : Color.primary)
            .frame(width: size, height: size)
            .glassEffect(.regular.interactive(), in: Circle())
            .contentShape(Circle())
    }
}
#endif
