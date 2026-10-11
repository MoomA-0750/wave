// swift-tools-version: 6.0
import PackageDescription

// macOS 版（SwiftUI）。画面と機能は Windows 版（windows/）と同じにする。
// WaveCore は Windows の Wave.Core を Swift へ移したもの、WaveMac は画面。
let package = Package(
    name: "Wave",
    defaultLocalization: "ja",
    platforms: [.macOS(.v15)],
    products: [
        .executable(name: "WaveMac", targets: ["WaveMac"]),
        .library(name: "WaveCore", targets: ["WaveCore"]),
    ],
    targets: [
        .target(
            name: "WaveCore",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .executableTarget(
            name: "WaveMac",
            dependencies: ["WaveCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "WaveCoreTests",
            dependencies: ["WaveCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
