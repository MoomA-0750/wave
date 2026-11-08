import Foundation

/// YouTube・YouTube Music のリンクか ID から、鳴らすもの（動画・プレイリスト・アルバム）を読む（ショートカットの「リンクで再生」）。
/// 動画は曲かライブかをまだ見分けない（鳴らす前に next で調べる）。
public enum YouTubeLink: Equatable, Sendable {
    /// 動画（曲・MV・ライブ）。videoId。
    case video(String)
    /// プレイリスト（PL…・OLAK5uy_…・RD… など）。playlistId（VL は付けない）。
    case playlist(String)
    /// アルバム（MPREb_…）。browseId。
    case album(String)

    private static let hosts: Set<String> = ["youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be"]
    /// ID だけで渡されたとき、プレイリストと見なす頭の字。
    private static let playlistPrefixes = ["PL", "OLAK5uy_", "RD"]
    /// 動画のページの、パスの2つ目が動画の ID になるもの。
    private static let videoPaths: Set<String> = ["live", "shorts", "embed", "v"]

    /// 読めなければ nil。前後の空白・改行は無視する。
    public static func parse(_ text: String) -> YouTubeLink? {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        if !text.contains("/"), !text.contains("?"), !text.contains(".") { return parseId(text) }
        let withScheme = text.contains("://") ? text : "https://" + text
        guard let components = URLComponents(string: withScheme), let scheme = components.scheme?.lowercased(), scheme == "https" || scheme == "http",
              let host = components.host?.lowercased(), hosts.contains(host)
        else { return nil }
        let path = components.path.split(separator: "/").map(String.init)
        let query = { (name: String) in components.queryItems?.first { $0.name == name }?.value }
        if host.hasSuffix("youtu.be") {
            return path.count == 1 ? videoId(path[0]).map(YouTubeLink.video) : nil
        }
        switch path.first {
        case "watch":
            // v= があれば動画（list= が付いていても、その動画から鳴らす）。list= だけならプレイリスト
            if let v = query("v") { return videoId(v).map(YouTubeLink.video) }
            return query("list").flatMap(playlistId).map(YouTubeLink.playlist)
        case "playlist":
            return query("list").flatMap(playlistId).map(YouTubeLink.playlist)
        case "browse":
            guard path.count == 2 else { return nil }
            if path[1].hasPrefix("MPREb_"), isIdChars(path[1]) { return .album(path[1]) }
            if path[1].hasPrefix("VL") { return playlistId(String(path[1].dropFirst(2))).map(YouTubeLink.playlist) }
            return nil
        case let first? where videoPaths.contains(first):
            return path.count == 2 ? videoId(path[1]).map(YouTubeLink.video) : nil
        default:
            return nil
        }
    }

    /// ID だけのとき（Android の PlayLink.parse と同じ順）: MPREb_ ならアルバム、11字なら動画、PL・OLAK5uy_・RD で始まればプレイリスト。
    private static func parseId(_ id: String) -> YouTubeLink? {
        guard isIdChars(id) else { return nil }
        if id.hasPrefix("MPREb_") { return .album(id) }
        if id.count == 11 { return .video(id) }
        if playlistPrefixes.contains(where: id.hasPrefix) { return .playlist(id) }
        return nil
    }

    private static func videoId(_ value: String) -> String? {
        value.count == 11 && isIdChars(value) ? value : nil
    }

    private static func playlistId(_ value: String) -> String? {
        let value = value.hasPrefix("VL") && value.count > 2 ? String(value.dropFirst(2)) : value
        return value.count >= 2 && isIdChars(value) ? value : nil
    }

    private static func isIdChars(_ value: String) -> Bool {
        !value.isEmpty && value.unicodeScalars.allSatisfy {
            ($0.value >= 0x30 && $0.value <= 0x39) || ($0.value >= 0x41 && $0.value <= 0x5A) || ($0.value >= 0x61 && $0.value <= 0x7A) || $0 == "_" || $0 == "-"
        }
    }
}
