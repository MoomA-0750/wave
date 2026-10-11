import Foundation
import Observation
import WaveCore

/// プレイリスト・アルバムのページを開くときの値。
struct CollectionRequest: Hashable {
    var isAlbum: Bool
    var id: String
    var title: String?
    var thumbnailUrl: String?
    var subtitle: String?
    var artist: ArtistFilter?
}

/// 一覧をそのアーティストの曲だけに絞る（高く評価した曲をアーティストのページから開くとき）。
struct ArtistFilter: Hashable {
    var browseId: String?
    var name: String
}

/// 中央の枠に出すページ。
enum Route: Hashable {
    case home
    case search(String?)
    case collection(CollectionRequest)
    case artist(String)
    case song(Song)
    case mood(MoodCategory)
    case history
    case settings
    case devices
    case lyrics
}

/// 画面の移り変わり（中央の枠）。戻る・進むは枠の履歴を使う（Windows の Frame と同じ）。
@MainActor @Observable
final class Nav {
    struct Entry: Hashable {
        let id = UUID()
        let route: Route
    }

    private(set) var current = Entry(route: .home)
    private var back: [Entry] = []
    private var forward: [Entry] = []

    var canGoBack: Bool { !back.isEmpty }
    var canGoForward: Bool { !forward.isEmpty }

    func go(_ route: Route) {
        back.append(current)
        forward.removeAll()
        current = Entry(route: route)
    }

    func goBack() {
        guard let previous = back.popLast() else { return }
        forward.append(current)
        current = previous
    }

    func goForward() {
        guard let next = forward.popLast() else { return }
        back.append(current)
        current = next
    }

    func home() { go(.home) }
    func search(_ query: String? = nil) { go(.search(query)) }

    func playlist(_ playlist: PlaylistSummary) {
        go(.collection(CollectionRequest(isAlbum: false, id: playlist.playlistId, title: playlist.title, thumbnailUrl: playlist.thumbnailUrl, subtitle: playlist.subtitle)))
    }

    func likedSongs() {
        go(.collection(CollectionRequest(isAlbum: false, id: MusicRepository.likedSongs, title: L.t("高く評価した曲", "Liked Songs"),
                                         thumbnailUrl: AppServices.library.likedThumbnail)))
    }

    /// 高く評価した曲のうち、そのアーティストのもの。
    func likedSongsBy(_ browseId: String?, _ name: String) {
        go(.collection(CollectionRequest(isAlbum: false, id: MusicRepository.likedSongs, title: L.t("高く評価した曲", "Liked Songs"),
                                         thumbnailUrl: AppServices.library.likedThumbnail, subtitle: name, artist: ArtistFilter(browseId: browseId, name: name))))
    }

    func album(_ browseId: String, _ summary: AlbumSummary? = nil) {
        go(.collection(CollectionRequest(isAlbum: true, id: browseId, title: summary?.title, thumbnailUrl: summary?.thumbnailUrl, subtitle: summary?.subtitle)))
    }

    func artist(_ browseId: String) { go(.artist(browseId)) }
    func song(_ song: Song) { go(.song(song)) }
    func mood(_ mood: MoodCategory) { go(.mood(mood)) }
    func history() { go(.history) }
    func settings() { go(.settings) }
    func devices() { go(.devices) }
    func lyrics() { go(.lyrics) }

    /// 棚のカードを押したとき。曲も、アルバム・プレイリストと同じく先にページを開く（Android と同じ。押してすぐ再生しない。再生はカードの再生ボタン）。
    func openCard(_ item: HomeItem) {
        if case .song(let s) = item { song(s) } else { open(item) }
    }

    /// 検索の候補・履歴などを選んだとき。曲は再生（Android の検索と同じ）、ほかはそのページへ。
    func open(_ item: HomeItem) {
        switch item {
        case .song(let s): AppServices.player.playSong(s)
        case .album(let a): album(a.browseId, a)
        case .playlist(let p): playlist(p)
        case .artist(let a): artist(a.browseId)
        }
    }

    /// カードの再生ボタン。ページを開かずに再生する。
    func play(_ item: HomeItem) {
        let player = AppServices.player!
        Task {
            switch item {
            case .song(let s):
                player.playSong(s)
            case .album(let a):
                if let album = (try? await AppServices.music.album(a.browseId))?.value {
                    player.playList(album.songs, start: 0, source: a.browseId)
                } else {
                    player.message = L.t("アルバムを読み込めませんでした", "Couldn't load the album")
                }
            case .playlist(let p):
                // 最初のページが届いたら鳴らし始め、残りはあとから足す
                var started = false
                let full = try? await AppServices.music.fullPlaylist(p.playlistId, onPage: { page in
                    DispatchQueue.main.async {
                        MainActor.assumeIsolated {
                            if started || page.songs.isEmpty { return }
                            started = true
                            player.playList(page.songs, start: 0, source: p.playlistId, shuffleOverride: player.shuffle ? true : nil)
                        }
                    }
                })
                await withCheckedContinuation { c in DispatchQueue.main.async { c.resume() } }
                if full?.isOk != true && !started {
                    player.message = L.t("プレイリストを読み込めませんでした", "Couldn't load the playlist")
                } else if let page = full?.value, player.sourceId == p.playlistId {
                    player.appendFrom(page.songs)
                }
            case .artist(let r):
                if let artist = (try? await AppServices.music.artist(r.browseId))?.value, !artist.topSongs.isEmpty {
                    let all = artist.allSongsPlaylistId.map { id in Task { try? await AppServices.music.fullPlaylist(id) } }
                    await player.playArtist(r.browseId, artist.topSongs, start: 0, shuffle: nil, allSongs: all)
                } else {
                    player.message = L.t("アーティストの曲を読み込めませんでした", "Couldn't load the artist's songs")
                }
            }
        }
    }
}

/// 画面の下に出す知らせ（Android のスナックバーと同じく、下の細いバーが消えるまでの残り時間を表す）。
@MainActor @Observable
final class ToastCenter {
    static let duration: TimeInterval = 4
    private(set) var text: String?
    private(set) var shownAt = Date()
    private var generation = 0

    func show(_ message: String) {
        text = message
        shownAt = Date()
        generation += 1
        let mine = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + ToastCenter.duration) {
            MainActor.assumeIsolated {
                if self.generation == mine { self.text = nil }
            }
        }
    }

    func close() {
        generation += 1
        text = nil
    }
}
