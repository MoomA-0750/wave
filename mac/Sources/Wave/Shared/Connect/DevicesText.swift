#if os(macOS)
    import AppKit
#else
    import UIKit
#endif
import CoreImage
import WaveCore

/// 端末の連携の画面（Mac の DevicesPageView・iPhone の DevicesView）で同じ文と操作。
@MainActor
enum DevicesText {
    /// 加入の手順の説明。
    static func pairing(_ pairing: PairingView) -> String {
        switch pairing.kind {
        case .working: return pairing.text ?? ""
        case .showingOffer:
            #if os(macOS)
                return L.t("加える端末で wave の「端末の連携」を開き、この QR を読み取ってください（PC なら下の文字列をコピーして貼り付けます）。2分で無効になります。",
                           "On the device you’re adding, open Devices in wave and scan this QR code (on a PC, copy the text below and paste it). Expires in 2 minutes.")
            #else
                return L.t("加える端末で wave の「端末の連携」を開き、この QR を読み取ってください（PC なら下の文字列を送って貼り付けます）。2分で無効になります。",
                           "On the device you’re adding, open Devices in wave and scan this QR code (on a PC, send the text below and paste it). Expires in 2 minutes.")
            #endif
        case .enterCode:
            return (pairing.text.map { $0 + "\n" } ?? "") + L.t("加える端末に出ている6桁の数字を入力してください", "Enter the 6-digit code shown on the device you’re adding")
        case .showCode:
            let peer = pairing.peerName ?? ""
            return L.t("「\(peer)」に、この6桁の数字を入力してください", "Enter this 6-digit code on “\(peer)”")
        case .done:
            let peer = pairing.peerName ?? ""
            return L.t("「\(peer)」と連携しました", "Connected with “\(peer)”")
        case .failed: return pairing.text ?? ""
        case .idle: return ""
        }
    }

    /// 中継の状態。
    static func relay(_ info: ConnectInfo) -> String {
        if !info.configured { return L.t("設定されていません", "Not configured") }
        switch info.relay {
        case .connected: return L.t("接続中", "Connected")
        case .connecting: return L.t("接続しています…", "Connecting…")
        case .tailnetUnreachable:
            #if os(macOS)
                return L.t("届きません（Tailscale につながっているか確かめてください）", "Unreachable (check that Tailscale is connected)")
            #else
                return L.t("届きません（Tailscale）", "Unreachable (Tailscale)")
            #endif
        case .notMember: return info.inHousehold ? L.t("受け付けられません", "Not accepted") : L.t("まだ連携していません", "Not set up yet")
        case .failed: return L.t("つながりませんでした", "Couldn’t connect")
        case .disconnected: return L.t("切断", "Disconnected")
        }
    }

    /// 家から端末を外し、結果を知らせる。
    static func remove(_ member: MemberView) {
        Task {
            let removed = await AppServices.connect.removeMember(member.deviceId)
            AppServices.toast.show(removed ? L.t("「\(member.label)」を外しました", "Removed \(member.label)") : L.t("外せませんでした", "Couldn’t remove the device"))
        }
    }

    #if os(macOS)
        typealias QrImage = NSImage
    #else
        typealias QrImage = UIImage
    #endif

    /// 文字列の QR（誤り訂正 M、1つの点を 8 px）。読み取れるよう白地に黒で、周りに余白を付ける。
    nonisolated static func qrImage(_ text: String) -> QrImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(text.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        let margin: CGFloat = 32
        let canvas = scaled.extent.insetBy(dx: -margin, dy: -margin)
        let framed = scaled.composited(over: CIImage(color: .white).cropped(to: canvas))
        guard let cg = CIContext().createCGImage(framed, from: canvas) else { return nil }
        #if os(macOS)
            return NSImage(cgImage: cg, size: NSSize(width: canvas.width, height: canvas.height))
        #else
            return UIImage(cgImage: cg)
        #endif
    }
}
