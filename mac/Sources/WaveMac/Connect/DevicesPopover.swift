import SwiftUI
import WaveCore

/// 下のバーの「デバイスに接続」（Windows の MainWindow の BuildDevicesPanel・DeviceRow）。
/// Spotify の「デバイスに接続」と同じ並び: 今鳴っている端末、その下に家のほかの端末（押すとその端末に移す）。
struct DevicesPopover: View {
    @Binding var isOpen: Bool

    var body: some View {
        let connect = AppServices.connect!
        let player = AppServices.player!
        let info = connect.info
        let remote = player.isRemote ? connect.remote : nil
        VStack(alignment: .leading, spacing: 4) {
            Text(L.t("現在のデバイス", "Current device")).font(.body.weight(.semibold))
            if let remote {
                let isPc = connect.members.first { $0.deviceId == remote.deviceId }?.isPc == true
                DeviceRow(symbol: isPc ? "desktopcomputer" : "iphone", title: remote.deviceLabel,
                          subtitle: [remote.title, remote.artist].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "), accent: true, enabled: true, action: nil)
            } else {
                DeviceRow(symbol: "desktopcomputer", title: L.t("この Mac", "This Mac"), subtitle: nil, accent: true, enabled: true, action: nil)
            }
            if !info.inHousehold {
                Text(L.t("スマホと連携すると、再生を引き継げます", "Connect your phone to move playback between devices"))
                    .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            } else {
                let others = connect.members
                    .filter { m in remote == nil ? !m.isSelf : m.deviceId != remote?.deviceId }
                    .filter { !$0.isController }
                    // 自分を先に、その後はオンラインのものを先に（同じなら家の順のまま）
                    .enumerated()
                    .sorted { a, b in
                        if a.element.isSelf != b.element.isSelf { return a.element.isSelf }
                        if a.element.online != b.element.online { return a.element.online }
                        return a.offset < b.offset
                    }
                    .map(\.element)
                if !others.isEmpty {
                    Text(L.t("ほかのデバイスを選択", "Select another device")).font(.body.weight(.semibold)).padding(.top, 8)
                    ForEach(others) { member in
                        if member.isSelf {
                            DeviceRow(symbol: "desktopcomputer", title: L.t("この Mac", "This Mac"), subtitle: nil, accent: false, enabled: true) {
                                isOpen = false
                                connect.transfer()
                            }
                        } else {
                            let target = member.deviceId
                            DeviceRow(symbol: member.isPc ? "desktopcomputer" : "iphone", title: member.label,
                                      subtitle: member.online ? nil : L.t("オフライン", "Offline"), accent: false, enabled: member.online) {
                                isOpen = false
                                connect.playOnDevice(target)
                            }
                        }
                    }
                }
            }
            Button(L.t("端末の連携の設定", "Device settings")) {
                isOpen = false
                AppServices.nav.devices()
            }
            .buttonStyle(.textLink)
            .padding(.top, 8)
        }
        .padding(16)
        .frame(width: 320, alignment: .leading)
    }
}

/// デバイスの1行（絵・名前・補足）。accent は今鳴っている端末（アクセントの色）、押せないものは薄くする。
private struct DeviceRow: View {
    var symbol: String
    var title: String
    var subtitle: String?
    var accent: Bool
    var enabled: Bool
    var action: (() -> Void)?

    @State private var hover = false

    var body: some View {
        let content = HStack(spacing: 12) {
            Image(systemName: symbol).font(.system(size: 22)).frame(width: 30)
                .foregroundStyle(accent ? Color.accentColor : Color.primary)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.body.weight(.semibold)).lineLimit(1).truncationMode(.tail)
                    .foregroundStyle(accent ? Color.accentColor : Color.primary)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(8)
        .opacity(enabled ? 1 : 0.45)
        .contentShape(Rectangle())
        if let action, enabled {
            Button(action: action) { content }
                .buttonStyle(.plain)
                .background(RoundedRectangle(cornerRadius: 6).fill(Color.primary.opacity(hover ? 0.07 : 0)))
                .onHover { hover = $0 }
        } else {
            content
        }
    }
}
