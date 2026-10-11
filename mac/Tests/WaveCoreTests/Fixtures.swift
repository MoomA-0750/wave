import Foundation
@testable import WaveCore

/// テストの応答の見本（Android・Windows と同じ core/src/desktopTest/resources/fixtures を読む）。
enum Fixtures {
    static let directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .appendingPathComponent("../../../core/src/desktopTest/resources/fixtures").standardizedFileURL

    static func text(_ name: String) -> String {
        let url = directory.appendingPathComponent(name + ".json")
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { fatalError("fixture が読めない: \(url.path)") }
        return text
    }

    static func json(_ name: String) -> JSON { parse(text(name)) }

    static func parse(_ text: String) -> JSON {
        guard let json = try? JSON.parse(text) else { fatalError("JSON が読めない") }
        return json
    }
}

extension String {
    func replacingFirst(_ from: String, with to: String) -> String {
        guard let r = range(of: from) else { return self }
        return replacingCharacters(in: r, with: to)
    }
}
