#if os(iOS)
import SwiftUI
import UIKit
import WaveCore

/// 端末の連携（Android の ConnectScreen・ConnectV2Section、Mac の DevicesPageView と同じ並び）。
/// 中継の状態の知らせ、ほかの端末の再生（「この iPhone で再生」で引き取る）、家に加わる（文字列を貼る）・新しく始める、
/// 加入の手順（QR と文字列・6桁の入力と表示）、連携している端末の一覧（左へ払って外す）、端末を追加。
struct DevicesView: View {
    @State private var offerText = ""
    @State private var codeText = ""
    @State private var removing: MemberView?
    @State private var granting: MemberView?
    @State private var confirmTransfer = false
    /// 加わるための QR をカメラで読んでいる（Android と同じ。貼り付けも残す）。
    @State private var scanning = false
    @State private var qr: (offer: String, image: UIImage)?

    private var devices: ConnectService { AppServices.connect }

    var body: some View {
        let info = devices.info
        let pairing = devices.pairingState
        Form {
            Section {
                Text(L.t("同じアカウントのスマホ・PC と、再生を引き継げます。端末どうしの通信は暗号化され、中継のサーバーは中身を読めません。",
                         "Move playback between your phone and computers on the same account. Devices encrypt everything they share, so the relay server can’t read it."))
                    .font(.footnote).foregroundStyle(.secondary)
            }
            statusSection(info)

            // ほかの端末で鳴っている曲
            if let remote = devices.remote, info.inHousehold && info.relay != .notMember {
                remoteSection(remote, info)
            }

            // 家に入っていない
            if info.configured && info.loaded && !info.inHousehold && !info.unreadable && (pairing.kind == .idle || pairing.kind == .failed) {
                joinSection
            }

            // 加入の手順
            if pairing.kind != .idle {
                pairingSection(pairing)
            }

            // 家の端末
            if info.inHousehold {
                householdSection
            }

            Section {
                LabeledContent(L.t("中継", "Relay"), value: DevicesText.relay(info))
            }
        }
        .navigationTitle(L.t("端末の連携", "Devices"))
        .onChange(of: pairing) { _, next in
            if next.kind == .done && !devices.info.inHousehold { offerText = "" }
        }
        .onDisappear {
            // 画面を離れたら、途中の加入を取り下げる（相手に数字を見せている間は残す。Android と同じ）
            switch devices.pairingState.kind {
            case .showCode, .idle, .done, .failed: break
            default: devices.cancelPairing()
            }
        }
        .sheet(isPresented: $scanning) {
            QrScanSheet(title: L.t("QR を読んで加わる", "Scan QR code to join")) { text in devices.accept(text) }
        }
        .confirmationDialog(L.t("端末を外しますか？", "Remove this device?"), isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible, presenting: removing) { member in
            Button(L.t("外す", "Remove"), role: .destructive) { DevicesText.remove(member) }
            Button(L.t("キャンセル", "Cancel"), role: .cancel) {}
        } message: { member in
            Text(L.t("「\(member.label)」は、もう再生を引き継げなくなります。もう一度加えるには、QR と6桁の手順が要ります。",
                     "“\(member.label)” won’t be able to move playback anymore. To add it again, you’ll need the QR code and 6-digit steps."))
        }
        .confirmationDialog(DevicesText.grantTitle, isPresented: Binding(get: { granting != nil }, set: { if !$0 { granting = nil } }),
                            titleVisibility: .visible, presenting: granting) { member in
            Button(L.t("渡す", "Share")) { DevicesText.grantLogin(member) }
            Button(L.t("やめる", "Cancel"), role: .cancel) {}
        } message: { member in
            Text(DevicesText.grantMessage(member))
        }
        .alert(L.t("この iPhone で再生", "Play on this iPhone"), isPresented: $confirmTransfer) {
            Button(L.t("置き換える", "Replace")) { devices.transfer() }
            Button(L.t("キャンセル", "Cancel"), role: .cancel) {}
        } message: {
            Text(L.t("この iPhone で鳴っている曲とキューは、ほかの端末の再生に置き換わります。", "What’s playing on this iPhone will be replaced with the other device’s playback."))
        }
    }

    // MARK: 知らせ

    @ViewBuilder
    private func statusSection(_ info: ConnectInfo) -> some View {
        if !info.configured {
            DevicesBanner(symbol: "info.circle.fill", color: .accentColor,
                          title: L.t("中継の場所が設定されていません", "The relay isn’t configured"),
                          message: devices.unreadable ? L.t("この iPhone の連携の鍵を読めませんでした", "Couldn’t read this iPhone’s device keys")
                              : L.t("設定ファイルに中継の場所を書くと使えます", "Add the relay address to the settings file to use this"))
        } else if info.unreadable {
            // Android の「リセット」と同じ: 読めない保存を消して、加わり直せるようにする
            DevicesBanner(symbol: "xmark.octagon.fill", color: .red,
                          title: L.t("連携の保存を読めません", "Couldn’t read the saved device group"),
                          message: L.t("リセットすると、この iPhone はもう一度ほかの端末から加わり直します。", "Reset it to join your devices again from another device."),
                          action: L.t("リセット", "Reset"), onAction: devices.forgetHousehold)
        } else if info.inHousehold && info.relay == .notMember {
            // 家から外された・中継の家が作り直された。消すかどうかは本人が決める（勝手に消さない）
            DevicesBanner(symbol: "xmark.octagon.fill", color: .red,
                          title: L.t("中継がこの iPhone を連携した端末として受け付けません", "The relay no longer accepts this iPhone"),
                          message: L.t("ほかの端末から外された可能性があります。", "It may have been removed by another device."),
                          action: L.t("この iPhone の連携の記録を消す", "Forget the group on this iPhone"), onAction: devices.forgetHousehold)
        } else if info.storeResetPending {
            DevicesBanner(symbol: "exclamationmark.triangle.fill", color: .yellow,
                          title: L.t("中継の状態が作り直されました", "The relay’s state was reset"), message: nil,
                          action: L.t("この状態から始める", "Start from this state"), onAction: devices.acceptStoreReset)
        }
    }

    // MARK: ほかの端末の再生

    private func remoteSection(_ remote: RemoteView, _ info: ConnectInfo) -> some View {
        Section {
            HStack(spacing: 14) {
                Cover(url: Ui.small(remote.thumbnailUrl), size: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(remote.playing ? L.t("\(remote.deviceLabel)で再生中", "Playing on \(remote.deviceLabel)") : L.t("\(remote.deviceLabel)で一時停止中", "Paused on \(remote.deviceLabel)"))
                        .font(.caption).foregroundStyle(Color.accentColor)
                    Text(remote.title ?? L.t("曲", "Song")).font(.body.weight(.semibold)).lineLimit(1)
                    Text("\(remote.artist ?? "") · \(Ui.time(Double(remote.positionMs) / 1000))").subtle()
                }
            }
            if !remote.accountMatches {
                Text(L.t("別のアカウントで再生しているため、引き取れません", "It’s playing on a different account, so it can’t be moved here"))
                    .font(.footnote).foregroundStyle(.secondary)
            } else if !info.isWriter {
                Button {
                    // 手元で鳴っていれば、置き換える前に確かめる（Android と同じ）
                    if AppServices.player.isPlaying && !AppServices.player.isRemote { confirmTransfer = true } else { devices.transfer() }
                } label: {
                    Label(L.t("この iPhone で再生", "Play on this iPhone"), systemImage: "iphone")
                }
            }
        } header: {
            Text(L.t("再生中の端末", "Now playing on"))
        }
    }

    // MARK: 家に入っていない

    private var joinSection: some View {
        Section {
            Text(L.t("連携済みの端末の wave で「端末の連携」→「端末を追加」を押し、出た QR を読むか、「文字で共有」で送った文字列をここに貼り付けてください。",
                     "On a device that’s already set up, open Devices in wave and tap “Add device”. Scan the QR code, or share the text and paste it here."))
                .font(.footnote).foregroundStyle(.secondary)
            Button(L.t("QR を読んで加わる", "Scan QR code to join"), systemImage: "qrcode.viewfinder") { scanning = true }
            HStack {
                TextField("wave-pair:…", text: $offerText, axis: .vertical)
                    .lineLimit(1...4)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.callout.monospaced())
                PasteButton(payloadType: String.self) { strings in
                    if let first = strings.first { offerText = first }
                }
                .labelStyle(.iconOnly)
                .buttonBorderShape(.capsule)
            }
            Button(L.t("加わる", "Join")) {
                let text = offerText.trimmingCharacters(in: .whitespacesAndNewlines)
                if text.isEmpty { return }
                devices.accept(text)
            }
            .disabled(offerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button(L.t("この iPhone で新しく始める", "Start a new group on this iPhone")) { devices.createHousehold() }
        } header: {
            Text(L.t("はじめる", "Get started"))
        }
    }

    // MARK: 加入の手順

    private func pairingSection(_ pairing: PairingView) -> some View {
        Section {
            Text(DevicesText.pairing(pairing)).foregroundStyle(pairing.kind == .failed ? Color.red : Color.primary)
            if pairing.kind == .working {
                HStack { Spacer(); ProgressView(); Spacer() }
            }
            if pairing.kind == .showingOffer, let offer = pairing.offerText {
                HStack {
                    Spacer()
                    Group {
                        if let qr, qr.offer == offer {
                            Image(uiImage: qr.image).interpolation(.none).resizable().scaledToFit()
                        } else {
                            Color.white
                        }
                    }
                    .frame(width: 260, height: 260)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .onAppear { makeQr(offer) }
                    .onChange(of: offer) { _, next in makeQr(next) }
                    Spacer()
                }
                .listRowBackground(Color.clear)
                // カメラの無い PC で加えるとき: QR と同じ文字列を送って、PC の wave に貼り付けてもらう（秘密は載っていない。照合は6桁で行う）
                ShareLink(item: offer) { Label(L.t("文字で共有", "Share as text"), systemImage: "square.and.arrow.up") }
                Button(L.t("文字列をコピー", "Copy text"), systemImage: "doc.on.doc") {
                    UIPasteboard.general.string = offer
                    AppServices.toast.show(L.t("コピーしました", "Copied"))
                }
            }
            if pairing.kind == .showCode, let code = pairing.code, code.count == 6 {
                Text(String(code.prefix(3)) + " " + String(code.suffix(3)))
                    .font(.system(size: 44, weight: .semibold)).tracking(8).monospacedDigit()
                    .frame(maxWidth: .infinity)
                    .textSelection(.enabled)
            }
            if pairing.kind == .enterCode {
                TextField("000000", text: $codeText)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .font(.title2.monospacedDigit())
                    .onChange(of: codeText) { _, next in
                        let digits = String(next.filter { $0.isASCII && $0.isNumber }.prefix(6))
                        if digits != next { codeText = digits }
                    }
                    .onSubmit(enterCode)
                Button(L.t("確認", "Confirm"), action: enterCode).disabled(codeText.count != 6)
            }
            if pairing.kind == .showCode {
                Button(L.t("相手に入力したら、続ける", "Continue after entering it")) { devices.confirmOnB() }
            }
            // 加えたのがログインをまだ持たない wave API なら、続けてログインを渡す
            if pairing.kind == .done, let id = pairing.grantDeviceId, let member = devices.members.first(where: { $0.deviceId == id }) {
                Button(L.t("ログインを渡す", "Share sign-in")) { granting = member }
                    .disabled(!AppServices.client.isLoggedIn)
            }
            Button(pairing.kind == .done || pairing.kind == .failed ? L.t("閉じる", "Close") : L.t("キャンセル", "Cancel"),
                   role: pairing.kind == .done || pairing.kind == .failed ? nil : .cancel) { devices.cancelPairing() }
        } header: {
            Text(L.t("端末を加える", "Add a device"))
        }
    }

    private func enterCode() {
        let code = String(codeText.filter { $0.isASCII && $0.isNumber })
        if code.count != 6 { return }
        devices.enterCode(code)
        codeText = ""
    }

    private func makeQr(_ offer: String) {
        if qr?.offer == offer { return }
        if let image = DevicesText.qrImage(offer) { qr = (offer, image) }
    }

    // MARK: 家の端末

    private var householdSection: some View {
        Section {
            ForEach(devices.members) { member in
                DevicesMemberRow(member: member) { granting = member }
                    .swipeActions(edge: .trailing) {
                        if !member.isSelf {
                            Button(L.t("外す", "Remove"), role: .destructive) { removing = member }
                        }
                    }
                    .contextMenu {
                        if !member.isSelf {
                            Button(L.t("外す", "Remove"), systemImage: "minus.circle", role: .destructive) { removing = member }
                        }
                    }
            }
            if devices.pairingState.kind == .idle {
                Button { devices.startOffer() } label: { Label(L.t("端末を追加", "Add device"), systemImage: "plus") }
            }
        } header: {
            Text(L.t("連携している端末", "Your devices"))
        } footer: {
            if devices.members.contains(where: { !$0.isSelf }) {
                Text(L.t("左へ払うと外せます", "Swipe left to remove a device"))
            }
        }
    }
}

/// 知らせの1つ（絵・見出し・説明・操作）。
private struct DevicesBanner: View {
    var symbol: String
    var color: Color
    var title: String
    var message: String?
    var action: String?
    var onAction: (() -> Void)?

    var body: some View {
        Section {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: symbol).foregroundStyle(color)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.semibold))
                    if let message, !message.isEmpty { Text(message).font(.footnote).foregroundStyle(.secondary) }
                }
            }
            if let action, let onAction {
                Button(action, role: color == .red ? .destructive : nil, action: onAction)
            }
        }
    }
}

/// 連携している端末の1行。
private struct DevicesMemberRow: View {
    var member: MemberView
    /// wave API の行の「ログインを渡す」。
    var onGrant: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: DevicesText.symbol(member, selfIsPc: false))
                .font(.title3).frame(width: 28)
                .foregroundStyle(member.active ? Color.accentColor : Color.primary)
            VStack(alignment: .leading, spacing: 2) {
                Text(member.isSelf ? L.t("\(member.label)（この iPhone）", "\(member.label) (this iPhone)") : DevicesText.label(member))
                    .lineLimit(1)
                Text(member.active ? L.t("再生中の端末", "Active device") : member.online ? L.t("オンライン", "Online") : L.t("オフライン", "Offline"))
                    .font(.caption).foregroundStyle(member.online ? Color.green : Color.secondary)
            }
            if member.isApi {
                Spacer()
                Button(L.t("ログインを渡す", "Share sign-in"), action: onGrant)
                    .buttonStyle(.borderless)
                    .disabled(!AppServices.client.isLoggedIn)
            }
        }
    }
}

/// 再生中の画面から開く端末の選び（Android の DevicePickerSheet・Mac の DevicesPopover と同じ並び）。
/// 上に今鳴っている端末、下に家のほかの端末（押すとその端末に移す）。家の設定は下の「端末の連携の設定」から開く。
struct DevicesSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let connect = AppServices.connect!
        let player = AppServices.player!
        let info = connect.info
        let remote = player.isRemote ? connect.remote : nil
        NavigationStack {
            List {
                Section(L.t("現在のデバイス", "Current device")) {
                    if let remote {
                        let isPc = connect.members.first { $0.deviceId == remote.deviceId }?.isPc == true
                        DevicesPickRow(symbol: isPc ? "desktopcomputer" : "iphone", title: remote.deviceLabel,
                                       subtitle: [remote.title, remote.artist].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "),
                                       accent: true, enabled: true, action: nil)
                    } else {
                        DevicesPickRow(symbol: "iphone", title: L.t("この iPhone", "This iPhone"), subtitle: nil, accent: true, enabled: true, action: nil)
                    }
                }
                if !info.inHousehold {
                    Section {
                        Text(L.t("ほかの端末と連携すると、再生を引き継げます", "Connect your other devices to move playback between them"))
                            .font(.callout).foregroundStyle(.secondary)
                    }
                } else {
                    let others = connect.members
                        .filter { m in remote == nil ? !m.isSelf : m.deviceId != remote?.deviceId }
                        // 操作だけの端末と wave API（音を出さない）は、再生の行き先にしない
                        .filter { !$0.isController && !$0.isApi }
                        // 自分を先に、その後はオンラインのものを先に（同じなら家の順のまま）
                        .enumerated()
                        .sorted { a, b in
                            if a.element.isSelf != b.element.isSelf { return a.element.isSelf }
                            if a.element.online != b.element.online { return a.element.online }
                            return a.offset < b.offset
                        }
                        .map(\.element)
                    if !others.isEmpty {
                        Section(L.t("ほかのデバイスを選択", "Select another device")) {
                            ForEach(others) { member in
                                if member.isSelf {
                                    DevicesPickRow(symbol: "iphone", title: L.t("この iPhone", "This iPhone"), subtitle: nil, accent: false, enabled: true) {
                                        dismiss()
                                        connect.transfer()
                                    }
                                } else {
                                    let target = member.deviceId
                                    DevicesPickRow(symbol: member.isPc ? "desktopcomputer" : "iphone", title: member.label,
                                                   subtitle: member.online ? nil : L.t("オフライン", "Offline"), accent: false, enabled: member.online) {
                                        dismiss()
                                        connect.playOnDevice(target)
                                    }
                                }
                            }
                        }
                    }
                }
                Section {
                    Button {
                        dismiss()
                        AppServices.nav.devices()
                    } label: {
                        Label(L.t("端末の連携の設定", "Device settings"), systemImage: "gearshape")
                    }
                }
            }
            .navigationTitle(L.t("デバイスに接続", "Connect to a device"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel(L.t("閉じる", "Close"))
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

/// 端末の選びの1行（絵・名前・補足）。accent は今鳴っている端末、押せないものは薄くする。
private struct DevicesPickRow: View {
    var symbol: String
    var title: String
    var subtitle: String?
    var accent: Bool
    var enabled: Bool
    var action: (() -> Void)?

    var body: some View {
        let content = HStack(spacing: 14) {
            Image(systemName: symbol).font(.title2).frame(width: 32)
                .foregroundStyle(accent ? Color.accentColor : Color.primary)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.body.weight(.semibold)).lineLimit(1)
                    .foregroundStyle(accent ? Color.accentColor : Color.primary)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle).subtle()
                }
            }
            Spacer(minLength: 0)
            if accent { Image(systemName: "speaker.wave.2.fill").foregroundStyle(Color.accentColor) }
        }
        .opacity(enabled ? 1 : 0.45)
        .contentShape(Rectangle())
        if let action, enabled {
            Button(action: action) { content }.buttonStyle(.plain)
        } else {
            content
        }
    }
}
#endif
