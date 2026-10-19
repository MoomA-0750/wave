#if os(iOS)
import AVFoundation
import SwiftUI

/// カメラで QR コードを読む画面（Android の QrScanner と同じ。端末の連携・Claude の接続に使う）。
/// 読めたら1回だけ onResult を呼ぶ。カメラが無い（シミュレーターなど）・許可されていなければ、その旨を出す（貼り付けは元の画面に残す）。
struct QrScanSheet: View {
    var title: String
    var onResult: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var access = AVCaptureDevice.authorizationStatus(for: .video)

    var body: some View {
        NavigationStack {
            Group {
                switch access {
                case .authorized:
                    if AVCaptureDevice.default(for: .video) != nil {
                        QrCameraView { text in
                            dismiss()
                            onResult(text)
                        }
                        .ignoresSafeArea(edges: .bottom)
                    } else {
                        note(L.t("この端末ではカメラを使えません。文字列を貼り付けてください。", "The camera isn't available on this device. Paste the text instead."))
                    }
                case .notDetermined:
                    ProgressView()
                default:
                    VStack(spacing: 12) {
                        note(L.t("カメラが許可されていません。設定で wave のカメラを許可するか、文字列を貼り付けてください。",
                                 "Camera access is off. Allow it for wave in Settings, or paste the text instead."))
                        Button(L.t("設定を開く", "Open Settings")) {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L.t("キャンセル", "Cancel")) { dismiss() } }
            }
        }
        .task {
            if access == .notDetermined {
                _ = await AVCaptureDevice.requestAccess(for: .video)
                access = AVCaptureDevice.authorizationStatus(for: .video)
            }
        }
    }

    private func note(_ text: String) -> some View {
        Text(text).multilineTextAlignment(.center).foregroundStyle(.secondary).padding(24)
    }
}

/// カメラの映像と QR の読み取り（AVCaptureMetadataOutput）。
private struct QrCameraView: UIViewControllerRepresentable {
    var onResult: (String) -> Void

    func makeUIViewController(context: Context) -> QrCameraController {
        let controller = QrCameraController()
        controller.onResult = onResult
        return controller
    }

    func updateUIViewController(_ controller: QrCameraController, context: Context) {}
}

final class QrCameraController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onResult: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var done = false
    /// カメラの開始・停止は時間がかかるので、主スレッドの外で行う。
    private let queue = DispatchQueue(label: "wave.qr")

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else { return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        if output.availableMetadataObjectTypes.contains(.qr) { output.metadataObjectTypes = [.qr] }
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        preview = layer
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        let session = session
        queue.async { if !session.isRunning { session.startRunning() } }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        let session = session
        queue.async { if session.isRunning { session.stopRunning() } }
    }

    nonisolated func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        // 主スレッドで呼ばれる（setMetadataObjectsDelegate の queue）
        guard let text = metadataObjects.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first else { return }
        MainActor.assumeIsolated {
            guard !done else { return }
            done = true
            // 読み取りの中身はログに出さない（加入の文字列・接続のトークンを含む）
            AppPaths.log("qr scanned length=\(text.count)")
            onResult?(text)
        }
    }
}
#endif
