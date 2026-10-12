// swift-tools-version: 6.0
import PackageDescription

// macOS 版と iPhone 版（SwiftUI）。Mac の画面と機能は Windows 版（windows/）と、iPhone は Android 版と同じにする。
// WaveCore は Windows の Wave.Core を Swift へ移したもの。Wave はアプリで、Shared（再生・ライブラリ・連携など）を両方で使い、
// Mac/ の画面は macOS で、iOS/ の画面は iOS で組む（同じ製品 wave を、組む先の OS を変えて組む）。
let package = Package(
    name: "Wave",
    defaultLocalization: "ja",
    platforms: [.macOS(.v15), .iOS(.v18)],
    products: [
        .executable(name: "wave", targets: ["Wave"]),
        // ウィジェット（アプリの PlugIns/WaveWidget.appex に入れる）。Shared/Widget をアプリと一緒に組む
        .executable(name: "WaveWidget", targets: ["WaveWidget"]),
        .library(name: "WaveCore", targets: ["WaveCore"]),
    ],
    targets: [
        .target(
            name: "WaveCore",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .executableTarget(
            name: "Wave",
            dependencies: ["WaveCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .executableTarget(
            name: "WaveWidget",
            swiftSettings: [.swiftLanguageMode(.v5), .unsafeFlags(["-application-extension"])],
            linkerSettings: [.unsafeFlags(["-Xlinker", "-application_extension"])]
        ),
        .testTarget(
            name: "WaveCoreTests",
            dependencies: ["WaveCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
