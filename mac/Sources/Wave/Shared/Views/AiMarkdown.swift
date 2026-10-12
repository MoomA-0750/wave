import SwiftUI
import WaveCore

/// Claude の答えの見た目（Mac の AiView・iPhone の AiTabView で使う。Android・Windows と同じ）。
/// Markdown のうち太字（**…**）と見出し（# …）だけを見た目にする。seekable なら、歌詞の時刻 [mm:ss] を押せるリンク（▶ mm:ss）にする
/// （押すと wave-seek://<ミリ秒> を開くので、画面の openURL で受けて AiChat.seek へ渡す）。
enum AiMarkdown {
    static let seekScheme = "wave-seek"

    static func render(_ text: String, seekable: Bool = false) -> AttributedString {
        var out = AttributedString()
        let lines = text.components(separatedBy: "\n")
        let heading = try? NSRegularExpression(pattern: #"^#{1,6}\s+"#)
        for (i, raw) in lines.enumerated() {
            if i > 0 { out += AttributedString("\n") }
            var line = raw
            var isHeading = false
            if let m = heading?.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)), let r = Range(m.range, in: line) {
                line.removeSubrange(r)
                isHeading = true
            }
            for (k, part) in line.components(separatedBy: "**").enumerated() {
                let bold = isHeading || k % 2 == 1
                var at = part.startIndex
                if seekable {
                    for stamp in AiText.timestamps(part) {
                        out += run(String(part[at..<stamp.range.lowerBound]), bold: bold)
                        var link = AttributedString("\u{00A0}▶\u{00A0}\(stamp.label)\u{00A0}")
                        link.link = URL(string: "\(seekScheme)://\(stamp.positionMs)")
                        link.backgroundColor = Color.accentColor.opacity(0.15)
                        if bold { link.font = .body.bold() }
                        out += link
                        at = stamp.range.upperBound
                    }
                }
                out += run(String(part[at...]), bold: bold)
            }
        }
        return out
    }

    private static func run(_ text: String, bold: Bool) -> AttributedString {
        var run = AttributedString(text)
        if bold { run.font = .body.bold() }
        return run
    }

    /// 押したリンクが時刻なら、その位置（ミリ秒）。
    static func seekPosition(_ url: URL) -> Int64? {
        url.scheme == seekScheme ? url.host.flatMap { Int64($0) } : nil
    }
}

/// Claude の答え1つ分（時刻を押すと、話している曲がまだ鳴っていればその位置へ飛ぶ）。
struct AiAnswerText: View {
    var text: String
    var videoId: String?

    var body: some View {
        // 話している曲がまだ鳴っているときだけ、時刻を押せる
        let seekVideoId = videoId.flatMap { $0 == AppServices.player.current?.videoId ? $0 : nil }
        Text(AiMarkdown.render(text, seekable: seekVideoId != nil))
            .textSelection(.enabled)
            .environment(\.openURL, OpenURLAction { url in
                guard let position = AiMarkdown.seekPosition(url), let seekVideoId else { return .systemAction }
                AiChat.seek(videoId: seekVideoId, positionMs: position)
                return .handled
            })
    }
}
