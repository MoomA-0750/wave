import Foundation
import WaveCore

/// 端末ごとの設定と、検索の履歴（認証情報は含まない）。Windows 版の Settings と同じ項目。
final class Settings: Codable {
    var volume: Double = 0.7
    var shuffle = false
    var repeatMode = 0
    /// 再生を YouTube Music の再生履歴に送る（Android 版の既定と同じく、オン）。
    var sendHistory = true
    var rightPaneOpen = true
    /// 登録したアーティストの新作を通知で知らせる（アプリが動いている間。Android と同じく既定はオン）。
    var releaseNotices = true
    /// マイライブラリの並べ方（recent: 最近再生した順・added: 最近追加した順・alpha: アルファベット順）と、グリッドで出すか。
    var librarySort = "recent"
    var libraryGrid = false
    /// プレイリストごとに選んだ並べ方（`added:desc` など）。Android と同じく開くたびにサーバーへ明示して送る。
    var playlistOrders: [String: String] = [:]
    /// 画面の言語。"system"（macOS に合わせる）・"ja"・"en"。次の起動から効く。
    var language = "system"
    var searchHistory: [SearchHistoryEntry] = []
    /// 端末の連携の中継 v2 の場所（例: wss://ホスト:ポート）と、署名に入れるホスト名とポート。空なら連携を使わない。
    var relayV2Url: String?
    var relayV2Host: String?
    /// Claude の中継（ai-bridge）の接続先・トークン・機械の名前。空なら使わない。
    var aiUrl: String?
    var aiToken: String?
    var aiName: String?
    /// Discord に再生中の曲を出す（既定はオフ。macOS だけ）と、そのための Discord のアプリケーション ID（discord.com/developers で作る）。
    var discordPresence = false
    var discordClientId: String?
    /// 映像の無い曲を鳴らしているときも、ホームへ戻ったら小窓（PiP）に曲名とジャケットを出す（iPhone だけ。既定はオン）。
    var musicPip = true
    /// 下のタブの欄で、右に離して置き、欄を畳んでも出しておくタブ（search・ai・library。iPhone だけ。既定は検索）。
    var pinnedTab = "search"

    private static var fileURL: URL { AppPaths.file("settings.json") }

    init() {}

    enum CodingKeys: String, CodingKey {
        case volume, shuffle, repeatMode, sendHistory, rightPaneOpen, releaseNotices, librarySort, libraryGrid, playlistOrders, language,
            searchHistory, relayV2Url, relayV2Host, aiUrl, aiToken, aiName, discordPresence, discordClientId, musicPip,
            pinnedTab
    }

    // 項目が足りないファイル（前の版）も読めるよう、無い項目は既定のまま
    required init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        volume = try c.decodeIfPresent(Double.self, forKey: .volume) ?? volume
        shuffle = try c.decodeIfPresent(Bool.self, forKey: .shuffle) ?? shuffle
        repeatMode = try c.decodeIfPresent(Int.self, forKey: .repeatMode) ?? repeatMode
        sendHistory = try c.decodeIfPresent(Bool.self, forKey: .sendHistory) ?? sendHistory
        rightPaneOpen = try c.decodeIfPresent(Bool.self, forKey: .rightPaneOpen) ?? rightPaneOpen
        releaseNotices = try c.decodeIfPresent(Bool.self, forKey: .releaseNotices) ?? releaseNotices
        librarySort = try c.decodeIfPresent(String.self, forKey: .librarySort) ?? librarySort
        libraryGrid = try c.decodeIfPresent(Bool.self, forKey: .libraryGrid) ?? libraryGrid
        playlistOrders = try c.decodeIfPresent([String: String].self, forKey: .playlistOrders) ?? playlistOrders
        language = try c.decodeIfPresent(String.self, forKey: .language) ?? language
        searchHistory = (try? c.decodeIfPresent([SearchHistoryEntry].self, forKey: .searchHistory)) ?? []
        relayV2Url = try c.decodeIfPresent(String.self, forKey: .relayV2Url)
        relayV2Host = try c.decodeIfPresent(String.self, forKey: .relayV2Host)
        aiUrl = try c.decodeIfPresent(String.self, forKey: .aiUrl)
        aiToken = try c.decodeIfPresent(String.self, forKey: .aiToken)
        aiName = try c.decodeIfPresent(String.self, forKey: .aiName)
        discordPresence = try c.decodeIfPresent(Bool.self, forKey: .discordPresence) ?? discordPresence
        discordClientId = try c.decodeIfPresent(String.self, forKey: .discordClientId)
        musicPip = try c.decodeIfPresent(Bool.self, forKey: .musicPip) ?? musicPip
        pinnedTab = try c.decodeIfPresent(String.self, forKey: .pinnedTab) ?? pinnedTab
    }

    static func load() -> Settings {
        guard let data = try? Data(contentsOf: fileURL), let settings = try? JSONDecoder().decode(Settings.self, from: data) else { return Settings() }
        return settings
    }

    func save() {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(self) else { return }
        try? data.write(to: Settings.fileURL, options: .atomic)
    }

    /// 検索の履歴に足す（同じものは先頭へ動かす。最大 30 件）。
    func addSearch(_ entry: SearchHistoryEntry) {
        searchHistory.removeAll { $0.key == entry.key }
        searchHistory.insert(entry, at: 0)
        if searchHistory.count > 30 { searchHistory.removeLast(searchHistory.count - 30) }
        save()
    }

    func removeSearch(_ entry: SearchHistoryEntry) {
        searchHistory.removeAll { $0.key == entry.key }
        save()
    }
}

/// 検索の履歴の1件（語・アーティスト・アルバム・曲・プレイリスト・ポッドキャスト。Windows と同じ。Spotify のように画像付きで出す）。
struct SearchHistoryEntry: Codable, Hashable, Identifiable {
    var kind = "query"
    var title = ""
    var subtitle: String?
    var thumbnailUrl: String?
    var id_: String?
    var song: SongRecord?

    enum CodingKeys: String, CodingKey {
        case kind, title, subtitle, thumbnailUrl, song
        case id_ = "id"
    }

    var key: String { kind + ":" + (id_ ?? title) }
    var id: String { key }
    var isRound: Bool { kind == "artist" }
    var isQuery: Bool { kind == "query" }

    static func query(_ q: String) -> SearchHistoryEntry { SearchHistoryEntry(kind: "query", title: q) }

    static func of(_ item: HomeItem) -> SearchHistoryEntry {
        switch item {
        case .song(let s):
            SearchHistoryEntry(kind: "song", title: s.title, subtitle: L.t("曲 • ", "Song • ") + s.artistsText, thumbnailUrl: s.thumbnailUrl, id_: s.videoId, song: SongRecord(s))
        case .album(let a):
            SearchHistoryEntry(kind: "album", title: a.title, subtitle: a.subtitle, thumbnailUrl: a.thumbnailUrl, id_: a.browseId)
        case .artist(let a):
            SearchHistoryEntry(kind: "artist", title: a.name, subtitle: L.t("アーティスト", "Artist"), thumbnailUrl: a.thumbnailUrl, id_: a.browseId)
        case .playlist(let p):
            SearchHistoryEntry(kind: "playlist", title: p.title, subtitle: p.subtitle, thumbnailUrl: p.thumbnailUrl, id_: p.playlistId)
        }
    }

    /// 検索から開いたポッドキャスト（番組）。
    static func of(_ podcast: PodcastSummary) -> SearchHistoryEntry {
        SearchHistoryEntry(kind: "podcast", title: podcast.title, subtitle: podcast.author ?? L.t("ポッドキャスト", "Podcast"),
                           thumbnailUrl: podcast.thumbnailUrl, id_: podcast.browseId)
    }

    /// ポッドキャストの履歴なら、その番組。
    func toPodcast() -> PodcastSummary? {
        guard kind == "podcast", let id = id_ else { return nil }
        return PodcastSummary(browseId: id, title: title, author: subtitle, thumbnailUrl: thumbnailUrl)
    }

    func toItem() -> HomeItem? {
        switch kind {
        case "song": return song.map { .song($0.song) }
        case "album": return id_.map { .album(AlbumSummary(browseId: $0, title: title, subtitle: subtitle, thumbnailUrl: thumbnailUrl)) }
        case "artist": return id_.map { .artist(ArtistSummary(browseId: $0, name: title, subtitle: subtitle, thumbnailUrl: thumbnailUrl)) }
        case "playlist": return id_.map { .playlist(PlaylistSummary(playlistId: $0, title: title, subtitle: subtitle, thumbnailUrl: thumbnailUrl)) }
        default: return nil
        }
    }
}
