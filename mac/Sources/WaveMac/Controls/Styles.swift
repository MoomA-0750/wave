import SwiftUI

/// 枠（Spotify の角の丸い面）。窓の地の上に浮かせる。
struct PaneBackground: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(Color.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.primary.opacity(0.08)))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

extension View {
    func pane() -> some View { modifier(PaneBackground()) }

    /// 小さな灰色の文字。
    func subtle() -> some View { font(.caption).foregroundStyle(.secondary).lineLimit(1) }

    /// 見出し（中くらい）。
    func subtitleStyle() -> some View { font(.title3.weight(.semibold)) }
}

/// 枠の無いアイコンだけのボタン（乗せると薄い地が出る）。
struct IconButtonStyle: ButtonStyle {
    var size: CGFloat = 32

    func makeBody(configuration: Configuration) -> some View {
        IconButtonBody(configuration: configuration, size: size)
    }

    private struct IconButtonBody: View {
        let configuration: Configuration
        let size: CGFloat
        @State private var hover = false
        @Environment(\.isEnabled) private var enabled

        var body: some View {
            configuration.label
                .frame(minWidth: size, minHeight: size)
                .foregroundStyle(enabled ? (configuration.isPressed ? Color.secondary : Color.primary) : Color.secondary.opacity(0.5))
                .background(RoundedRectangle(cornerRadius: 6).fill(Color.primary.opacity(configuration.isPressed ? 0.12 : hover ? 0.07 : 0)))
                .contentShape(Rectangle())
                .onHover { hover = $0 }
        }
    }
}

/// 切り替えのボタン（Spotify と同じく地は透明、オンのときはアイコンをアクセントの色にして下に点）。
struct IconToggle<Label: View>: View {
    var isOn: Bool
    var help: String
    var action: () -> Void
    @ViewBuilder var label: () -> Label

    var body: some View {
        Button(action: action) {
            label()
                .foregroundStyle(isOn ? Color.accentColor : Color.secondary)
                .overlay(alignment: .bottom) {
                    if isOn { Circle().fill(Color.accentColor).frame(width: 4, height: 4).offset(y: 9) }
                }
        }
        .buttonStyle(IconButtonStyle())
        .help(help)
    }
}

/// 大きな丸い再生ボタン（Spotify の緑の丸を、アクセントの色で）。
struct PlayCircleButton<Label: View>: View {
    var size: CGFloat = 56
    var help: String = L.t("再生", "Play")
    var action: () -> Void
    @ViewBuilder var label: () -> Label

    var body: some View {
        Button(action: action) {
            label()
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .background(Circle().fill(Color.accentColor))
        }
        .buttonStyle(.plain)
        .help(help)
    }
}

/// チップ（絞り込みの切り替え）。
struct Chip: View {
    var title: String
    var isOn: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.callout)
                .padding(.horizontal, 12)
                .padding(.vertical, 5)
                .foregroundStyle(isOn ? Color.white : Color.primary)
                .background(Capsule().fill(isOn ? Color.accentColor : Color.primary.opacity(0.08)))
        }
        .buttonStyle(.plain)
    }
}

/// 画像の枠（アーティストは丸、ほかは角の少し丸い四角。Spotify と同じ）。
struct Cover: View {
    var url: URL?
    var size: CGFloat
    var round = false
    var radius: CGFloat = 6

    var body: some View {
        RemoteImage(url: url)
            .frame(width: size, height: size)
            .background(Color.primary.opacity(0.08))
            .clipShape(round ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: radius)))
    }
}

/// 高く評価した曲の絵（紫から緑のグラデーションにハート）。
struct LikedCover: View {
    var size: CGFloat
    var round = false
    var radius: CGFloat = 6

    var body: some View {
        ZStack {
            Ui.likedGradient
            Image(systemName: "heart.fill").font(.system(size: size * 0.36)).foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .clipShape(round ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: radius)))
    }
}

/// 押せる文字（曲名・アーティスト名のリンク）。
struct LinkText: View {
    var text: String
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        Text(text)
            .underline(hover)
            .onHover { hover = $0 }
            .onTapGesture(perform: action)
    }
}
