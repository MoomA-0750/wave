import Foundation

/// 取っておく曲の情報（通信なしで一覧を出すため。ADR-0016）。RSS のエピソードは videoId が `ext:` で始まる。
public struct DownloadSong: Codable, Hashable, Sendable {
    public struct Artist: Codable, Hashable, Sendable {
        public var name: String
        public var browseId: String?
    }

    public struct Album: Codable, Hashable, Sendable {
        public var title: String
        public var browseId: String?
    }

    public var videoId: String
    public var title: String
    public var artists: [Artist]
    public var album: Album?
    public var durationSeconds: Int?
    public var thumbnailUrl: String?
    public var kind: String?
    /// ポッドキャストのエピソード（YouTube も RSS も）。ダウンロードの画面で曲と分けて出す。
    public var episode: Bool

    public init(_ song: Song, episode: Bool = false) {
        videoId = song.videoId
        title = song.title
        artists = song.artists.map { Artist(name: $0.name, browseId: $0.browseId) }
        album = song.album.map { Album(title: $0.title, browseId: $0.browseId) }
        durationSeconds = song.durationSeconds
        thumbnailUrl = song.thumbnailUrl
        kind = song.kind
        self.episode = episode || Feed.isExternal(song.videoId)
    }

    public var song: Song {
        Song(videoId: videoId, title: title, artists: artists.map { ArtistRef(name: $0.name, browseId: $0.browseId) },
             album: album.map { AlbumRef(title: $0.title, browseId: $0.browseId) }, durationSeconds: durationSeconds, thumbnailUrl: thumbnailUrl, kind: kind)
    }

    enum CodingKeys: String, CodingKey { case videoId, title, artists, album, durationSeconds, thumbnailUrl, kind, episode }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        videoId = try c.decode(String.self, forKey: .videoId)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        artists = try c.decodeIfPresent([Artist].self, forKey: .artists) ?? []
        album = try c.decodeIfPresent(Album.self, forKey: .album)
        durationSeconds = try c.decodeIfPresent(Int.self, forKey: .durationSeconds)
        thumbnailUrl = try c.decodeIfPresent(String.self, forKey: .thumbnailUrl)
        kind = try c.decodeIfPresent(String.self, forKey: .kind)
        episode = try c.decodeIfPresent(Bool.self, forKey: .episode) ?? false
    }
}

/// 丸ごと取るものの種類。
public enum DownloadCollectionKind: String, Codable, Sendable {
    case playlist
    case album
    /// 高く評価した曲（LM）。
    case liked
}

/// 丸ごと取っているもの（プレイリスト・アルバム・高く評価した曲）。曲の並びは読み直すたびに合わせる。
public struct DownloadCollection: Codable, Hashable, Sendable, Identifiable {
    public var kind: DownloadCollectionKind
    public var id: String
    public var title: String
    public var thumbnailUrl: String?
    public var addedAtMs: Int64
    public var trackIds: [String]

    public init(kind: DownloadCollectionKind, id: String, title: String, thumbnailUrl: String?, addedAtMs: Int64, trackIds: [String]) {
        self.kind = kind
        self.id = id
        self.title = title
        self.thumbnailUrl = thumbnailUrl
        self.addedAtMs = addedAtMs
        self.trackIds = trackIds
    }
}

/// スマートダウンロードの分類（ADR-0016 の 10）。並びは取る順（1曲ずつ順番に取るときの順）。
public enum SmartCategory: String, Codable, CaseIterable, Sendable {
    /// いつも聴いている
    case frequent
    /// 好きそう（聴いたことのない曲）
    case discover
    /// おすすめ
    case recommended
}

public struct SmartTrack: Codable, Hashable, Sendable {
    public var videoId: String
    public var category: SmartCategory

    public init(videoId: String, category: SmartCategory) {
        self.videoId = videoId
        self.category = category
    }
}

/// 一覧を読み直したときに、足す曲と外す曲。
public struct CollectionDiff: Equatable, Sendable {
    public var added: [String]
    public var removed: [String]

    public static func of(old: [String], new: [String]) -> CollectionDiff {
        let before = Set(old), after = Set(new)
        var seen = Set<String>()
        return CollectionDiff(added: new.filter { !before.contains($0) && seen.insert($0).inserted }, removed: old.filter { !after.contains($0) })
    }
}

/// 何を取っているかの目録（downloads.json）。曲のファイルは、どれか1つでも持ち主（1曲ずつ・丸ごと・スマート）がいれば残す。
/// 署名付き URL は書かない。
public struct DownloadManifest: Codable, Equatable, Sendable {
    public var collections: [DownloadCollection] = []
    /// 1曲ずつ取ったもの（曲・エピソード）。新しいものが先。
    public var singles: [String] = []
    /// スマートダウンロードの曲（分類つき。取る順）。
    public var smart: [SmartTrack] = []
    /// 最後にスマートダウンロードを選び直した時刻（0 は一度も）。
    public var smartRefreshedAtMs: Int64 = 0
    /// 最後に丸ごとのものを読み直した時刻。
    public var collectionsSyncedAtMs: Int64 = 0
    /// 持ち主のいる曲の情報。
    public var songs: [String: DownloadSong] = [:]

    public init() {}

    enum CodingKeys: String, CodingKey { case collections, singles, smart, smartRefreshedAtMs, collectionsSyncedAtMs, songs }

    // 項目が足りない（前の版の）ファイルも読む
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        collections = try c.decodeIfPresent([DownloadCollection].self, forKey: .collections) ?? []
        singles = try c.decodeIfPresent([String].self, forKey: .singles) ?? []
        smart = try c.decodeIfPresent([SmartTrack].self, forKey: .smart) ?? []
        smartRefreshedAtMs = try c.decodeIfPresent(Int64.self, forKey: .smartRefreshedAtMs) ?? 0
        collectionsSyncedAtMs = try c.decodeIfPresent(Int64.self, forKey: .collectionsSyncedAtMs) ?? 0
        songs = try c.decodeIfPresent([String: DownloadSong].self, forKey: .songs) ?? [:]
    }

    public static func decode(_ data: Data) -> DownloadManifest? { try? JSONDecoder().decode(DownloadManifest.self, from: data) }

    public func encode() -> Data? {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try? encoder.encode(self)
    }

    /// 取っておく曲（1曲ずつ → 丸ごと → スマートの順。重なりは1つにする）。この順に取る。
    public var wanted: [String] {
        var seen = Set<String>()
        return (singles + collections.flatMap(\.trackIds) + smart.map(\.videoId)).filter { seen.insert($0).inserted }
    }

    /// 持ち主の数（1曲ずつ・丸ごとのそれぞれ・スマート）。
    public func owners(_ id: String) -> Int {
        (singles.contains(id) ? 1 : 0) + collections.filter { $0.trackIds.contains(id) }.count + (smart.contains { $0.videoId == id } ? 1 : 0)
    }

    public func isOwned(_ id: String) -> Bool { owners(id) > 0 }

    public func isSingle(_ id: String) -> Bool { singles.contains(id) }

    public func collection(_ id: String) -> DownloadCollection? { collections.first { $0.id == id } }

    /// 手で取っている（1曲ずつか、丸ごとのものに入っている）。スマートダウンロードの選び直しでは消さない。
    public func isManual(_ id: String) -> Bool { singles.contains(id) || collections.contains { $0.trackIds.contains(id) } }

    public mutating func addSingle(_ song: DownloadSong) {
        songs[song.videoId] = song
        singles.removeAll { $0 == song.videoId }
        singles.insert(song.videoId, at: 0)
    }

    /// 1曲ずつ取った印を外す。ほかの持ち主がいなければ、消してよい曲として返す。
    @discardableResult
    public mutating func removeSingle(_ id: String) -> [String] {
        singles.removeAll { $0 == id }
        return prune()
    }

    /// 丸ごとのものを足す・読み直した中身に合わせる。足した曲・外した曲を返す（外した曲のうち消してよいものは prune で分かる）。
    @discardableResult
    public mutating func setCollection(_ kind: DownloadCollectionKind, id: String, title: String, thumbnailUrl: String?, songs list: [DownloadSong],
                                       nowMs: Int64) -> CollectionDiff
    {
        var seen = Set<String>()
        let ids = list.map(\.videoId).filter { seen.insert($0).inserted }
        for song in list { songs[song.videoId] = song }
        if let i = collections.firstIndex(where: { $0.id == id }) {
            let diff = CollectionDiff.of(old: collections[i].trackIds, new: ids)
            collections[i].trackIds = ids
            collections[i].title = title.isEmpty ? collections[i].title : title
            collections[i].thumbnailUrl = thumbnailUrl ?? collections[i].thumbnailUrl
            return diff
        }
        collections.append(DownloadCollection(kind: kind, id: id, title: title, thumbnailUrl: thumbnailUrl, addedAtMs: nowMs, trackIds: ids))
        return CollectionDiff(added: ids, removed: [])
    }

    @discardableResult
    public mutating func removeCollection(_ id: String) -> [String] {
        collections.removeAll { $0.id == id }
        return prune()
    }

    /// スマートダウンロードの曲を選び直したものに替える。外れて持ち主のいなくなった曲を返す（手で取っている曲は残る）。
    @discardableResult
    public mutating func setSmart(_ picked: [(song: DownloadSong, category: SmartCategory)], nowMs: Int64) -> [String] {
        for p in picked { songs[p.song.videoId] = p.song }
        smart = picked.map { SmartTrack(videoId: $0.song.videoId, category: $0.category) }
        smartRefreshedAtMs = nowMs
        return prune()
    }

    /// スマートダウンロードを切った。
    @discardableResult
    public mutating func clearSmart() -> [String] {
        smart = []
        smartRefreshedAtMs = 0
        return prune()
    }

    /// 持ち主のいない曲の情報を捨て、その id を返す（ファイルを消す）。
    @discardableResult
    public mutating func prune() -> [String] {
        let owned = Set(wanted)
        let gone = songs.keys.filter { !owned.contains($0) }.sorted()
        for id in gone { songs.removeValue(forKey: id) }
        return gone
    }

    /// 分類の曲（all なら全部。取る順）。
    public func smartIds(_ category: SmartCategory?) -> [String] {
        smart.filter { category == nil || $0.category == category }.map(\.videoId)
    }

    /// 1日1回の選び直しの時か。
    public func smartDue(nowMs: Int64, intervalMs: Int64 = 24 * 60 * 60 * 1000) -> Bool { smartRefreshedAtMs == 0 || nowMs - smartRefreshedAtMs >= intervalMs }

    public func collectionsDue(nowMs: Int64, intervalMs: Int64 = 24 * 60 * 60 * 1000) -> Bool { collectionsSyncedAtMs == 0 || nowMs - collectionsSyncedAtMs >= intervalMs }
}
