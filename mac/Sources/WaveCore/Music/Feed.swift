import Foundation

/// RSS で配られるポッドキャスト（ADR-0012。Android の core の Feed.kt と同じ）。番組の目印は `rss:` + フィードの URL、
/// エピソードの曲の ID は `ext:` + 音声の URL。
public enum Feed {
    public static let showPrefix = "rss:"
    public static let audioPrefix = "ext:"

    public static func showBrowseId(_ feedUrl: String) -> String { showPrefix + feedUrl }

    /// `rss:` の番組ならフィードの URL。
    public static func feedUrlOf(_ browseId: String) -> String? {
        browseId.hasPrefix(showPrefix) ? String(browseId.dropFirst(showPrefix.count)) : nil
    }

    public static func isFeedShow(_ browseId: String) -> Bool { browseId.hasPrefix(showPrefix) }

    public static func audioVideoId(_ audioUrl: String) -> String { audioPrefix + audioUrl }

    /// `ext:` の曲なら音声の URL（https のものだけ）。
    public static func audioUrlOf(_ videoId: String) -> String? {
        guard videoId.hasPrefix(audioPrefix) else { return nil }
        let url = String(videoId.dropFirst(audioPrefix.count))
        return isAllowedFeedUrl(url) ? url : nil
    }

    /// YouTube の曲ではない（ストリームの解決・履歴・評価・歌詞・ラジオをしない）。
    public static func isExternal(_ videoId: String) -> Bool { videoId.hasPrefix(audioPrefix) }

    /// 取りに行ってよい URL（https だけ。ADR-0012）。
    public static func isAllowedFeedUrl(_ url: String) -> Bool {
        let length = url.utf16.count
        return url.lowercased().hasPrefix("https://") && length >= 12 && length <= 4096
            && !url.unicodeScalars.contains { CharacterSet.whitespacesAndNewlines.contains($0) }
    }

    /// 打ち込まれた URL を整える（`feed://`・`http://`・スキーム無しを https にする）。使えなければ nil。
    public static func normalizeFeedUrl(_ input: String) -> String? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = trimmed.lowercased()
        let url: String
        if lower.hasPrefix("https://") {
            url = trimmed
        } else if lower.hasPrefix("http://") {
            url = "https://" + trimmed.dropFirst(7)
        } else if lower.hasPrefix("feed://") {
            url = "https://" + trimmed.dropFirst(7)
        } else if lower.hasPrefix("feed:") {
            guard let inner = normalizeFeedUrl(String(trimmed.dropFirst(5))) else { return nil }
            url = inner
        } else if trimmed.contains("://") {
            return nil
        } else if trimmed.contains(".") {
            url = "https://" + trimmed
        } else {
            return nil
        }
        return isAllowedFeedUrl(url) ? url : nil
    }
}

/// RSS で登録した番組（端末ごとに覚える）。
public struct FeedSubscription: Hashable, Sendable, Codable {
    public var feedUrl: String
    public var title: String
    public var author: String?
    public var thumbnailUrl: String?
    /// 登録した時刻（エポックミリ秒）。
    public var addedAtMs: Int64

    public init(feedUrl: String, title: String, author: String? = nil, thumbnailUrl: String? = nil, addedAtMs: Int64 = 0) {
        self.feedUrl = feedUrl
        self.title = title
        self.author = author
        self.thumbnailUrl = thumbnailUrl
        self.addedAtMs = addedAtMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        feedUrl = try c.decode(String.self, forKey: .feedUrl)
        title = try c.decode(String.self, forKey: .title)
        author = try c.decodeIfPresent(String.self, forKey: .author)
        thumbnailUrl = try c.decodeIfPresent(String.self, forKey: .thumbnailUrl)
        addedAtMs = try c.decodeIfPresent(Int64.self, forKey: .addedAtMs) ?? 0
    }

    public var summary: PodcastSummary {
        PodcastSummary(browseId: Feed.showBrowseId(feedUrl), title: title, author: author, thumbnailUrl: thumbnailUrl)
    }
}

/// エピソードの聴いたところ（音声の URL ごと）。
public struct EpisodePosition: Hashable, Sendable, Codable {
    public static let finishedMarginSeconds = 30

    public var positionSeconds: Int
    public var durationSeconds: Int?
    /// 最後まで（残り30秒未満まで）聴いた。
    public var finished: Bool
    public var updatedAtMs: Int64

    public init(positionSeconds: Int, durationSeconds: Int? = nil, finished: Bool = false, updatedAtMs: Int64 = 0) {
        self.positionSeconds = positionSeconds
        self.durationSeconds = durationSeconds
        self.finished = finished
        self.updatedAtMs = updatedAtMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        positionSeconds = try c.decode(Int.self, forKey: .positionSeconds)
        durationSeconds = try c.decodeIfPresent(Int.self, forKey: .durationSeconds)
        finished = try c.decodeIfPresent(Bool.self, forKey: .finished) ?? false
        updatedAtMs = try c.decodeIfPresent(Int64.self, forKey: .updatedAtMs) ?? 0
    }

    /// 0〜100。分からなければ nil。
    public var percent: Int? {
        if finished { return 100 }
        guard let d = durationSeconds, d > 0 else { return nil }
        let p = min(max(positionSeconds * 100 / d, 0), 100)
        return p > 0 ? p : nil
    }

    /// 続きから流す位置（聴き終えていれば頭から）。
    public var resumeSeconds: Int { finished ? 0 : positionSeconds }

    public static func of(positionSeconds: Int, durationSeconds: Int?, nowMs: Int64) -> EpisodePosition {
        let finished = durationSeconds.map { $0 > 0 && $0 - positionSeconds < finishedMarginSeconds } ?? false
        return EpisodePosition(positionSeconds: max(positionSeconds, 0), durationSeconds: durationSeconds, finished: finished, updatedAtMs: nowMs)
    }
}

/// RSS で登録した番組と、エピソードの聴いたところ（端末ごと、ADR-0012）。JSON の形は Android の FeedLibraryState と同じ。
public struct FeedLibraryState: Hashable, Sendable, Codable {
    public static let maxPositions = 1000

    /// 登録した順（新しいものが先）。
    public var subscriptions: [FeedSubscription]
    /// 音声の URL ごとの聴いたところ。
    public var positions: [String: EpisodePosition]
    /// 外した番組と、外した時刻（ほかの端末へ「外した」を伝える印、ADR-0013）。
    public var removed: [String: Int64]

    public init(subscriptions: [FeedSubscription] = [], positions: [String: EpisodePosition] = [:], removed: [String: Int64] = [:]) {
        self.subscriptions = subscriptions
        self.positions = positions
        self.removed = removed
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        subscriptions = try c.decodeIfPresent([FeedSubscription].self, forKey: .subscriptions) ?? []
        positions = try c.decodeIfPresent([String: EpisodePosition].self, forKey: .positions) ?? [:]
        removed = try c.decodeIfPresent([String: Int64].self, forKey: .removed) ?? [:]
    }

    public func isSubscribed(_ feedUrl: String) -> Bool { subscriptions.contains { $0.feedUrl == feedUrl } }

    /// 登録する（subscription.addedAtMs が登録した時刻）。外した印は消す。
    public func subscribe(_ subscription: FeedSubscription) -> FeedLibraryState {
        var next = self
        next.subscriptions = [subscription] + subscriptions.filter { $0.feedUrl != subscription.feedUrl }
        next.removed[subscription.feedUrl] = nil
        return next
    }

    /// 外す。外した印（atMs）を残す。
    public func unsubscribe(_ feedUrl: String, atMs: Int64 = 0) -> FeedLibraryState {
        var next = self
        next.subscriptions = subscriptions.filter { $0.feedUrl != feedUrl }
        next.removed[feedUrl] = atMs
        return next
    }

    /// 端末の間で合わせる形（ADR-0013。Android の toBody と同じ）。
    public func toBody() -> LibraryBody {
        var feeds: [String: LibraryFeed] = [:]
        for (url, at) in removed { feeds[url] = LibraryFeed(title: "", removed: true, updatedAtMs: at) }
        for s in subscriptions {
            feeds[s.feedUrl] = LibraryFeed(title: s.title, author: s.author, thumbnailUrl: s.thumbnailUrl, addedAtMs: s.addedAtMs, removed: false, updatedAtMs: s.addedAtMs)
        }
        return LibraryBody(feeds: feeds, positions: positions.mapValues {
            LibraryPosition(positionSeconds: $0.positionSeconds, durationSeconds: $0.durationSeconds, finished: $0.finished, updatedAtMs: $0.updatedAtMs)
        })
    }

    /// ほかの端末と合わせた結果を取り込む。番組・エピソードごとに、手元より新しいものだけを当てる。
    public func apply(_ body: LibraryBody) -> FeedLibraryState {
        var next = self
        // 辞書の並びに依らないよう、URL の順に当てる
        for url in body.feeds.keys.sorted() {
            guard let feed = body.feeds[url], Feed.isAllowedFeedUrl(url) else { continue }
            let localAt = next.subscriptions.first { $0.feedUrl == url }?.addedAtMs ?? next.removed[url]
            if let localAt, localAt >= feed.updatedAtMs { continue }
            if feed.removed {
                next = next.unsubscribe(url, atMs: feed.updatedAtMs)
            } else {
                next = next.subscribe(FeedSubscription(feedUrl: url, title: feed.title, author: feed.author,
                                                       thumbnailUrl: feed.thumbnailUrl.flatMap { Feed.isAllowedFeedUrl($0) ? $0 : nil }, addedAtMs: feed.updatedAtMs))
            }
        }
        // 登録の並びは登録した時刻の新しい順（同じなら今の並び）
        next.subscriptions = next.subscriptions.enumerated().sorted { a, b in
            a.element.addedAtMs != b.element.addedAtMs ? a.element.addedAtMs > b.element.addedAtMs : a.offset < b.offset
        }.map(\.element)
        for (url, p) in body.positions {
            if let local = next.positions[url], local.updatedAtMs >= p.updatedAtMs { continue }
            next.positions[url] = EpisodePosition(positionSeconds: p.positionSeconds, durationSeconds: p.durationSeconds, finished: p.finished, updatedAtMs: p.updatedAtMs)
        }
        if next.positions.count > FeedLibraryState.maxPositions {
            let kept = next.positions.sorted { $0.value.updatedAtMs > $1.value.updatedAtMs }.prefix(FeedLibraryState.maxPositions)
            next.positions = Dictionary(uniqueKeysWithValues: kept.map { ($0.key, $0.value) })
        }
        return next
    }

    /// 番組の名前・画像が変わっていたら、登録の表示を合わせる（登録の順と時刻は変えない）。
    public func refresh(_ subscription: FeedSubscription) -> FeedLibraryState {
        var next = self
        next.subscriptions = subscriptions.map { s in
            guard s.feedUrl == subscription.feedUrl else { return s }
            var updated = subscription
            updated.addedAtMs = s.addedAtMs
            return updated
        }
        return next
    }

    /// 位置を覚える。多くなりすぎたら古いものから捨てる。
    public func remember(_ audioUrl: String, _ position: EpisodePosition) -> FeedLibraryState {
        var next = self
        next.positions[audioUrl] = position
        if next.positions.count > FeedLibraryState.maxPositions {
            let kept = next.positions.sorted { $0.value.updatedAtMs > $1.value.updatedAtMs }.prefix(FeedLibraryState.maxPositions)
            next.positions = Dictionary(uniqueKeysWithValues: kept.map { ($0.key, $0.value) })
        }
        return next
    }

    public static func decode(_ data: Data) -> FeedLibraryState? { try? JSONDecoder().decode(FeedLibraryState.self, from: data) }

    public static func encode(_ state: FeedLibraryState) -> Data? {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try? encoder.encode(state)
    }
}

/// フィードの item を、読んだまま（要る欄だけ）持ったもの。エピソードにするのは要るときだけ。
public struct FeedItem: Sendable {
    var title: String?
    var itunesTitle: String?
    var enclosureUrl: String?
    var pubDate: String?
    var duration: String?
    var itunesSummary: String?
    var description: String?
    var contentEncoded: String?
    var imageHref: String?
}

/// 読んだ RSS の番組。エピソードは新しい順。
public struct FeedShow: Sendable {
    public static let pageSize = 30
    public static let continuationPrefix = "feed:"

    public var feedUrl: String
    public var title: String
    public var author: String?
    public var description: String?
    public var thumbnailUrl: String?
    /// エピソードの元（新しい順。音声の無い項目も含む）。
    let items: [FeedItem]

    /// エピソードの項目の数（音声の無いものも数える）。
    public var itemCount: Int { items.count }

    public var subscription: FeedSubscription { FeedSubscription(feedUrl: feedUrl, title: title, author: author, thumbnailUrl: thumbnailUrl) }

    /// from 番目から count 件の項目のうち、エピソードとして読めたもの。
    public func episodes(from: Int, count: Int) -> [FeedEpisode] {
        let end = min(items.count, from + count)
        guard from < end else { return [] }
        return items[from..<end].compactMap(FeedParser.episode)
    }

    /// 全部のエピソード（確かめ用）。
    public var allEpisodes: [FeedEpisode] { episodes(from: 0, count: items.count) }

    /// 番組のページの形にする。エピソードは count 件の項目まで（saved は登録しているか）。
    public func toPage(saved: Bool?, count: Int = FeedShow.pageSize, positions: (String) -> EpisodePosition? = { _ in nil }) -> PodcastPage {
        PodcastPage(browseId: Feed.showBrowseId(feedUrl), title: title, author: author, description: description, thumbnailUrl: thumbnailUrl,
                    episodes: episodes(from: 0, count: count).map { $0.toEpisode(self, positions($0.audioUrl)) },
                    continuation: continuationAfter(count), saved: saved)
    }

    /// 続き（token は toPage・前の続きが返したもの）。
    public func more(_ token: String, positions: (String) -> EpisodePosition? = { _ in nil }) -> SearchPage<PodcastEpisode> {
        guard token.hasPrefix(FeedShow.continuationPrefix), let from = Int(token.dropFirst(FeedShow.continuationPrefix.count)), from >= 0, from <= items.count else {
            return SearchPage(items: [], continuation: nil)
        }
        return SearchPage(items: episodes(from: from, count: FeedShow.pageSize).map { $0.toEpisode(self, positions($0.audioUrl)) },
                          continuation: continuationAfter(from + FeedShow.pageSize))
    }

    private func continuationAfter(_ count: Int) -> String? { count < items.count ? FeedShow.continuationPrefix + String(count) : nil }
}

public struct FeedEpisode: Hashable, Sendable {
    public var title: String
    public var audioUrl: String
    /// 公開日（UTC のエポック秒）。読めなければ nil。
    public var publishedEpochSeconds: Int64?
    public var durationSeconds: Int?
    public var description: String?
    public var thumbnailUrl: String?

    public func toEpisode(_ show: FeedShow, _ position: EpisodePosition?) -> PodcastEpisode {
        PodcastEpisode(
            song: Song(videoId: Feed.audioVideoId(audioUrl), title: title, artists: [ArtistRef(name: show.title, browseId: nil)], album: nil,
                       durationSeconds: durationSeconds, thumbnailUrl: thumbnailUrl ?? show.thumbnailUrl),
            publishedText: publishedEpochSeconds.map(FeedParser.dateText),
            durationText: durationSeconds.map(FeedParser.durationText),
            description: description,
            progressPercent: position?.percent,
            showBrowseId: Feed.showBrowseId(show.feedUrl))
    }
}

/// RSS 2.0（iTunes の拡張つき）を読む。Foundation の XMLParser で、要る要素だけを拾う（木は作らない）。
public enum FeedParser {
    /// 番組として読めなければ nil。音声の無い項目は、エピソードにするときに落とす。
    /// 壊れた XML でも、壊れたところまでに読めた分を返す。
    public static func parse(feedUrl: String, data: Data) -> FeedShow? {
        let collector = FeedCollector()
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = false
        parser.shouldReportNamespacePrefixes = false
        parser.shouldResolveExternalEntities = false
        parser.delegate = collector
        _ = parser.parse()
        guard collector.sawChannel, let title = nz(collector.channelTitle) else { return nil }
        let items = collector.items
        // ほとんどのフィードは新しい順。古い順（連続ものの番組）なら逆にする（端の2つの日付だけを見る）
        func dateOf(_ item: FeedItem) -> Int64? { nz(item.pubDate).flatMap(parseRfc822) }
        let firstDate = items.prefix(5).lazy.compactMap(dateOf).first
        let lastDate = items.reversed().prefix(5).lazy.compactMap(dateOf).first
        let ordered = firstDate != nil && lastDate != nil && firstDate! < lastDate! ? Array(items.reversed()) : items
        let image = nz(collector.channelImageHref).flatMap { Feed.isAllowedFeedUrl($0) ? $0 : nil }
            ?? nz(collector.channelImageUrl).flatMap { Feed.isAllowedFeedUrl($0) ? $0 : nil }
        let description = (nz(collector.channelItunesSummary) ?? nz(collector.channelDescription)).map(plainText).flatMap { $0.isEmpty ? nil : $0 }
        return FeedShow(feedUrl: feedUrl, title: title,
                        author: nz(collector.channelItunesAuthor) ?? nz(collector.channelAuthor) ?? nz(collector.channelManagingEditor),
                        description: description, thumbnailUrl: image, items: ordered)
    }

    public static func parse(feedUrl: String, xml: String) -> FeedShow? { parse(feedUrl: feedUrl, data: Data(xml.utf8)) }

    /// エピソード1回分。音声・題が無ければ nil。
    static func episode(_ item: FeedItem) -> FeedEpisode? {
        guard let raw = nz(item.enclosureUrl?.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
        let audio = upgradeToHttps(raw)
        guard Feed.isAllowedFeedUrl(audio), let title = nz(item.title) ?? nz(item.itunesTitle) else { return nil }
        let description = (nz(item.itunesSummary) ?? nz(item.description) ?? nz(item.contentEncoded)).map(plainText).flatMap { $0.isEmpty ? nil : $0 }
        return FeedEpisode(title: title, audioUrl: audio, publishedEpochSeconds: nz(item.pubDate).flatMap(parseRfc822),
                           durationSeconds: nz(item.duration).flatMap(parseDuration), description: description,
                           thumbnailUrl: nz(item.imageHref).flatMap { Feed.isAllowedFeedUrl($0) ? $0 : nil })
    }

    /// 音声は https でだけ取る。http の URL は同じ場所の https を試す（多くの配信元は両方で返す）。
    static func upgradeToHttps(_ url: String) -> String {
        url.lowercased().hasPrefix("http://") ? "https://" + url.dropFirst(7) : url
    }

    /// 空の文字は無いものとする。
    static func nz(_ s: String?) -> String? {
        guard let s, !s.isEmpty else { return nil }
        return s
    }

    /// 一覧で使う説明の長さ（数百回分の番組で、長い HTML を全部ほどかない）。
    static let descriptionSourceLimit = 600

    /// 改行にするタグ（閉じの p・li・見出しと br）。
    private static let breakTags: Set<String> = ["br", "br/", "/p", "/li", "/h1", "/h2", "/h3", "/h4", "/h5", "/h6", "/div"]

    /// HTML の説明を、段落を残した素の文にする（最初の600字だけ）。
    public static func plainText(_ html: String) -> String {
        let source = Array(html.trimmingCharacters(in: .whitespacesAndNewlines).prefix(descriptionSourceLimit))
        var stripped = ""
        var i = 0
        while i < source.count {
            let c = source[i]
            if c == "<" {
                guard let close = source[i...].firstIndex(of: ">") else { break }
                let name = String(source[(i + 1)..<close]).trimmingCharacters(in: .whitespacesAndNewlines)
                    .split(separator: " ", maxSplits: 1, omittingEmptySubsequences: false).first.map { $0.lowercased() } ?? ""
                if breakTags.contains(name) { stripped.append("\n") }
                i = close + 1
            } else {
                stripped.append(c)
                i += 1
            }
        }
        // 行ごとに前後の空白を落とし、空行は2つまで
        var out = ""
        var blank = 0
        for line in decodeEntities(stripped).split(separator: "\n", omittingEmptySubsequences: false) {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty {
                blank += 1
                continue
            }
            if !out.isEmpty { out += blank > 0 ? "\n\n" : "\n" }
            out += trimmed
            blank = 0
        }
        return out
    }

    /// `&amp;`・`&#123;` などをほどく（知らない実体はそのまま）。
    public static func decodeEntities(_ text: String) -> String {
        guard text.contains("&") else { return text }
        let chars = Array(text)
        var out = ""
        var i = 0
        while i < chars.count {
            guard chars[i] == "&" else {
                out.append(chars[i])
                i += 1
                continue
            }
            var decoded: String?
            var end = -1
            var j = i + 1
            while j < chars.count && j <= i + 10 {
                if chars[j] == ";" {
                    end = j
                    break
                }
                j += 1
            }
            if end >= i + 2 {
                let entity = String(chars[(i + 1)..<end])
                switch entity {
                case "amp": decoded = "&"
                case "lt": decoded = "<"
                case "gt": decoded = ">"
                case "quot": decoded = "\""
                case "apos": decoded = "'"
                case "nbsp": decoded = " "
                default:
                    if entity.hasPrefix("#x") || entity.hasPrefix("#X") {
                        decoded = UInt32(entity.dropFirst(2), radix: 16).flatMap(Unicode.Scalar.init).map { String(Character($0)) }
                    } else if entity.hasPrefix("#") {
                        decoded = UInt32(entity.dropFirst(1)).flatMap(Unicode.Scalar.init).map { String(Character($0)) }
                    }
                }
            }
            if let decoded {
                out += decoded
                i = end + 1
            } else {
                out.append("&")
                i += 1
            }
        }
        return out
    }

    /// `HH:MM:SS`・`MM:SS`・秒。
    public static func parseDuration(_ text: String) -> Int? {
        let parts = text.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ":", omittingEmptySubsequences: false)
        if parts.isEmpty || parts.count > 3 { return nil }
        var total = 0
        for part in parts {
            let whole = part.trimmingCharacters(in: .whitespaces).split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
            guard let v = Int(whole) else { return nil }
            total = total * 60 + v
        }
        return total > 0 ? total : nil
    }

    /// 「1:02:03」「42:05」。言語に依らない形にする。
    public static func durationText(_ seconds: Int) -> String {
        let h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60
        return h > 0 ? "\(h):\(pad2(m)):\(pad2(s))" : "\(m):\(pad2(s))"
    }

    /// 「2026/10/13」（UTC）。
    public static func dateText(_ epochSeconds: Int64) -> String {
        let (y, m, d) = civilFromDays(floorDiv(epochSeconds, 86_400))
        return "\(y)/\(pad2(m))/\(pad2(d))"
    }

    private static let months = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]

    /// RFC 822 の日時（`Mon, 13 Oct 2026 10:00:00 +0900`）。曜日・秒・時差は省けてもよい。
    public static func parseRfc822(_ text: String) -> Int64? {
        let tokens = text.replacingOccurrences(of: ",", with: " ").split(whereSeparator: { $0.isWhitespace }).map(String.init)
        let start = tokens.first?.first?.isLetter == true ? 1 : 0
        func token(_ i: Int) -> String? { i < tokens.count ? tokens[i] : nil }
        guard let day = token(start).flatMap({ Int($0) }) else { return nil }
        guard let monthText = token(start + 1)?.lowercased().prefix(3), let monthIndex = months.firstIndex(of: String(monthText)) else { return nil }
        guard var year = token(start + 2).flatMap({ Int($0) }) else { return nil }
        if year < 100 { year += 2000 }
        let time = token(start + 3)?.split(separator: ":", omittingEmptySubsequences: false).map { Int($0) ?? 0 } ?? []
        let offsetMinutes = token(start + 4).flatMap(zoneOffsetMinutes) ?? 0
        let days = daysFromCivil(year, monthIndex + 1, day)
        let seconds = Int64(time.count > 0 ? time[0] : 0) * 3600 + Int64(time.count > 1 ? time[1] : 0) * 60 + Int64(time.count > 2 ? time[2] : 0)
        return days * 86_400 + seconds - Int64(offsetMinutes) * 60
    }

    private static func zoneOffsetMinutes(_ zone: String) -> Int? {
        let chars = Array(zone)
        if chars.count == 5 && (chars[0] == "+" || chars[0] == "-") {
            guard let h = Int(String(chars[1...2])), let m = Int(String(chars[3...4])) else { return nil }
            return (h * 60 + m) * (chars[0] == "-" ? -1 : 1)
        }
        switch zone.uppercased() {
        case "GMT", "UT", "UTC", "Z": return 0
        case "EST": return -300
        case "EDT": return -240
        case "CST": return -360
        case "CDT": return -300
        case "MST": return -420
        case "MDT": return -360
        case "PST": return -480
        case "PDT": return -420
        case "JST": return 540
        default: return nil
        }
    }

    private static func pad2(_ v: Int) -> String { v < 10 ? "0\(v)" : "\(v)" }

    private static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    // Howard Hinnant の days_from_civil / civil_from_days
    private static func daysFromCivil(_ year: Int, _ month: Int, _ day: Int) -> Int64 {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return Int64(era) * 146_097 + Int64(doe) - 719_468
    }

    private static func civilFromDays(_ days: Int64) -> (Int, Int, Int) {
        let z = days + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = Int(z - era * 146_097)
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        let y = yoe + Int(era) * 400 + (m <= 2 ? 1 : 0)
        return (y, m, d)
    }
}

/// XMLParser の受け手。channel と item の要る欄だけを拾う（名前空間は解決せず、`itunes:image` のような名前のまま見る）。
private final class FeedCollector: NSObject, XMLParserDelegate {
    var sawChannel = false
    var channelTitle: String?
    var channelItunesAuthor: String?
    var channelAuthor: String?
    var channelManagingEditor: String?
    var channelItunesSummary: String?
    var channelDescription: String?
    var channelImageHref: String?
    var channelImageUrl: String?
    var items: [FeedItem] = []

    private var path: [String] = []
    private var item: FeedItem?
    /// 拾っている欄（その要素の深さと、たまった文字）。中の子要素の文字も足す（HTML がそのまま入っている説明）。
    private var capture: (field: Field, depth: Int, text: String, length: Int)?

    /// 長い説明（content:encoded など）は、使う分（最初の600字）より少し多くまでしかためない。
    private static let captureLimit = 4096

    enum Field {
        case channelTitle, channelItunesAuthor, channelAuthor, channelManagingEditor, channelItunesSummary, channelDescription, channelImageUrl
        case title, itunesTitle, pubDate, duration, itunesSummary, description, contentEncoded
    }

    private static let breakTags: Set<String> = ["p", "br", "li", "div", "h1", "h2", "h3", "h4", "h5", "h6"]

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
        let parent = path.last
        path.append(name)
        if capture != nil { return }
        if name == "channel" {
            sawChannel = true
            return
        }
        if name == "item" && parent == "channel" {
            item = FeedItem()
            return
        }
        if item != nil {
            guard parent == "item" else { return }
            switch name {
            case "enclosure": if item?.enclosureUrl == nil { item?.enclosureUrl = attributes["url"] }
            case "itunes:image": if item?.imageHref == nil { item?.imageHref = attributes["href"] }
            case "title": start(.title, item?.title)
            case "itunes:title": start(.itunesTitle, item?.itunesTitle)
            case "pubDate": start(.pubDate, item?.pubDate)
            case "itunes:duration": start(.duration, item?.duration)
            case "itunes:summary": start(.itunesSummary, item?.itunesSummary)
            case "description": start(.description, item?.description)
            case "content:encoded": start(.contentEncoded, item?.contentEncoded)
            default: break
            }
            return
        }
        if parent == "channel" {
            switch name {
            case "title": start(.channelTitle, channelTitle)
            case "itunes:author": start(.channelItunesAuthor, channelItunesAuthor)
            case "author": start(.channelAuthor, channelAuthor)
            case "managingEditor": start(.channelManagingEditor, channelManagingEditor)
            case "itunes:summary": start(.channelItunesSummary, channelItunesSummary)
            case "description": start(.channelDescription, channelDescription)
            case "itunes:image": if channelImageHref == nil { channelImageHref = attributes["href"] }
            default: break
            }
        } else if name == "url" && parent == "image" && path.count >= 3 && path[path.count - 3] == "channel" {
            start(.channelImageUrl, channelImageUrl)
        }
    }

    /// まだ値の無い欄だけ拾う（最初のものを使う）。
    private func start(_ field: Field, _ existing: String?) {
        guard existing == nil else { return }
        capture = (field, path.count, "", 0)
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { append(string) }

    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
        guard capture != nil else { return }
        append(String(decoding: CDATABlock, as: UTF8.self))
    }

    private func append(_ string: String) {
        guard var c = capture, c.length < FeedCollector.captureLimit else { return }
        c.text += string
        c.length += string.utf16.count
        capture = c
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        defer { path.removeLast() }
        if let c = capture {
            if path.count > c.depth {
                // 説明の中の HTML の段落・改行は、改行にする
                if FeedCollector.breakTags.contains(name.lowercased()) { append("\n") }
                return
            }
            capture = nil
            set(c.field, c.text.trimmingCharacters(in: .whitespacesAndNewlines))
            return
        }
        if name == "item", let done = item, path.count >= 2, path[path.count - 2] == "channel" {
            items.append(done)
            item = nil
        }
    }

    /// 空の欄も「最初のもの」として空文字で埋める（Android と同じく、最初の要素が空なら無いものとする。読む側が nz で nil にする）。
    private func set(_ field: Field, _ value: String) {
        switch field {
        case .channelTitle: channelTitle = value
        case .channelItunesAuthor: channelItunesAuthor = value
        case .channelAuthor: channelAuthor = value
        case .channelManagingEditor: channelManagingEditor = value
        case .channelItunesSummary: channelItunesSummary = value
        case .channelDescription: channelDescription = value
        case .channelImageUrl: channelImageUrl = value
        case .title: item?.title = value
        case .itunesTitle: item?.itunesTitle = value
        case .pubDate: item?.pubDate = value
        case .duration: item?.duration = value
        case .itunesSummary: item?.itunesSummary = value
        case .description: item?.description = value
        case .contentEncoded: item?.contentEncoded = value
        }
    }
}
