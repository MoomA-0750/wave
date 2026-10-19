#if os(macOS)
import AppKit
import SwiftUI
import WaveCore

/// 端末の連携: 家の作成・加入（QR と6桁）・家の端末の一覧・ほかの端末の再生の引き継ぎ（Windows の DevicesPage と同じ並び）。
struct DevicesPageView: View {
    @State private var offerText = ""
    @State private var codeText = ""
    @State private var removing: MemberView?
    @State private var granting: MemberView?
    @State private var qr: (offer: String, image: NSImage)?

    private var devices: ConnectService { AppServices.connect }

    var body: some View {
        let info = devices.info
        let pairing = devices.pairingState
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(L.t("端末の連携", "Devices")).font(.system(size: 48, weight: .bold))
                Text(L.t("同じアカウントのスマホと Mac のあいだで、再生を引き継げます。端末どうしの通信は暗号化され、中継のサーバーは中身を読めません。",
                         "Move playback between your phone and Mac on the same account. Devices encrypt everything they share, so the relay server can’t read it."))
                    .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true).padding(.bottom, 16)

                statusBar(info)

                // 再生中の端末
                if let remote = devices.remote, info.inHousehold && info.relay != .notMember && remote.accountMatches && !info.isWriter {
                    remoteCard(remote)
                }

                // 家に入っていない
                if info.loaded && !info.inHousehold && (pairing.kind == .idle || pairing.kind == .failed) {
                    joinPanel
                }

                // 加入の手順
                if pairing.kind != .idle {
                    pairingPanel(pairing)
                }

                // 家の端末
                if info.inHousehold {
                    householdPanel
                }

                Text(L.t("中継: ", "Relay: ") + DevicesText.relay(info)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true).padding(.top, 16)
            }
            .padding(.horizontal, 40)
            .padding(.vertical, 32)
            .frame(maxWidth: 880, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onChange(of: pairing) { _, next in
            if next.kind == .done && !devices.info.inHousehold { offerText = "" }
        }
        .onDisappear {
            // 画面を離れたら、途中の加入を取り下げる（相手に数字を見せている間は残す）
            switch devices.pairingState.kind {
            case .showCode, .idle, .done, .failed: break
            default: devices.cancelPairing()
            }
        }
        .confirmationDialog(L.t("端末を外しますか？", "Remove this device?"), isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            presenting: removing) { member in
            Button(L.t("外す", "Remove"), role: .destructive) { DevicesText.remove(member) }
            Button(L.t("キャンセル", "Cancel"), role: .cancel) {}
        } message: { member in
            Text(L.t("「\(member.label)」は、もう再生を引き継げなくなります。もう一度加えるには、QR と6桁の手順が要ります。",
                     "“\(member.label)” won’t be able to move playback anymore. To add it again, you’ll need the QR code and 6-digit steps."))
        }
        .confirmationDialog(DevicesText.grantTitle, isPresented: Binding(get: { granting != nil }, set: { if !$0 { granting = nil } }),
                            presenting: granting) { member in
            Button(L.t("渡す", "Share")) { DevicesText.grantLogin(member) }
            Button(L.t("やめる", "Cancel"), role: .cancel) {}
        } message: { member in
            Text(DevicesText.grantMessage(member))
        }
    }

    // MARK: 知らせの帯（Windows の InfoBar）

    private enum Severity { case informational, warning, error }

    @ViewBuilder
    private func statusBar(_ info: ConnectInfo) -> some View {
        if !info.configured {
            banner(.informational, L.t("中継の場所が設定されていません", "The relay isn’t configured"),
                   devices.unreadable ? L.t("この Mac の連携の鍵を読めませんでした", "Couldn’t read this Mac’s device keys")
                       : L.t("設定ファイルに中継の場所を書くと使えます", "Add the relay address to the settings file to use this"))
        } else if info.unreadable {
            // Android の「リセット」と同じ: 読めない保存を消して、加わり直せるようにする
            banner(.error, L.t("連携の保存を読めません", "Couldn’t read the saved device group"),
                   L.t("リセットすると、この Mac はもう一度ほかの端末から加わり直します。", "Reset it to join your devices again from another device."),
                   L.t("リセット", "Reset"), devices.forgetHousehold)
        } else if info.inHousehold && info.relay == .notMember {
            banner(.error, L.t("中継がこの Mac を連携した端末として受け付けません", "The relay no longer accepts this Mac"),
                   L.t("ほかの端末から外された可能性があります。", "It may have been removed by another device."),
                   L.t("この Mac の連携の記録を消す", "Forget the group on this Mac"), devices.forgetHousehold)
        } else if info.storeResetPending {
            banner(.warning, L.t("中継の状態が作り直されました", "The relay’s state was reset"), nil, L.t("この状態から始める", "Start from this state"),
                   devices.acceptStoreReset)
        }
    }

    private func banner(_ severity: Severity, _ title: String, _ message: String?, _ action: String? = nil, _ onAction: (() -> Void)? = nil) -> some View {
        let (symbol, color): (String, Color) = switch severity {
        case .informational: ("info.circle.fill", .accentColor)
        case .warning: ("exclamationmark.triangle.fill", .yellow)
        case .error: ("xmark.octagon.fill", .red)
        }
        return HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol).foregroundStyle(color)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body.weight(.semibold))
                if let message, !message.isEmpty { Text(message).font(.callout).fixedSize(horizontal: false, vertical: true) }
            }
            Spacer()
            if let action, let onAction { Button(action, action: onAction) }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 8).fill(color.opacity(0.12)))
    }

    // MARK: 再生中の端末

    private func remoteCard(_ remote: RemoteView) -> some View {
        HStack(spacing: 16) {
            Cover(url: Ui.small(remote.thumbnailUrl), size: 64)
            VStack(alignment: .leading, spacing: 2) {
                Text(remote.playing ? L.t("\(remote.deviceLabel)で再生中", "Playing on \(remote.deviceLabel)") : L.t("\(remote.deviceLabel)で一時停止中", "Paused on \(remote.deviceLabel)"))
                    .font(.caption).foregroundStyle(Color.accentColor)
                Text(remote.title ?? L.t("曲", "Song")).font(.body.weight(.semibold)).lineLimit(1)
                Text("\(remote.artist ?? "") · \(Ui.time(Double(remote.positionMs) / 1000))").foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Button(L.t("この Mac で再生", "Play on this Mac")) { devices.transfer() }
                .buttonStyle(.borderedProminent)
        }
        .card()
    }

    // MARK: 家に入っていない

    private var joinPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L.t("はじめる", "Get started")).font(.body.weight(.semibold)).padding(.top, 8).padding(.bottom, 4)
            VStack(alignment: .leading, spacing: 12) {
                Text(L.t("連携済みのスマホの wave で「端末の連携」→「端末を追加」を押し、「文字で共有」で送った文字列をここに貼り付けてください。",
                         "On a phone that’s already set up, open Devices in wave, tap “Add device”, then “Share as text”, and paste the text here."))
                    .fixedSize(horizontal: false, vertical: true)
                TextField("wave-pair:…", text: $offerText).textFieldStyle(.roundedBorder)
                HStack(spacing: 8) {
                    Button(L.t("加わる", "Join")) {
                        if offerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return }
                        devices.accept(offerText)
                    }
                    .buttonStyle(.borderedProminent)
                    Button(L.t("この Mac で新しく始める", "Start a new group on this Mac")) { devices.createHousehold() }
                }
            }
            .card()
        }
    }

    // MARK: 加入の手順

    private func pairingPanel(_ pairing: PairingView) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(DevicesText.pairing(pairing)).foregroundStyle(pairing.kind == .failed ? Color.red : Color.primary).fixedSize(horizontal: false, vertical: true)
            if pairing.kind == .showingOffer, let offer = pairing.offerText {
                Group {
                    if let qr, qr.offer == offer {
                        Image(nsImage: qr.image).interpolation(.none).resizable().frame(width: 280, height: 280)
                    } else {
                        Color.clear.frame(width: 280, height: 280)
                    }
                }
                .onAppear { makeQr(offer) }
                .onChange(of: offer) { _, next in makeQr(next) }
                Button(L.t("文字列をコピー", "Copy text")) { copyOffer() }
            }
            if pairing.kind == .showCode, let code = pairing.code, code.count == 6 {
                Text(String(code.prefix(3)) + " " + String(code.suffix(3))).font(.system(size: 44, weight: .semibold)).tracking(8).monospacedDigit()
            }
            if pairing.kind == .enterCode {
                HStack(spacing: 8) {
                    TextField("000000", text: $codeText).textFieldStyle(.roundedBorder).frame(width: 160)
                        .onChange(of: codeText) { _, next in if next.count > 6 { codeText = String(next.prefix(6)) } }
                        .onSubmit(enterCode)
                    Button(L.t("確認", "Confirm"), action: enterCode).buttonStyle(.borderedProminent)
                }
            }
            if pairing.kind == .working {
                ProgressView().controlSize(.small)
            }
            HStack(spacing: 8) {
                if pairing.kind == .showCode {
                    Button(L.t("相手に入力したら、続ける", "Continue after entering it")) { devices.confirmOnB() }.buttonStyle(.borderedProminent)
                }
                // 加えたのがログインをまだ持たない wave API なら、続けてログインを渡す
                if pairing.kind == .done, let id = pairing.grantDeviceId, let member = devices.members.first(where: { $0.deviceId == id }) {
                    Button(L.t("ログインを渡す", "Share sign-in")) { granting = member }.buttonStyle(.borderedProminent)
                        .disabled(!AppServices.client.isLoggedIn)
                }
                Button(pairing.kind == .done || pairing.kind == .failed ? L.t("閉じる", "Close") : L.t("キャンセル", "Cancel")) { devices.cancelPairing() }
            }
        }
        .card()
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

    private func copyOffer() {
        guard let offer = devices.pairingState.offerText else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(offer, forType: .string)
        AppServices.toast.show(L.t("コピーしました", "Copied"))
    }

    // MARK: 家の端末

    private var householdPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L.t("連携している端末", "Your devices")).font(.body.weight(.semibold))
                Spacer()
                Button { devices.startOffer() } label: { Label(L.t("端末を追加", "Add device"), systemImage: "plus") }
            }
            .padding(.top, 8)
            .padding(.bottom, 4)
            VStack(spacing: 0) {
                ForEach(Array(devices.members.enumerated()), id: \.element.id) { index, member in
                    if index > 0 { Divider() }
                    memberRow(member)
                }
            }
            .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.05)))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.primary.opacity(0.08)))
        }
    }

    private func memberRow(_ member: MemberView) -> some View {
        HStack(spacing: 16) {
            Image(systemName: DevicesText.symbol(member, selfIsPc: true)).font(.system(size: 20)).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(DevicesText.label(member))
                Text(member.active ? L.t("再生中の端末", "Active device") : member.online ? L.t("オンライン", "Online") : L.t("オフライン", "Offline"))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if member.isApi {
                Button(L.t("ログインを渡す", "Share sign-in")) { granting = member }
                    .disabled(!AppServices.client.isLoggedIn)
            }
            if !member.isSelf {
                Button(L.t("外す", "Remove")) { removing = member }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

private extension View {
    /// カード（Windows の CardBackgroundFillColorDefaultBrush の枠）。
    func card() -> some View {
        padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.05)))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.primary.opacity(0.08)))
    }
}
#endif
