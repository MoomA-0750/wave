import Foundation

/// ポッドキャストの番組のカード（検索・ライブラリ。Android の PodcastSummary と同じ）。
public struct PodcastSummary: Hashable, Sendable, Identifiable {
    public static let podcastPrefix = "MPSP"

    /// `MPSP…`（番組のページ）。
    public var browseId: String
    public var title: String
    /// 配信者（「Rebuild Podcast」など）。
    public var author: String?
    public var thumbnailUrl: String?

    public init(browseId: String, title: String, author: String?, thumbnailUrl: String?) {
        self.browseId = browseId
        self.title = title
        self.author = author
        self.thumbnailUrl = thumbnailUrl
    }

    public var id: String { browseId }

    /// 番組のエピソードの一覧のプレイリスト（`PL…`）。保存と、エピソードを続けて流すのに使う。
    public var playlistId: String { PodcastSummary.playlistId(of: browseId) }

    /// MPSP（番組）・VL（「新しいエピソード」の VLRDPN）を外したプレイリストの ID。
    static func playlistId(of browseId: String) -> String {
        let id = browseId.hasPrefix(podcastPrefix) ? String(browseId.dropFirst(podcastPrefix.count)) : browseId
        return id.hasPrefix("VL") ? String(id.dropFirst(2)) : id
    }
}

/// ポッドキャストのエピソード。再生は曲と同じ（song の videoId で流す。番組名を song.artists に入れてある）。
public struct PodcastEpisode: Hashable, Sendable, Identifiable {
    public var song: Song
    /// 「6 日前」「2024/10/01」など、応答の表示のまま。
    public var publishedText: String?
    /// 「1 時間 37 分」など、応答の表示のまま（番組のページだけ。検索では nil）。
    public var durationText: String?
    public var description: String?
    /// 聴いたところ（1〜100）。ログインしていない・聴いていなければ nil。
    public var progressPercent: Int?
    /// 番組のページ（`MPSP…`）。分からなければ nil。
    public var showBrowseId: String?

    public init(song: Song, publishedText: String?, durationText: String? = nil, description: String? = nil, progressPercent: Int? = nil,
                showBrowseId: String? = nil)
    {
        self.song = song
        self.publishedText = publishedText
        self.durationText = durationText
        self.description = description
        self.progressPercent = progressPercent
        self.showBrowseId = showBrowseId
    }

    public var id: String { song.videoId }

    /// 「番組 • 日付」（検索の行）。
    public var showAndDate: String {
        [song.artistsText, publishedText].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " • ")
    }

    /// 「日付 • 長さ」（番組のページの行）。
    public var dateAndLength: String {
        [publishedText, durationText].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " • ")
    }
}

/// ポッドキャストの番組のページ（`MPSP…`）。「新しいエピソード」（`VLRDPN`）も同じ形で読む。
public struct PodcastPage: Sendable {
    public var browseId: String
    public var title: String
    public var author: String?
    public var description: String?
    public var thumbnailUrl: String?
    /// 新しい順のエピソード（最初のページ）。
    public var episodes: [PodcastEpisode]
    /// エピソードの続き。無ければ最後のページ。
    public var continuation: String?
    /// ライブラリに保存しているか（ログインしていない・分からなければ nil）。
    public var saved: Bool?

    public init(browseId: String, title: String, author: String?, description: String?, thumbnailUrl: String?, episodes: [PodcastEpisode],
                continuation: String?, saved: Bool?)
    {
        self.browseId = browseId
        self.title = title
        self.author = author
        self.description = description
        self.thumbnailUrl = thumbnailUrl
        self.episodes = episodes
        self.continuation = continuation
        self.saved = saved
    }

    /// 番組のプレイリスト（PL…）。「新しいエピソード」（VLRDPN）なら RDPN（再生の出どころに使う）。
    public var playlistId: String { PodcastSummary.playlistId(of: browseId) }

    public var summary: PodcastSummary { PodcastSummary(browseId: browseId, title: title, author: author, thumbnailUrl: thumbnailUrl) }
}
