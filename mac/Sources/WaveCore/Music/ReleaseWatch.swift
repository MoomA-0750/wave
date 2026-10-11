import Foundation

/// 登録したアーティストの新作（アルバム・シングル・動画）。Android の NewRelease と同じ。
public struct NewRelease: Hashable, Sendable {
    public var artistId: String
    public var artistName: String
    public var title: String
    public var albumBrowseId: String?
    public var videoId: String?
    public var thumbnailUrl: String?
    public var foundAtEpochMs: Int64
    /// 初めて見たときに、今年の作品として覚えたもの（知らせていない）
    public var seeded: Bool

    public init(artistId: String, artistName: String, title: String, albumBrowseId: String?, videoId: String?, thumbnailUrl: String?,
                foundAtEpochMs: Int64 = 0, seeded: Bool = false)
    {
        self.artistId = artistId
        self.artistName = artistName
        self.title = title
        self.albumBrowseId = albumBrowseId
        self.videoId = videoId
        self.thumbnailUrl = thumbnailUrl
        self.foundAtEpochMs = foundAtEpochMs
        self.seeded = seeded
    }

    public var key: String { albumBrowseId ?? videoId ?? title }
}

/// 新作の見張り（Android の ReleaseWatchState と同じ）。アーティストごとに前に見たアルバム・動画の ID を覚え、増えたアルバム・シングルのうち今年のものを新作とする。
/// 初めて見るアーティストは知らせず、今年の作品だけを「最近の新作」として覚える。
public struct ReleaseWatchState: Equatable, Sendable {
    private static let maxIdsPerArtist = 300
    private static let maxRecent = 60
    public static let recentMaxAgeMs: Int64 = 45 * 24 * 60 * 60 * 1000

    public var seen: [String: Set<String>]
    public var recent: [NewRelease]

    public init(seen: [String: Set<String>], recent: [NewRelease]) {
        self.seen = seen
        self.recent = recent
    }

    public static var empty: ReleaseWatchState { ReleaseWatchState(seen: [:], recent: []) }

    public func observe(_ artistId: String, page: ArtistPage, nowEpochMs: Int64, year: Int?) -> (fresh: [NewRelease], next: ReleaseWatchState) {
        var seenAlbums = Set<String>()
        let albums = page.albumShelves.flatMap(\.albums).filter { seenAlbums.insert($0.browseId).inserted }
        let ids = albums.map { "a:" + $0.browseId } + page.videos.map { "v:" + $0.videoId }
        // 棚が読めなかった（空）ときは覚えている分を消さない
        if ids.isEmpty { return ([], self) }
        let before = seen[artistId]
        let idSet = Set(ids)
        let merged = Set((ids + (before ?? []).filter { !idSet.contains($0) }).prefix(ReleaseWatchState.maxIdsPerArtist))
        var nextSeen = seen
        nextSeen[artistId] = merged
        let next = ReleaseWatchState(seen: nextSeen, recent: ReleaseWatchState.listed(recent, artistId: artistId, page: page, year: year))
        guard let before else {
            guard let y = year else { return ([], next) }
            let seeds = albums.filter { $0.subtitle?.contains(String(y)) == true }
                .map { NewRelease(artistId: artistId, artistName: page.name, title: $0.title, albumBrowseId: $0.browseId, videoId: nil,
                                  thumbnailUrl: $0.thumbnailUrl, foundAtEpochMs: nowEpochMs, seeded: true) }
            return ([], next.withRecent(seeds))
        }
        // 新作は「初めて見た」だけでなく、今年の作品（補足に今年の年がある）に限る（Android と同じ）。棚の中身は入れ替わるので、
        // 何年も前の作品が棚に入り直しただけでも初めて見る扱いになり、通知が大量に来ていた。動画の棚は新作として扱わない（覚えるだけ）
        let thisYear = year.map { String($0) }
        let fresh = albums.filter { a in !before.contains("a:" + a.browseId) && (thisYear == nil || a.subtitle?.contains(thisYear!) == true) }
            .map { NewRelease(artistId: artistId, artistName: page.name, title: $0.title, albumBrowseId: $0.browseId, videoId: nil,
                              thumbnailUrl: $0.thumbnailUrl, foundAtEpochMs: nowEpochMs) }
        return (fresh, next.withRecent(fresh))
    }

    /// アーティストのページから消えた新作を外す（ほかのアーティストの分はそのまま。Android の listedReleases と同じ）。
    /// 同じ名前の別人の作品が YouTube 側で同じアーティストにまとめられ、後で外されることがある（その間に新作として覚えてしまう）。
    /// 棚がどれも今年より前の作品まで並べているのに無いものは、外されたものとみなす。
    /// 今年の作品だけの棚がある（新しい作品に押し出されただけかもしれない）・棚が読めない・年が分からないときは何も外さない。
    public static func listed(_ releases: [NewRelease], artistId: String, page: ArtistPage, year: Int?) -> [NewRelease] {
        let shelves = page.albumShelves.filter { !$0.albums.isEmpty }
        guard let y = year, !shelves.isEmpty else { return releases }
        guard shelves.allSatisfy({ s in s.albums.contains { a in yearOf(a.subtitle).map { $0 < y } ?? false } }) else { return releases }
        let listed = Set(shelves.flatMap(\.albums).map(\.browseId))
        return releases.filter { r in r.artistId != artistId || r.albumBrowseId == nil || listed.contains(r.albumBrowseId!) }
    }

    private static let yearPattern = try! NSRegularExpression(pattern: #"\b(19|20)\d\d\b"#)

    /// 補足（「Single • 2023」など）の年。
    private static func yearOf(_ subtitle: String?) -> Int? {
        guard let subtitle else { return nil }
        let ns = subtitle as NSString
        guard let m = yearPattern.matches(in: subtitle, range: NSRange(location: 0, length: ns.length)).last else { return nil }
        return Int(ns.substring(with: m.range))
    }

    private func withRecent(_ added: [NewRelease]) -> ReleaseWatchState {
        if added.isEmpty { return self }
        var keys = Set<String>()
        let all = (added + recent).filter { keys.insert($0.key).inserted }
        return ReleaseWatchState(seen: seen, recent: Array((all.filter { !$0.seeded } + all.filter(\.seeded)).prefix(ReleaseWatchState.maxRecent)))
    }

    public func retain(_ artistIds: Set<String>) -> ReleaseWatchState {
        ReleaseWatchState(seen: seen.filter { artistIds.contains($0.key) }, recent: recent.filter { artistIds.contains($0.artistId) })
    }

    // 動画は新作として扱わなくなった（前に覚えた分も外す）
    public func expire(_ nowEpochMs: Int64, maxAgeMs: Int64 = ReleaseWatchState.recentMaxAgeMs) -> ReleaseWatchState {
        ReleaseWatchState(seen: seen, recent: recent.filter { nowEpochMs - $0.foundAtEpochMs <= maxAgeMs && $0.videoId == nil })
    }

    /// Windows 版（System.Text.Json の既定）と同じ形の JSON から戻す。読めなければ nil。
    public static func decode(_ text: String) -> ReleaseWatchState? {
        guard let root = try? JSON.parse(text), root.obj != nil else { return nil }
        var seen: [String: Set<String>] = [:]
        for (artistId, ids) in root["Seen"].obj?.pairs ?? [] {
            seen[artistId] = Set((ids.arr ?? []).compactMap(\.str))
        }
        let recent = root["Recent"].items.compactMap { r -> NewRelease? in
            guard let artistId = r["ArtistId"].str, let artistName = r["ArtistName"].str, let title = r["Title"].str else { return nil }
            return NewRelease(artistId: artistId, artistName: artistName, title: title, albumBrowseId: r["AlbumBrowseId"].str, videoId: r["VideoId"].str,
                              thumbnailUrl: r["ThumbnailUrl"].str, foundAtEpochMs: r["FoundAtEpochMs"].long ?? 0, seeded: r["Seeded"].bool ?? false)
        }
        return ReleaseWatchState(seen: seen, recent: recent)
    }

    /// Windows 版と同じ形（キーは PascalCase、Key も書く）の JSON にする。
    public func encode() -> String {
        let seenJson = JSONObject(seen.keys.sorted().map { ($0, JSON.array(seen[$0]!.sorted().map { .string($0) })) })
        let recentJson = recent.map { r in
            JSON.obj([
                "ArtistId": .string(r.artistId),
                "ArtistName": .string(r.artistName),
                "Title": .string(r.title),
                "AlbumBrowseId": JSON(r.albumBrowseId),
                "VideoId": JSON(r.videoId),
                "ThumbnailUrl": JSON(r.thumbnailUrl),
                "FoundAtEpochMs": JSON(r.foundAtEpochMs),
                "Seeded": JSON(r.seeded),
                "Key": .string(r.key),
            ])
        }
        return JSON.obj(["Seen": .object(seenJson), "Recent": .array(recentJson)]).serialized()
    }

    /// 棚に出す並び: アーティストごとに perArtist 件まで、アーティストが交互になるように（見つけた新作を先に）。
    public static func diversified<S: Sequence>(_ releases: S, perArtist: Int = 2) -> [NewRelease] where S.Element == NewRelease {
        func roundRobin(_ items: [NewRelease], _ per: Int) -> [NewRelease] {
            var order: [String] = []
            var groups: [String: [NewRelease]] = [:]
            for r in items {
                if groups[r.artistId] == nil { order.append(r.artistId) }
                groups[r.artistId, default: []].append(r)
            }
            let lists = order.map { Array(groups[$0]!.prefix(per)) }
            return (0..<max(per, 0)).flatMap { round in lists.filter { round < $0.count }.map { $0[round] } }
        }
        let list = Array(releases)
        return roundRobin(list.filter { !$0.seeded }, perArtist) + roundRobin(list.filter(\.seeded), perArtist)
    }
}
