import Foundation

/// ライブラリの「新しいエピソード」に、RSS で登録した番組の新しい回を混ぜる（ADR-0012。Android の NewEpisodes と同じ規則）。
/// YouTube の回は「6 日前」「2025/09/08」のような表示しか持たないので、だいたいの日時に直して新しい順に並べる。
public enum NewEpisodes {
    /// RSS の番組ごとに混ぜる回の数。
    public static let perFeed = 10
    /// これより古い RSS の回は混ぜない（YouTube の新着も数か月分まで）。
    public static let maxAgeDays: Int64 = 90

    private static let day: Int64 = 86_400

    public static func merge(_ youtube: [PodcastEpisode], _ feeds: [FeedShow], nowEpochSeconds: Int64,
                             positions: (String) -> EpisodePosition? = { _ in nil }) -> [PodcastEpisode]
    {
        // 読めない表示の YouTube の回は、一つ前の回と同じ日時にする（並びを崩さない）
        var previous = nowEpochSeconds
        var dated: [(at: Int64, episode: PodcastEpisode)] = youtube.map { episode in
            let at = episode.publishedText.flatMap { approximateEpochSeconds($0, nowEpochSeconds: nowEpochSeconds) } ?? previous
            previous = at
            return (at, episode)
        }
        for show in feeds {
            for episode in show.episodes(from: 0, count: perFeed) {
                guard let at = episode.publishedEpochSeconds, nowEpochSeconds - at <= maxAgeDays * day else { continue }
                dated.append((at, episode.toEpisode(show, positions(episode.audioUrl))))
            }
        }
        // 同じ時刻なら YouTube を先に（Kotlin の安定な並べ替えと同じく、元の順で決める）
        return dated.enumerated().sorted { a, b in
            a.element.at != b.element.at ? a.element.at > b.element.at : a.offset < b.offset
        }.map(\.element.episode)
    }

    private static let relative = try! NSRegularExpression(pattern: #"(\d+)\s*(分|時間|日|週間|か月|ヶ月|年)前"#)
    private static let absolute = try! NSRegularExpression(pattern: #"(\d{4})[/年.-](\d{1,2})[/月.-](\d{1,2})"#)
    private static let monthNames = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

    /// 「6 日前」「2 時間前」「2025/09/08」「2025年9月8日」をだいたいの日時（UTC のエポック秒）にする。読めなければ nil。
    public static func approximateEpochSeconds(_ text: String, nowEpochSeconds: Int64) -> Int64? {
        let range = NSRange(text.startIndex..., in: text)
        func group(_ m: NSTextCheckingResult, _ i: Int) -> String? { Range(m.range(at: i), in: text).map { String(text[$0]) } }
        if let m = relative.firstMatch(in: text, range: range) {
            guard let n = group(m, 1).flatMap({ Int64($0) }) else { return nil }
            let unit: Int64 = switch group(m, 2) {
            case "分": 60
            case "時間": 3_600
            case "日": day
            case "週間": 7 * day
            case "か月", "ヶ月": 30 * day
            default: 365 * day
            }
            return nowEpochSeconds - n * unit
        }
        if let m = absolute.firstMatch(in: text, range: range), let y = group(m, 1), let mo = group(m, 2).flatMap({ Int($0) }), let d = group(m, 3) {
            guard mo >= 1 && mo <= 12 else { return nil }
            return FeedParser.parseRfc822("\(d) \(monthNames[mo - 1]) \(y) 00:00:00 GMT")
        }
        return nil
    }
}
