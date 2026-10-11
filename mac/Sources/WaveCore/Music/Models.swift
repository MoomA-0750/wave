import Foundation

/// 画面に依らない曲の表現。
public struct Song: Hashable, Sendable, Identifiable {
    public static let kindSong = "MUSIC_VIDEO_TYPE_ATV"
    public static let kindMusicVideo = "MUSIC_VIDEO_TYPE_OMV"
    public static let kindUserVideo = "MUSIC_VIDEO_TYPE_UGC"

    public var videoId: String
    public var title: String
    public var artists: [ArtistRef]
    public var album: AlbumRef?
    public var durationSeconds: Int?
    public var thumbnailUrl: String?
    /// 曲の種類（musicVideoType の値。MUSIC_VIDEO_TYPE_ATV は音源、_OMV は公式の MV、_UGC は投稿者の動画）。応答に無ければ nil
    public var kind: String?

    public init(videoId: String, title: String, artists: [ArtistRef], album: AlbumRef?, durationSeconds: Int?, thumbnailUrl: String?, kind: String? = nil) {
        self.videoId = videoId
        self.title = title
        self.artists = artists
        self.album = album
        self.durationSeconds = durationSeconds
        self.thumbnailUrl = thumbnailUrl
        self.kind = kind
    }

    public var id: String { videoId }

    public var artistsText: String { artists.map(\.name).joined(separator: ", ") }

    /// 「アーティスト • アルバム」の1行。
    public var subtitle: String {
        [artistsText, album?.title].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " • ")
    }
}

public struct ArtistRef: Hashable, Sendable {
    public var name: String
    public var browseId: String?
    public init(name: String, browseId: String?) {
        self.name = name
        self.browseId = browseId
    }
}

public struct AlbumRef: Hashable, Sendable {
    public var title: String
    public var browseId: String?
    public init(title: String, browseId: String?) {
        self.title = title
        self.browseId = browseId
    }
}

public struct SongPage: Sendable {
    public var songs: [Song]
    public var continuation: String?
    public init(songs: [Song], continuation: String?) {
        self.songs = songs
        self.continuation = continuation
    }
}

public struct UpNextPage: Sendable {
    public var songs: [Song]
    public var continuation: String?
    public var playlistId: String?
    public init(songs: [Song], continuation: String?, playlistId: String?) {
        self.songs = songs
        self.continuation = continuation
        self.playlistId = playlistId
    }
}

public struct PlaylistSummary: Hashable, Sendable {
    public var playlistId: String
    public var title: String
    public var subtitle: String?
    public var thumbnailUrl: String?
    public var editable: Bool
    public init(playlistId: String, title: String, subtitle: String?, thumbnailUrl: String?, editable: Bool = false) {
        self.playlistId = playlistId
        self.title = title
        self.subtitle = subtitle
        self.thumbnailUrl = thumbnailUrl
        self.editable = editable
    }
}

public struct PlaylistPage: Sendable {
    public var title: String?
    public var songs: [Song]
    public var continuation: String?
    public var setVideoIds: [String?]
    public var editable: Bool
    public var endConfirmed: Bool
    public var subtitle: String?
    public var thumbnailUrl: String?
    /// サーバーで並べ替えられる（自分のプレイリストなど。並べ替えのメニューがある）。
    public var sortable: Bool
    public init(title: String?, songs: [Song], continuation: String?, setVideoIds: [String?], editable: Bool, endConfirmed: Bool,
                subtitle: String? = nil, thumbnailUrl: String? = nil, sortable: Bool = false)
    {
        self.title = title
        self.songs = songs
        self.continuation = continuation
        self.setVideoIds = setVideoIds
        self.editable = editable
        self.endConfirmed = endConfirmed
        self.subtitle = subtitle
        self.thumbnailUrl = thumbnailUrl
        self.sortable = sortable
    }
}

public struct HistorySection: Sendable {
    public var title: String?
    public var songs: [Song]
    public init(title: String?, songs: [Song]) {
        self.title = title
        self.songs = songs
    }
}

public struct HistoryPage: Sendable {
    public var sections: [HistorySection]
    public var continuation: String?
    public init(sections: [HistorySection], continuation: String?) {
        self.sections = sections
        self.continuation = continuation
    }
}

public enum LikeStatus: String, Sendable {
    case like = "Like"
    case dislike = "Dislike"
    case indifferent = "Indifferent"
}

public struct AlbumSummary: Hashable, Sendable {
    public var browseId: String
    public var title: String
    public var subtitle: String?
    public var thumbnailUrl: String?
    public init(browseId: String, title: String, subtitle: String?, thumbnailUrl: String?) {
        self.browseId = browseId
        self.title = title
        self.subtitle = subtitle
        self.thumbnailUrl = thumbnailUrl
    }
}

public struct AlbumPage: Sendable {
    public var browseId: String
    public var title: String
    public var subtitle: String?
    public var artists: [ArtistRef]
    public var thumbnailUrl: String?
    public var playlistId: String?
    public var songs: [Song]
    public init(browseId: String, title: String, subtitle: String?, artists: [ArtistRef], thumbnailUrl: String?, playlistId: String?, songs: [Song]) {
        self.browseId = browseId
        self.title = title
        self.subtitle = subtitle
        self.artists = artists
        self.thumbnailUrl = thumbnailUrl
        self.playlistId = playlistId
        self.songs = songs
    }
}

/// アーティストの YouTube のチャンネルの投稿（本人のお知らせ）。publishedText は「2 日前」など応答の表示のまま。
public struct ArtistPost: Hashable, Sendable {
    public var postId: String
    public var text: String
    public var publishedText: String?
    public var imageUrl: String?
    public init(postId: String, text: String, publishedText: String?, imageUrl: String?) {
        self.postId = postId
        self.text = text
        self.publishedText = publishedText
        self.imageUrl = imageUrl
    }

    public var url: String { "https://www.youtube.com/post/" + postId }
}

/// アーティストの投稿のうち、ライブ・ツアー・出演の告知らしいもの（Android の LiveNews と同じ）。
public enum LiveNews {
    private static let keywords = try! NSRegularExpression(
        pattern: #"ライブ|ﾗｲﾌﾞ|ツアー|公演|ワンマン|チケット|開催決定|出演決定|フェス|ドーム|アリーナ|ホール|\bLIVE\b|\bTOUR\b|\bconcert|\btickets?\b|\bfestival\b"#,
        options: [.caseInsensitive])

    // 「1 年前」「2 years ago」など、年で数える古さ（1年以上前）
    private static let yearsAgo = try! NSRegularExpression(pattern: #"\d+\s*(年前|years? ago)"#, options: [.caseInsensitive])

    public static func isLiveRelated(_ text: String) -> Bool { keywords.matches(text) }

    /// 1年以内の投稿か（投稿の時期の表示が無ければ、分からないので残す）。
    public static func isRecent(_ publishedText: String?) -> Bool {
        guard let publishedText else { return true }
        return !yearsAgo.matches(publishedText)
    }

    /// 1年以内の、告知らしい投稿だけ（何年も前の告知は終わっているので出さない）。
    public static func of(_ posts: [ArtistPost]) -> [ArtistPost] {
        posts.filter { isRecent($0.publishedText) && isLiveRelated($0.text) }
    }
}

public extension NSRegularExpression {
    func matches(_ text: String) -> Bool {
        firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }
}

public struct ArtistPage: Sendable {
    public var name: String
    public var thumbnailUrl: String?
    public var topSongs: [Song]
    public var allSongsPlaylistId: String?
    public var albumShelves: [AlbumShelf]
    /// 登録（サブスクライブ）に使うチャンネルと、登録しているか（分からなければ nil）
    public var channelId: String?
    public var subscribed: Bool?
    /// 動画の棚（MV・YouTube の動画）。新作の知らせに使う
    public var videos: [Song]
    public init(name: String, thumbnailUrl: String?, topSongs: [Song], allSongsPlaylistId: String?, albumShelves: [AlbumShelf],
                channelId: String? = nil, subscribed: Bool? = nil, videos: [Song] = [])
    {
        self.name = name
        self.thumbnailUrl = thumbnailUrl
        self.topSongs = topSongs
        self.allSongsPlaylistId = allSongsPlaylistId
        self.albumShelves = albumShelves
        self.channelId = channelId
        self.subscribed = subscribed
        self.videos = videos
    }
}

public struct AlbumShelf: Sendable {
    public var title: String
    public var albums: [AlbumSummary]
    public init(title: String, albums: [AlbumSummary]) {
        self.title = title
        self.albums = albums
    }
}

public struct ArtistSummary: Hashable, Sendable {
    public var browseId: String
    public var name: String
    public var subtitle: String?
    public var thumbnailUrl: String?
    public init(browseId: String, name: String, subtitle: String?, thumbnailUrl: String?) {
        self.browseId = browseId
        self.name = name
        self.subtitle = subtitle
        self.thumbnailUrl = thumbnailUrl
    }
}

public struct SearchPage<T: Sendable>: Sendable {
    public var items: [T]
    public var continuation: String?
    public init(items: [T], continuation: String?) {
        self.items = items
        self.continuation = continuation
    }
}

public struct SearchSuggestions: Sendable {
    public var queries: [String]
    public var songs: [Song]
    public var albums: [AlbumSummary]
    public var artists: [ArtistSummary]
    public init(queries: [String], songs: [Song], albums: [AlbumSummary], artists: [ArtistSummary]) {
        self.queries = queries
        self.songs = songs
        self.albums = albums
        self.artists = artists
    }

    public var isEmpty: Bool { queries.isEmpty && songs.isEmpty && albums.isEmpty && artists.isEmpty }
}

/// ホームの棚の1枚。曲・アルバム・プレイリスト・アーティストが混ざる。
public enum HomeItem: Hashable, Sendable, Identifiable {
    case song(Song)
    case album(AlbumSummary)
    case playlist(PlaylistSummary)
    case artist(ArtistSummary)

    public var title: String {
        switch self {
        case .song(let s): s.title
        case .album(let a): a.title
        case .playlist(let p): p.title
        case .artist(let a): a.name
        }
    }

    public var subtitle: String? {
        switch self {
        case .song(let s): s.subtitle
        case .album(let a): a.subtitle
        case .playlist(let p): p.subtitle
        case .artist(let a): a.subtitle
        }
    }

    public var thumbnailUrl: String? {
        switch self {
        case .song(let s): s.thumbnailUrl
        case .album(let a): a.thumbnailUrl
        case .playlist(let p): p.thumbnailUrl
        case .artist(let a): a.thumbnailUrl
        }
    }

    public var isArtist: Bool { if case .artist = self { return true } else { return false } }

    /// 同じものを見分けるための値（重複を除くのに使う）。
    public var key: String {
        switch self {
        case .song(let s): "s:" + s.videoId
        case .album(let a): "a:" + a.browseId
        case .playlist(let p): "p:" + p.playlistId
        case .artist(let a): "r:" + a.browseId
        }
    }

    public var id: String { key }
}

/// ホームの棚。見出しは応答の表示のまま（言語に依るので判定に使わない）。
public struct HomeShelf: Sendable {
    public var title: String
    public var strapline: String?
    public var items: [HomeItem]
    public init(title: String, strapline: String?, items: [HomeItem]) {
        self.title = title
        self.strapline = strapline
        self.items = items
    }
}

public struct HomeChip: Hashable, Sendable {
    public var title: String
    public var params: String
    public init(title: String, params: String) {
        self.title = title
        self.params = params
    }
}

public struct HomePage: Sendable {
    public var shelves: [HomeShelf]
    public var continuation: String?
    public var chips: [HomeChip]
    public init(shelves: [HomeShelf], continuation: String?, chips: [HomeChip]) {
        self.shelves = shelves
        self.continuation = continuation
        self.chips = chips
    }
}

public struct MoodCategory: Hashable, Sendable {
    public var title: String
    public var params: String
    public var color: Int64?
    public init(title: String, params: String, color: Int64?) {
        self.title = title
        self.params = params
        self.color = color
    }
}

public struct MoodSection: Sendable {
    public var title: String
    public var categories: [MoodCategory]
    public init(title: String, categories: [MoodCategory]) {
        self.title = title
        self.categories = categories
    }
}

/// next の最初の応答から分かる、曲の評価と歌詞のタブと、曲に重ねる映像（無ければ nil）。
public struct SongInfo: Sendable {
    public var like: LikeStatus?
    public var lyricsBrowseId: String?
    public var video: MusicVideo?
    public init(like: LikeStatus?, lyricsBrowseId: String?, video: MusicVideo? = nil) {
        self.like = like
        self.lyricsBrowseId = lyricsBrowseId
        self.video = video
    }
}

/// 音源と MV の時刻の対応の1区間（MV には前奏や間奏が足されていて、同じ時刻にはならない）。
public struct TimeSegment: Hashable, Sendable {
    public var primaryStartMs: Int64
    public var counterpartStartMs: Int64
    public var durationMs: Int64
    public init(primaryStartMs: Int64, counterpartStartMs: Int64, durationMs: Int64) {
        self.primaryStartMs = primaryStartMs
        self.counterpartStartMs = counterpartStartMs
        self.durationMs = durationMs
    }
}

/// 曲に見せる映像（Android の MusicVideo と同じ）。
public struct MusicVideo: Hashable, Sendable {
    public var videoId: String
    public var segments: [TimeSegment]
    public init(videoId: String, segments: [TimeSegment]) {
        self.videoId = videoId
        self.segments = segments
    }

    /// 曲の positionMs に当たる映像の時刻。対応する区間の外（MV に無い部分）なら nil。
    public func videoPositionOf(_ positionMs: Int64) -> Int64? {
        if segments.isEmpty { return positionMs }
        guard let s = segments.first(where: { positionMs >= $0.primaryStartMs && positionMs < $0.primaryStartMs + $0.durationMs }) else { return nil }
        return s.counterpartStartMs + (positionMs - s.primaryStartMs)
    }
}

/// 確かめたアカウント（DATASYNC_ID）と、確かめたときのログインの世代。id はログ・画面に出さない。
public struct VerifiedAccount: Sendable, CustomStringConvertible {
    public var generation: Int64
    public var id: String
    public init(generation: Int64, id: String) {
        self.generation = generation
        self.id = id
    }

    public var description: String { "VerifiedAccount(generation=\(generation))" }
}
