import Foundation

/// プレイリストへの追加の結果（Android 版の決定 G-1: 先に全ページを読んで重複を確かめる）。
public enum AddToPlaylistResult: Sendable, Equatable {
    case added
    case alreadyInPlaylist
    case notChecked
    case failed
}

/// アカウントの名前と画像。
public struct AccountInfo: Hashable, Sendable {
    public var name: String
    public var photoUrl: String?
    public init(name: String, photoUrl: String?) {
        self.name = name
        self.photoUrl = photoUrl
    }
}

/// 曲・プレイリスト・ラジオなどの取得と書き込み（Android 版の MusicRepository と同じ呼び方。Windows の MusicRepository を移したもの）。
/// throw するのは取り消し（CancellationError）だけ。
public final class MusicRepository: Sendable {
    public static let songsFilter = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
    public static let albumsFilter = "EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D"
    public static let artistsFilter = "EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D"
    public static let podcastsFilter = "EgWKAQJQAWoKEAkQBRAKEAMQBA%3D%3D"
    public static let episodesFilter = "EgWKAQJIAWoKEAkQBRAKEAMQBA%3D%3D"
    /// ライブラリのポッドキャスト（保存した番組）。
    public static let libraryPodcastsBrowse = "FEmusic_library_non_music_audio_list"
    /// 保存した番組の新しいエピソード（ログインしたときだけ中身がある自動のプレイリスト）。
    public static let newEpisodes = "RDPN"
    /// 「新しいエピソード」のページ（番組のページと同じ形で返る）。
    public static let newEpisodesBrowse = "VL" + newEpisodes
    public static let videosFilter = "EgWKAQIQAWoSEAUQBBAJEAMQChAVEA4QERAQ"
    public static let featuredPlaylistsFilter = "EgeKAQQoADgBahIQBRAEEAkQAxAKEBUQDhAREBA%3D"
    public static let communityPlaylistsFilter = "EgeKAQQoAEABahIQBRAEEAkQAxAKEBUQDhAREBA%3D"
    public static let libraryPlaylists = "FEmusic_liked_playlists"
    public static let history = "FEmusic_history"
    public static let home = "FEmusic_home"
    public static let libraryLanding = "FEmusic_library_landing"
    public static let moodsBrowse = "FEmusic_moods_and_genres"
    public static let libraryArtists = "FEmusic_library_corpus_track_artists"
    public static let libraryAlbums = "FEmusic_liked_albums"
    public static let subscribedArtists = "FEmusic_library_corpus_artists"
    public static let moodCategoryBrowse = "FEmusic_moods_and_genres_category"
    public static let newReleasesBrowse = "FEmusic_new_releases_albums"
    public static let likedSongs = "LM"
    private static let maxLibraryPages = 20
    private static let maxPlaylistPages = 50
    /// チャンネルの「投稿」タブを開く params。
    private static let postsParams = "EgVwb3N0c_IGBAoCSgA="

    public let client: InnerTubeClient
    /// ライブ配信の検索（www.youtube.com、認証情報を持たない）。無ければ YouTube Music の検索のライブだけを出す。
    public let live: LiveClient?

    public init(client: InnerTubeClient, live: LiveClient? = nil) {
        self.client = client
        self.live = live
    }

    public func searchSongs(_ query: String, continuation: String? = nil) async throws -> ApiResult<SongPage> {
        try await search(query, filter: MusicRepository.songsFilter, continuation: continuation, parse: MusicParsers.searchSongs)
    }

    public func searchAlbums(_ query: String, continuation: String? = nil) async throws -> ApiResult<SearchPage<AlbumSummary>> {
        try await search(query, filter: MusicRepository.albumsFilter, continuation: continuation, parse: MusicParsers.searchAlbums)
    }

    public func searchArtists(_ query: String, continuation: String? = nil) async throws -> ApiResult<SearchPage<ArtistSummary>> {
        try await search(query, filter: MusicRepository.artistsFilter, continuation: continuation, parse: MusicParsers.searchArtists)
    }

    /// ポッドキャスト（番組）に絞った検索。
    public func searchPodcasts(_ query: String, continuation: String? = nil) async throws -> ApiResult<SearchPage<PodcastSummary>> {
        try await search(query, filter: MusicRepository.podcastsFilter, continuation: continuation, parse: MusicParsers.searchPodcasts)
    }

    /// ポッドキャストのエピソードに絞った検索。
    public func searchEpisodes(_ query: String, continuation: String? = nil) async throws -> ApiResult<SearchPage<PodcastEpisode>> {
        try await search(query, filter: MusicRepository.episodesFilter, continuation: continuation, parse: MusicParsers.searchEpisodes)
    }

    /// ポッドキャストの番組のページ（MPSP…）。エピソードは新しい順。
    public func podcast(_ browseId: String) async throws -> ApiResult<PodcastPage> {
        let result = try await browse(browseId, continuation: nil, params: nil)
        guard result.isOk, let value = result.value else { return .failed(result.failure ?? .parse, status: result.status) }
        guard let page = MusicParsers.podcastPage(value, browseId: browseId) else { return .failed(.parse) }
        return .ok(page)
    }

    /// 番組のページのエピソードの続き。show は番組名（行に無いのでエピソードのアーティストとして入れる）。
    public func podcastEpisodes(continuation: String, show: ArtistRef, showBrowseId: String?) async throws -> ApiResult<SearchPage<PodcastEpisode>> {
        try await browse(nil, continuation: continuation, params: nil).map { MusicParsers.podcastEpisodes($0, show: show, showBrowseId: showBrowseId) }
    }

    /// ライブラリのポッドキャスト（保存した番組）。ログイン必須。
    public func libraryPodcasts(onPage: (([PodcastSummary]) -> Void)? = nil) async throws -> ApiResult<[PodcastSummary]> {
        try await allLibraryPages(MusicRepository.libraryPodcastsBrowse, parse: MusicParsers.libraryPodcasts, key: \.browseId, onPage: onPage)
    }

    /// ポッドキャストをライブラリに保存する・外す。番組のプレイリスト（PL…）を高く評価するのと同じ書き込み（Android と同じ）。
    public func setPodcastSaved(_ playlistId: String, saved: Bool) async throws -> ApiResult<Bool> {
        guard client.isLoggedIn else { return .failed(.notLoggedIn) }
        let body = JSON.obj(["target": .obj(["playlistId": .string(playlistId)])])
        return try await client.post(saved ? "like/like" : "like/removelike", body: body).map { _ in true }
    }

    private func search<T>(_ query: String, filter: String, continuation: String?, parse: (JSON) -> T) async throws -> ApiResult<T> {
        let body: JSON = continuation == nil ? .obj(["query": .string(query), "params": .string(filter)]) : .obj([:])
        let q = continuation.map { [("continuation", $0), ("ctoken", $0)] } ?? []
        return try await client.post("search", body: body, query: q).map(parse)
    }

    public func suggestions(_ input: String) async throws -> ApiResult<SearchSuggestions> {
        try await client.post("music/get_search_suggestions", body: .obj(["input": .string(input)]), setLogin: false).map(MusicParsers.searchSuggestions)
    }

    public func home(continuation: String? = nil, chipParams: String? = nil) async throws -> ApiResult<HomePage> {
        try await browse(MusicRepository.home, continuation: continuation, params: chipParams).map(MusicParsers.homePage)
    }

    /// 気分とジャンルの一覧（検索のタブのタイル）。ジャンルの先頭に、アプリで足した Lo-Fi を置く。
    public func moods() async throws -> ApiResult<[MoodSection]> {
        try await browse(MusicRepository.moodsBrowse, continuation: nil, params: nil).map { LofiGenre.addTo(MusicParsers.moods($0)) }
    }

    /// 気分・ジャンルの1つのページ（ホームと同じ棚の並び）。Lo-Fi はアプリで組んだページ。
    public func moodCategory(_ moodParams: String) async throws -> ApiResult<HomePage> {
        if moodParams == LofiGenre.params { return try await lofiPage() }
        return try await browse(MusicRepository.moodCategoryBrowse, continuation: nil, params: moodParams).map(MusicParsers.homePage)
    }

    /// ライブ配信の検索。最初のページは YouTube Music の動画の検索のうちライブのもの（Lofi Girl のような音楽のライブが上に来る）と、
    /// YouTube のライブの絞り込みを合わせる。続きは YouTube のライブの絞り込みだけ。両方失敗したときだけ失敗。
    public func searchLive(_ query: String, continuation: String? = nil) async throws -> ApiResult<SongPage> {
        if let continuation {
            guard let page = try await live?.search(query, continuation: continuation) else { return .failed(.network) }
            return .ok(page)
        }
        async let musicResult = search(query, filter: MusicRepository.videosFilter, continuation: nil, parse: MusicParsers.searchSongs)
        async let webResult = live?.search(query)
        let music = try await musicResult
        let web = try await webResult
        let musicLive = music.value?.songs.filter(\.isLive)
        if musicLive == nil && web == nil { return .failed(music.failure ?? .network, status: music.status) }
        var seen = Set<String>()
        let songs = ((musicLive ?? []) + (web?.songs ?? [])).filter { seen.insert($0.videoId).inserted }
        return .ok(SongPage(songs: songs, continuation: web?.continuation))
    }

    /// アプリで足した Lo-Fi のページ。ライブ配信・YouTube Music のプレイリスト・みんなのプレイリストの棚。
    public func lofiPage() async throws -> ApiResult<HomePage> {
        async let livesResult = searchLive(LofiGenre.liveQuery)
        async let featuredResult = search(LofiGenre.playlistQuery, filter: MusicRepository.featuredPlaylistsFilter, continuation: nil,
                                          parse: MusicParsers.searchPlaylists)
        async let communityResult = search(LofiGenre.playlistQuery, filter: MusicRepository.communityPlaylistsFilter, continuation: nil,
                                           parse: MusicParsers.searchPlaylists)
        let lives = try await livesResult
        let featured = try await featuredResult
        let community = try await communityResult
        let shelves = [
            lives.value.flatMap { LofiGenre.shelf(LofiGenre.liveTitle, $0.songs.prefix(LofiGenre.maxItems).map(HomeItem.song)) },
            featured.value.flatMap { LofiGenre.shelf(LofiGenre.featuredTitle, LofiGenre.featured($0.items).map(HomeItem.playlist)) },
            community.value.flatMap { LofiGenre.shelf(LofiGenre.communityTitle, $0.items.prefix(LofiGenre.maxItems).map(HomeItem.playlist)) },
        ].compactMap { $0 }
        if !shelves.isEmpty { return .ok(HomePage(shelves: shelves, continuation: nil, chips: [])) }
        if let failed = [lives.failure, featured.failure, community.failure].compactMap({ $0 }).first {
            return .failed(failed, status: [lives.status, featured.status, community.status].compactMap { $0 }.first)
        }
        return .ok(HomePage(shelves: [], continuation: nil, chips: []))
    }

    public func newReleases() async throws -> ApiResult<[AlbumSummary]> {
        try await browse(MusicRepository.newReleasesBrowse, continuation: nil, params: nil).map(MusicParsers.newReleases)
    }

    /// 最近再生したアルバム・プレイリスト・アーティスト。ログイン必須。
    public func recentActivity() async throws -> ApiResult<[HomeItem]> {
        try await loggedInBrowse(MusicRepository.libraryLanding, continuation: nil, parse: MusicParsers.gridItems)
    }

    public func libraryPlaylists(onPage: (([PlaylistSummary]) -> Void)? = nil) async throws -> ApiResult<[PlaylistSummary]> {
        try await allLibraryPages(MusicRepository.libraryPlaylists, parse: MusicParsers.playlists, key: \.playlistId, onPage: onPage)
    }

    public func libraryArtists(onPage: (([ArtistSummary]) -> Void)? = nil) async throws -> ApiResult<[ArtistSummary]> {
        try await allLibraryPages(MusicRepository.libraryArtists, parse: MusicRepository.artistCards, key: \.browseId, onPage: onPage)
    }

    /// 登録（サブスクライブ）したアーティスト。新作の知らせに使う（Android と同じ）。
    public func subscribedArtists() async throws -> ApiResult<[ArtistSummary]> {
        try await allLibraryPages(MusicRepository.subscribedArtists, parse: MusicRepository.artistCards, key: \.browseId, onPage: nil)
    }

    public func libraryAlbums(onPage: (([AlbumSummary]) -> Void)? = nil) async throws -> ApiResult<[AlbumSummary]> {
        try await allLibraryPages(MusicRepository.libraryAlbums, parse: MusicRepository.albumCards, key: \.browseId, onPage: onPage)
    }

    private static func artistCards(_ root: JSON) -> [ArtistSummary] {
        MusicParsers.libraryCards(root).compactMap { if case .artist(let a) = $0 { return a } else { return nil } }
    }

    private static func albumCards(_ root: JSON) -> [AlbumSummary] {
        MusicParsers.libraryCards(root).compactMap { if case .album(let a) = $0 { return a } else { return nil } }
    }

    /// ライブラリの一覧を続きのページまで読む。途中で失敗したらそこまでの分を返す。onPage にはそれまでの分を渡す。
    private func allLibraryPages<T, K: Hashable>(_ browseId: String, parse: @escaping (JSON) -> [T], key: (T) -> K,
                                                 onPage: (([T]) -> Void)?) async throws -> ApiResult<[T]>
    {
        var items: [T] = []
        var token: String?
        var pages = 0
        repeat {
            let page = try await loggedInBrowse(browseId, continuation: token) { root in (items: parse(root), next: MusicParsers.libraryContinuation(root)) }
            guard let value = page.value, page.isOk else {
                return pages == 0 ? .failed(page.failure ?? .parse, status: page.status) : .ok(MusicRepository.distinct(items, key))
            }
            items += value.items
            onPage?(MusicRepository.distinct(items, key))
            // 同じトークンが返ったら止める
            token = value.next != token ? value.next : nil
            pages += 1
        } while token != nil && pages < MusicRepository.maxLibraryPages
        return .ok(MusicRepository.distinct(items, key))
    }

    private static func distinct<T, K: Hashable>(_ items: [T], _ key: (T) -> K) -> [T] {
        var seen = Set<K>()
        return items.filter { seen.insert(key($0)).inserted }
    }

    public func history(continuation: String? = nil) async throws -> ApiResult<HistoryPage> {
        try await loggedInBrowse(MusicRepository.history, continuation: continuation, parse: MusicParsers.historyPage)
    }

    /// 次に流れる曲（ラジオ）。最初は videoId と playlistId（RDAMVM<videoId>）、続きは continuation で取る。
    public func upNext(videoId: String?, playlistId: String?, continuation: String? = nil) async throws -> ApiResult<UpNextPage> {
        var body = JSONObject([("contentCheckOk", true), ("racyCheckOk", true)])
        if let videoId { body["videoId"] = .string(videoId) }
        if let playlistId { body["playlistId"] = .string(playlistId) }
        if let continuation { body["continuation"] = .string(continuation) }
        return try await client.post("next", body: .object(body)).map(MusicParsers.upNext)
    }

    /// プレイリストの曲。高く評価した曲は playlistId = LM。
    /// - Parameter order: 並べ方の params（PlaylistOrder）。nil なら付けない
    public func playlist(_ playlistId: String, continuation: String? = nil, order: String? = nil) async throws -> ApiResult<PlaylistPage> {
        try await loggedInBrowse("VL" + playlistId, continuation: continuation, parse: MusicParsers.playlistPage, params: order)
    }

    /// プレイリストの曲を全部読む（再生とシャッフル用）。onPage にはそれまでの分を渡す。
    public func fullPlaylist(_ playlistId: String, onPage: ((PlaylistPage) -> Void)? = nil, order: String? = nil) async throws -> ApiResult<PlaylistPage> {
        let first = try await playlist(playlistId, continuation: nil, order: order)
        guard first.isOk, var page = first.value else { return first }
        var songs = page.songs
        var setIds = page.setVideoIds
        var token = page.continuation
        onPage?(page)
        var i = 0
        while token != nil && i < MusicRepository.maxPlaylistPages {
            let next = try await playlist(playlistId, continuation: token)
            guard next.isOk, let value = next.value else { break }
            songs += value.songs
            setIds += value.setVideoIds
            token = value.continuation != token ? value.continuation : nil
            var partial = page
            partial.songs = songs
            partial.setVideoIds = setIds
            partial.continuation = token
            onPage?(partial)
            i += 1
        }
        page.songs = songs
        page.setVideoIds = setIds
        page.continuation = token
        return .ok(page)
    }

    /// 曲の評価と歌詞のタブ（next の最初の応答から取る）。
    public func songInfo(_ videoId: String) async throws -> ApiResult<SongInfo> {
        try await client.post("next", body: .obj(["videoId": .string(videoId), "contentCheckOk": true, "racyCheckOk": true]))
            .map { MusicParsers.songInfo($0, videoId: videoId) }
    }

    /// YouTube Music の時刻なしの歌詞。無ければ ok(nil)。
    public func plainLyrics(_ videoId: String) async throws -> ApiResult<SongLyrics.Plain?> {
        let info = try await songInfo(videoId)
        guard info.isOk, let value = info.value else { return .failed(info.failure ?? .parse, status: info.status) }
        guard let browseId = value.lyricsBrowseId else { return .ok(nil) }
        return try await browse(browseId, continuation: nil, params: nil).map(MusicParsers.plainLyrics)
    }

    /// アーティストの YouTube のチャンネルの投稿（本人のお知らせ。ライブ・ツアーの告知を拾うのに使う。Android と同じ）。
    public func artistPosts(_ channelId: String) async throws -> ApiResult<[ArtistPost]> {
        try await client.postYouTube("browse", body: .obj(["browseId": .string(channelId), "params": .string(MusicRepository.postsParams)]))
            .map(MusicParsers.artistPosts)
    }

    /// アーティスト（チャンネル）を登録する・登録を外す（Android と同じ）。
    public func setSubscribed(_ channelId: String, subscribe: Bool) async throws -> ApiResult<Bool> {
        guard client.isLoggedIn else { return .failed(.notLoggedIn) }
        let body = JSON.obj(["channelIds": .array([.string(channelId)])])
        return try await client.post(subscribe ? "subscription/subscribe" : "subscription/unsubscribe", body: body).map { _ in true }
    }

    public func setLiked(_ videoId: String, liked: Bool) async throws -> ApiResult<Bool> {
        guard client.isLoggedIn else { return .failed(.notLoggedIn) }
        let body = JSON.obj(["target": .obj(["videoId": .string(videoId)])])
        return try await client.post(liked ? "like/like" : "like/removelike", body: body).map { _ in true }
    }

    /// プレイリストの中の曲の ID を全ページ読む（重複の確認と「どのプレイリストに入っているか」の表示。Android と同じ）。
    /// 最後まで読めなければ nil。
    public func scanPlaylist(_ playlistId: String) async throws -> Set<String>? {
        try await scanPlaylistItems(playlistId).map { Set($0.keys) }
    }

    /// プレイリストの中の曲と、曲ごとのプレイリスト内の項目の ID（外すのに使う。同じ曲が2回入っていれば2つ。取れなかった曲は空）。
    /// 最後まで読めなければ nil。
    public func scanPlaylistItems(_ playlistId: String) async throws -> [String: [String]]? {
        var ids: [String: [String]] = [:]
        var token: String?
        var pages = 0
        repeat {
            let page = try await playlist(playlistId, continuation: token)
            guard page.isOk, let value = page.value else { return nil }
            for (i, song) in value.songs.enumerated() {
                var setIds = ids[song.videoId] ?? []
                if i < value.setVideoIds.count, let setId = value.setVideoIds[i] { setIds.append(setId) }
                ids[song.videoId] = setIds
            }
            token = value.continuation
            if token == nil && !value.endConfirmed { return nil }
            pages += 1
            if pages >= MusicRepository.maxPlaylistPages && token != nil { return nil }
        } while token != nil
        return ids
    }

    /// 曲をプレイリストに追加する。先に全ページを読んで重複を確かめ、読めなければ追加しない。
    public func addToPlaylist(_ playlistId: String, videoId: String, knownEmpty: Bool = false) async throws -> AddToPlaylistResult {
        if !knownEmpty {
            guard let ids = try await scanPlaylist(playlistId) else { return .notChecked }
            if ids.contains(videoId) { return .alreadyInPlaylist }
        }
        let ok = try await editPlaylist(playlistId, action: .obj(["action": "ACTION_ADD_VIDEO", "addedVideoId": .string(videoId)]))
        return ok ? .added : .failed
    }

    public func removeFromPlaylist(_ playlistId: String, videoId: String, setVideoId: String) async throws -> Bool {
        try await editPlaylist(playlistId, action: .obj(["action": "ACTION_REMOVE_VIDEO", "setVideoId": .string(setVideoId), "removedVideoId": .string(videoId)]))
    }

    private func editPlaylist(_ playlistId: String, action: JSON) async throws -> Bool {
        guard client.isLoggedIn else { return false }
        let id = playlistId.hasPrefix("VL") ? String(playlistId.dropFirst(2)) : playlistId
        let body = JSON.obj(["playlistId": .string(id), "actions": .array([action])])
        let result = try await client.post("browse/edit_playlist", body: body)
        guard result.isOk, let value = result.value else { return false }
        return MusicParsers.editPlaylistStatus(value) == "STATUS_SUCCEEDED"
    }

    /// 非公開のプレイリストを作る。作った playlistId を返す。
    public func createPlaylist(_ title: String) async throws -> ApiResult<String> {
        guard client.isLoggedIn else { return .failed(.notLoggedIn) }
        let result = try await client.post("playlist/create", body: .obj(["title": .string(title), "privacyStatus": "PRIVATE"]))
        guard result.isOk, let value = result.value else { return .failed(result.failure ?? .parse, status: result.status) }
        guard let id = MusicParsers.createdPlaylistId(value) else { return .failed(.parse) }
        return .ok(id)
    }

    public func album(_ browseId: String) async throws -> ApiResult<AlbumPage> {
        let result = try await browse(browseId, continuation: nil, params: nil)
        guard result.isOk, let value = result.value else { return .failed(result.failure ?? .parse, status: result.status) }
        guard let page = MusicParsers.albumPage(value, browseId: browseId) else { return .failed(.parse) }
        return .ok(page)
    }

    public func artist(_ browseId: String) async throws -> ApiResult<ArtistPage> {
        let result = try await browse(browseId, continuation: nil, params: nil)
        guard result.isOk, let value = result.value else { return .failed(result.failure ?? .parse, status: result.status) }
        guard let page = MusicParsers.artistPage(value) else { return .failed(.parse) }
        return .ok(page)
    }

    /// アカウントの名前と画像（ログインの確認を兼ねる）。
    public func account() async throws -> ApiResult<AccountInfo> {
        guard client.isLoggedIn else { return .failed(.notLoggedIn) }
        let result = try await client.post("account/account_menu", body: .obj([:]))
        guard result.isOk, let value = result.value else { return .failed(result.failure ?? .parse, status: result.status) }
        guard let header = value.findAll("activeAccountHeaderRenderer").first else { return .failed(.notLoggedIn) }
        let name = header.path("accountName", "runs").items.map { $0.path("text").str ?? "" }.joined()
        let photo = header.path("accountPhoto", "thumbnails").items.last.path("url").str
        return .ok(AccountInfo(name: name, photoUrl: photo))
    }

    /// 今のログインが、保存した DATASYNC_ID のアカウントか（Android の WaveSession.verifiedAccount と同じ）。端末の連携でアカウントの照合に使う。
    /// accounts_list の選択中のアカウントがちょうど1件で、その datasyncIdToken（"||" より前）が保存した値と同じで、
    /// 確かめている間にログインが変わっていないときだけ返す。値はログ・画面に出さない。
    public func verifiedAccount() async throws -> VerifiedAccount? {
        let generation = client.generation
        guard let saved = client.currentCredentials?.revealDataSyncId() else { return nil }
        let result = try await client.post("account/accounts_list", body: .obj([:]))
        guard result.isOk, let value = result.value else { return nil }
        let selected = MusicRepository.distinct(MusicRepository.accountTokens(value).filter(\.selected).map(\.id), { $0 })
        guard selected.count == 1, selected[0] == saved, client.generation == generation else { return nil }
        return VerifiedAccount(generation: generation, id: saved)
    }

    /// accounts_list の応答から、各アカウントの（選択中か, datasyncIdToken の "||" より前）を集める。
    static func accountTokens(_ root: JSON) -> [(selected: Bool, id: String)] {
        var tokens: [(selected: Bool, id: String)] = []
        func walk(_ node: JSON, _ selected: Bool) {
            switch node {
            case .object(let o):
                let flag = o["isSelected"]
                let isSelected = selected || flag == JSON.bool(true) || flag == JSON.string("true")
                for (name, value) in o.pairs {
                    if name == "datasyncIdToken", let text = value.str, let id = Credentials.normalizeDataSyncId(text) {
                        tokens.append((isSelected, id))
                    }
                    walk(value, isSelected)
                }
            case .array(let a):
                for item in a { walk(item, selected) }
            default:
                break
            }
        }
        walk(root, false)
        return tokens
    }

    private func browse(_ browseId: String?, continuation: String?, params: String?) async throws -> ApiResult<JSON> {
        var body = JSONObject()
        if let continuation {
            body["continuation"] = .string(continuation)
        } else {
            body["browseId"] = JSON(browseId)
            if let params { body["params"] = .string(params) }
        }
        return try await client.post("browse", body: .object(body))
    }

    /// ログイン必須の browse。ログインの案内だけが返ったら notLoggedIn。
    private func loggedInBrowse<T>(_ browseId: String, continuation: String?, parse: (JSON) -> T, params: String? = nil) async throws -> ApiResult<T> {
        guard client.isLoggedIn else { return .failed(.notLoggedIn) }
        let result = try await browse(browseId, continuation: continuation, params: params)
        guard result.isOk, let value = result.value else { return .failed(result.failure ?? .parse, status: result.status) }
        return MusicParsers.isSignInPrompt(value) ? .failed(.notLoggedIn) : .ok(parse(value))
    }
}

extension MusicRepository: PlainLyricsSource {
    public func plainLyrics(videoId: String) async throws -> ApiResult<SongLyrics.Plain?> { try await plainLyrics(videoId) }
}
