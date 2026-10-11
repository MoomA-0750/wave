import Foundation

/// データの置き場（~/Library/Application Support/wave）と動作の記録。
enum AppPaths {
    static let data: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("wave", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    static func file(_ name: String) -> URL { data.appendingPathComponent(name) }

    private static let logLock = NSLock()
    private static let stamp: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return f
    }()

    /// 動作の記録（件数・状態だけ。認証情報・URL は書かない）。
    static func log(_ message: String) {
        logLock.lock()
        defer { logLock.unlock() }
        let line = "\(stamp.string(from: Date())) \(message)\n"
        let url = file("wave.log")
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            handle.write(Data(line.utf8))
            try? handle.close()
        } else {
            try? Data(line.utf8).write(to: url)
        }
    }
}
