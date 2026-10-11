import Foundation
import Observation
import WaveCore

/// マイライブラリ（左の欄）。プレイリスト・アーティスト・アルバムを、届いたページから順に並べる。
@MainActor @Observable
final class LibraryState {
    var playlists: [PlaylistSummary] = []
    var artists: [ArtistSummary] = []
    var albums: [AlbumSummary] = []

    var isLoading = false
    var failure: String?
    /// 高く評価した曲（LM）のサムネイル。
    var likedThumbnail: String?
    /// 最近再生したアルバム・プレイリスト・アーティストの目印（並べ方の「最近」に使う。Android と同じ）。
    var recentKeys: [String] = []
    /// 高く評価した曲の ID（一覧の曲の行にバッジを付ける。Android と同じ）。読む前は空。
    private(set) var likedIds: Set<String> = []

    static func keyOf(_ item: HomeItem) -> String? {
        switch item {
        case .playlist(let p): "p-" + p.playlistId
        case .artist(let a): "ar-" + a.browseId
        case .album(let a): "al-" + a.browseId
        case .song: nil
        }
    }

    private var refreshTask: Task<Void, Never>?
    private var generation = 0

    /// 曲がどのプレイリストに入っているかの覚え（プレイリストに追加する画面）。
    @ObservationIgnored let membership = PlaylistMembership()

    /// 自分で編集できるプレイリスト（「プレイリストに追加」の行き先）。
    var editable: [PlaylistSummary] { playlists.filter { $0.editable && $0.playlistId != MusicRepository.likedSongs } }

    func isLiked(_ videoId: String) -> Bool { likedIds.contains(videoId) }

    /// 評価が変わった。曲と向きが分かれば、ID の集合をすぐ直す（全部は読み直さない）。
    func likedChanged(_ videoId: String? = nil, _ liked: Bool? = nil) {
        likedSongsTask = nil
        if let videoId, let liked {
            if liked { likedIds.insert(videoId) } else { likedIds.remove(videoId) }
        }
    }

    /// 高く評価した曲を全部（アーティストのページで、そのアーティストの曲を数えて絞るため）。
    /// 開くたびに読み直さないよう、評価が変わるまで持っておく。complete は最後まで読めたか。
    @ObservationIgnored private var likedSongsTask: Task<([Song], Bool), Never>?

    func likedSongs() async -> (songs: [Song], complete: Bool) {
        if let task = likedSongsTask { return await task.value }
        let task = Task<([Song], Bool), Never> { @MainActor in
            guard let result = try? await AppServices.music.fullPlaylist(MusicRepository.likedSongs), let page = result.value else {
                self.likedSongsTask = nil
                return ([], false)
            }
            AppPaths.log("liked songs loaded songs=\(page.songs.count) complete=\(page.continuation == nil)")
            return (page.songs, page.continuation == nil)
        }
        likedSongsTask = task
        return await task.value
    }

    /// 高く評価した曲のうち、そのアーティストのもの（ID が取れない行は名前で照らす。Android と同じ）。
    static func byArtist(_ songs: [Song], _ browseId: String?, _ name: String) -> [Song] {
        songs.filter { song in
            song.artists.contains { a in
                if let browseId, let id = a.browseId { return id == browseId }
                return a.name.caseInsensitiveCompare(name) == .orderedSame
            }
        }
    }

    func refresh() {
        refreshTask?.cancel()
        generation += 1
        let mine = generation
        playlists = []
        artists = []
        albums = []
        failure = nil
        likedSongsTask = nil
        likedIds = []
        membership.clear()
        guard AppServices.client.isLoggedIn else {
            isLoading = false
            return
        }
        isLoading = true
        refreshTask = Task { @MainActor in
            Task { @MainActor in
                let (songs, _) = await self.likedSongs()
                if self.generation == mine { self.likedIds = Set(songs.map(\.videoId)) }
            }
            Task { @MainActor in
                if let recent = try? await AppServices.music.recentActivity(), let items = recent.value, self.generation == mine {
                    self.recentKeys = items.compactMap(LibraryState.keyOf)
                }
            }
            let music = AppServices.music!
            async let p = music.libraryPlaylists(onPage: { page in
                DispatchQueue.main.async {
                    guard self.generation == mine else { return }
                    if let liked = page.first(where: { $0.playlistId == MusicRepository.likedSongs }) { self.likedThumbnail = liked.thumbnailUrl }
                    self.playlists = page.filter { $0.playlistId != MusicRepository.likedSongs }
                }
            })
            async let r = music.libraryArtists(onPage: { page in
                DispatchQueue.main.async { if self.generation == mine { self.artists = page } }
            })
            async let a = music.libraryAlbums(onPage: { page in
                DispatchQueue.main.async { if self.generation == mine { self.albums = page } }
            })
            let results = [try? await p.failure, try? await r.failure, try? await a.failure]
            // onPage の分を先に当ててから終わりにする
            await withCheckedContinuation { c in DispatchQueue.main.async { c.resume() } }
            guard self.generation == mine else { return }
            self.failure = results.compactMap { $0 ?? nil }.first.map { String(describing: $0) }
            self.isLoading = false
            AppPaths.log("library playlists=\(self.playlists.count) artists=\(self.artists.count) albums=\(self.albums.count) failure=\(self.failure ?? "nil")")
            AppServices.onLibraryLoaded()
        }
    }

    func addCreated(_ playlist: PlaylistSummary) { playlists.insert(playlist, at: 0) }
}
