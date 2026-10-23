import Foundation

/// この端末での曲ごとの再生回数（スマートダウンロードの「いつも聴いている」の元。ADR-0016 の 10）。
/// 1回の再生で、再生履歴を送るのと同じだけ聴いたら1回と数える。2000 曲まで、最後に聴いたのが古いものから忘れる。
public struct PlayCounts: Codable, Equatable, Sendable {
    public static let maxSongs = 2000

    public struct Entry: Codable, Equatable, Sendable {
        public var song: DownloadSong
        public var count: Int
        public var lastPlayedMs: Int64
    }

    public var entries: [String: Entry] = [:]

    public init() {}

    public mutating func record(_ song: DownloadSong, nowMs: Int64) {
        var entry = entries[song.videoId] ?? Entry(song: song, count: 0, lastPlayedMs: nowMs)
        entry.song = song
        entry.count += 1
        entry.lastPlayedMs = nowMs
        entries[song.videoId] = entry
        if entries.count > PlayCounts.maxSongs {
            for old in entries.values.sorted(by: { $0.lastPlayedMs < $1.lastPlayedMs }).prefix(entries.count - PlayCounts.maxSongs) {
                entries.removeValue(forKey: old.song.videoId)
            }
        }
    }

    /// 回数の多い順、同じなら新しい順。
    public var ranked: [Entry] {
        entries.values.sorted { $0.count != $1.count ? $0.count > $1.count : $0.lastPlayedMs != $1.lastPlayedMs ? $0.lastPlayedMs > $1.lastPlayedMs : $0.song.videoId < $1.song.videoId }
    }

    public static func decode(_ data: Data) -> PlayCounts? { try? JSONDecoder().decode(PlayCounts.self, from: data) }
    public func encode() -> Data? { try? JSONEncoder().encode(self) }
}

/// スマートダウンロードの曲の選び方（ADR-0016 の 10・11）。通信はせず、集めた候補から選ぶ。
public enum SmartDownloads {
    /// 選べる曲数と既定。
    public static let countChoices = [25, 50, 100, 200]
    public static let defaultCount = 50
    /// これより長い曲（長い動画・作業用のミックスなど）は選ばない。長さの分からない曲は選んでよい。
    public static let maxDurationSeconds = 15 * 60
    /// 「好きそう」の種にする「いつも聴いている」の上位の曲の数。
    public static let seedCount = 5
    /// 同じアーティストは全体でこの曲数まで。
    public static let perArtist = 2

    /// スマートダウンロードで選べる曲: ライブ配信・RSS のエピソード・15 分より長い曲・ふつうの動画（種類が分かっていて、音源でも公式の MV でもないもの。
    /// 投稿者の動画やポッドキャストのエピソードなど）は選ばない。長さや種類の分からない曲は選んでよい。手で取るものには当てはめない。
    static func eligible(_ song: Song) -> Bool {
        !song.isLive && !Feed.isExternal(song.videoId) && !song.videoId.isEmpty && (song.durationSeconds ?? 0) <= maxDurationSeconds
            && (song.kind == nil || song.kind == Song.kindSong || song.kind == Song.kindMusicVideo)
    }

    /// いつも聴いている: この端末の再生回数と、YouTube の再生履歴（新しい順）に出る曲。履歴に出る曲は回数に1を足す。
    /// 回数の多い順、同じならこの端末で聴いた曲を先に（新しい順）、履歴にだけ出る曲は履歴の並び（上ほど新しい）で後に。
    public static func frequent(counts: PlayCounts, history: [Song]) -> [Song] {
        struct Item { var song: Song; var count: Int; var local: Bool; var lastMs: Int64; var historyIndex: Int }
        var items: [String: Item] = [:]
        for e in counts.entries.values where eligible(e.song.song) {
            items[e.song.videoId] = Item(song: e.song.song, count: e.count, local: true, lastMs: e.lastPlayedMs, historyIndex: Int.max)
        }
        var seen = Set<String>()
        for (i, song) in history.enumerated() where eligible(song) && seen.insert(song.videoId).inserted {
            if var item = items[song.videoId] {
                item.count += 1
                item.historyIndex = i
                items[song.videoId] = item
            } else {
                items[song.videoId] = Item(song: song, count: 1, local: false, lastMs: 0, historyIndex: i)
            }
        }
        return items.values.sorted { a, b in
            if a.count != b.count { return a.count > b.count }
            if a.local != b.local { return a.local }
            if a.local { return a.lastMs != b.lastMs ? a.lastMs > b.lastMs : a.song.videoId < b.song.videoId }
            return a.historyIndex < b.historyIndex
        }.map(\.song)
    }

    /// 好きそう: 種ごとのラジオから、聴いたことのある曲（再生回数・再生履歴・高く評価した曲）を除き、種ごとに順番に1曲ずつ取る。
    public static func discover(radios: [[Song]], known: Set<String>) -> [Song] {
        let lists = radios.map { $0.filter { eligible($0) && !known.contains($0.videoId) } }
        var out: [Song] = []
        var seen = Set<String>()
        var i = 0
        while lists.contains(where: { i < $0.count }) {
            for list in lists where i < list.count && seen.insert(list[i].videoId).inserted { out.append(list[i]) }
            i += 1
        }
        return out
    }

    /// おすすめ: ホームの1ページ目の、曲が並んだ棚（半分より多くが曲）の曲。
    public static func recommended(shelves: [HomeShelf]) -> [Song] {
        var out: [Song] = []
        var seen = Set<String>()
        for shelf in shelves {
            let songs = shelf.items.compactMap { item -> Song? in if case .song(let s) = item { s } else { nil } }
            guard !songs.isEmpty, songs.count * 2 > shelf.items.count else { continue }
            for s in songs where eligible(s) && seen.insert(s.videoId).inserted { out.append(s) }
        }
        return out
    }

    /// ホームの最初のミックス（プレイリスト）。「おすすめ」の曲が足りないときに、その曲で埋める。
    public static func firstMix(shelves: [HomeShelf]) -> PlaylistSummary? {
        let playlists = shelves.flatMap(\.items).compactMap { item -> PlaylistSummary? in if case .playlist(let p) = item { p } else { nil } }
        return playlists.first { $0.playlistId.hasPrefix("RD") } ?? playlists.first
    }

    /// 3つの分類から1曲ずつ順番に取る。同じアーティストは全体で perArtist 曲まで、分類どうしで重なった曲は先に入った分類に入れる。
    /// どれかの分類が足りなければ、ほかの分類で埋める。
    public static func pick(_ candidates: [SmartCategory: [Song]], count: Int) -> [(song: Song, category: SmartCategory)] {
        var cursors: [SmartCategory: Int] = [:]
        var out: [(song: Song, category: SmartCategory)] = []
        var seen = Set<String>()
        var perArtist: [String: Int] = [:]
        func next(_ category: SmartCategory) -> Song? {
            let list = candidates[category] ?? []
            var i = cursors[category] ?? 0
            defer { cursors[category] = i }
            while i < list.count {
                let song = list[i]
                i += 1
                guard eligible(song), !seen.contains(song.videoId) else { continue }
                let key = artistKey(song)
                if let key, perArtist[key, default: 0] >= SmartDownloads.perArtist { continue }
                seen.insert(song.videoId)
                if let key { perArtist[key, default: 0] += 1 }
                return song
            }
            return nil
        }
        var exhausted = Set<SmartCategory>()
        while out.count < count && exhausted.count < SmartCategory.allCases.count {
            for category in SmartCategory.allCases where out.count < count && !exhausted.contains(category) {
                if let song = next(category) { out.append((song, category)) } else { exhausted.insert(category) }
            }
        }
        return out
    }

    /// アーティストの見分け（最初のアーティストの id、無ければ名前）。分からなければ nil（数えない）。
    static func artistKey(_ song: Song) -> String? {
        guard let first = song.artists.first else { return nil }
        if let id = first.browseId, !id.isEmpty { return id }
        let name = first.name.trimmingCharacters(in: .whitespaces).lowercased()
        return name.isEmpty ? nil : name
    }
}
